import { describe, it, expect, beforeAll, beforeEach } from "vitest";
import { env, initSchema, resetDb } from "./helpers";
import { encryptToken, decryptToken } from "../crypto";

beforeAll(initSchema);
beforeEach(resetDb);

describe("crypto", () => {
  // Use a base64-encoded 32-byte key for testing
  const testKey = btoa(String.fromCharCode(...new Uint8Array(32).fill(42)));

  it("encrypts and decrypts a token", async () => {
    const original = "ghp_test_token_12345";
    const encrypted = await encryptToken(original, testKey);
    expect(encrypted).not.toBe(original);

    const decrypted = await decryptToken(encrypted, testKey);
    expect(decrypted).toBe(original);
  });

  it("produces different ciphertext each time (random IV)", async () => {
    const original = "ghp_same_token";
    const encrypted1 = await encryptToken(original, testKey);
    const encrypted2 = await encryptToken(original, testKey);
    expect(encrypted1).not.toBe(encrypted2);

    // Both should decrypt to the same value
    expect(await decryptToken(encrypted1, testKey)).toBe(original);
    expect(await decryptToken(encrypted2, testKey)).toBe(original);
  });

  it("fails to decrypt with wrong key", async () => {
    const wrongKey = btoa(String.fromCharCode(...new Uint8Array(32).fill(99)));
    const encrypted = await encryptToken("ghp_secret", testKey);

    await expect(decryptToken(encrypted, wrongKey)).rejects.toThrow();
  });
});

describe("resolveDeviceTokensForUser", () => {
  it("returns device tokens for a user", async () => {
    await env.DB.exec(
      `INSERT INTO device_tokens (user_id, token, platform) VALUES ('u1', 'tok-a', 'android');
       INSERT INTO device_tokens (user_id, token, platform) VALUES ('u1', 'tok-b', 'android');
       INSERT INTO device_tokens (user_id, token, platform) VALUES ('u2', 'tok-c', 'android');`
    );

    const { resolveDeviceTokensForUser } = await import("../github-poller");
    const tokens = await resolveDeviceTokensForUser(env.DB, "u1");
    expect(tokens).toHaveLength(2);
    expect(tokens).toContain("tok-a");
    expect(tokens).toContain("tok-b");
  });

  it("returns empty array for user with no devices", async () => {
    const { resolveDeviceTokensForUser } = await import("../github-poller");
    const tokens = await resolveDeviceTokensForUser(env.DB, "nobody");
    expect(tokens).toHaveLength(0);
  });
});
