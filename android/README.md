# NFC in Time — Android

The app. Kotlin and Jetpack Compose, one module, `minSdk 29`.

    ./gradlew test            unit tests: the game arithmetic and the wire format
    ./gradlew assembleDebug   debug APK
    ./gradlew installDebug    onto a connected phone

## Before the first build

Two things are not in the repository.

**A GitHub token.** Telegram publishes its login SDK to GitHub Packages and
nowhere else, and reading it needs a classic personal token with the
`read:packages` scope. Put it in `~/.gradle/gradle.properties`:

    gpr.user=<your github login>
    gpr.key=<ghp_...>

`GITHUB_USERNAME` and `GITHUB_TOKEN` work instead, which is what CI uses.

**Local settings.** `local.properties` holds the SDK path, the bot's client id,
the app id of its native registration and the server to talk to:

    sdk.dir=/path/to/android-sdk
    nfcit.telegram.clientId=<from BotFather>
    nfcit.telegram.appId.debug=<from BotFather, Native Login>
    nfcit.baseUrl.debug=http://10.0.2.2:8080

`10.0.2.2` is the host as an emulator sees it; a real phone needs the machine's
address on the same network. Release builds read `nfcit.baseUrl` and
`nfcit.telegram.appId`.

**Signing a release.** Without a keystore the release variant is left unsigned
and only the debug APK assembles. Point `local.properties` at one to sign it:

    nfcit.keystore=keystore/nfcit-release.jks
    nfcit.keystore.password=<store password>
    nfcit.key.alias=<alias>
    nfcit.key.password=<key password>

The equivalent `NFCIT_KEYSTORE`, `NFCIT_KEYSTORE_PASSWORD`, `NFCIT_KEY_ALIAS`
and `NFCIT_KEY_PASSWORD` variables take over where there is no
`local.properties`, which is how CI signs. A keystore kept inside the checkout
belongs under `android/keystore/`, which `.gitignore` excludes along with every
`*.jks`: the key that is published is a key that has to be replaced, and
replacing it strands every installation already carrying the old signature.

The server pins that same certificate through `NFCIT_ATTESTATION_APP_DIGEST`,
which is its SHA-256 base64-encoded — see the server README for how to derive
one from the keystore.

The app id is not the client id. Registering a native app under Bot Settings >
Login Widget > Native Login — package name plus the signing SHA-256 — gives that
registration an id of its own, and only `app<id>-login.tg.dev` serves an
assetlinks.json naming the package. Debug builds carry an applicationId suffix,
so they need a registration of their own.

## Working against a local server

`make dev` in the repository root starts the API with `NFCIT_AUTH_MODE=dev`,
which takes `tg_id[:name]` in place of a Telegram token. Debug builds sign in
that way (`BuildConfig.DEV_AUTH`), so the game is playable before the bot is
registered anywhere. Set `nfcit.devAuth=false` to exercise the real sign-in.

## What cannot be tested on an emulator

Host Card Emulation needs two real phones back to back. The emulator runs the
counter, the sign-in and the sync, and nothing of the contact.

## Layout

    time/     the balance: monotonic clock, spans, formatting
    data/     DataStore — profile, cached balance, queue of unsent transfers
    crypto/   the Keystore key, and the bytes both players sign
    net/      the endpoints the app calls, over HttpURLConnection
    auth/     Telegram sign-in, and the dev path beside it
    nfc/      AID, frames, card service, reader loop, the contact itself
    game/     dominance, progression, the integral both sides agree on
    sensor/   gravity
    ui/       screens and the landing's palette

## Fonts

IBM Plex Mono and Archivo, bundled rather than downloaded — the app does not
assume Google Play is on the device. Both are under the SIL Open Font License,
copies in `licenses/`.
