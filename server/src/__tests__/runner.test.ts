import { describe, it, expect, beforeAll, beforeEach } from "vitest";
import {
  SELF,
  env,
  initSchema,
  resetDb,
  jsonRequest,
  apiHeaders,
} from "./helpers";
import { hashPairingToken, cleanupStaleCommands } from "../runner";

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

describe("POST /commands/retry-flaky", () => {
  it("creates a flaky retry job and command", async () => {
    await registerRunner("device-flaky", "flaky-tok");

    const req = jsonRequest(
      "POST",
      "/commands/retry-flaky",
      { userId: "u1", repoFullName: "owner/repo", prNumber: 10 },
      apiHeaders()
    );
    const res = await SELF.fetch(req);
    expect(res.status).toBe(200);

    const body = await res.json<{ ok: boolean; commandId: number; jobId: number }>();
    expect(body.ok).toBe(true);
    expect(body.commandId).toBeGreaterThan(0);
    expect(body.jobId).toBeGreaterThan(0);

    // Check job created
    const job = await env.DB
      .prepare("SELECT * FROM flaky_retry_jobs WHERE repo_full_name = ? AND pr_number = ?")
      .bind("owner/repo", 10)
      .first<{ retries_remaining: number; status: string; workflow_attempts: string }>();
    expect(job).not.toBeNull();
    expect(job!.retries_remaining).toBe(3);
    expect(job!.status).toBe("active");
    expect(job!.workflow_attempts).toBe("{}");

    // Check command created
    const cmd = await env.DB
      .prepare("SELECT * FROM runner_commands WHERE id = ?")
      .bind(body.commandId)
      .first<{ command_type: string; payload: string; status: string }>();
    expect(cmd!.command_type).toBe("retry-flaky");
    expect(cmd!.status).toBe("pending");
    expect(JSON.parse(cmd!.payload)).toEqual({
      repoFullName: "owner/repo",
      prNumber: 10,
      workflowAttempts: {},
    });
  });

  it("rejects missing prNumber", async () => {
    await registerRunner("device-flaky2", "flaky-tok2");

    const req = jsonRequest(
      "POST",
      "/commands/retry-flaky",
      { userId: "u1", repoFullName: "owner/repo" },
      apiHeaders()
    );
    const res = await SELF.fetch(req);
    expect(res.status).toBe(400);
  });

  it("resets budget to 3 on resubmit", async () => {
    await registerRunner("device-flaky3", "flaky-tok3");

    // First submit
    const req1 = jsonRequest(
      "POST",
      "/commands/retry-flaky",
      { userId: "u1", repoFullName: "owner/repo", prNumber: 20 },
      apiHeaders()
    );
    await SELF.fetch(req1);

    // Simulate decrementing retries_remaining
    await env.DB.exec(
      "UPDATE flaky_retry_jobs SET retries_remaining = 1 WHERE repo_full_name = 'owner/repo' AND pr_number = 20"
    );

    // Mark existing command as completed so dedup doesn't kick in
    await env.DB.exec(
      "UPDATE runner_commands SET status = 'completed' WHERE command_type = 'retry-flaky'"
    );

    // Resubmit
    const req2 = jsonRequest(
      "POST",
      "/commands/retry-flaky",
      { userId: "u1", repoFullName: "owner/repo", prNumber: 20 },
      apiHeaders()
    );
    const res2 = await SELF.fetch(req2);
    expect(res2.status).toBe(200);

    const job = await env.DB
      .prepare("SELECT retries_remaining, status FROM flaky_retry_jobs WHERE repo_full_name = ? AND pr_number = ?")
      .bind("owner/repo", 20)
      .first<{ retries_remaining: number; status: string }>();
    expect(job!.retries_remaining).toBe(3);
    expect(job!.status).toBe("active");
  });

  it("deduplicates when pending command already exists", async () => {
    await registerRunner("device-flaky4", "flaky-tok4");

    // First submit
    const req1 = jsonRequest(
      "POST",
      "/commands/retry-flaky",
      { userId: "u1", repoFullName: "owner/repo", prNumber: 30 },
      apiHeaders()
    );
    const res1 = await SELF.fetch(req1);
    const body1 = await res1.json<{ commandId: number }>();

    // Second submit — should return same commandId
    const req2 = jsonRequest(
      "POST",
      "/commands/retry-flaky",
      { userId: "u1", repoFullName: "owner/repo", prNumber: 30 },
      apiHeaders()
    );
    const res2 = await SELF.fetch(req2);
    const body2 = await res2.json<{ commandId: number }>();

    expect(body2.commandId).toBe(body1.commandId);

    // Only one command should exist
    const cmds = await env.DB
      .prepare("SELECT id FROM runner_commands WHERE command_type = 'retry-flaky'")
      .all<{ id: number }>();
    expect(cmds.results).toHaveLength(1);
  });
});

