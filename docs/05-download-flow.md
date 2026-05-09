# Download & Install Flow

## Architecture

Downloads are handled by `DownloadManager` (`@Singleton`), not individual ViewModels. This ensures downloads survive screen navigation.

```
User taps ⬇
  ↓
AppSearchViewModel / AppUpdatesViewModel
  ↓
DownloadManager.startDownload(url, filename, key, appName)
  ↓
CoroutineScope(IO).launch → DownloadUpdateUseCase.download()
  ↓
callbackFlow emits DownloadProgress every 200ms
  ↓
DownloadManager updates StateFlow<Map<String, ActiveDownload>>
  ↓
UI collects and shows progress
  ↓
Download complete → installApk(file) or installSplitApk(file)
  ↓
Shizuku pm install (stdin pipe) or PackageInstaller Intent
  ↓
Status: INSTALLING → COMPLETED or ERROR
```

## APKMirror Download (Search Results)

APKMirror requires JavaScript to generate the actual CDN URL. The flow:

1. User taps ⬇ on search result
2. `DownloadActivity` opens (WebView loading APKMirror download page)
3. User taps "Download APK" button on the page
4. WebView's `DownloadListener` captures the Cloudflare R2 CDN URL
5. Activity returns URL → `downloadLauncher` callback → `DownloadManager.startDownload()`
6. CDN URL is time-limited (AWS S3 signed URL, ~1 hour)

## UI States

| State | Icon | Action |
|-------|------|--------|
| PREPARING | ⬇ | Cancel |
| DOWNLOADING | Progress bar + % + speed | Cancel |
| INSTALLING | 🔄 | - |
| COMPLETED | ✓ | Dismiss |
| ERROR | ✗ | Dismiss / Retry |
| CANCELLED | ✗ | Dismiss |

## Progress Reporting

- Buffer size: 64KB (optimized for network I/O)
- Emission interval: every 200ms (avoids UI thread flooding)
- Speed calculation: `(bytes_new - bytes_old) / elapsed_time`

## Cancellation

`downloadJob.cancel()` cancels the coroutine. The partial file is deleted on error/cancel.
