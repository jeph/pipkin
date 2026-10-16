#!/data/data/com.termux/files/usr/bin/bash
# Starts Pipkin's helper (run.sh) as a Termux background task unless it is already running.
# --shell: called by the login shell's hook that pipkin-setup.sh writes (~/.bashrc, ~/.zshrc, fish's conf.d); warns
# when the helper has a problem, and exits 3 when the calling terminal session should close (see below).
set -u
dir="$HOME/.pipkin/helper"
shell=false
[ "${1:-}" = --shell ] && shell=true

alive() { # $1 = pid file, $2 = text the process's command line must contain
  local pid
  pid=$(cat "$1" 2>/dev/null) && [ -n "$pid" ] && grep -qaF "$2" "/proc/$pid/cmdline" 2>/dev/null
}

# How many terminal sessions Termux has open: the processes the Termux app itself started that have a terminal, as
# the login shell running the hook does. 0 when that shell wasn't started by Termux (a nested shell, tmux).
terminals() {
  local stat fields app n=0 f
  read -r stat 2>/dev/null <"/proc/$PPID/stat" || { echo 0; return; }
  read -ra fields <<<"${stat##*) }"
  app=${fields[1]}
  # Android hides app processes, Termux's own included, from /proc; a parent shown there is another shell or tmux.
  [ -e "/proc/$app" ] && { echo 0; return; }
  for f in /proc/[0-9]*/stat; do
    read -r stat 2>/dev/null <"$f" || continue
    read -ra fields <<<"${stat##*) }"
    [ "${fields[1]}" = "$app" ] && [ "${fields[4]}" != 0 ] && n=$((n + 1))
  done
  echo "$n"
}

# A helper without its wrapper is stopping itself (see daemon.ts), so it doesn't count.
alive "$dir/helper.pid" helper.mjs && alive "$dir/run.pid" run.sh && exit 0

# Says what the helper's last run reported going wrong, if anything.
problem() {
  case $(cat "$dir/status" 2>/dev/null) in
    node-missing) echo "Pipkin: Node.js is missing. Paste Pipkin's setup command again." ;;
    node-crashed*) echo "Pipkin: The helper keeps stopping. Paste Pipkin's setup command again." ;;
    no-key) echo "Pipkin: The helper isn't paired. Paste Pipkin's setup command again." ;;
    ports-taken) echo "Pipkin: Another app is using the helper's ports. Restart your phone." ;;
  esac
}

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
  # Pipkin opens a new Termux session each time it starts the helper, and Termux refuses to open a ninth. A session
  # that started the helper has done its job once the helper runs, so it closes then, unless it's Termux's only one.
  # Should the helper not come up within 15 s, the session stays open with whatever went wrong, as this start
  # reported it.
  if [ "$(terminals)" -gt 1 ]; then
    start
    for ((i = 0; i < 60; i++)); do
      alive "$dir/helper.pid" helper.mjs && exit 3
      sleep 0.25
    done
    problem
    exit 0
  fi
  problem
  start &
  disown
  exit 0
fi
echo "Starting Pipkin's helper…"
start || {
  echo "Pipkin: Couldn't start the helper." >&2
  exit 1
}
