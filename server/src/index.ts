import { normalizeAction } from "./normalize";
import {
  sendFcmPush,
  resetAccessTokenCache,
  cachedAccessToken,
  type PushDataPayload,
  type FcmSendResult,
} from "./fcm";
import {
  requireRunnerAuth,
  handleRunnerRegister,
  handleRunnerStatus,
  handleRunnerUnregister,
  handleRunnerSync,
  handleRunnerPollStatus,
  handleRunnerPollInfo,
  handleListRunnerSubscriptions,
  handlePollCommands,
  handleCommandResult,
  handleSubmitCommand,
  handleSubmitRetryFlaky,
  handleListRetryFlakyJobs,
  handleCancelRetryFlaky,
} from "./runner";

export type { PushDataPayload, FcmSendResult };
export { sendFcmPush, resetAccessTokenCache, cachedAccessToken };

export interface Env {
  GITHUB_WEBHOOK_SECRET: string;
  FCM_PROJECT_ID: string;
  FCM_CLIENT_EMAIL: string;
  FCM_PRIVATE_KEY: string;
  INTERNAL_API_KEY?: string;
  DB: D1Database;
}

type GitHubEvent = {
  action?: string;
  repository?: { full_name?: string };
  pull_request?: {
    number?: number;
    html_url?: string;
    title?: string;
    merged?: boolean;
    user?: { login?: string };
    requested_reviewers?: Array<{ login?: string }>;
  };
  requested_reviewer?: { login?: string };
};

type DeviceRegisterBody = {
  userId?: string;
  token?: string;
  platform?: string;
};

type SubscriptionBody = {
  userId?: string;
  repoFullName?: string;
};

type DeviceTokenRow = {
  token: string;
};

type SubscriptionRow = {
  repo_full_name: string;
};

type UserDeviceRow = {
  token: string;
  platform: string;
};

type UserDeviceView = {
  platform: string;
  tokenPreview: string;
};

type PRChangeRow = {
  delivery_id: string;
  repo_full_name: string;
  pr_number: number;
  action: string;
  changed_at_ms: number;
};

const encoder = new TextEncoder();

export async function sha256HmacHex(secret: string, body: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    "raw",
    encoder.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"]
  );
  const signature = await crypto.subtle.sign("HMAC", key, encoder.encode(body));
  const hex = [...new Uint8Array(signature)]
    .map((value) => value.toString(16).padStart(2, "0"))
    .join("");
  return `sha256=${hex}`;
}

function jsonResponse(payload: unknown, status = 200): Response {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" }
  });
}

async function parseJsonBody<T>(request: Request): Promise<T | null> {
  try {
    return (await request.json()) as T;
  } catch {
    return null;
  }
}

function normalizeRepoFullName(input: string): string {
  return input.trim().toLowerCase();
}

function normalizeWebhookAction(action: string, merged: boolean): string {
  if (merged && action.trim().toLowerCase() === "closed") return "merged";
  return normalizeAction(action);
}

type AuthResult = { userId: string };

type JwkKey = {
  kid: string;
  kty: string;
  alg: string;
  n: string;
  e: string;
  use: string;
};

let cachedJwks: { keys: JwkKey[]; expiresAt: number } | null = null;

export function resetJwksCache(): void {
  cachedJwks = null;
}

async function fetchGoogleJwks(forceRefresh = false): Promise<JwkKey[]> {
  const now = Date.now();
  if (!forceRefresh && cachedJwks && cachedJwks.expiresAt > now) {
    return cachedJwks.keys;
  }

  const res = await fetch(
    "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com"
  );
  if (!res.ok) {
    throw new Error(`Failed to fetch Google JWKs: ${res.status}`);
  }

  const data = (await res.json()) as { keys: JwkKey[] };
  const cacheControl = res.headers.get("cache-control") ?? "";
  const maxAgeMatch = cacheControl.match(/max-age=(\d+)/);
  const maxAge = maxAgeMatch ? parseInt(maxAgeMatch[1], 10) : 3600;

  cachedJwks = { keys: data.keys, expiresAt: now + maxAge * 1000 };
  return data.keys;
}

function decodeBase64Url(input: string): Uint8Array {
  const base64 = input.replace(/-/g, "+").replace(/_/g, "/");
  const padded = base64 + "=".repeat((4 - (base64.length % 4)) % 4);
  return Uint8Array.from(atob(padded), (c) => c.charCodeAt(0));
}

