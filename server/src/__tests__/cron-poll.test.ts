import { describe, it, expect, beforeAll, beforeEach } from "vitest";
import { env, initSchema, resetDb } from "./helpers";

beforeAll(initSchema);
beforeEach(resetDb);

describe("resolveDeviceTokensForUser", () => {
  it("returns device tokens for a user", async () => {
    await env.DB.exec(
      `INSERT INTO device_tokens (user_id, token, platform) VALUES ('u1', 'tok-a', 'android');
       INSERT INTO device_tokens (user_id, token, platform) VALUES ('u1', 'tok-b', 'android');
       INSERT INTO device_tokens (user_id, token, platform) VALUES ('u2', 'tok-c', 'android');`
    );

    const { resolveDeviceTokensForUser } = await import("../github-poller");
    const tokens = await resolveDeviceTokensForUser(env.DB, "u1");
    expect(tokens).toHaveLength(2);
    expect(tokens).toContain("tok-a");
    expect(tokens).toContain("tok-b");
  });

  it("returns empty array for user with no devices", async () => {
    const { resolveDeviceTokensForUser } = await import("../github-poller");
    const tokens = await resolveDeviceTokensForUser(env.DB, "nobody");
    expect(tokens).toHaveLength(0);
  });

  it("normalizes poll reasons into app actions", async () => {
    const { reasonToAction } = await import("../github-poller");
    expect(reasonToAction("review_requested")).toBe("review_requested");
    expect(reasonToAction("author")).toBe("updated");
    expect(reasonToAction("comment")).toBe("commented");
    expect(reasonToAction("mention")).toBe("mentioned");
    expect(reasonToAction("assign")).toBe("assigned");
    expect(reasonToAction("state_change")).toBe("state_changed");
    expect(reasonToAction("something_else")).toBe("updated");
  });
});
