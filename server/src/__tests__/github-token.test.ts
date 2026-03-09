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

describe("POST /github-token", () => {
  it("stores an encrypted github token", async () => {
    const req = jsonRequest(
      "POST",
      "/github-token",
      { userId: "u1", githubToken: "ghp_test123", githubLogin: "testuser" },
      apiHeaders()
    );
    const res = await SELF.fetch(req);
    expect(res.status).toBe(200);

    const body = await res.json<{ ok: boolean }>();
    expect(body.ok).toBe(true);

    const row = await env.DB
      .prepare("SELECT * FROM user_github_tokens WHERE user_id = ?")
      .bind("u1")
      .first();
    expect(row).not.toBeNull();
    expect(row!.github_login).toBe("testuser");
    // Token should be encrypted, not stored in plain text
    expect(row!.encrypted_token).not.toBe("ghp_test123");
    expect(row!.encrypted_token).toBeTruthy();
  });

  it("upserts on duplicate user_id", async () => {
    const req1 = jsonRequest(
      "POST",
      "/github-token",
      { userId: "u1", githubToken: "ghp_old", githubLogin: "user1" },
      apiHeaders()
    );
    await SELF.fetch(req1);

    const req2 = jsonRequest(
      "POST",
      "/github-token",
      { userId: "u1", githubToken: "ghp_new", githubLogin: "user1_updated" },
      apiHeaders()
    );
    const res = await SELF.fetch(req2);
    expect(res.status).toBe(200);

    const row = await env.DB
      .prepare("SELECT github_login FROM user_github_tokens WHERE user_id = ?")
      .bind("u1")
      .first();
    expect(row!.github_login).toBe("user1_updated");
  });

  it("rejects missing githubToken", async () => {
    const req = jsonRequest(
      "POST",
      "/github-token",
      { userId: "u1", githubLogin: "user1" },
      apiHeaders()
    );
    const res = await SELF.fetch(req);
    expect(res.status).toBe(400);
  });

  it("rejects missing githubLogin", async () => {
    const req = jsonRequest(
      "POST",
      "/github-token",
      { userId: "u1", githubToken: "ghp_test" },
      apiHeaders()
    );
    const res = await SELF.fetch(req);
    expect(res.status).toBe(400);
  });

  it("rejects without auth", async () => {
    const req = jsonRequest("POST", "/github-token", {
      userId: "u1",
      githubToken: "ghp_test",
      githubLogin: "user1",
    });
    const res = await SELF.fetch(req);
    expect(res.status).toBe(401);
  });
});

describe("DELETE /github-token", () => {
  it("deletes a stored token", async () => {
    // First store a token
    const storeReq = jsonRequest(
      "POST",
      "/github-token",
      { userId: "u1", githubToken: "ghp_test", githubLogin: "user1" },
      apiHeaders()
    );
    await SELF.fetch(storeReq);

    // Then delete it
    const req = jsonRequest(
      "DELETE",
      "/github-token?userId=u1",
      undefined,
      apiHeaders()
    );
    const res = await SELF.fetch(req);
    expect(res.status).toBe(200);

    const row = await env.DB
      .prepare("SELECT * FROM user_github_tokens WHERE user_id = ?")
      .bind("u1")
      .first();
    expect(row).toBeNull();
  });

  it("succeeds even if no token exists", async () => {
    const req = jsonRequest(
      "DELETE",
      "/github-token?userId=u1",
      undefined,
      apiHeaders()
    );
    const res = await SELF.fetch(req);
    expect(res.status).toBe(200);
  });

  it("rejects without auth", async () => {
    const req = jsonRequest("DELETE", "/github-token");
    const res = await SELF.fetch(req);
    expect(res.status).toBe(401);
  });
});

describe("GET /github-token/status", () => {
  it("returns not configured when token is missing", async () => {
    const req = jsonRequest(
      "GET",
      "/github-token/status?userId=u1",
      undefined,
      apiHeaders()
    );
    const res = await SELF.fetch(req);
    expect(res.status).toBe(200);
    const body = await res.json<{ configured: boolean }>();
    expect(body.configured).toBe(false);
  });

  it("returns polling status fields for configured token", async () => {
    await env.DB.prepare(
      `INSERT INTO user_github_tokens (
        user_id,
        encrypted_token,
        github_login,
        last_poll_at,
        last_poll_status,
        last_poll_error,
        last_poll_success_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?)`
    )
      .bind(
        "u1",
        "cipher",
        "octocat",
        "2026-03-09T10:00:00.000Z",
        "github_error",
        "GitHub API error: 500",
        "2026-03-09T09:50:00.000Z"
      )
      .run();

    const req = jsonRequest(
      "GET",
      "/github-token/status?userId=u1",
      undefined,
      apiHeaders()
    );
    const res = await SELF.fetch(req);
    expect(res.status).toBe(200);
    const body = await res.json<{
      configured: boolean;
      githubLogin: string;
      lastPollStatus: string;
      lastPollError: string;
      lastPollAt: string;
      lastPollSuccessAt: string;
    }>();
    expect(body.configured).toBe(true);
    expect(body.githubLogin).toBe("octocat");
    expect(body.lastPollStatus).toBe("github_error");
    expect(body.lastPollError).toBe("GitHub API error: 500");
    expect(body.lastPollAt).toBe("2026-03-09T10:00:00.000Z");
    expect(body.lastPollSuccessAt).toBe("2026-03-09T09:50:00.000Z");
  });
});
