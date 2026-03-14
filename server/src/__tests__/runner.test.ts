import { describe, it, expect, beforeAll, beforeEach } from "vitest";
import {
  SELF,
  env,
  initSchema,
  resetDb,
  jsonRequest,
  apiHeaders,
} from "./helpers";
import { hashPairingToken } from "../runner";

beforeAll(initSchema);
beforeEach(resetDb);

function runnerHeaders(pairingToken: string): Record<string, string> {
  return { "x-runner-token": pairingToken };
}

async function registerRunner(
  deviceId = "device-001",
  pairingToken = "tok-pair-001",
  label?: string,
  githubLogin = "testuser"
): Promise<void> {
  const body: Record<string, string> = {
    userId: "u1",
    deviceId,
    pairingToken,
    githubLogin,
  };
  if (label) body.label = label;
  const req = jsonRequest("POST", "/runners/register", body, apiHeaders());
  const res = await SELF.fetch(req);
  expect(res.status).toBe(200);
}

describe("POST /runners/register", () => {
  it("registers a new runner with githubLogin", async () => {
    await registerRunner();

    const row = await env.DB
      .prepare("SELECT * FROM runners WHERE device_id = ?")
      .bind("device-001")
      .first();
    expect(row).not.toBeNull();
    expect(row!.user_id).toBe("u1");
    expect(row!.device_id).toBe("device-001");
    expect(row!.github_login).toBe("testuser");
  });

  it("stores pairing token as hash, not plaintext", async () => {
    const pairingToken = "my-secret-token-123";
    await registerRunner("device-hash", pairingToken);

    const row = await env.DB
      .prepare("SELECT pairing_token_hash FROM runners WHERE device_id = ?")
      .bind("device-hash")
      .first<{ pairing_token_hash: string }>();
    expect(row).not.toBeNull();
    expect(row!.pairing_token_hash).not.toBe(pairingToken);
    const expectedHash = await hashPairingToken(pairingToken);
    expect(row!.pairing_token_hash).toBe(expectedHash);
  });

  it("upserts on duplicate device_id", async () => {
    await registerRunner("device-dup", "tok-1");

    const req = jsonRequest(
      "POST",
      "/runners/register",
      { userId: "u2", deviceId: "device-dup", pairingToken: "tok-2", githubLogin: "user2" },
      apiHeaders()
    );
    await SELF.fetch(req);

    const row = await env.DB
      .prepare("SELECT user_id, github_login FROM runners WHERE device_id = ?")
      .bind("device-dup")
      .first<{ user_id: string; github_login: string }>();
    expect(row!.user_id).toBe("u2");
    expect(row!.github_login).toBe("user2");
  });

  it("saves optional label", async () => {
    await registerRunner("device-label", "tok-label", "macbook-pro");

    const row = await env.DB
      .prepare("SELECT label FROM runners WHERE device_id = ?")
      .bind("device-label")
      .first<{ label: string }>();
    expect(row!.label).toBe("macbook-pro");
  });

  it("rejects missing deviceId", async () => {
    const req = jsonRequest(
      "POST",
      "/runners/register",
      { userId: "u1", pairingToken: "tok", githubLogin: "user1" },
      apiHeaders()
    );
    const res = await SELF.fetch(req);
    expect(res.status).toBe(400);
  });

  it("rejects missing pairingToken", async () => {
    const req = jsonRequest(
      "POST",
      "/runners/register",
      { userId: "u1", deviceId: "dev", githubLogin: "user1" },
      apiHeaders()
    );
    const res = await SELF.fetch(req);
    expect(res.status).toBe(400);
  });

  it("rejects missing githubLogin", async () => {
    const req = jsonRequest(
      "POST",
      "/runners/register",
      { userId: "u1", deviceId: "dev", pairingToken: "tok" },
      apiHeaders()
    );
    const res = await SELF.fetch(req);
    expect(res.status).toBe(400);
  });

  it("rejects without auth", async () => {
    const req = jsonRequest("POST", "/runners/register", {
      userId: "u1",
      deviceId: "dev",
      pairingToken: "tok",
      githubLogin: "user1",
    });
    const res = await SELF.fetch(req);
    expect(res.status).toBe(401);
  });
});

