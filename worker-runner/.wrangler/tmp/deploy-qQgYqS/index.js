var __defProp = Object.defineProperty;
var __name = (target, value) => __defProp(target, "name", { value, configurable: true });

// src/api.ts
function serverFetch(env, path, init) {
  return env.SERVER.fetch(new Request(`https://server${path}`, init));
}
__name(serverFetch, "serverFetch");
async function selfRegister(env) {
  const res = await serverFetch(env, "/runners/self-register", {
    method: "POST",
    headers: {
      "x-github-token": env.GITHUB_TOKEN,
      "content-type": "application/json"
    },
    body: JSON.stringify({ deviceId: "worker-runner", pairingToken: env.RUNNER_TOKEN })
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`self-register failed: HTTP ${res.status}: ${text}`);
  }
}
__name(selfRegister, "selfRegister");
async function pollCommands(env) {
  const res = await serverFetch(env, "/runners/commands/poll", {
    headers: { "x-runner-token": env.RUNNER_TOKEN }
  });
  if (!res.ok) {
    const body2 = await res.text().catch(() => "");
    throw new Error(`poll failed: HTTP ${res.status} body=${body2}`);
  }
  const body = await res.json();
  return body.commands ?? [];
}
__name(pollCommands, "pollCommands");
async function reportResult(env, commandId, status, result) {
  const res = await serverFetch(env, `/runners/commands/${commandId}/result`, {
    method: "POST",
    headers: {
      "x-runner-token": env.RUNNER_TOKEN,
      "content-type": "application/json"
    },
    body: JSON.stringify({ status, result })
  });
  if (!res.ok) {
    throw new Error(`report result failed: HTTP ${res.status}`);
  }
}
__name(reportResult, "reportResult");
async function fetchSubscriptions(env) {
  const res = await serverFetch(env, "/runners/subscriptions", {
    headers: { "x-runner-token": env.RUNNER_TOKEN }
  });
  if (!res.ok) {
    throw new Error(`fetch subscriptions failed: HTTP ${res.status}`);
  }
  const body = await res.json();
  return {
    subscriptions: body.subscriptions ?? [],
    notifLastModified: body.notifLastModified ?? null
  };
}
__name(fetchSubscriptions, "fetchSubscriptions");
async function syncToServer(env, notifications, notifLastModified) {
  const res = await serverFetch(env, "/runners/sync", {
    method: "POST",
    headers: {
      "x-runner-token": env.RUNNER_TOKEN,
      "content-type": "application/json"
    },
    body: JSON.stringify({ notifications, notifLastModified })
  });
  if (!res.ok) {
    throw new Error(`sync failed: HTTP ${res.status}`);
  }
}
__name(syncToServer, "syncToServer");
async function reportPollStatus(env, status, error) {
  const res = await serverFetch(env, "/runners/poll-status", {
    method: "POST",
    headers: {
      "x-runner-token": env.RUNNER_TOKEN,
      "content-type": "application/json"
    },
    body: JSON.stringify({ status, error })
  });
  if (!res.ok) {
    throw new Error(`report poll status failed: HTTP ${res.status}`);
  }
}
__name(reportPollStatus, "reportPollStatus");

// src/github.ts
var GITHUB_API = "https://api.github.com";
function headers(token) {
  return {
    Authorization: `Bearer ${token}`,
    Accept: "application/vnd.github+json",
    "User-Agent": "ghpr-worker-runner/1.0",
    "X-GitHub-Api-Version": "2022-11-28"
  };
}
__name(headers, "headers");
async function getPrHeadSha(token, repo, prNumber) {
  const res = await fetch(`${GITHUB_API}/repos/${repo}/pulls/${prNumber}`, {
    headers: headers(token)
  });
  if (!res.ok) {
    throw new Error(`GitHub PR API error: ${res.status}`);
  }
  const body = await res.json();
  return body.head.sha;
}
__name(getPrHeadSha, "getPrHeadSha");
async function listFailedRuns(token, repo, headSha) {
  let url = `${GITHUB_API}/repos/${repo}/actions/runs?status=failure`;
  if (headSha) {
    url += `&head_sha=${headSha}`;
  }
  const res = await fetch(url, { headers: headers(token) });
  if (!res.ok) {
    throw new Error(`GitHub Actions API error: ${res.status}`);
  }
  const body = await res.json();
  return body.workflow_runs;
}
__name(listFailedRuns, "listFailedRuns");
async function rerunFailedJobs(token, repo, runId) {
  const res = await fetch(
    `${GITHUB_API}/repos/${repo}/actions/runs/${runId}/rerun-failed-jobs`,
    {
      method: "POST",
      headers: headers(token)
    }
  );
  return { ok: res.ok, status: res.status };
}
__name(rerunFailedJobs, "rerunFailedJobs");

// src/commands/retry-ci.ts
async function executeRetryCi(payload, githubToken) {
  const p = payload;
  if (!p.repoFullName) {
    return { status: "failed", result: { error: "invalid payload: missing repoFullName" } };
  }
  try {
    let headSha;
    if (p.prNumber) {
      headSha = await getPrHeadSha(githubToken, p.repoFullName, p.prNumber);
    }
    const runs = await listFailedRuns(githubToken, p.repoFullName, headSha);
    let rerunCount = 0;
    const rerunRuns = [];
    for (const run of runs) {
      const { ok } = await rerunFailedJobs(githubToken, p.repoFullName, run.id);
      if (ok) {
        rerunCount++;
        rerunRuns.push({ id: run.id, name: run.name, url: run.html_url });
      }
    }
    return {
      status: "completed",
      result: { rerunCount, runs: rerunRuns }
    };
  } catch (e) {
    return {
      status: "failed",
      result: { error: e instanceof Error ? e.message : String(e) }
    };
  }
}
__name(executeRetryCi, "executeRetryCi");

