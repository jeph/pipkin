import { HostError } from "../env.js";

/** [value] as one single-quoted word for `bash`, or any POSIX shell. */
export function quote(value: string): string {
  // A shell argument ends at a NUL, so a path holding one would silently name another file.
  if (value.includes("\0")) throw new HostError("bad_params", "A path or command can't contain a NUL character");
  return `'${value.replaceAll("'", `'\\''`)}'`;
}

/**
 * The remote command line that runs [script] in `bash`, whatever the user's login shell is (fish and zsh included):
 * base64 has no character any shell treats specially, so the script arrives intact. Keep it small, as one argument is
 * limited to 128 KiB on Linux; file contents go through standard input.
 */
export function remoteScript(script: string): string {
  return `exec bash -c 'eval "$(printf %s ${Buffer.from(script).toString("base64")} | base64 -d)"'`;
}

/**
 * A script that runs [command] with `bash` in [cwd] under a watchdog, for anything that can run long. Killing the local
 * `ssh` doesn't stop a remote command, as nothing tells it without a terminal. So the command runs as its own process
 * group (`set -m`), and a watcher kills the group once the script's standard input closes, which happens when the local
 * `ssh` dies or Pipkin closes the pipe. The watcher's output goes to `/dev/null`, as sshd holds a session open while
 * anything still has its output. With `set -m`, bash also reports the command's end ("[1]+ Done …") on its own standard
 * error, so once `cd` has had its say that goes to `/dev/null`, and only the command gets the real one (on fd 3). Run
 * it with standard input held open until the command ends; it exits with the command's status.
 */
export function watched(command: string, cwd: string): string {
  return [
    "set -m",
    `cd -- ${quote(cwd)} || exit 1`,
    "exec 3>&2 2>/dev/null",
    `bash -c ${quote(command)} </dev/null 2>&3 3>&- &`,
    "child=$!",
    `{ cat >/dev/null; kill -TERM -- "-$child" 2>/dev/null; } >/dev/null 2>&1 3>&- &`,
    "watcher=$!",
    `wait "$child"`,
    "status=$?",
    `kill "$watcher" 2>/dev/null`,
    `exit "$status"`,
    "",
  ].join("\n");
}
