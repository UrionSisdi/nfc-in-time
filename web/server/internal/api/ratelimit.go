package api

import (
	"sync"
	"time"
)

// limiter is a per-client token bucket. /v1/sync verifies signatures, so an
// unauthenticated flood is cheap to send and expensive to serve.
type limiter struct {
	rate  float64
	burst float64

	mu      sync.Mutex
	buckets map[string]*bucket
}

type bucket struct {
	tokens float64
	seen   time.Time
}

func newLimiter(perMinute int) *limiter {
	rate := float64(perMinute) / 60
	return &limiter{
		rate:    rate,
		burst:   float64(perMinute),
		buckets: make(map[string]*bucket),
	}
}

func (l *limiter) allow(key string, now time.Time) bool {
	l.mu.Lock()
	defer l.mu.Unlock()

	b, ok := l.buckets[key]
	if !ok {
		l.buckets[key] = &bucket{tokens: l.burst - 1, seen: now}
		return true
	}
	b.tokens = min(l.burst, b.tokens+now.Sub(b.seen).Seconds()*l.rate)
	b.seen = now
	if b.tokens < 1 {
		return false
	}
	b.tokens--
	return true
}

// sweep drops buckets that have refilled anyway, keeping the map from growing
// with every IP that ever called.
func (l *limiter) sweep(now time.Time, idle time.Duration) {
	l.mu.Lock()
	defer l.mu.Unlock()
	for k, b := range l.buckets {
		if now.Sub(b.seen) > idle {
			delete(l.buckets, k)
		}
	}
}
