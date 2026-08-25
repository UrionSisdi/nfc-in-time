# NFC in Time

An NFC game after the film *In Time* (2011): everyone carries a countdown, and
time changes hands by touching two phones together. Hold two phones back to back
and the one held screen up takes from the one below, faster every second the
contact lasts; twenty-five years are issued once per Telegram account.

The live board and the rules: [in-time-nfc.ru](https://in-time-nfc.ru). The APK
is on the [releases page](https://github.com/urionsisdi/nfc-in-time/releases/latest).

    web/public    landing page — no build step, plain HTML, CSS and JS
    web/server    Go API and static host, one binary
    web/infra     Docker Compose stacks, Dockerfile, Caddy
    android/      the app — Kotlin, Compose, NFC host card emulation

## Running the web side

    make dev      # API + PostgreSQL on http://127.0.0.1:8080, dev auth, no TLS
    make prod     # adds Caddy, a real domain, Telegram OIDC and Key Attestation

See [web/server/README.md](web/server/README.md) for the API, the signing
protocol and every environment variable.

## Running the app

    make android          # debug APK
    make android-install  # onto a connected phone
    make android-test     # the game arithmetic and the wire format

[android/README.md](android/README.md) covers the two things the repository does
not carry: a GitHub token for Telegram's login SDK, and `local.properties`.

## License

[MIT](LICENSE)
