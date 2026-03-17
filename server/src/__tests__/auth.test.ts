import { describe, it, expect, beforeAll, beforeEach } from "vitest";
import { SELF, initSchema, resetDb, jsonRequest, apiHeaders } from "./helpers";

beforeAll(initSchema);
beforeEach(resetDb);

describe("auth: api-key legacy path", () => {
  it("allows requests with valid api key and userId in body", async () => {
    const req = jsonRequest(
      "POST",
      "/devices/register",
      { userId: "u1", token: "tok-1" },
      apiHeaders()
    );
    const res = await SELF.fetch(req);
    expect(res.status).toBe(200);
  });

  it("allows requests with valid api key and userId in query", async () => {
    const req = jsonRequest(
      "GET",
      "/subscriptions?userId=u1",
      undefined,
      apiHeaders()
    );
    const res = await SELF.fetch(req);
    expect(res.status).toBe(200);
  });

  it("rejects requests with invalid api key", async () => {
    const req = jsonRequest(
      "GET",
      "/subscriptions?userId=u1",
      undefined,
      { "x-api-key": "wrong-key" }
    );
    const res = await SELF.fetch(req);
    expect(res.status).toBe(401);
  });

  it("rejects requests with no auth at all", async () => {
    const req = jsonRequest("GET", "/subscriptions?userId=u1");
    const res = await SELF.fetch(req);
    expect(res.status).toBe(401);
  });

  it("rejects api-key auth with missing userId for POST", async () => {
    const req = jsonRequest(
      "POST",
      "/devices/register",
      { token: "tok-1" },
      apiHeaders()
    );
    const res = await SELF.fetch(req);
    expect(res.status).toBe(400);
    const body = await res.json<{ error: string }>();
    expect(body.error).toMatch(/userId/i);
  });
});

describe("auth: bearer token path", () => {
  it("rejects empty bearer token", async () => {
    const req = jsonRequest("GET", "/subscriptions?userId=u1", undefined, {
      authorization: "Bearer ",
    });
    const res = await SELF.fetch(req);
    expect(res.status).toBe(401);
  });

  it("rejects malformed bearer token", async () => {
    const req = jsonRequest("GET", "/subscriptions?userId=u1", undefined, {
      authorization: "Bearer not.a.valid.jwt",
    });
    const res = await SELF.fetch(req);
    expect(res.status).toBe(401);
  });
});

describe("webhook endpoint does not require auth", () => {
  it("webhook has its own HMAC auth, not user auth", async () => {
    const req = new Request("http://localhost/github/webhook", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: "{}",
    });
    const res = await SELF.fetch(req);
    expect(res.status).toBe(400);
    const body = await res.json<{ error: string }>();
    expect(body.error).toMatch(/missing webhook headers/i);
  });
});
