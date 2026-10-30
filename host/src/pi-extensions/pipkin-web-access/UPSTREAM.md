# pipkin-web-access: pi-web-access, vendored

This folder is [pi-web-access](https://github.com/nicobailon/pi-web-access) by Nico Bailon, MIT License (see `LICENSE`), copied into Pipkin so that Pipkin can fix it without waiting for upstream. Pipkin ships it as the extension `pipkin-web-access`.

- Upstream version: 0.46.0, tag `v0.46.0`, commit `4af1a7f7757d450ba0afefe2d98737384dc9a9b4`.

## What differs from upstream

Everything below is a deliberate Pipkin change. Keep each one when folding in a release, unless upstream made it unnecessary.

### In this folder

1. `tool-activation.ts`, `index.ts` and `gemini-search.ts` are the only files whose code differs. Search for `// Pipkin:`.
   - `tool-activation.ts`, upstream: `import { buildSessionContext, type ExtensionAPI, type ExtensionContext, type SessionContext } from "@earendil-works/pi-coding-agent";`
     Pipkin: `import type { ExtensionAPI, ExtensionContext, SessionContext } from "@earendil-works/pi-coding-agent";`
   - `tool-activation.ts`, upstream: `const messages = buildSessionContext(ctx.sessionManager.getBranch()).messages;`
     Pipkin: `const messages = ctx.sessionManager.buildSessionProjection().messages;`
   - Why: Node, not Pi's loader, resolves this extension's imports, so a value import from Pi loads a second, unbundled copy of Pi, about 1,200 modules, every time Pi starts. Type-only imports are fine. A new value import from `@earendil-works/pi-coding-agent` in an upstream release needs the same treatment.
   - `index.ts`, upstream: `import { showUpgradeNotice } from "./upgrade-notice.ts";`, and in the `session_start` handler `void showUpgradeNotice(ctx).catch((error) => console.error("Failed to show the pi-web-access upgrade notice:", error));`
     Pipkin: neither line, and a `// Pipkin:` comment where the call was.
   - Why: after an update, upstream's first session shows the highlights of the pi-web-access releases the user skipped, with a link to upstream's changelog, and records the version it showed in `pi-web-access/last-seen-version.json` in Pi's config folder. Pipkin updates this extension with the app, so the notice would announce an update to a package the user never installed. It also imports upstream's `package.json` and reads its `CHANGELOG.md`, which aren't copied, so the bundle wouldn't build, and it imports `getAgentDir` from Pi as a value.
   - `gemini-search.ts`, upstream, at the top of `validateSearchModelOverride`:
     ```ts
     if (model === undefined) return undefined;
     if (typeof model !== "string" || !model.trim()) {
     ```
     Pipkin:
     ```ts
     if (model === undefined || (typeof model === "string" && !model.trim())) return undefined;
     if (typeof model !== "string") {
     ```
   - Why: some models, GPT among them, fill in every optional tool argument, so they call `web_search` with `model: ""`. Upstream rejects that with "model must be a non-empty model ID", and the model then tries other providers it has no key for, so its searches failed. An empty `provider` already counts as omitted upstream; Pipkin treats an empty `model` the same way.
2. Every `.ts` file starts with one extra line, `// Vendored from pi-web-access <version> (https://github.com/nicobailon/pi-web-access), MIT License: see LICENSE and UPSTREAM.md.`, which Pipkin's rules for copied code require. Upstream's own first line follows it.
3. Only upstream's top-level `*.ts` files and `LICENSE` are copied. Its `package.json`, `CHANGELOG.md`, `tsconfig.json`, tests, scripts, docs and media are left out. `mcp-cli.ts`, upstream's standalone MCP server, is copied but never built, and so is `upgrade-notice.ts`, which nothing imports.

### Outside this folder

- `host/build.ts` bundles `index.ts` into the APK asset `pipkin-extensions/pipkin-web-access.js`. It bundles `typebox` in, because loading a separate typebox from `node_modules` costs more at startup than the rest of the extension, and leaves out `@earendil-works/*` and every package in `app/src/main/assets/pi/package.json`. The bundle starts with the MIT notices of pi-web-access and typebox, which the license requires in every copy; if upstream starts bundling more packages, add their notices there too. It also writes `licenses/pi-web-access.txt` for the app's license list, whose first line names the upstream version.
- `app/src/main/assets/pi/package.json` lists upstream's runtime `dependencies`, pinned to exact versions, so that `npm ci` installs them in Pipkin's Pi runtime.
- `BundledExtension.WebAccess` in `app/src/main/kotlin/io/jeph/pipkin/data/BundledExtension.kt` sets `usesRuntimePackages`, so `ensure` installs it as `<root>/pi/extensions/pipkin-web-access/index.js`. There Node finds those packages, and because the runtime's `package.json` says `"type": "module"`, Pi's loader imports the file natively. Outside that folder Pi's loader would transpile it instead and cache the result in Termux's `$TMPDIR`, which Termux wipes when its service stops, so the first chat after that would transpile 1.3 MB again.
- `host/src/modes/rpc.ts` sets `toolActivation` to `eager` in `web-search.json` unless the user chose a mode or set `toolExposure` to `deferred` (the extension refuses to load with both), and leaves `pipkin-web-access` out when the user installs `npm:pi-web-access` in Pi's global settings, because Pi exits when two extensions register the same tools.
- `host/tsconfig.json` and `host/.oxlintrc.json` leave this folder out, because upstream's `tsconfig.json` is looser than the host's. Only esbuild checks it, when it bundles the extension.

## Updating to a new upstream release

Fold upstream's changes into this folder rather than copying the release over it, so Pipkin's changes stay. In the commands, `OLD` is the version above and `NEW` is the release to take.

1. Read upstream's `CHANGELOG.md` between the two versions, and check whether each change above is still needed. For example, upstream may have dropped the `buildSessionContext` import itself, or added another value import from Pi.
2. Make upstream's diff for the copied files, and apply it from the repository root:

   ```sh
   git clone https://github.com/nicobailon/pi-web-access /tmp/pi-web-access
   git -C /tmp/pi-web-access diff vOLD vNEW -- '*.ts' ':(exclude)*/**' LICENSE > /tmp/pi-web-access.patch
   patch -p1 -d host/src/pi-extensions/pipkin-web-access < /tmp/pi-web-access.patch
   ```

   `patch` tolerates the one-line headers. Resolve any hunk it rejects by hand (it leaves a `.rej` file next to the file), then delete the `.rej` and `.orig` files.
3. Give every new file the header, and change the version in every header, at the top of this file, and in the `pi-web-access` license text in `host/build.ts`.
4. Compare upstream's `package.json` `dependencies` between the two tags (`git -C /tmp/pi-web-access diff vOLD vNEW -- package.json`). Copy any change into `app/src/main/assets/pi/package.json` with exact versions, check each new package's license, and refresh the lockfile with `npm install --package-lock-only --ignore-scripts` in `app/src/main/assets/pi`.
5. If upstream changed its `typebox` version, check that it still matches the one in `host/package.json`, which is also the one Pi uses.
6. Update the version at the top of this file and the list above, then build and test as for any host change, including a chat on the emulator that searches the web and fetches a page.
