#!/data/data/com.termux/files/usr/bin/bash
# Starts Pipkin's helper (run.sh) as a Termux background task unless it is already running.
# --shell: called from ~/.bashrc; doesn't wait for am, and warns when the helper has a problem.
set -u
dir="$HOME/.pipkin/helper"
shell=false
[ "${1:-}" = --shell ] && shell=true

alive() { # $1 = pid file, $2 = text the process's command line must contain
  local pid
  pid=$(cat "$1" 2>/dev/null) && [ -n "$pid" ] && grep -qaF "$2" "/proc/$pid/cmdline" 2>/dev/null
}

alive "$dir/helper.pid" helper.mjs && exit 0

if $shell; then
  case $(cat "$dir/status" 2>/dev/null) in
    node-missing) echo "Pipkin: Node.js is missing. Paste Pipkin's setup command again." ;;
    node-crashed*) echo "Pipkin: The helper keeps stopping. Paste Pipkin's setup command again." ;;
    no-key) echo "Pipkin: The helper isn't paired. Paste Pipkin's setup command again." ;;
    ports-taken) echo "Pipkin: Another app is using the helper's ports. Restart your phone." ;;
  esac
fi

# A live wrapper without a problem in status is starting the helper right now. One that has a problem is waiting
# out a backoff and would retry late; replace it with a fresh one.
if alive "$dir/run.pid" run.sh; then
  [ -s "$dir/status" ] || exit 0
  kill "$(cat "$dir/run.pid")" 2>/dev/null
fi

start() {
  am startservice -n com.termux/.app.TermuxService -a com.termux.service.action.service_execute \
    -d "file://$dir/run.sh" --ez com.termux.execute.background true >/dev/null 2>&1
}

if $shell; then
  start &
  disown
  exit 0
fi
echo "Starting Pipkin's helper…"
start || {
  echo "Pipkin: Couldn't start the helper." >&2
  exit 1
}
