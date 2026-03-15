import { pollCommands, reportResult } from "./api";
import type { Command } from "./api";
import { executeRetryCi } from "./commands/retry-ci";
import { executeRetryFlaky } from "./commands/retry-flaky";

export interface Env {
  GITHUB_TOKEN: string;
  RUNNER_TOKEN: string;
  WORKER_URL: string;
}

/**
 * Allowed command types for the worker↔runner RPC protocol.
 * Must match the server-side ALLOWED_COMMAND_TYPES.
 */
const ALLOWED_COMMAND_TYPES = ["retry-ci", "retry-flaky"] as const;

async function dispatch(
  cmd: Command,
  githubToken: string
): Promise<{ status: string; result: unknown }> {
  if (!(ALLOWED_COMMAND_TYPES as readonly string[]).includes(cmd.commandType)) {
    return {
      status: "failed",
      result: { error: `rejected unknown command type: ${cmd.commandType}` },
    };
  }

  switch (cmd.commandType) {
    case "retry-ci":
      return executeRetryCi(cmd.payload, githubToken);
    case "retry-flaky":
      return executeRetryFlaky(cmd.payload, githubToken);
    default:
      return { status: "failed", result: { error: "unreachable" } };
  }
}

export default {
  async scheduled(
    _event: ScheduledController,
    env: Env,
    _ctx: ExecutionContext
  ): Promise<void> {
    const commands = await pollCommands(env);

    for (const cmd of commands) {
      try {
        const { status, result } = await dispatch(cmd, env.GITHUB_TOKEN);
        await reportResult(env, cmd.id, status, result);
      } catch (e) {
        const error = e instanceof Error ? e.message : String(e);
        await reportResult(env, cmd.id, "failed", { error }).catch(() => {});
      }
    }
  },
} satisfies ExportedHandler<Env>;
