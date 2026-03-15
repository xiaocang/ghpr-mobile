import type { Env } from "./index";

export type Command = {
  id: number;
  commandType: string;
  payload: Record<string, unknown>;
  createdAt: string;
};

type PollResponse = {
  ok: boolean;
  commands: Command[];
};

export async function pollCommands(env: Env): Promise<Command[]> {
  const res = await fetch(`${env.WORKER_URL}/runners/commands/poll`, {
    headers: { "x-runner-token": env.RUNNER_TOKEN },
  });

  if (!res.ok) {
    throw new Error(`poll failed: HTTP ${res.status}`);
  }

  const body = (await res.json()) as PollResponse;
  return body.commands ?? [];
}

export async function reportResult(
  env: Env,
  commandId: number,
  status: string,
  result: unknown
): Promise<void> {
  const res = await fetch(
    `${env.WORKER_URL}/runners/commands/${commandId}/result`,
    {
      method: "POST",
      headers: {
        "x-runner-token": env.RUNNER_TOKEN,
        "content-type": "application/json",
      },
      body: JSON.stringify({ status, result }),
    }
  );

  if (!res.ok) {
    throw new Error(`report result failed: HTTP ${res.status}`);
  }
}

type SubscriptionsResponse = {
  ok: boolean;
  subscriptions: string[];
  notifLastModified: string | null;
};

export async function fetchSubscriptions(
  env: Env
): Promise<{ subscriptions: string[]; notifLastModified: string | null }> {
  const res = await fetch(`${env.WORKER_URL}/runners/subscriptions`, {
    headers: { "x-runner-token": env.RUNNER_TOKEN },
  });

  if (!res.ok) {
    throw new Error(`fetch subscriptions failed: HTTP ${res.status}`);
  }

  const body = (await res.json()) as SubscriptionsResponse;
  return {
    subscriptions: body.subscriptions ?? [],
    notifLastModified: body.notifLastModified ?? null,
  };
}

export type SyncNotification = {
  repo: string;
  prNumber: number;
  action: string;
  prTitle?: string;
  prUrl?: string;
  author?: string;
  reviewers?: string[];
};

export async function syncToServer(
  env: Env,
  notifications: SyncNotification[],
  notifLastModified: string | null
): Promise<void> {
  const res = await fetch(`${env.WORKER_URL}/runners/sync`, {
    method: "POST",
    headers: {
      "x-runner-token": env.RUNNER_TOKEN,
      "content-type": "application/json",
    },
    body: JSON.stringify({ notifications, notifLastModified }),
  });

  if (!res.ok) {
    throw new Error(`sync failed: HTTP ${res.status}`);
  }
}

export async function reportPollStatus(
  env: Env,
  status: string,
  error?: string
): Promise<void> {
  const res = await fetch(`${env.WORKER_URL}/runners/poll-status`, {
    method: "POST",
    headers: {
      "x-runner-token": env.RUNNER_TOKEN,
      "content-type": "application/json",
    },
    body: JSON.stringify({ status, error }),
  });

  if (!res.ok) {
    throw new Error(`report poll status failed: HTTP ${res.status}`);
  }
}
