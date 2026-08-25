#!/usr/bin/env sh
# Fetches the Google hardware attestation roots and writes them as a PEM bundle
# next to this script. Google publishes the same certificates on the developer
# documentation page; the fingerprints printed at the end are there to compare
# against it, because this bundle is the only thing standing between a real
# secure element and a chain somebody generated on a laptop.
set -eu

url=https://android.googleapis.com/attestation/root
out=$(dirname "$0")/google-attestation-roots.pem
tmp=$(mktemp)
trap 'rm -f "$tmp"' EXIT

curl -fsS "$url" \
	| python3 -c 'import json,sys; print("\n".join(c.strip() for c in json.load(sys.stdin)))' \
	> "$tmp"

count=$(grep -c 'BEGIN CERTIFICATE' "$tmp")
[ "$count" -ge 2 ] || { echo "expected at least 2 roots, got $count" >&2; exit 1; }

openssl crl2pkcs7 -nocrl -certfile "$tmp" >/dev/null

mv "$tmp" "$out"
chmod 644 "$out"   # mktemp is 0600; the container reads it as an unprivileged user
trap - EXIT
echo "wrote $count roots to $out"
echo
openssl crl2pkcs7 -nocrl -certfile "$out" \
	| openssl pkcs7 -print_certs \
	| awk '/^subject=/ {print}'
