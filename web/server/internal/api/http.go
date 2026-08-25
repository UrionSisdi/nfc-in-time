package api

import (
	"encoding/json"
	"io"
	"log/slog"
	"net"
	"net/http"
	"strings"
)

const maxBody = 1 << 20

type errorBody struct {
	Error string `json:"error"`
	Code  string `json:"code,omitempty"`
}

// codeKeyUnknown says the caller's installation key is not one the server will
// accept again. The app answers by forgetting the key and asking the player to
// sign in; every other failure leaves the installation alone, so the code is
// only ever set where re-registering is the actual remedy.
const codeKeyUnknown = "key_unknown"

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("content-type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	if err := json.NewEncoder(w).Encode(v); err != nil {
		slog.Debug("write response", "error", err)
	}
}

func writeError(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, errorBody{Error: msg})
}

func writeErrorCode(w http.ResponseWriter, status int, code, msg string) {
	writeJSON(w, status, errorBody{Error: msg, Code: code})
}

// readBody returns the raw bytes as well, because /v1/sync verifies a signature
// over exactly what was sent rather than over a re-encoding of it.
func readBody(w http.ResponseWriter, r *http.Request, v any) ([]byte, bool) {
	raw, err := io.ReadAll(io.LimitReader(r.Body, maxBody))
	if err != nil {
		writeError(w, http.StatusBadRequest, "cannot read request body")
		return nil, false
	}
	if err := json.Unmarshal(raw, v); err != nil {
		writeError(w, http.StatusBadRequest, "malformed json")
		return nil, false
	}
	return raw, true
}

func clientIP(r *http.Request, trustProxy bool) string {
	if trustProxy {
		if fwd := r.Header.Get("X-Forwarded-For"); fwd != "" {
			if first, _, ok := strings.Cut(fwd, ","); ok {
				return strings.TrimSpace(first)
			}
			return strings.TrimSpace(fwd)
		}
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}
