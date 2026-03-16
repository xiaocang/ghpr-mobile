use reqwest::Client;
use serde::Deserialize;

use crate::api::{PollStatusRequest, SyncNotification, SyncRequest, WorkerApi};

#[derive(Debug, Deserialize)]
#[allow(dead_code)]
struct Notification {
    id: String,
    reason: String,
    subject: Subject,
    repository: Repository,
    url: String,
}

#[derive(Debug, Deserialize)]
struct Subject {
    title: String,
    url: Option<String>,
    #[serde(rename = "type")]
    subject_type: String,
}

#[derive(Debug, Deserialize)]
struct Repository {
    full_name: String,
}

#[derive(Debug, Deserialize)]
struct PullRequestDetail {
    number: u64,
    html_url: String,
    user: Option<PrUser>,
    requested_reviewers: Option<Vec<PrUser>>,
}

#[derive(Debug, Deserialize)]
struct PrUser {
    login: String,
}

fn github_headers(token: &str) -> Vec<(&'static str, String)> {
    vec![
        ("authorization", format!("Bearer {token}")),
        ("accept", "application/vnd.github+json".to_string()),
        ("user-agent", "ghpr-runner/1.0".to_string()),
        ("x-github-api-version", "2022-11-28".to_string()),
    ]
}

pub async fn poll_and_sync(
    worker: &WorkerApi,
    github_token: &str,
    subscriptions: &[String],
    last_modified: &mut Option<String>,
) {
    let client = Client::new();
    let headers = github_headers(github_token);

    // Fetch GitHub notifications
    let mut req = client.get("https://api.github.com/notifications?participating=true&all=false");
    for (k, v) in &headers {
        req = req.header(*k, v);
    }
    if let Some(ref lm) = last_modified {
        req = req.header("if-modified-since", lm);
    }

    let res = match req.send().await {
        Ok(r) => r,
        Err(e) => {
            eprintln!("GitHub notifications request failed: {e}");
            report_poll_error(worker, &format!("request_failed: {e}")).await;
            return;
        }
    };

    let status = res.status();
    if status.as_u16() == 304 {
        // Not modified
        let _ = worker
            .report_poll_status(&PollStatusRequest {
                status: "ok".to_string(),
                error: None,
            })
            .await;
        return;
    }

    if !status.is_success() {
        let code = status.as_u16();
        eprintln!("GitHub notifications API error: {code}");
        report_poll_error(worker, &format!("github_error: {code}")).await;
        return;
    }

    // Save Last-Modified for next poll
    if let Some(lm) = res.headers().get("last-modified") {
        if let Ok(lm_str) = lm.to_str() {
            *last_modified = Some(lm_str.to_string());
        }
    }

    let notifications: Vec<Notification> = match res.json().await {
        Ok(n) => n,
        Err(e) => {
            eprintln!("Failed to parse notifications: {e}");
            report_poll_error(worker, &format!("parse_error: {e}")).await;
            return;
        }
    };

    // Filter to PullRequest notifications for subscribed repos
    let sub_set: std::collections::HashSet<&str> =
        subscriptions.iter().map(|s| s.as_str()).collect();

    let pr_notifications: Vec<&Notification> = notifications
        .iter()
        .filter(|n| {
            n.subject.subject_type == "PullRequest"
                && sub_set.contains(n.repository.full_name.to_lowercase().as_str())
        })
        .collect();

    if pr_notifications.is_empty() {
        // Still sync to persist last_modified even with no PR notifications
        if last_modified.is_some() {
            let _ = worker
                .sync_notifications(&SyncRequest {
                    notifications: Vec::new(),
                    notif_last_modified: last_modified.clone(),
                })
                .await;
        }
        let _ = worker
            .report_poll_status(&PollStatusRequest {
                status: "ok".to_string(),
                error: None,
            })
            .await;
        return;
    }

    // Fetch PR details and build sync payload
    let mut sync_notifs = Vec::new();

    for notif in &pr_notifications {
        let pr_api_url = match &notif.subject.url {
            Some(url) => url.clone(),
            None => continue,
        };

        let mut req = client.get(&pr_api_url);
        for (k, v) in &headers {
            req = req.header(*k, v);
        }

        let pr_detail: PullRequestDetail = match req.send().await {
            Ok(res) if res.status().is_success() => match res.json().await {
                Ok(pr) => pr,
                Err(_) => continue,
            },
            _ => continue,
        };

        let author = pr_detail.user.as_ref().map(|u| u.login.clone());
        let reviewers: Vec<String> = pr_detail
            .requested_reviewers
            .unwrap_or_default()
            .iter()
            .map(|u| u.login.clone())
            .collect();

        sync_notifs.push(SyncNotification {
            repo: notif.repository.full_name.to_lowercase(),
            pr_number: pr_detail.number,
            action: notif.reason.clone(),
            pr_title: Some(notif.subject.title.clone()),
            pr_url: Some(pr_detail.html_url.clone()),
            author,
            reviewers: if reviewers.is_empty() {
                None
            } else {
                Some(reviewers)
            },
            mentioned_user: None,
            notification_id: Some(notif.id.clone()),
        });
    }

    if !sync_notifs.is_empty() {
        let count = sync_notifs.len();
        match worker
            .sync_notifications(&SyncRequest {
                notifications: sync_notifs,
                notif_last_modified: last_modified.clone(),
            })
            .await
        {
            Ok(resp) => {
                println!(
                    "Synced {} notifications (pushed: {})",
                    resp.synced_count, resp.pushed_count
                );
            }
            Err(e) => {
                eprintln!("Failed to sync {count} notifications: {e}");
            }
        }
    }

    let _ = worker
        .report_poll_status(&PollStatusRequest {
            status: "ok".to_string(),
            error: None,
        })
        .await;
}

pub async fn fetch_github_login(github_token: &str) -> Result<String, String> {
    let client = Client::new();
    let headers = github_headers(github_token);

    let mut req = client.get("https://api.github.com/user");
    for (k, v) in &headers {
        req = req.header(*k, v);
    }

    let res = req
        .send()
        .await
        .map_err(|e| format!("GitHub user API request failed: {e}"))?;

    if !res.status().is_success() {
        return Err(format!("GitHub user API error: {}", res.status().as_u16()));
    }

    #[derive(Deserialize)]
    struct User {
        login: String,
    }

    let user: User = res
        .json()
        .await
        .map_err(|e| format!("Failed to parse GitHub user: {e}"))?;

    Ok(user.login)
}

async fn report_poll_error(worker: &WorkerApi, error: &str) {
    let _ = worker
        .report_poll_status(&PollStatusRequest {
            status: "error".to_string(),
            error: Some(error.to_string()),
        })
        .await;
}
