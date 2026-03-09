-- Migration number: 0001 	 2026-03-09T01:53:10.914Z

CREATE TABLE IF NOT EXISTS user_github_tokens (
  user_id TEXT PRIMARY KEY,
  encrypted_token TEXT NOT NULL,
  github_login TEXT NOT NULL,
  last_poll_at TEXT,
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at TEXT NOT NULL DEFAULT (datetime('now'))
);
