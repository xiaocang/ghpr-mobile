mod api;
mod commands;
mod config;
mod device;
mod poller;

use api::{CommandResultRequest, RegisterRequest, WorkerApi};
use clap::{Parser, Subcommand};

#[derive(Parser)]
#[command(name = "ghpr-runner", about = "GHPr runner client")]
struct Cli {
    #[command(subcommand)]
    command: Commands,
}

#[derive(Subcommand)]
enum Commands {
    /// Register this device as a runner
    Register {
        /// API key for user authentication
        #[arg(long, env = "GHPR_API_KEY")]
        api_key: String,

        /// User ID to bind runner to
        #[arg(long, env = "GHPR_USER_ID")]
        user_id: String,

        /// Optional label for this runner
        #[arg(long)]
        label: Option<String>,
    },
    /// Start polling for and executing commands
    Run,
    /// Check runner status
    Status,
}

#[tokio::main]
async fn main() {
    let cli = Cli::parse();

    match cli.command {
        Commands::Register {
            api_key,
            user_id,
            label,
        } => {
            if let Err(e) = cmd_register(&api_key, &user_id, label).await {
                eprintln!("Error: {e}");
                std::process::exit(1);
            }
        }
        Commands::Run => {
            if let Err(e) = cmd_run().await {
                eprintln!("Error: {e}");
                std::process::exit(1);
            }
        }
        Commands::Status => {
            if let Err(e) = cmd_status().await {
                eprintln!("Error: {e}");
                std::process::exit(1);
            }
        }
    }
}

async fn cmd_register(api_key: &str, user_id: &str, label: Option<String>) -> Result<(), String> {
    let cfg = config::Config::from_env()?;
    let device_id = device::device_id()?;
    let pairing_token = device::pairing_token()?;

    // Fetch GitHub login from token
    println!("Fetching GitHub login...");
    let github_login = poller::fetch_github_login(&cfg.github_token).await?;
    println!("  GitHub login: {github_login}");

    let worker = WorkerApi::new(&cfg.worker_url, &pairing_token);

    let req = RegisterRequest {
        device_id: device_id.clone(),
        pairing_token: pairing_token.clone(),
        github_login,
        label,
    };

    let res = worker.register(api_key, user_id, &req).await?;
    if res.ok {
        println!("Runner registered successfully.");
        println!("  Device ID: {device_id}");
        println!("  Pairing token saved to ~/.ghpr-runner/pairing_token");
    } else {
        return Err(format!(
            "registration failed: {}",
            res.error.unwrap_or_default()
        ));
    }

    Ok(())
}

async fn cmd_run() -> Result<(), String> {
    let cfg = config::Config::from_env()?;
    let pairing_token = device::pairing_token()?;
    let worker = WorkerApi::new(&cfg.worker_url, &pairing_token);

    // Fetch subscriptions (includes last stored notifLastModified)
    let subs = worker.list_subscriptions().await?;
    let mut subscriptions = subs.subscriptions;
    let mut last_modified = subs.notif_last_modified;
    println!(
        "Runner started. {} subscriptions, polling every {}s...{}",
        subscriptions.len(),
        cfg.poll_interval.as_secs(),
        if last_modified.is_some() { " (resuming with stored Last-Modified)" } else { "" }
    );

    let sub_refresh_interval = std::time::Duration::from_secs(5 * 60);
    let mut last_sub_refresh = std::time::Instant::now();

    loop {
        // Periodically refresh subscriptions
        if last_sub_refresh.elapsed() >= sub_refresh_interval {
            match worker.list_subscriptions().await {
                Ok(subs) => {
                    if subs.subscriptions != subscriptions {
                        println!("Subscriptions updated: {} repos", subs.subscriptions.len());
                    }
                    subscriptions = subs.subscriptions;
                }
                Err(e) => {
                    eprintln!("Failed to refresh subscriptions: {e}");
                }
            }
            last_sub_refresh = std::time::Instant::now();
        }

        // 1. Poll GitHub notifications and sync to worker
        poller::poll_and_sync(&worker, &cfg.github_token, &subscriptions, &mut last_modified)
            .await;

        // 2. Poll and execute commands from worker
        match worker.poll_commands().await {
            Ok(poll) => {
                for cmd in &poll.commands {
                    println!(
                        "Executing command #{}: {}",
                        cmd.id, cmd.command_type
                    );

                    let (status, result) =
                        commands::execute(&cmd.command_type, &cmd.payload, &cfg.github_token).await;

                    println!("  Result: {status}");

                    let report = CommandResultRequest { status, result };
                    match worker.report_result(cmd.id, &report).await {
                        Ok(_) => println!("  Reported to worker."),
                        Err(e) => eprintln!("  Failed to report: {e}"),
                    }
                }
            }
            Err(e) => {
                eprintln!("Poll error: {e}");
            }
        }

        tokio::time::sleep(cfg.poll_interval).await;
    }
}

async fn cmd_status() -> Result<(), String> {
    let cfg = config::Config::from_env()?;
    let pairing_token = device::pairing_token()?;
    let worker = WorkerApi::new(&cfg.worker_url, &pairing_token);

    let status = worker.status().await?;
    println!("{}", serde_json::to_string_pretty(&status).unwrap());

    Ok(())
}