describe("GET /runners/status", () => {
  it("returns runner status with valid pairing token", async () => {
    const pairingToken = "status-tok-001";
    await registerRunner("device-status", pairingToken);

    const req = jsonRequest(
      "GET",
      "/runners/status",
      undefined,
      runnerHeaders(pairingToken)
    );
    const res = await SELF.fetch(req);
    expect(res.status).toBe(200);

    const body = await res.json<{
      ok: boolean;
      deviceId: string;
      userId: string;
      githubLogin: string;
    }>();
    expect(body.ok).toBe(true);
    expect(body.deviceId).toBe("device-status");
    expect(body.userId).toBe("u1");
    expect(body.githubLogin).toBe("testuser");
  });

  it("rejects missing x-runner-token", async () => {
    const req = jsonRequest("GET", "/runners/status");
    const res = await SELF.fetch(req);
    expect(res.status).toBe(401);
  });

  it("rejects invalid pairing token", async () => {
    const req = jsonRequest(
      "GET",
      "/runners/status",
      undefined,
      runnerHeaders("invalid-token")
    );
    const res = await SELF.fetch(req);
    expect(res.status).toBe(401);
  });
});

describe("DELETE /runners/register", () => {
  it("unregisters a runner", async () => {
    const pairingToken = "del-tok-001";
    await registerRunner("device-del", pairingToken);

    const req = jsonRequest(
      "DELETE",
      "/runners/register",
      undefined,
      runnerHeaders(pairingToken)
    );
    const res = await SELF.fetch(req);
    expect(res.status).toBe(200);

    const row = await env.DB
      .prepare("SELECT * FROM runners WHERE device_id = ?")
      .bind("device-del")
      .first();
    expect(row).toBeNull();
  });

  it("rejects without auth", async () => {
    const req = jsonRequest("DELETE", "/runners/register");
    const res = await SELF.fetch(req);
    expect(res.status).toBe(401);
  });
});

