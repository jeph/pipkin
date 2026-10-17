#!/data/data/com.termux/files/usr/bin/bash
# Pipkin's Termux setup for the Google Play build of Termux. Pipkin fills in the @…@ values and serves this
# from its setup command, with PIPKIN_URL and PIPKIN_TOKEN in the environment. Every step checks before it
# acts, so running the command again after a failure finishes the setup.
set -u

say() { printf 'Pipkin: %s\n' "$*"; }
fail() {
  say "$*" >&2
  exit 1
}

# 1. Environment.
[ -n "${PREFIX:-}" ] && [ -n "${HOME:-}" ] || fail "Run this inside Termux."
[ -n "${PIPKIN_URL:-}" ] && [ -n "${PIPKIN_TOKEN:-}" ] || fail "Copy the setup command in Pipkin and paste it here."
dir="$HOME/.pipkin/helper"
package='@PIPKIN_PACKAGE@'

# Termux sessions run the shell that ~/.termux/shell links to (chsh sets it), or bash without a usable one; this picks
# it the way $PREFIX/bin/login does. Pipkin starts its helper by opening a session, so that shell must run one of the
# hooks written in step 4.
login_shell=bash
if [ -L "$HOME/.termux/shell" ]; then
  target=$(readlink "$HOME/.termux/shell")
  if [ -f "$target" ] && [ -x "$target" ] && [ ! "$target" -ef "$PREFIX/bin/login" ]; then
    login_shell=$(basename "$(readlink -f "$target")")
  fi
fi
case $login_shell in
  bash | zsh | fish) ;;
  *) fail "Pipkin needs bash, zsh or fish as Termux's shell. Run chsh -s bash, then paste the command again." ;;
esac

alive() { # $1 = pid file, $2 = text the process's command line must contain
  local pid
  pid=$(cat "$1" 2>/dev/null) && [ -n "$pid" ] && grep -qaF "$2" "/proc/$pid/cmdline" 2>/dev/null
}

# Replaces $1 with stdin through a temp file, unless it already has that content.
write_file() {
  local tmp="$1.$$.tmp"
  cat >"$tmp" || {
    rm -f "$tmp"
    return 1
  }
  if cmp -s "$tmp" "$1"; then
    rm -f "$tmp"
  else
    mv -f "$tmp" "$1"
  fi
}

# 2. Shared storage, so Pipkin can reach the files you work on. Waiting for the grant brings the user back to Termux
# before the setup ends, so the switch back to Pipkin isn't blocked. A declined prompt doesn't stop the setup.
if [ ! -w "$HOME/storage/shared" ]; then
  termux-setup-storage || true
  say "In All files access, tap Termux and turn on Allow access to manage all files, then come back here."
  for ((i = 0; i < 120; i++)); do
    [ -w "$HOME/storage/shared" ] && break
    sleep 1
  done
fi

# 3. Node.js 22.19 or newer.
node_ok() {
  node -e 'const [a, b] = process.versions.node.split(".").map(Number); process.exit(a > 22 || (a === 22 && b >= 19) ? 0 : 1)' \
    >/dev/null 2>&1
}
dpkg --configure -a || true
if ! node_ok; then
  say "Installing Node.js. This can take a few minutes."
  yes | pkg upgrade -y
  pkg install -y nodejs
  node_ok || fail "Node.js couldn't be installed. Check your internet connection and paste the command again."
fi

# 4. The helper's files, the boot script, the shell hooks and pipkin-start.
{ mkdir -p "$dir" && chmod 700 "$dir"; } || fail "Couldn't create $dir."
fetch() { # $1 = name, $2 = sha256, $3 = mode
  local tmp="$dir/$1.$$.tmp"
  [ "$(sha256sum "$dir/$1" 2>/dev/null | cut -d' ' -f1)" = "$2" ] && chmod "$3" "$dir/$1" && return 0
  curl -fsS -m 60 "$PIPKIN_URL/$1" -o "$tmp" || {
    rm -f "$tmp"
    fail "Couldn't download $1 from Pipkin. Keep Pipkin open and paste the command again."
  }
  if [ "$(sha256sum "$tmp" | cut -d' ' -f1)" != "$2" ]; then
    rm -f "$tmp"
    fail "$1 from Pipkin was damaged. Paste the command again."
  fi
  { chmod "$3" "$tmp" && mv -f "$tmp" "$dir/$1"; } || fail "Couldn't install $1."
}
fetch helper.mjs '@HELPER_SHA256@' 600
fetch run.sh '@RUN_SHA256@' 700
fetch start.sh '@START_SHA256@' 700

boot="$HOME/.termux/boot/pipkin-helper"
mkdir -p "${boot%/*}" || fail "Couldn't create ~/.termux/boot."
# shellcheck disable=SC2016 # $HOME expands when the boot script runs.
printf '#!%s/bin/sh\nexec "$HOME/.pipkin/helper/run.sh"\n' "$PREFIX" | write_file "$boot" ||
  fail "Couldn't write the boot script."