describe("retry-flaky post-processing", () => {
  it("schedules next round when retriedCount > 0 and budget remaining", async () => {
    const pairingToken = "flaky-pp-tok";
    await registerRunner("device-flaky-pp", pairingToken);

    // Submit retry-flaky
    const submitReq = jsonRequest(
      "POST",
      "/commands/retry-flaky",
      { userId: "u1", repoFullName: "owner/repo", prNumber: 50 },
      apiHeaders()
    );
    const submitRes = await SELF.fetch(submitReq);
    const { commandId } = await submitRes.json<{ commandId: number }>();

    // Report result with retriedCount > 0
    const resultReq = jsonRequest(
      "POST",
      `/runners/commands/${commandId}/result`,
      {
        status: "completed",
        result: {
          retriedCount: 2,
          workflows: [
            { name: "Build", attempts: 1 },
            { name: "Test", attempts: 1 },
          ],
        },
      },
      runnerHeaders(pairingToken)
    );
    const resultRes = await SELF.fetch(resultReq);
    expect(resultRes.status).toBe(200);

    // Check job updated
    const job = await env.DB
      .prepare("SELECT retries_remaining, workflow_attempts, status FROM flaky_retry_jobs WHERE repo_full_name = ? AND pr_number = ?")
      .bind("owner/repo", 50)
      .first<{ retries_remaining: number; workflow_attempts: string; status: string }>();
    expect(job!.retries_remaining).toBe(2);
    expect(job!.status).toBe("active");
    expect(JSON.parse(job!.workflow_attempts)).toEqual({ Build: 1, Test: 1 });

    // Check new delayed command created
    const nextCmd = await env.DB
      .prepare("SELECT command_type, status, scheduled_after, payload FROM runner_commands WHERE status = 'pending' AND command_type = 'retry-flaky'")
      .first<{ command_type: string; status: string; scheduled_after: string; payload: string }>();
    expect(nextCmd).not.toBeNull();
    expect(nextCmd!.scheduled_after).not.toBeNull();
    const payload = JSON.parse(nextCmd!.payload);
    expect(payload.workflowAttempts).toEqual({ Build: 1, Test: 1 });
  });

  it("marks job completed when retriedCount is 0", async () => {
    const pairingToken = "flaky-done-tok";
    await registerRunner("device-flaky-done", pairingToken);

    // Submit retry-flaky
    const submitReq = jsonRequest(
      "POST",
      "/commands/retry-flaky",
      { userId: "u1", repoFullName: "owner/repo", prNumber: 60 },
      apiHeaders()
    );
    const submitRes = await SELF.fetch(submitReq);
    const { commandId } = await submitRes.json<{ commandId: number }>();

    // Report result with retriedCount = 0 (no failures)
    const resultReq = jsonRequest(
      "POST",
      `/runners/commands/${commandId}/result`,
      {
        status: "completed",
        result: { retriedCount: 0, workflows: [] },
      },
      runnerHeaders(pairingToken)
    );
    await SELF.fetch(resultReq);

    const job = await env.DB
      .prepare("SELECT status FROM flaky_retry_jobs WHERE repo_full_name = ? AND pr_number = ?")
      .bind("owner/repo", 60)
      .first<{ status: string }>();
    expect(job!.status).toBe("completed");
  });

  it("marks job exhausted when budget reaches 0", async () => {
    const pairingToken = "flaky-exhaust-tok";
    await registerRunner("device-flaky-exhaust", pairingToken);

    // Submit retry-flaky
    const submitReq = jsonRequest(
      "POST",
      "/commands/retry-flaky",
      { userId: "u1", repoFullName: "owner/repo", prNumber: 70 },
      apiHeaders()
    );
    const submitRes = await SELF.fetch(submitReq);
    const { commandId } = await submitRes.json<{ commandId: number }>();

    // Set retries_remaining to 1 so next completion exhausts it
    await env.DB.exec(
      "UPDATE flaky_retry_jobs SET retries_remaining = 1 WHERE repo_full_name = 'owner/repo' AND pr_number = 70"
    );

    // Report result with retriedCount > 0
    const resultReq = jsonRequest(
      "POST",
      `/runners/commands/${commandId}/result`,
      {
        status: "completed",
        result: { retriedCount: 1, workflows: [{ name: "Build", attempts: 3 }] },
      },
      runnerHeaders(pairingToken)
    );
    await SELF.fetch(resultReq);

    const job = await env.DB
      .prepare("SELECT status, retries_remaining FROM flaky_retry_jobs WHERE repo_full_name = ? AND pr_number = ?")
      .bind("owner/repo", 70)
      .first<{ status: string; retries_remaining: number }>();
    expect(job!.status).toBe("exhausted");
    expect(job!.retries_remaining).toBe(0);

    // No new pending command should be created
    const nextCmd = await env.DB
      .prepare("SELECT id FROM runner_commands WHERE status = 'pending' AND command_type = 'retry-flaky'")
      .first<{ id: number }>();
    expect(nextCmd).toBeNull();
  });
});