describe("POST /runners/sync", () => {
  it("syncs notifications and stores pr_changes + involvement", async () => {
    const pairingToken = "sync-tok-001";
    await registerRunner("device-sync", pairingToken);

    const req = jsonRequest(
      "POST",
      "/runners/sync",
      {
        notifications: [
          {
            repo: "owner/repo",
            prNumber: 42,
            action: "review_requested",
            prTitle: "Fix bug",
            prUrl: "https://github.com/owner/repo/pull/42",
            author: "alice",
            reviewers: ["bob"],
            mentionedUser: "carol",
          },
        ],
      },
      runnerHeaders(pairingToken)
    );
    const res = await SELF.fetch(req);
    expect(res.status).toBe(200);

    const body = await res.json<{ ok: boolean; syncedCount: number; pushedCount: number }>();
    expect(body.ok).toBe(true);
    expect(body.syncedCount).toBe(1);

    // Check pr_changes
    const prChange = await env.DB
      .prepare("SELECT * FROM pr_changes WHERE repo_full_name = ? AND pr_number = ?")
      .bind("owner/repo", 42)
      .first();
    expect(prChange).not.toBeNull();
    expect(prChange!.action).toBe("review_requested");

    // Check pr_user_involvement
    const involvement = await env.DB
      .prepare("SELECT * FROM pr_user_involvement WHERE repo_full_name = ? AND pr_number = ?")
      .bind("owner/repo", 42)
      .all();
    const logins = (involvement.results ?? []).map((r: Record<string, unknown>) => r.github_login);
    expect(logins).toContain("alice");
    expect(logins).toContain("bob");
    expect(logins).toContain("carol");
  });

  it("returns syncedCount 0 for empty notifications", async () => {
    const pairingToken = "sync-empty-tok";
    await registerRunner("device-sync-empty", pairingToken);

    const req = jsonRequest(
      "POST",
      "/runners/sync",
      { notifications: [] },
      runnerHeaders(pairingToken)
    );
    const res = await SELF.fetch(req);
    expect(res.status).toBe(200);

    const body = await res.json<{ syncedCount: number; pushedCount: number }>();
    expect(body.syncedCount).toBe(0);
    expect(body.pushedCount).toBe(0);
  });

  it("skips invalid notifications", async () => {
    const pairingToken = "sync-invalid-tok";
    await registerRunner("device-sync-invalid", pairingToken);

    const req = jsonRequest(
      "POST",
      "/runners/sync",
      {
        notifications: [
          { repo: "", prNumber: 42, action: "opened" },
          { repo: "o/r", prNumber: 0, action: "opened" },
          { repo: "o/r", prNumber: 1, action: "" },
        ],
      },
      runnerHeaders(pairingToken)
    );
    const res = await SELF.fetch(req);
    expect(res.status).toBe(200);

    const body = await res.json<{ syncedCount: number }>();
    expect(body.syncedCount).toBe(0);
  });

  it("triggers FCM push for subscribed users with involvement", async () => {
    const pairingToken = "sync-push-tok";
    await registerRunner("device-sync-push", pairingToken, undefined, "alice");

    // Set up device token and subscription for the runner's user
    await env.DB.exec(
      `INSERT INTO device_tokens (user_id, token, platform) VALUES ('u1', 'fcm-tok-1', 'android');
       INSERT INTO repo_subscriptions (user_id, repo_full_name) VALUES ('u1', 'owner/repo');`
    );

    // Sync a notification where the runner's user (alice) is the author
    const req = jsonRequest(
      "POST",
      "/runners/sync",
      {
        notifications: [
          {
            repo: "owner/repo",
            prNumber: 10,
            action: "opened",
            author: "alice",
          },
        ],
      },
      runnerHeaders(pairingToken)
    );
    const res = await SELF.fetch(req);
    expect(res.status).toBe(200);

    const body = await res.json<{ syncedCount: number }>();
    expect(body.syncedCount).toBe(1);

    // Verify pr_user_involvement was created (FCM send fails in test env, so pushedCount is 0)
    const involvement = await env.DB
      .prepare("SELECT * FROM pr_user_involvement WHERE repo_full_name = ? AND pr_number = ? AND github_login = ?")
      .bind("owner/repo", 10, "alice")
      .first();
    expect(involvement).not.toBeNull();
  });

  it("rejects without auth", async () => {
    const req = jsonRequest("POST", "/runners/sync", { notifications: [] });
    const res = await SELF.fetch(req);
    expect(res.status).toBe(401);
  });
});

describe("POST /runners/poll-status", () => {
  it("updates runner poll status", async () => {
    const pairingToken = "poll-status-tok";
    await registerRunner("device-poll-status", pairingToken);

    const req = jsonRequest(
      "POST",
      "/runners/poll-status",
      { status: "ok" },
      runnerHeaders(pairingToken)
    );
    const res = await SELF.fetch(req);
    expect(res.status).toBe(200);

    const row = await env.DB
      .prepare("SELECT last_poll_status, last_poll_at FROM runners WHERE device_id = ?")
      .bind("device-poll-status")
      .first<{ last_poll_status: string; last_poll_at: string }>();
    expect(row!.last_poll_status).toBe("ok");
    expect(row!.last_poll_at).not.toBeNull();
  });

  it("stores poll error", async () => {
    const pairingToken = "poll-err-tok";
    await registerRunner("device-poll-err", pairingToken);

    const req = jsonRequest(
      "POST",
      "/runners/poll-status",
      { status: "error", error: "rate limited" },
      runnerHeaders(pairingToken)
    );
    const res = await SELF.fetch(req);
    expect(res.status).toBe(200);

    const row = await env.DB
      .prepare("SELECT last_poll_status, last_poll_error FROM runners WHERE device_id = ?")
      .bind("device-poll-err")
      .first<{ last_poll_status: string; last_poll_error: string }>();
    expect(row!.last_poll_status).toBe("error");
    expect(row!.last_poll_error).toBe("rate limited");
  });

  it("rejects missing status", async () => {
    const pairingToken = "poll-no-status-tok";
    await registerRunner("device-poll-no-status", pairingToken);

    const req = jsonRequest(
      "POST",
      "/runners/poll-status",
      { error: "something" },
      runnerHeaders(pairingToken)
    );
    const res = await SELF.fetch(req);
    expect(res.status).toBe(400);
  });

  it("rejects without auth", async () => {
    const req = jsonRequest("POST", "/runners/poll-status", { status: "ok" });
    const res = await SELF.fetch(req);
    expect(res.status).toBe(401);
  });
});

