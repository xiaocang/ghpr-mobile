import { normalizeAction } from "./normalize";

type DeviceTokenRow = {
  token: string;
};

export function reasonToAction(reason: string): string {
  return normalizeAction(reason);
}

export async function resolveDeviceTokensForUser(
  db: D1Database,
  userId: string
): Promise<string[]> {
  const result = await db
    .prepare("SELECT token FROM device_tokens WHERE user_id = ?")
    .bind(userId)
    .all<DeviceTokenRow>();
  return (result.results ?? []).map((row) => row.token);
}
