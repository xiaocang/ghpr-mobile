import type { Env } from "./index";
import { normalizeAction } from "./normalize";
import { sendFcmPush } from "./fcm";
import { decryptToken } from "./crypto";

type GitHubNotification = {
  id: string;
  reason: string;
  subject: {
    title: string;
    url: string;
    type: string;
  };
  repository: {
    full_name: string;
  };
  updated_at: string;
};

type UserTokenRow = {
  user_id: string;
  encrypted_token: string;
  github_login: string;
  last_poll_at: string | null;
  last_poll_status: string | null;
};

type DeviceTokenRow = {
  token: string;
};

export function reasonToAction(reason: string): string {
  return normalizeAction(reason);
}

function buildPrUrl(repo: string, prNumber: number): string {
  return `https://github.com/${repo}/pull/${prNumber}`;
}

async function updatePollStatus(
  db: D1Database,
  userId: string,
  status: string,
  error: string | null,
  lastPollAt?: string
): Promise<void> {
  const nowIso = new Date().toISOString();
  await db.prepare(
    `UPDATE user_github_tokens
     SET last_poll_status = ?,
         last_poll_error = ?,
         last_poll_at = COALESCE(?, last_poll_at),
         last_poll_success_at = CASE WHEN ? = 'ok' OR ? = 'fcm_error' THEN ? ELSE last_poll_success_at END,
         updated_at = datetime('now')
     WHERE user_id = ?`
  )
    .bind(status, error, lastPollAt ?? null, status, status, nowIso, userId)
    .run();
}

export async function resolveDeviceTokensForUser(
  db: D1Database,
  userId: string
): Promise<string[]> {
  const result = await db
    .prepare("SELECT token FROM device_tokens WHERE user_id = ?")
    .bind(userId)
    .all<DeviceTokenRow>();
  return (result.results ?? []).map((row) => row.token);
}

async function pollNotificationsForUser(
  token: string,
  since: string | null
): Promise<GitHubNotification[]> {
  const params = new URLSearchParams({ participating: "true" });
  if (since) {
    params.set("since", since);
  }

  const res = await fetch(
    `https://api.github.com/notifications?${params.toString()}`,
    {
      headers: {
        authorization: `Bearer ${token}`,
        accept: "application/vnd.github+json",
        "user-agent": "ghpr-server/1.0",
        "x-github-api-version": "2022-11-28",
      },
    }
  );

  if (res.status === 401) {
    throw new Error("TOKEN_REVOKED");
  }

  if (!res.ok) {
    throw new Error(`GitHub API error: ${res.status}`);
  }

  const notifications = (await res.json()) as GitHubNotification[];
  return notifications.filter((n) => n.subject.type === "PullRequest");
}

export async function pollAllUsers(env: Env): Promise<void> {
  const startTime = Date.now();
  const users = await env.DB.prepare(
    "SELECT user_id, encrypted_token, github_login, last_poll_at, last_poll_status FROM user_github_tokens WHERE last_poll_status IS NULL OR last_poll_status != 'token_revoked'"
  ).all<UserTokenRow>();

  for (const user of users.results ?? []) {
    // Exit early if approaching 25s limit
    if (Date.now() - startTime > 25_000) {
      console.log("Approaching time limit, exiting early");
      break;
    }

    let plainToken: string;
    try {
      plainToken = await decryptToken(
        user.encrypted_token,
        env.GITHUB_TOKEN_ENCRYPTION_KEY
      );
    } catch (err) {
      console.error(
        `Failed to decrypt token for user ${user.user_id}:`,
        err
      );
      await updatePollStatus(env.DB, user.user_id, "decrypt_error", "decrypt_failed");
      continue;
    }

    let notifications: GitHubNotification[];
    try {
      notifications = await pollNotificationsForUser(
        plainToken,
        user.last_poll_at
      );
    } catch (err) {
      if (err instanceof Error && err.message === "TOKEN_REVOKED") {
        console.warn(
          `Token revoked for user ${user.user_id}`
        );
        await updatePollStatus(env.DB, user.user_id, "token_revoked", "TOKEN_REVOKED");
      } else {
        console.error(
          `Failed to poll for user ${user.user_id}:`,
          err
        );
        await updatePollStatus(
          env.DB,
          user.user_id,
          "github_error",
          err instanceof Error ? err.message : "poll_failed"
        );
      }
      continue;
    }

    // Get user's subscriptions
    const subs = await env.DB.prepare(
      "SELECT repo_full_name FROM repo_subscriptions WHERE user_id = ?"
    )
      .bind(user.user_id)
      .all<{ repo_full_name: string }>();
    const subscribedRepos = new Set(
      (subs.results ?? []).map((r) => r.repo_full_name.toLowerCase())
    );

    // Filter to subscribed repos and save changes
    let hadFcmFailure = false;
    let firstFcmFailure: string | null = null;
    for (const notif of notifications) {
      const repoName = notif.repository.full_name.toLowerCase();
      if (!subscribedRepos.has(repoName)) continue;

      const prMatch = notif.subject.url.match(/\/pulls\/(\d+)$/) ??
        notif.subject.url.match(/\/(\d+)$/);
      if (!prMatch) continue;

      const prNumber = parseInt(prMatch[1], 10);
      const action = reasonToAction(notif.reason);
      const deliveryId = `notif-${notif.id}`;
      const changedAtMs = Date.now();

      await env.DB.prepare(
        `INSERT INTO pr_changes (delivery_id, repo_full_name, pr_number, action, changed_at_ms)
         VALUES (?, ?, ?, ?, ?)
         ON CONFLICT(delivery_id) DO NOTHING`
      )
        .bind(deliveryId, repoName, prNumber, action, changedAtMs)
        .run();

      // Send FCM push to all user devices
      const deviceTokens = await resolveDeviceTokensForUser(
        env.DB,
        user.user_id
      );
      for (const deviceToken of deviceTokens) {
        const payload = {
          type: "pr_update" as const,
          repo: repoName,
          prNumber: String(prNumber),
          action,
          deliveryId,
          sentAt: String(changedAtMs),
          prTitle: notif.subject.title,
          prUrl: buildPrUrl(repoName, prNumber),
        };

        try {
          const fcmResult = await sendFcmPush(env, payload, deviceToken);
          if (!fcmResult.success) {
            hadFcmFailure = true;
            firstFcmFailure = firstFcmFailure ?? fcmResult.reason;
          }
        } catch (err) {
          console.error(`FCM push failed for ${user.user_id}:`, err);
          hadFcmFailure = true;
          firstFcmFailure = firstFcmFailure ?? "send_failed";
        }
      }
    }

    // Update last_poll_at
    const pollAt = new Date().toISOString();
    if (hadFcmFailure) {
      await updatePollStatus(env.DB, user.user_id, "fcm_error", firstFcmFailure, pollAt);
    } else {
      await updatePollStatus(env.DB, user.user_id, "ok", null, pollAt);
    }
  }
}
