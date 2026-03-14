import type { Env } from "./index";
import { normalizeAction } from "./normalize";
import {
  sendFcmPush,
  type PushDataPayload,
} from "./fcm";

type RunnerRegisterBody = {
  deviceId?: string;
  pairingToken?: string;
  label?: string;
  githubLogin?: string;
};

export type RunnerRow = {
  id: number;
  device_id: string;
  pairing_token_hash: string;
  label: string | null;
  user_id: string;
  github_login: string | null;
  last_poll_status: string | null;
  last_poll_error: string | null;
  last_poll_at: string | null;
  created_at: string;
  last_seen_at: string | null;
};

type RunnerAuthResult = {
  runner: RunnerRow;
};

type CommandResultBody = {
  status?: string;
  result?: unknown;
};

type SubmitCommandBody = {
  repoFullName?: string;
  prNumber?: number;
};

type SyncNotification = {
  repo?: string;
  prNumber?: number;
  action?: string;
  prTitle?: string;
  prUrl?: string;
  author?: string;
  reviewers?: string[];
  mentionedUser?: string;
};

type SyncBody = {
  notifications?: SyncNotification[];
};

type PollStatusBody = {
  status?: string;
  error?: string | null;
};

const encoder = new TextEncoder();

function jsonResponse(payload: unknown, status = 200): Response {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}

async function parseJsonBody<T>(request: Request): Promise<T | null> {
  try {
    return (await request.json()) as T;
  } catch {
    return null;
  }
}

