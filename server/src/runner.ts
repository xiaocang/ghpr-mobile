import type { Env } from "./index";

type RunnerRegisterBody = {
  deviceId?: string;
  pairingToken?: string;
  label?: string;
};

type RunnerRow = {
  id: number;
  device_id: string;
  pairing_token_hash: string;
  label: string | null;
  user_id: string;
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

type RunnerCommandRow = {
  id: number;
  runner_id: number;
  user_id: string;
  command_type: string;
  payload: string;
  status: string;
  result: string | null;
  created_at: string;
  updated_at: string;
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

  if (!deviceId) {
    return jsonResponse({ error: "deviceId is required" }, 400);
  }
  if (!pairingToken) {
    return jsonResponse({ error: "pairingToken is required" }, 400);
  }

  const tokenHash = await hashPairingToken(pairingToken);

  await env.DB.prepare(
    `INSERT INTO runners (device_id, pairing_token_hash, label, user_id, created_at)
     VALUES (?, ?, ?, ?, datetime('now'))
     ON CONFLICT(device_id) DO UPDATE SET
       pairing_token_hash = excluded.pairing_token_hash,
       label = excluded.label,
       user_id = excluded.user_id`
  )
    .bind(deviceId, tokenHash, label, userId)
    .run();

  return jsonResponse({ ok: true });
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
