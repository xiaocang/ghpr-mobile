use reqwest::Client;
use serde::Deserialize;
use serde_json::Value;
use std::collections::HashMap;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Payload {
    repo_full_name: String,
    pr_number: u64,
    #[serde(default)]
    workflow_attempts: HashMap<String, u32>,
}

#[derive(Deserialize)]
struct WorkflowRun {
    id: u64,
    name: Option<String>,
}

#[derive(Deserialize)]
struct WorkflowRunsResponse {
    workflow_runs: Vec<WorkflowRun>,
}

#[derive(Deserialize)]
struct PullRequest {
    head: PrHead,
}

#[derive(Deserialize)]
struct PrHead {
    sha: String,
}

const MAX_PER_WORKFLOW: u32 = 3;

fn github_headers(token: &str) -> Vec<(&'static str, String)> {
    vec![
        ("authorization", format!("Bearer {token}")),
        ("accept", "application/vnd.github+json".to_string()),
        ("user-agent", "ghpr-runner/1.0".to_string()),
        ("x-github-api-version", "2022-11-28".to_string()),
    ]
}

pub async fn execute(payload: &Value, github_token: &str) -> (String, Option<Value>) {
    let parsed: Payload = match serde_json::from_value(payload.clone()) {
        Ok(p) => p,
        Err(e) => {
            return (
                "failed".to_string(),
                Some(serde_json::json!({ "error": format!("invalid payload: {e}") })),
            );
        }
    };

    let client = Client::new();
    let headers = github_headers(github_token);

    // Get PR head SHA
    let pr_url = format!(
        "https://api.github.com/repos/{}/pulls/{}",
        parsed.repo_full_name, parsed.pr_number
    );
    let mut req = client.get(&pr_url);
    for (k, v) in &headers {
        req = req.header(*k, v);
    }

    let head_sha = match req.send().await {
        Ok(res) if res.status().is_success() => match res.json::<PullRequest>().await {
            Ok(pr) => pr.head.sha,
            Err(e) => {
                return (
                    "failed".to_string(),
                    Some(serde_json::json!({ "error": format!("parse PR failed: {e}") })),
                );
            }
        },
        Ok(res) => {
            let status = res.status().as_u16();
            return (
                "failed".to_string(),
                Some(serde_json::json!({ "error": format!("GitHub PR API error: {status}") })),
            );
        }
        Err(e) => {
            return (
                "failed".to_string(),
                Some(serde_json::json!({ "error": format!("GitHub PR API request failed: {e}") })),
            );
        }
    };

    // List failed workflow runs for this SHA
    let runs_url = format!(
        "https://api.github.com/repos/{}/actions/runs?status=failure&head_sha={}",
        parsed.repo_full_name, head_sha
    );
    let mut req = client.get(&runs_url);
    for (k, v) in &headers {
        req = req.header(*k, v);
    }

    let runs_response = match req.send().await {
        Ok(res) if res.status().is_success() => match res.json::<WorkflowRunsResponse>().await {
            Ok(r) => r,
            Err(e) => {
                return (
                    "failed".to_string(),
                    Some(serde_json::json!({ "error": format!("parse workflow runs failed: {e}") })),
                );
            }
        },
        Ok(res) => {
            let status = res.status().as_u16();
            return (
                "failed".to_string(),
                Some(serde_json::json!({ "error": format!("GitHub Actions API error: {status}") })),
            );
        }
        Err(e) => {
            return (
                "failed".to_string(),
                Some(serde_json::json!({ "error": format!("GitHub Actions API request failed: {e}") })),
            );
        }
    };

    let mut retried_count = 0u64;
    let mut workflows: Vec<Value> = Vec::new();

    for run in &runs_response.workflow_runs {
        let name = run.name.clone().unwrap_or_else(|| format!("run-{}", run.id));
        let prev_attempts = parsed.workflow_attempts.get(&name).copied().unwrap_or(0);

        if prev_attempts >= MAX_PER_WORKFLOW {
            workflows.push(serde_json::json!({
                "name": name,
                "attempts": prev_attempts,
                "skipped": true,
            }));
            continue;
        }

        let rerun_url = format!(
            "https://api.github.com/repos/{}/actions/runs/{}/rerun-failed-jobs",
            parsed.repo_full_name, run.id
        );
        let mut req = client.post(&rerun_url);
        for (k, v) in &headers {
            req = req.header(*k, v);
        }

        match req.send().await {
            Ok(res) if res.status().is_success() || res.status().as_u16() == 201 => {
                retried_count += 1;
                workflows.push(serde_json::json!({
                    "name": name,
                    "attempts": prev_attempts + 1,
                }));
            }
            Ok(res) if res.status().as_u16() == 409 => {
                // Already rerunning — don't count as a new attempt
                workflows.push(serde_json::json!({
                    "name": name,
                    "attempts": prev_attempts,
                }));
            }
            Ok(res) => {
                let status = res.status().as_u16();
                eprintln!("Failed to rerun workflow {} ({}): HTTP {}", run.id, name, status);
                workflows.push(serde_json::json!({
                    "name": name,
                    "attempts": prev_attempts,
                    "error": format!("HTTP {status}"),
                }));
            }
            Err(e) => {
                eprintln!("Failed to rerun workflow {} ({}): {}", run.id, name, e);
                workflows.push(serde_json::json!({
                    "name": name,
                    "attempts": prev_attempts,
                    "error": e.to_string(),
                }));
            }
        }
    }

    (
        "completed".to_string(),
        Some(serde_json::json!({
            "retriedCount": retried_count,
            "workflows": workflows,
        })),
    )
}
