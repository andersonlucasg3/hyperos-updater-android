# Atualizações de Apps

## Fontes de Dados

### Apps do Sistema Xiaomi
- **Fonte primária:** APKMirror RSS feeds (`apkmirror.com/apk/{slug}/feed/`)
- **User-Agent necessário:** `APKUpdater-v3.0.3`
- **Mapeamento:** XiaomiApps.kt ─ 95+ apps mapeados com package name → slug

### Apps de Terceiros
- **Fonte:** APKPure (web scraping com Jsoup)
- **Fallback:** Tentativa de construir slug APKMirror a partir do package name

## Fluxo de Verificação

```
Início → PackageManager.getInstalledPackages()
           ↓
           Para cada app instalado:
           ├─ É app Xiaomi conhecido?
           │  ├─ SIM → Tem slug APKMirror?
           │  │  ├─ SIM → APKMirrorService.fetchAppFeed(slug)
           │  │  │         Parse RSS XML → comparar versões
           │  │  │         Se mais novo → AppUpdate(APKMIRROR)
           │  │  │         Se atualizado → AppUpdate(up-to-date)
           │  │  └─ NÃO → AppUpdate(UNTRACKED)
           │  └─ NÃO → AppUpdate(UNTRACKED)
           │
           └─ É app de terceiro?
              ├─ APKPureService.search(packageName)
              │  Se found + mais novo → AppUpdate(APKPURE)
              │
              └─ Fallback: construir slug APKMirror
                 Se RSS retornar versão mais nova → AppUpdate(APKMIRROR)
                 Senão → AppUpdate(UNTRACKED)
```

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

Cada app recebe um status:

| Status | Significado |
|--------|------------|
| `APKMIRROR` | App rastreado via APKMirror RSS |
| `APKPURE` | App rastreado via APKPure scraping |
| `TRACKER` | App rastreado via miui-updates-tracker |
| `UNTRACKED` | App não rastreado (sem fonte disponível) |

Apps `UNTRACKED` são exibidos na lista mas não têm atualizações verificáveis automaticamente.
O mapeamento em `XiaomiApps.kt` pode ser expandido para cobrir mais apps.

## Comparação de Versões

- **Versões MIUI/HyperOS:** formato `OS3.0.306.0.WPBCNXM` — parse numérico por segmentos
- **Versões semânticas:** formato `1.2.3` — comparação segmento a segmento
- VersionCode (inteiro) é usado quando disponível (APKPure)

## Arquivos Relevantes

- [data/remote/ApkMirrorService.kt](../app/src/main/java/com/hyperos/updater/data/remote/ApkMirrorService.kt) — RSS + scraping
- [data/remote/ApkPureService.kt](../app/src/main/java/com/hyperos/updater/data/remote/ApkPureService.kt) — Scraping APKPure
- [data/repository/AppUpdateRepositoryImpl.kt](../app/src/main/java/com/hyperos/updater/data/repository/AppUpdateRepositoryImpl.kt) — Lógica de verificação
- [util/XiaomiApps.kt](../app/src/main/java/com/hyperos/updater/util/XiaomiApps.kt) — Mapeamento package→slug
- [util/VersionComparator.kt](../app/src/main/java/com/hyperos/updater/util/VersionComparator.kt) — Comparação de versões
- [ui/screens/apps/AppUpdatesViewModel.kt](../app/src/main/java/com/hyperos/updater/ui/screens/apps/AppUpdatesViewModel.kt) — ViewModel
- [ui/screens/apps/AppUpdatesScreen.kt](../app/src/main/java/com/hyperos/updater/ui/screens/apps/AppUpdatesScreen.kt) — UI
