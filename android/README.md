# Android Client

The app connects to the official server (`ghpr-server.xiaocang.workers.dev`) by default — no server setup needed.

## Prerequisites

- Android Studio (latest stable)
- JDK 21

## Configuration

Create `android/local.properties` (gitignored) with your GitHub OAuth App client ID:

```properties
github.clientId=YOUR_GITHUB_OAUTH_CLIENT_ID
```

To use a self-hosted server, also add:

```properties
ghpr.serverUrl=https://<your-server-worker>.workers.dev
```

## Build

Open the project in Android Studio, or build from the command line:

```bash
cd android
./gradlew assembleDebug
```

## Run tests

```bash
cd android
./gradlew :core-domain:test
```
