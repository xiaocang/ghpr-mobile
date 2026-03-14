pub mod retry_ci;

use serde_json::Value;

pub async fn execute(
    command_type: &str,
    payload: &Value,
    github_token: &str,
) -> (String, Option<Value>) {
    match command_type {
        "retry-ci" => retry_ci::execute(payload, github_token).await,
        _ => (
            "failed".to_string(),
            Some(serde_json::json!({ "error": format!("unknown command type: {command_type}") })),
        ),
    }
}
