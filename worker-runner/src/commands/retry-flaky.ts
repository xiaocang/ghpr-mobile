import { getPrHeadSha, listFailedRuns, rerunFailedJobs } from "../github";

const MAX_PER_WORKFLOW = 3;

type Payload = {
  repoFullName: string;
  prNumber: number;
  workflowAttempts?: Record<string, number>;
};

type WorkflowResult = {
  name: string;
  attempts: number;
  skipped?: boolean;
  error?: string;
};

export async function executeRetryFlaky(
  payload: Record<string, unknown>,
  githubToken: string
): Promise<{ status: string; result: unknown }> {
  const p = payload as unknown as Payload;
  if (!p.repoFullName || !p.prNumber) {
    return { status: "failed", result: { error: "invalid payload: missing repoFullName or prNumber" } };
  }

  const workflowAttempts = p.workflowAttempts ?? {};

  try {
    const headSha = await getPrHeadSha(githubToken, p.repoFullName, p.prNumber);
    const runs = await listFailedRuns(githubToken, p.repoFullName, headSha);

    let retriedCount = 0;
    const workflows: WorkflowResult[] = [];

    for (const run of runs) {
      const name = run.name ?? `run-${run.id}`;
      const prevAttempts = workflowAttempts[name] ?? 0;

      if (prevAttempts >= MAX_PER_WORKFLOW) {
        workflows.push({ name, attempts: prevAttempts, skipped: true });
        continue;
      }

      const { ok, status } = await rerunFailedJobs(githubToken, p.repoFullName, run.id);

      if (ok) {
        retriedCount++;
        workflows.push({ name, attempts: prevAttempts + 1 });
      } else if (status === 409) {
        // Already rerunning — don't count as a new attempt
        workflows.push({ name, attempts: prevAttempts });
      } else {
        workflows.push({ name, attempts: prevAttempts, error: `HTTP ${status}` });
      }
    }

    return {
      status: "completed",
      result: { retriedCount, workflows },
    };
  } catch (e) {
    return {
      status: "failed",
      result: { error: e instanceof Error ? e.message : String(e) },
    };
  }
}