async function verifyFirebaseIdToken(token: string, env: Env): Promise<AuthResult> {
  const parts = token.split(".");
  if (parts.length !== 3) {
    throw new Error("Invalid token format");
  }

  const headerJson = new TextDecoder().decode(decodeBase64Url(parts[0]));
  const header = JSON.parse(headerJson) as { alg: string; kid: string };

  if (header.alg !== "RS256") {
    throw new Error("Unsupported algorithm");
  }

  let jwks = await fetchGoogleJwks();
  let jwk = jwks.find((k) => k.kid === header.kid);
  if (!jwk) {
    // Google key rotation can invalidate cached kids; refetch once before failing.
    jwks = await fetchGoogleJwks(true);
    jwk = jwks.find((k) => k.kid === header.kid);
  }
  if (!jwk) {
    throw new Error("Unknown signing key");
  }

  const key = await crypto.subtle.importKey(
    "jwk",
    jwk,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["verify"]
  );

  const signatureData = encoder.encode(`${parts[0]}.${parts[1]}`);
  const signature = decodeBase64Url(parts[2]);

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const valid = await crypto.subtle.verify("RSASSA-PKCS1-v1_5", key, signature as any, signatureData as any);
  if (!valid) {
    throw new Error("Invalid signature");
  }

  const payloadJson = new TextDecoder().decode(decodeBase64Url(parts[1]));
  const payload = JSON.parse(payloadJson) as {
    iss: string;
    aud: string;
    sub: string;
    exp: number;
    iat: number;
  };

  const now = Math.floor(Date.now() / 1000);
  if (payload.exp < now) {
    throw new Error("Token expired");
  }

  if (payload.iat > now + 300) {
    throw new Error("Token issued in the future");
  }

  const expectedIssuer = `https://securetoken.google.com/${env.FCM_PROJECT_ID}`;
  if (payload.iss !== expectedIssuer) {
    throw new Error("Invalid issuer");
  }

  if (payload.aud !== env.FCM_PROJECT_ID) {
    throw new Error("Invalid audience");
  }

  if (!payload.sub) {
    throw new Error("Missing subject");
  }

  return { userId: payload.sub };
}

async function requireAuth(request: Request, env: Env): Promise<AuthResult | Response> {
  const apiKey = request.headers.get("x-api-key")?.trim() ?? "";
  if (apiKey && env.INTERNAL_API_KEY && apiKey === env.INTERNAL_API_KEY) {
    const url = new URL(request.url);
    const userId = url.searchParams.get("userId")?.trim() ?? "";
    if (userId) {
      return { userId };
    }
    if (request.method === "POST" || request.method === "DELETE") {
      try {
        const cloned = request.clone();
        const body = (await cloned.json()) as { userId?: string };
        if (body.userId?.trim()) {
          return { userId: body.userId.trim() };
        }
      } catch {
        // fall through
      }
    }
    return jsonResponse({ error: "userId required with api-key auth" }, 400);
  }

  const authHeader = request.headers.get("authorization") ?? "";
  if (authHeader.startsWith("Bearer ")) {
    const token = authHeader.slice(7).trim();
    if (!token) {
      return jsonResponse({ error: "empty bearer token" }, 401);
    }
    try {
      return await verifyFirebaseIdToken(token, env);
    } catch (err) {
      const message = err instanceof Error ? err.message : "token verification failed";
      console.error(`Auth failed: ${message}`);
      return jsonResponse({ error: "unauthorized" }, 401);
    }
  }

  return jsonResponse({ error: "unauthorized" }, 401);
}

function maskToken(token: string): string {
  if (token.length <= 10) {
    return `${token.slice(0, 2)}***${token.slice(-2)}`;
  }

  return `${token.slice(0, 6)}...${token.slice(-6)}`;
}

async function hasSeenDelivery(db: D1Database, deliveryId: string): Promise<boolean> {
  const row = await db
    .prepare("SELECT delivery_id FROM webhook_deliveries WHERE delivery_id = ? LIMIT 1")
    .bind(deliveryId)
    .first<{ delivery_id: string }>();
  return Boolean(row?.delivery_id);
}

async function saveDelivery(db: D1Database, deliveryId: string, eventType: string): Promise<void> {
  await db
    .prepare(
      "INSERT INTO webhook_deliveries (delivery_id, event_type, created_at) VALUES (?, ?, datetime('now'))"
    )
    .bind(deliveryId, eventType)
    .run();
}