describe("handlePollCommands scheduled_after filtering", () => {
  it("does not return commands scheduled in the future", async () => {
    const pairingToken = "sched-tok";
    await registerRunner("device-sched", pairingToken);

    const runner = await env.DB
      .prepare("SELECT id FROM runners WHERE device_id = ?")
      .bind("device-sched")
      .first<{ id: number }>();

    // Insert a command scheduled far in the future
    await env.DB.prepare(
      `INSERT INTO runner_commands (runner_id, user_id, command_type, payload, status, scheduled_after, created_at, updated_at)
       VALUES (?, 'u1', 'retry-flaky', '{}', 'pending', datetime('now', '+1 hour'), datetime('now'), datetime('now'))`
    ).bind(runner!.id).run();

    const req = jsonRequest(
      "GET",
      "/runners/commands/poll",
      undefined,
      runnerHeaders(pairingToken)
    );
    const res = await SELF.fetch(req);
    const body = await res.json<{ commands: unknown[] }>();
    expect(body.commands).toHaveLength(0);
  });

  it("returns commands with null scheduled_after", async () => {
    const pairingToken = "sched-null-tok";
    await registerRunner("device-sched-null", pairingToken);

    // Submit a normal command (no scheduled_after)
    const submitReq = jsonRequest(
      "POST",
      "/commands/retry-ci",
      { userId: "u1", repoFullName: "owner/repo" },
      apiHeaders()
    );
    await SELF.fetch(submitReq);

    const req = jsonRequest(
      "GET",
      "/runners/commands/poll",
      undefined,
      runnerHeaders(pairingToken)
    );
    const res = await SELF.fetch(req);
    const body = await res.json<{ commands: unknown[] }>();
    expect(body.commands).toHaveLength(1);
  });
});

describe("GET /commands/retry-flaky (list jobs)", () => {
  it("returns active retry jobs for the user", async () => {
    await registerRunner("device-list-jobs", "list-jobs-tok");

    // Create a job
    const submitReq = jsonRequest(
      "POST",
      "/commands/retry-flaky",
      { userId: "u1", repoFullName: "owner/repo", prNumber: 100 },
      apiHeaders()
    );
    await SELF.fetch(submitReq);

    const req = jsonRequest(
      "GET",
      "/commands/retry-flaky?userId=u1",
      undefined,
      apiHeaders()
    );
    const res = await SELF.fetch(req);
    expect(res.status).toBe(200);

    const body = await res.json<{
      ok: boolean;
      jobs: Array<{
        id: number;
        repoFullName: string;
        prNumber: number;
        retriesRemaining: number;
        status: string;
      }>;
    }>();
    expect(body.ok).toBe(true);
    expect(body.jobs).toHaveLength(1);
    expect(body.jobs[0].repoFullName).toBe("owner/repo");
    expect(body.jobs[0].prNumber).toBe(100);
    expect(body.jobs[0].retriesRemaining).toBe(3);
    expect(body.jobs[0].status).toBe("active");
  });

  it("returns empty array when no jobs", async () => {
    const req = jsonRequest(
      "GET",
      "/commands/retry-flaky?userId=u-no-jobs",
      undefined,
      apiHeaders()
    );
    const res = await SELF.fetch(req);
    expect(res.status).toBe(200);

    const body = await res.json<{ jobs: unknown[] }>();
    expect(body.jobs).toHaveLength(0);
  });

  it("rejects without auth", async () => {
    const req = jsonRequest("GET", "/commands/retry-flaky");
    const res = await SELF.fetch(req);
    expect(res.status).toBe(401);
  });
});

