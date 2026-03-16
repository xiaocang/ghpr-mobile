use std::env;
use std::time::Duration;

pub struct Config {
    pub github_token: String,
    pub worker_url: String,
    pub poll_interval: Duration,
}

impl Config {
    pub fn from_env() -> Result<Self, String> {
        let github_token = env::var("GITHUB_TOKEN")
            .map_err(|_| "GITHUB_TOKEN environment variable is required".to_string())?;

        let worker_url = env::var("GHPR_WORKER_URL")
            .map_err(|_| "GHPR_WORKER_URL environment variable is required".to_string())?;

        if !worker_url.starts_with("https://") {
            eprintln!("WARNING: GHPR_WORKER_URL does not use HTTPS — pairing token will be sent in cleartext");
        }

        let poll_interval_secs: u64 = env::var("GHPR_POLL_INTERVAL")
            .ok()
            .and_then(|v| v.parse().ok())
            .unwrap_or(30);

        Ok(Config {
            github_token,
            worker_url: worker_url.trim_end_matches('/').to_string(),
            poll_interval: Duration::from_secs(poll_interval_secs),
        })
    }
}
