import { pollCommands, reportResult, reportPollStatus, selfRegister } from "./api";
import type { Command } from "./api";
import { executeRetryCi } from "./commands/retry-ci";
import { executeRetryFlaky } from "./commands/retry-flaky";
import { pollAndSync } from "./poller";

export interface Env {
  GITHUB_TOKEN: string;
  RUNNER_TOKEN: string;
  WORKER_URL: string;
  SERVER: Fetcher;
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

async function runPollCycle(env: Env): Promise<void> {
  const [, commands] = await Promise.all([
    pollAndSync(env).catch((e) => {
      const error = e instanceof Error ? e.message : String(e);
      reportPollStatus(env, "error", error).catch(() => {});
    }),
    pollCommands(env).catch(async (e) => {
      const msg = e instanceof Error ? e.message : String(e);
      if (msg.includes("HTTP 401")) {
        console.log("Runner not registered — attempting self-registration...");
        await selfRegister(env).catch((re) => {
          console.error("self-register failed:", re instanceof Error ? re.message : String(re));
        });
      } else {
        console.error("pollCommands failed:", msg);
      }
      return [] as Command[];
    }),
  ]);

  for (const cmd of commands) {
    try {
      const { status, result } = await dispatch(cmd, env.GITHUB_TOKEN);
      await reportResult(env, cmd.id, status, result);
    } catch (e) {
      const error = e instanceof Error ? e.message : String(e);
      await reportResult(env, cmd.id, "failed", { error }).catch(() => {});
    }
  }
}

export default {
  async fetch(_request: Request, env: Env): Promise<Response> {
    await runPollCycle(env);
    return new Response("ok");
  },

  async scheduled(
    _event: ScheduledController,
    env: Env,
    _ctx: ExecutionContext
  ): Promise<void> {
    await runPollCycle(env);
  },
} satisfies ExportedHandler<Env>;
