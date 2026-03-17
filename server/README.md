# Push Server (Cloudflare Workers)

This service receives GitHub webhook events and sends device push via FCM.

## Scope

- Verify GitHub webhook signature (`X-Hub-Signature-256`)
- Deduplicate using `X-GitHub-Delivery`
- Parse PR-related events
- Resolve affected subscribers
- Send FCM push notification

## Runtime and storage

- Cloudflare Workers (TypeScript)
- Optional Queue for async fan-out
- D1 for event dedupe and subscription metadata (or Postgres later)

## Deployment

### 1. Create the D1 database

```bash
cd server
npm install
npx wrangler d1 create ghpr
```

Copy the database ID into `wrangler.toml`.

### 2. Initialize schema

```bash
npx wrangler d1 execute ghpr --remote --file=schema.sql
```

### 3. Set secrets

```bash
npx wrangler secret put GITHUB_WEBHOOK_SECRET
npx wrangler secret put FCM_PROJECT_ID
npx wrangler secret put FCM_CLIENT_EMAIL
npx wrangler secret put FCM_PRIVATE_KEY
```

Optional but recommended:

```bash
npx wrangler secret put INTERNAL_API_KEY
```

### 4. Deploy

```bash
npx wrangler deploy
```

### 5. Configure the GitHub webhook

1. Go to your repo (or org) **Settings > Webhooks > Add webhook**.
2. Set **Payload URL** to `https://<your-server-worker>.workers.dev/github/webhook`.
3. Set **Content type** to `application/json`.
4. Set **Secret** to the same value you used for `GITHUB_WEBHOOK_SECRET`.
5. Select events: **Pull requests**, **Pull request reviews**, **Check runs**, **Issue comments**.

## Local development

Prerequisite: Node.js 20+ (Wrangler 4 requirement).

```bash
cd server
npm install
npm run dev
```

## Endpoints

Management endpoints require `x-api-key` when `INTERNAL_API_KEY` is set.

- `POST /devices/register`
- `DELETE /devices/register`
- `GET /devices?userId=...`
- `POST /subscriptions`
- `GET /subscriptions?userId=...`
- `DELETE /subscriptions`
- `POST /github/webhook`
- `GET /mobile/sync?userId=...&since=...&cursorDeliveryId=...&limit=...`
- `GET /healthz`

### Runner endpoints (api-key auth)

- `POST /runners/register` — register a runner
- `GET /runners/poll-info` — get runner status for current user
- `POST /commands/retry-ci` — submit retry-ci command
- `POST /commands/retry-flaky` — submit retry-flaky command
- `GET /commands/retry-flaky` — list retry-flaky jobs
- `DELETE /commands/retry-flaky` — cancel retry-flaky job

### Runner endpoints (runner-token auth)

- `GET /runners/status` — runner heartbeat/status
- `DELETE /runners/register` — unregister runner
- `GET /runners/commands/poll` — poll for pending commands
- `POST /runners/commands/{id}/result` — submit command result
- `POST /runners/sync` — sync runner state
- `POST /runners/poll-status` — update poll status
- `GET /runners/subscriptions` — list subscriptions for runner's user

## Matching model

1. Client calls `POST /devices/register` to bind `userId -> token`.
2. Client calls `POST /subscriptions` to bind `userId -> repoFullName`.
3. Webhook event resolves `repoFullName -> userId -> token` and fans out pushes.
4. Client settings/diagnostics call list endpoints to inspect registered devices/subscriptions.
5. Client can query incremental changes via `GET /mobile/sync`.


- `GET /devices` returns masked token previews only (no raw FCM token leakage).
