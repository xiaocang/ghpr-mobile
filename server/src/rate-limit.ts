export async function checkRateLimit(
  db: D1Database,
  key: string,
  maxRequests: number,
  windowSec: number,
): Promise<Response | null> {
  const now = Math.floor(Date.now() / 1000);
  const windowStart = Math.floor(now / windowSec) * windowSec;

  const result = await db
    .prepare(
      `INSERT INTO rate_limits (key, window_start, count)
       VALUES (?, ?, 1)
       ON CONFLICT(key, window_start) DO UPDATE SET count = count + 1
       RETURNING count`
    )
    .bind(key, windowStart)
    .first<{ count: number }>();

  const count = result?.count ?? 1;

  if (count > maxRequests) {
    const retryAfter = windowStart + windowSec - now;
    return new Response(
      JSON.stringify({ error: "rate limit exceeded" }),
      {
        status: 429,
        headers: {
          "content-type": "application/json; charset=utf-8",
          "retry-after": String(retryAfter),
        },
      },
    );
  }

  return null;
}

export async function cleanupRateLimits(db: D1Database): Promise<void> {
  const cutoff = Math.floor(Date.now() / 1000) - 300;
  await db
    .prepare("DELETE FROM rate_limits WHERE window_start < ?")
    .bind(cutoff)
    .run();
}