describe("DELETE /commands/retry-flaky (cancel)", () => {
  it("cancels an active retry job and deletes pending commands", async () => {
    await registerRunner("device-cancel", "cancel-tok");

    // Create a job
    const submitReq = jsonRequest(
      "POST",
      "/commands/retry-flaky",
      { userId: "u1", repoFullName: "owner/repo", prNumber: 200 },
      apiHeaders()
    );
    await SELF.fetch(submitReq);

    // Cancel it
    const cancelReq = jsonRequest(
      "DELETE",
      "/commands/retry-flaky",
      { userId: "u1", repoFullName: "owner/repo", prNumber: 200 },
      apiHeaders()
    );
    const cancelRes = await SELF.fetch(cancelReq);
    expect(cancelRes.status).toBe(200);

    const body = await cancelRes.json<{ ok: boolean }>();
    expect(body.ok).toBe(true);

    // Job should be cancelled
    const job = await env.DB
      .prepare("SELECT status FROM flaky_retry_jobs WHERE repo_full_name = ? AND pr_number = ?")
      .bind("owner/repo", 200)
      .first<{ status: string }>();
    expect(job!.status).toBe("cancelled");

    // Pending commands should be deleted
    const cmds = await env.DB
      .prepare("SELECT id FROM runner_commands WHERE command_type = 'retry-flaky' AND status = 'pending'")
      .all<{ id: number }>();
    expect(cmds.results).toHaveLength(0);
  });

  it("returns 404 when no active job exists", async () => {
    const req = jsonRequest(
      "DELETE",
      "/commands/retry-flaky",
      { userId: "u1", repoFullName: "owner/repo", prNumber: 999 },
      apiHeaders()
    );
    const res = await SELF.fetch(req);
    expect(res.status).toBe(404);
  });

  it("rejects without auth", async () => {
    const req = jsonRequest("DELETE", "/commands/retry-flaky", {
      repoFullName: "owner/repo",
      prNumber: 1,
    });
    const res = await SELF.fetch(req);
    expect(res.status).toBe(401);
  });
});

