# KIMI.md

This file provides guidance to Kimi Code CLI when working with code in this repository.

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
  - **ui/screens/detail/** — AppDetailActivity (standalone, registered in manifest) + AppDetailViewModel + AppDetailScreen; opened via info button (ⓘ) on Updates/Search cards. Modes: installed-app (packageName + appType) and search-origin (SEARCH_* extras). Sections: header, "Status da Versão" (auto-recheck), "Versões por Fonte" (download per source row), "Histórico de versões" (collapsible per-source groups, history endpoints for MemeOS/F-Droid/GitHub/APKMirror), "Ações" (Pular/Ocultar/Verificar). Download routing mirrors tabs: MEMEOS resolve-direct/WebView fallback, APTOIDE/GITHUB/FDROID/TENCENT direct, APKMIRROR/APKCOMBO/APKPURE/UPTODOWN WebView.
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

### APKPure Search Selectors
Search page selectors updated to `.first` (featured result) + `#search-res li` (list items). Both require `Referer: https://apkpure.com/` and `Origin: https://apkpure.com` headers. Version extracted from `data-dt-version` attribute (featured) or `.version`/`.p2` text (list). `searchByName()` aggregates both into `List<SearchItem>`.

### Aggregated Multi-Source Search
`AppSearchViewModel.kt` groups flat results by normalized app name (exact match: lowercase, strip non-alphanumeric) into `AppSearchResult(appName, iconUrl, devName, hits: List<SourceHit>, displayVersion, bestSource)`. Cards show all source badges (one per `SourceHit`). Quick-download uses `bestSource` with priority: APTOIDE > MEMEOS > GITHUB > FDROID > TENCENT. Detail page receives `EXTRA_SEARCH_HITS` (pipe-joined `SOURCE|VERSION|URL`) and renders "Versões por Fonte" for search origin — every hit gets its own download row with appropriate routing.

Results are emitted incrementally as each source completes (not all-at-once), with `distinctBy { downloadPageUrl }` dedup. Each source fails soft — empty lists on error.

### App Detail — History Loads Unconditionally (v1.1.1)
`loadHistory()` is called for ALL installed apps, not just those with `sourceVersions`. The old code gated on `sourceVersions`, which only contains sources with a NEWER version — up-to-date apps got an empty history page. Now history always loads for MemeOS, F-Droid, GitHub, and APKMirror regardless of update status. Each source fails soft; unknown packages yield empty groups (no error shown).

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

### App Detail — History Per Source (Availability Matrix)

The detail page loads version history from dedicated endpoints, collapsed per source:

| Source | History method | Endpoint | Returns |
|--------|---------------|----------|---------|
| **MemeOS** | `MemeOsService.getAppHistory(pkg)` | `memeosupdates.com/apps/{pkg}` — HTML scrape of `version-item` divs | All versions: version, versionCode, region, date, sizeBytes, pageUrl. Each downloadable via `resolveDirectDownloadUrl` on the version page. |
| **F-Droid** | `FDroidService.getVersionHistory(pkg)` | `f-droid.org/api/v1/packages/{pkg}` → `packages[]` JSON array | All versions: versionName, versionCode, apkUrl (direct). |
| **GitHub** | `GitHubService.getReleaseHistory(pkg)` | `api.github.com/repos/{repo}/releases?per_page=20` | All releases: tag, name, publishedAt, apkUrl (first `.apk` asset). |
| **APKMirror** | `ApkMirrorService.getRecentVersions(appName)` | RSS feed via `searchByName` → slug → `fetchAppFeed` | Recent versions: version, pageUrl (WebView download). |
| **APKPure/APKCombo/Aptoide/Uptodown/Tencent** | — | — | Latest only + "abrir página de versões" link. |

History loading is fail-soft per source — one failure does not block others. Load is triggered only for sources that appear in `sourceVersions`. In search-origin mode: only APKMirror (RSS slug from page URL) and MemeOS (package-name from page URL) attempt history.

### Root Install waitFor-Before-Join
`su` stdin-pipe installs use `process.waitFor(120, SECONDS)` **before** joining reader threads. The old code joined stdout/stderr reader threads first, which block until EOF (process exit). If the process hangs (e.g. behind a Magisk grant prompt), the join hangs forever and the timeout is never reached. The fix: `waitFor(timeout)` → `destroyForcibly()` on timeout → then `join(5s)` reader threads. Same pattern in both `DownloadManager.rootInstallSingle()` and `RootApkInstaller`.

### Root Diagnosis (5 su candidates)
`RootApkInstaller.diagnoseAvailability(promptTimeoutSeconds, probeTimeoutSeconds)` probes 5 su candidates in order: `su`, `/system/bin/su`, `/system/xbin/su`, `/sbin/su`, `/su/bin/su`. The first candidate gets the long prompt timeout (60s from "Solicitar acesso root", 10s from auto/refresh) — enough for the user to answer a Magisk/KernelSU grant dialog. Remaining candidates are quick probes (5-8s). The `RootDiagnosis` result (per-candidate `OK`, exit code, stderr, or timeout) is shown in Settings as `rootDiagnosis` for on-device debugging. `checkAvailability()` calls `diagnoseAvailability(10, 5)` internally. KernelSU is confirmed working.

### APKCombo Always-WebView Rule
APKCombo NEVER downloads directly — `ApkComboResult.downloadUrl` = `<appPage>/download/apk`, a real page that 403s via plain OkHttp (Cloudflare) but works in WebView. All download paths (SearchTab, AppSearchScreen, UpdatesTab sourceVersions) route APKCOMBO into the WebView branch. `AppUpdatesViewModel.getSourcePageUrl` uses `sourceDownloadUrl` directly for APKCOMBO (no bogus search URL fallback). `AppSearchViewModel.downloadFromPage` has no explicit APKCOMBO branch (falls to `else → result.downloadPageUrl`) — no double-append of `/download/apk`.

**APKCombo name-search impossible:** The search endpoint `apkcombo.com/search/<query>` returns HTTP 403 (Cloudflare) for non-package-name queries. The guard `!query.contains(".")` in `tryComboSearch()` skips name-only queries because they would 403 silently. APKCombo is effectively **package-name-only** for all operations.

### Xiaomi.eu OTA (OS Updates tab)
SourceForge RSS `https://sourceforge.net/projects/xiaomi-eu-multilang-miui-roms/rss?path=/xiaomi.eu/HyperOS-STABLE-RELEASES/HyperOS3.0/`. XmlPullParser, filter by `_{CODENAME}_` in title. Version from filename regex `_OS(\d+)\.(\d+)\.(\d+)\.(\d+)_` → `OS{major}.{minor}.{patch}.{build}`. **Numeric-only compare:** `extractNumericParts()` extracts up to 4 leading numeric components; `isNumericNewer()` does lexicographic comparison on the 4-tuple. Explicitly does NOT use `VersionComparator` line-gate — xiaomi.eu suffixes differ from stock (they rebase China ROMs), but the 4 numeric components alone determine update-worthiness. ROM download is native OkHttp (SourceForge link 302→mirror), saved to Downloads/HyperOSUpdater, NO install step, NO WebView. Manual check only — OtaCheckWorker NOT scheduled.

### Tencent MyApp (应用宝) — 9th Source
Endpoint: `GET https://a.app.sj.qq.com/o/simple.jsp?pkgname=<pkg>`. HTML page with `window.systemData = {...}` JSON. Parse `appDetail.versionName`, `appDetail.apkUrl64` (preferred) / `appDetail.apkUrl`. Domain resolves only from Chinese networks — fail soft (any error returns null). `UpdateSource.TENCENT`. Direct-download source (`DIRECT_DOWNLOAD_SOURCES` includes TENCENT in AppCheckWorker; UpdatesTab `hasDirectUrl` includes TENCENT). No search integration.

### Wear OS Two-Layer Protection
Wear OS (relógio) variants are blocked in two independent layers to prevent accidental install of wrong builds:

**Layer 1 — Listing filter** (`WearOsDetector.isWearOsListing`):
Case-insensitive regex `(?i)\bwear[\s_]*os\b|\bwearos\b|\(wear\)|\bandroid[\s_]+wear\b|\bwear[\s_]+watch\b` — matches "Wear OS", "WearOS", "(Wear)", "Android Wear", "Wear Watch".
Applied at:
- `ApkMirrorService.searchByName()` — `.appRow` entries (line 59)
- `ApkMirrorService.parseRssFeed()` — RSS `<item>` titles (line 179)
- `AppSearchViewModel` — all 6 search sources filter on `appName` and `versionName` before grouping (lines 102-107)
- `AppDetailViewModel` — history lists (MemeOS, F-Droid, GitHub, APKMirror) filtered per-source (lines 284, 304, 324, 344, 376, 402)

**Layer 2 — Hard install guard** (`DownloadManager.isWearOsApk`):
After download completes (before install), the APK is checked:
1. `PackageManager.getPackageArchiveInfo()` → `reqFeatures` → any `FeatureInfo.name == "android.hardware.type.watch"`
2. Fallback: `WearOsDetector.scanApkForWearFeature()` — byte-level scan of `AndroidManifest.xml` for `android.hardware.type.watch` in UTF-8 and UTF-16LE encodings (Android binary AXML can use either)
3. For bundles (XAPK/APKM): recursively scans inner `.apk` entries' manifests
If detected → `DownloadStatus.ERROR` with message `"Este APK é para Wear OS (relógio), não para o telefone"` — install is BLOCKED. Fail-soft: any read error returns `false` (allows install).

22 unit tests in `WearOsDetectorTest`: 14 for listing regex (null/blank, positive cases, negative cases like "swear"/"wear" alone, case-insensitive, version strings), 8 for byte-scan (UTF-8/UTF-16LE marker found/not found, empty, partial marker, start/end of array, needle larger than haystack).

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

### Fast-Path de Fontes (Two-Phase Pipeline)
`checkOneThirdPartyApp` in `AppUpdateRepositoryImpl` uses a two-phase pipeline:

**Phase 1 (cheap JSON APIs, parallel):** Aptoide (`ws75.aptoide.com/api/7/getApp`), F-Droid (`f-droid.org/api/v1/packages`), GitHub (`api.github.com/repos/.../releases/latest`), Tencent (`a.app.sj.qq.com/o/simple.jsp`). These are simple HTTP+JSON calls with no parsing overhead.

**Phase 2 (HTML scrapers, parallel):** APKPure, APKCombo, APKMirror, MemeOS, Uptodown. Only runs when **every** phase-1 source returned `null` — i.e. the app is unknown to all JSON APIs. These scrapers are ~4-8× slower than JSON APIs because they involve Jsoup HTML parsing and often multiple HTTP round-trips.

**Trade-off documented in KDoc:** when an API resolves the app, scrapers are skipped entirely — less cross-checking in that specific case. In practice the JSON APIs (especially F-Droid and Aptoide) cover the vast majority of packages.

`Log.d("AppUpdateRepo", ...)` shows `Phase 2 scraping for <pkg> — no API source knows this app` or `Phase 2 skipped for <pkg> — found in <sources>` per package. System apps (MemeOs catalog) are untouched — they still use `checkOneSystemApp`.

### OkHttp Tuning
`NetworkModule.kt` configures a shared `OkHttpClient` (also used by `DownloadUpdateUseCase` for large APK downloads):

| Setting | Value | Rationale |
|---------|-------|-----------|
| `ConnectionPool` | 32 idle connections, 5 min keep-alive | 4× larger pool for 6 concurrent apps × multiple sources |
| `Dispatcher.maxRequestsPerHost` | 16 | Up from default 5 — more parallelism to the same host |
| `connectTimeout` | 10 s | Tight — dead hosts fail fast |
| `readTimeout` | 15 s | Gap timeout: a download that is continuously streaming is fine; a scraper that stalls mid-response dies in 15 s. Changed from 120 s — 120 s was too lenient for hung scrapers |
| `writeTimeout` | 15 s | Symmetric with read |
| `Cache` | 20 MiB on-disk (`http_cache`) | Caches HTTP responses — repeated metadata calls (same package across scans) hit the cache |
| **NO `callTimeout`** | — | `callTimeout` applies to the ENTIRE call including body streaming. A 180 MB APK on a slow network could exceed any reasonable global timeout. The per-read-gap `readTimeout` (15 s) is safe for downloads while still preventing hung scrapers from stalling forever. **Intentionally omitted.** |

The 120 s → 15 s `readTimeout` change does NOT kill slow-but-continuous downloads — `readTimeout` is per-read-gap, not cumulative. Only true stalls (no bytes for 15 s) are killed.

## Known Issues
- OTA code still exists but is unwired from v1 (OTA tab removed, `ota_check` worker cancelled in `WorkerScheduler`)
- Xiaomi GetApps (`app.market.xiaomi.com/apm/app`) evaluated and NOT added — requires undisclosed params/signing (HTTP 400 "参数不能为空"); would need MITM reverse engineering
- APKCombo: download page works in WebView but 403s via plain OkHttp — always routed through WebView (see Critical Discoveries); **name-search impossible** — `apkcombo.com/search/<name>` returns Cloudflare 403, package-name only
- `d.apkpure.com` returns 403 without proper Referer/Origin headers
- Uptodown: no reliable package-name→URL mapping; **search endpoint DEAD** — all known URL patterns (`/android/search/<q>`, `/search?q=<q>`, `/android/buscar/<q>`) return HTTP 404/410; the site appears to have removed/relocated its search feature; service code kept intact but non-functional for search
- MemeOS search returns empty for non-Xiaomi names — expected, catalog is Xiaomi system apps only
- Root install via su may hang at Magisk grant prompt (120s timeout mitigates this)
