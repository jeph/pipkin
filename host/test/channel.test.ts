import { describe, expect, it } from "vitest";
import { AppInputRouter, type ChannelMessage, ChannelReader, MAX_CHANNEL_LINE, OutputMerger } from "../src/channel.js";

/** [data] cut into chunks at pseudo-random points from [seed], so every run tries the same cuts. */
function chunks(data: Buffer, seed: number): Buffer[] {
  let state = seed;
  const random = (): number => {
    state = (state * 1103515245 + 12345) % 2 ** 31;
    return state / 2 ** 31;
  };
  const pieces: Buffer[] = [];
  for (let start = 0; start < data.length; ) {
    const size = 1 + Math.floor(random() * 40);
    pieces.push(data.subarray(start, start + size));
    start += size;
  }
  return pieces;
}

const channelLine = (message: ChannelMessage): string =>
  `{"type":"pipkin_channel","topic":"${message.topic}","data":${JSON.stringify(message.data)}}\n`;

describe("AppInputRouter", () => {
  const piLines = [
    `{"id":"1","type":"prompt","message":"hi"}\n`,
    `{"type":"pipkin_request"}\n`,
    "\n",
    `{"type":"pipkin_chan"}\n`,
    `{"id":"2","type":"prompt","message":"${"x".repeat(200)}"}\n`,
  ];
  const stop: ChannelMessage = { topic: "background", data: { action: "stop" } };
  const other: ChannelMessage = { topic: "other-topic", data: null };
  const messages = [stop, other];
  const input = Buffer.from(
    piLines[0] + channelLine(stop) + piLines[1] + piLines[2] + channelLine(other) + piLines[3] + piLines[4],
  );

  for (const seed of [1, 2, 3, 4, 5, 6, 7, 8]) {
    it(`passes Pi's bytes on unchanged and takes channel lines out, however the input is cut (seed ${seed})`, () => {
      const toPi: Buffer[] = [];
      const received: ChannelMessage[] = [];
      const dropped: string[] = [];
      const router = new AppInputRouter(
        (data) => toPi.push(Buffer.from(data)),
        (message) => received.push(message),
        (reason) => dropped.push(reason),
      );
      for (const chunk of chunks(input, seed)) router.push(chunk);
      router.end();

      expect(Buffer.concat(toPi).toString()).toBe(piLines.join(""));
      expect(received).toEqual(messages);
      expect(dropped).toEqual([]);
    });
  }

  it("streams a long line to Pi without holding it back", () => {
    const toPi: Buffer[] = [];
    const router = new AppInputRouter((data) => toPi.push(Buffer.from(data)), () => {}, () => {});
    router.push(Buffer.from(`{"id":"1","message":"${"y".repeat(1000)}`));
    expect(Buffer.concat(toPi).length).toBe(1000 + 21);
  });

  it("gives Pi the held back start of a line when the input ends", () => {
    const toPi: Buffer[] = [];
    const router = new AppInputRouter((data) => toPi.push(Buffer.from(data)), () => {}, () => {});
    router.push(Buffer.from(`{"type":"pip`));
    expect(toPi).toEqual([]);
    router.end();
    expect(Buffer.concat(toPi).toString()).toBe(`{"type":"pip`);
  });

  it("drops a channel line that is too long or not a message, and goes on with the next lines", () => {
    const toPi: Buffer[] = [];
    const received: ChannelMessage[] = [];
    const dropped: string[] = [];
    const router = new AppInputRouter(
      (data) => toPi.push(Buffer.from(data)),
      (message) => received.push(message),
      (reason) => dropped.push(reason),
    );
    router.push(Buffer.from(`{"type":"pipkin_channel","topic":"t","data":"${"z".repeat(MAX_CHANNEL_LINE)}"}\n`));
    router.push(Buffer.from(`{"type":"pipkin_channel","topic":"Bad Topic"}\n`));
    router.push(Buffer.from(`{"type":"pipkin_channel",broken\n`));
    router.push(Buffer.from(`${channelLine({ topic: "t", data: 1 })}{"id":"3"}\n`));

    expect(dropped).toEqual([`Line exceeds ${MAX_CHANNEL_LINE} bytes`, "not a channel message", "not a channel message"]);
    expect(received).toEqual([{ topic: "t", data: 1 }]);
    expect(Buffer.concat(toPi).toString()).toBe(`{"id":"3"}\n`);
  });
});

