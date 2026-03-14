use reqwest::Client;
use serde::{Deserialize, Serialize};

pub struct WorkerApi {
    client: Client,
    base_url: String,
    pairing_token: String,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RegisterRequest {
    pub device_id: String,
    pub pairing_token: String,
    pub github_login: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub label: Option<String>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SyncRequest {
    pub notifications: Vec<SyncNotification>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SyncNotification {
    pub repo: String,
    pub pr_number: u64,
    pub action: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub pr_title: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub pr_url: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub author: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reviewers: Option<Vec<String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub mentioned_user: Option<String>,
}

#[derive(Debug, Serialize)]
pub struct PollStatusRequest {
    pub status: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
#[allow(dead_code)]
pub struct SyncResponse {
    pub ok: bool,
    #[serde(default)]
    pub synced_count: u64,
    #[serde(default)]
    pub pushed_count: u64,
}

#[derive(Debug, Deserialize)]
#[allow(dead_code)]
pub struct SubscriptionsResponse {
    pub ok: bool,
    #[serde(default)]
    pub subscriptions: Vec<String>,
}

#[derive(Debug, Deserialize)]
pub struct ApiResponse {
    pub ok: bool,
    #[serde(default)]
    pub error: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
#[allow(dead_code)]
pub struct PollResponse {
    pub ok: bool,
    pub commands: Vec<Command>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
#[allow(dead_code)]
pub struct Command {
    pub id: u64,
    pub command_type: String,
    pub payload: serde_json::Value,
    pub created_at: String,
}

#[derive(Debug, Serialize)]
pub struct CommandResultRequest {
    pub status: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub result: Option<serde_json::Value>,
}

impl WorkerApi {
    pub fn new(base_url: &str, pairing_token: &str) -> Self {
        WorkerApi {
            client: Client::new(),
            base_url: base_url.to_string(),
            pairing_token: pairing_token.to_string(),
        }
    }

    pub async fn register(
        &self,
        api_key: &str,
        user_id: &str,
        req: &RegisterRequest,
    ) -> Result<ApiResponse, String> {
        let res = self
            .client
            .post(format!("{}/runners/register", self.base_url))
            .header("x-api-key", api_key)
            .query(&[("userId", user_id)])
            .json(req)
            .send()
            .await
            .map_err(|e| format!("register request failed: {e}"))?;

        res.json::<ApiResponse>()
            .await
            .map_err(|e| format!("register response parse failed: {e}"))
    }

    pub async fn poll_commands(&self) -> Result<PollResponse, String> {
        let res = self
            .client
            .get(format!("{}/runners/commands/poll", self.base_url))
            .header("x-runner-token", &self.pairing_token)
            .send()
            .await
            .map_err(|e| format!("poll request failed: {e}"))?;

        res.json::<PollResponse>()
            .await
            .map_err(|e| format!("poll response parse failed: {e}"))
    }

    pub async fn report_result(
        &self,
        command_id: u64,
        req: &CommandResultRequest,
    ) -> Result<ApiResponse, String> {
        let res = self
            .client
            .post(format!(
                "{}/runners/commands/{}/result",
                self.base_url, command_id
            ))
            .header("x-runner-token", &self.pairing_token)
            .json(req)
            .send()
            .await
            .map_err(|e| format!("report result failed: {e}"))?;

        res.json::<ApiResponse>()
            .await
            .map_err(|e| format!("report result parse failed: {e}"))
    }

    pub async fn sync_notifications(&self, req: &SyncRequest) -> Result<SyncResponse, String> {
        let res = self
            .client
            .post(format!("{}/runners/sync", self.base_url))
            .header("x-runner-token", &self.pairing_token)
            .json(req)
            .send()
            .await
            .map_err(|e| format!("sync request failed: {e}"))?;

        res.json::<SyncResponse>()
            .await
            .map_err(|e| format!("sync response parse failed: {e}"))
    }

    pub async fn report_poll_status(&self, req: &PollStatusRequest) -> Result<ApiResponse, String> {
        let res = self
            .client
            .post(format!("{}/runners/poll-status", self.base_url))
            .header("x-runner-token", &self.pairing_token)
            .json(req)
            .send()
            .await
            .map_err(|e| format!("poll-status request failed: {e}"))?;

        res.json::<ApiResponse>()
            .await
            .map_err(|e| format!("poll-status response parse failed: {e}"))
    }

    pub async fn list_subscriptions(&self) -> Result<SubscriptionsResponse, String> {
        let res = self
            .client
            .get(format!("{}/runners/subscriptions", self.base_url))
            .header("x-runner-token", &self.pairing_token)
            .send()
            .await
            .map_err(|e| format!("subscriptions request failed: {e}"))?;

        res.json::<SubscriptionsResponse>()
            .await
            .map_err(|e| format!("subscriptions response parse failed: {e}"))
    }

    pub async fn status(&self) -> Result<serde_json::Value, String> {
        let res = self
            .client
            .get(format!("{}/runners/status", self.base_url))
            .header("x-runner-token", &self.pairing_token)
            .send()
            .await
            .map_err(|e| format!("status request failed: {e}"))?;

        res.json::<serde_json::Value>()
            .await
            .map_err(|e| format!("status response parse failed: {e}"))
    }
}