async function savePrChange(db: D1Database, payload: PushDataPayload): Promise<void> {
  await db
    .prepare(
      `INSERT INTO pr_changes (delivery_id, repo_full_name, pr_number, action, changed_at_ms)
       VALUES (?, ?, ?, ?, ?)
       ON CONFLICT(delivery_id) DO NOTHING`
    )
    .bind(
      payload.deliveryId,
      payload.repo,
      Number(payload.prNumber),
      payload.action,
      Number(payload.sentAt)
    )
    .run();
}

async function savePrInvolvement(db: D1Database, event: GitHubEvent): Promise<void> {
  const repo = event.repository?.full_name?.trim().toLowerCase() ?? "";
  const prNumber = event.pull_request?.number;
  if (!repo || !prNumber) return;

  const now = Date.now();
  const stmts: D1PreparedStatement[] = [];
  const upsertSql = `INSERT INTO pr_user_involvement (repo_full_name, pr_number, github_login, role, updated_at_ms)
     VALUES (?, ?, ?, ?, ?)
     ON CONFLICT(repo_full_name, pr_number, github_login, role)
     DO UPDATE SET updated_at_ms = excluded.updated_at_ms`;

  // Author
  const author = event.pull_request?.user?.login?.trim().toLowerCase();
  if (author) {
    stmts.push(db.prepare(upsertSql).bind(repo, prNumber, author, "author", now));
  }

  // Requested reviewers from PR payload
  for (const r of event.pull_request?.requested_reviewers ?? []) {
    const login = r.login?.trim().toLowerCase();
    if (login) {
      stmts.push(db.prepare(upsertSql).bind(repo, prNumber, login, "reviewer", now));
    }
  }

  // Specific requested_reviewer from review_requested action
  const reqReviewer = event.requested_reviewer?.login?.trim().toLowerCase();
  if (reqReviewer) {
    stmts.push(db.prepare(upsertSql).bind(repo, prNumber, reqReviewer, "reviewer", now));
  }

  if (stmts.length > 0) {
    await db.batch(stmts);
  }
}

export function buildPushPayload(event: GitHubEvent, deliveryId: string): PushDataPayload | null {
  const repo = event.repository?.full_name?.trim() ?? "";
  const prNumber = event.pull_request?.number;
  const actionRaw = event.action?.trim() ?? "";
  const action = normalizeWebhookAction(actionRaw, Boolean(event.pull_request?.merged));

  if (!repo || !actionRaw || !prNumber || prNumber <= 0) {
    return null;
  }

  return {
    type: "pr_update",
    repo: normalizeRepoFullName(repo),
    prNumber: String(prNumber),
    action,
    deliveryId,
    sentAt: String(Date.now()),
    prTitle: event.pull_request?.title?.trim() || undefined,
    prUrl: event.pull_request?.html_url?.trim() || undefined,
  };
}

async function resolveDeviceTokensForPr(db: D1Database, repoFullName: string, prNumber: number): Promise<string[]> {
  const result = await db
    .prepare(
      `SELECT DISTINCT dt.token AS token
       FROM repo_subscriptions rs
       JOIN device_tokens dt ON dt.user_id = rs.user_id
       JOIN runners r ON r.user_id = rs.user_id
       JOIN pr_user_involvement pui
         ON pui.repo_full_name = rs.repo_full_name
         AND pui.pr_number = ?
         AND LOWER(pui.github_login) = LOWER(r.github_login)
       WHERE rs.repo_full_name = ?`
    )
    .bind(prNumber, repoFullName)
    .all<DeviceTokenRow>();

  return (result.results ?? []).map((row) => row.token);
}

async function fanOutPush(env: Env, payload: PushDataPayload): Promise<number> {
  const tokens = await resolveDeviceTokensForPr(env.DB, payload.repo, Number(payload.prNumber));
  for (const token of tokens) {
    try {
      const result = await sendFcmPush(env, payload, token);
      if (!result.success && result.deleteToken) {
        await env.DB
          .prepare("DELETE FROM device_tokens WHERE token = ?")
          .bind(token)
          .run();
      }
    } catch (err) {
      console.error(`FCM send failed for token ${token.slice(0, 6)}...`, err);
    }
  }
  return tokens.length;
}

