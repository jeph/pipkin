// Stand-in for Pi's RPC mode: one stderr line at start, three JSONL events per stdin
// command, exit 0 on stdin EOF. Ignores argv (the daemon passes Pi's fixed flags).
import readline from "node:readline";

process.stderr.write("fake-pi ready\n");
const input = readline.createInterface({ input: process.stdin });
input.on("line", (line) => {
  const command = JSON.parse(line);
  for (const record of [{ type: "agent_start" }, { type: "echo", command }, { type: "agent_settled" }]) {
    process.stdout.write(JSON.stringify(record) + "\n");
  }
});
input.on("close", () => process.exit(0));
