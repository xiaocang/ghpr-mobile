import type { Env } from "./index";
import { normalizeAction } from "./normalize";
import {
  sendFcmPush,
  type PushDataPayload,
} from "./fcm";
import { resolveDeviceTokensForPr } from "./shared";

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
  notif_last_modified: string | null;
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
  notificationId?: string;
};

type SyncBody = {
  notifications?: SyncNotification[];
  notifLastModified?: string;
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

async function upsertRunner(
  env: Env,
  userId: string,
  deviceId: string,
  pairingToken: string,
  label: string | null,
  githubLogin: string
): Promise<Response> {
  const tokenHash = await hashPairingToken(pairingToken);

  // Prevent runner hijacking: reject re-registration by a different user
  const existing = await env.DB.prepare(
    "SELECT user_id FROM runners WHERE device_id = ? LIMIT 1"
  )
    .bind(deviceId)
    .first<{ user_id: string }>();

  if (existing && existing.user_id !== userId) {
    return jsonResponse({ error: "device already registered to another user" }, 403);
  }

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

  return upsertRunner(env, userId, deviceId, pairingToken, label, githubLogin);
}

type SelfRegisterBody = {
  deviceId?: string;
  pairingToken?: string;
  label?: string;
};

export async function handleRunnerSelfRegister(
  request: Request,
  env: Env
): Promise<Response> {
  const body = await parseJsonBody<SelfRegisterBody>(request);
  if (!body) {
    return jsonResponse({ error: "invalid json" }, 400);
  }

  const pairingToken = body.pairingToken?.trim() ?? "";
  if (!pairingToken) {
    return jsonResponse({ error: "pairingToken is required" }, 400);
  }

  // Look up runner by pairing token hash (app must register it first)
  const tokenHash = await hashPairingToken(pairingToken);
  const runner = await env.DB.prepare(
    "SELECT * FROM runners WHERE pairing_token_hash = ? LIMIT 1"
  )
    .bind(tokenHash)
    .first<RunnerRow>();

  if (!runner) {
    return jsonResponse({ error: "register runner from the app first" }, 401);
  }

  // Update device_id, label, and github_login if provided
  const deviceId = body.deviceId?.trim() || runner.device_id;
  const label = body.label?.trim() || runner.label;

  const githubToken = request.headers.get("x-github-token")?.trim() ?? "";
  let githubLogin = runner.github_login ?? "";

  if (githubToken) {
    const ghRes = await fetch("https://api.github.com/user", {
      headers: {
        Authorization: `Bearer ${githubToken}`,
        "User-Agent": "ghpr-server",
      },
    });
    if (ghRes.ok) {
      const ghUser = (await ghRes.json()) as { login: string };
      githubLogin = ghUser.login.toLowerCase();
    }
  }

  // Update runner fields
  await env.DB.prepare(
    `UPDATE runners SET device_id = ?, label = ?, github_login = ?, last_seen_at = datetime('now')
     WHERE id = ?`
  )
    .bind(deviceId, label, githubLogin, runner.id)
    .run();

  return jsonResponse({ ok: true });
}

export async function handleRunnerPollInfo(
  env: Env,
  userId: string
): Promise<Response> {
  const runner = await env.DB.prepare(
    "SELECT * FROM runners WHERE user_id = ? ORDER BY last_seen_at DESC LIMIT 1"
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
    env.DB.prepare("DELETE FROM flaky_retry_jobs WHERE runner_id = ?").bind(runner.id),
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

  // Persist notifLastModified if provided (used for If-Modified-Since on next poll)
  if (body.notifLastModified) {
    await env.DB.prepare(
      "UPDATE runners SET notif_last_modified = ? WHERE id = ?"
    )
      .bind(body.notifLastModified, runner.id)
      .run();
  }

  const notifications = body.notifications ?? [];
  if (notifications.length === 0) {
    return jsonResponse({ ok: true, syncedCount: 0, pushedCount: 0 });
  }

  let syncedCount = 0;
  let pushedCount = 0;
  const now = Date.now();

  for (let i = 0; i < notifications.length; i++) {
    const notif = notifications[i];
    const repo = notif.repo?.trim().toLowerCase() ?? "";
    const prNumber = notif.prNumber;
    const actionRaw = notif.action?.trim() ?? "";
    const action = normalizeAction(actionRaw);

    if (!repo || !prNumber || prNumber <= 0 || !actionRaw) {
      continue;
    }

    const notifId = notif.notificationId?.trim();
    const deliveryId = notifId
      ? `runner-${runner.id}-${notifId}`
      : `runner-${runner.id}-${repo}-${prNumber}-${now}-${i}`;

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
    notifLastModified: runner.notif_last_modified,
  });
}

export async function handlePollCommands(
  _request: Request,
  env: Env,
  runner: RunnerRow
): Promise<Response> {
  const result = await env.DB.prepare(
    `UPDATE runner_commands
     SET status = 'running', updated_at = datetime('now')
     WHERE id IN (
       SELECT id FROM runner_commands
       WHERE runner_id = ? AND status = 'pending'
         AND (scheduled_after IS NULL OR scheduled_after <= datetime('now'))
       ORDER BY created_at ASC
       LIMIT 10
     )
     RETURNING id, command_type, payload, created_at`
  )
    .bind(runner.id)
    .all<{ id: number; command_type: string; payload: string; created_at: string }>();

  const commands = result.results ?? [];

  return jsonResponse({
    ok: true,
    commands: commands.reduce<unknown[]>((acc, c) => {
      try {
        acc.push({
          id: c.id,
          commandType: c.command_type,
          payload: JSON.parse(c.payload),
          createdAt: c.created_at,
        });
      } catch {
        console.warn(`Skipping command ${c.id}: malformed payload`);
      }
      return acc;
    }, []),
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
    "SELECT id, command_type, payload FROM runner_commands WHERE id = ? AND runner_id = ? LIMIT 1"
  )
    .bind(commandId, runner.id)
    .first<{ id: number; command_type: string; payload: string }>();

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

  // Post-processing for retry-flaky commands
  if (command.command_type === "retry-flaky" && status === "completed" && body.result != null) {
    await postProcessRetryFlaky(env, runner, command.payload, body.result);
  }

  return jsonResponse({ ok: true });
}

async function postProcessRetryFlaky(
  env: Env,
  runner: RunnerRow,
  commandPayloadStr: string,
  result: unknown
): Promise<void> {
  const commandPayload = JSON.parse(commandPayloadStr) as {
    repoFullName?: string;
    prNumber?: number;
    workflowAttempts?: Record<string, number>;
  };
  const repoFullName = commandPayload.repoFullName ?? "";
  const prNumber = commandPayload.prNumber ?? 0;
  if (!repoFullName || !prNumber) return;

  const res = result as {
    retriedCount?: number;
    workflows?: { name: string; attempts: number }[];
  };

  const retriedCount = res.retriedCount ?? 0;

  // Merge workflow attempts from result into stored map
  const prevAttempts = commandPayload.workflowAttempts ?? {};
  const updatedAttempts: Record<string, number> = { ...prevAttempts };
  for (const wf of res.workflows ?? []) {
    updatedAttempts[wf.name] = wf.attempts;
  }

  const job = await env.DB.prepare(
    "SELECT id, retries_remaining FROM flaky_retry_jobs WHERE repo_full_name = ? AND pr_number = ? AND status = 'active' LIMIT 1"
  )
    .bind(repoFullName, prNumber)
    .first<{ id: number; retries_remaining: number }>();

  if (!job) return;

  const newRemaining = retriedCount > 0
    ? Math.max(0, job.retries_remaining - 1)
    : job.retries_remaining;

  if (retriedCount === 0) {
    // No failures found — mark completed
    await env.DB.prepare(
      `UPDATE flaky_retry_jobs
       SET workflow_attempts = ?, retries_remaining = ?, status = 'completed', updated_at = datetime('now')
       WHERE id = ?`
    )
      .bind(JSON.stringify(updatedAttempts), newRemaining, job.id)
      .run();
  } else if (newRemaining <= 0) {
    // Budget exhausted
    await env.DB.prepare(
      `UPDATE flaky_retry_jobs
       SET workflow_attempts = ?, retries_remaining = 0, status = 'exhausted', updated_at = datetime('now')
       WHERE id = ?`
    )
      .bind(JSON.stringify(updatedAttempts), job.id)
      .run();
  } else {
    // Schedule next round with 5 min delay
    await env.DB.prepare(
      `UPDATE flaky_retry_jobs
       SET workflow_attempts = ?, retries_remaining = ?, updated_at = datetime('now')
       WHERE id = ?`
    )
      .bind(JSON.stringify(updatedAttempts), newRemaining, job.id)
      .run();

    const payload = JSON.stringify({
      repoFullName,
      prNumber,
      workflowAttempts: updatedAttempts,
    });

    await env.DB.prepare(
      `INSERT INTO runner_commands (runner_id, user_id, command_type, payload, status, scheduled_after, created_at, updated_at)
       VALUES (?, ?, 'retry-flaky', ?, 'pending', datetime('now', '+5 minutes'), datetime('now'), datetime('now'))`
    )
      .bind(runner.id, runner.user_id, payload)
      .run();
  }
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
    "SELECT id FROM runners WHERE user_id = ? ORDER BY last_seen_at DESC LIMIT 1"
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

export async function handleSubmitRetryFlaky(
  request: Request,
  env: Env,
  userId: string
): Promise<Response> {
  const body = await parseJsonBody<SubmitCommandBody>(request);
  if (!body) {
    return jsonResponse({ error: "invalid json" }, 400);
  }

  const repoFullName = body.repoFullName?.trim().toLowerCase() ?? "";
  const prNumber = body.prNumber;
  if (!repoFullName) {
    return jsonResponse({ error: "repoFullName is required" }, 400);
  }
  if (!prNumber || prNumber <= 0) {
    return jsonResponse({ error: "prNumber is required" }, 400);
  }

  const runner = await env.DB.prepare(
    "SELECT id FROM runners WHERE user_id = ? ORDER BY last_seen_at DESC LIMIT 1"
  )
    .bind(userId)
    .first<{ id: number }>();

  if (!runner) {
    return jsonResponse({ error: "no runner registered for this user" }, 400);
  }

  // Upsert flaky_retry_jobs — reset budget to 3 on resubmit
  await env.DB.prepare(
    `INSERT INTO flaky_retry_jobs (repo_full_name, pr_number, user_id, runner_id, retries_remaining, workflow_attempts, status, created_at, updated_at)
     VALUES (?, ?, ?, ?, 3, '{}', 'active', datetime('now'), datetime('now'))
     ON CONFLICT(repo_full_name, pr_number) DO UPDATE SET
       retries_remaining = 3,
       workflow_attempts = '{}',
       status = 'active',
       user_id = excluded.user_id,
       runner_id = excluded.runner_id,
       updated_at = datetime('now')`
  )
    .bind(repoFullName, prNumber, userId, runner.id)
    .run();

  const job = await env.DB.prepare(
    "SELECT id, workflow_attempts FROM flaky_retry_jobs WHERE repo_full_name = ? AND pr_number = ? LIMIT 1"
  )
    .bind(repoFullName, prNumber)
    .first<{ id: number; workflow_attempts: string }>();

  // Check for existing pending/running retry-flaky command for this runner+PR
  const existing = await env.DB.prepare(
    `SELECT id FROM runner_commands
     WHERE runner_id = ? AND command_type = 'retry-flaky' AND status IN ('pending', 'running')
       AND json_extract(payload, '$.repoFullName') = ?
       AND json_extract(payload, '$.prNumber') = ?
     LIMIT 1`
  )
    .bind(runner.id, repoFullName, prNumber)
    .first<{ id: number }>();

  if (existing) {
    return jsonResponse({ ok: true, commandId: existing.id, jobId: job?.id });
  }

  const workflowAttempts = job ? JSON.parse(job.workflow_attempts) : {};
  const payload = JSON.stringify({ repoFullName, prNumber, workflowAttempts });

  const result = await env.DB.prepare(
    `INSERT INTO runner_commands (runner_id, user_id, command_type, payload, status, created_at, updated_at)
     VALUES (?, ?, 'retry-flaky', ?, 'pending', datetime('now'), datetime('now'))`
  )
    .bind(runner.id, userId, payload)
    .run();

  return jsonResponse({ ok: true, commandId: result.meta.last_row_id, jobId: job?.id });
}

export async function handleListRetryFlakyJobs(
  env: Env,
  userId: string
): Promise<Response> {
  const result = await env.DB.prepare(
    `SELECT id, repo_full_name, pr_number, retries_remaining, workflow_attempts, status, created_at, updated_at
     FROM flaky_retry_jobs
     WHERE user_id = ?
     ORDER BY updated_at DESC
     LIMIT 50`
  )
    .bind(userId)
    .all<{
      id: number;
      repo_full_name: string;
      pr_number: number;
      retries_remaining: number;
      workflow_attempts: string;
      status: string;
      created_at: string;
      updated_at: string;
    }>();

  const jobs = (result.results ?? []).map((row) => ({
    id: row.id,
    repoFullName: row.repo_full_name,
    prNumber: row.pr_number,
    retriesRemaining: row.retries_remaining,
    workflowAttempts: JSON.parse(row.workflow_attempts),
    status: row.status,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
  }));

  return jsonResponse({ ok: true, jobs });
}

export async function handleCancelRetryFlaky(
  request: Request,
  env: Env,
  userId: string
): Promise<Response> {
  const body = await parseJsonBody<SubmitCommandBody>(request);
  if (!body) {
    return jsonResponse({ error: "invalid json" }, 400);
  }

  const repoFullName = body.repoFullName?.trim().toLowerCase() ?? "";
  const prNumber = body.prNumber;
  if (!repoFullName || !prNumber || prNumber <= 0) {
    return jsonResponse({ error: "repoFullName and prNumber are required" }, 400);
  }

  const job = await env.DB.prepare(
    "SELECT id, runner_id FROM flaky_retry_jobs WHERE repo_full_name = ? AND pr_number = ? AND user_id = ? AND status = 'active' LIMIT 1"
  )
    .bind(repoFullName, prNumber, userId)
    .first<{ id: number; runner_id: number }>();

  if (!job) {
    return jsonResponse({ error: "no active retry job found" }, 404);
  }

  await env.DB.batch([
    env.DB.prepare(
      `UPDATE flaky_retry_jobs SET status = 'cancelled', updated_at = datetime('now') WHERE id = ?`
    ).bind(job.id),
    env.DB.prepare(
      `DELETE FROM runner_commands
       WHERE runner_id = ? AND command_type = 'retry-flaky' AND status = 'pending'
         AND json_extract(payload, '$.repoFullName') = ?
         AND json_extract(payload, '$.prNumber') = ?`
    ).bind(job.runner_id, repoFullName, prNumber),
  ]);

  return jsonResponse({ ok: true });
}

export async function cleanupStaleCommands(
  env: Env
): Promise<{ deletedCommands: number; deletedJobs: number; expiredJobs: number }> {
  // 1) Delete terminal commands older than 24h
  const cmdResult = await env.DB.prepare(
    `DELETE FROM runner_commands
     WHERE status IN ('completed', 'failed')
       AND updated_at < datetime('now', '-24 hours')`
  ).run();

  // 2) Delete stale pending/running commands older than 7 days (runner likely offline)
  const staleCmdResult = await env.DB.prepare(
    `DELETE FROM runner_commands
     WHERE status IN ('pending', 'running')
       AND updated_at < datetime('now', '-7 days')`
  ).run();

  // 3) Expire active jobs stuck for 7+ days → mark exhausted + clean pending commands
  const staleJobs = await env.DB.prepare(
    `SELECT id, runner_id, repo_full_name, pr_number FROM flaky_retry_jobs
     WHERE status = 'active'
       AND updated_at < datetime('now', '-7 days')`
  ).all<{ id: number; runner_id: number; repo_full_name: string; pr_number: number }>();

  for (const job of staleJobs.results ?? []) {
    await env.DB.batch([
      env.DB.prepare(
        `UPDATE flaky_retry_jobs SET status = 'exhausted', updated_at = datetime('now') WHERE id = ?`
      ).bind(job.id),
      env.DB.prepare(
        `DELETE FROM runner_commands
         WHERE runner_id = ? AND command_type = 'retry-flaky' AND status = 'pending'
           AND json_extract(payload, '$.repoFullName') = ?
           AND json_extract(payload, '$.prNumber') = ?`
      ).bind(job.runner_id, job.repo_full_name, job.pr_number),
    ]);
  }

  // 4) Delete terminal jobs older than 24h
  const jobResult = await env.DB.prepare(
    `DELETE FROM flaky_retry_jobs
     WHERE status IN ('completed', 'exhausted', 'cancelled')
       AND updated_at < datetime('now', '-24 hours')`
  ).run();

  return {
    deletedCommands: cmdResult.meta.changes + staleCmdResult.meta.changes,
    deletedJobs: jobResult.meta.changes,
    expiredJobs: (staleJobs.results ?? []).length,
  };
}
