import { env, SELF } from "cloudflare:test";
import { sha256HmacHex } from "../index";

export { env, SELF };

export async function initSchema(): Promise<void> {
  await env.DB.exec("CREATE TABLE IF NOT EXISTS webhook_deliveries (delivery_id TEXT PRIMARY KEY, event_type TEXT NOT NULL, created_at TEXT NOT NULL)");
  await env.DB.exec("CREATE TABLE IF NOT EXISTS device_tokens (id INTEGER PRIMARY KEY AUTOINCREMENT, user_id TEXT NOT NULL, token TEXT NOT NULL UNIQUE, platform TEXT NOT NULL DEFAULT 'android', created_at TEXT NOT NULL DEFAULT (datetime('now')))");
  await env.DB.exec("CREATE TABLE IF NOT EXISTS repo_subscriptions (id INTEGER PRIMARY KEY AUTOINCREMENT, user_id TEXT NOT NULL, repo_full_name TEXT NOT NULL, created_at TEXT NOT NULL DEFAULT (datetime('now')), UNIQUE(user_id, repo_full_name))");
  await env.DB.exec("CREATE TABLE IF NOT EXISTS pr_changes (delivery_id TEXT PRIMARY KEY, repo_full_name TEXT NOT NULL, pr_number INTEGER NOT NULL, action TEXT NOT NULL, changed_at_ms INTEGER NOT NULL)");
  await env.DB.exec("CREATE INDEX IF NOT EXISTS idx_device_tokens_user_id ON device_tokens (user_id)");
  await env.DB.exec("CREATE INDEX IF NOT EXISTS idx_repo_subscriptions_repo ON repo_subscriptions (repo_full_name)");
  await env.DB.exec("CREATE INDEX IF NOT EXISTS idx_pr_changes_repo_changed_at ON pr_changes (repo_full_name, changed_at_ms)");
  await env.DB.exec("CREATE TABLE IF NOT EXISTS pr_user_involvement (id INTEGER PRIMARY KEY AUTOINCREMENT, repo_full_name TEXT NOT NULL, pr_number INTEGER NOT NULL, github_login TEXT NOT NULL, role TEXT NOT NULL, updated_at_ms INTEGER NOT NULL)");
  await env.DB.exec("CREATE UNIQUE INDEX IF NOT EXISTS idx_pr_involvement_unique ON pr_user_involvement (repo_full_name, pr_number, github_login, role)");
  await env.DB.exec("CREATE INDEX IF NOT EXISTS idx_pr_involvement_login ON pr_user_involvement (github_login)");
  await env.DB.exec("CREATE TABLE IF NOT EXISTS runners (id INTEGER PRIMARY KEY AUTOINCREMENT, device_id TEXT NOT NULL UNIQUE, pairing_token_hash TEXT NOT NULL, label TEXT, user_id TEXT NOT NULL, github_login TEXT, last_poll_status TEXT, last_poll_error TEXT, last_poll_at TEXT, created_at TEXT NOT NULL DEFAULT (datetime('now')), last_seen_at TEXT)");
  await env.DB.exec("CREATE INDEX IF NOT EXISTS idx_runners_pairing_token_hash ON runners (pairing_token_hash)");
  await env.DB.exec("CREATE INDEX IF NOT EXISTS idx_runners_user_id ON runners (user_id)");
  await env.DB.exec("CREATE TABLE IF NOT EXISTS runner_commands (id INTEGER PRIMARY KEY AUTOINCREMENT, runner_id INTEGER NOT NULL REFERENCES runners(id), user_id TEXT NOT NULL, command_type TEXT NOT NULL, payload TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'pending', result TEXT, created_at TEXT NOT NULL DEFAULT (datetime('now')), updated_at TEXT NOT NULL DEFAULT (datetime('now')))");
  await env.DB.exec("CREATE INDEX IF NOT EXISTS idx_runner_commands_runner_status ON runner_commands (runner_id, status)");
}

export async function resetDb(): Promise<void> {
  await env.DB.exec(
    `DELETE FROM pr_changes;
     DELETE FROM repo_subscriptions;
     DELETE FROM device_tokens;
     DELETE FROM webhook_deliveries;
     DELETE FROM pr_user_involvement;
     DELETE FROM runner_commands;
     DELETE FROM runners;`
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
  action = "opened",
  author = "testuser"
): object {
  return {
    action,
    repository: { full_name: repo },
    pull_request: {
      number: prNumber,
      html_url: `https://github.com/${repo}/pull/${prNumber}`,
      title: `PR #${prNumber}`,
      user: { login: author },
    },
  };
}
