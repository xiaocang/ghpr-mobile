CREATE TABLE IF NOT EXISTS webhook_deliveries (
  delivery_id TEXT PRIMARY KEY,
  event_type TEXT NOT NULL,
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS device_tokens (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id TEXT NOT NULL,
  token TEXT NOT NULL UNIQUE,
  platform TEXT NOT NULL DEFAULT 'android',
  created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS repo_subscriptions (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id TEXT NOT NULL,
  repo_full_name TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  UNIQUE(user_id, repo_full_name)
);

CREATE TABLE IF NOT EXISTS pr_changes (
  delivery_id TEXT PRIMARY KEY,
  repo_full_name TEXT NOT NULL,
  pr_number INTEGER NOT NULL,
  action TEXT NOT NULL,
  changed_at_ms INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_device_tokens_user_id ON device_tokens (user_id);
CREATE INDEX IF NOT EXISTS idx_repo_subscriptions_repo ON repo_subscriptions (repo_full_name);
CREATE INDEX IF NOT EXISTS idx_pr_changes_repo_changed_at ON pr_changes (repo_full_name, changed_at_ms);

CREATE TABLE IF NOT EXISTS user_github_tokens (
  user_id TEXT PRIMARY KEY,
  encrypted_token TEXT NOT NULL,
  github_login TEXT NOT NULL,
  last_poll_at TEXT,
  last_poll_status TEXT,
  last_poll_error TEXT,
  last_poll_success_at TEXT,
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS pr_user_involvement (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  repo_full_name TEXT NOT NULL,
  pr_number INTEGER NOT NULL,
  github_login TEXT NOT NULL,
  role TEXT NOT NULL,
  updated_at_ms INTEGER NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_pr_involvement_unique
  ON pr_user_involvement (repo_full_name, pr_number, github_login, role);
CREATE INDEX IF NOT EXISTS idx_pr_involvement_login
  ON pr_user_involvement (github_login);
