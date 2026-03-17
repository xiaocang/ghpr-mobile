import { getPrHeadSha, listFailedRuns, rerunFailedJobs } from "../github";

type Payload = {
  repoFullName: string;
  prNumber?: number;
};

export async function executeRetryCi(
  payload: Record<string, unknown>,
  githubToken: string
): Promise<{ status: string; result: unknown }> {
  const p = payload as unknown as Payload;
  if (!p.repoFullName) {
    return { status: "failed", result: { error: "invalid payload: missing repoFullName" } };
  }

  try {
    let headSha: string | undefined;
    if (p.prNumber) {
      headSha = await getPrHeadSha(githubToken, p.repoFullName, p.prNumber);
    }

    const runs = await listFailedRuns(githubToken, p.repoFullName, headSha);

    let rerunCount = 0;
    const rerunRuns: Array<{ id: number; name: string | null; url?: string }> = [];

    for (const run of runs) {
      const { ok } = await rerunFailedJobs(githubToken, p.repoFullName, run.id);
      if (ok) {
        rerunCount++;
        rerunRuns.push({ id: run.id, name: run.name, url: run.html_url });
      }
    }

    return {
      status: "completed",
      result: { rerunCount, runs: rerunRuns },
    };
  } catch (e) {
    return {
      status: "failed",
      result: { error: e instanceof Error ? e.message : String(e) },
    };
  }
}
