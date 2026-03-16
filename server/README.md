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

## Local development

Prerequisite: Node.js 20+ (Wrangler 4 requirement).

```bash
cd server
npm install
npm run dev
```

## D1 binding and schema

- The Worker code expects D1 binding name `DB` in `wrangler.toml`.
- Initialize schema on the same D1 database before testing protected APIs:

```bash
cd server
npx wrangler d1 execute ghpr --remote --file=schema.sql
```

## Required secrets

- `GITHUB_WEBHOOK_SECRET`
- `FCM_PROJECT_ID`
- `FCM_CLIENT_EMAIL`
- `FCM_PRIVATE_KEY`
- `INTERNAL_API_KEY` (optional but recommended for management endpoints)

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

## Notes

Current implementation is a bootstrap scaffold to start development and integration tests.


## Matching model

1. Client calls `POST /devices/register` to bind `userId -> token`.
2. Client calls `POST /subscriptions` to bind `userId -> repoFullName`.
3. Webhook event resolves `repoFullName -> userId -> token` and fans out pushes.
4. Client settings/diagnostics call list endpoints to inspect registered devices/subscriptions.
5. Client can query incremental changes via `GET /mobile/sync`.


- `GET /devices` returns masked token previews only (no raw FCM token leakage).
