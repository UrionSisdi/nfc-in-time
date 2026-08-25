package api

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"testing"

	"github.com/urionsisdi/nfc-in-time/web/server/internal/keys"
)

func TestTransferMessageIsStable(t *testing.T) {
	got := string(transferMessage(transferPayload{
		Nonce:    "8f2c",
		From:     "42",
		To:       "43",
		Amount:   3600,
		SignedAt: 1787270512,
		PrevHash: "deadbeef",
	}))
	want := "nfcit/transfer/v1\n8f2c\n42\n43\n3600\n1787270512\ndeadbeef"
	if got != want {
		t.Fatalf("message changed, every transfer in flight would break:\ngot  %q\nwant %q", got, want)
	}
}

func TestSignatureRoundTrip(t *testing.T) {
	priv, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	der, err := x509.MarshalPKIXPublicKey(&priv.PublicKey)
	if err != nil {
		t.Fatal(err)
	}
	pub, id, err := keys.ParseDER(der)
	if err != nil {
		t.Fatal(err)
	}
	if id != keys.IDOf(der) {
		t.Fatalf("key id is not derived from the key itself")
	}

	msg := transferMessage(transferPayload{Nonce: "n", From: "1", To: "2", Amount: 60})
	digest := sha256.Sum256(msg)
	sig, err := ecdsa.SignASN1(rand.Reader, priv, digest[:])
	if err != nil {
		t.Fatal(err)
	}
	if err := keys.Verify(pub, msg, base64.StdEncoding.EncodeToString(sig)); err != nil {
		t.Fatalf("valid signature rejected: %v", err)
	}

	msg[0] ^= 0xff
	if err := keys.Verify(pub, msg, base64.StdEncoding.EncodeToString(sig)); err == nil {
		t.Fatal("tampered message accepted")
	}
}