async function handleRegisterDevice(request: Request, env: Env, userId: string): Promise<Response> {
  const body = await parseJsonBody<DeviceRegisterBody>(request);
  if (!body) {
    return jsonResponse({ error: "invalid json" }, 400);
  }

  const token = body.token?.trim() ?? "";
  const platform = body.platform?.trim() || "android";

  if (!token) {
    return jsonResponse({ error: "token is required" }, 400);
  }

  await env.DB
    .prepare(
      `INSERT INTO device_tokens (user_id, token, platform, created_at)
       VALUES (?, ?, ?, datetime('now'))
       ON CONFLICT(token) DO UPDATE SET
         user_id = excluded.user_id,
         platform = excluded.platform`
    )
    .bind(userId, token, platform)
    .run();

  return jsonResponse({ ok: true });
}

async function handleSubscribeRepo(request: Request, env: Env, userId: string): Promise<Response> {
  const body = await parseJsonBody<SubscriptionBody>(request);
  if (!body) {
    return jsonResponse({ error: "invalid json" }, 400);
  }

  const repoFullName = normalizeRepoFullName(body.repoFullName ?? "");
  if (!repoFullName) {
    return jsonResponse({ error: "repoFullName is required" }, 400);
  }

  await env.DB
    .prepare(
      `INSERT INTO repo_subscriptions (user_id, repo_full_name, created_at)
       VALUES (?, ?, datetime('now'))
       ON CONFLICT(user_id, repo_full_name) DO NOTHING`
    )
    .bind(userId, repoFullName)
    .run();

  return jsonResponse({ ok: true });
}

async function handleListSubscriptions(env: Env, userId: string): Promise<Response> {
  const result = await env.DB
    .prepare(
      `SELECT repo_full_name
       FROM repo_subscriptions
       WHERE user_id = ?
       ORDER BY repo_full_name ASC`
    )
    .bind(userId)
    .all<SubscriptionRow>();

  return jsonResponse({
    ok: true,
    userId,
    subscriptions: (result.results ?? []).map((row) => row.repo_full_name)
  });
}

async function handleListDevices(env: Env, userId: string): Promise<Response> {
  const result = await env.DB
    .prepare(
      `SELECT token, platform
       FROM device_tokens
       WHERE user_id = ?
       ORDER BY created_at DESC`
    )
    .bind(userId)
    .all<UserDeviceRow>();

  return jsonResponse({
    ok: true,
    userId,
    devices: (result.results ?? []).map<UserDeviceView>((row) => ({
      platform: row.platform,
      tokenPreview: maskToken(row.token)
    }))
  });
}

async function handleUnregisterDevice(request: Request, env: Env, userId: string): Promise<Response> {
  const body = await parseJsonBody<DeviceRegisterBody>(request);
  if (!body) {
    return jsonResponse({ error: "invalid json" }, 400);
  }

  const token = body.token?.trim() ?? "";
  if (!token) {
    return jsonResponse({ error: "token is required" }, 400);
  }

  await env.DB
    .prepare("DELETE FROM device_tokens WHERE user_id = ? AND token = ?")
    .bind(userId, token)
    .run();

  return jsonResponse({ ok: true });
}

async function handleUnsubscribeRepo(request: Request, env: Env, userId: string): Promise<Response> {
  const body = await parseJsonBody<SubscriptionBody>(request);
  if (!body) {
    return jsonResponse({ error: "invalid json" }, 400);
  }

  const repoFullName = normalizeRepoFullName(body.repoFullName ?? "");
  if (!repoFullName) {
    return jsonResponse({ error: "repoFullName is required" }, 400);
  }

  await env.DB
    .prepare("DELETE FROM repo_subscriptions WHERE user_id = ? AND repo_full_name = ?")
    .bind(userId, repoFullName)
    .run();

  return jsonResponse({ ok: true });
}

