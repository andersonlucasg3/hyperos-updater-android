# Atualizações de Apps

## Fontes de Dados

### Apps do Sistema Xiaomi
- **Fonte exclusiva:** MemeOs Updates (`memeosupdates.com`) — catálogo + páginas de detalhe
- **Catálogo:** `GET https://memeosupdates.com/apps/` — lista única com ~190 apps (`/apps/{packageName}`)
- **Detalhe:** `GET https://memeosupdates.com/apps/{pkg}` — versionName, **versionCode real** (extraído do APK), tamanho, data, histórico por região (`data-region="gl|cn"`)
- **Comparação:** `hasUpdate = sameLine && (semanticNewer || (codeNewer && !semanticOlder))` — `versionCode` nunca cruza linhas (ex: `0.0.0-global` nunca atualiza para `0.0.0-cn`)
- **Cache:** catálogo em memória no `@Singleton`, revalidado a cada scan completo (`forceRefresh`)
- **Apps não presentes no catálogo:** marcados `UNTRACKED`

### Apps de Terceiros (8 fontes em paralelo)
- **APKPure** — web scraping com Jsoup (`apkpure.com/search?q=`, página de detalhes)
- **APKCombo** — scraping (`apkcombo.com/search/`)
- **Aptoide** — API oficial v7 (`ws75.aptoide.com/api/7/getApp?package_name=`) com URL direta de download
- **F-Droid** — API REST (`f-droid.org/api/v1/packages/`)
- **APKMirror** — busca por nome (`?s=&post_type=app_release`), download via WebView assistido
- **GitHub** — API releases (`api.github.com/repos/{owner}/{repo}/releases/latest`)
- **MemeOs** — catálogo de apps Xiaomi
- **Uptodown** — scraping best-effort (`uptodown.com/android/search/`), sem mapeamento package→URL confiável

As 8 fontes são consultadas em paralelo via `supervisorScope` com `async` por app, com `Semaphore(6)` para limite de concorrência. O `pickBest()` usa `VersionComparator.compare(versionName, versionCode)` — versionName-first, versionCode apenas como desempate quando ambos > 0.

**Gate de linha para F-Droid:** o `versionCode` do F-Droid só conta como update se `isSameLine(instalada, fdroid.versionName)` for `true` — versionCode nunca cruza linhas. O mesmo vale para o `isNewer` aplicado às demais fontes, que já filtra por linha internamente.

## APKMirror RSS Parsing

O APKMirror não tem API pública. O fluxo é:

1. **RSS Feed:** `GET https://www.apkmirror.com/apk/{slug}/feed/`
   - Parse XML com XmlPullParser
   - Extrai: título, link, pubDate de cada `<item>`
   - A versão é extraída do título (formato: "AppName X.Y.Z (arch)")

2. **Download Page:** (para obter URL de download)
   - Scrape com Jsoup no link da release page
   - Procura por `<a rel="nofollow">` com href contendo "download"

## APKPure Scraping

1. **Busca:** `GET https://apkpure.com/search?q={packageName}`
   - Parse HTML com Jsoup
   - Extrai nome, versão, link da página de detalhes

2. **Download Page:** (para obter URL de download)
   - Segue link da página de detalhes
   - Procura por link de download

## Status de Rastreamento

Cada app recebe um status (enum `UpdateSource`):

| Status | Significado |
|--------|------------|
| `APKMIRROR` | App rastreado via APKMirror |
| `APKPURE` | App rastreado via APKPure scraping |
| `APKCOMBO` | App rastreado via APKCombo |
| `APTOIDE` | App rastreado via Aptoide API v7 |
| `FDROID` | App rastreado via F-Droid API |
| `GITHUB` | App rastreado via GitHub releases |
| `MEMEOS` | App rastreado via MemeOs Updates |
| `UPTODOWN` | App rastreado via Uptodown (best-effort) |
| `UNTRACKED` | App não rastreado (sem fonte disponível) |

Apps `UNTRACKED` são exibidos na lista mas não têm atualizações verificáveis automaticamente.
O mapeamento em `XiaomiApps.kt` pode ser expandido para cobrir mais apps.

## Comparação de Versões

- **Versões MIUI/HyperOS:** formato `OS3.0.306.0.WPBCNXM` — parse numérico por segmentos (prefixo `OS` removido)
- **Versões semânticas:** formato `1.2.3` — comparação segmento a segmento, padding com zero
- **Linha (qualifier):** versões com qualifiers diferentes (ex: `-global` vs `-cn`, `0.0.0` vs `0.0.0-R`) pertencem a linhas diferentes e são **incomparáveis** — `isNewer` retorna `false` em ambas as direções e `compare` retorna `0`. A regra é: **versionCode nunca cruza linhas.**
- **`isSameLine(a, b)`:** API pública que verifica se duas versões compartilham o mesmo qualifier (após case-folding e normalização de separadores `.`/`-`/`_`)
- **`compare(versionNameA, versionCodeA, versionNameB, versionCodeB)`:** versionName-first; versionCode só é usado como desempate quando AMBOS > 0. Retorna `0` para pares de linhas diferentes.
- **`isNewer(a, b)`:** retorna `true` se `b` é mais novo que `a` **e ambos pertencem à mesma linha**

