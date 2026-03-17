# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

GHPr is a GitHub PR push notification system: GitHub webhooks flow to a Cloudflare Worker server, which sends FCM push notifications to an Android app. A runner subsystem (CF Worker or native Rust CLI) executes CI retry commands on behalf of users.

```
GitHub ──webhook──> Server (CF Worker + D1) ──FCM──> Android App
                         ▲
                    Runner (CF Worker or native Rust) ── CI retry commands
```

## Repository Structure

Four independent components, each with its own build system:

| Component | Path | Stack |
|-----------|------|-------|
| Android app | `android/` | Kotlin, Jetpack Compose, Gradle |
| Push server | `server/` | TypeScript, Cloudflare Workers, D1 |
| Worker runner | `worker-runner/` | TypeScript, Cloudflare Workers |
| Native runner | `runner/` | Rust (2021 edition) |

## Build & Test Commands

### Android (`android/`)
```bash
cd android
./gradlew :core-domain:test          # Unit tests (JUnit 5, domain logic)
./gradlew :app:assembleRelease       # Build release APK
./gradlew :app:assembleDebug         # Build debug APK
```
- Requires JDK 21
- `local.properties` (gitignored) holds GitHub OAuth client ID and optional server URL
- `google-services.json` is required for Firebase/FCM (decoded from secret in CI)
- Two modules: `:app` (Android UI/services) and `:core-domain` (pure Kotlin domain logic)

### Server (`server/`)
```bash
cd server
npm ci
npx tsc --noEmit                     # Type check
npx vitest run                       # Run all tests
npx vitest run src/__tests__/webhook.test.ts  # Single test file
npx wrangler dev                     # Local dev server
npx wrangler d1 migrations apply ghpr-db --local  # Apply D1 migrations locally
```
- Node 20+
- Tests use Vitest with Cloudflare Workers pool (`vitest.config.ts`)
- Database schema in `schema.sql`, migrations in `migrations/`

### Worker Runner (`worker-runner/`)
```bash
cd worker-runner
npm ci
npx tsc --noEmit                     # Type check
npx vitest run                       # Run all tests
npx wrangler dev                     # Local dev (has cron trigger every 5 min)
```

### Native Runner (`runner/`)
```bash
cd runner
cargo build                          # Build
cargo test                           # Run tests
cargo run -- register                # Register runner
cargo run -- run                     # Start polling loop
cargo run -- status                  # Check registration
```
- Binary name: `ghpr-runner`
- Uses clap for CLI, tokio async runtime, reqwest for HTTP

## Architecture Details

### Android App
- **DI:** Manual dependency injection via `AppContainer` (initialized in `GhprApplication`)
- **UI:** Single-activity Compose app (`MainActivity`) with bottom navigation across screens: Open PRs, History, Subscriptions, Settings
- **Push:** `GhprFirebaseMessagingService` handles incoming FCM messages
- **Auth:** GitHub OAuth flow in `com.ghpr.app.auth`
- **Data layer:** Repositories and DataStore in `com.ghpr.app.data`

### Server (CF Worker)
- **Routing:** Manual request routing in `src/index.ts` (no framework)
- **Key endpoints:** `/github/webhook`, `/devices/*`, `/subscriptions/*`, `/mobile/sync`, `/runners/*`, `/commands/*`
- **Auth:** Firebase token verification for device endpoints; HMAC-SHA256 for webhook verification
- **Database:** Cloudflare D1 (SQLite-compatible) with 9 migration files
- **Rate limiting:** Custom implementation in `rate-limit.ts`

### Runner Subsystem
- Two implementations: CF Worker (`worker-runner/`) runs on cron every 5 min; native Rust CLI (`runner/`) runs as a local process
- Supported commands: `retry-ci`, `retry-flaky`
- Worker runner uses CF service bindings to communicate with the server

## CI/CD

GitHub Actions workflows in `.github/workflows/`, each scoped to its component's path:
- `android-ci.yml` — JDK 21, Gradle tests + APK build
- `server-ci.yml` — Node 20, type check + vitest
- `worker-runner-ci.yml` — Node 20, type check + vitest
- `runner-ci.yml` — Rust CI
