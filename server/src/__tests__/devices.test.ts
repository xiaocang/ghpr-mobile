import { describe, it, expect, beforeAll, beforeEach } from "vitest";
import {
  SELF,
  env,
  initSchema,
  resetDb,
  jsonRequest,
  apiHeaders,
} from "./helpers";

beforeAll(initSchema);
beforeEach(resetDb);

describe("POST /devices/register", () => {
  it("registers a new device", async () => {
    const req = jsonRequest(
      "POST",
      "/devices/register",
      { userId: "u1", token: "tok-abc", platform: "android" },
      apiHeaders()
    );
    const res = await SELF.fetch(req);
    expect(res.status).toBe(200);

    const row = await env.DB
      .prepare("SELECT * FROM device_tokens WHERE token = ?")
      .bind("tok-abc")
      .first();
    expect(row).not.toBeNull();
    expect(row!.user_id).toBe("u1");
  });

  it("upserts on duplicate token", async () => {
    const req1 = jsonRequest(
      "POST",
      "/devices/register",
      { userId: "u1", token: "tok-dup" },
      apiHeaders()
    );
    await SELF.fetch(req1);

    const req2 = jsonRequest(
      "POST",
      "/devices/register",
      { userId: "u2", token: "tok-dup" },
      apiHeaders()
    );
    await SELF.fetch(req2);

    const row = await env.DB
      .prepare("SELECT user_id FROM device_tokens WHERE token = ?")
      .bind("tok-dup")
      .first();
    expect(row!.user_id).toBe("u2");
  });

  it("rejects missing token", async () => {
    const req = jsonRequest(
      "POST",
      "/devices/register",
      { userId: "u1" },
      apiHeaders()
    );
    const res = await SELF.fetch(req);
    expect(res.status).toBe(400);
  });

  it("rejects without auth", async () => {
    const req = jsonRequest("POST", "/devices/register", {
      userId: "u1",
      token: "tok",
    });
    const res = await SELF.fetch(req);
    expect(res.status).toBe(401);
  });

  it("defaults platform to android", async () => {
    const req = jsonRequest(
      "POST",
      "/devices/register",
      { userId: "u1", token: "tok-def" },
      apiHeaders()
    );
    await SELF.fetch(req);

    const row = await env.DB
      .prepare("SELECT platform FROM device_tokens WHERE token = ?")
      .bind("tok-def")
      .first();
    expect(row!.platform).toBe("android");
  });
});

describe("DELETE /devices/register", () => {
  it("unregisters a device", async () => {
    await env.DB.exec(
      `INSERT INTO device_tokens (user_id, token, platform) VALUES ('u1', 'tok-del', 'android');`
    );

    const req = jsonRequest(
      "DELETE",
      "/devices/register",
      { userId: "u1", token: "tok-del" },
      apiHeaders()
    );
    const res = await SELF.fetch(req);
    expect(res.status).toBe(200);

    const row = await env.DB
      .prepare("SELECT * FROM device_tokens WHERE token = ?")
      .bind("tok-del")
      .first();
    expect(row).toBeNull();
  });

  it("succeeds even if token does not exist", async () => {
    const req = jsonRequest(
      "DELETE",
      "/devices/register",
      { userId: "u1", token: "nonexistent" },
      apiHeaders()
    );
    const res = await SELF.fetch(req);
    expect(res.status).toBe(200);
  });
});

describe("GET /devices", () => {
  it("lists devices for a user with masked tokens", async () => {
    await env.DB.exec(
      `INSERT INTO device_tokens (user_id, token, platform) VALUES ('u1', 'abcdef1234567890ghij', 'android');`
    );

    const req = jsonRequest("GET", "/devices?userId=u1", undefined, apiHeaders());
    const res = await SELF.fetch(req);
    expect(res.status).toBe(200);

    const body = await res.json<{
      devices: Array<{ platform: string; tokenPreview: string }>;
    }>();
    expect(body.devices).toHaveLength(1);
    expect(body.devices[0].platform).toBe("android");
    expect(body.devices[0].tokenPreview).not.toBe("abcdef1234567890ghij");
    expect(body.devices[0].tokenPreview).toContain("...");
  });

  it("returns empty array for user with no devices", async () => {
    const req = jsonRequest(
      "GET",
      "/devices?userId=nobody",
      undefined,
      apiHeaders()
    );
    const res = await SELF.fetch(req);
    const body = await res.json<{ devices: unknown[] }>();
    expect(body.devices).toHaveLength(0);
  });

  it("rejects missing userId (api-key auth without userId query)", async () => {
    const req = jsonRequest("GET", "/devices", undefined, apiHeaders());
    const res = await SELF.fetch(req);
    expect(res.status).toBe(400);
  });
});