describe("OutputMerger", () => {
  const piOutput = Buffer.from(
    Array.from({ length: 30 }, (_, seq) => `${JSON.stringify({ type: "event", seq, pad: "p".repeat(seq * 3) })}\n`).join(""),
  );

  for (const seed of [11, 12, 13, 14, 15, 16]) {
    it(`never puts a channel message inside one of Pi's lines (seed ${seed})`, () => {
      const written: string[] = [];
      const merger = new OutputMerger((data) => written.push(data.toString()));
      const pieces = chunks(piOutput, seed);
      pieces.forEach((piece, index) => {
        merger.output(piece);
        if (index % 3 === 0) merger.channel({ topic: "background", data: { active: index } });
      });
      merger.end();

      const lines = written.join("").split("\n").slice(0, -1);
      const piLines = lines.filter((line) => !line.startsWith(`{"type":"pipkin_channel"`));
      const channel = lines.filter((line) => line.startsWith(`{"type":"pipkin_channel"`));
      expect(`${piLines.join("\n")}\n`).toBe(piOutput.toString());
      expect(channel.map((line): unknown => JSON.parse(line))).toEqual(
        pieces.flatMap((_, index) =>
          index % 3 === 0 ? [{ type: "pipkin_channel", topic: "background", data: { active: index } }] : [],
        ),
      );
    });
  }

  it("writes a message at once while Pi is between lines, and at the next line end otherwise", () => {
    const written: string[] = [];
    const merger = new OutputMerger((data) => written.push(data.toString()));
    merger.channel({ topic: "a", data: 1 });
    merger.output(Buffer.from(`{"half":`));
    merger.channel({ topic: "b", data: 2 });
    expect(written.join("")).toBe(`${channelLine({ topic: "a", data: 1 })}{"half":`);
    merger.output(Buffer.from(`1}\n{"next"`));
    expect(written.join("")).toBe(
      `${channelLine({ topic: "a", data: 1 })}{"half":1}\n${channelLine({ topic: "b", data: 2 })}{"next"`,
    );
  });

  it("ends a line Pi left unfinished before the messages still waiting when its output ends", () => {
    const written: string[] = [];
    const merger = new OutputMerger((data) => written.push(data.toString()));
    merger.output(Buffer.from(`{"cut`));
    merger.channel({ topic: "a", data: null });
    merger.end();
    expect(written.join("")).toBe(`{"cut\n${channelLine({ topic: "a", data: null })}`);
  });
});

describe("ChannelReader", () => {
  it("reads messages from Pi's side, dropping lines that aren't, even across a too long one", () => {
    const received: ChannelMessage[] = [];
    const dropped: string[] = [];
    const reader = new ChannelReader(
      (message) => received.push(message),
      (reason) => dropped.push(reason),
    );
    reader.push(Buffer.from(`{"topic":"a","data":{"x":1}}\n{"type":"pipkin_exit","topic":"a"}\n`));
    reader.push(Buffer.from(`{"topic":"b","data":"${"q".repeat(MAX_CHANNEL_LINE)}"}\n`));
    reader.push(Buffer.from(`{"topic":"c"}\n`));

    expect(received).toEqual([
      { topic: "a", data: { x: 1 } },
      { topic: "c", data: null },
    ]);
    expect(dropped[0]).toBe("not a channel message");
    expect(dropped[1]).toBe(`Line exceeds ${MAX_CHANNEL_LINE} bytes`);
  });
});
