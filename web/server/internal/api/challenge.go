package api

import (
	"crypto/rand"
	"encoding/base64"
	"sync"
	"time"
)

const challengeTTL = 10 * time.Minute

// challenges tracks the nonces handed out for Key Attestation. The client bakes
// one into the key at generation time, so seeing it come back inside a valid
// certificate proves the key was made for us, not replayed from elsewhere.
type challenges struct {
	mu     sync.Mutex
	issued map[string]time.Time
}

func newChallenges() *challenges {
	return &challenges{issued: make(map[string]time.Time)}
}

func (c *challenges) issue(now time.Time) (string, error) {
	raw := make([]byte, 32)
	if _, err := rand.Read(raw); err != nil {
		return "", err
	}
	value := base64.StdEncoding.EncodeToString(raw)

	c.mu.Lock()
	c.issued[value] = now.Add(challengeTTL)
	c.mu.Unlock()
	return value, nil
}

// consume accepts a challenge once.
func (c *challenges) consume(value string, now time.Time) bool {
	c.mu.Lock()
	defer c.mu.Unlock()

	expires, ok := c.issued[value]
	if !ok || now.After(expires) {
		delete(c.issued, value)
		return false
	}
	delete(c.issued, value)
	return true
}

func (c *challenges) sweep(now time.Time) {
	c.mu.Lock()
	defer c.mu.Unlock()
	for value, expires := range c.issued {
		if now.After(expires) {
			delete(c.issued, value)
		}
	}
}
