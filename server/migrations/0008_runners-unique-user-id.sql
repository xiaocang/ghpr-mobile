-- Deduplicate runners: keep only the most recently seen row per user_id.
DELETE FROM runners WHERE id NOT IN (
  SELECT id FROM (
    SELECT id, ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY last_seen_at DESC, id DESC) AS rn
    FROM runners
  ) WHERE rn = 1
);

DROP INDEX IF EXISTS idx_runners_user_id;
CREATE UNIQUE INDEX idx_runners_user_id ON runners (user_id);
