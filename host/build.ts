/**
 * Bundles the host into `$OUT/pipkin-host.mjs`, and each Pi extension Pipkin makes into
 * `$OUT/pipkin-extensions/<name>.js`. Both ship as APK assets. The extensions stay out of the host, which Pipkin hands
 * to Termux inside an Intent with a size limit (`HostLauncher`); Pipkin sends their source over the host's socket
 * instead.
 */
import { build } from "esbuild";

const out = process.env.OUT;
if (!out) throw new Error("Set OUT to the directory the bundles go in");

const common = { bundle: true, platform: "node", format: "esm", target: "node22", logLevel: "warning" } as const;

await Promise.all([
  build({ ...common, entryPoints: ["src/main.ts"], outfile: `${out}/pipkin-host.mjs` }),
  build({
    ...common,
    entryPoints: ["src/pi-extensions/pipkin-todo/index.ts"],
    outfile: `${out}/pipkin-extensions/pipkin-todo.js`,
    // Pi resolves these to its own copies when it loads an extension.
    external: ["@earendil-works/*", "typebox"],
  }),
]);
