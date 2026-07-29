# Download & Install Flow

## Architecture

Downloads are handled by `DownloadManager` (`@Singleton`), not individual ViewModels. This ensures downloads survive screen navigation.

Entry points:
- **UpdatesTab / SearchTab:** download button on cards.
- **AppDetailActivity:** download button in "Versões por Fonte" rows and in "Histórico de versões" cards. Same routing rules as tabs.

```
User taps ⬇
  ↓
AppSearchViewModel / AppUpdatesViewModel / AppDetailViewModel
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
Download complete → InstallApkUseCase(file, pkg, isSystem)
  ↓
Root su pm install (stdin pipe) or PackageInstaller Intent fallback
  ↓
Status: INSTALLING → COMPLETED or ERROR
```

## WebView Assistido (APKMirror, APKCombo, APKPure, Uptodown)

Estas fontes requerem JavaScript/WebView para gerar a URL real do CDN. O fluxo:

1. User taps ⬇ on search result or sourceVersion entry
2. `DownloadActivity` opens (WebView with top instruction bar + cancel button)
3. User navigates freely on the download page — NO auto-click
4. URL capture happens passively via:
   - `DownloadListener` (native WebView download events)
   - JS injection intercepting `fetch`/`XHR` calls
   - `shouldOverrideUrlLoading` scanning for APK/CDN patterns (including apkcombo `d?u=` Base64 redirects)
5. On capture: Activity returns `EXTRA_DOWNLOAD_URL` + `EXTRA_REFERER` + `EXTRA_USER_AGENT` + `EXTRA_COOKIE`
6. Caller replays headers in OkHttp via `DownloadManager.startDownload(url, headers)`
7. CDN URL may be time-limited; 3-minute timeout on capture

### Strict Capture Filter (isDownloadUrl)

Antes, o helper JS `_isDl()` fazia match em qualquer URL contendo `/download` ou `cdn` como substring — isso incluía a própria URL da página do APKCombo (`.../download/apk`) e endpoints XHR de API, que "envenenavam" `window._apkm_dl_url`. O WebView capturava esse falso positivo sem validação → fechava instantaneamente → o OkHttp tentava baixar uma página HTML (403).

Agora existe um predicado único e estrito `isDownloadUrl()` (Kotlin + JS espelhado):

- **Regra:** o path da URL (com query/fragment removidos) deve terminar em `.apk`, `.apkm`, `.xapk`, `.apks` ou `.aab`; OU o host deve conter um CDN de arquivos conhecido (`cloudflarestorage.com`, `d.apkpure.com`, `downloadr`).
- **Aplicado em todos os pontos de captura:** JS `_isDl()` (fetch, XHR send, JSON-sniff), `onPageStarted`/`onPageFinished` (checagem do resultado JS), `shouldOverrideUrlLoading`, `DownloadListener`.
- **Efeito colateral:** o strip de query antes do `endsWith` faz URLs assinadas (`...apk?sig=...`) passarem no filtro — o `endsWith` puro falhava porque a URL terminava em `?sig=...`.

Páginas comuns (`/download/apk`, `/cdn/...`) e endpoints de API **nunca** passam por este filtro.

### APKCombo: Always WebView
APKCombo **nunca** faz download direto. `ApkComboResult.downloadUrl` aponta para `<appPage>/download/apk` — uma página real que retorna 403 via OkHttp puro (Cloudflare) mas funciona no WebView. Todas as branches de download (SearchTab, AppSearchScreen, UpdatesTab sourceVersions) roteiam APKCOMBO para o WebView. Não há double-append de `/download/apk` — `AppSearchViewModel.downloadFromPage` não tem branch explícita para APKCOMBO (cai no `else → result.downloadPageUrl`). `AppUpdatesViewModel.getSourcePageUrl` usa `sourceDownloadUrl` diretamente para APKCOMBO, sem fallback para URL de busca.

### Direct-Download Sources (sem WebView)
Fontes com URL direta de APK são baixadas nativamente via OkHttp, sem abrir o WebView:

| Fonte | Condição | Observação |
|-------|----------|------------|
| APTOIDE | `downloadUrl != null` | `file.path` da API v7 é URL direta |
| GITHUB | `downloadUrl != null` | Release assets são URLs diretas |
| FDROID | `downloadUrl != null` | `packages[].apk` é URL direta |
| MEMEOS | resolveDirectDownloadUrl != null | Tenta bypass do countdown primeiro; cai no WebView se falhar |

