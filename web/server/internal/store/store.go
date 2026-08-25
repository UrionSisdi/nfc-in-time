// Package store owns the authoritative state: players, their device keys and the
// ledger of signed transfers.
package store

import (
	"context"
	"embed"
	"errors"
	"fmt"
	"sort"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/urionsisdi/nfc-in-time/web/server/internal/keys"
)

//go:embed migrations/*.sql
var migrations embed.FS

var (
	ErrNoPlayer = errors.New("player not found")
	ErrNoDevice = errors.New("device not registered")
	ErrRevoked  = errors.New("device key revoked")
)

type Store struct {
	pool *pgxpool.Pool
}

func Open(ctx context.Context, dsn string) (*Store, error) {
	cfg, err := pgxpool.ParseConfig(dsn)
	if err != nil {
		return nil, fmt.Errorf("parse dsn: %w", err)
	}
	cfg.MaxConnIdleTime = 5 * time.Minute

	pool, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		return nil, fmt.Errorf("connect: %w", err)
	}
	if err := pool.Ping(ctx); err != nil {
		pool.Close()
		return nil, fmt.Errorf("ping: %w", err)
	}
	return &Store{pool: pool}, nil
}

func (s *Store) Close() { s.pool.Close() }

func (s *Store) Ping(ctx context.Context) error { return s.pool.Ping(ctx) }

// Migrate applies every embedded migration that has not run yet.
func (s *Store) Migrate(ctx context.Context) error {
	_, err := s.pool.Exec(ctx, `CREATE TABLE IF NOT EXISTS schema_migrations (
		name text PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())`)
	if err != nil {
		return fmt.Errorf("migration table: %w", err)
	}

	entries, err := migrations.ReadDir("migrations")
	if err != nil {
		return err
	}
	names := make([]string, 0, len(entries))
	for _, e := range entries {
		names = append(names, e.Name())
	}
	sort.Strings(names)

	for _, name := range names {
		var seen bool
		err := s.pool.QueryRow(ctx,
			`SELECT EXISTS (SELECT 1 FROM schema_migrations WHERE name = $1)`, name).Scan(&seen)
		if err != nil {
			return fmt.Errorf("check migration %s: %w", name, err)
		}
		if seen {
			continue
		}

		body, err := migrations.ReadFile("migrations/" + name)
		if err != nil {
			return err
		}
		err = s.tx(ctx, func(tx pgx.Tx) error {
			if _, err := tx.Exec(ctx, string(body)); err != nil {
				return err
			}
			_, err := tx.Exec(ctx, `INSERT INTO schema_migrations (name) VALUES ($1)`, name)
			return err
		})
		if err != nil {
			return fmt.Errorf("apply migration %s: %w", name, err)
		}
	}
	return nil
}

func (s *Store) tx(ctx context.Context, fn func(pgx.Tx) error) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)

	if err := fn(tx); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

type Player struct {
	TgID      string
	Name      string
	ExpiresAt int64
	BornAt    int64
	DiedAt    *int64
	Listed    bool
}

// BalanceAt is the remaining seconds, floored at zero.
func (p Player) BalanceAt(now int64) int64 {
	if p.ExpiresAt <= now {
		return 0
	}
	return p.ExpiresAt - now
}

func (p Player) Alive(now int64) bool { return p.DiedAt == nil && p.ExpiresAt > now }

type Device struct {
	KeyID     keys.ID
	TgID      string
	PublicKey []byte
	Revoked   bool
}

