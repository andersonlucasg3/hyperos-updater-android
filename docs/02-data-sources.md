# Data Sources

## OTA ROM Updates (xiaomi.eu via RSS)

> **Nota (v1):** Aba "OS Updates" restaurada. Fonte: RSS do xiaomi.eu no SourceForge. Ver docs/01-ota-updates.md.
> A API antiga da Xiaomi (`update.miui.com`) não é mais usada — retornava HTTP 400 para popsicle.

**Fonte:** RSS `https://sourceforge.net/projects/xiaomi-eu-multilang-miui-roms/rss?path=/xiaomi.eu/HyperOS-STABLE-RELEASES/HyperOS3.0/`

**Version detection:** `persist.sys.grant_version` getprop. Comparação numérica de 4 componentes (sem line-gate).

**Status:** Funcional. Download nativo OkHttp (SourceForge → mirror), sem WebView, sem instalação automática.

---

## App Updates

As fontes de terceiros são divididas em duas fases no `checkOneThirdPartyApp`:

- **Phase 1 (APIs JSON baratas):** Aptoide, F-Droid, GitHub, Tencent — executadas sempre em paralelo
- **Phase 2 (HTML scrapers):** APKPure, APKCombo, APKMirror, MemeOS, Uptodown — só executadas quando **nenhuma** fonte da fase 1 conhece o app

### APKPure *(Phase 2 — HTML scraper)*
- **Search:** `GET https://apkpure.com/search?q={packageName}` com headers `Referer` + `Origin`
- **Search selectors:** `.first` (featured result, atributo `data-dt-version`) + `#search-res li` (list items, classe `.version`/`.p2`); ambos exigem `Referer: https://apkpure.com/` e `Origin: https://apkpure.com`
- **CDN Download:** `https://d.apkpure.com/b/APK/{packageName}?version=latest`
- **Parsing:** Jsoup HTML scraping
- **Issues:** Returns 403 for system packages. Search by name is JS-rendered.

### APKCombo *(Phase 2 — HTML scraper)*
- **Search:** `GET https://apkcombo.com/search/{packageName}` — **apenas package-name** (busca por nome retorna HTTP 403 Cloudflare; o guard `!query.contains(".")` em `tryComboSearch()` impede queries só com nome)
- **Parsing:** Jsoup HTML scraping, JSON-LD `softwareVersion`
- **downloadUrl semantics:** `ApkComboResult.downloadUrl` = `<appPage>/download/apk` — this is a **real page** (not a direct APK URL). It 403s via plain OkHttp (Cloudflare protection) but works in the assisted WebView. APKCombo is **never** downloaded directly; all download paths route it through `DownloadActivity`.
- **Issues:** No direct APK URL available. Always requires WebView. Name-search impossible — Cloudflare 403.

### APKMirror *(Phase 2 — HTML scraper)*
- **Search by name:** `GET https://www.apkmirror.com/?s={query}&post_type=app_release`
- **User-Agent:** `APKUpdater-v3.0.3` (special UA agreed with APKMirror)
- **Parsing:** `.appRow` > `h5.appRowTitle` > `a.fontBlack`
- **Download:** Requires WebView (JS generates CDN URL). Captured via assisted `DownloadActivity`.
- **Wear OS filter:** Search results and RSS feed items are filtered by `WearOsDetector.isWearOsListing()` — variants with "Wear OS", "WearOS", "(Wear)", "Android Wear", or "Wear Watch" in the title are excluded at the source level.

### Aptoide *(Phase 1 — JSON API)* (API v7)
- **Version check:** `GET https://ws75.aptoide.com/api/7/getApp?package_name={pkg}`
- **Search:** `GET https://ws75.aptoide.com/api/7/apps/search?query={q}&limit=25`
- **JSON path:** `nodes.meta.data.file.{vername,vercode,path,filesize}`
- **Key advantage:** `file.path` is a direct APK download URL — no scraping/WebView needed. Eligible for auto-update.
- **Issues:** Not all packages are listed; smaller catalog than APKPure.

### F-Droid *(Phase 1 — JSON API)*
- **Version check:** `GET https://f-droid.org/api/v1/packages/{packageName}`
- **Parsing:** JSON REST API (`suggestedVersionCode`, `packages[].apk`)
- **Key advantage:** Real `versionCode` from APK metadata. Eligible for auto-update.
- **Issues:** Only FOSS apps. Many proprietary apps not listed.

### GitHub *(Phase 1 — JSON API)*
- **Version check:** `GET https://api.github.com/repos/{owner}/{repo}/releases/latest`
- **Mapping:** Hardcoded package→repo map for popular FOSS apps (VLC, NewPipe, Termux, etc.)
- **Key advantage:** Direct APK download URL from release assets. Eligible for auto-update.
- **Issues:** Limited to mapped repos. Rate-limited without token.

### Uptodown *(Phase 2 — HTML scraper)* (best-effort)
- **Search:** `GET https://www.uptodown.com/android/search/{query}` — **NÃO FUNCIONAL** (todos os padrões de URL conhecidos retornam HTTP 404/410; o site parece ter removido/relocado a feature de busca)
- **Parsing:** Jsoup HTML scraping (multiple selector strategies)
- **Version extraction:** JSON-LD structured data → CSS selectors → regex fallback
- **Issues:** No reliable package-name→URL mapping. Search uses last segment of package name as query. Results are inherently unreliable. NOT eligible for auto-update. **Search endpoint is dead** — service code kept intact for when a working URL is discovered; currently `tryUptodownSearch()` always returns empty list.

### Tencent MyApp *(Phase 1 — JSON API)* (应用宝)
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