async function handleWebhook(request: Request, env: Env): Promise<Response> {
  const signatureHeader = request.headers.get("x-hub-signature-256");
  const deliveryId = request.headers.get("x-github-delivery");
  const eventType = request.headers.get("x-github-event") ?? "unknown";

  if (!signatureHeader || !deliveryId) {
    return jsonResponse({ error: "missing webhook headers" }, 400);
  }

  const rawBody = await request.text();
  const expected = await sha256HmacHex(env.GITHUB_WEBHOOK_SECRET, rawBody);
  if (signatureHeader !== expected) {
    return jsonResponse({ error: "invalid signature" }, 401);
  }

  if (await hasSeenDelivery(env.DB, deliveryId)) {
    return jsonResponse({ ok: true, deduplicated: true });
  }

  await saveDelivery(env.DB, deliveryId, eventType);

  let parsed: GitHubEvent;
  try {
    parsed = JSON.parse(rawBody) as GitHubEvent;
  } catch {
    return jsonResponse({ error: "invalid json" }, 400);
  }

  const isPREvent = eventType === "pull_request" || Boolean(parsed.pull_request?.number);
  if (!isPREvent) {
    return jsonResponse({ ok: true, fanOutCount: 0, skipped: "not pr event" });
  }

  const payload = buildPushPayload(parsed, deliveryId);
  if (!payload) {
    return jsonResponse({ ok: true, fanOutCount: 0, skipped: "incomplete pr payload" });
  }

  await savePrChange(env.DB, payload);
  await savePrInvolvement(env.DB, parsed);

  const fanOutCount = await fanOutPush(env, payload);
  return jsonResponse({ ok: true, fanOutCount });
}

function parsePositiveInt(value: string | null, defaultValue: number): number {
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed <= 0) {
    return defaultValue;
  }
  return Math.floor(parsed);
}

async function handleMobileSync(url: URL, env: Env, userId: string): Promise<Response> {
  const since = parsePositiveInt(url.searchParams.get("since"), 0);
  const cursorDeliveryId = url.searchParams.get("cursorDeliveryId")?.trim() ?? "";
  const limit = Math.min(parsePositiveInt(url.searchParams.get("limit"), 100), 200);
  const queryLimit = limit + 1;

  const result = await env.DB
    .prepare(
      `WITH filtered AS (
         SELECT pc.delivery_id, pc.repo_full_name, pc.pr_number, pc.action, pc.changed_at_ms
         FROM pr_changes pc
         JOIN repo_subscriptions rs ON rs.repo_full_name = pc.repo_full_name
         LEFT JOIN runners r ON r.user_id = rs.user_id
         LEFT JOIN pr_user_involvement pui
           ON pui.repo_full_name = pc.repo_full_name
           AND pui.pr_number = pc.pr_number
           AND LOWER(pui.github_login) = LOWER(r.github_login)
         WHERE rs.user_id = ?
           AND (r.id IS NULL OR pui.github_login IS NOT NULL)
           AND (pc.changed_at_ms > ? OR (pc.changed_at_ms = ? AND pc.delivery_id > ?))
       ), ranked AS (
         SELECT
           delivery_id,
           repo_full_name,
           pr_number,
           action,
           changed_at_ms,
           ROW_NUMBER() OVER (
             PARTITION BY repo_full_name, pr_number
             ORDER BY changed_at_ms DESC, delivery_id DESC
           ) AS row_num
         FROM filtered
       )
       SELECT delivery_id, repo_full_name, pr_number, action, changed_at_ms
       FROM ranked
       WHERE row_num = 1
       ORDER BY changed_at_ms DESC, delivery_id DESC
       LIMIT ?`
    )
    .bind(userId, since, since, cursorDeliveryId, queryLimit)
    .all<PRChangeRow>();

  const rows = result.results ?? [];
  const hasMore = rows.length > limit;
  const pagedRows = hasMore ? rows.slice(0, limit) : rows;

  const changedPullRequests = pagedRows.map((row) => ({
    repo: row.repo_full_name,
    number: row.pr_number,
    action: normalizeAction(row.action),
    changedAtMs: row.changed_at_ms
  }));

  const nextSince = changedPullRequests.reduce(
    (acc, item) => Math.max(acc, item.changedAtMs),
    since
  );

  const lastRow = pagedRows[pagedRows.length - 1];
  const nextCursorDeliveryId = lastRow?.delivery_id ?? cursorDeliveryId;

  return jsonResponse({
    ok: true,
    userId,
    nextSince,
    nextCursorDeliveryId,
    hasMore,
    changedPullRequests
  });
}

