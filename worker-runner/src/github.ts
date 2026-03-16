export const GITHUB_API = "https://api.github.com";

export function headers(token: string): Record<string, string> {
  return {
    Authorization: `Bearer ${token}`,
    Accept: "application/vnd.github+json",
    "User-Agent": "ghpr-worker-runner/1.0",
    "X-GitHub-Api-Version": "2022-11-28",
  };
}

export type WorkflowRun = {
  id: number;
  name: string | null;
  html_url?: string;
};

export async function getPrHeadSha(
  token: string,
  repo: string,
  prNumber: number
): Promise<string> {
  const res = await fetch(`${GITHUB_API}/repos/${repo}/pulls/${prNumber}`, {
    headers: headers(token),
  });

  if (!res.ok) {
    throw new Error(`GitHub PR API error: ${res.status}`);
  }

  const body = (await res.json()) as { head: { sha: string } };
  return body.head.sha;
}

export async function listFailedRuns(
  token: string,
  repo: string,
  headSha?: string
): Promise<WorkflowRun[]> {
  let url = `${GITHUB_API}/repos/${repo}/actions/runs?status=failure`;
  if (headSha) {
    url += `&head_sha=${headSha}`;
  }

  const res = await fetch(url, { headers: headers(token) });

  if (!res.ok) {
    throw new Error(`GitHub Actions API error: ${res.status}`);
  }

  const body = (await res.json()) as { workflow_runs: WorkflowRun[] };
  return body.workflow_runs;
}

export async function rerunFailedJobs(
  token: string,
  repo: string,
  runId: number
): Promise<{ ok: boolean; status: number }> {
  const res = await fetch(
    `${GITHUB_API}/repos/${repo}/actions/runs/${runId}/rerun-failed-jobs`,
    {
      method: "POST",
      headers: headers(token),
    }
  );

  return { ok: res.ok, status: res.status };
}
