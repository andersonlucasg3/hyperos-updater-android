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

- **data/** — Room entities/DAOs, Retrofit APIs, Jsoup scrapers (8 sources), repository implementations
- **domain/** — Pure Kotlin: models, repository interfaces, use cases, installer abstraction (Root/PackageManager)
- **ui/** — Jetpack Compose: 3 tabs (Find & Install, Updates, Settings), ViewModels, navigation, components
- **di/** — Hilt modules (App, Network, Database, Installer, Repository)
- **worker/** — WorkManager workers (AppCheckWorker only in v1) + NotificationHelper
- **util/** — VersionComparator (with versionName-first `compare`), XiaomiApps, Extensions

Key pattern: `StateFlow` for UI state, `collectAsState()` in Composables, one-way data flow.

## Critical Discoveries

### ~~Shizuku~~ (REMOVED from app)
Shizuku was completely removed. The manifest provider, `moe.shizuku.manager.permission.API_V23` permission, V3_SUPPORT meta-data, `newProcess()` reflection, stdin-pipe install via Shizuku, `ShizukuStatusIcon`, `ShizukuStatusBanner`, `ShizukuHelper`, and the `shizuku_enabled` DataStore pref have all been deleted. Root (`su`) is now the only privileged install method; fallback is PackageInstaller.Session → Intent.

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

### Root Install (su stdin pipe)
Only privileged install method in v1. Uses `su` with `pm install -S <size> -r -d -i com.android.vending` and stdin pipe for APK data.
Both `RootApkInstaller` and `DownloadManager.rootInstallSingle()` use 120s `waitFor` timeouts — Magisk may show a grant prompt that hangs if not dismissed.
**Critical:** `waitFor(timeout)` must be called **before** joining reader threads (stdout/stderr). Joining first hangs forever if the process is stuck — the timeout is never reached. See "Root Install waitFor-Before-Join" below.
`-i com.android.vending` makes Android believe the APK came from Play Store, avoiding some system restrictions.

### Aptoide API v7
Public JSON API. Version check: `GET https://ws75.aptoide.com/api/7/getApp?package_name=<pkg>` → JSON path `nodes.meta.data.file.{vername,vercode,path}`.
Search: `GET https://ws75.aptoide.com/api/7/apps/search?query=<q>&limit=25`.
`file.path` is a direct APK download URL — no WebView needed. This makes Aptoide one of the DIRECT_DOWNLOAD_SOURCES for auto-update.

### MemeOS Direct Download (no countdown)
`MemeOsService.resolveDirectDownloadUrl(versionPageUrl)` bypasses the 20-second countdown with 2 plain HTTP GETs:
1. GET the version page (e.g. `.../apps/{pkg}/{versionCode}`) → regex-extract `data-download-url` (dl=0 preferred, dl=1 fallback).
2. GET that URL with `Referer` set to the version page → regex-extract `https://download.memeosupdates.com/…` signed URL.
The signed URL serves the APK directly (`application/vnd.android.package-archive`, ~99MB). `exp` is an expiry timestamp — download soon after resolving.
Callers in UI (UpdatesTab, SearchTab, AppSearchScreen) try resolution first; on null they fall back to WebView. AppCheckWorker resolves before silent download; on null it skips (same as other "requires manual download" sources).

### pickBest Version Comparator
`pickBest()` in `AppUpdateRepositoryImpl` uses `VersionComparator.compare(versionNameA, versionCodeA, versionNameB, versionCodeB)`.
VersionName-first: `isNewer(X,Y)` means "Y is newer than X". `compare(A,B)` returns positive if A is newer, negative if B is newer.
versionCode is only a tiebreaker when BOTH codes > 0. This prevents unreliable scraped codes from overriding semantic version comparison.

### Version-Line Gate (isSameLine)
**versionCode must never cross version lines.** `VersionComparator.isSameLine(a, b)` is a public function that checks whether two versionName strings share the same qualifier (after case-folding and separator normalisation). Different lines (e.g. `0.0.0` vs `0.0.0-R`, `-global` vs `-cn`) are **incomparable** — `isNewer` returns `false` both ways, and `compare` returns `0`.
This gate is applied in **every** update-decision path:
- `checkSystemAppUpdates` (MemeOs): `hasUpdate = sameLine && (semanticNewer || (codeNewer && !semanticOlder))` — even high versionCodes from a different line never trigger an update.
- Third-party F-Droid path: `fdroidResult.versionCode > app.versionCode` only counts when `isSameLine(installed, fdroid.versionName)`.
- `isNewer` itself gates on line internally, so `sourceVersions.filter { isNewer(...) }` is line-safe.
Concrete rule: `0.0.0` vs `0.0.0-R` (or `-global` vs `-cn`) are different lines → never offered as updates.

### XAPK/APKM Content Detection (adjustArchiveType)
CDN URLs often lack file extensions, so a `.apk`-named file after download may actually be a split-APK bundle (XAPK/APKM). `DownloadManager.adjustArchiveType()` opens the downloaded file as a ZIP and checks: if there are inner `.apk` entries AND no root `AndroidManifest.xml`, it's a bundle → renames to `.xapk` → routes to `installSplitApk()`. A real single APK always has `AndroidManifest.xml` at the ZIP root. This fixes bundles arriving from extension-less CDN URLs.

### Scan Scoping: `.first()` to Avoid Eagerly Race
`AppUpdatesViewModel.checkAllApps()` reads `preferencesRepository.showSystemApps.first()` (suspending, reads current DataStore value) instead of using the `showSystemApps` StateFlow directly. The StateFlow is `SharingStarted.Eagerly` with initial value `true`, but on cold start DataStore hasn't emitted yet — using the StateFlow would race the auto-scan and always include system apps on first open even when the user had them off. `.first()` suspends until DataStore emits the real persisted value.

When "Sistema" is off, the scan only launches the third-party job (system job is `null`). Stale system entries from a previous scan are kept in the list (not removed) because the removal pass only considers app types that were in the current scan scope.

### Self-Update via GitHub Releases
`SelfUpdateService` checks `api.github.com/repos/andersonlucasg3/hyperos-updater-android/releases/latest` (public repo). Settings has a "Atualização do app" section with a manual "Verificar atualização" button — there is no periodic worker for self-update. States: `Idle`, `Checking`, `UpToDate`, `Available` (release with `.apk` asset), `Error`, `NoRelease`. When `Available`, download uses `DownloadManager.startDownload()` with the fixed key `"SELFUPDATE"` — the root install chain handles it (the APK is HyperOS-Updater itself, installed via `pm install -r`). Tags are expected like `v1.0.1`; the leading `v`/`V` is stripped by `SelfUpdateService`. Each release must have at least one `.apk` asset.

### Root Install waitFor-Before-Join
`su` stdin-pipe installs use `process.waitFor(120, SECONDS)` **before** joining reader threads. The old code joined stdout/stderr reader threads first, which block until EOF (process exit). If the process hangs (e.g. behind a Magisk grant prompt), the join hangs forever and the timeout is never reached. The fix: `waitFor(timeout)` → `destroyForcibly()` on timeout → then `join(5s)` reader threads. Same pattern in both `DownloadManager.rootInstallSingle()` and `RootApkInstaller`.

### Root Diagnosis (5 su candidates)
`RootApkInstaller.diagnoseAvailability(promptTimeoutSeconds, probeTimeoutSeconds)` probes 5 su candidates in order: `su`, `/system/bin/su`, `/system/xbin/su`, `/sbin/su`, `/su/bin/su`. The first candidate gets the long prompt timeout (60s from "Solicitar acesso root", 10s from auto/refresh) — enough for the user to answer a Magisk/KernelSU grant dialog. Remaining candidates are quick probes (5-8s). The `RootDiagnosis` result (per-candidate `OK`, exit code, stderr, or timeout) is shown in Settings as `rootDiagnosis` for on-device debugging. `checkAvailability()` calls `diagnoseAvailability(10, 5)` internally. KernelSU is confirmed working.

### APKCombo Always-WebView Rule
APKCombo NEVER downloads directly — `ApkComboResult.downloadUrl` = `<appPage>/download/apk`, a real page that 403s via plain OkHttp (Cloudflare) but works in WebView. All download paths (SearchTab, AppSearchScreen, UpdatesTab sourceVersions) route APKCOMBO into the WebView branch. `AppUpdatesViewModel.getSourcePageUrl` uses `sourceDownloadUrl` directly for APKCOMBO (no bogus search URL fallback). `AppSearchViewModel.downloadFromPage` has no explicit APKCOMBO branch (falls to `else → result.downloadPageUrl`) — no double-append of `/download/apk`.

### Xiaomi.eu OTA (OS Updates tab)
SourceForge RSS `https://sourceforge.net/projects/xiaomi-eu-multilang-miui-roms/rss?path=/xiaomi.eu/HyperOS-STABLE-RELEASES/HyperOS3.0/`. XmlPullParser, filter by `_{CODENAME}_` in title. Version from filename regex `_OS(\d+)\.(\d+)\.(\d+)\.(\d+)_` → `OS{major}.{minor}.{patch}.{build}`. **Numeric-only compare:** `extractNumericParts()` extracts up to 4 leading numeric components; `isNumericNewer()` does lexicographic comparison on the 4-tuple. Explicitly does NOT use `VersionComparator` line-gate — xiaomi.eu suffixes differ from stock (they rebase China ROMs), but the 4 numeric components alone determine update-worthiness. ROM download is native OkHttp (SourceForge link 302→mirror), saved to Downloads/HyperOSUpdater, NO install step, NO WebView. Manual check only — OtaCheckWorker NOT scheduled.

### Tencent MyApp (应用宝) — 9th Source
Endpoint: `GET https://a.app.sj.qq.com/o/simple.jsp?pkgname=<pkg>`. HTML page with `window.systemData = {...}` JSON. Parse `appDetail.versionName`, `appDetail.apkUrl64` (preferred) / `appDetail.apkUrl`. Domain resolves only from Chinese networks — fail soft (any error returns null). `UpdateSource.TENCENT`. Direct-download source (`DIRECT_DOWNLOAD_SOURCES` includes TENCENT in AppCheckWorker; UpdatesTab `hasDirectUrl` includes TENCENT). No search integration.

### Download Hardening Rules
- `DownloadUpdateUseCase`: throws on non-2xx (`HTTP ${code}`) and on `text/html` content-type ("Got HTML page instead of APK").
- `DownloadProgress.errorMessage` surfaced in card UI as "Erro: ..." (red labelSmall) for ERROR status.
- `installCached`: validates with `getPackageArchiveInfo` + requires COMPLETED/AWAITING_INSTALL status — no more blind dir scan for orphan APKs.
- `startDownload`: deletes existing file if not a valid APK (`getPackageArchiveInfo` null → re-download).
- `buildApkFileName(url, appName, version)`: uses the URL's own filename when meaningful (archive extension + non-generic base name); otherwise builds `<AppName>-<version>.<ext>`. Query stripped before extension detection. Generic names (download, file, apk, index, redirect, dl, get, downloaded), short lengths (≤3 chars), all-numeric, and raw hashes (≥24 chars alphanum) trigger the app-name fallback.

### WebView Assisted-Mode Header Replay
`DownloadActivity` is passive: user navigates freely, the app captures the download URL via `DownloadListener`, JS injection (fetch/XHR interception), and `shouldOverrideUrlLoading`.
Returns `EXTRA_DOWNLOAD_URL` + `EXTRA_REFERER` + `EXTRA_USER_AGENT` + `EXTRA_COOKIE` to caller.
Caller replays these headers in OkHttp via `DownloadManager.startDownload(headers)` → `DownloadUpdateUseCase.download(headers)`.

**Strict capture filter (`isDownloadUrl`):** Never capture on loose substring matches like `/download` or `cdn` — the old `_isDl()` matched the APKCombo page URL itself (`.../download/apk`) and XHR API endpoints, poisoning `window._apkm_dl_url` and causing premature capture (OkHttp would download an HTML page → 403). The fix: a single strict predicate (Kotlin + mirrored JS) — URL path (query/fragment stripped) must end in `.apk/.apkm/.xapk/.apks/.aab`, or host must be a known file-CDN (`cloudflarestorage.com`, `d.apkpure.com`, `downloadr`). Applied at every capture entry point (JS `_isDl()`, XHR JSON-sniff, `onPageStarted`/`onPageFinished` JS-result checks, `shouldOverrideUrlLoading`, `DownloadListener`). Also fixes signed query strings (`...apk?sig=...`) that bare `endsWith` missed — query is stripped before the extension check.

### Auto-Update Worker Constraints
`AppCheckWorker`: OFF mode = notify only. ON mode = silent download+install via Root only (never Intent fallback in background).
Only sources with direct APK URLs are eligible: `DIRECT_DOWNLOAD_SOURCES = {APTOIDE, GITHUB, FDROID, MEMEOS}`.
APKMirror and Uptodown are skipped — they require intermediate pages or WebView.
MEMEOS provides direct signed URLs via `MemeOsService.resolveDirectDownloadUrl()` (two HTTP GETs, bypasses the 20-second countdown).

## Known Issues
- OTA code still exists but is unwired from v1 (OTA tab removed, `ota_check` worker cancelled in `WorkerScheduler`)
- Xiaomi GetApps (`app.market.xiaomi.com/apm/app`) evaluated and NOT added — requires undisclosed params/signing (HTTP 400 "参数不能为空"); would need MITM reverse engineering
- APKCombo: download page works in WebView but 403s via plain OkHttp — always routed through WebView (see Critical Discoveries)
- `d.apkpure.com` returns 403 without proper Referer/Origin headers
- Uptodown has no reliable package-name→URL mapping — search-based only, best-effort
- Root install via su may hang at Magisk grant prompt (120s timeout mitigates this)
