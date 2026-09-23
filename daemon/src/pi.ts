/** Pinned Pi runtime; must match `pi/package.json` in the APK assets and TermuxProtocol.PI_VERSION. */
export const PI_VERSION = "0.85.1";

export const PI_PACKAGE = "@earendil-works/pi-coding-agent";

/** Fixed RPC-mode flags; the daemon appends `--session-dir <workspace>/sessions`. */
export const PI_ARGS = [
  "--offline",
  "--no-approve",
  "--no-extensions",
  "--no-skills",
  "--no-prompt-templates",
  "--no-themes",
  "--no-context-files",
  "--tools",
  "read,write,edit,bash",
] as const;
