// Stand-in for `pi --mode rpc`: reports its argv, cwd and the variables of REPORTED_ENV it inherited, echoes each stdin JSONL line as
// {"type":"echo","command":...}, writes to stderr, and exits on stdin EOF like Pi does.
// Splits on LF only so U+2028 inside JSON strings survives, as Pi's framing requires.
// {"type":"flood","bytes":N,"done":path} instead writes about N bytes of numbered lines, waiting on
// stdout backpressure, then {"type":"flood_end","count":...}, and creates `done` once the last line
// was handed to the host. Pi's writes to a pipe block on Linux, so stdin EOF during a flood is only
// acted on once the flood finished.
// Pipkin's channel on fd 3: with FAKE_PI_CHANNEL set, each line read there is reported as
// {"type":"channel_received","message":...}; {"type":"channel_send","line":...} writes `line` and a newline to it.
import { once } from "node:events";
import { writeFileSync } from "node:fs";
import net from "node:net";

const channel = process.env.FAKE_PI_CHANNEL ? new net.Socket({ fd: 3, readable: true, writable: true }) : null;
let channelCarry = "";
channel?.setEncoding("utf8");
channel?.on("data", (chunk) => {
  const lines = (channelCarry + chunk).split("\n");
  channelCarry = lines.pop() ?? "";
  for (const line of lines) process.stdout.write(`${JSON.stringify({ type: "channel_received", message: JSON.parse(line) })}\n`);
});
channel?.unref();

const FLOOD_PAD = "x".repeat(1000);

let flooding;

async function flood(bytes, done) {
  let count = 0;
  for (let written = 0; written < bytes; count++) {
    const line = `${JSON.stringify({ type: "flood", seq: count, pad: FLOOD_PAD })}\n`;
    written += line.length;
    if (!process.stdout.write(line)) await once(process.stdout, "drain");
  }
  process.stdout.write(`${JSON.stringify({ type: "flood_end", count })}\n`, () => writeFileSync(done, "done"));
}

process.stderr.write("fake-pi ready\n");
const REPORTED_ENV = ["PIPKIN_TOKEN", "PIPKIN_ROOT", "PI_CODING_AGENT_DIR", "SESSION_VAR"];
const env = Object.fromEntries(REPORTED_ENV.filter((name) => name in process.env).map((name) => [name, process.env[name]]));
process.stdout.write(`${JSON.stringify({ type: "ready", argv: process.argv.slice(2), cwd: process.cwd(), env })}\n`);

let carry = "";
process.stdin.setEncoding("utf8");
process.stdin.on("data", (chunk) => {
  const lines = (carry + chunk).split("\n");
  carry = lines.pop() ?? "";
  for (const line of lines) {
    if (line.length === 0) continue;
    const command = JSON.parse(line);
    if (command.type === "flood") flooding = flood(command.bytes, command.done);
    else if (command.type === "channel_send") channel.write(`${command.line}\n`);
    else process.stdout.write(`${JSON.stringify({ type: "echo", command })}\n`);
  }
});
process.stdin.on("end", async () => {
  await flooding;
  process.stderr.write("fake-pi stdin closed\n");
  if (process.env.FAKE_PI_MARKER) writeFileSync(process.env.FAKE_PI_MARKER, "eof");
  process.exit(Number(process.env.FAKE_PI_EXIT_CODE ?? "0"));
});