// src/commands/retry-flaky.ts
var MAX_PER_WORKFLOW = 3;
async function executeRetryFlaky(payload, githubToken) {
  const p = payload;
  if (!p.repoFullName || !p.prNumber) {
    return { status: "failed", result: { error: "invalid payload: missing repoFullName or prNumber" } };
  }
  const workflowAttempts = p.workflowAttempts ?? {};
  try {
    const headSha = await getPrHeadSha(githubToken, p.repoFullName, p.prNumber);
    const runs = await listFailedRuns(githubToken, p.repoFullName, headSha);
    let retriedCount = 0;
    const workflows = [];
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
        workflows.push({ name, attempts: prevAttempts });
      } else {
        workflows.push({ name, attempts: prevAttempts, error: `HTTP ${status}` });
      }
    }
    return {
      status: "completed",
      result: { retriedCount, workflows }
    };
  } catch (e) {
    return {
      status: "failed",
      result: { error: e instanceof Error ? e.message : String(e) }
    };
  }
}
__name(executeRetryFlaky, "executeRetryFlaky");

// src/poller.ts
async function concurrentMap(items, fn, concurrency) {
  const results = [];
  for (let i = 0; i < items.length; i += concurrency) {
    const batch = items.slice(i, i + concurrency);
    const batchResults = await Promise.all(batch.map(fn));
    results.push(...batchResults);
  }
  return results;
}
__name(concurrentMap, "concurrentMap");
async function pollAndSync(env) {
  const { subscriptions, notifLastModified } = await fetchSubscriptions(env);
  const headers2 = headers(env.GITHUB_TOKEN);
  if (notifLastModified) {
    headers2["If-Modified-Since"] = notifLastModified;
  }
  const res = await fetch(
    `${GITHUB_API}/notifications?participating=true&all=false`,
    { headers: headers2 }
  );
  if (res.status === 304) {
    await reportPollStatus(env, "ok");
    return;
  }
  if (!res.ok) {
    throw new Error(`GitHub notifications error: ${res.status}`);
  }
  const newLastModified = res.headers.get("Last-Modified");
  const notifications = await res.json();
  const subSet = new Set(subscriptions.map((s) => s.toLowerCase()));
  const prNotifs = notifications.filter(
    (n) => n.subject.type === "PullRequest" && subSet.has(n.repository.full_name.toLowerCase())
  );
  const notifsWithUrl = prNotifs.filter((n) => n.subject.url);
  const detailResults = await concurrentMap(
    notifsWithUrl,
    async (notif) => {
      const prRes = await fetch(notif.subject.url, {
        headers: headers(env.GITHUB_TOKEN)
      });
      if (!prRes.ok) return null;
      const pr = await prRes.json();
      const reviewers = (pr.requested_reviewers ?? []).map((r) => r.login).filter(Boolean);
      return {
        repo: notif.repository.full_name.toLowerCase(),
        prNumber: pr.number,
        action: notif.reason,
        prTitle: notif.subject.title,
        prUrl: pr.html_url,
        author: pr.user?.login,
        ...reviewers.length > 0 ? { reviewers } : {},
        notificationId: notif.id
      };
    },
    5
  );
  const syncNotifs = detailResults.filter(
    (n) => n !== null
  );
  if (syncNotifs.length > 0 || newLastModified) {
    await syncToServer(env, syncNotifs, newLastModified);
  }
  await reportPollStatus(env, "ok");
}
__name(pollAndSync, "pollAndSync");

// src/index.ts
var ALLOWED_COMMAND_TYPES = ["retry-ci", "retry-flaky"];
async function dispatch(cmd, githubToken) {
  if (!ALLOWED_COMMAND_TYPES.includes(cmd.commandType)) {
    return {
      status: "failed",
      result: { error: `rejected unknown command type: ${cmd.commandType}` }
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
__name(dispatch, "dispatch");
async function runPollCycle(env) {
  const [, commands] = await Promise.all([
    pollAndSync(env).catch((e) => {
      const error = e instanceof Error ? e.message : String(e);
      reportPollStatus(env, "error", error).catch(() => {
      });
    }),
    pollCommands(env).catch(async (e) => {
      const msg = e instanceof Error ? e.message : String(e);
      if (msg.includes("HTTP 401")) {
        console.log("Runner not registered \u2014 attempting self-registration...");
        await selfRegister(env).catch((re) => {
          console.error("self-register failed:", re instanceof Error ? re.message : String(re));
        });
      } else {
        console.error("pollCommands failed:", msg);
      }
      return [];
    })
  ]);
  for (const cmd of commands) {
    try {
      const { status, result } = await dispatch(cmd, env.GITHUB_TOKEN);
      await reportResult(env, cmd.id, status, result);
    } catch (e) {
      const error = e instanceof Error ? e.message : String(e);
      await reportResult(env, cmd.id, "failed", { error }).catch(() => {
      });
    }
  }
}
__name(runPollCycle, "runPollCycle");
var index_default = {
  async fetch(_request, env) {
    await runPollCycle(env);
    return new Response("ok");
  },
  async scheduled(_event, env, _ctx) {
    await runPollCycle(env);
  }
};
export {
  index_default as default
};
//# sourceMappingURL=index.js.map
