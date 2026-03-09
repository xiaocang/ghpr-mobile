-- Migration number: 0002 	 2026-03-09T08:30:00.000Z

ALTER TABLE user_github_tokens ADD COLUMN last_poll_status TEXT;
ALTER TABLE user_github_tokens ADD COLUMN last_poll_error TEXT;
ALTER TABLE user_github_tokens ADD COLUMN last_poll_success_at TEXT;