describe("GET /runners/subscriptions", () => {
  it("returns subscriptions for the runner's user", async () => {
    const pairingToken = "subs-tok";
    await registerRunner("device-subs", pairingToken);

    await env.DB.exec(
      `INSERT INTO repo_subscriptions (user_id, repo_full_name) VALUES ('u1', 'owner/repo-a');
       INSERT INTO repo_subscriptions (user_id, repo_full_name) VALUES ('u1', 'owner/repo-b');
       INSERT INTO repo_subscriptions (user_id, repo_full_name) VALUES ('u2', 'other/repo');`
    );

    const req = jsonRequest(
      "GET",
      "/runners/subscriptions",
      undefined,
      runnerHeaders(pairingToken)
    );
    const res = await SELF.fetch(req);
    expect(res.status).toBe(200);

    const body = await res.json<{ ok: boolean; subscriptions: string[] }>();
    expect(body.ok).toBe(true);
    expect(body.subscriptions).toHaveLength(2);
    expect(body.subscriptions).toContain("owner/repo-a");
    expect(body.subscriptions).toContain("owner/repo-b");
  });

  it("returns empty array when no subscriptions", async () => {
    const pairingToken = "subs-empty-tok";
    await registerRunner("device-subs-empty", pairingToken);

    const req = jsonRequest(
      "GET",
      "/runners/subscriptions",
      undefined,
      runnerHeaders(pairingToken)
    );
    const res = await SELF.fetch(req);
    expect(res.status).toBe(200);

    const body = await res.json<{ subscriptions: string[] }>();
    expect(body.subscriptions).toHaveLength(0);
  });

  it("rejects without auth", async () => {
    const req = jsonRequest("GET", "/runners/subscriptions");
    const res = await SELF.fetch(req);
    expect(res.status).toBe(401);
  });
});

describe("POST /commands/retry-ci", () => {
  it("submits a retry-ci command", async () => {
    await registerRunner("device-cmd", "cmd-tok");

    const req = jsonRequest(
      "POST",
      "/commands/retry-ci",
      { userId: "u1", repoFullName: "owner/repo", prNumber: 42 },
      apiHeaders()
    );
    const res = await SELF.fetch(req);
    expect(res.status).toBe(200);

    const body = await res.json<{ ok: boolean; commandId: number }>();
    expect(body.ok).toBe(true);
    expect(body.commandId).toBeGreaterThan(0);

    const cmd = await env.DB
      .prepare("SELECT * FROM runner_commands WHERE id = ?")
      .bind(body.commandId)
      .first<{ command_type: string; status: string; payload: string }>();
    expect(cmd).not.toBeNull();
    expect(cmd!.command_type).toBe("retry-ci");
    expect(cmd!.status).toBe("pending");
    expect(JSON.parse(cmd!.payload)).toEqual({
      repoFullName: "owner/repo",
      prNumber: 42,
    });
  });

  it("rejects missing repoFullName", async () => {
    await registerRunner("device-cmd2", "cmd-tok2");

    const req = jsonRequest(
      "POST",
      "/commands/retry-ci",
      { userId: "u1" },
      apiHeaders()
    );
    const res = await SELF.fetch(req);
    expect(res.status).toBe(400);
  });

  it("rejects when user has no runner", async () => {
    const req = jsonRequest(
      "POST",
      "/commands/retry-ci",
      { userId: "u-no-runner", repoFullName: "owner/repo" },
      apiHeaders()
    );
    const res = await SELF.fetch(req);
    expect(res.status).toBe(400);
  });

  it("rejects without auth", async () => {
    const req = jsonRequest("POST", "/commands/retry-ci", {
      repoFullName: "owner/repo",
    });
    const res = await SELF.fetch(req);
    expect(res.status).toBe(401);
  });
});

