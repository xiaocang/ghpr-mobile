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

## Android GitHub OAuth setup

The Android app uses GitHub OAuth Device Flow and requires an OAuth App `client_id`.

1. Open `android/local.properties` (gitignored).
2. Add:

```properties
github.clientId=YOUR_GITHUB_OAUTH_CLIENT_ID
```

You can also pass `-Pgithub.clientId=...` from Gradle.  
`client_id` is safe to ship in the app, but do not put `client_secret` in Android.

## Android server URL setup

The Android app reads backend URL from `ghpr.serverUrl`.

In `android/local.properties`:

```properties
ghpr.serverUrl=https://<your-worker>.workers.dev
```

You can also pass `-Pghpr.serverUrl=...` from Gradle.
Debug builds fail fast when this value is missing.
