# NFC in Time — server

One Go binary: the `/v1` API and the landing page in `web/public`. PostgreSQL
holds the authoritative state.

## Running it

Two stacks, both with `docker compose`.

**Local** — API and database, nothing else. No TLS, no domain, no Telegram
credentials, no attestation:

    make dev            # http://localhost:8080

**Production** — Caddy in front, automatic TLS for a real domain, Telegram OIDC
and Key Attestation enforced:

    cp web/infra/.env.example web/infra/.env && $EDITOR web/infra/.env
    make prod

Before the first production run, fetch the Google hardware attestation roots:

    web/infra/attestation/fetch-roots.sh

Until they are there, start with `NFCIT_ATTESTATION=off` in `.env` — the rest of
the stack works, but any key is then accepted as hardware-backed.

`NFCIT_ATTESTATION_APP_DIGEST` pins the APK signer, which is what makes
sideloading safe: a repackaged build attests a different certificate and is
refused. Attestation carries the digest base64-encoded, while the Android tools
print it as hex, so convert it:

    keytool -list -v -keystore release.jks -alias <alias> \
      | awk '/SHA256:/ {print $2; exit}' | tr -d : | xxd -r -p | base64

    # or, straight from a built APK
    apksigner verify --print-certs app-release.apk \
      | awk '/SHA-256 digest:/ {print $NF; exit}' | xxd -r -p | base64

Leaving it empty is allowed and logs a warning on every start: the server then
accepts any signer, and anyone can build their own client.

## Configuration

| variable | default | meaning |
|---|---|---|
| `NFCIT_ADDR` | `:8080` | listen address |
| `NFCIT_DB` | `postgres://nfcit:nfcit@localhost:5432/nfcit?sslmode=disable` | PostgreSQL DSN |
| `NFCIT_STATIC_DIR` | `web/public` | landing page directory, empty disables it |
| `NFCIT_AUTH_MODE` | `telegram` | `telegram` or `dev` |
| `NFCIT_TELEGRAM_CLIENT_ID` | — | client id from BotFather, checked against the token audience |
| `NFCIT_ATTESTATION` | `chain` | `chain` or `off` |
| `NFCIT_ATTESTATION_ROOTS` | — | PEM bundle of Google attestation roots |
| `NFCIT_ATTESTATION_APP_DIGEST` | — | base64 SHA-256 of the release signing certificate |
| `NFCIT_GENESIS_SECONDS` | `788940000` | starting balance, 25 Julian years |
| `NFCIT_MAX_CLOCK_SKEW_SECONDS` | `300` | accepted age of a signed sync request |
| `NFCIT_BOARD_INTERVAL_SECONDS` | `10` | how often the cached board is recomputed |
| `NFCIT_SYNC_RATE_PER_MIN` | `30` | per-client rate limit |
| `NFCIT_TRUST_PROXY` | `false` | take the client address from `X-Forwarded-For` |
| `NFCIT_HTTPS_PROXY` | — | egress proxy for upstreams the host cannot reach |
| `NFCIT_NO_PROXY` | — | hosts to keep off that proxy |

The server reaches out to exactly two places: Telegram's JWKS, to verify an
`id_token`, and Google's revocation list, to reject a withdrawn attestation key.
Where the first is blocked — some hosting providers do block it — sign-in fails
with `fetch jwks: context deadline exceeded` and nothing else misbehaves. Setting
`NFCIT_HTTPS_PROXY` routes it through a proxy; `NFCIT_NO_PROXY` keeps the second
one direct, so attestation does not come to depend on that proxy being up.

`dev` auth accepts any account: the `id_token` field is read as `tg_id[:name]`
and believed. It exists so the stack runs without Telegram credentials, and the
server logs a warning for as long as it is on.

## API

    POST /v1/auth/challenge   attestation nonce, valid 10 minutes
    POST /v1/auth/telegram    sign in, register the device key, get the balance
    POST /v1/sync             submit offline transfers, get the authoritative balance
    GET  /v1/board            public aggregates for the landing page
    GET  /v1/top              public top 20
    GET  /v1/version          the build the app should be running
    GET  /healthz             database reachability

### Registration

The app signs in with the official Telegram SDK, which hands it an `id_token`
directly — the authorization code flow never touches the server, so there is no
client secret to keep. The app asks for a challenge, generates a P-256 key in the
Android Keystore with that challenge baked in, then posts:

