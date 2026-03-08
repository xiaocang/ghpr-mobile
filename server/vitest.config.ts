import { defineWorkersConfig } from "@cloudflare/vitest-pool-workers/config";

export default defineWorkersConfig({
  test: {
    poolOptions: {
      workers: {
        wrangler: { configPath: "./wrangler.toml" },
        miniflare: {
          bindings: {
            GITHUB_WEBHOOK_SECRET: "test-webhook-secret",
            FCM_PROJECT_ID: "test-project",
            FCM_CLIENT_EMAIL: "test@test.iam.gserviceaccount.com",
            FCM_PRIVATE_KEY: "test-private-key",
            INTERNAL_API_KEY: "test-api-key",
          },
        },
      },
    },
  },
});
