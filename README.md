# HyperOS Updater

Android app for Xiaomi devices running HyperOS that manages app and system updates.

**Target device:** Xiaomi 17 Pro Max (popsicle), HyperOS 3.1 China ROM, Android 16 (API 36).

## Features

- **OTA System Updates** — checks Xiaomi's official API for HyperOS ROM updates
- **App Updates** — scans installed apps and finds updates via APKPure + APKCombo
- **Find & Install** — searches APKMirror by app name, downloads and installs APKs
- **Shizuku Integration** — silent install via `pm install` (requires Shizuku setup)
- **Download Manager** — persistent downloads with progress, speed, cancel
- **Background Checks** — WorkManager periodic tasks (OTA every 12h, apps every 24h)
- **Material 3 UI** — dynamic color, dark mode, expandable cards

## Architecture

- **Language:** Kotlin 2.1 + Jetpack Compose
- **DI:** Hilt 2.53 (KSP)
- **Networking:** Retrofit 2.11 + Moshi 1.15 + OkHttp 4.12
- **Database:** Room 2.7
- **Background:** WorkManager 2.10
- **Scraping:** Jsoup 1.18
- **Installation:** Shizuku 13.1.5 API + PackageInstaller fallback
- **Architecture:** Clean Architecture (data / domain / ui layers)

See [docs/](docs/) for detailed documentation.

## Requirements

- Android 12+ (API 31)
- Xiaomi device running HyperOS / MIUI
- Shizuku (optional, for silent APK installation)

## Setup

### Build
```bash
./gradlew assembleDebug
```

### Shizuku (for silent install)
1. Install Shizuku from [shizuku.rikka.app](https://shizuku.rikka.app)
2. Start Shizuku via wireless debugging
3. Open HyperOS Updater → Settings → Shizuku → Grant Permission
4. Status should show "Connected" (green shield icon)

## Current Status (May 2026)

### Working
- App scanning and update detection via APKPure + APKCombo
- APKMirror search by app name
- APK download with progress tracking
- Single APK install via Shizuku (stdin pipe method)
- Persistent download manager across screens
- Shizuku binder connection and status detection

### Known Issues
- **Split APK install via Shizuku:** `pm install-create/install-write/install-commit` session fails on HyperOS
- **OTA API:** `update.miui.com` returns HTTP 400 for popsicle codename
- **APKPure:** returns HTTP 403 for many system packages
- **APKCombo:** Cloudflare protection blocks some requests
- **APKMirror download:** requires WebView with user interaction to capture CDN URL

## License

MIT