describe("GET /runners/commands/poll", () => {
  it("returns pending commands and marks them running", async () => {
    const pairingToken = "poll-tok";
    await registerRunner("device-poll", pairingToken);

    // Submit a command
    const submitReq = jsonRequest(
      "POST",
      "/commands/retry-ci",
      { userId: "u1", repoFullName: "owner/repo" },
      apiHeaders()
    );
    await SELF.fetch(submitReq);

    // Poll
    const req = jsonRequest(
      "GET",
      "/runners/commands/poll",
      undefined,
      runnerHeaders(pairingToken)
    );
    const res = await SELF.fetch(req);
    expect(res.status).toBe(200);

    const body = await res.json<{
      ok: boolean;
      commands: Array<{
        id: number;
        commandType: string;
        payload: { repoFullName: string };
      }>;
    }>();
    expect(body.ok).toBe(true);
    expect(body.commands).toHaveLength(1);
    expect(body.commands[0].commandType).toBe("retry-ci");
    expect(body.commands[0].payload.repoFullName).toBe("owner/repo");

    // Verify status changed to running
    const cmd = await env.DB
      .prepare("SELECT status FROM runner_commands WHERE id = ?")
      .bind(body.commands[0].id)
      .first<{ status: string }>();
    expect(cmd!.status).toBe("running");
  });

  it("returns empty array when no pending commands", async () => {
    const pairingToken = "poll-empty-tok";
    await registerRunner("device-poll-empty", pairingToken);

    const req = jsonRequest(
      "GET",
      "/runners/commands/poll",
      undefined,
      runnerHeaders(pairingToken)
    );
    const res = await SELF.fetch(req);
    expect(res.status).toBe(200);

    const body = await res.json<{ commands: unknown[] }>();
    expect(body.commands).toHaveLength(0);
  });
});

describe("POST /runners/commands/:id/result", () => {
  it("reports command result successfully", async () => {
    const pairingToken = "result-tok";
    await registerRunner("device-result", pairingToken);

    // Submit command
    const submitReq = jsonRequest(
      "POST",
      "/commands/retry-ci",
      { userId: "u1", repoFullName: "owner/repo" },
      apiHeaders()
    );
    const submitRes = await SELF.fetch(submitReq);
    const { commandId } = await submitRes.json<{ commandId: number }>();

    // Report result
    const req = jsonRequest(
      "POST",
      `/runners/commands/${commandId}/result`,
      { status: "completed", result: { rerunCount: 2 } },
      runnerHeaders(pairingToken)
    );
    const res = await SELF.fetch(req);
    expect(res.status).toBe(200);

    const cmd = await env.DB
      .prepare("SELECT status, result FROM runner_commands WHERE id = ?")
      .bind(commandId)
      .first<{ status: string; result: string }>();
    expect(cmd!.status).toBe("completed");
    expect(JSON.parse(cmd!.result)).toEqual({ rerunCount: 2 });
  });

  it("rejects invalid status", async () => {
    const pairingToken = "result-bad-tok";
    await registerRunner("device-result-bad", pairingToken);

    const submitReq = jsonRequest(
      "POST",
      "/commands/retry-ci",
      { userId: "u1", repoFullName: "owner/repo" },
      apiHeaders()
    );
    const submitRes = await SELF.fetch(submitReq);
    const { commandId } = await submitRes.json<{ commandId: number }>();

    const req = jsonRequest(
      "POST",
      `/runners/commands/${commandId}/result`,
      { status: "invalid" },
      runnerHeaders(pairingToken)
    );
    const res = await SELF.fetch(req);
    expect(res.status).toBe(400);
  });

  it("rejects without auth", async () => {
    const req = jsonRequest("POST", "/runners/commands/1/result", {
      status: "completed",
    });
    const res = await SELF.fetch(req);
    expect(res.status).toBe(401);
  });

  it("returns 404 for non-existent command", async () => {
    const pairingToken = "result-404-tok";
    await registerRunner("device-result-404", pairingToken);

    const req = jsonRequest(
      "POST",
      "/runners/commands/99999/result",
      { status: "completed" },
      runnerHeaders(pairingToken)
    );
    const res = await SELF.fetch(req);
    expect(res.status).toBe(404);
  });
});
