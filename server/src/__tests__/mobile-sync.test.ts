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

async function seedSubscription(userId: string, repo: string): Promise<void> {
  await env.DB
    .prepare("INSERT INTO repo_subscriptions (user_id, repo_full_name) VALUES (?, ?)")
    .bind(userId, repo)
    .run();
}

async function seedPrChange(
  deliveryId: string,
  repo: string,
  prNumber: number,
  action: string,
  changedAtMs: number
): Promise<void> {
  await env.DB
    .prepare(
      "INSERT INTO pr_changes (delivery_id, repo_full_name, pr_number, action, changed_at_ms) VALUES (?, ?, ?, ?, ?)"
    )
    .bind(deliveryId, repo, prNumber, action, changedAtMs)
    .run();
}

describe("GET /mobile/sync", () => {
  it("returns empty when no changes", async () => {
    await seedSubscription("u1", "o/r");

    const req = jsonRequest(
      "GET",
      "/mobile/sync?userId=u1&since=0",
      undefined,
      apiHeaders()
    );
    const res = await SELF.fetch(req);
    expect(res.status).toBe(200);

    const body = await res.json<{
      changedPullRequests: unknown[];
      hasMore: boolean;
    }>();
    expect(body.changedPullRequests).toHaveLength(0);
    expect(body.hasMore).toBe(false);
  });

  it("returns changes for subscribed repos only", async () => {
    await seedSubscription("u1", "o/r");
    await seedPrChange("d1", "o/r", 1, "opened", 1000);
    await seedPrChange("d2", "other/repo", 2, "opened", 1001);

    const req = jsonRequest(
      "GET",
      "/mobile/sync?userId=u1&since=0",
      undefined,
      apiHeaders()
    );
    const res = await SELF.fetch(req);
    const body = await res.json<{
      changedPullRequests: Array<{ repo: string; number: number }>;
    }>();
    expect(body.changedPullRequests).toHaveLength(1);
    expect(body.changedPullRequests[0].repo).toBe("o/r");
  });

  it("collapses multiple changes per PR to latest", async () => {
    await seedSubscription("u1", "o/r");
    await seedPrChange("d1", "o/r", 1, "opened", 1000);
    await seedPrChange("d2", "o/r", 1, "synchronize", 2000);
    await seedPrChange("d3", "o/r", 1, "closed", 3000);

    const req = jsonRequest(
      "GET",
      "/mobile/sync?userId=u1&since=0",
      undefined,
      apiHeaders()
    );
    const res = await SELF.fetch(req);
    const body = await res.json<{
      changedPullRequests: Array<{ action: string }>;
    }>();
    expect(body.changedPullRequests).toHaveLength(1);
    expect(body.changedPullRequests[0].action).toBe("closed");
  });

  it("normalizes legacy actions in sync response", async () => {
    await seedSubscription("u1", "o/r");
    await seedPrChange("d1", "o/r", 1, "synchronize", 1000);

    const req = jsonRequest(
      "GET",
      "/mobile/sync?userId=u1&since=0",
      undefined,
      apiHeaders()
    );
    const res = await SELF.fetch(req);
    const body = await res.json<{
      changedPullRequests: Array<{ action: string }>;
    }>();
    expect(body.changedPullRequests[0].action).toBe("updated");
  });

  it("paginates with hasMore", async () => {
    await seedSubscription("u1", "o/r");
    for (let i = 1; i <= 3; i++) {
      await seedPrChange(`d${i}`, "o/r", i, "opened", 1000 + i);
    }

    const req = jsonRequest(
      "GET",
      "/mobile/sync?userId=u1&since=0&limit=2",
      undefined,
      apiHeaders()
    );
    const res = await SELF.fetch(req);
    const body = await res.json<{
      changedPullRequests: unknown[];
      hasMore: boolean;
      nextSince: number;
      nextCursorDeliveryId: string;
    }>();
    expect(body.changedPullRequests).toHaveLength(2);
    expect(body.hasMore).toBe(true);
    expect(body.nextSince).toBeGreaterThan(0);
  });

  it("cursor tie-breaking with deliveryId", async () => {
    await seedSubscription("u1", "o/r");
    await seedPrChange("aaa", "o/r", 1, "opened", 1000);
    await seedPrChange("bbb", "o/r", 2, "opened", 1000);
    await seedPrChange("ccc", "o/r", 3, "opened", 1000);

    const req1 = jsonRequest(
      "GET",
      "/mobile/sync?userId=u1&since=0&limit=2",
      undefined,
      apiHeaders()
    );
    const res1 = await SELF.fetch(req1);
    const body1 = await res1.json<{
      changedPullRequests: Array<{ number: number }>;
      nextSince: number;
      nextCursorDeliveryId: string;
      hasMore: boolean;
    }>();
    expect(body1.changedPullRequests).toHaveLength(2);

    const req2 = jsonRequest(
      "GET",
      `/mobile/sync?userId=u1&since=${body1.nextSince}&cursorDeliveryId=${body1.nextCursorDeliveryId}&limit=2`,
      undefined,
      apiHeaders()
    );
    const res2 = await SELF.fetch(req2);
    const body2 = await res2.json<{
      changedPullRequests: Array<{ number: number }>;
      hasMore: boolean;
    }>();
    expect(body2.changedPullRequests).toHaveLength(1);
    expect(body2.hasMore).toBe(false);
  });

  it("rejects missing userId (api-key auth without userId query)", async () => {
    const req = jsonRequest(
      "GET",
      "/mobile/sync?since=0",
      undefined,
      apiHeaders()
    );
    const res = await SELF.fetch(req);
    expect(res.status).toBe(400);
  });
});