// Register binds a Telegram account to a device key. A returning account keeps
// its balance and revokes every earlier key: one live installation per player.
func (s *Store) Register(ctx context.Context, tgID, name string, pubKey []byte, keyID keys.ID, genesis, now int64) (Player, bool, error) {
	var p Player
	var created bool

	err := s.tx(ctx, func(tx pgx.Tx) error {
		err := tx.QueryRow(ctx, `
			INSERT INTO players (tg_id, name, expires_at, born_at, synced_at)
			VALUES ($1, $2, $3::bigint + $4::bigint, $4, $4)
			ON CONFLICT (tg_id) DO UPDATE SET synced_at = EXCLUDED.synced_at
			RETURNING tg_id, name, expires_at, born_at, died_at, listed, (xmax = 0)`,
			tgID, name, genesis, now,
		).Scan(&p.TgID, &p.Name, &p.ExpiresAt, &p.BornAt, &p.DiedAt, &p.Listed, &created)
		if err != nil {
			return fmt.Errorf("upsert player: %w", err)
		}

		if _, err := tx.Exec(ctx,
			`UPDATE devices SET revoked_at = $2 WHERE tg_id = $1 AND revoked_at IS NULL AND key_id <> $3`,
			tgID, now, string(keyID)); err != nil {
			return fmt.Errorf("revoke previous devices: %w", err)
		}

		_, err = tx.Exec(ctx, `
			INSERT INTO devices (key_id, tg_id, public_key, created_at)
			VALUES ($1, $2, $3, $4)
			ON CONFLICT (key_id) DO UPDATE SET tg_id = EXCLUDED.tg_id, revoked_at = NULL`,
			string(keyID), tgID, pubKey, now)
		if err != nil {
			return fmt.Errorf("insert device: %w", err)
		}
		return nil
	})
	return p, created, err
}

func (s *Store) Device(ctx context.Context, id keys.ID) (Device, error) {
	var d Device
	var revoked *int64
	err := s.pool.QueryRow(ctx,
		`SELECT key_id, tg_id, public_key, revoked_at FROM devices WHERE key_id = $1`,
		string(id)).Scan(&d.KeyID, &d.TgID, &d.PublicKey, &revoked)
	if errors.Is(err, pgx.ErrNoRows) {
		return d, ErrNoDevice
	}
	if err != nil {
		return d, err
	}
	if revoked != nil {
		return d, ErrRevoked
	}
	return d, nil
}

func (s *Store) Player(ctx context.Context, tgID string) (Player, error) {
	var p Player
	err := s.pool.QueryRow(ctx, `
		SELECT tg_id, name, expires_at, born_at, died_at, listed
		FROM players WHERE tg_id = $1`, tgID).
		Scan(&p.TgID, &p.Name, &p.ExpiresAt, &p.BornAt, &p.DiedAt, &p.Listed)
	if errors.Is(err, pgx.ErrNoRows) {
		return p, ErrNoPlayer
	}
	return p, err
}

// UpdateProfile records what the device reports about itself. An empty name
// leaves the stored one alone.
func (s *Store) UpdateProfile(ctx context.Context, tgID, name string, listed *bool, now int64) error {
	_, err := s.pool.Exec(ctx, `
		UPDATE players SET
			name      = coalesce(nullif($2, ''), name),
			listed    = coalesce($3, listed),
			synced_at = $4
		WHERE tg_id = $1`, tgID, name, listed, now)
	return err
}

// Transfer is one contact settled: the net time that changed hands, signed by
// both players.
type Transfer struct {
	Nonce    string
	FromTgID string
	ToTgID   string
	Amount   int64
	PrevHash string
	SignedAt int64
}

// TransferResult reports what the ledger did with a submitted transfer.
type TransferResult struct {
	Nonce string `json:"nonce"`
	// Applied is the amount that actually moved. It is short of Amount when the
	// donor ran out mid-contact, and zero for a duplicate.
	Applied   int64 `json:"applied"`
	Duplicate bool  `json:"duplicate"`
}

// ApplyTransfers settles a batch. Each transfer is its own transaction: one bad
// record does not cost the caller the rest of the batch.
func (s *Store) ApplyTransfers(ctx context.Context, ts []Transfer, reportedBy string, now int64) ([]TransferResult, error) {
	out := make([]TransferResult, 0, len(ts))
	for _, t := range ts {
		res, err := s.applyTransfer(ctx, t, reportedBy, now)
		if err != nil {
			return out, err
		}
		out = append(out, res)
	}
	return out, nil
}

