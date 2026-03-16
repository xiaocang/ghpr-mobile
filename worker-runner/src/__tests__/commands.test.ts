import { describe, it, expect, beforeEach, afterEach } from "vitest";
import { fetchMock } from "cloudflare:test";
import { executeRetryCi } from "../commands/retry-ci";
import { executeRetryFlaky } from "../commands/retry-flaky";

beforeEach(() => {
  fetchMock.activate();
  fetchMock.disableNetConnect();
});

afterEach(() => {
  fetchMock.deactivate();
});

const GITHUB_TOKEN = "ghp-test";

describe("executeRetryCi", () => {
  it("reruns failed workflows for a PR", async () => {
    fetchMock
      .get("https://api.github.com")
      .intercept({ path: "/repos/owner/repo/pulls/42", method: "GET" })
      .reply(200, { head: { sha: "abc123" } });

    fetchMock
      .get("https://api.github.com")
      .intercept({ path: "/repos/owner/repo/actions/runs?status=failure&head_sha=abc123", method: "GET" })
      .reply(200, {
        workflow_runs: [
          { id: 1, name: "Build", html_url: "https://github.com/runs/1" },
          { id: 2, name: "Test", html_url: "https://github.com/runs/2" },
        ],
      });

    fetchMock
      .get("https://api.github.com")
      .intercept({ path: "/repos/owner/repo/actions/runs/1/rerun-failed-jobs", method: "POST" })
      .reply(201, {});

    fetchMock
      .get("https://api.github.com")
      .intercept({ path: "/repos/owner/repo/actions/runs/2/rerun-failed-jobs", method: "POST" })
      .reply(201, {});

    const result = await executeRetryCi(
      { repoFullName: "owner/repo", prNumber: 42 },
      GITHUB_TOKEN
    );

    expect(result.status).toBe("completed");
    const r = result.result as { rerunCount: number; runs: unknown[] };
    expect(r.rerunCount).toBe(2);
    expect(r.runs).toHaveLength(2);
  });

  it("handles no failed runs", async () => {
    fetchMock
      .get("https://api.github.com")
      .intercept({ path: "/repos/owner/repo/actions/runs?status=failure", method: "GET" })
      .reply(200, { workflow_runs: [] });

    const result = await executeRetryCi(
      { repoFullName: "owner/repo" },
      GITHUB_TOKEN
    );

    expect(result.status).toBe("completed");
    const r = result.result as { rerunCount: number };
    expect(r.rerunCount).toBe(0);
  });

  it("returns failed on GitHub API error", async () => {
    fetchMock
      .get("https://api.github.com")
      .intercept({ path: "/repos/owner/repo/pulls/99", method: "GET" })
      .reply(404, {});

    const result = await executeRetryCi(
      { repoFullName: "owner/repo", prNumber: 99 },
      GITHUB_TOKEN
    );

    expect(result.status).toBe("failed");
  });

  it("returns failed on missing repoFullName", async () => {
    const result = await executeRetryCi({}, GITHUB_TOKEN);
    expect(result.status).toBe("failed");
  });
});

describe("executeRetryFlaky", () => {
  it("retries failed workflows respecting attempt counts", async () => {
    fetchMock
      .get("https://api.github.com")
      .intercept({ path: "/repos/owner/repo/pulls/10", method: "GET" })
      .reply(200, { head: { sha: "sha1" } });

    fetchMock
      .get("https://api.github.com")
      .intercept({ path: "/repos/owner/repo/actions/runs?status=failure&head_sha=sha1", method: "GET" })
      .reply(200, {
        workflow_runs: [
          { id: 1, name: "Build" },
          { id: 2, name: "Test" },
        ],
      });

    fetchMock
      .get("https://api.github.com")
      .intercept({ path: "/repos/owner/repo/actions/runs/1/rerun-failed-jobs", method: "POST" })
      .reply(201, {});

    fetchMock
      .get("https://api.github.com")
      .intercept({ path: "/repos/owner/repo/actions/runs/2/rerun-failed-jobs", method: "POST" })
      .reply(201, {});

    const result = await executeRetryFlaky(
      { repoFullName: "owner/repo", prNumber: 10, workflowAttempts: { Build: 1 } },
      GITHUB_TOKEN
    );

    expect(result.status).toBe("completed");
    const r = result.result as { retriedCount: number; workflows: Array<{ name: string; attempts: number; skipped?: boolean }> };
    expect(r.retriedCount).toBe(2);
    expect(r.workflows).toHaveLength(2);
    expect(r.workflows[0]).toEqual({ name: "Build", attempts: 2 });
    expect(r.workflows[1]).toEqual({ name: "Test", attempts: 1 });
  });

  it("skips workflows at MAX_PER_WORKFLOW", async () => {
    fetchMock
      .get("https://api.github.com")
      .intercept({ path: "/repos/owner/repo/pulls/10", method: "GET" })
      .reply(200, { head: { sha: "sha2" } });

    fetchMock
      .get("https://api.github.com")
      .intercept({ path: "/repos/owner/repo/actions/runs?status=failure&head_sha=sha2", method: "GET" })
      .reply(200, {
        workflow_runs: [{ id: 1, name: "Build" }],
      });

    const result = await executeRetryFlaky(
      { repoFullName: "owner/repo", prNumber: 10, workflowAttempts: { Build: 3 } },
      GITHUB_TOKEN
    );

    expect(result.status).toBe("completed");
    const r = result.result as { retriedCount: number; workflows: Array<{ name: string; skipped?: boolean }> };
    expect(r.retriedCount).toBe(0);
    expect(r.workflows[0].skipped).toBe(true);
  });

  it("handles 409 (already rerunning) without counting attempt", async () => {
    fetchMock
      .get("https://api.github.com")
      .intercept({ path: "/repos/owner/repo/pulls/10", method: "GET" })
      .reply(200, { head: { sha: "sha3" } });

    fetchMock
      .get("https://api.github.com")
      .intercept({ path: "/repos/owner/repo/actions/runs?status=failure&head_sha=sha3", method: "GET" })
      .reply(200, {
        workflow_runs: [{ id: 1, name: "Build" }],
      });

    fetchMock
      .get("https://api.github.com")
      .intercept({ path: "/repos/owner/repo/actions/runs/1/rerun-failed-jobs", method: "POST" })
      .reply(409, {});

    const result = await executeRetryFlaky(
      { repoFullName: "owner/repo", prNumber: 10, workflowAttempts: { Build: 1 } },
      GITHUB_TOKEN
    );

    expect(result.status).toBe("completed");
    const r = result.result as { retriedCount: number; workflows: Array<{ name: string; attempts: number }> };
    expect(r.retriedCount).toBe(0);
    expect(r.workflows[0].attempts).toBe(1); // unchanged
  });

  it("returns failed on missing payload fields", async () => {
    const result = await executeRetryFlaky({ repoFullName: "owner/repo" }, GITHUB_TOKEN);
    expect(result.status).toBe("failed");
  });
});
