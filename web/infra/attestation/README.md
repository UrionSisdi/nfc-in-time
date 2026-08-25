The Google hardware attestation roots belong here as
`google-attestation-roots.pem`. Run `./fetch-roots.sh` to write them, then
compare the printed subjects with the ones on
https://developer.android.com/privacy-and-security/security-key-attestation.

The bundle is not committed: it rotates, and a stale copy silently refuses
every device that ships with the newer root. The directory is mounted read-only
into the API container as `/etc/nfcit`.

Until the file is in place, run with `NFCIT_ATTESTATION=off`.
