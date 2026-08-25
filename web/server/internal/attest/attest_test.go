package attest

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"errors"
	"math/big"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestVerifyRejectsAnUntrustedRoot(t *testing.T) {
	trusted, trustedKey := selfSigned(t, "trusted root")
	rogue, rogueKey := selfSigned(t, "rogue root")

	roots := filepath.Join(t.TempDir(), "roots.pem")
	if err := os.WriteFile(roots, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: trusted.Raw}), 0o600); err != nil {
		t.Fatal(err)
	}
	v, err := NewVerifier(roots, "")
	if err != nil {
		t.Fatal(err)
	}

	now := time.Now()
	ctx := context.Background()
	v.status.entries, v.status.expires = map[string]statusEntry{}, now.Add(time.Hour)

	// A leaf under the trusted root gets far enough to look for the attestation
	// extension, which a hand-made certificate does not carry.
	leaf, leafKey := issue(t, trusted, trustedKey)
	if _, err := v.Verify(ctx, [][]byte{leaf.Raw, trusted.Raw}, &leafKey.PublicKey, now); !errors.Is(err, ErrNoAttestation) {
		t.Fatalf("want ErrNoAttestation, got %v", err)
	}

	// The same certificate under somebody else's root must not pass at all.
	rogueLeaf, rogueLeafKey := issue(t, rogue, rogueKey)
	if _, err := v.Verify(ctx, [][]byte{rogueLeaf.Raw, rogue.Raw}, &rogueLeafKey.PublicKey, now); err == nil {
		t.Fatal("a chain rooted outside the bundle was accepted")
	}

	// A leaf that does not hold the submitted key is a replayed chain.
	other, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := v.Verify(ctx, [][]byte{leaf.Raw, trusted.Raw}, &other.PublicKey, now); err == nil {
		t.Fatal("a chain attesting a different key was accepted")
	}
}

func selfSigned(t *testing.T, cn string) (*x509.Certificate, *ecdsa.PrivateKey) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	tmpl := &x509.Certificate{
		SerialNumber:          big.NewInt(1),
		Subject:               pkix.Name{CommonName: cn},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().Add(time.Hour),
		IsCA:                  true,
		BasicConstraintsValid: true,
		KeyUsage:              x509.KeyUsageCertSign,
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	cert, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	return cert, key
}

func issue(t *testing.T, parent *x509.Certificate, parentKey *ecdsa.PrivateKey) (*x509.Certificate, *ecdsa.PrivateKey) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(2),
		Subject:      pkix.Name{CommonName: "device key"},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(time.Hour),
		KeyUsage:     x509.KeyUsageDigitalSignature,
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, parent, &key.PublicKey, parentKey)
	if err != nil {
		t.Fatal(err)
	}
	cert, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	return cert, key
}
