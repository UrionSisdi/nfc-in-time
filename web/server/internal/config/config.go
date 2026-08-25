package config

import (
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"
)

// AuthMode selects how a device proves it belongs to a Telegram account.
type AuthMode string

const (
	// AuthTelegram verifies the ID token the app got from the Telegram SDK.
	AuthTelegram AuthMode = "telegram"
	// AuthDev accepts a caller-supplied Telegram id without proof. Local only.
	AuthDev AuthMode = "dev"
)

// AttestMode selects how much of the Android Key Attestation chain is enforced.
type AttestMode string

const (
	AttestOff   AttestMode = "off"
	AttestChain AttestMode = "chain"
)

type Config struct {
	Addr        string
	StaticDir   string
	DatabaseDSN string

	AuthMode AuthMode
	Telegram Telegram

	AttestMode AttestMode
	AttestRoot string
	// AttestAppDigest is the release signing certificate digest, base64. Empty
	// accepts any signer.
	AttestAppDigest string

	GenesisSeconds int64
	MaxClockSkew   time.Duration
	BoardInterval  time.Duration
	SyncRatePerMin int

	Release Release

	TrustProxy bool
}

// Release is the build the app should be running. The app has no updater of its
// own — it asks here and installs what this names, so the version lives in one
// place rather than in whatever GitHub's release JSON looks like this year.
type Release struct {
	VersionCode int64  `json:"version_code"`
	VersionName string `json:"version_name"`
	URL         string `json:"url"`
}

type Telegram struct {
	ClientID string
	Issuer   string
	JWKSURL  string
}

// yearSeconds matches the client: a Julian year, so a genesis of 25 years is
// exactly the number the app counts down from.
const yearSeconds = 365.25 * 24 * 3600

func Load() (Config, error) {
	c := Config{
		Addr:            env("NFCIT_ADDR", ":8080"),
		StaticDir:       env("NFCIT_STATIC_DIR", "web/public"),
		DatabaseDSN:     env("NFCIT_DB", "postgres://nfcit:nfcit@localhost:5432/nfcit?sslmode=disable"),
		AuthMode:        AuthMode(env("NFCIT_AUTH_MODE", string(AuthTelegram))),
		AttestMode:      AttestMode(env("NFCIT_ATTESTATION", string(AttestChain))),
		AttestRoot:      env("NFCIT_ATTESTATION_ROOTS", ""),
		AttestAppDigest: env("NFCIT_ATTESTATION_APP_DIGEST", ""),
		GenesisSeconds:  int64(25 * yearSeconds),
		TrustProxy:      env("NFCIT_TRUST_PROXY", "false") == "true",
		Release: Release{
			VersionName: env("NFCIT_APP_VERSION_NAME", ""),
			URL:         env("NFCIT_APP_URL", ""),
		},
		Telegram: Telegram{
			ClientID: env("NFCIT_TELEGRAM_CLIENT_ID", ""),
			Issuer:   env("NFCIT_TELEGRAM_ISSUER", "https://oauth.telegram.org"),
			JWKSURL:  env("NFCIT_TELEGRAM_JWKS_URL", "https://oauth.telegram.org/.well-known/jwks.json"),
		},
	}

	var err error
	if c.GenesisSeconds, err = envInt("NFCIT_GENESIS_SECONDS", c.GenesisSeconds); err != nil {
		return c, err
	}
	skew, err := envInt("NFCIT_MAX_CLOCK_SKEW_SECONDS", 300)
	if err != nil {
		return c, err
	}
	c.MaxClockSkew = time.Duration(skew) * time.Second

	board, err := envInt("NFCIT_BOARD_INTERVAL_SECONDS", 10)
	if err != nil {
		return c, err
	}
	c.BoardInterval = time.Duration(board) * time.Second

	rate, err := envInt("NFCIT_SYNC_RATE_PER_MIN", 30)
	if err != nil {
		return c, err
	}
	c.SyncRatePerMin = int(rate)

	if c.Release.VersionCode, err = envInt("NFCIT_APP_VERSION_CODE", 0); err != nil {
		return c, err
	}

	return c, c.validate()
}

func (c Config) validate() error {
	switch c.AuthMode {
	case AuthTelegram:
		// The client id is not a secret, but without it the audience check
		// would accept a token minted for somebody else's bot.
		if c.Telegram.ClientID == "" {
			return fmt.Errorf("auth mode %q needs NFCIT_TELEGRAM_CLIENT_ID", c.AuthMode)
		}
	case AuthDev:
	default:
		return fmt.Errorf("unknown NFCIT_AUTH_MODE %q", c.AuthMode)
	}

	switch c.AttestMode {
	case AttestOff:
	case AttestChain:
		if c.AttestRoot == "" {
			return fmt.Errorf("attestation mode %q needs NFCIT_ATTESTATION_ROOTS", c.AttestMode)
		}
	default:
		return fmt.Errorf("unknown NFCIT_ATTESTATION %q", c.AttestMode)
	}

	if c.GenesisSeconds <= 0 {
		return fmt.Errorf("genesis must be positive, got %d", c.GenesisSeconds)
	}

	// Announcing a version without somewhere to get it would leave every
	// installation offering an update it cannot fetch.
	if c.Release.VersionCode > 0 && (c.Release.VersionName == "" || c.Release.URL == "") {
		return fmt.Errorf("NFCIT_APP_VERSION_CODE needs NFCIT_APP_VERSION_NAME and NFCIT_APP_URL")
	}
	return nil
}

func env(key, fallback string) string {
	if v := strings.TrimSpace(os.Getenv(key)); v != "" {
		return v
	}
	return fallback
}

func envInt(key string, fallback int64) (int64, error) {
	raw := strings.TrimSpace(os.Getenv(key))
	if raw == "" {
		return fallback, nil
	}
	v, err := strconv.ParseInt(raw, 10, 64)
	if err != nil {
		return fallback, fmt.Errorf("%s: %w", key, err)
	}
	return v, nil
}
