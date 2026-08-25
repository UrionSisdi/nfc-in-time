package attest

import (
	"context"
	"crypto/x509"
	"math/big"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestStatusListRefusesARevokedSerial(t *testing.T) {
	var hits int
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits++
		w.Header().Set("Cache-Control", "public, max-age=86400")
		w.Write([]byte(`{"entries":{"6681152659205225093":{"status":"REVOKED","reason":"KEY_COMPROMISE"}}}`))
	}))
	defer srv.Close()

	s := newStatusList()
	s.url = srv.URL
	now := time.Now()
	ctx := context.Background()

	revoked, _ := new(big.Int).SetString("6681152659205225093", 16)
	chain := []*x509.Certificate{{SerialNumber: revoked}}
	if err := s.check(ctx, chain, now); err == nil {
		t.Fatal("a revoked certificate was accepted")
	}

	clean := []*x509.Certificate{{SerialNumber: big.NewInt(0x1234)}}
	if err := s.check(ctx, clean, now); err != nil {
		t.Fatalf("an unlisted certificate was refused: %v", err)
	}
	if hits != 1 {
		t.Fatalf("list fetched %d times, want 1 within the cache window", hits)
	}

	if err := s.check(ctx, clean, now.Add(25*time.Hour)); err != nil {
		t.Fatalf("refetch after expiry failed: %v", err)
	}
	if hits != 2 {
		t.Fatalf("list fetched %d times, want a refetch after max-age", hits)
	}
}

func TestStatusListFailsClosedWithoutACachedCopy(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "nope", http.StatusInternalServerError)
	}))
	defer srv.Close()

	s := newStatusList()
	s.url = srv.URL
	chain := []*x509.Certificate{{SerialNumber: big.NewInt(1)}}
	if err := s.check(context.Background(), chain, time.Now()); err == nil {
		t.Fatal("verification passed while the revocation list was unreachable")
	}
}

func TestCacheTTL(t *testing.T) {
	cases := map[string]time.Duration{
		"public, max-age=86400": 24 * time.Hour,
		"max-age=0":             fallbackTTL,
		"no-store":              fallbackTTL,
		"":                      fallbackTTL,
	}
	for header, want := range cases {
		if got := cacheTTL(header); got != want {
			t.Errorf("cacheTTL(%q) = %v, want %v", header, got, want)
		}
	}
}
