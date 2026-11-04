/**
 * File operations on a remote host, each one `ssh` round trip. Scripts run in `bash` and use only what GNU, BSD and
 * busybox userlands share, and they report the usual failures through their exit status, as errno names.
 */
import { quote } from "./script.js";
import { type Remote, runScript, type RunOptions } from "./ssh.js";

/** The errno names remote operations fail with; `EIO` stands for anything else, with the remote's message. */
export type RemoteErrno = "ENOENT" | "EACCES" | "ENOTDIR" | "EISDIR" | "EIO";

/** A remote file operation that failed, worded like Node's own errors so tools can pass it on as is. */
export class RemoteFileError extends Error {
  constructor(
    readonly code: RemoteErrno,
    readonly path: string,
    message: string,
  ) {
    super(message);
  }
}

const EXIT_ERRNO: Record<number, RemoteErrno> = { 2: "ENOENT", 13: "EACCES", 20: "ENOTDIR", 21: "EISDIR" };
const DESCRIPTION: Record<RemoteErrno, string> = {
  ENOENT: "no such file or directory",
  EACCES: "permission denied",
  ENOTDIR: "not a directory",
  EISDIR: "illegal operation on a directory",
  EIO: "i/o error",
};

type Options = Pick<RunOptions, "signal" | "timeoutMs" | "idleTimeoutMs">;

/** Runs [script], turning a failure exit into a [RemoteFileError] for [syscall] on [path]. */
async function runFileScript(remote: Remote, script: string, syscall: string, path: string, options: RunOptions): Promise<Buffer> {
  const result = await runScript(remote, script, options);
  if (result.code === 0) return result.stdout;
  const code = (result.code === null ? undefined : EXIT_ERRNO[result.code]) ?? "EIO";
  const detail = code === "EIO" && result.stderr.trim() !== "" ? `: ${result.stderr.trim()}` : "";
  throw new RemoteFileError(code, path, `${code}: ${DESCRIPTION[code]}, ${syscall} '${path}'${detail}`);
}

/** Sets `fmt` to the `stat` options that print `size mtime` (seconds), following links: GNU `-c`, else BSD `-f`. */
const STAT_FORMAT = `if stat -c %s / >/dev/null 2>&1; then fmt=(-L -c '%s %Y'); else fmt=(-L -f '%z %m'); fi`;

/** Remote file [path]'s whole content. */
export async function readRemoteFile(remote: Remote, path: string, options: Options = {}): Promise<Buffer> {
  const script = [
    `p=${quote(path)}`,
    `[ -d "$p" ] && exit 21`,
    `[ -e "$p" ] || exit 2`,
    `[ -r "$p" ] || exit 13`,
    `exec cat -- "$p"`,
  ].join("\n");
  return runFileScript(remote, script, "open", path, options);
}

/** Resolves when remote file [path] exists and can be read. */
export async function accessRemote(remote: Remote, path: string, options: Options = {}): Promise<void> {
  const script = [`p=${quote(path)}`, `[ -e "$p" ] || exit 2`, `[ -r "$p" ] || exit 13`].join("\n");
  await runFileScript(remote, script, "access", path, options);
}

/** Replaces remote file [path]'s content with [data], which goes through standard input whatever its size. */
export async function writeRemoteFile(remote: Remote, path: string, data: Buffer | string, options: Options = {}): Promise<void> {
  const script = [
    `p=${quote(path)}`,
    `case $p in */*) d=\${p%/*}; d=\${d:-/} ;; *) d=. ;; esac`,
    `[ -d "$p" ] && exit 21`,
    `[ -d "$d" ] || exit 2`,
    `if [ -e "$p" ]; then [ -w "$p" ] || exit 13; else [ -w "$d" ] || exit 13; fi`,
    `cat > "$p"`,
  ].join("\n");
  await runFileScript(remote, script, "open", path, { ...options, stdin: Buffer.isBuffer(data) ? data : Buffer.from(data) });
}

/** Makes remote folder [path] and any missing parents. */
export async function mkdirRemote(remote: Remote, path: string, options: Options = {}): Promise<void> {
  const script = [`p=${quote(path)}`, `mkdir -p -- "$p" 2>/dev/null && exit 0`, `[ -e "$p" ] && exit 20`, "exit 13"].join("\n");
  await runFileScript(remote, script, "mkdir", path, options);
}

/** The remote user's home folder. */
export async function remoteHome(remote: Remote, options: Options = {}): Promise<string> {
  return (await runFileScript(remote, `printf '%s' "$HOME"`, "home", "~", options)).toString("utf8");
}

/** Remote folder [path] with every link resolved (`cd -P`). */
export async function realRemoteDir(remote: Remote, path: string, options: Options = {}): Promise<string> {
  const script = [
    `p=${quote(path)}`,
    `[ -e "$p" ] || exit 2`,
    `[ -d "$p" ] || exit 20`,
    `cd -P -- "$p" 2>/dev/null || exit 13`,
    // `cd -P` leaves the resolved path in `PWD`; `$(pwd -P)` would drop a trailing newline the name may end with.
    `printf '%s' "$PWD"`,
  ].join("\n");
  return (await runFileScript(remote, script, "realpath", path, options)).toString("utf8");
}

