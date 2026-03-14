pub mod retry_ci;
pub mod retry_flaky;

use serde_json::Value;

pub async fn execute(
    command_type: &str,
    payload: &Value,
    github_token: &str,
) -> (String, Option<Value>) {
    match command_type {
        "retry-ci" => retry_ci::execute(payload, github_token).await,
        "retry-flaky" => retry_flaky::execute(payload, github_token).await,
        _ => (
            "failed".to_string(),
            Some(serde_json::json!({ "error": format!("unknown command type: {command_type}") })),
        ),
    }
}
