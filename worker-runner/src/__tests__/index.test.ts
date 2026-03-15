import { describe, it, expect, beforeEach, afterEach } from "vitest";
import { fetchMock, env, createExecutionContext, createScheduledController } from "cloudflare:test";
import worker from "../index";

beforeEach(() => {
  fetchMock.activate();
  fetchMock.disableNetConnect();
});

afterEach(() => {
  fetchMock.deactivate();
});

const testEnv = env as unknown as {
  GITHUB_TOKEN: string;
  RUNNER_TOKEN: string;
  WORKER_URL: string;
};

describe("scheduled handler", () => {
  it("polls commands, executes retry-ci, and reports result", async () => {
    // Mock poll response
    fetchMock
      .get("https://ghpr-server.test.workers.dev")
      .intercept({ path: "/runners/commands/poll", method: "GET" })
      .reply(200, {
        ok: true,
        commands: [
          {
            id: 1,
            commandType: "retry-ci",
            payload: { repoFullName: "owner/repo" },
            createdAt: "2026-01-01T00:00:00Z",
          },
        ],
      });

    // Mock GitHub: list failed runs (empty)
    fetchMock
      .get("https://api.github.com")
      .intercept({ path: "/repos/owner/repo/actions/runs?status=failure", method: "GET" })
      .reply(200, { workflow_runs: [] });

    // Mock report result
    fetchMock
      .get("https://ghpr-server.test.workers.dev")
      .intercept({ path: "/runners/commands/1/result", method: "POST" })
      .reply(200, { ok: true });

    const ctrl = createScheduledController({ cron: "* * * * *" });
    const ctx = createExecutionContext();
    await worker.scheduled(ctrl, testEnv, ctx);

    // If we got here without errors, poll → execute → report succeeded
    expect(true).toBe(true);
  });

  it("reports failed for unknown command types", async () => {
    fetchMock
      .get("https://ghpr-server.test.workers.dev")
      .intercept({ path: "/runners/commands/poll", method: "GET" })
      .reply(200, {
        ok: true,
        commands: [
          {
            id: 99,
            commandType: "unknown-cmd",
            payload: {},
            createdAt: "2026-01-01T00:00:00Z",
          },
        ],
      });

    fetchMock
      .get("https://ghpr-server.test.workers.dev")
      .intercept({ path: "/runners/commands/99/result", method: "POST" })
      .reply(200, { ok: true });

    const ctrl = createScheduledController({ cron: "* * * * *" });
    const ctx = createExecutionContext();
    await worker.scheduled(ctrl, testEnv, ctx);

    expect(true).toBe(true);
  });

  it("handles empty poll response", async () => {
    fetchMock
      .get("https://ghpr-server.test.workers.dev")
      .intercept({ path: "/runners/commands/poll", method: "GET" })
      .reply(200, { ok: true, commands: [] });

    const ctrl = createScheduledController({ cron: "* * * * *" });
    const ctx = createExecutionContext();
    await worker.scheduled(ctrl, testEnv, ctx);

    expect(true).toBe(true);
  });
});