describe("cleanupStaleCommands", () => {
  async function insertCommand(
    runnerId: number,
    status: string,
    updatedAt: string,
    commandType: string = "retry-ci"
  ): Promise<number> {
    const result = await env.DB.prepare(
      `INSERT INTO runner_commands (runner_id, user_id, command_type, payload, status, created_at, updated_at)
       VALUES (?, 'u1', ?, '{}', ?, datetime('now'), datetime('now', ?))`,
    ).bind(runnerId, commandType, status, updatedAt).run();
    return result.meta.last_row_id as number;
  }

  async function insertJob(
    runnerId: number,
    prNumber: number,
    status: string,
    updatedAt: string,
  ): Promise<number> {
    const result = await env.DB.prepare(
      `INSERT INTO flaky_retry_jobs (repo_full_name, pr_number, user_id, runner_id, retries_remaining, workflow_attempts, status, created_at, updated_at)
       VALUES ('owner/repo', ?, 'u1', ?, 3, '{}', ?, datetime('now'), datetime('now', ?))`,
    ).bind(prNumber, runnerId, status, updatedAt).run();
    return result.meta.last_row_id as number;
  }

  async function getRunnerId(): Promise<number> {
    await registerRunner("device-cleanup", "cleanup-tok");
    const runner = await env.DB
      .prepare("SELECT id FROM runners WHERE device_id = ?")
      .bind("device-cleanup")
      .first<{ id: number }>();
    return runner!.id;
  }

  it("deletes terminal commands older than 24h", async () => {
    const rid = await getRunnerId();
    await insertCommand(rid, "completed", "-25 hours");
    await insertCommand(rid, "failed", "-25 hours");

    const result = await cleanupStaleCommands(env);

    expect(result.deletedCommands).toBe(2);
    const remaining = await env.DB.prepare("SELECT count(*) as c FROM runner_commands").first<{ c: number }>();
    expect(remaining!.c).toBe(0);
  });

  it("preserves terminal commands within 24h", async () => {
    const rid = await getRunnerId();
    await insertCommand(rid, "completed", "-1 hour");

    const result = await cleanupStaleCommands(env);

    expect(result.deletedCommands).toBe(0);
    const remaining = await env.DB.prepare("SELECT count(*) as c FROM runner_commands").first<{ c: number }>();
    expect(remaining!.c).toBe(1);
  });

  it("deletes stale pending/running commands older than 7 days", async () => {
    const rid = await getRunnerId();
    await insertCommand(rid, "pending", "-8 days");
    await insertCommand(rid, "running", "-8 days");

    const result = await cleanupStaleCommands(env);

    expect(result.deletedCommands).toBe(2);
    const remaining = await env.DB.prepare("SELECT count(*) as c FROM runner_commands").first<{ c: number }>();
    expect(remaining!.c).toBe(0);
  });

  it("preserves pending/running commands within 7 days", async () => {
    const rid = await getRunnerId();
    await insertCommand(rid, "pending", "-1 day");
    await insertCommand(rid, "running", "-1 day");

    const result = await cleanupStaleCommands(env);

    expect(result.deletedCommands).toBe(0);
    const remaining = await env.DB.prepare("SELECT count(*) as c FROM runner_commands").first<{ c: number }>();
    expect(remaining!.c).toBe(2);
  });

  it("expires active jobs stuck for 7+ days and cleans pending commands", async () => {
    const rid = await getRunnerId();
    const jobId = await insertJob(rid, 500, "active", "-8 days");
    // Associated pending command
    await env.DB.prepare(
      `INSERT INTO runner_commands (runner_id, user_id, command_type, payload, status, created_at, updated_at)
       VALUES (?, 'u1', 'retry-flaky', '{"repoFullName":"owner/repo","prNumber":500}', 'pending', datetime('now'), datetime('now'))`,
    ).bind(rid).run();

    const result = await cleanupStaleCommands(env);

    expect(result.expiredJobs).toBe(1);
    // Job should now be exhausted
    const job = await env.DB.prepare("SELECT status FROM flaky_retry_jobs WHERE id = ?").bind(jobId).first<{ status: string }>();
    expect(job!.status).toBe("exhausted");
    // Pending command should be deleted
    const cmds = await env.DB.prepare("SELECT count(*) as c FROM runner_commands WHERE status = 'pending'").first<{ c: number }>();
    expect(cmds!.c).toBe(0);
  });

  it("deletes terminal jobs older than 24h", async () => {
    const rid = await getRunnerId();
    await insertJob(rid, 600, "exhausted", "-25 hours");
    await insertJob(rid, 601, "cancelled", "-25 hours");
    await insertJob(rid, 602, "completed", "-25 hours");

    const result = await cleanupStaleCommands(env);

    expect(result.deletedJobs).toBe(3);
    const remaining = await env.DB.prepare("SELECT count(*) as c FROM flaky_retry_jobs").first<{ c: number }>();
    expect(remaining!.c).toBe(0);
  });

  it("preserves recent active jobs", async () => {
    const rid = await getRunnerId();
    await insertJob(rid, 700, "active", "-1 day");

    const result = await cleanupStaleCommands(env);

    expect(result.expiredJobs).toBe(0);
    expect(result.deletedJobs).toBe(0);
    const job = await env.DB.prepare("SELECT status FROM flaky_retry_jobs WHERE pr_number = 700").first<{ status: string }>();
    expect(job!.status).toBe("active");
  });
});
