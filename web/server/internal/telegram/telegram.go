// Package telegram verifies the ID token a signed-in app presents. The
// authorization flow itself runs in the app: the official native SDK hands it an
// id_token directly, so the server holds no client secret and talks to Telegram
// only to fetch the signing keys.
package telegram

import (
	"context"
	"crypto"
	"crypto/rsa"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math/big"
	"net/http"
	"strings"
	"sync"
	"time"
)

// Identity is the part of the ID token the game cares about.
type Identity struct {
	TgID string
	Name string
}

// Verifier turns an ID token into an identity.
type Verifier interface {
	Identify(ctx context.Context, idToken string) (Identity, error)
}

type Client struct {
	clientID string
	issuer   string
	jwksURL  string

	http *http.Client

	mu       sync.Mutex
	jwks     map[string]*rsa.PublicKey
	jwksTime time.Time
}

const jwksTTL = time.Hour

func New(clientID, issuer, jwksURL string) *Client {
	return &Client{
		clientID: clientID,
		issuer:   issuer,
		jwksURL:  jwksURL,
		http:     &http.Client{Timeout: 10 * time.Second},
	}
}

type claims struct {
	Issuer     string          `json:"iss"`
	Subject    string          `json:"sub"`
	Audience   json.RawMessage `json:"aud"`
	Expires    int64           `json:"exp"`
	Name       string          `json:"name"`
	GivenName  string          `json:"given_name"`
	FamilyName string          `json:"family_name"`
	Username   string          `json:"username"`
}

// displayName prefers the full name over the handle: the board reads as a room
// of people, and a first and last name says more there than a username.
func (c claims) displayName() string {
	if c.Name != "" {
		return c.Name
	}
	if full := strings.TrimSpace(c.GivenName + " " + c.FamilyName); full != "" {
		return full
	}
	return c.Username
}

func (c *Client) Identify(ctx context.Context, idToken string) (Identity, error) {
	parts := strings.Split(idToken, ".")
	if len(parts) != 3 {
		return Identity{}, errors.New("id_token is not a three-part JWT")
	}

	headerJSON, err := base64.RawURLEncoding.DecodeString(parts[0])
	if err != nil {
		return Identity{}, fmt.Errorf("decode header: %w", err)
	}
	var header struct {
		Alg string `json:"alg"`
		Kid string `json:"kid"`
	}
	if err := json.Unmarshal(headerJSON, &header); err != nil {
		return Identity{}, fmt.Errorf("decode header: %w", err)
	}
	if header.Alg != "RS256" {
		return Identity{}, fmt.Errorf("unexpected id_token algorithm %q", header.Alg)
	}

	key, err := c.key(ctx, header.Kid)
	if err != nil {
		return Identity{}, err
	}
	sig, err := base64.RawURLEncoding.DecodeString(parts[2])
	if err != nil {
		return Identity{}, fmt.Errorf("decode signature: %w", err)
	}
	digest := sha256.Sum256([]byte(parts[0] + "." + parts[1]))
	if err := rsa.VerifyPKCS1v15(key, crypto.SHA256, digest[:], sig); err != nil {
		return Identity{}, fmt.Errorf("id_token signature: %w", err)
	}

	payload, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return Identity{}, fmt.Errorf("decode claims: %w", err)
	}
	var cl claims
	if err := json.Unmarshal(payload, &cl); err != nil {
		return Identity{}, fmt.Errorf("decode claims: %w", err)
	}
	if cl.Issuer != c.issuer {
		return Identity{}, fmt.Errorf("id_token issued by %q, want %q", cl.Issuer, c.issuer)
	}
	if cl.Subject == "" {
		return Identity{}, errors.New("id_token carries no subject")
	}
	if time.Now().Unix() >= cl.Expires {
		return Identity{}, errors.New("id_token has expired")
	}
	// Without this an id_token minted for any other Telegram client would pass.
	if !audienceHas(cl.Audience, c.clientID) {
		return Identity{}, errors.New("id_token is for another client")
	}

	return Identity{TgID: cl.Subject, Name: cl.displayName()}, nil
}

func audienceHas(raw json.RawMessage, want string) bool {
	var one string
	if err := json.Unmarshal(raw, &one); err == nil {
		return one == want
	}
	var many []string
	if err := json.Unmarshal(raw, &many); err != nil {
		return false
	}
	for _, a := range many {
		if a == want {
			return true
		}
	}
	return false
}

func (c *Client) key(ctx context.Context, kid string) (*rsa.PublicKey, error) {
	c.mu.Lock()
	key, ok := c.jwks[kid]
	fresh := time.Since(c.jwksTime) < jwksTTL
	c.mu.Unlock()
	if ok && fresh {
		return key, nil
	}

	set, err := c.fetchJWKS(ctx)
	if err != nil {
		// A cached key beats refusing every sign-in while Telegram is
		// unreachable; keys rotate far slower than outages last.
		if ok {
			return key, nil
		}
		return nil, err
	}

	c.mu.Lock()
	c.jwks, c.jwksTime = set, time.Now()
	c.mu.Unlock()

	key, ok = set[kid]
	if !ok {
		return nil, fmt.Errorf("jwks has no key %q", kid)
	}
	return key, nil
}

func (c *Client) fetchJWKS(ctx context.Context) (map[string]*rsa.PublicKey, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.jwksURL, nil)
	if err != nil {
		return nil, err
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return nil, fmt.Errorf("fetch jwks: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("jwks endpoint returned %d", resp.StatusCode)
	}

	var doc struct {
		Keys []struct {
			Kid string `json:"kid"`
			Kty string `json:"kty"`
			N   string `json:"n"`
			E   string `json:"e"`
		} `json:"keys"`
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(&doc); err != nil {
		return nil, fmt.Errorf("decode jwks: %w", err)
	}

	out := make(map[string]*rsa.PublicKey, len(doc.Keys))
	for _, k := range doc.Keys {
		if k.Kty != "RSA" {
			continue
		}
		n, err := base64.RawURLEncoding.DecodeString(k.N)
		if err != nil {
			return nil, fmt.Errorf("jwks key %q modulus: %w", k.Kid, err)
		}
		e, err := base64.RawURLEncoding.DecodeString(k.E)
		if err != nil {
			return nil, fmt.Errorf("jwks key %q exponent: %w", k.Kid, err)
		}
		if len(e) == 0 || len(e) > 8 {
			return nil, fmt.Errorf("jwks key %q has an unusable exponent", k.Kid)
		}
		padded := make([]byte, 8)
		copy(padded[8-len(e):], e)
		out[k.Kid] = &rsa.PublicKey{
			N: new(big.Int).SetBytes(n),
			E: int(binary.BigEndian.Uint64(padded)),
		}
	}
	if len(out) == 0 {
		return nil, errors.New("jwks carries no RSA keys")
	}
	return out, nil
}

// Dev accepts an unverified Telegram id. It exists so the stack runs locally
// without Telegram at all, and must never be enabled in production.
type Dev struct{}

func (Dev) Identify(_ context.Context, idToken string) (Identity, error) {
	tgID, name, _ := strings.Cut(idToken, ":")
	if tgID == "" {
		return Identity{}, errors.New("dev auth expects an id_token of the form tg_id[:name]")
	}
	if name == "" {
		name = "player-" + tgID
	}
	return Identity{TgID: tgID, Name: name}, nil
}
