import { env, SELF } from "cloudflare:test";
import { sha256HmacHex } from "../index";

export { env, SELF };

export async function initSchema(): Promise<void> {
  await env.DB.exec(
    `CREATE TABLE IF NOT EXISTS webhook_deliveries (
       delivery_id TEXT PRIMARY KEY,
       event_type TEXT NOT NULL,
       created_at TEXT NOT NULL
     );
     CREATE TABLE IF NOT EXISTS device_tokens (
       id INTEGER PRIMARY KEY AUTOINCREMENT,
       user_id TEXT NOT NULL,
       token TEXT NOT NULL UNIQUE,
       platform TEXT NOT NULL DEFAULT 'android',
       created_at TEXT NOT NULL DEFAULT (datetime('now'))
     );
     CREATE TABLE IF NOT EXISTS repo_subscriptions (
       id INTEGER PRIMARY KEY AUTOINCREMENT,
       user_id TEXT NOT NULL,
       repo_full_name TEXT NOT NULL,
       created_at TEXT NOT NULL DEFAULT (datetime('now')),
       UNIQUE(user_id, repo_full_name)
     );
     CREATE TABLE IF NOT EXISTS pr_changes (
       delivery_id TEXT PRIMARY KEY,
       repo_full_name TEXT NOT NULL,
       pr_number INTEGER NOT NULL,
       action TEXT NOT NULL,
       changed_at_ms INTEGER NOT NULL
     );
     CREATE INDEX IF NOT EXISTS idx_device_tokens_user_id ON device_tokens (user_id);
     CREATE INDEX IF NOT EXISTS idx_repo_subscriptions_repo ON repo_subscriptions (repo_full_name);
     CREATE INDEX IF NOT EXISTS idx_pr_changes_repo_changed_at ON pr_changes (repo_full_name, changed_at_ms);`
  );
}

export async function resetDb(): Promise<void> {
  await env.DB.exec(
    `DELETE FROM pr_changes;
     DELETE FROM repo_subscriptions;
     DELETE FROM device_tokens;
     DELETE FROM webhook_deliveries;`
  );
}

export function jsonRequest(
  method: string,
  path: string,
  body?: unknown,
  headers?: Record<string, string>
): Request {
  const init: RequestInit = {
    method,
    headers: {
      "content-type": "application/json",
      ...headers,
    },
  };
  if (body !== undefined) {
    init.body = JSON.stringify(body);
  }
  return new Request(`http://localhost${path}`, init);
}

export async function webhookRequest(
  event: unknown,
  options: {
    deliveryId?: string;
    eventType?: string;
    secret?: string;
    signature?: string;
  } = {}
): Promise<Request> {
  const {
    deliveryId = "delivery-001",
    eventType = "pull_request",
    secret = "test-webhook-secret",
  } = options;

  const rawBody = JSON.stringify(event);
  const sig = options.signature ?? (await sha256HmacHex(secret, rawBody));

  return new Request("http://localhost/github/webhook", {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "x-hub-signature-256": sig,
      "x-github-delivery": deliveryId,
      "x-github-event": eventType,
    },
    body: rawBody,
  });
}

export function apiHeaders(apiKey = "test-api-key"): Record<string, string> {
  return { "x-api-key": apiKey };
}

export function makePrEvent(
  repo = "owner/repo",
  prNumber = 42,
  action = "opened"
): object {
  return {
    action,
    repository: { full_name: repo },
    pull_request: {
      number: prNumber,
      html_url: `https://github.com/${repo}/pull/${prNumber}`,
      title: `PR #${prNumber}`,
    },
  };
}