export default {
  async fetch(request, env): Promise<Response> {
    const url = new URL(request.url);

    if (request.method === "GET" && url.pathname === "/healthz") {
      return jsonResponse({ ok: true, service: "ghpr-server" });
    }

    if (request.method === "POST" && url.pathname === "/github/webhook") {
      return handleWebhook(request, env);
    }

    const protectedRoutes: Record<string, Record<string, true>> = {
      "/devices/register": { POST: true, DELETE: true },
      "/devices": { GET: true },
      "/subscriptions": { POST: true, GET: true, DELETE: true },
      "/mobile/sync": { GET: true },
      "/runners/register": { POST: true },
      "/runners/poll-info": { GET: true },
      "/commands/retry-ci": { POST: true },
      "/commands/retry-flaky": { POST: true, GET: true, DELETE: true },
    };

    const routeMethods = protectedRoutes[url.pathname];
    if (routeMethods && routeMethods[request.method]) {
      const authResult = await requireAuth(request, env);
      if (authResult instanceof Response) return authResult;
      const { userId } = authResult;

      if (url.pathname === "/devices/register" && request.method === "POST") {
        return handleRegisterDevice(request, env, userId);
      }
      if (url.pathname === "/devices/register" && request.method === "DELETE") {
        return handleUnregisterDevice(request, env, userId);
      }
      if (url.pathname === "/devices" && request.method === "GET") {
        return handleListDevices(env, userId);
      }
      if (url.pathname === "/subscriptions" && request.method === "POST") {
        return handleSubscribeRepo(request, env, userId);
      }
      if (url.pathname === "/subscriptions" && request.method === "GET") {
        return handleListSubscriptions(env, userId);
      }
      if (url.pathname === "/subscriptions" && request.method === "DELETE") {
        return handleUnsubscribeRepo(request, env, userId);
      }
      if (url.pathname === "/mobile/sync" && request.method === "GET") {
        return handleMobileSync(url, env, userId);
      }
      if (url.pathname === "/runners/register" && request.method === "POST") {
        return handleRunnerRegister(request, env, userId);
      }
      if (url.pathname === "/runners/poll-info" && request.method === "GET") {
        return handleRunnerPollInfo(env, userId);
      }
      if (url.pathname === "/commands/retry-ci" && request.method === "POST") {
        return handleSubmitCommand(request, env, userId);
      }
      if (url.pathname === "/commands/retry-flaky" && request.method === "POST") {
        return handleSubmitRetryFlaky(request, env, userId);
      }
      if (url.pathname === "/commands/retry-flaky" && request.method === "GET") {
        return handleListRetryFlakyJobs(env, userId);
      }
      if (url.pathname === "/commands/retry-flaky" && request.method === "DELETE") {
        return handleCancelRetryFlaky(request, env, userId);
      }
    }

    // Runner-authenticated routes (x-runner-token header)
    const runnerRoutes: Record<string, Record<string, true>> = {
      "/runners/status": { GET: true },
      "/runners/register": { DELETE: true },
      "/runners/commands/poll": { GET: true },
      "/runners/sync": { POST: true },
      "/runners/poll-status": { POST: true },
      "/runners/subscriptions": { GET: true },
    };

    // Match /runners/commands/:id/result
    const commandResultMatch = url.pathname.match(/^\/runners\/commands\/(\d+)\/result$/);
    if (commandResultMatch && request.method === "POST") {
      const runnerAuth = await requireRunnerAuth(request, env);
      if (runnerAuth instanceof Response) return runnerAuth;
      const commandId = parseInt(commandResultMatch[1], 10);
      return handleCommandResult(request, env, runnerAuth.runner, commandId);
    }

    const runnerRouteMethods = runnerRoutes[url.pathname];
    if (runnerRouteMethods && runnerRouteMethods[request.method]) {
      const runnerAuth = await requireRunnerAuth(request, env);
      if (runnerAuth instanceof Response) return runnerAuth;
      const { runner } = runnerAuth;

      if (url.pathname === "/runners/status" && request.method === "GET") {
        return handleRunnerStatus(request, env, runner);
      }
      if (url.pathname === "/runners/register" && request.method === "DELETE") {
        return handleRunnerUnregister(request, env, runner);
      }
      if (url.pathname === "/runners/commands/poll" && request.method === "GET") {
        return handlePollCommands(request, env, runner);
      }
      if (url.pathname === "/runners/sync" && request.method === "POST") {
        return handleRunnerSync(request, env, runner);
      }
      if (url.pathname === "/runners/poll-status" && request.method === "POST") {
        return handleRunnerPollStatus(request, env, runner);
      }
      if (url.pathname === "/runners/subscriptions" && request.method === "GET") {
        return handleListRunnerSubscriptions(request, env, runner);
      }
    }

    return jsonResponse({ error: "not found" }, 404);
  },
  async scheduled(_event, _env, _ctx) {
    // Polling moved to runner client
  },
} satisfies ExportedHandler<Env>;
