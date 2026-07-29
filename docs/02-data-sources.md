# Data Sources

## OTA ROM Updates (xiaomi.eu via RSS)

> **Nota (v1):** Aba "OS Updates" restaurada. Fonte: RSS do xiaomi.eu no SourceForge. Ver docs/01-ota-updates.md.
> A API antiga da Xiaomi (`update.miui.com`) não é mais usada — retornava HTTP 400 para popsicle.

**Fonte:** RSS `https://sourceforge.net/projects/xiaomi-eu-multilang-miui-roms/rss?path=/xiaomi.eu/HyperOS-STABLE-RELEASES/HyperOS3.0/`

**Version detection:** `persist.sys.grant_version` getprop. Comparação numérica de 4 componentes (sem line-gate).

**Status:** Funcional. Download nativo OkHttp (SourceForge → mirror), sem WebView, sem instalação automática.

---

## App Updates

### APKPure
- **Search:** `GET https://apkpure.com/search?q={packageName}`
- **CDN Download:** `https://d.apkpure.com/b/APK/{packageName}?version=latest`
- **Parsing:** Jsoup HTML scraping
- **Issues:** Returns 403 for system packages. Search by name is JS-rendered.

### APKCombo
- **Search:** `GET https://apkcombo.com/search/{packageName}`
- **Parsing:** Jsoup HTML scraping, JSON-LD `softwareVersion`
- **downloadUrl semantics:** `ApkComboResult.downloadUrl` = `<appPage>/download/apk` — this is a **real page** (not a direct APK URL). It 403s via plain OkHttp (Cloudflare protection) but works in the assisted WebView. APKCombo is **never** downloaded directly; all download paths route it through `DownloadActivity`.
- **Issues:** No direct APK URL available. Always requires WebView.

### APKMirror
- **Search by name:** `GET https://www.apkmirror.com/?s={query}&post_type=app_release`
- **User-Agent:** `APKUpdater-v3.0.3` (special UA agreed with APKMirror)
- **Parsing:** `.appRow` > `h5.appRowTitle` > `a.fontBlack`
- **Download:** Requires WebView (JS generates CDN URL). Captured via assisted `DownloadActivity`.

### Aptoide (API v7)
- **Version check:** `GET https://ws75.aptoide.com/api/7/getApp?package_name={pkg}`
- **Search:** `GET https://ws75.aptoide.com/api/7/apps/search?query={q}&limit=25`
- **JSON path:** `nodes.meta.data.file.{vername,vercode,path,filesize}`
- **Key advantage:** `file.path` is a direct APK download URL — no scraping/WebView needed. Eligible for auto-update.
- **Issues:** Not all packages are listed; smaller catalog than APKPure.

### F-Droid
- **Version check:** `GET https://f-droid.org/api/v1/packages/{packageName}`
- **Parsing:** JSON REST API (`suggestedVersionCode`, `packages[].apk`)
- **Key advantage:** Real `versionCode` from APK metadata. Eligible for auto-update.
- **Issues:** Only FOSS apps. Many proprietary apps not listed.

### GitHub
- **Version check:** `GET https://api.github.com/repos/{owner}/{repo}/releases/latest`
- **Mapping:** Hardcoded package→repo map for popular FOSS apps (VLC, NewPipe, Termux, etc.)
- **Key advantage:** Direct APK download URL from release assets. Eligible for auto-update.
- **Issues:** Limited to mapped repos. Rate-limited without token.

### Uptodown (best-effort)
- **Search:** `GET https://www.uptodown.com/android/search/{query}`
- **Parsing:** Jsoup HTML scraping (multiple selector strategies)
- **Version extraction:** JSON-LD structured data → CSS selectors → regex fallback
- **Issues:** No reliable package-name→URL mapping. Search uses last segment of package name as query. Results are inherently unreliable. NOT eligible for auto-update.

### Tencent MyApp (应用宝)
- **Version check:** `GET https://a.app.sj.qq.com/o/simple.jsp?pkgname={pkg}`
- **Parsing:** HTML page with `window.systemData = {...}` JSON. Extracts `appDetail.versionName`, `appDetail.apkUrl64` (preferred 64-bit APK).
- **Key advantage:** Direct APK download URL. Eligible for auto-update.
- **Issues:** Domain resolves only from Chinese networks — fail soft (any error returns null). No search integration.

### Xiaomi GetApps (avaliado, NÃO adicionado)
- `app.market.xiaomi.com/apm/app` retorna HTTP 400 "参数不能为空" — requer parâmetros/assinatura não documentados.
- Precisaria de MITM reverse engineering para descobrir o protocolo.
- Documentado como possibilidade futura.

---

## Version Comparison

Uses `VersionComparator.isNewer(currentVersion, newVersion)`:
- MIUI/HyperOS versions: compares numeric segments (OS3.0.400.0 > OS3.0.306.0)
- Standard apps: semantic version comparison (5.18.5.5 > 5.18.4.0)
- Non-numeric segments are dropped; shorter versions are zero-padded

`VersionComparator.compare(versionNameA, versionCodeA, versionNameB, versionCodeB)`:
- versionName-first via `isNewer()` — code is only a tiebreaker when BOTH > 0
- Used by `pickBest()` to select the best update across all sources
- APKCombo is excluded from `pickBest` unless it's the only source (listings often lack real download URLs)

---

## Device Detection

`DeviceRepositoryImpl` reads:
1. `Build.DEVICE` → codename (popsicle)
2. `Build.MODEL` → marketing name
3. `persist.sys.grant_version` → HyperOS version (OS3.0.306.0.WPBCNXM)
4. `Build.VERSION.SDK_INT` → Android SDK (36)
5. Region derived from version suffix (CNXM → China)
