package store

import "context"

// Board is the public aggregate the landing page runs on.
type Board struct {
	WorldSeconds   int64 `json:"world_seconds"`
	Alive          int64 `json:"alive"`
	Dead           int64 `json:"dead"`
	Deals          int64 `json:"deals"`
	Transferred24h int64 `json:"transferred_24h"`
	AsOf           int64 `json:"as_of"`
}

type TopEntry struct {
	Name    string `json:"name"`
	Seconds int64  `json:"seconds"`
	Diff24h int64  `json:"diff_24h"`
}

const day = 24 * 3600

func (s *Store) Board(ctx context.Context, now int64) (Board, error) {
	b := Board{AsOf: now}
	err := s.pool.QueryRow(ctx, `
		SELECT
			coalesce((SELECT sum(expires_at) - count(*) * $1::bigint FROM players
			          WHERE died_at IS NULL AND expires_at > $1), 0),
			(SELECT count(*) FROM players WHERE died_at IS NULL AND expires_at > $1),
			(SELECT count(*) FROM players WHERE died_at IS NOT NULL OR expires_at <= $1),
			(SELECT count(*) FROM transfers),
			coalesce((SELECT sum(applied) FROM transfers WHERE recorded_at > $1::bigint - $2::bigint), 0)`,
		now, int64(day),
	).Scan(&b.WorldSeconds, &b.Alive, &b.Dead, &b.Deals, &b.Transferred24h)
	return b, err
}

// Top ranks the living by remaining time. Only players who opted in are listed.
func (s *Store) Top(ctx context.Context, now int64, limit int) ([]TopEntry, error) {
	rows, err := s.pool.Query(ctx, `
		WITH recent AS (
			SELECT from_tg, to_tg, applied FROM transfers WHERE recorded_at > $1::bigint - $2::bigint
		), flow AS (
			SELECT tg_id, sum(delta) AS diff FROM (
				SELECT from_tg AS tg_id, -applied AS delta FROM recent
				UNION ALL
				SELECT to_tg, applied FROM recent
			) moves GROUP BY tg_id
		)
		SELECT p.name, p.expires_at - $1::bigint, coalesce(f.diff, 0)
		FROM players p
		LEFT JOIN flow f ON f.tg_id = p.tg_id
		WHERE p.died_at IS NULL AND p.expires_at > $1 AND p.listed
		ORDER BY p.expires_at DESC
		LIMIT $3`, now, int64(day), limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]TopEntry, 0, limit)
	for rows.Next() {
		var e TopEntry
		if err := rows.Scan(&e.Name, &e.Seconds, &e.Diff24h); err != nil {
			return nil, err
		}
		out = append(out, e)
	}
	return out, rows.Err()
}
