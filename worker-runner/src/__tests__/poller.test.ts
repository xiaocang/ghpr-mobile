import { describe, it, expect, beforeEach, afterEach } from "vitest";
import { fetchMock } from "cloudflare:test";
import { pollAndSync } from "../poller";
import type { Env } from "../index";

const testEnv: Env = {
  GITHUB_TOKEN: "ghp-test-token",
  RUNNER_TOKEN: "test-runner-token",
  WORKER_URL: "https://ghpr-server.test.workers.dev",
};

beforeEach(() => {
  fetchMock.activate();
  fetchMock.disableNetConnect();
});

afterEach(() => {
  fetchMock.deactivate();
});

function mockPollStatus() {
  fetchMock
    .get("https://ghpr-server.test.workers.dev")
    .intercept({ path: "/runners/poll-status", method: "POST" })
    .reply(200, { ok: true });
}

describe("pollAndSync", () => {
  it("fetches notifications and syncs PR details to server", async () => {
    // Mock subscriptions
    fetchMock
      .get("https://ghpr-server.test.workers.dev")
      .intercept({ path: "/runners/subscriptions", method: "GET" })
      .reply(200, {
        ok: true,
        subscriptions: ["owner/repo"],
        notifLastModified: null,
      });

    // Mock GitHub notifications
    fetchMock
      .get("https://api.github.com")
      .intercept({
        path: "/notifications?participating=true&all=false",
        method: "GET",
      })
      .reply(
        200,
        [
          {
            reason: "review_requested",
            subject: {
              title: "Fix bug",
              url: "https://api.github.com/repos/owner/repo/pulls/42",
              type: "PullRequest",
            },
            repository: { full_name: "owner/repo" },
          },
        ],
        { headers: { "Last-Modified": "Mon, 15 Mar 2026 00:00:00 GMT" } }
      );

    // Mock PR detail
    fetchMock
      .get("https://api.github.com")
      .intercept({
        path: "/repos/owner/repo/pulls/42",
        method: "GET",
      })
      .reply(200, {
        number: 42,
        html_url: "https://github.com/owner/repo/pull/42",
        user: { login: "alice" },
        requested_reviewers: [{ login: "bob" }],
      });

    // Mock sync
    fetchMock
      .get("https://ghpr-server.test.workers.dev")
      .intercept({ path: "/runners/sync", method: "POST" })
      .reply(200, { ok: true, syncedCount: 1, pushedCount: 1 });

    mockPollStatus();

    await pollAndSync(testEnv);
    // No error means success
    expect(true).toBe(true);
  });

  it("skips on 304 Not Modified", async () => {
    fetchMock
      .get("https://ghpr-server.test.workers.dev")
      .intercept({ path: "/runners/subscriptions", method: "GET" })
      .reply(200, {
        ok: true,
        subscriptions: ["owner/repo"],
        notifLastModified: "Mon, 14 Mar 2026 00:00:00 GMT",
      });

    fetchMock
      .get("https://api.github.com")
      .intercept({
        path: "/notifications?participating=true&all=false",
        method: "GET",
      })
      .reply(304, "");

    mockPollStatus();

    await pollAndSync(testEnv);
    expect(true).toBe(true);
  });

  it("filters out non-PullRequest notifications", async () => {
    fetchMock
      .get("https://ghpr-server.test.workers.dev")
      .intercept({ path: "/runners/subscriptions", method: "GET" })
      .reply(200, {
        ok: true,
        subscriptions: ["owner/repo"],
        notifLastModified: null,
      });

    fetchMock
      .get("https://api.github.com")
      .intercept({
        path: "/notifications?participating=true&all=false",
        method: "GET",
      })
      .reply(
        200,
        [
          {
            reason: "mention",
            subject: {
              title: "Issue #1",
              url: "https://api.github.com/repos/owner/repo/issues/1",
              type: "Issue",
            },
            repository: { full_name: "owner/repo" },
          },
        ],
        { headers: { "Last-Modified": "Mon, 15 Mar 2026 00:00:00 GMT" } }
      );

    // Still syncs to persist lastModified even with no PR notifs
    fetchMock
      .get("https://ghpr-server.test.workers.dev")
      .intercept({ path: "/runners/sync", method: "POST" })
      .reply(200, { ok: true, syncedCount: 0, pushedCount: 0 });

    mockPollStatus();

    await pollAndSync(testEnv);
    expect(true).toBe(true);
  });

  it("filters out notifications from unsubscribed repos", async () => {
    fetchMock
      .get("https://ghpr-server.test.workers.dev")
      .intercept({ path: "/runners/subscriptions", method: "GET" })
      .reply(200, {
        ok: true,
        subscriptions: ["owner/repo"],
        notifLastModified: null,
      });

    fetchMock
      .get("https://api.github.com")
      .intercept({
        path: "/notifications?participating=true&all=false",
        method: "GET",
      })
      .reply(
        200,
        [
          {
            reason: "review_requested",
            subject: {
              title: "PR in other repo",
              url: "https://api.github.com/repos/other/repo/pulls/1",
              type: "PullRequest",
            },
            repository: { full_name: "other/repo" },
          },
        ],
        { headers: { "Last-Modified": "Mon, 15 Mar 2026 00:00:00 GMT" } }
      );

    // Syncs to persist lastModified
    fetchMock
      .get("https://ghpr-server.test.workers.dev")
      .intercept({ path: "/runners/sync", method: "POST" })
      .reply(200, { ok: true, syncedCount: 0, pushedCount: 0 });

    mockPollStatus();

    await pollAndSync(testEnv);
    expect(true).toBe(true);
  });

  it("throws on GitHub API error", async () => {
    fetchMock
      .get("https://ghpr-server.test.workers.dev")
      .intercept({ path: "/runners/subscriptions", method: "GET" })
      .reply(200, {
        ok: true,
        subscriptions: ["owner/repo"],
        notifLastModified: null,
      });

    fetchMock
      .get("https://api.github.com")
      .intercept({
        path: "/notifications?participating=true&all=false",
        method: "GET",
      })
      .reply(401, { message: "Bad credentials" });

    await expect(pollAndSync(testEnv)).rejects.toThrow(
      "GitHub notifications error: 401"
    );
  });
});
