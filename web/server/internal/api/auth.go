package api

import (
	"encoding/base64"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/urionsisdi/nfc-in-time/web/server/internal/config"
	"github.com/urionsisdi/nfc-in-time/web/server/internal/keys"
	"github.com/urionsisdi/nfc-in-time/web/server/internal/store"
)

type authRequest struct {
	IDToken     string   `json:"id_token"`
	PublicKey   string   `json:"public_key"`
	Attestation []string `json:"attestation"`
	Name        string   `json:"name"`
	Listed      *bool    `json:"listed"`
}

type playerState struct {
	TgID           string                 `json:"tg_id"`
	KeyID          string                 `json:"key_id"`
	Name           string                 `json:"name"`
	BalanceSeconds int64                  `json:"balance_seconds"`
	Alive          bool                   `json:"alive"`
	Listed         bool                   `json:"listed"`
	BornAt         int64                  `json:"born_at"`
	DiedAt         *int64                 `json:"died_at,omitempty"`
	ServerTime     int64                  `json:"server_time"`
	Transfers      []store.TransferResult `json:"transfers,omitempty"`
}

func (s *Server) handleChallenge(w http.ResponseWriter, r *http.Request) {
	if !s.limiter.allow(clientIP(r, s.cfg.TrustProxy), time.Now()) {
		writeError(w, http.StatusTooManyRequests, "slow down")
		return
	}
	value, err := s.challenges.issue(time.Now())
	if err != nil {
		s.log.Error("issue challenge", "error", err)
		writeError(w, http.StatusInternalServerError, "cannot issue a challenge")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"challenge":  value,
		"expires_in": int(challengeTTL.Seconds()),
	})
}

func (s *Server) handleAuth(w http.ResponseWriter, r *http.Request) {
	now := time.Now()
	if !s.limiter.allow(clientIP(r, s.cfg.TrustProxy), now) {
		writeError(w, http.StatusTooManyRequests, "slow down")
		return
	}

	var req authRequest
	if _, ok := readBody(w, r, &req); !ok {
		return
	}
	if req.IDToken == "" {
		writeError(w, http.StatusBadRequest, "id_token is required")
		return
	}

	pub, keyID, err := keys.Parse(req.PublicKey)
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}

	if s.cfg.AttestMode == config.AttestChain {
		chain, err := decodeChain(req.Attestation)
		if err != nil {
			writeError(w, http.StatusBadRequest, err.Error())
			return
		}
		// The challenge is read out of the certificate and only then matched
		// against what we handed out: the client never gets to name it.
		res, err := s.attester.Verify(r.Context(), chain, pub, now)
		if err != nil {
			s.log.Warn("attestation rejected", "error", err)
			writeError(w, http.StatusForbidden, "key attestation rejected")
			return
		}
		if !s.challenges.consume(base64.StdEncoding.EncodeToString(res.Challenge), now) {
			writeError(w, http.StatusForbidden, "attestation challenge is unknown or expired")
			return
		}
	}

	identity, err := s.auth.Identify(r.Context(), req.IDToken)
	if err != nil {
		s.log.Warn("id_token rejected", "error", err)
		writeError(w, http.StatusUnauthorized, "telegram sign-in failed")
		return
	}

	name, err := chooseName(req.Name, identity.Name, identity.TgID)
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}

	pubDER, err := base64.StdEncoding.DecodeString(req.PublicKey)
	if err != nil {
		writeError(w, http.StatusBadRequest, "public key is not base64")
		return
	}

	unix := now.Unix()
	player, created, err := s.store.Register(r.Context(), identity.TgID, name, pubDER, keyID, s.cfg.GenesisSeconds, unix)
	if err != nil {
		s.log.Error("register player", "error", err)
		writeError(w, http.StatusInternalServerError, "cannot register")
		return
	}
	if !created || req.Listed != nil {
		if err := s.store.UpdateProfile(r.Context(), identity.TgID, name, req.Listed, unix); err != nil {
			s.log.Error("update profile", "error", err)
		}
		if player, err = s.store.Player(r.Context(), identity.TgID); err != nil {
			s.log.Error("reload player", "error", err)
			writeError(w, http.StatusInternalServerError, "cannot register")
			return
		}
	}

	status := http.StatusOK
	if created {
		status = http.StatusCreated
	}
	writeJSON(w, status, state(player, keyID, unix, nil))
}

// chooseName prefers what the player typed, falls back to the Telegram handle
// and then to something unique. Only the player's own choice is rejected on bad
// input: refusing to register somebody because Telegram gave them a name we do
// not like would be absurd.
func chooseName(chosen, telegram, tgID string) (string, error) {
	if strings.TrimSpace(chosen) != "" {
		return cleanName(chosen)
	}
	if name, err := cleanName(telegram); err == nil {
		return name, nil
	}
	return "player-" + tgID, nil
}

func decodeChain(chain []string) ([][]byte, error) {
	if len(chain) == 0 {
		return nil, errors.New("attestation chain is required")
	}
	out := make([][]byte, 0, len(chain))
	for i, c := range chain {
		der, err := base64.StdEncoding.DecodeString(c)
		if err != nil {
			return nil, fmt.Errorf("attestation certificate %d is not base64", i)
		}
		out = append(out, der)
	}
	return out, nil
}

func state(p store.Player, keyID keys.ID, now int64, transfers []store.TransferResult) playerState {
	return playerState{
		TgID:           p.TgID,
		KeyID:          string(keyID),
		Name:           p.Name,
		BalanceSeconds: p.BalanceAt(now),
		Alive:          p.Alive(now),
		Listed:         p.Listed,
		BornAt:         p.BornAt,
		DiedAt:         p.DiedAt,
		ServerTime:     now,
		Transfers:      transfers,
	}
}
