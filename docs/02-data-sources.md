# Data Sources

## OTA ROM Updates

**Endpoint:** `POST https://update.miui.com/updates/miotaV3.php`

**Parameters:**
| Param | Value | Description |
|-------|-------|-------------|
| `d` | popsicle | Device codename |
| `b` | F | Branch (F=Stable, X=Beta) |
| `c` | CN | Region (CN=China) |
| `v` | OS3.0.306.0.WPBCNXM | Current ROM version |
| `is_global` | 0 | China ROM flag |
| `r` | CN | Region code |
| `pn` | popsicle | Product name |

**Response:** JSON with version, filesize, md5, changelog, CDN URLs (ultimateota, superota, cdnorg, aliyuncs).

**Status:** HTTP 400 for popsicle. API may not support this codename yet.

**Version detection:** Uses `persist.sys.grant_version` getprop (device restricts standard `ro.*` properties).

---

## App Updates

### APKPure
- **Search:** `GET https://apkpure.com/search?q={packageName}`
- **CDN Download:** `https://d.apkpure.com/b/APK/{packageName}?version=latest`
- **Parsing:** Jsoup HTML scraping
- **Issues:** Returns 403 for system packages. Search by name is JS-rendered.

### APKCombo
- **Search:** `GET https://apkcombo.com/search/{packageName}`
- **Parsing:** Jsoup HTML scraping
- **Issues:** Cloudflare protection on some requests. Download requires JS.

### APKMirror
- **Search by name:** `GET https://www.apkmirror.com/?s={query}&post_type=app_release`
- **User-Agent:** `APKUpdater-v3.0.3` (special UA agreed with APKMirror)
- **Parsing:** `.appRow` > `h5.appRowTitle` > `a.fontBlack`
- **Download:** Requires WebView (JS generates CDN URL). Captured via `DownloadListener`.

---

## Version Comparison

Uses `VersionComparator.isNewer(currentVersion, newVersion)`:
- MIUI/HyperOS versions: compares numeric segments (OS3.0.400.0 > OS3.0.306.0)
- Standard apps: semantic version comparison (5.18.5.5 > 5.18.4.0)
- Does NOT use `versionCode` from APKPure/APKCombo (unreliable)

---

## Device Detection

`DeviceRepositoryImpl` reads:
1. `Build.DEVICE` → codename (popsicle)
2. `Build.MODEL` → marketing name
3. `persist.sys.grant_version` → HyperOS version (OS3.0.306.0.WPBCNXM)
4. `Build.VERSION.SDK_INT` → Android SDK (36)
5. Region derived from version suffix (CNXM → China)
