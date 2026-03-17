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

describe("POST /subscriptions", () => {
  it("subscribes to a repo", async () => {
    const req = jsonRequest(
      "POST",
      "/subscriptions",
      { userId: "u1", repoFullName: "Owner/Repo" },
      apiHeaders()
    );
    const res = await SELF.fetch(req);
    expect(res.status).toBe(200);

    const row = await env.DB
      .prepare("SELECT * FROM repo_subscriptions WHERE user_id = ?")
      .bind("u1")
      .first();
    expect(row).not.toBeNull();
    expect(row!.repo_full_name).toBe("owner/repo");
  });

  it("ignores duplicate subscription", async () => {
    const body = { userId: "u1", repoFullName: "o/r" };
    await SELF.fetch(jsonRequest("POST", "/subscriptions", body, apiHeaders()));
    await SELF.fetch(jsonRequest("POST", "/subscriptions", body, apiHeaders()));

    const result = await env.DB
      .prepare("SELECT COUNT(*) as cnt FROM repo_subscriptions WHERE user_id = ?")
      .bind("u1")
      .first<{ cnt: number }>();
    expect(result!.cnt).toBe(1);
  });

  it("rejects missing repoFullName", async () => {
    const req = jsonRequest(
      "POST",
      "/subscriptions",
      { userId: "u1" },
      apiHeaders()
    );
    const res = await SELF.fetch(req);
    expect(res.status).toBe(400);
  });
});

describe("DELETE /subscriptions", () => {
  it("unsubscribes from a repo", async () => {
    await env.DB.exec(
      `INSERT INTO repo_subscriptions (user_id, repo_full_name) VALUES ('u1', 'o/r');`
    );

    const req = jsonRequest(
      "DELETE",
      "/subscriptions",
      { userId: "u1", repoFullName: "o/r" },
      apiHeaders()
    );
    const res = await SELF.fetch(req);
    expect(res.status).toBe(200);

    const row = await env.DB
      .prepare("SELECT * FROM repo_subscriptions WHERE user_id = ?")
      .bind("u1")
      .first();
    expect(row).toBeNull();
  });
});

describe("GET /subscriptions", () => {
  it("lists subscriptions for a user", async () => {
    await env.DB.exec(
      `INSERT INTO repo_subscriptions (user_id, repo_full_name) VALUES ('u1', 'alpha/one');
       INSERT INTO repo_subscriptions (user_id, repo_full_name) VALUES ('u1', 'beta/two');`
    );

    const req = jsonRequest(
      "GET",
      "/subscriptions?userId=u1",
      undefined,
      apiHeaders()
    );
    const res = await SELF.fetch(req);
    expect(res.status).toBe(200);

    const body = await res.json<{ subscriptions: string[] }>();
    expect(body.subscriptions).toEqual(["alpha/one", "beta/two"]);
  });

  it("returns empty for user with no subscriptions", async () => {
    const req = jsonRequest(
      "GET",
      "/subscriptions?userId=nobody",
      undefined,
      apiHeaders()
    );
    const res = await SELF.fetch(req);
    const body = await res.json<{ subscriptions: string[] }>();
    expect(body.subscriptions).toEqual([]);
  });

  it("rejects missing userId (api-key auth without userId query)", async () => {
    const req = jsonRequest(
      "GET",
      "/subscriptions",
      undefined,
      apiHeaders()
    );
    const res = await SELF.fetch(req);
    expect(res.status).toBe(400);
  });
});
