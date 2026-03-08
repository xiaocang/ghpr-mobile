# Android Client

This directory now contains a real Gradle-based starter project for mobile development.

## Current status

- Gradle project initialized (`settings.gradle`, module setup)
- `core-domain` module implemented for refresh decision logic
- push payload parser for FCM `data` messages (`pr_update`)
- push handling use-case that marks refresh pending only for valid payloads
- in-memory delivery deduplication to ignore repeated push deliveries
- Unit tests added for open-refresh interval gating and push-triggered refresh behavior
- DataStore-ready persistence abstractions added for `lastRefreshAt` and refresh settings in `core-domain`

## Why start with `core-domain`

The app's critical behavior can be developed and tested without Android SDK coupling:

- Refresh on app open
- Minimum refresh interval guard
- Push-event override (force refresh when pending)
- Manual refresh override

## Module layout

- `core-domain`: pure Kotlin business logic + unit tests
- `app` (next): Compose UI + ViewModel wiring + FCM integration

## Run tests

Use JDK 21 for Gradle runtime and toolchain consistency.


```bash
cd android
./gradlew :core-domain:test
```

## Next implementation steps

1. Add Android `app` module (Compose + Navigation)
2. Implement DataStore-backed stores in app module for `LastRefreshStore` and `RefreshSettingsStore`
3. Wire FCM receiver/service to call `markPushEventReceived`
4. Add diagnostics screen for notification readiness
