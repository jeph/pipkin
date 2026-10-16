import { describe, expect, it } from "vitest";
import { decryptPairingBody, pairingKey } from "../src/helper/pairing.js";
import {
  clientMac,
  FrameError,
  FrameOpener,
  FrameSealer,
  serverMac,
  sessionKeys,
} from "../src/helper/protocol.js";

// The spec's test vectors; the app's HelperProtocol asserts the same ones.
const K = Buffer.from("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f", "hex");
const nonceC = Buffer.alloc(32, 0xaa);
const nonceS = Buffer.alloc(32, 0xbb);
const { c2s, s2c } = sessionKeys(K, nonceC, nonceS);

describe("pipkin-helper/1 vectors", () => {
  it("derives the handshake MACs and session keys", () => {
    expect(serverMac(K, nonceC, nonceS).toString("hex")).toBe(
      "c92c57837f8549ddfb5a965ace997642ce25f3e3b136a6bf561433663f3a1461",
    );
    expect(clientMac(K, nonceC, nonceS).toString("hex")).toBe(
      "9fb54e8cc24ecc55dea53d1287d19d3bcfb851c899430f38410a4df7a93503ab",
    );
    expect(c2s.toString("hex")).toBe("eb0b435f5cf0a7eb2819992de866100bbfc6a262e919e47e01853e37d871fab7");
    expect(s2c.toString("hex")).toBe("3a4ec171786f7ad7f37d65dfa5744436aab39444b5c7823744a1225b0e36f487");
  });

  it("seals frames with a per-direction counter", () => {
    const client = new FrameSealer(c2s);
    expect(client.sealBody({ type: "hello" }).toString("hex")).toBe(
      "d63a72ad9216ab54d318f274debda5af6abd7a1a6216a23ca62b049800c61723",
    );
    expect(client.sealBody({ type: "hello" }).toString("hex")).toBe(
      "740b781b9f3a35201158d88e92de83752160aa8dfd430a4c5ae8f2520ca20250",
    );
    expect(new FrameSealer(s2c).sealBody({ type: "hello", version: "x" }).toString("hex")).toBe(
      "a916a91721dc194149655fc1fa133d8a8a5746ae372aaa1199edd92cc04ab9340a0d56b15a8f2a0ba991c0bf6156",
    );
  });

  it("opens length-prefixed frames split across chunks", () => {
    const sealer = new FrameSealer(c2s);
    const bytes = Buffer.concat([sealer.seal({ type: "hello" }), sealer.seal({ type: "launch", n: 1 })]);
    const opener = new FrameOpener(c2s);
    expect(opener.push(bytes.subarray(0, 3))).toEqual([]);
    expect(opener.push(bytes.subarray(3, 40))).toEqual([{ type: "hello" }]);
    expect(opener.push(bytes.subarray(40))).toEqual([{ type: "launch", n: 1 }]);
  });

  it("rejects replayed, reordered, tampered and oversized frames", () => {
    const sealer = new FrameSealer(c2s);
    const first = sealer.seal({ type: "hello" });
    const second = sealer.seal({ type: "hello" });

    const replay = new FrameOpener(c2s);
    replay.push(first);
    expect(() => replay.push(first)).toThrow(FrameError);

    expect(() => new FrameOpener(c2s).push(second)).toThrow(FrameError);

    const tampered = Buffer.from(first);
    tampered[6] = (tampered[6] ?? 0) ^ 1;
    expect(() => new FrameOpener(c2s).push(tampered)).toThrow(FrameError);

    expect(() => new FrameOpener(c2s).push(Buffer.from([0x00, 0x41, 0x00, 0x11]))).toThrow(FrameError);
    expect(() => new FrameOpener(c2s).push(Buffer.alloc(4))).toThrow(FrameError);
  });
});

describe("pairing vector", () => {
  const token = Buffer.alloc(32, 0x11);
  const sid = "00112233445566778899aabbccddeeff";
  const body =
    "222222222222222222222222319d2a307af21c87b8dbc08698713d1eca98a06961f19086e5b493fa03650cce9202f9300b110c5b7284c10e82768b8e";

  it("derives pk and decrypts the key body", () => {
    expect(pairingKey(token).toString("hex")).toBe("5f0176d40fe08c42eb95805e16f536883a652dc2778578b27bb197ccf0d9e34c");
    expect(decryptPairingBody(body, token, sid)).toEqual(K);
  });

  it("rejects a body for another session", () => {
    expect(() => decryptPairingBody(body, token, "ffeeddccbbaa99887766554433221100")).toThrow(
      "The key from Pipkin didn't match this setup command.",
    );
  });
});
