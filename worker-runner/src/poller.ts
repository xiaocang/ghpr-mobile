import type { Env } from "./index";
import {
  fetchSubscriptions,
  syncToServer,
  reportPollStatus,
} from "./api";
import type { SyncNotification } from "./api";
import { headers as githubHeaders, GITHUB_API } from "./github";

type GitHubNotification = {
  id: string;
  reason: string;
  subject: {
    title: string;
    url: string | null;
    type: string;
  };
  repository: {
    full_name: string;
  };
};

type PullRequestDetail = {
  number: number;
  html_url: string;
  user?: { login: string };
  requested_reviewers?: Array<{ login: string }>;
};

export async function pollAndSync(env: Env): Promise<void> {
  // 1. Get subscriptions + stored notifLastModified from server
  const { subscriptions, notifLastModified } = await fetchSubscriptions(env);

  // 2. Fetch GitHub notifications with conditional polling
  const headers = githubHeaders(env.GITHUB_TOKEN);
  if (notifLastModified) {
    headers["If-Modified-Since"] = notifLastModified;
  }

  const res = await fetch(
    `${GITHUB_API}/notifications?participating=true&all=false`,
    { headers }
  );

  if (res.status === 304) {
    await reportPollStatus(env, "ok");
    return;
  }

  if (!res.ok) {
    throw new Error(`GitHub notifications error: ${res.status}`);
  }

  const newLastModified = res.headers.get("Last-Modified");
  const notifications = (await res.json()) as GitHubNotification[];

  // 3. Filter to PullRequest notifications for subscribed repos
  const subSet = new Set(subscriptions.map((s) => s.toLowerCase()));
  const prNotifs = notifications.filter(
    (n) =>
      n.subject.type === "PullRequest" &&
      subSet.has(n.repository.full_name.toLowerCase())
  );

  // 4. Fetch PR details in parallel
  const detailPromises = prNotifs
    .filter((n) => n.subject.url)
    .map(async (notif): Promise<SyncNotification | null> => {
      const prRes = await fetch(notif.subject.url!, {
        headers: githubHeaders(env.GITHUB_TOKEN),
      });
      if (!prRes.ok) return null;

      const pr = (await prRes.json()) as PullRequestDetail;
      const reviewers = (pr.requested_reviewers ?? [])
        .map((r) => r.login)
        .filter(Boolean);

      return {
        repo: notif.repository.full_name.toLowerCase(),
        prNumber: pr.number,
        action: notif.reason,
        prTitle: notif.subject.title,
        prUrl: pr.html_url,
        author: pr.user?.login,
        ...(reviewers.length > 0 ? { reviewers } : {}),
        notificationId: notif.id,
      };
    });

  const syncNotifs = (await Promise.all(detailPromises)).filter(
    (n): n is SyncNotification => n !== null
  );

  // 5. Sync to server (always send if we have a new lastModified to persist)
  if (syncNotifs.length > 0 || newLastModified) {
    await syncToServer(env, syncNotifs, newLastModified);
  }

  await reportPollStatus(env, "ok");
}
