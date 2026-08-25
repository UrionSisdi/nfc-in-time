// Package api serves the public board, the device sync endpoint and the landing
// page itself.
package api

import (
	"context"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"sync/atomic"
	"time"

	"github.com/urionsisdi/nfc-in-time/web/server/internal/attest"
	"github.com/urionsisdi/nfc-in-time/web/server/internal/config"
	"github.com/urionsisdi/nfc-in-time/web/server/internal/store"
	"github.com/urionsisdi/nfc-in-time/web/server/internal/telegram"
)

const topSize = 20

type Server struct {
	cfg      config.Config
	store    *store.Store
	auth     telegram.Verifier
	attester *attest.Verifier
	limiter  *limiter

	challenges *challenges
	board      atomic.Pointer[snapshot]
	log        *slog.Logger
}

type snapshot struct {
	board store.Board
	top   []store.TopEntry
}

func New(cfg config.Config, st *store.Store, auth telegram.Verifier, attester *attest.Verifier, log *slog.Logger) *Server {
	s := &Server{
		cfg:        cfg,
		store:      st,
		auth:       auth,
		attester:   attester,
		limiter:    newLimiter(cfg.SyncRatePerMin),
		challenges: newChallenges(),
		log:        log,
	}
	s.board.Store(&snapshot{board: store.Board{AsOf: time.Now().Unix()}})
	return s
}

func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", s.handleHealth)
	mux.HandleFunc("GET /v1/board", s.handleBoard)
	mux.HandleFunc("GET /v1/top", s.handleTop)
	mux.HandleFunc("GET /v1/version", s.handleVersion)
	mux.HandleFunc("POST /v1/auth/challenge", s.handleChallenge)
	mux.HandleFunc("POST /v1/auth/telegram", s.handleAuth)
	mux.HandleFunc("POST /v1/sync", s.handleSync)

	if s.cfg.StaticDir != "" {
		mux.Handle("GET /", s.static())
	}
	return logRequests(s.log, mux)
}

// static serves the landing page, falling back to index.html so a deep link is
// not a 404.
func (s *Server) static() http.Handler {
	dir := http.Dir(s.cfg.StaticDir)
	files := http.FileServer(dir)
	index := filepath.Join(s.cfg.StaticDir, "index.html")

	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if _, err := os.Stat(filepath.Join(s.cfg.StaticDir, filepath.Clean(r.URL.Path))); err != nil {
			http.ServeFile(w, r, index)
			return
		}
		files.ServeHTTP(w, r)
	})
}

// Run keeps the cached board fresh and reaps players whose clock ran out.
func (s *Server) Run(ctx context.Context) {
	s.refresh(ctx)

	board := time.NewTicker(s.cfg.BoardInterval)
	defer board.Stop()
	housekeeping := time.NewTicker(time.Minute)
	defer housekeeping.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-board.C:
			s.refresh(ctx)
		case now := <-housekeeping.C:
			if n, err := s.store.ReapDead(ctx, now.Unix()); err != nil {
				s.log.Error("reap dead players", "error", err)
			} else if n > 0 {
				s.log.Info("players timed out", "count", n)
			}
			s.challenges.sweep(now)
			s.limiter.sweep(now, time.Hour)
		}
	}
}

func (s *Server) refresh(ctx context.Context) {
	ctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()

	now := time.Now().Unix()
	board, err := s.store.Board(ctx, now)
	if err != nil {
		s.log.Error("refresh board", "error", err)
		return
	}
	top, err := s.store.Top(ctx, now, topSize)
	if err != nil {
		s.log.Error("refresh top", "error", err)
		return
	}
	s.board.Store(&snapshot{board: board, top: top})
}

func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	if err := s.store.Ping(r.Context()); err != nil {
		writeError(w, http.StatusServiceUnavailable, "database unreachable")
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (s *Server) handleBoard(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("cache-control", "public, max-age=10")
	writeJSON(w, http.StatusOK, s.board.Load().board)
}

func (s *Server) handleTop(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("cache-control", "public, max-age=10")
	top := s.board.Load().top
	if top == nil {
		top = []store.TopEntry{}
	}
	writeJSON(w, http.StatusOK, top)
}

// handleVersion names the build the app should be running. A zero version code
// means no release is announced, and every installation considers itself current.
func (s *Server) handleVersion(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("cache-control", "public, max-age=300")
	writeJSON(w, http.StatusOK, s.cfg.Release)
}

func logRequests(log *slog.Logger, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		started := time.Now()
		rec := &statusRecorder{ResponseWriter: w, status: http.StatusOK}
		next.ServeHTTP(rec, r)
		log.Info("request",
			"method", r.Method,
			"path", r.URL.Path,
			"status", rec.status,
			"duration", time.Since(started).Round(time.Millisecond))
	})
}

type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (r *statusRecorder) WriteHeader(status int) {
	r.status = status
	r.ResponseWriter.WriteHeader(status)
}
