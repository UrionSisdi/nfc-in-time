// Command nfcit serves the NFC in Time API and the landing page from a single
// binary.
package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/urionsisdi/nfc-in-time/web/server/internal/api"
	"github.com/urionsisdi/nfc-in-time/web/server/internal/attest"
	"github.com/urionsisdi/nfc-in-time/web/server/internal/config"
	"github.com/urionsisdi/nfc-in-time/web/server/internal/store"
	"github.com/urionsisdi/nfc-in-time/web/server/internal/telegram"
)

func main() {
	log := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo}))
	slog.SetDefault(log)

	if err := run(log); err != nil {
		log.Error("fatal", "error", err)
		os.Exit(1)
	}
}

func run(log *slog.Logger) error {
	cfg, err := config.Load()
	if err != nil {
		return err
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	st, err := store.Open(ctx, cfg.DatabaseDSN)
	if err != nil {
		return err
	}
	defer st.Close()

	if err := st.Migrate(ctx); err != nil {
		return err
	}

	var auth telegram.Verifier = telegram.New(
		cfg.Telegram.ClientID, cfg.Telegram.Issuer, cfg.Telegram.JWKSURL)
	if cfg.AuthMode == config.AuthDev {
		log.Warn("running with unverified dev auth, never do this in production")
		auth = telegram.Dev{}
	}

	var attester *attest.Verifier
	if cfg.AttestMode == config.AttestChain {
		if attester, err = attest.NewVerifier(cfg.AttestRoot, cfg.AttestAppDigest); err != nil {
			return err
		}
		if cfg.AttestAppDigest == "" {
			log.Warn("attestation accepts any app signer, set NFCIT_ATTESTATION_APP_DIGEST to the release certificate digest")
		}
	}

	srv, err := api.New(cfg, st, auth, attester, log)
	if err != nil {
		return err
	}
	go srv.Run(ctx)

	httpSrv := &http.Server{
		Addr:              cfg.Addr,
		Handler:           srv.Handler(),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      15 * time.Second,
		IdleTimeout:       60 * time.Second,
	}

	errc := make(chan error, 1)
	go func() {
		log.Info("listening", "addr", cfg.Addr, "auth", cfg.AuthMode, "attestation", cfg.AttestMode)
		if err := httpSrv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			errc <- err
		}
	}()

	select {
	case err := <-errc:
		return err
	case <-ctx.Done():
	}

	log.Info("shutting down")
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	return httpSrv.Shutdown(shutdownCtx)
}
