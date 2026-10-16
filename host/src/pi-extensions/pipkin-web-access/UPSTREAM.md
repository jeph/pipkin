# pipkin-web-access: pi-web-access, vendored

This folder is [pi-web-access](https://github.com/nicobailon/pi-web-access) by Nico Bailon, MIT License (see `LICENSE`), copied into Pipkin so that Pipkin can fix it without waiting for upstream. Pipkin ships it as the extension `pipkin-web-access`.

- Upstream version: 0.39.0, tag `v0.39.0`, commit `8e812826269624406458531f9231b679547f5a3a`.
- Copied: every top-level `*.ts` file and `LICENSE`. Upstream's tests, scripts, docs and media are left out. `mcp-cli.ts`, upstream's standalone MCP server, is copied with the rest but not built.
- Every `.ts` file starts with a one-line header naming the source, version and license, which Pipkin's rules for copied code require.

## Pipkin's changes

Search for `// Pipkin:` to find each one.

1. `tool-activation.ts`: read the transcript through `ctx.sessionManager.buildSessionProjection()` instead of importing `buildSessionContext` from `@earendil-works/pi-coding-agent`. Node, not Pi's loader, resolves this extension's imports, so that import loaded a second, unbundled copy of Pi, about 1,200 modules, every time Pi started.

## How Pipkin builds and ships it

- `host/build.ts` bundles `index.ts` into the APK asset `pipkin-extensions/pipkin-web-access.js`, like Pipkin's own extensions. It bundles `typebox` in too, because loading a separate typebox from `node_modules` costs more at startup than the rest of the extension. It leaves out `@earendil-works/*` and every package in `app/src/main/assets/pi/package.json`. The bundle starts with the MIT notices of pi-web-access and typebox, which the license requires in every copy; if upstream starts bundling more packages, add their notices there too.
- Upstream's runtime `dependencies` are listed in `app/src/main/assets/pi/package.json`, pinned to exact versions, so that `npm ci` installs them in Pipkin's Pi runtime.
- `BundledExtension.WebAccess` marks it as using runtime packages, so the host saves it as `<root>/pi/extensions/pipkin-web-access/index.js`. There Node finds those packages, and because the runtime's `package.json` says `"type": "module"`, Pi's loader imports the file natively. Outside that folder Pi's loader would transpile it instead and cache the result in Termux's `$TMPDIR`, which Termux wipes when its service stops, so the first chat after that would transpile 1.3 MB again.
- When the user installs `npm:pi-web-access` in Pi's global settings, the host leaves `pipkin-web-access` out, because Pi exits when two extensions register the same tools.

Upstream's `tsconfig.json` is looser than the host's, so `host/tsconfig.json` and `host/.oxlintrc.json` leave this folder out. Only esbuild checks it, when it bundles the extension.

## Updating to a new upstream release

Fold upstream's changes into this folder rather than copying the release over it, so Pipkin's changes stay. In the commands, `OLD` is the version above and `NEW` is the release to take.

1. Read upstream's `CHANGELOG.md` between the two versions, and check whether Pipkin's changes are still needed. For example, upstream may have dropped the `buildSessionContext` import itself.
2. Make upstream's diff for the copied files, and apply it from the repository root:

   ```sh
   git clone https://github.com/nicobailon/pi-web-access /tmp/pi-web-access
   git -C /tmp/pi-web-access diff vOLD vNEW -- '*.ts' ':(exclude)*/**' LICENSE > /tmp/pi-web-access.patch
   patch -p1 -d host/src/pi-extensions/pipkin-web-access < /tmp/pi-web-access.patch
   ```

   `patch` tolerates the one-line headers. Resolve any hunk it rejects by hand (it leaves a `.rej` file next to the file), then delete the `.rej` and `.orig` files.
3. Give every new file the header, and change the version in every header and at the top of this file.
4. Compare upstream's `package.json` `dependencies` between the two tags (`git -C /tmp/pi-web-access diff vOLD vNEW -- package.json`). Copy any change into `app/src/main/assets/pi/package.json` with exact versions, check each new package's license, and refresh the lockfile with `npm install --package-lock-only --ignore-scripts` in `app/src/main/assets/pi`.
5. If upstream changed its `typebox` version, check that it still matches the one in `host/package.json`, which is also the one Pi uses.
6. Update the version, commit and change list above, then build and test as for any host change, including a chat on the emulator that searches the web and fetches a page.