## Fluxo de Verificação (Terceiros)

```
Para cada app instalado:
  supervisorScope + async (8 fontes em paralelo, Semaphore(6))
    APKPure / APKCombo / Aptoide / F-Droid / APKMirror / GitHub / MemeOs / Uptodown
  ↓
  Coleta SourceResult de cada fonte
  ↓
  Filtra sourceVersions: apenas as genuinamente mais novas (isNewer)
  ↓
  pickBest(): VersionComparator.compare → melhor versionName (code como tiebreaker)
  ↓
  APKCombo excluído do pickBest a menos que seja a única fonte (listings sem download real)
  ↓
  AppUpdate com sourceVersions completo (todas as fontes com versão mais nova)
```

### Per-App Recheck (Re-verificação individual)

Cada card na UpdatesTab tem um botão Refresh que re-verifica **apenas aquele app**, sem disparar um scan completo:

```
UpdatesTab: IconButton(Refresh) → viewModel.recheckApp(update)
  ↓
AppUpdatesViewModel.recheckApp(update):
  - trackingKey = packageName + appType.name
  - _checkingApps += trackingKey  →  UI mostra spinner no lugar do ícone Refresh
  ↓
AppUpdateRepository.recheckApp(packageName, appType):
  - Lê PackageInfo atual do dispositivo
  - Constrói AppInfo com versionName/versionCode reais
  - Dispatcheia para a função privada correta:
      AppType.SYSTEM    → checkOneSystemApp(app, catalog)
      AppType.THIRD_PARTY → checkOneThirdPartyApp(app, appType)
  ↓
  checkOneSystemApp / checkOneThirdPartyApp:
    MESMA lógica dos scans completos (isSameLine gate, pickBest, 8 fontes em paralelo).
    Resultado idêntico ao que o scan completo produziria para este app.
  ↓
ViewModel faz upsert(result) → substitui APENAS o card deste app na lista.
_isScanning NÃO é alterado — apenas _checkingApps é atualizado.
```

As funções privadas `checkOneSystemApp(app, catalog)` e `checkOneThirdPartyApp(app, appType)` são a **single source of truth** para verificação de um app — usadas tanto pelos flow builders dos scans completos quanto por `recheckApp`. Isso garante resultados idênticos em ambos os caminhos, incluindo o gate `isSameLine` e a exclusão do APKCombo do `pickBest`.

O estado `checkingApps: StateFlow<Set<String>>` no ViewModel rastreia quais apps estão sendo re-verificados no momento. A UI usa isso para mostrar um `CircularProgressIndicator` no lugar do ícone Refresh enquanto a verificação está em andamento. Re-checks duplicados para o mesmo app são ignorados (early return se a key já está em `_checkingApps`).

## Escopo do Scan (Filtro "Sistema")

O chip "Sistema" nas Settings **também escopa o scan** — não é apenas um filtro visual:

- **Ligado (default):** o scan cobre apps de sistema (MemeOs) + apps de terceiros (8 fontes).
- **Desligado:** o scan cobre **apenas** apps de terceiros. O job de sistema (`systemJob`) é `null` e não é lançado.

**Race com DataStore — `.first()`:** O ViewModel expõe `showSystemApps` como `StateFlow` com `SharingStarted.Eagerly` e valor inicial `true`. No cold start, o DataStore ainda não emitiu o valor real — usar o StateFlow diretamente faria o auto-scan sempre incluir sistema no primeiro scan, mesmo com o toggle desligado. Para evitar isso, `checkAllApps()` lê a pref diretamente com `preferencesRepository.showSystemApps.first()` (função suspend que espera a primeira emissão real do DataStore).

**Entradas stale de sistema:** Quando "Sistema" está desligado, apps de sistema de scans anteriores **não são removidos** da lista. A passagem de remoção (`toRemove`) só considera app types que estavam no escopo do scan atual (`inScope`). Isso evita que o toggle "Sistema" apague dados persistidos de sistema quando desligado temporariamente.

## Progresso Determinado do Scan

Diferente da barra indeterminada genérica, o scan mostra progresso determinado "x de y":

- `scanProgress: StateFlow<Pair<Int, Int>?>` — `(checked, total)`, `null` quando não está escaneando.
- O total é calculado antes do scan: `systemApps.size + thirdPartyApps.size` (considerando o escopo).
- Cada app verificado incrementa o contador via `bumped()`.
- A UI mostra `LinearProgressIndicator` determinada + texto `"${scan!!.first} de ${scan!!.second}"`.
- Se `total == 0`, a barra é indeterminada (fallback).

