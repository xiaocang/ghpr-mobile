-- Move polling from worker to runner: add polling fields to runners, drop user_github_tokens

ALTER TABLE runners ADD COLUMN github_login TEXT;
ALTER TABLE runners ADD COLUMN last_poll_status TEXT;
ALTER TABLE runners ADD COLUMN last_poll_error TEXT;
ALTER TABLE runners ADD COLUMN last_poll_at TEXT;

DROP TABLE IF EXISTS user_github_tokens;
