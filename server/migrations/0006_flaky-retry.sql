-- Add delayed execution support for runner commands
ALTER TABLE runner_commands ADD COLUMN scheduled_after TEXT;

-- Flaky retry job tracking per PR
CREATE TABLE IF NOT EXISTS flaky_retry_jobs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  repo_full_name TEXT NOT NULL,
  pr_number INTEGER NOT NULL,
  user_id TEXT NOT NULL,
  runner_id INTEGER NOT NULL REFERENCES runners(id),
  retries_remaining INTEGER NOT NULL DEFAULT 3,
  workflow_attempts TEXT NOT NULL DEFAULT '{}',
  status TEXT NOT NULL DEFAULT 'active',
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at TEXT NOT NULL DEFAULT (datetime('now')),
  UNIQUE(repo_full_name, pr_number)
);
