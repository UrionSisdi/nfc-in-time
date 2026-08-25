// Package attest verifies Android Key Attestation: proof that the device key was
// generated inside a secure element, in an app signed with our release key.
package attest

import (
	"context"
	"crypto/ecdsa"
	"crypto/x509"
	"encoding/asn1"
	"encoding/base64"
	"errors"
	"fmt"
	"os"
	"time"
)

// attestationOID carries the KeyDescription in the leaf certificate.
var attestationOID = asn1.ObjectIdentifier{1, 3, 6, 1, 4, 1, 11129, 2, 1, 17}

// Security levels as defined by the Keymaster/KeyMint attestation schema.
const levelTEE = 1

// appIDTag is the attestationApplicationId entry of an AuthorizationList.
const appIDTag = 709

type Verifier struct {
	roots  *x509.CertPool
	status *statusList
	// appDigest is the release signing certificate digest, base64. Empty means
	// any signer is accepted, which is only sane in a test deployment.
	appDigest string
}

// Result is what the chain says about the device that produced the key.
type Result struct {
	// Challenge is the nonce the key was generated against, read out of the
	// certificate rather than taken from the caller.
	Challenge []byte
}

var ErrNoAttestation = errors.New("leaf certificate carries no attestation extension")

// NewVerifier loads the Google hardware attestation roots from a PEM bundle.
func NewVerifier(rootsPath, appDigest string) (*Verifier, error) {
	pemBytes, err := os.ReadFile(rootsPath)
	if err != nil {
		return nil, fmt.Errorf("read attestation roots: %w", err)
	}
	pool := x509.NewCertPool()
	if !pool.AppendCertsFromPEM(pemBytes) {
		return nil, fmt.Errorf("no certificates in %s", rootsPath)
	}
	return &Verifier{roots: pool, status: newStatusList(), appDigest: appDigest}, nil
}

// Verify checks the chain against the Google roots, confirms none of it has been
// revoked, confirms the leaf holds the key the client claims, and reads the
// attestation extension.
func (v *Verifier) Verify(ctx context.Context, chainDER [][]byte, pub *ecdsa.PublicKey, now time.Time) (Result, error) {
	var res Result
	if len(chainDER) == 0 {
		return res, errors.New("empty attestation chain")
	}

	certs := make([]*x509.Certificate, 0, len(chainDER))
	for i, der := range chainDER {
		c, err := x509.ParseCertificate(der)
		if err != nil {
			return res, fmt.Errorf("parse certificate %d: %w", i, err)
		}
		certs = append(certs, c)
	}

	leaf := certs[0]
	intermediates := x509.NewCertPool()
	for _, c := range certs[1:] {
		intermediates.AddCert(c)
	}
	// KeyUsage is left unconstrained: attestation leaves carry digitalSignature,
	// which no x509.ExtKeyUsage models.
	if _, err := leaf.Verify(x509.VerifyOptions{
		Roots:         v.roots,
		Intermediates: intermediates,
		CurrentTime:   now,
		KeyUsages:     []x509.ExtKeyUsage{x509.ExtKeyUsageAny},
	}); err != nil {
		return res, fmt.Errorf("chain does not reach a trusted root: %w", err)
	}

	if err := v.status.check(ctx, certs, now); err != nil {
		return res, err
	}

	certPub, ok := leaf.PublicKey.(*ecdsa.PublicKey)
	if !ok || !certPub.Equal(pub) {
		return res, errors.New("attested key differs from the submitted public key")
	}

	desc, err := keyDescription(leaf)
	if err != nil {
		return res, err
	}
	if desc.AttestationSecurityLevel < levelTEE {
		return res, fmt.Errorf("key lives in software, security level %d", desc.AttestationSecurityLevel)
	}
	if v.appDigest != "" {
		if err := v.checkApp(desc.SoftwareEnforced); err != nil {
			return res, err
		}
	}

	res.Challenge = desc.AttestationChallenge
	return res, nil
}

type keyDescriptionASN1 struct {
	AttestationVersion       int
	AttestationSecurityLevel asn1.Enumerated
	KeymasterVersion         int
	KeymasterSecurityLevel   asn1.Enumerated
	AttestationChallenge     []byte
	UniqueID                 []byte
	SoftwareEnforced         asn1.RawValue
	TeeEnforced              asn1.RawValue
}

func keyDescription(leaf *x509.Certificate) (keyDescriptionASN1, error) {
	var desc keyDescriptionASN1
	for _, ext := range leaf.Extensions {
		if !ext.Id.Equal(attestationOID) {
			continue
		}
		if _, err := asn1.Unmarshal(ext.Value, &desc); err != nil {
			return desc, fmt.Errorf("parse attestation extension: %w", err)
		}
		return desc, nil
	}
	return desc, ErrNoAttestation
}

type attestationAppID struct {
	Packages asn1.RawValue `asn1:"set"`
	Digests  [][]byte      `asn1:"set"`
}

// checkApp confirms the APK was signed with our release certificate. This is the
// check that makes sideloading safe: a repackaged build attests a different
// signer and is refused.
func (v *Verifier) checkApp(list asn1.RawValue) error {
	raw, err := taggedValue(list.Bytes, appIDTag)
	if err != nil {
		return err
	}
	var wrapped []byte
	if _, err := asn1.Unmarshal(raw, &wrapped); err != nil {
		return fmt.Errorf("parse attestationApplicationId: %w", err)
	}
	var app attestationAppID
	if _, err := asn1.Unmarshal(wrapped, &app); err != nil {
		return fmt.Errorf("parse application id: %w", err)
	}
	for _, d := range app.Digests {
		if base64.StdEncoding.EncodeToString(d) == v.appDigest {
			return nil
		}
	}
	return errors.New("app signing certificate is not ours")
}

// taggedValue walks the elements of an AuthorizationList and returns the content
// of the given context tag.
func taggedValue(list []byte, tag int) ([]byte, error) {
	rest := list
	for len(rest) > 0 {
		var v asn1.RawValue
		var err error
		rest, err = asn1.Unmarshal(rest, &v)
		if err != nil {
			return nil, fmt.Errorf("walk authorization list: %w", err)
		}
		if v.Class == asn1.ClassContextSpecific && v.Tag == tag {
			return v.Bytes, nil
		}
	}
	return nil, fmt.Errorf("authorization list has no tag %d", tag)
}