export async function hashPairingToken(token: string): Promise<string> {
  const data = encoder.encode(token);
  const hashBuffer = await crypto.subtle.digest("SHA-256", data);
  return [...new Uint8Array(hashBuffer)]
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

export async function requireRunnerAuth(
  request: Request,
  env: Env
): Promise<RunnerAuthResult | Response> {
  const token = request.headers.get("x-runner-token")?.trim() ?? "";
  if (!token) {
    return jsonResponse({ error: "unauthorized" }, 401);
  }

  const hash = await hashPairingToken(token);
  const runner = await env.DB.prepare(
    "SELECT * FROM runners WHERE pairing_token_hash = ? LIMIT 1"
  )
    .bind(hash)
    .first<RunnerRow>();

  if (!runner) {
    return jsonResponse({ error: "unauthorized" }, 401);
  }

  await env.DB.prepare(
    "UPDATE runners SET last_seen_at = datetime('now') WHERE id = ?"
  )
    .bind(runner.id)
    .run();

  return { runner };
}

export async function handleRunnerRegister(
  request: Request,
  env: Env,
  userId: string
): Promise<Response> {
  const body = await parseJsonBody<RunnerRegisterBody>(request);
  if (!body) {
    return jsonResponse({ error: "invalid json" }, 400);
  }

  const deviceId = body.deviceId?.trim() ?? "";
  const pairingToken = body.pairingToken?.trim() ?? "";
  const label = body.label?.trim() || null;
  const githubLogin = body.githubLogin?.trim().toLowerCase() ?? "";

  if (!deviceId) {
    return jsonResponse({ error: "deviceId is required" }, 400);
  }
  if (!pairingToken) {
    return jsonResponse({ error: "pairingToken is required" }, 400);
  }
  if (!githubLogin) {
    return jsonResponse({ error: "githubLogin is required" }, 400);
  }

  const tokenHash = await hashPairingToken(pairingToken);

  await env.DB.prepare(
    `INSERT INTO runners (device_id, pairing_token_hash, label, user_id, github_login, created_at)
     VALUES (?, ?, ?, ?, ?, datetime('now'))
     ON CONFLICT(device_id) DO UPDATE SET
       pairing_token_hash = excluded.pairing_token_hash,
       label = excluded.label,
       user_id = excluded.user_id,
       github_login = excluded.github_login`
  )
    .bind(deviceId, tokenHash, label, userId, githubLogin)
    .run();

  return jsonResponse({ ok: true });
}

export async function handleRunnerPollInfo(
  env: Env,
  userId: string
): Promise<Response> {
  const runner = await env.DB.prepare(
    "SELECT * FROM runners WHERE user_id = ? LIMIT 1"
  )
    .bind(userId)
    .first<RunnerRow>();

  if (!runner) {
    return jsonResponse({
      ok: true,
      deviceId: null,
      githubLogin: null,
      lastPollStatus: null,
      lastPollError: null,
      lastPollAt: null,
      lastSeenAt: null,
    });
  }

  return jsonResponse({
    ok: true,
    deviceId: runner.device_id,
    githubLogin: runner.github_login,
    lastPollStatus: runner.last_poll_status,
    lastPollError: runner.last_poll_error,
    lastPollAt: runner.last_poll_at,
    lastSeenAt: runner.last_seen_at,
  });
}

export async function handleRunnerStatus(
  _request: Request,
  _env: Env,
  runner: RunnerRow
): Promise<Response> {
  return jsonResponse({
    ok: true,
    deviceId: runner.device_id,
    label: runner.label,
    userId: runner.user_id,
    githubLogin: runner.github_login,
    lastPollStatus: runner.last_poll_status,
    lastPollError: runner.last_poll_error,
    lastPollAt: runner.last_poll_at,
    createdAt: runner.created_at,
    lastSeenAt: runner.last_seen_at,
  });
}

export async function handleRunnerUnregister(
  _request: Request,
  env: Env,
  runner: RunnerRow
): Promise<Response> {
  await env.DB.batch([
    env.DB.prepare("DELETE FROM runner_commands WHERE runner_id = ?").bind(runner.id),
    env.DB.prepare("DELETE FROM runners WHERE id = ?").bind(runner.id),
  ]);

  return jsonResponse({ ok: true });
}

export async function handleRunnerSync(
  request: Request,
  env: Env,
  runner: RunnerRow
): Promise<Response> {
  const body = await parseJsonBody<SyncBody>(request);
  if (!body) {
    return jsonResponse({ error: "invalid json" }, 400);
  }

  const notifications = body.notifications ?? [];
  if (notifications.length === 0) {
    return jsonResponse({ ok: true, syncedCount: 0, pushedCount: 0 });
  }

  let syncedCount = 0;
  let pushedCount = 0;
  const now = Date.now();

  for (const notif of notifications) {
    const repo = notif.repo?.trim().toLowerCase() ?? "";
    const prNumber = notif.prNumber;
    const actionRaw = notif.action?.trim() ?? "";
    const action = normalizeAction(actionRaw);

    if (!repo || !prNumber || prNumber <= 0 || !actionRaw) {
      continue;
    }

    const deliveryId = `runner-${runner.id}-${repo}-${prNumber}-${now}`;

    // Save PR change
    await env.DB.prepare(
      `INSERT INTO pr_changes (delivery_id, repo_full_name, pr_number, action, changed_at_ms)
       VALUES (?, ?, ?, ?, ?)
       ON CONFLICT(delivery_id) DO NOTHING`
    )
      .bind(deliveryId, repo, prNumber, action, now)
      .run();

    // Save PR involvement
    const upsertSql = `INSERT INTO pr_user_involvement (repo_full_name, pr_number, github_login, role, updated_at_ms)
       VALUES (?, ?, ?, ?, ?)
       ON CONFLICT(repo_full_name, pr_number, github_login, role)
       DO UPDATE SET updated_at_ms = excluded.updated_at_ms`;

    const stmts: D1PreparedStatement[] = [];

    const author = notif.author?.trim().toLowerCase();
    if (author) {
      stmts.push(env.DB.prepare(upsertSql).bind(repo, prNumber, author, "author", now));
    }

    for (const reviewer of notif.reviewers ?? []) {
      const login = reviewer.trim().toLowerCase();
      if (login) {
        stmts.push(env.DB.prepare(upsertSql).bind(repo, prNumber, login, "reviewer", now));
      }
    }

    const mentioned = notif.mentionedUser?.trim().toLowerCase();
    if (mentioned) {
      stmts.push(env.DB.prepare(upsertSql).bind(repo, prNumber, mentioned, "mentioned", now));
    }

    if (stmts.length > 0) {
      await env.DB.batch(stmts);
    }

    syncedCount++;

    // Fan out FCM push
    const tokens = await resolveDeviceTokensForPr(env.DB, repo, prNumber);
    const payload: PushDataPayload = {
      type: "pr_update",
      repo,
      prNumber: String(prNumber),
      action,
      deliveryId,
      sentAt: String(now),
      prTitle: notif.prTitle?.trim() || undefined,
      prUrl: notif.prUrl?.trim() || undefined,
    };

    for (const deviceToken of tokens) {
      try {
        const result = await sendFcmPush(env, payload, deviceToken);
        if (result.success) {
          pushedCount++;
        }
        if (!result.success && result.deleteToken) {
          await env.DB.prepare("DELETE FROM device_tokens WHERE token = ?")
            .bind(deviceToken)
            .run();
        }
      } catch (err) {
        console.error(`FCM send failed for token ${deviceToken.slice(0, 6)}...`, err);
      }
    }
  }

  return jsonResponse({ ok: true, syncedCount, pushedCount });
}

async function resolveDeviceTokensForPr(
  db: D1Database,
  repoFullName: string,
  prNumber: number
): Promise<string[]> {
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
    .all<{ token: string }>();

  return (result.results ?? []).map((row) => row.token);
}

export async function handleRunnerPollStatus(
  request: Request,
  env: Env,
  runner: RunnerRow
): Promise<Response> {
  const body = await parseJsonBody<PollStatusBody>(request);
  if (!body) {
    return jsonResponse({ error: "invalid json" }, 400);
  }

  const status = body.status?.trim() ?? "";
  if (!status) {
    return jsonResponse({ error: "status is required" }, 400);
  }

  await env.DB.prepare(
    `UPDATE runners
     SET last_poll_status = ?, last_poll_error = ?, last_poll_at = datetime('now')
     WHERE id = ?`
  )
    .bind(status, body.error?.trim() ?? null, runner.id)
    .run();

  return jsonResponse({ ok: true });
}

export async function handleListRunnerSubscriptions(
  _request: Request,
  env: Env,
  runner: RunnerRow
): Promise<Response> {
  const result = await env.DB.prepare(
    `SELECT repo_full_name
     FROM repo_subscriptions
     WHERE user_id = ?
     ORDER BY repo_full_name ASC`
  )
    .bind(runner.user_id)
    .all<{ repo_full_name: string }>();

  return jsonResponse({
    ok: true,
    subscriptions: (result.results ?? []).map((row) => row.repo_full_name),
  });
}

export async function handlePollCommands(
  _request: Request,
  env: Env,
  runner: RunnerRow
): Promise<Response> {
  const result = await env.DB.prepare(
    `SELECT id, command_type, payload, created_at
     FROM runner_commands
     WHERE runner_id = ? AND status = 'pending'
     ORDER BY created_at ASC
     LIMIT 10`
  )
    .bind(runner.id)
    .all<{ id: number; command_type: string; payload: string; created_at: string }>();

  const commands = result.results ?? [];

  if (commands.length > 0) {
    const ids = commands.map((c) => c.id);
    const placeholders = ids.map(() => "?").join(",");
    await env.DB.prepare(
      `UPDATE runner_commands SET status = 'running', updated_at = datetime('now')
       WHERE id IN (${placeholders})`
    )
      .bind(...ids)
      .run();
  }

  return jsonResponse({
    ok: true,
    commands: commands.map((c) => ({
      id: c.id,
      commandType: c.command_type,
      payload: JSON.parse(c.payload),
      createdAt: c.created_at,
    })),
  });
}

export async function handleCommandResult(
  request: Request,
  env: Env,
  runner: RunnerRow,
  commandId: number
): Promise<Response> {
  const body = await parseJsonBody<CommandResultBody>(request);
  if (!body) {
    return jsonResponse({ error: "invalid json" }, 400);
  }

  const status = body.status?.trim() ?? "";
  if (status !== "completed" && status !== "failed") {
    return jsonResponse({ error: "status must be 'completed' or 'failed'" }, 400);
  }

  const command = await env.DB.prepare(
    "SELECT id FROM runner_commands WHERE id = ? AND runner_id = ? LIMIT 1"
  )
    .bind(commandId, runner.id)
    .first<{ id: number }>();

  if (!command) {
    return jsonResponse({ error: "command not found" }, 404);
  }

  const resultJson = body.result != null ? JSON.stringify(body.result) : null;

  await env.DB.prepare(
    `UPDATE runner_commands
     SET status = ?, result = ?, updated_at = datetime('now')
     WHERE id = ?`
  )
    .bind(status, resultJson, commandId)
    .run();

  return jsonResponse({ ok: true });
}

export async function handleSubmitCommand(
  request: Request,
  env: Env,
  userId: string
): Promise<Response> {
  const body = await parseJsonBody<SubmitCommandBody>(request);
  if (!body) {
    return jsonResponse({ error: "invalid json" }, 400);
  }

  const repoFullName = body.repoFullName?.trim().toLowerCase() ?? "";
  if (!repoFullName) {
    return jsonResponse({ error: "repoFullName is required" }, 400);
  }

  const runner = await env.DB.prepare(
    "SELECT id FROM runners WHERE user_id = ? LIMIT 1"
  )
    .bind(userId)
    .first<{ id: number }>();

  if (!runner) {
    return jsonResponse({ error: "no runner registered for this user" }, 400);
  }

  const payload: Record<string, unknown> = { repoFullName };
  if (body.prNumber != null && body.prNumber > 0) {
    payload.prNumber = body.prNumber;
  }

  const result = await env.DB.prepare(
    `INSERT INTO runner_commands (runner_id, user_id, command_type, payload, status, created_at, updated_at)
     VALUES (?, ?, 'retry-ci', ?, 'pending', datetime('now'), datetime('now'))`
  )
    .bind(runner.id, userId, JSON.stringify(payload))
    .run();

  return jsonResponse({ ok: true, commandId: result.meta.last_row_id });
}
