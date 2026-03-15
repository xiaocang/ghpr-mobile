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
