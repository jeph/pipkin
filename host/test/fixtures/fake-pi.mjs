// Stand-in for `pi --mode rpc`: reports its argv, echoes each stdin JSONL line as
// {"type":"echo","command":...}, writes to stderr, and exits on stdin EOF like Pi does.
// Splits on LF only so U+2028 inside JSON strings survives, as Pi's framing requires.
import { writeFileSync } from "node:fs";

process.stderr.write("fake-pi ready\n");
process.stdout.write(`${JSON.stringify({ type: "ready", argv: process.argv.slice(2), cwd: process.cwd() })}\n`);

let carry = "";
process.stdin.setEncoding("utf8");
process.stdin.on("data", (chunk) => {
  const lines = (carry + chunk).split("\n");
  carry = lines.pop() ?? "";
  for (const line of lines) {
    if (line.length === 0) continue;
    process.stdout.write(`${JSON.stringify({ type: "echo", command: JSON.parse(line) })}\n`);
  }
});
process.stdin.on("end", () => {
  process.stderr.write("fake-pi stdin closed\n");
  if (process.env.FAKE_PI_MARKER) writeFileSync(process.env.FAKE_PI_MARKER, "eof");
  process.exit(Number(process.env.FAKE_PI_EXIT_CODE ?? "0"));
});