chmod 700 "$boot" || fail "Couldn't write the boot script."

# Puts $2 in the file $1 between Pipkin's markers: in place of the first complete marked block, dropping any later
# ones, or at the end when there is none. A start marker without an end marker is left alone.
put_block() {
  local rc=$1
  [ -L "$rc" ] && rc=$(readlink -f "$rc")
  [ -e "$rc" ] || : >"$rc" || return 1
  PIPKIN_BLOCK=$2 awk '
    function flush() { for (i = 0; i < n; i++) print held[i]; n = 0 }
    $0 == "# >>> pipkin >>>" { flush(); inside = 1; held[n++] = $0; next }
    inside && $0 == "# <<< pipkin <<<" { n = 0; inside = 0; if (!done) print ENVIRON["PIPKIN_BLOCK"]; done = 1; next }
    inside { held[n++] = $0; next }
    { print }
    END { flush(); if (!done) print ENVIRON["PIPKIN_BLOCK"] }
  ' "$rc" | write_file "$rc"
}

# The session Pipkin opens to start the helper runs these hooks; start.sh does the rest. A shell gets its hook when
# it's installed, so switching to it later with chsh keeps working; bash is always installed. A session that stays
# open starts with status 0, so prompts that show a failed status don't flag it.
# shellcheck disable=SC2016 # $HOME expands when the shell runs the hook.
sh_hook='# >>> pipkin >>>
if [ -x "$HOME/.pipkin/helper/start.sh" ]; then
  "$HOME/.pipkin/helper/start.sh" --shell
  # 3: this session only started the helper, and closing it keeps Termux under its limit of 8 sessions.
  if [ $? = 3 ]; then exit 0; fi
fi
# <<< pipkin <<<'
put_block "$HOME/.bashrc" "$sh_hook" || fail "Couldn't update ~/.bashrc."
if [ -x "$PREFIX/bin/zsh" ] || [ "$login_shell" = zsh ]; then
  put_block "${ZDOTDIR:-$HOME}/.zshrc" "$sh_hook" || fail "Couldn't update ~/.zshrc."
fi
if [ -x "$PREFIX/bin/fish" ] || [ "$login_shell" = fish ]; then
  # A file of its own, since fish sources conf.d in every fish process, scripts and fish -c included. exit there would
  # only end the file, so the shell gives way to a command that exits 0 instead.
  fish_hook="${XDG_CONFIG_HOME:-$HOME/.config}/fish/conf.d/pipkin.fish"
  mkdir -p "${fish_hook%/*}" || fail "Couldn't create ${fish_hook%/*}."
  write_file "$fish_hook" <<'EOF' || fail "Couldn't write $fish_hook."
# Written by Pipkin's Termux setup, which replaces this file each time it runs.
if status is-interactive; and test -x "$HOME/.pipkin/helper/start.sh"
    "$HOME/.pipkin/helper/start.sh" --shell
    # 3: this session only started the helper, and closing it keeps Termux under its limit of 8 sessions.
    if test $status = 3
        exec true
    end
end
EOF
fi

if [ "$(readlink "$PREFIX/bin/pipkin-start" 2>/dev/null)" != "$dir/start.sh" ]; then
  ln -sfn "$dir/start.sh" "$PREFIX/bin/pipkin-start" || fail "Couldn't create pipkin-start."
fi

# 5. Pairing.
node "$dir/helper.mjs" pair
case $? in
  0) ;;
  10)
    grep -qxE '[0-9a-f]{64}' "$dir/key" 2>/dev/null ||
      fail "This setup command has expired. In Pipkin, tap Copy setup command again."
    ;;
  *) exit 1 ;;
esac

# 6. Start the helper, or restart it with the new files and key.
old=
if alive "$dir/helper.pid" helper.mjs; then
  old=$(cat "$dir/helper.pid")
  kill -USR1 "$old"
else
  rm -f "$dir/helper.port"
  "$dir/start.sh" || exit 1
fi
ready=false
for ((i = 1; i <= 40; i++)); do
  if [ -s "$dir/helper.port" ] && alive "$dir/helper.pid" helper.mjs && [ "$(cat "$dir/helper.pid")" != "$old" ]; then
    ready=true
    break
  fi
  # A helper that ran without run.sh doesn't come back after USR1.
  if [ "$i" = 12 ] && [ -n "$old" ] && ! alive "$dir/helper.pid" helper.mjs && ! alive "$dir/run.pid" run.sh; then
    "$dir/start.sh" || exit 1
  fi
  sleep 0.25
done
$ready || fail "The helper didn't start. Paste the command again."

# 7. Done; back to Pipkin. Android blocks the switch when Termux isn't on screen, so say how to get there too.
echo "Pipkin setup done. Switch back to Pipkin if it doesn't open on its own."
am start --user 0 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER \
  -n "$package/io.jeph.pipkin.MainActivity" >/dev/null 2>&1 || true
