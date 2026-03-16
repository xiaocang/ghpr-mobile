pub mod retry_ci;
pub mod retry_flaky;

use serde_json::Value;

/// Allowed command types for the worker↔runner RPC protocol.
/// Must match the server-side ALLOWED_COMMAND_TYPES.
const ALLOWED_COMMAND_TYPES: &[&str] = &["retry-ci", "retry-flaky"];

pub fn is_valid_repo_name(name: &str) -> bool {
    let parts: Vec<&str> = name.splitn(3, '/').collect();
    if parts.len() != 2 || parts[0].is_empty() || parts[1].is_empty() {
        return false;
    }
    parts.iter().all(|part| {
        part.chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '_' || c == '-')
    })
}

pub async fn execute(
    command_type: &str,
    payload: &Value,
    github_token: &str,
) -> (String, Option<Value>) {
    if !ALLOWED_COMMAND_TYPES.contains(&command_type) {
        return (
            "failed".to_string(),
            Some(serde_json::json!({ "error": format!("rejected unknown command type: {command_type}") })),
        );
    }

    match command_type {
        "retry-ci" => retry_ci::execute(payload, github_token).await,
        "retry-flaky" => retry_flaky::execute(payload, github_token).await,
        _ => unreachable!(),
    }
}