```json
POST /v1/auth/telegram
{
  "id_token": "<JWT from the Telegram SDK>",
  "public_key": "<base64 DER SPKI>",
  "attestation": ["<base64 DER leaf>", "..."],
  "listed": false
}
```

The server verifies the attestation chain against the Google roots, checks that
nothing in the chain appears on Google's revocation list, checks the leaf holds
the submitted key, matches the challenge against one it issued, confirms the APK
signer, then verifies the `id_token` signature against Telegram's JWKS and binds
its `sub` to the key.

The revocation list is cached for as long as its `Cache-Control` says. If it
cannot be fetched and nothing is cached, registration fails rather than trusting
a chain nobody vouched for; a cached copy keeps being used through an outage. A
new account gets the genesis balance; a returning one gets whatever it has left,
and every earlier key of that account is revoked.

### Sync

The body is signed with the device key. The signature is ASN.1 ECDSA over the
SHA-256 of the raw request bytes, base64 in a header:

    X-NFCIT-Key: <key id>
    X-NFCIT-Signature: <base64 signature>

```json
{
  "issued_at": 1787270512,
  "transfers": [{
    "nonce": "...", "from": "42", "to": "43", "amount": 3600,
    "prev_hash": "...", "signed_at": 1787270400,
    "from_sig": "<base64>", "to_sig": "<base64>"
  }]
}
```

Each transfer carries both signatures over

    nfcit/transfer/v1\n<nonce>\n<from>\n<to>\n<amount>\n<signed_at>\n<prev_hash>

The reporter must be one of the two parties. Duplicates are dropped by nonce, so
either player can submit the same contact. If the donor ran out in the meantime,
only the remainder moves and the response says how much.

The response is the authoritative player state; the device's own number is never
an input.

An error body may carry a machine-readable `code` beside its `error` text. Only
one is defined: `key_unknown`, returned with `401` when the key is not
registered, has been revoked by a sign-in elsewhere, or no longer matches the
signature. The app treats it as the one refusal worth acting on — it forgets the
key and puts the player back on the sign-in screen. Everything else, a clock
skew rejected with the same `401` included, leaves the installation as it is.

## Backups

The production stack runs a `backup` service beside the database: `pg_dump
--format=custom` every 12 hours, the last 14 dumps kept, older ones rotated out.
It writes to `NFCIT_BACKUP_DIR` on the host, so the dumps outlive
`docker compose down -v`. Restore with

    pg_restore --clean --if-exists -d "$NFCIT_DB" backups/nfcit-<stamp>.dump

`NFCIT_BACKUP_INTERVAL_SECONDS` and `NFCIT_BACKUP_KEEP` change the schedule and
the depth.

## Tests

    make test         # everything that needs no database
    make test-store   # ledger tests against the running dev stack

The store tests drop and recreate the schema, so they run against the local dev
database and nothing else.

## Design notes

**Balance is stored as an expiry instant, not as a countdown.** A player row
holds `expires_at`; the balance is `expires_at - now`. Time then drains without
a single write, and the world pool on the landing page is `SUM(expires_at) -
alive * now` — one indexed aggregate instead of a per-player tick.

**A contact is settled in checkpoints, each its own signed record.** Nothing
unsigned survives the hands coming apart, and a second of held contact is worth
years, so the record is closed and reopened twice a second. `deals` on the board
is the row count of the ledger: every handover of time that both players signed,
which is several per contact. Nothing in the ledger marks where one contact ends
and the next begins, so the number of contacts is not derivable from it.

**`prev_hash` is stored but not enforced.** It is part of what both players sign,
so it cannot be rewritten after the fact, but the server holds the whole ledger
and does not need the chain to detect anything: nonce deduplication and the
two-signature rule already make a forged record unusable. Enforcing per-player
chains across two-party records would only add ordering failures for honest
clients that sync out of order.

**Transfer signatures are checked against every key the player ever had**,
revoked ones included. A contact signed before a reinstall is still genuine, and
rejecting it would punish the other player for something they did not do.

**Names are cleaned, not escaped away.** A handle is capped at 32 visible
characters, and control and format characters are refused outright: a bidi
override inside a nickname reorders the row it is rendered in, and the board is
public.

**Time handed to a dead player is burnt.** It leaves the donor and does not
arrive. Zero is final: there is no revival by donation.
