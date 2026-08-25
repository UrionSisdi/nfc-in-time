CREATE TABLE players (
    tg_id      text   PRIMARY KEY,
    name       text   NOT NULL,
    -- The clock, stored as the instant the player hits zero. Balance is
    -- expires_at - now(), so the countdown needs no writes to keep running and
    -- the world pool is a plain SUM.
    expires_at bigint NOT NULL,
    born_at    bigint NOT NULL,
    died_at    bigint,
    listed     boolean NOT NULL DEFAULT false,
    boot_id    text,
    synced_at  bigint NOT NULL
);

CREATE INDEX players_alive ON players (expires_at) WHERE died_at IS NULL;

CREATE TABLE devices (
    key_id     text   PRIMARY KEY,
    tg_id      text   NOT NULL REFERENCES players (tg_id) ON DELETE CASCADE,
    public_key bytea  NOT NULL,
    created_at bigint NOT NULL,
    revoked_at bigint
);

CREATE INDEX devices_active ON devices (tg_id) WHERE revoked_at IS NULL;

CREATE TABLE transfers (
    nonce       text   PRIMARY KEY,
    from_tg     text   NOT NULL REFERENCES players (tg_id),
    to_tg       text   NOT NULL REFERENCES players (tg_id),
    amount      bigint NOT NULL CHECK (amount > 0),
    applied     bigint NOT NULL CHECK (applied >= 0),
    prev_hash   text   NOT NULL,
    signed_at   bigint NOT NULL,
    recorded_at bigint NOT NULL,
    reported_by text   NOT NULL,
    CHECK (from_tg <> to_tg)
);

CREATE INDEX transfers_recorded_at ON transfers (recorded_at DESC);
CREATE INDEX transfers_from ON transfers (from_tg, recorded_at DESC);
CREATE INDEX transfers_to ON transfers (to_tg, recorded_at DESC);
