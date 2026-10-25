#!/usr/bin/env bash
# Signs an unsigned release app bundle with Pipkin's Play upload key and checks that the result carries the upload
# certificate. Google Play checks the upload signature, then re-signs the app with the release key for users.
# .github/workflows/release.yml runs it in a job that holds the upload key and nothing else.
#
# Usage: sign-play-bundle.sh <unsigned.aab> <signed.aab>
#
# Environment:
#   PLAY_UPLOAD_KEYSTORE_BASE64    the PKCS12 keystore, base64-encoded
#   PLAY_UPLOAD_KEYSTORE_PASSWORD  the keystore's password, which PKCS12 also uses for the key
#   PLAY_UPLOAD_KEY_ALIAS          the key's alias in the keystore
#   PLAY_UPLOAD_CERT_SHA256        the SHA-256 digest of the upload certificate, in hex; colons are optional
set -euo pipefail

if [ $# -ne 2 ]; then
  echo "Usage: $0 <unsigned.aab> <signed.aab>" >&2
  exit 2
fi
unsigned=$1
signed=$2

: "${PLAY_UPLOAD_KEYSTORE_BASE64:?is not set}"
: "${PLAY_UPLOAD_KEYSTORE_PASSWORD:?is not set}"
: "${PLAY_UPLOAD_KEY_ALIAS:?is not set}"
: "${PLAY_UPLOAD_CERT_SHA256:?is not set}"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
keystore="$work/upload.p12"
(
  umask 077
  printf '%s' "$PLAY_UPLOAD_KEYSTORE_BASE64" | base64 --decode >"$keystore"
)

# The password reaches jarsigner through the environment, never the command line.
jarsigner \
  -keystore "$keystore" \
  -storetype PKCS12 \
  -storepass:env PLAY_UPLOAD_KEYSTORE_PASSWORD \
  -sigalg SHA256withRSA \
  -digestalg SHA-256 \
  -signedjar "$signed" \
  "$unsigned" \
  "$PLAY_UPLOAD_KEY_ALIAS" >/dev/null

# Not -strict: that also fails on the upload certificate being self-signed, as Play expects it to be.
jarsigner -verify "$signed" >/dev/null
actual=$(keytool -printcert -jarfile "$signed" | sed -n 's/^[[:space:]]*SHA256: //p' | sort -u | tr -d ':' | tr 'A-F' 'a-f')
expected=$(printf '%s' "$PLAY_UPLOAD_CERT_SHA256" | tr -d ':' | tr 'A-F' 'a-f')
if [ "$actual" != "$expected" ]; then
  echo "$signed is signed by $actual, not the upload certificate $expected" >&2
  exit 1
fi
