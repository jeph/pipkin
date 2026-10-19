#!/data/data/com.termux/files/usr/bin/bash
# Keeps Pipkin's helper running as a Termux background task. Started by start.sh and the boot script.
# Helper exit codes: 0 another helper runs or it was stopped, 75 restart with fresh code and key,
# 76 every port is taken (the helper wrote status), 78 no key (nothing to do until setup runs again).
set -u
# The folder this script is in: ~/.pipkin/helper for the release, ~/.pipkin-dev/helper for the debug build.
dir=$(dirname "$(readlink -f "$0")")

alive() { # $1 = pid file, $2 = text the process's command line must contain
  local pid
  pid=$(cat "$1" 2>/dev/null) && [ -n "$pid" ] && grep -qaF "$2" "/proc/$pid/cmdline" 2>/dev/null
}

set_status() {
  printf '%s\n' "$1" >"$dir/status.$$.tmp" && mv -f "$dir/status.$$.tmp" "$dir/status"
}

# One wrapper at a time; start.sh replaces a wrapper that is waiting out a backoff.
if alive "$dir/run.pid" run.sh && [ "$(cat "$dir/run.pid")" != $$ ]; then exit 0; fi
printf '%s\n' $$ >"$dir/run.pid"

delay=1
backoff() {
  sleep "$delay"
  delay=$((delay * 2 > 60 ? 60 : delay * 2))
}

while :; do
  if ! command -v node >/dev/null 2>&1; then
    set_status node-missing
    backoff
    continue
  fi
  started=$SECONDS
  node "$dir/helper.mjs"
  code=$?
  case $code in
    0 | 78) break ;;
    75)
      delay=1
      continue
      ;;
    76) ;;
    *) set_status "node-crashed $code" ;;
  esac
  if ((SECONDS - started >= 60)); then delay=1; fi
  backoff
done
if [ "$(cat "$dir/run.pid" 2>/dev/null)" = $$ ]; then rm -f "$dir/run.pid"; fi
