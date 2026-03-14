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
  label?: string
): Promise<void> {
  const body: Record<string, string> = {
    userId: "u1",
    deviceId,
    pairingToken,
  };
  if (label) body.label = label;
  const req = jsonRequest("POST", "/runners/register", body, apiHeaders());
  const res = await SELF.fetch(req);
  expect(res.status).toBe(200);
}

describe("POST /runners/register", () => {
  it("registers a new runner", async () => {
    await registerRunner();

    const row = await env.DB
      .prepare("SELECT * FROM runners WHERE device_id = ?")
      .bind("device-001")
      .first();
    expect(row).not.toBeNull();
    expect(row!.user_id).toBe("u1");
    expect(row!.device_id).toBe("device-001");
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

    // Re-register same device with new token
    const req = jsonRequest(
      "POST",
      "/runners/register",
      { userId: "u2", deviceId: "device-dup", pairingToken: "tok-2" },
      apiHeaders()
    );
    await SELF.fetch(req);

    const row = await env.DB
      .prepare("SELECT user_id FROM runners WHERE device_id = ?")
      .bind("device-dup")
      .first<{ user_id: string }>();
    expect(row!.user_id).toBe("u2");
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
      { userId: "u1", pairingToken: "tok" },
      apiHeaders()
    );
    const res = await SELF.fetch(req);
    expect(res.status).toBe(400);
  });

  it("rejects missing pairingToken", async () => {
    const req = jsonRequest(
      "POST",
      "/runners/register",
      { userId: "u1", deviceId: "dev" },
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
    }>();
    expect(body.ok).toBe(true);
    expect(body.deviceId).toBe("device-status");
    expect(body.userId).toBe("u1");
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
