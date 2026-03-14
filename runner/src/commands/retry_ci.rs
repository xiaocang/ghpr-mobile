use reqwest::Client;
use serde::Deserialize;
use serde_json::Value;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Payload {
    repo_full_name: String,
    pr_number: Option<u64>,
}

#[derive(Deserialize)]
struct WorkflowRun {
    id: u64,
    name: Option<String>,
    html_url: Option<String>,
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

    // If PR number provided, get head SHA to filter runs
    let head_sha = if let Some(pr_number) = parsed.pr_number {
        let url = format!(
            "https://api.github.com/repos/{}/pulls/{}",
            parsed.repo_full_name, pr_number
        );
        let mut req = client.get(&url);
        for (k, v) in &headers {
            req = req.header(*k, v);
        }
        match req.send().await {
            Ok(res) if res.status().is_success() => {
                match res.json::<PullRequest>().await {
                    Ok(pr) => Some(pr.head.sha),
                    Err(_) => None,
                }
            }
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
        }
    } else {
        None
    };

    // List failed workflow runs
    let mut url = format!(
        "https://api.github.com/repos/{}/actions/runs?status=failure",
        parsed.repo_full_name
    );
    if let Some(sha) = &head_sha {
        url.push_str(&format!("&head_sha={sha}"));
    }

    let mut req = client.get(&url);
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

    // Rerun failed jobs for each run
    let mut rerun_count = 0u64;
    let mut rerun_runs = Vec::new();

    for run in &runs_response.workflow_runs {
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
                rerun_count += 1;
                rerun_runs.push(serde_json::json!({
                    "id": run.id,
                    "name": run.name,
                    "url": run.html_url,
                }));
            }
            Ok(res) => {
                let status = res.status().as_u16();
                eprintln!("Failed to rerun workflow {}: HTTP {}", run.id, status);
            }
            Err(e) => {
                eprintln!("Failed to rerun workflow {}: {}", run.id, e);
            }
        }
    }

    (
        "completed".to_string(),
        Some(serde_json::json!({
            "rerunCount": rerun_count,
            "runs": rerun_runs,
        })),
    )
}
