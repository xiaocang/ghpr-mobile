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
  pull_request?: { number?: number; html_url?: string; title?: string };
};

type PushDataPayload = {
  type: "pr_update";
  repo: string;
  prNumber: string;
  action: string;
  deliveryId: string;
  sentAt: string;
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

type UserScopedQuery = {
  userId: string;
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

async function sha256HmacHex(secret: string, body: string): Promise<string> {
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

function requireInternalApiKey(request: Request, env: Env): Response | null {
  if (!env.INTERNAL_API_KEY) {
    return null;
  }

  const provided = request.headers.get("x-api-key")?.trim() ?? "";
  if (provided !== env.INTERNAL_API_KEY) {
    return jsonResponse({ error: "unauthorized" }, 401);
  }

  return null;
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

function buildPushPayload(event: GitHubEvent, deliveryId: string): PushDataPayload | null {
  const repo = event.repository?.full_name?.trim() ?? "";
  const prNumber = event.pull_request?.number;
  const action = event.action?.trim() ?? "";

  if (!repo || !action || !prNumber || prNumber <= 0) {
    return null;
  }

  return {
    type: "pr_update",
    repo: normalizeRepoFullName(repo),
    prNumber: String(prNumber),
    action,
    deliveryId,
    sentAt: String(Date.now())
  };
}

async function resolveDeviceTokensForRepo(db: D1Database, repoFullName: string): Promise<string[]> {
  const result = await db
    .prepare(
      `SELECT dt.token AS token
       FROM repo_subscriptions rs
       JOIN device_tokens dt ON dt.user_id = rs.user_id
       WHERE rs.repo_full_name = ?`
    )
    .bind(repoFullName)
    .all<DeviceTokenRow>();

  return (result.results ?? []).map((row) => row.token);
}

async function sendPushStub(_env: Env, payload: PushDataPayload, token: string): Promise<void> {
  console.log(`Push stub token=${token} payload=${JSON.stringify(payload)}`);
}

async function fanOutPush(env: Env, payload: PushDataPayload): Promise<number> {
  const tokens = await resolveDeviceTokensForRepo(env.DB, payload.repo);
  for (const token of tokens) {
    await sendPushStub(env, payload, token);
  }
  return tokens.length;
}

async function handleRegisterDevice(request: Request, env: Env): Promise<Response> {
  const body = await parseJsonBody<DeviceRegisterBody>(request);
  if (!body) {
    return jsonResponse({ error: "invalid json" }, 400);
  }

  const userId = body.userId?.trim() ?? "";
  const token = body.token?.trim() ?? "";
  const platform = body.platform?.trim() || "android";

  if (!userId || !token) {
    return jsonResponse({ error: "userId and token are required" }, 400);
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

async function handleSubscribeRepo(request: Request, env: Env): Promise<Response> {
  const body = await parseJsonBody<SubscriptionBody>(request);
  if (!body) {
    return jsonResponse({ error: "invalid json" }, 400);
  }

  const userId = body.userId?.trim() ?? "";
  const repoFullName = normalizeRepoFullName(body.repoFullName ?? "");
  if (!userId || !repoFullName) {
    return jsonResponse({ error: "userId and repoFullName are required" }, 400);
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

function getRequiredUserIdFromQuery(url: URL): string {
  return url.searchParams.get("userId")?.trim() ?? "";
}

async function handleListSubscriptions(url: URL, env: Env): Promise<Response> {
  const userId = getRequiredUserIdFromQuery(url);
  if (!userId) {
    return jsonResponse({ error: "userId is required" }, 400);
  }

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

async function handleListDevices(url: URL, env: Env): Promise<Response> {
  const userId = getRequiredUserIdFromQuery(url);
  if (!userId) {
    return jsonResponse({ error: "userId is required" }, 400);
  }

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

async function handleUnregisterDevice(request: Request, env: Env): Promise<Response> {
  const body = await parseJsonBody<DeviceRegisterBody>(request);
  if (!body) {
    return jsonResponse({ error: "invalid json" }, 400);
  }

  const userId = body.userId?.trim() ?? "";
  const token = body.token?.trim() ?? "";
  if (!userId || !token) {
    return jsonResponse({ error: "userId and token are required" }, 400);
  }

  await env.DB
    .prepare("DELETE FROM device_tokens WHERE user_id = ? AND token = ?")
    .bind(userId, token)
    .run();

  return jsonResponse({ ok: true });
}

async function handleUnsubscribeRepo(request: Request, env: Env): Promise<Response> {
  const body = await parseJsonBody<SubscriptionBody>(request);
  if (!body) {
    return jsonResponse({ error: "invalid json" }, 400);
  }

  const userId = body.userId?.trim() ?? "";
  const repoFullName = normalizeRepoFullName(body.repoFullName ?? "");
  if (!userId || !repoFullName) {
    return jsonResponse({ error: "userId and repoFullName are required" }, 400);
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

async function handleMobileSync(url: URL, env: Env): Promise<Response> {
  const userId = getRequiredUserIdFromQuery(url);
  if (!userId) {
    return jsonResponse({ error: "userId is required" }, 400);
  }

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
         WHERE rs.user_id = ?
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
       ORDER BY changed_at_ms ASC, delivery_id ASC
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
    action: row.action,
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

    if (request.method === "POST" && url.pathname === "/devices/register") {
      const authError = requireInternalApiKey(request, env);
      if (authError) return authError;
      return handleRegisterDevice(request, env);
    }

    if (request.method === "DELETE" && url.pathname === "/devices/register") {
      const authError = requireInternalApiKey(request, env);
      if (authError) return authError;
      return handleUnregisterDevice(request, env);
    }

    if (request.method === "GET" && url.pathname === "/devices") {
      const authError = requireInternalApiKey(request, env);
      if (authError) return authError;
      return handleListDevices(url, env);
    }

    if (request.method === "POST" && url.pathname === "/subscriptions") {
      const authError = requireInternalApiKey(request, env);
      if (authError) return authError;
      return handleSubscribeRepo(request, env);
    }

    if (request.method === "GET" && url.pathname === "/subscriptions") {
      const authError = requireInternalApiKey(request, env);
      if (authError) return authError;
      return handleListSubscriptions(url, env);
    }

    if (request.method === "DELETE" && url.pathname === "/subscriptions") {
      const authError = requireInternalApiKey(request, env);
      if (authError) return authError;
      return handleUnsubscribeRepo(request, env);
    }

    if (request.method === "POST" && url.pathname === "/github/webhook") {
      return handleWebhook(request, env);
    }

    if (request.method === "GET" && url.pathname === "/mobile/sync") {
      const authError = requireInternalApiKey(request, env);
      if (authError) return authError;
      return handleMobileSync(url, env);
    }

    return jsonResponse({ error: "not found" }, 404);
  }
} satisfies ExportedHandler<Env>;
