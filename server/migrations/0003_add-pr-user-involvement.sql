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
