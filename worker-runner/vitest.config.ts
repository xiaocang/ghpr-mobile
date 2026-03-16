import { defineWorkersConfig } from "@cloudflare/vitest-pool-workers/config";

export default defineWorkersConfig({
  test: {
    poolOptions: {
      workers: {
        wrangler: { configPath: "./wrangler.toml" },
        miniflare: {
          bindings: {
            GITHUB_TOKEN: "ghp-test-token",
            RUNNER_TOKEN: "test-runner-token",
            WORKER_URL: "https://ghpr-server.test.workers.dev",
          },
        },
      },
    },
  },
});