export type RemoteKind = "file" | "directory" | "other";

export interface RemoteStat {
  /** What [path] is, following links. */
  kind: RemoteKind;
  size: number;
  mtimeMs: number;
}

/** What remote [path] is, following links; null when nothing is there, a dangling link included. */
export async function statRemote(remote: Remote, path: string, options: Options = {}): Promise<RemoteStat | null> {
  const script = [
    STAT_FORMAT,
    `p=${quote(path)}`,
    `[ -e "$p" ] || exit 2`,
    `if [ -d "$p" ]; then k=directory; elif [ -f "$p" ]; then k=file; else k=other; fi`,
    `s=$(stat "\${fmt[@]}" -- "$p") || exit 13`,
    `printf '%s %s\\n' "$k" "$s"`,
  ].join("\n");
  let output: string;
  try {
    output = (await runFileScript(remote, script, "stat", path, options)).toString("utf8");
  } catch (error) {
    if (error instanceof RemoteFileError && error.code === "ENOENT") return null;
    throw error;
  }
  const [kind, size, mtime] = output.trim().split(" ");
  if (kind !== "directory" && kind !== "file" && kind !== "other") throw new RemoteFileError("EIO", path, `EIO: unexpected stat output, stat '${path}'`);
  return { kind, size: Number(size), mtimeMs: Number(mtime) * 1000 };
}

export interface RemoteEntry {
  name: string;
  /** What the entry is, following a link. */
  kind: RemoteKind;
  size: number;
  mtimeMs: number;
  symlink: boolean;
  /** A link's target with every link resolved (`realpath`); null for other entries, or when the remote can't tell. */
  target: string | null;
}

/**
 * The script [listRemoteDir] runs for [path], for a caller that prepends its own checks to the same round trip; its
 * output goes to [parseListing].
 */
export function listingScript(path: string): string {
  return [
    STAT_FORMAT,
    `d=${quote(path)}`,
    `[ -e "$d" ] || exit 2`,
    `[ -d "$d" ] || exit 20`,
    `[ -r "$d" ] && [ -x "$d" ] || exit 13`,
    `cd -- "$d" || exit 13`,
    "shopt -s dotglob nullglob",
    "names=()",
    "for f in *; do",
    `  [ -e "$f" ] || continue`,
    `  if [ -d "$f" ]; then k=d; elif [ -f "$f" ]; then k=f; else k=o; fi`,
    `  l=0; t=`,
    // Captured with an `x` after it, then cut back, as `$(…)` drops trailing newlines a target's name may end with.
    `  if [ -L "$f" ]; then l=1; t=$({ realpath "./$f" 2>/dev/null || readlink -f "./$f" 2>/dev/null; } && echo x); t=\${t%x}; t=\${t%$'\\n'}; fi`,
    `  printf '%s\\0%s\\0%s\\0%s\\0' "$k" "$l" "$t" "$f"`,
    `  names+=("./$f")`,
    "done",
    `printf 'end\\0'`,
    `[ "\${#names[@]}" -eq 0 ] && exit 0`,
    `printf '%s\\0' "\${names[@]}" | xargs -0 stat "\${fmt[@]}" -- || exit 13`,
  ].join("\n");
}

/**
 * Remote folder [path]'s entries, hidden ones included, with links followed; dangling links are left out. Names may
 * hold any byte but NUL and `/`, so they travel NUL-separated; sizes and times come from one batched `stat`.
 */
export async function listRemoteDir(remote: Remote, path: string, options: Options = {}): Promise<RemoteEntry[]> {
  const output = await runFileScript(remote, listingScript(path), "scandir", path, options);
  return parseListing(output, path);
}

const KINDS: Record<string, RemoteKind> = { d: "directory", f: "file", o: "other" };

/** The entries in [listRemoteDir]'s output: NUL-separated records up to `end`, then one `size mtime` line each. */
export function parseListing(output: Buffer, path: string): RemoteEntry[] {
  const records: { kind: RemoteKind; symlink: boolean; target: string | null; name: string }[] = [];
  let start = 0;
  const field = (): string => {
    const end = output.indexOf(0, start);
    if (end < 0) throw new RemoteFileError("EIO", path, `EIO: truncated listing, scandir '${path}'`);
    const value = output.subarray(start, end).toString("utf8");
    start = end + 1;
    return value;
  };
  for (;;) {
    const kind = field();
    if (kind === "end") break;
    const symlink = field() === "1";
    const target = field();
    records.push({ kind: KINDS[kind] ?? "other", symlink, target: symlink && target !== "" ? target : null, name: field() });
  }
  const sizes = output.subarray(start).toString("utf8").split("\n").filter((line) => line !== "");
  if (sizes.length !== records.length) throw new RemoteFileError("EIO", path, `EIO: entries changed while listing, scandir '${path}'`);
  return records.map((record, index) => {
    const [size, mtime] = (sizes[index] ?? "").split(" ");
    return { ...record, size: Number(size), mtimeMs: Number(mtime) * 1000 };
  });
}