O botão principal de download na UpdatesTab verifica `hasDirectUrl` (APTOIDE/GITHUB/FDROID) e, se `downloadUrl` estiver presente, inicia o download nativo. Caso contrário, abre o WebView.

### Handoff Pattern (pendingKey + pendingAppName)

Tanto UpdatesTab quanto SearchTab usam `pendingKey` + `pendingAppName` (remembered state) para fazer a ponte entre o callback `ActivityResult` e a entrada correta de download. Quando a captura do WebView completa, o handler do launcher lê essas variáveis para rotear a URL + headers capturados para `DownloadManager.startDownload()`. Após o roteamento, ambas são limpas.

### Completion Handler: Dual-Key Matching
O handler de completação no `AppUpdatesViewModel` (que coleta `downloadManager.downloads`) faz matching tanto pela chave primária (`updateSource.name + appName`) quanto pelas chaves de `sourceVersions` (`sv.source.name + appName`). Isso garante que downloads iniciados a partir das versões alternativas (sourceVersions expandidas) também sejam detectados como concluídos e disparem a atualização de `currentVersion`.

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

## Download Hardening

### Non-2xx & HTML rejection
`DownloadUpdateUseCase.download()` rejeita corpos que não são APK:
- **HTTP não-2xx:** lança `IllegalStateException("HTTP ${code} from <url>")` — o erro é surfacado no card como "Erro: HTTP 403..."
- **Content-Type text/html:** lança `IllegalStateException("Got HTML page instead of APK...")` — protege contra páginas de desafio Cloudflare/403 que o servidor entrega como 200 OK com corpo HTML

### Error surfacing
`DownloadProgress.errorMessage` é populado com a mensagem da exceção quando o download falha. A UI mostra `"Erro: ${errorMessage}"` em texto vermelho (`labelSmall`) abaixo da barra de progresso nos cards com status `ERROR`.

### Cache validation
- **`installCached`:** só instala APKs válidos — `getPackageArchiveInfo()` deve retornar não-nulo + status deve ser `COMPLETED` ou `AWAITING_INSTALL`. Não faz mais scan cego do diretório de downloads (que podia instalar APKs órfãos de outros apps).
- **`startDownload`:** se o arquivo já existe mas `getPackageArchiveInfo()` retorna nulo (HTML salvo como APK, download parcial corrompido), o arquivo é deletado e o download é refeito.

### Smart filenames (`buildApkFileName`)
Quando a URL do CDN não tem um nome de arquivo utilizável (ex: `https://cdn.example.com/dl?token=abc` → `dl` é genérico), `buildApkFileName(url, appName, version)` constrói um nome no formato `<App>-<versão>.<ext>`:

```
URL path (query/fragment stripped) → last segment
  ↓
Extension é de arquivo (.apk/.apkm/.xapk/.apks/.aab)?
  ├─ SIM → nome base parece genérico?
  │         (download, file, apk, index, redirect, dl, get, downloaded,
  │          ≤3 chars, all-numeric, ou hash ≥24 chars alphanum)
  │         ├─ SIM → fallback: <AppName>-<version>.<ext>
  │         └─ NÃO → usa o nome original
  └─ NÃO → fallback: <AppName>-<version>.apk
```

Usado em UpdatesTab para downloads com URL direta (APTOIDE/GITHUB/FDROID/TENCENT/MEMEOS).

### XAPK/APKM Content Detection (`adjustArchiveType`)

CDNs frequentemente servem bundles (XAPK/APKM) sem extensão de arquivo — o download chega nomeado `.apk`, mas o conteúdo é um ZIP multi-APK. Instalar isso como APK único produz instalação corrompida.

`DownloadManager.adjustArchiveType()` resolve isso por inspeção de conteúdo pós-download:

```
Download concluído → arquivo .apk
  ↓
Extensão já é .apkm/.xapk/.apks/.aab? → retorna sem alterar
  ↓
Abre como ZipFile:
  - Lista entries (max 500)
  - Verifica se existe "AndroidManifest.xml" na raiz do ZIP
  - Conta entries internas .apk
  ↓
Sem manifest na raiz + ≥1 .apk interno → BUNDLE
  ↓
Renomeia: arquivo.apk → arquivo.xapk
  ↓
installSplitApk() → extrai APKs → rootInstallMulti()
```

