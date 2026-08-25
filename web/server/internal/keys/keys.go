// Package keys handles the device identity: an ECDSA P-256 key pair generated in
// the Android Keystore. The server only ever sees the public half, in DER SPKI.
package keys

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"errors"
	"fmt"
)

// ID is the base64url SHA-256 of the DER SPKI. It names a device installation
// without the caller having to send the whole key on every request.
type ID string

var ErrBadSignature = errors.New("signature does not verify")

// Parse decodes a base64 DER SPKI public key and returns it with its ID.
func Parse(b64 string) (*ecdsa.PublicKey, ID, error) {
	der, err := base64.StdEncoding.DecodeString(b64)
	if err != nil {
		return nil, "", fmt.Errorf("decode public key: %w", err)
	}
	return ParseDER(der)
}

func ParseDER(der []byte) (*ecdsa.PublicKey, ID, error) {
	pub, err := x509.ParsePKIXPublicKey(der)
	if err != nil {
		return nil, "", fmt.Errorf("parse public key: %w", err)
	}
	ec, ok := pub.(*ecdsa.PublicKey)
	if !ok {
		return nil, "", fmt.Errorf("public key is %T, want ECDSA", pub)
	}
	if ec.Curve != elliptic.P256() {
		return nil, "", errors.New("public key is not on P-256")
	}
	return ec, IDOf(der), nil
}

func IDOf(der []byte) ID {
	sum := sha256.Sum256(der)
	return ID(base64.RawURLEncoding.EncodeToString(sum[:]))
}

// Verify checks an ASN.1 ECDSA signature, base64, over the SHA-256 of msg.
func Verify(pub *ecdsa.PublicKey, msg []byte, sigB64 string) error {
	sig, err := base64.StdEncoding.DecodeString(sigB64)
	if err != nil {
		return fmt.Errorf("decode signature: %w", err)
	}
	digest := sha256.Sum256(msg)
	if !ecdsa.VerifyASN1(pub, digest[:], sig) {
		return ErrBadSignature
	}
	return nil
}
