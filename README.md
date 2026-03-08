# GHPr

GitHub PR notification system — get push notifications on your phone when PRs you care about are updated.

## Structure

- `android/` — Android client (Kotlin + Compose)
- `server/` — GitHub webhook + FCM push backend (Cloudflare Workers + D1)
- `docs/` — product and system design notes

## Roadmap

1. **Android MVP** (`android/`)
   - GitHub sign-in flow
   - PR list + detail
   - open-triggered refresh with minimum interval guard
   - push-triggered refresh hook
2. **Push server MVP** (`server/`)
   - GitHub webhook ingestion
   - signature validation + idempotency
   - subscription matching
   - FCM notification dispatch
3. **Documentation** (`docs/`)
   - event model
   - API and sync contracts
   - onboarding and runtime settings checklist

## Implemented so far

- Android `core-domain` module with tested refresh decision logic
- Push server with webhook ingestion, device registration, repo subscriptions, and mobile sync API
- Push-ready user settings checklist
- Mobile sync contract for FCM-triggered refresh
