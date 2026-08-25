package attest

import (
	"context"
	"crypto/x509"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

// statusURL publishes every attestation key Google has revoked. A chain that
// verifies cryptographically can still belong to a key that leaked, so this is
// the second half of the check, not an optional extra.
const statusURL = "https://android.googleapis.com/attestation/status"

const fallbackTTL = time.Hour

type statusEntry struct {
	Status string `json:"status"`
	Reason string `json:"reason"`
}

type statusList struct {
	url  string
	http *http.Client

	mu      sync.Mutex
	entries map[string]statusEntry
	expires time.Time
}

func newStatusList() *statusList {
	return &statusList{
		url:  statusURL,
		http: &http.Client{Timeout: 10 * time.Second},
	}
}

// check refuses the chain if any certificate in it is listed. Serial numbers are
// keyed as lowercase hex without leading zeros.
func (s *statusList) check(ctx context.Context, chain []*x509.Certificate, now time.Time) error {
	entries, err := s.load(ctx, now)
	if err != nil {
		return err
	}
	for _, cert := range chain {
		serial := strings.ToLower(cert.SerialNumber.Text(16))
		if e, listed := entries[serial]; listed {
			return fmt.Errorf("certificate %s is %s: %s", serial, strings.ToLower(e.Status), strings.ToLower(e.Reason))
		}
	}
	return nil
}

func (s *statusList) load(ctx context.Context, now time.Time) (map[string]statusEntry, error) {
	s.mu.Lock()
	entries, expires := s.entries, s.expires
	s.mu.Unlock()

	if entries != nil && now.Before(expires) {
		return entries, nil
	}

	fresh, ttl, err := s.fetch(ctx)
	if err != nil {
		// A stale list still names every key revoked up to the last successful
		// fetch, which beats trusting all of them while Google is unreachable.
		// With nothing cached there is nothing to fall back on, and refusing a
		// registration is the safe half of that choice.
		if entries != nil {
			return entries, nil
		}
		return nil, err
	}

	s.mu.Lock()
	s.entries, s.expires = fresh, now.Add(ttl)
	s.mu.Unlock()
	return fresh, nil
}

func (s *statusList) fetch(ctx context.Context) (map[string]statusEntry, time.Duration, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, s.url, nil)
	if err != nil {
		return nil, 0, err
	}
	resp, err := s.http.Do(req)
	if err != nil {
		return nil, 0, fmt.Errorf("fetch revocation list: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, 0, fmt.Errorf("revocation list returned %d", resp.StatusCode)
	}

	var doc struct {
		Entries map[string]statusEntry `json:"entries"`
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, 8<<20)).Decode(&doc); err != nil {
		return nil, 0, fmt.Errorf("decode revocation list: %w", err)
	}
	if doc.Entries == nil {
		doc.Entries = map[string]statusEntry{}
	}
	return doc.Entries, cacheTTL(resp.Header.Get("Cache-Control")), nil
}

func cacheTTL(header string) time.Duration {
	for _, part := range strings.Split(header, ",") {
		value, ok := strings.CutPrefix(strings.TrimSpace(part), "max-age=")
		if !ok {
			continue
		}
		seconds, err := strconv.Atoi(value)
		if err != nil || seconds <= 0 {
			break
		}
		return time.Duration(seconds) * time.Second
	}
	return fallbackTTL
}