**Auto-scan único:** `checkAllAppsIfNeeded()` usa um guard `autoCheckDone` — retorna sem fazer nada se já rodou. Isso garante que o scan dispare apenas na primeira abertura do app (via `LaunchedEffect(Unit)` na UpdatesTab). Trocas de abas não reescaneiam. O botão manual de refresh (`IconButton(Refresh)`) chama `checkAllApps()` diretamente, ignorando o guard.

## Endpoints de Histórico de Versões (App Detail Page)

A página de detalhes (`AppDetailActivity`) carrega histórico completo de versões de fontes que oferecem endpoints dedicados:

| Fonte | Método | Endpoint | Retorno |
|-------|--------|----------|---------|
| **MemeOS** | `getAppHistory(pkg)` | `memeosupdates.com/apps/{pkg}` — scrape HTML dos `div.version-item` | Todas as versões: version, versionCode, region, date, sizeBytes, pageUrl. Cada entrada é baixável via `resolveDirectDownloadUrl` na página da versão. |
| **F-Droid** | `getVersionHistory(pkg)` | `f-droid.org/api/v1/packages/{pkg}` → array JSON `packages[]` | Todas as versões: versionName, versionCode, apkUrl (URL direta). |
| **GitHub** | `getReleaseHistory(pkg)` | `api.github.com/repos/{repo}/releases?per_page=20` | Todas as releases: tag, name, publishedAt, apkUrl (primeiro asset `.apk`). |
| **APKMirror** | `getRecentVersions(appName)` | RSS feed via `searchByName` → slug → `fetchAppFeed` | Versões recentes: version, pageUrl (download via WebView). |
| **APKPure/APKCombo/Aptoide/Uptodown/Tencent** | — | — | Apenas latest + link "abrir página de versões". |

O carregamento é fail-soft por fonte — falha em uma fonte não bloqueia as demais. O histórico só é carregado para fontes presentes em `sourceVersions`. No modo search-origin, apenas APKMirror (RSS via slug extraído da page URL) e MemeOS (package-name extraído da page URL) tentam carregar histórico.

## Arquivos Relevantes

- [data/remote/ApkMirrorService.kt](../app/src/main/java/com/hyperos/updater/data/remote/ApkMirrorService.kt) — RSS + scraping
- [data/remote/ApkPureService.kt](../app/src/main/java/com/hyperos/updater/data/remote/ApkPureService.kt) — Scraping APKPure
- [data/remote/AptoideService.kt](../app/src/main/java/com/hyperos/updater/data/remote/AptoideService.kt) — Aptoide API v7
- [data/remote/UptodownService.kt](../app/src/main/java/com/hyperos/updater/data/remote/UptodownService.kt) — Scraping Uptodown
- [data/remote/FDroidService.kt](../app/src/main/java/com/hyperos/updater/data/remote/FDroidService.kt) — F-Droid API
- [data/remote/GitHubService.kt](../app/src/main/java/com/hyperos/updater/data/remote/GitHubService.kt) — GitHub releases
- [data/remote/MemeOsService.kt](../app/src/main/java/com/hyperos/updater/data/remote/MemeOsService.kt) — MemeOs Updates
- [domain/repository/AppUpdateRepository.kt](../app/src/main/java/com/hyperos/updater/domain/repository/AppUpdateRepository.kt) — Interface (checkSystemAppUpdates, checkThirdPartyAppUpdates, recheckApp)
- [data/repository/AppUpdateRepositoryImpl.kt](../app/src/main/java/com/hyperos/updater/data/repository/AppUpdateRepositoryImpl.kt) — Lógica de verificação (8 fontes, pickBest, checkOneSystemApp, checkOneThirdPartyApp, recheckApp)
- [util/VersionComparator.kt](../app/src/main/java/com/hyperos/updater/util/VersionComparator.kt) — Comparação de versões (isNewer + compare)
- [ui/screens/apps/AppUpdatesViewModel.kt](../app/src/main/java/com/hyperos/updater/ui/screens/apps/AppUpdatesViewModel.kt) — ViewModel (checkAllApps, recheckApp, checkingApps)
- [ui/screens/detail/AppDetailActivity.kt](../app/src/main/java/com/hyperos/updater/ui/screens/detail/AppDetailActivity.kt) — Activity standalone (modos: installed-app e search-origin)
- [ui/screens/detail/AppDetailViewModel.kt](../app/src/main/java/com/hyperos/updater/ui/screens/detail/AppDetailViewModel.kt) — ViewModel (loadInstalled, loadSearchOrigin, recheck, downloadFromSource, skipVersion, hideApp, history loading)
- [ui/screens/detail/AppDetailScreen.kt](../app/src/main/java/com/hyperos/updater/ui/screens/detail/AppDetailScreen.kt) — Tela de detalhes (header, status, sourceVersions, history groups, ações)
