# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Install

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
export ANDROID_HOME="c:/Users/anderson/AppData/Local/Android/Sdk"
cd c:/Users/anderson/Projetos/HyperOS-Updater
./gradlew assembleDebug

# Xiaomi 17 Pro Max (popsicle)
adb -s 4d7fc9af install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 4d7fc9af shell am start -n com.hyperos.updater/.ui.MainActivity

# Redmi 12 (fire)
adb -s f10c4f767d7b install -r app/build/outputs/apk/debug/app-debug.apk
adb -s f10c4f767d7b shell am start -n com.hyperos.updater/.ui.MainActivity

# Logs
adb -s f10c4f767d7b logcat --pid=$(adb -s f10c4f767d7b shell pidof com.hyperos.updater)
```

## Architecture

Clean Architecture with package-separated layers in a single `:app` module.

- **data/** — Room entities/DAOs, Retrofit APIs, Jsoup scrapers, repository implementations
- **domain/** — Pure Kotlin: models, repository interfaces, use cases, installer abstraction
- **ui/** — Jetpack Compose: screens, ViewModels, navigation, components
- **di/** — Hilt modules (App, Network, Database, Installer, Repository)
- **worker/** — WorkManager workers + NotificationHelper
- **util/** — VersionComparator, XiaomiApps, ShizukuHelper, Extensions

Key pattern: `StateFlow` for UI state, `collectAsState()` in Composables, one-way data flow.

## Critical Discoveries

### Shizuku Manifest
Provider MUST NOT have `android:permission` — blocks binder delivery from Shizuku service.
Must add `<meta-data android:name="moe.shizuku.client.V3_SUPPORT" android:value="true" />`.
Permission `moe.shizuku.manager.permission.API_V23` goes on `<uses-permission>`, NOT on the provider.

### Shizuku Install (stdin pipe)
```
pm install -S <size> -r -d   (pipe APK content via stdin)
```
SELinux prevents `system_server` from reading files outside `/data/local/tmp/`.
`newProcess()` is private in Shizuku 13 — use reflection: `Shizuku::class.java.declaredMethods.firstOrNull { it.name == "newProcess" }`.

### APKPure Version Detection
Use `d.apkpure.com/b/APK/{pkg}?version=latest` with HEAD, `followRedirects(false)`, headers:
- `Referer: https://apkpure.com/`
- `Origin: https://apkpure.com`
- `User-Agent: Mozilla/5.0 ... Mobile Safari/537.36`
Parse version from 302 `Location` header's `filename=` parameter.

### APKMirror Search
Use WordPress search: `?s={query}&post_type=app_release` (NOT `?searchtype=apk&search=`).
User-Agent: `APKUpdater-v3.0.3`.

### Device Detection
HyperOS version is in `persist.sys.grant_version` (not `ro.miui.ui.version.name`).
getprop is heavily restricted on some devices — fallback to `Build.*` classes.

### Version Comparison
Use `VersionComparator.isNewer()` with semantic comparison — NOT `versionCode` from scrapers (unreliable).

### Download Flow
`DownloadManager` is `@Singleton` — downloads survive screen navigation.
Progress via `callbackFlow` with `trySend()` (not `flow {}` — violates Dispatchers.IO→Main invariant).
Emissions throttled to 200ms intervals with 64KB buffer.

## Known Issues
- Split APK install via Shizuku fails on HyperOS (`install-create`/`write`/`commit`)
- OTA API (`update.miui.com/updates/miotaV3.php`) returns HTTP 400
- APKCombo has Cloudflare protection blocking some requests
- `d.apkpure.com` returns 403 without proper Referer/Origin headers
