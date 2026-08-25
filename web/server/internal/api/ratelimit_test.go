package api

import (
	"testing"
	"time"
)

func TestLimiterRefills(t *testing.T) {
	l := newLimiter(60)
	now := time.Unix(1787270512, 0)

	for i := 0; i < 60; i++ {
		if !l.allow("1.2.3.4", now) {
			t.Fatalf("burst exhausted after %d calls, want 60", i)
		}
	}
	if l.allow("1.2.3.4", now) {
		t.Fatal("limiter let a 61st call through")
	}
	if !l.allow("5.6.7.8", now) {
		t.Fatal("limiter is shared between clients")
	}
	if !l.allow("1.2.3.4", now.Add(2*time.Second)) {
		t.Fatal("bucket did not refill")
	}

	l.sweep(now.Add(2*time.Hour), time.Hour)
	if len(l.buckets) != 0 {
		t.Fatalf("sweep left %d buckets", len(l.buckets))
	}
}
