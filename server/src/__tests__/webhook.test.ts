import { describe, it, expect, beforeAll, beforeEach } from "vitest";
import { sha256HmacHex, buildPushPayload } from "../index";
import {
  SELF,
  env,
  initSchema,
  resetDb,
  webhookRequest,
  makePrEvent,
} from "./helpers";

beforeAll(initSchema);
beforeEach(resetDb);

describe("sha256HmacHex", () => {
  it("produces deterministic sha256= prefixed hex", async () => {
    const result = await sha256HmacHex("secret", "body");
    expect(result).toMatch(/^sha256=[0-9a-f]{64}$/);
  });

  it("returns different values for different secrets", async () => {
    const a = await sha256HmacHex("secret-a", "body");
    const b = await sha256HmacHex("secret-b", "body");
    expect(a).not.toBe(b);
  });
});

describe("buildPushPayload", () => {
  it("builds payload from valid PR event", () => {
    const event = {
      action: "opened",
      repository: { full_name: "Owner/Repo" },
      pull_request: { number: 1 },
    };
    const payload = buildPushPayload(event, "d-001");
    expect(payload).not.toBeNull();
    expect(payload!.type).toBe("pr_update");
    expect(payload!.repo).toBe("owner/repo");
    expect(payload!.prNumber).toBe("1");
    expect(payload!.action).toBe("opened");
    expect(payload!.deliveryId).toBe("d-001");
    expect(payload!.prTitle).toBeUndefined();
    expect(payload!.prUrl).toBeUndefined();
  });

  it("normalizes webhook action and carries title/url", () => {
    const event = {
      action: "ready_for_review",
      repository: { full_name: "Owner/Repo" },
      pull_request: {
        number: 9,
        title: "Improve notifier",
        html_url: "https://github.com/Owner/Repo/pull/9",
      },
    };
    const payload = buildPushPayload(event, "d-002");
    expect(payload).not.toBeNull();
    expect(payload!.action).toBe("opened");
    expect(payload!.prTitle).toBe("Improve notifier");
    expect(payload!.prUrl).toBe("https://github.com/Owner/Repo/pull/9");
  });

  it("maps merged closed event to merged action", () => {
    const event = {
      action: "closed",
      repository: { full_name: "Owner/Repo" },
      pull_request: { number: 7, merged: true },
    };
    const payload = buildPushPayload(event, "d-003");
    expect(payload).not.toBeNull();
    expect(payload!.action).toBe("merged");
  });

  it("returns null for missing repo", () => {
    const event = { action: "opened", pull_request: { number: 1 } };
    expect(buildPushPayload(event, "d")).toBeNull();
  });

  it("returns null for missing pr number", () => {
    const event = {
      action: "opened",
      repository: { full_name: "o/r" },
    };
    expect(buildPushPayload(event, "d")).toBeNull();
  });

  it("returns null for pr number <= 0", () => {
    const event = {
      action: "opened",
      repository: { full_name: "o/r" },
      pull_request: { number: 0 },
    };
    expect(buildPushPayload(event, "d")).toBeNull();
  });
});

describe("POST /github/webhook", () => {
  it("rejects missing signature header", async () => {
    const req = new Request("http://localhost/github/webhook", {
      method: "POST",
      headers: {
        "x-github-delivery": "d-001",
        "x-github-event": "pull_request",
      },
      body: "{}",
    });
    const res = await SELF.fetch(req);
    expect(res.status).toBe(400);
    const body = await res.json<{ error: string }>();
    expect(body.error).toMatch(/missing/i);
  });

  it("rejects invalid signature", async () => {
    const req = await webhookRequest(makePrEvent(), {
      signature: "sha256=0000000000000000000000000000000000000000000000000000000000000000",
    });
    const res = await SELF.fetch(req);
    expect(res.status).toBe(401);
  });

  it("accepts valid PR webhook and returns fanOutCount", async () => {
    const req = await webhookRequest(makePrEvent());
    const res = await SELF.fetch(req);
    expect(res.status).toBe(200);
    const body = await res.json<{ ok: boolean; fanOutCount: number }>();
    expect(body.ok).toBe(true);
    expect(body.fanOutCount).toBe(0);
  });

  it("deduplicates by delivery id", async () => {
    const req1 = await webhookRequest(makePrEvent(), { deliveryId: "dup-1" });
    const res1 = await SELF.fetch(req1);
    expect(res1.status).toBe(200);

    const req2 = await webhookRequest(makePrEvent(), { deliveryId: "dup-1" });
    const res2 = await SELF.fetch(req2);
    expect(res2.status).toBe(200);
    const body2 = await res2.json<{ deduplicated: boolean }>();
    expect(body2.deduplicated).toBe(true);
  });

  it("skips non-PR events", async () => {
    const req = await webhookRequest(
      { action: "completed" },
      { eventType: "check_run" }
    );
    const res = await SELF.fetch(req);
    expect(res.status).toBe(200);
    const body = await res.json<{ skipped: string }>();
    expect(body.skipped).toMatch(/not pr/i);
  });

  it("stores pr_changes for valid PR events", async () => {
    const req = await webhookRequest(makePrEvent("owner/repo", 10, "opened"), {
      deliveryId: "store-1",
    });
    await SELF.fetch(req);

    const row = await env.DB
      .prepare("SELECT * FROM pr_changes WHERE delivery_id = ?")
      .bind("store-1")
      .first();
    expect(row).not.toBeNull();
    expect(row!.repo_full_name).toBe("owner/repo");
    expect(row!.pr_number).toBe(10);
  });

  it("stores normalized merged action for merged close webhook", async () => {
    const req = await webhookRequest(
      {
        action: "closed",
        repository: { full_name: "owner/repo" },
        pull_request: {
          number: 11,
          merged: true,
          html_url: "https://github.com/owner/repo/pull/11",
          title: "Merged PR",
        },
      },
      { deliveryId: "store-merged-1" }
    );
    await SELF.fetch(req);

    const row = await env.DB
      .prepare("SELECT action FROM pr_changes WHERE delivery_id = ?")
      .bind("store-merged-1")
      .first<{ action: string }>();
    expect(row?.action).toBe("merged");
  });

  it("fans out push to subscribed devices", async () => {
    await env.DB.exec(
      `INSERT INTO device_tokens (user_id, token, platform) VALUES ('u1', 'tok-1', 'android');
       INSERT INTO repo_subscriptions (user_id, repo_full_name) VALUES ('u1', 'owner/repo');
       INSERT INTO user_github_tokens (user_id, encrypted_token, github_login) VALUES ('u1', 'enc-tok', 'testuser');`
    );

    const req = await webhookRequest(makePrEvent("owner/repo", 5, "opened", "testuser"), {
      deliveryId: "fan-1",
    });
    const res = await SELF.fetch(req);
    const body = await res.json<{ fanOutCount: number }>();
    expect(body.fanOutCount).toBe(1);
  });
});
