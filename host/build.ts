/**
 * Bundles the host into `$OUT/pipkin-host.mjs`, and each Pi extension Pipkin ships into
 * `$OUT/pipkin-extensions/<name>.js`. Both ship as APK assets. The extensions stay out of the host, which Pipkin hands
 * to Termux inside an Intent with a size limit (`HostLauncher`); Pipkin sends their source over the host's socket to
 * `ensure` instead, which installs them, and each chat sends only their SHA-256 to check the installed copy.
 */
import { readFile } from "node:fs/promises";
import { build } from "esbuild";

const runtimeManifest: unknown = JSON.parse(await readFile("../app/src/main/assets/pi/package.json", "utf8"));
if (typeof runtimeManifest !== "object" || runtimeManifest === null || !("dependencies" in runtimeManifest)) {
  throw new Error("The Pi runtime's package.json has no dependencies");
}
/** The packages `npm ci` installs in Pipkin's Pi runtime, which the extensions saved there import at run time. */
const runtimePackages = Object.keys(Object(runtimeManifest.dependencies));

const out = process.env.OUT;
if (!out) throw new Error("Set OUT to the directory the bundles go in");

const common = { bundle: true, platform: "node", format: "esm", target: "node22", logLevel: "warning" } as const;

// The MIT License asks every copy to carry its notice, and esbuild drops the notices of the code it bundles.
const [webAccessLicense, typeboxLicense] = await Promise.all([
  readFile("src/pi-extensions/pipkin-web-access/LICENSE", "utf8"),
  readFile("node_modules/typebox/license", "utf8"),
]);
const webAccessNotices = `/*\nVendored from pi-web-access:\n\n${webAccessLicense}\nBundles typebox:\n\n${typeboxLicense}*/`;

await Promise.all([
  build({ ...common, entryPoints: ["src/main.ts"], outfile: `${out}/pipkin-host.mjs` }),
  build({
    ...common,
    entryPoints: ["src/pi-extensions/pipkin-todo/index.ts"],
    outfile: `${out}/pipkin-extensions/pipkin-todo.js`,
    // Pi resolves these to its own copies when it loads an extension.
    external: ["@earendil-works/*", "typebox"],
  }),
  build({
    ...common,
    entryPoints: ["src/pi-extensions/pipkin-web-access/index.ts"],
    outfile: `${out}/pipkin-extensions/pipkin-web-access.js`,
    banner: { js: webAccessNotices },
    // Node, not Pi, resolves this extension's imports, since the host saves it inside the runtime. Anything left
    // external loads its whole module graph, so typebox, which builds the tool schemas at startup, is bundled.
    external: [...runtimePackages, "@earendil-works/*"],
  }),
]);
