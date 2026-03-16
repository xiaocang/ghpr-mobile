export async function resolveDeviceTokensForPr(
  db: D1Database,
  repoFullName: string,
  prNumber: number
): Promise<string[]> {
  const result = await db
    .prepare(
      `SELECT DISTINCT dt.token AS token
       FROM repo_subscriptions rs
       JOIN device_tokens dt ON dt.user_id = rs.user_id
       JOIN runners r ON r.user_id = rs.user_id
       JOIN pr_user_involvement pui
         ON pui.repo_full_name = rs.repo_full_name
         AND pui.pr_number = ?
         AND LOWER(pui.github_login) = LOWER(r.github_login)
       WHERE rs.repo_full_name = ?`
    )
    .bind(prNumber, repoFullName)
    .all<{ token: string }>();

  return (result.results ?? []).map((row) => row.token);
}
