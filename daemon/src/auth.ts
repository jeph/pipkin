import { timingSafeEqual } from "node:crypto";
import { isObject } from "./wire.js";

export const PROTOCOL = 1;
export const RPC_PATH = "/rpc";
export const RPC_PROTOCOL = "pipkin.rpc.v1";
export const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const TOKEN = /^[0-9a-f]{64}$/;

/** Provisioned by the paired launcher; never written by the daemon or inherited by Pi. */
export interface Credentials {
  token: string;
  certificate: string;
  privateKey: string;
}

export function parseCredentials(text: string | undefined): Credentials {
  const value: unknown = JSON.parse(text ?? "null");
  if (!isObject(value) || typeof value.token !== "string" || !TOKEN.test(value.token) ||
      typeof value.certificate !== "string" || !value.certificate.includes("-----BEGIN CERTIFICATE-----") ||
      typeof value.privateKey !== "string" || !value.privateKey.includes("PRIVATE KEY-----")) {
    throw new Error("Missing or malformed daemon credentials");
  }
  return { token: value.token, certificate: value.certificate, privateKey: value.privateKey };
}

/** Fixed-size comparison; malformed header lengths never reach timingSafeEqual. */
export function tokenMatches(expected: Buffer, authorization: string | undefined): boolean {
  if (authorization?.length !== expected.length) return false;
  const received = Buffer.from(authorization, "utf8");
  return received.length === expected.length && timingSafeEqual(expected, received);
}