func (s *Store) applyTransfer(ctx context.Context, t Transfer, reportedBy string, now int64) (TransferResult, error) {
	res := TransferResult{Nonce: t.Nonce}

	err := s.tx(ctx, func(tx pgx.Tx) error {
		// Lock both rows in a fixed order so two contacts sharing a player
		// cannot deadlock against each other.
		first, second := t.FromTgID, t.ToTgID
		if first > second {
			first, second = second, first
		}
		expiry := make(map[string]int64, 2)
		for _, id := range []string{first, second} {
			var exp int64
			err := tx.QueryRow(ctx,
				`SELECT expires_at FROM players WHERE tg_id = $1 FOR UPDATE`, id).Scan(&exp)
			if errors.Is(err, pgx.ErrNoRows) {
				return fmt.Errorf("%w: %s", ErrNoPlayer, id)
			}
			if err != nil {
				return err
			}
			expiry[id] = exp
		}

		donor, recipient := expiry[t.FromTgID], expiry[t.ToTgID]
		applied := min(t.Amount, max(donor-now, 0))

		tag, err := tx.Exec(ctx, `
			INSERT INTO transfers (nonce, from_tg, to_tg, amount, applied, prev_hash,
			                       signed_at, recorded_at, reported_by)
			VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
			ON CONFLICT (nonce) DO NOTHING`,
			t.Nonce, t.FromTgID, t.ToTgID, t.Amount, applied, t.PrevHash, t.SignedAt, now, reportedBy)
		if err != nil {
			return fmt.Errorf("record transfer: %w", err)
		}
		if tag.RowsAffected() == 0 {
			res.Duplicate = true
			return nil
		}
		res.Applied = applied
		if applied == 0 {
			return nil
		}

		if err := setExpiry(ctx, tx, t.FromTgID, donor-applied, now); err != nil {
			return err
		}
		// A dead recipient stays dead: time handed to a zeroed player is burnt,
		// not banked.
		if recipient > now {
			if err := setExpiry(ctx, tx, t.ToTgID, recipient+applied, now); err != nil {
				return err
			}
		}
		return nil
	})
	if err != nil {
		var pgErr *pgconn.PgError
		if errors.As(err, &pgErr) {
			return res, fmt.Errorf("transfer %s: %s", t.Nonce, pgErr.Message)
		}
		return res, err
	}
	return res, nil
}

func setExpiry(ctx context.Context, tx pgx.Tx, tgID string, expiresAt, now int64) error {
	_, err := tx.Exec(ctx, `
		UPDATE players SET
			expires_at = $2::bigint,
			died_at    = CASE WHEN $2::bigint <= $3::bigint AND died_at IS NULL
			                  THEN $3::bigint ELSE died_at END,
			synced_at  = $3::bigint
		WHERE tg_id = $1`, tgID, expiresAt, now)
	if err != nil {
		return fmt.Errorf("update %s: %w", tgID, err)
	}
	return nil
}

// ReapDead stamps players whose clock ran out while nothing touched them.
func (s *Store) ReapDead(ctx context.Context, now int64) (int64, error) {
	tag, err := s.pool.Exec(ctx,
		`UPDATE players SET died_at = $1 WHERE died_at IS NULL AND expires_at <= $1`, now)
	if err != nil {
		return 0, err
	}
	return tag.RowsAffected(), nil
}

// PlayerKeys returns every key ever registered to a player, revoked ones
// included: an offline transfer signed before a reinstall is still genuine, and
// refusing it would punish the honest half of the contact.
func (s *Store) PlayerKeys(ctx context.Context, tgID string) ([][]byte, error) {
	rows, err := s.pool.Query(ctx,
		`SELECT public_key FROM devices WHERE tg_id = $1 ORDER BY created_at DESC`, tgID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var out [][]byte
	for rows.Next() {
		var der []byte
		if err := rows.Scan(&der); err != nil {
			return nil, err
		}
		out = append(out, der)
	}
	return out, rows.Err()
}