**Regra:** um APK real **sempre** tem `AndroidManifest.xml` na raiz do ZIP. Um bundle (XAPK/APKM) tem APKs aninhados sem manifest no nível raiz. Isso é confiável e não depende de heurísticas de nome de arquivo.

### Wear OS Install Guard (`isWearOsApk`)

Após o download (e após `adjustArchiveType`), o APK é verificado para builds de relógio (Wear OS) antes da instalação:

```
Download concluído → adjustArchiveType (corrige extensão)
  ↓
isWearOsApk(finalFile):
  1. PackageManager.getPackageArchiveInfo() → reqFeatures
     → algum FeatureInfo.name == "android.hardware.type.watch"?
     ├─ SIM → retorna true (Wear OS detectado)
     └─ NÃO → passo 2
  2. WearOsDetector.scanApkForWearFeature() — byte-scan do manifest
     → busca "android.hardware.type.watch" em UTF-8 e UTF-16LE
     → para bundles (XAPK/APKM sem manifest raiz), varre APKs internos
     ├─ Encontrado → retorna true
     └─ Não encontrado → retorna false
  ↓
Se true:
  → status = ERROR
  → errorMessage = "Este APK é para Wear OS (relógio), não para o telefone"
  → instalação BLOQUEADA
```

**Motivação:** algumas variantes Wear OS compartilham o mesmo nome de app e package que a versão phone (ex.: Spotify, WhatsApp). O filtro de listing (Layer 1) cobre resultados de busca e RSS, mas um APK pode chegar por outros caminhos (download direto de URL, cache, etc.). A guarda de instalação (Layer 2) é a última linha de defesa — **sempre** verificada pós-download.

**Fail-soft:** qualquer erro de leitura do ZIP/manifest retorna `false` (permite a instalação). Apenas uma detecção positiva do marker bloqueia.

## Cancellation

`downloadJob.cancel()` cancels the coroutine. The partial file is deleted on error/cancel.

Cancel and dismiss also terminate any active **install-poll job** (`"$key-poll"`) — this prevents cancelled downloads from resurrecting when the poll eventually confirms the install. Dismiss additionally removes the entry from `StateFlow` and the `activeJobs` map.

## Install Robustness

Before launching a new install (`runInstall`), any prior install job for the same key is cancelled — preventing concurrent double-install races. The completion handler in `AppUpdatesViewModel` matches both primary-source keys (`updateSource.name + appName`) and sourceVersion keys (`sv.source.name + appName`), so downloads initiated from the expanded sourceVersions list are correctly detected.

## Root Install: waitFor Before Join

`su` stdin-pipe installs (both `DownloadManager.rootInstallSingle()` and `RootApkInstaller`) use `process.waitFor(timeout)` **before** joining stdout/stderr reader threads. Reader threads block until EOF (process exit), so joining them first would hang forever behind a Magisk grant prompt, making the timeout useless. The correct order:
1. `process.waitFor(120, SECONDS)` — blocks with timeout
2. On timeout: `process.destroyForcibly()`
3. Then `stdoutThread.join(5_000)`, `stderrThread.join(5_000)`, `writerThread.join(5_000)`

## MemeOS Direct Download (Bypass do Countdown)

MemeOS (`memeosupdates.com`) impõe um countdown de 20 segundos antes de liberar o download.
O app faz bypass completo desse countdown sem WebView, usando dois HTTP GETs simples:

```
1. GET version page (ex: .../apps/{pkg}/{versionCode})
   ↓ regex extract data-download-url (prefer dl=0, fallback dl=1)
2. GET tokenUrl (com Referer=versionPage)
   ↓ regex extract https://download.memeosupdates.com/...apk?exp&nonce&sig
3. Native OkHttp download usando a URL assinada
```

A URL assinada contém parâmetros `exp` (expiry timestamp), `nonce` e `sig`.
O download deve ocorrer logo após a resolução — a URL expira.

Este fluxo é implementado em `MemeOsService.resolveDirectDownloadUrl(versionPageUrl)`.
No `AppCheckWorker`, fontes `MEMEOS` são resolvidas via este método antes do download silencioso.
Na UI (UpdatesTab, SearchTab, AppSearchScreen), tenta-se a resolução primeiro; se falhar,
cai no fluxo WebView assistido.
