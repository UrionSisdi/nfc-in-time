package api

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/urionsisdi/nfc-in-time/web/server/internal/keys"
	"github.com/urionsisdi/nfc-in-time/web/server/internal/store"
)

const maxTransfersPerSync = 256

type syncRequest struct {
	IssuedAt  int64             `json:"issued_at"`
	Name      string            `json:"name"`
	Listed    *bool             `json:"listed"`
	Transfers []transferPayload `json:"transfers"`
}

type transferPayload struct {
	Nonce    string `json:"nonce"`
	From     string `json:"from"`
	To       string `json:"to"`
	Amount   int64  `json:"amount"`
	PrevHash string `json:"prev_hash"`
	SignedAt int64  `json:"signed_at"`
	FromSig  string `json:"from_sig"`
	ToSig    string `json:"to_sig"`
}

// handleSync applies the transfers a device collected offline and answers with
// the authoritative balance. The device's own number never enters the
// calculation: the server tells it what it has, not the other way round.
func (s *Server) handleSync(w http.ResponseWriter, r *http.Request) {
	now := time.Now()
	if !s.limiter.allow(clientIP(r, s.cfg.TrustProxy), now) {
		writeError(w, http.StatusTooManyRequests, "slow down")
		return
	}

	var req syncRequest
	raw, ok := readBody(w, r, &req)
	if !ok {
		return
	}
	if len(req.Transfers) > maxTransfersPerSync {
		writeError(w, http.StatusRequestEntityTooLarge, "too many transfers in one sync")
		return
	}

	device, err := s.store.Device(r.Context(), keys.ID(r.Header.Get(headerKeyID)))
	switch {
	case errors.Is(err, store.ErrNoDevice):
		writeErrorCode(w, http.StatusUnauthorized, codeKeyUnknown, "unknown device key")
		return
	case errors.Is(err, store.ErrRevoked):
		writeErrorCode(w, http.StatusUnauthorized, codeKeyUnknown, "device key revoked, sign in again")
		return
	case err != nil:
		s.log.Error("look up device", "error", err)
		writeError(w, http.StatusInternalServerError, "cannot sync")
		return
	}

	pub, _, err := keys.ParseDER(device.PublicKey)
	if err != nil {
		s.log.Error("stored device key is unusable", "key_id", device.KeyID, "error", err)
		writeError(w, http.StatusInternalServerError, "cannot sync")
		return
	}
	if err := keys.Verify(pub, raw, r.Header.Get(headerSignature)); err != nil {
		writeErrorCode(w, http.StatusUnauthorized, codeKeyUnknown, "body signature does not verify")
		return
	}
	if skew := now.Sub(time.Unix(req.IssuedAt, 0)); skew > s.cfg.MaxClockSkew || skew < -s.cfg.MaxClockSkew {
		writeError(w, http.StatusUnauthorized, "request timestamp is too far from server time")
		return
	}

	accepted := make([]store.Transfer, 0, len(req.Transfers))
	results := make([]store.TransferResult, 0, len(req.Transfers))
	for _, t := range req.Transfers {
		if err := s.checkTransfer(r.Context(), device.TgID, t); err != nil {
			s.log.Warn("transfer rejected", "nonce", t.Nonce, "reporter", device.TgID, "error", err)
			results = append(results, store.TransferResult{Nonce: t.Nonce})
			continue
		}
		accepted = append(accepted, store.Transfer{
			Nonce:    t.Nonce,
			FromTgID: t.From,
			ToTgID:   t.To,
			Amount:   t.Amount,
			PrevHash: t.PrevHash,
			SignedAt: t.SignedAt,
		})
	}

	unix := now.Unix()
	applied, err := s.store.ApplyTransfers(r.Context(), accepted, device.TgID, unix)
	if err != nil {
		s.log.Error("apply transfers", "error", err)
		writeError(w, http.StatusInternalServerError, "cannot apply transfers")
		return
	}
	results = append(results, applied...)

	name := ""
	if strings.TrimSpace(req.Name) != "" {
		if name, err = cleanName(req.Name); err != nil {
			writeError(w, http.StatusBadRequest, err.Error())
			return
		}
	}
	if err := s.store.UpdateProfile(r.Context(), device.TgID, name, req.Listed, unix); err != nil {
		s.log.Error("update profile", "error", err)
	}

	player, err := s.store.Player(r.Context(), device.TgID)
	if err != nil {
		s.log.Error("load player", "error", err)
		writeError(w, http.StatusInternalServerError, "cannot sync")
		return
	}
	writeJSON(w, http.StatusOK, state(player, device.KeyID, unix, results))
}

// checkTransfer rejects anything the ledger should not even see: a record the
// reporter was not part of, or one missing a signature from either side.
func (s *Server) checkTransfer(ctx context.Context, reporter string, t transferPayload) error {
	switch {
	case t.Nonce == "":
		return errors.New("nonce is empty")
	case t.From == t.To:
		return errors.New("transfer points at a single player")
	case t.Amount <= 0:
		return errors.New("amount is not positive")
	case reporter != t.From && reporter != t.To:
		return errors.New("reporter is not a party to this transfer")
	}

	msg := transferMessage(t)
	if err := s.verifyParty(ctx, t.From, msg, t.FromSig); err != nil {
		return fmt.Errorf("donor signature: %w", err)
	}
	if err := s.verifyParty(ctx, t.To, msg, t.ToSig); err != nil {
		return fmt.Errorf("recipient signature: %w", err)
	}
	return nil
}

func (s *Server) verifyParty(ctx context.Context, tgID string, msg []byte, sig string) error {
	if sig == "" {
		return errors.New("missing")
	}
	registered, err := s.store.PlayerKeys(ctx, tgID)
	if err != nil {
		return err
	}
	if len(registered) == 0 {
		return fmt.Errorf("player %s has no registered key", tgID)
	}
	for _, der := range registered {
		pub, _, err := keys.ParseDER(der)
		if err != nil {
			continue
		}
		if err := keys.Verify(pub, msg, sig); err == nil {
			return nil
		}
	}
	return keys.ErrBadSignature
}
