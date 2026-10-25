#!/usr/bin/env bash
# Uploads a signed app bundle to Google Play and releases it to every user of one track, through one edit of the
# Google Play Developer API. A bundle Play already has, as when a release is rerun, isn't uploaded again; the track
# still gets it. .github/workflows/release.yml runs it in a job that holds the Play publishing credential and nothing
# else.
#
# Usage: upload-play-bundle.sh <signed.aab>
#
# Environment:
#   PLAY_ACCESS_TOKEN  an OAuth access token with the androidpublisher scope
#   PLAY_PACKAGE       the app's package name
#   PLAY_TRACK         the track to release to, such as internal, alpha (closed testing) or production
#   VERSION_CODE       the bundle's version code
#   VERSION_NAME       the version name, which names the release in Play Console
set -euo pipefail

if [ $# -ne 1 ]; then
  echo "Usage: $0 <signed.aab>" >&2
  exit 2
fi
bundle=$1

: "${PLAY_ACCESS_TOKEN:?is not set}"
: "${PLAY_PACKAGE:?is not set}"
: "${PLAY_TRACK:?is not set}"
: "${VERSION_CODE:?is not set}"
: "${VERSION_NAME:?is not set}"

api="https://androidpublisher.googleapis.com/androidpublisher/v3/applications/$PLAY_PACKAGE"
upload_api="https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/$PLAY_PACKAGE"

# The token reaches curl through a header file, never the command line.
headers=$(mktemp)
trap 'rm -f "$headers"' EXIT
printf 'Authorization: Bearer %s\n' "$PLAY_ACCESS_TOKEN" >"$headers"

# Prints the response, or on an HTTP error sends it to stderr, where it tells what Google Play rejected.
play() {
  local response
  if ! response=$(curl --silent --show-error --fail-with-body --retry 3 --header "@$headers" "$@"); then
    printf '%s\n' "$response" >&2
    return 1
  fi
  printf '%s\n' "$response"
}

edit=$(play --request POST "$api/edits" | jq -r '.id')

bundles=$(play "$api/edits/$edit/bundles")
if jq -e --argjson code "$VERSION_CODE" '.bundles // [] | any(.versionCode == $code)' <<<"$bundles" >/dev/null; then
  echo "Google Play already has version code $VERSION_CODE"
else
  uploaded=$(
    play --request POST \
      --header 'Content-Type: application/octet-stream' \
      --data-binary "@$bundle" \
      --max-time 1800 \
      "$upload_api/edits/$edit/bundles?uploadType=media" | jq -r '.versionCode'
  )
  if [ "$uploaded" != "$VERSION_CODE" ]; then
    echo "The bundle carries version code $uploaded, not $VERSION_CODE" >&2
    exit 1
  fi
fi

jq -n --arg track "$PLAY_TRACK" --arg name "$VERSION_NAME" --arg code "$VERSION_CODE" \
  '{track: $track, releases: [{name: $name, versionCodes: [$code], status: "completed"}]}' |
  play --request PUT --header 'Content-Type: application/json' --data-binary @- \
    "$api/edits/$edit/tracks/$PLAY_TRACK" >/dev/null

play --request POST "$api/edits/$edit:commit" >/dev/null
echo "Released $VERSION_NAME ($VERSION_CODE) to the $PLAY_TRACK track"
