# Mobile Sync Contract (Draft)

This document defines how Android client and push server coordinate refresh.

## Push payload

FCM `data` payload fields:

- `type`: `pr_update`
- `repo`: `owner/repo`
- `prNumber`: pull request number
- `action`: GitHub webhook action
- `prTitle` (optional): pull request title
- `prUrl` (optional): pull request URL
- `deliveryId`: GitHub delivery id for idempotency tracing
- `sentAt`: unix millis

## Client parsing rules

- Only process payload when `type == pr_update`.
- Require non-empty `repo`, `action`, `deliveryId`.
- Require `prNumber` as positive integer string.
- Require `sentAt` as positive unix millis string.
- Deduplicate by `deliveryId` on client side to avoid repeat refresh for redelivered pushes.
- `action` is normalized into app events: `opened`, `updated`, `review_requested`, `commented`, `mentioned`, `assigned`, `merged`, `closed`, `state_changed`.

## Client behavior

1. Receive push payload in FCM service.
2. Call `RefreshCoordinator.markPushEventReceived()`.
3. If app in foreground and visible list exists, trigger lightweight refresh.
4. Otherwise refresh on next app open.

## Data source expectations

- GitHub OAuth is account identity only and does not directly return PR list content.
- `GET /mobile/sync` returns data only when both are true:
  - user has at least one subscribed `owner/repo`
  - server has ingested matching PR webhook events into `pr_changes`


## Push service APIs

- Management APIs may require `x-api-key` header (server-configured).
- `POST /devices/register`
  - body: `{ "userId": "u_123", "token": "fcm_token", "platform": "android" }`
- `DELETE /devices/register`
  - body: `{ "userId": "u_123", "token": "fcm_token" }`
- `GET /devices?userId=u_123` returns masked token previews.
- `POST /subscriptions`
  - body: `{ "userId": "u_123", "repoFullName": "owner/repo" }`
- `GET /subscriptions?userId=u_123`
- `DELETE /subscriptions`
  - body: `{ "userId": "u_123", "repoFullName": "owner/repo" }`

## API sync endpoint

`GET /mobile/sync?userId=<user_id>&since=<unix_millis>&cursorDeliveryId=<delivery_id>&limit=100` (and can be protected by `x-api-key`)

Response:

```json
{
  "ok": true,
  "userId": "u_123",
  "nextSince": 1730000100000,
  "nextCursorDeliveryId": "delivery_abc",
  "hasMore": false,
  "changedPullRequests": [
    {
      "repo": "owner/repo",
      "number": 123,
      "action": "synchronize",
      "changedAtMs": 1730000100000
    }
  ]
}
```

Notes:

- `changedPullRequests` is collapsed by `(repo, number)`, returning only the latest change after `since` for each PR.
- `hasMore` indicates whether additional rows are available; client should continue paging when `true`.
- `cursorDeliveryId` + `nextCursorDeliveryId` prevent page gaps when multiple rows share the same `changedAtMs`.

## Conflict policy

- Server state is source of truth for PR status.
- Client may show cached data before sync finishes.
- Same PR in multiple push events should collapse to one refresh cycle.
