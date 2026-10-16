/**
 * Entry point of `pipkin-helper.mjs` (`helper.mjs` in Termux):
 * - `node helper.mjs`: the daemon (see `daemon.ts`).
 * - `node helper.mjs pair`: stores the pairing key from Pipkin's setup endpoint (see `pairing.ts`).
 * - `node helper.mjs client …`: developer CLI that launches a command through a running helper (see `client.ts`).
 */
import { runClient } from "./client.js";
import { runDaemon } from "./daemon.js";
import { HelperFiles } from "./files.js";
import { pair } from "./pairing.js";

const [command, ...rest] = process.argv.slice(2);

let code: number;
if (command === undefined) {
  code = await runDaemon();
} else if (command === "pair") {
  code = await pair(process.env, new HelperFiles());
} else if (command === "client") {
  code = await runClient(rest).catch((error: unknown) => {
    process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`);
    return 1;
  });
} else {
  process.stderr.write("Usage: node helper.mjs [pair | client …]\n");
  code = 2;
}
process.exit(code);
