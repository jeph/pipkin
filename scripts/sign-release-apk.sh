#!/usr/bin/env bash
# Signs an unsigned release APK with Pipkin's release key and checks that the result carries the release certificate.
# .github/workflows/release.yml runs it in a job that holds the signing secrets and nothing else.
#
# Usage: sign-release-apk.sh <unsigned.apk> <signed.apk>
#
# Environment:
#   RELEASE_KEYSTORE_BASE64    the PKCS12 keystore, base64-encoded
#   RELEASE_KEYSTORE_PASSWORD  the keystore's password, which PKCS12 also uses for the key
#   RELEASE_KEY_ALIAS          the key's alias in the keystore
#   RELEASE_CERT_SHA256        the SHA-256 digest of the release certificate, in hex; colons are optional
#   BUILD_TOOLS                the Android build-tools folder holding zipalign and apksigner
set -euo pipefail

if [ $# -ne 2 ]; then
  echo "Usage: $0 <unsigned.apk> <signed.apk>" >&2
  exit 2
fi
unsigned=$1
signed=$2

: "${RELEASE_KEYSTORE_BASE64:?is not set}"
: "${RELEASE_KEYSTORE_PASSWORD:?is not set}"
: "${RELEASE_KEY_ALIAS:?is not set}"
: "${RELEASE_CERT_SHA256:?is not set}"
: "${BUILD_TOOLS:?is not set}"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
keystore="$work/release.p12"
(
  umask 077
  printf '%s' "$RELEASE_KEYSTORE_BASE64" | base64 --decode >"$keystore"
)

# 16 KB page alignment keeps the APK installable on devices with 16 KB memory pages.
"$BUILD_TOOLS/zipalign" -P 16 -f 4 "$unsigned" "$work/aligned.apk"
# The password reaches apksigner through the environment, never the command line.
"$BUILD_TOOLS/apksigner" sign \
  --ks "$keystore" \
  --ks-type PKCS12 \
  --ks-pass env:RELEASE_KEYSTORE_PASSWORD \
  --ks-key-alias "$RELEASE_KEY_ALIAS" \
  --v4-signing-enabled false \
  --out "$signed" \
  "$work/aligned.apk"

certificates=$("$BUILD_TOOLS/apksigner" verify --print-certs "$signed")
# apksigner prints one digest per signature scheme; they must all name the one release certificate.
actual=$(sed -n 's/^.*certificate SHA-256 digest: //p' <<<"$certificates" | sort -u)
expected=$(printf '%s' "$RELEASE_CERT_SHA256" | tr -d ':' | tr 'A-F' 'a-f')
if [ "$actual" != "$expected" ]; then
  echo "$signed is signed by $actual, not the release certificate $expected" >&2
  exit 1
fi
