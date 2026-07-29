# Atualizações OTA do Sistema

> **Status (v1):** Aba "OS Updates" restaurada — verifica ROMs do **xiaomi.eu** (não mais a API da Xiaomi).
> `OtaCheckWorker` NÃO é agendado (verificação manual apenas).
> O código antigo da API Xiaomi (`OtaApi`, `OtaRepositoryImpl`, `CheckOtaUpdateUseCase`, `OtaCheckWorker`, etc.)
> permanece em disco mas não é usado.

## Fonte de Dados

**xiaomi.eu via RSS do SourceForge:** `https://sourceforge.net/projects/xiaomi-eu-multilang-miui-roms/rss?path=/xiaomi.eu/HyperOS-STABLE-RELEASES/HyperOS3.0/`

O xiaomi.eu produz ROMs baseadas na China ROM com Google Services e multi-idioma. O RSS do SourceForge lista os builds estáveis do HyperOS 3.0.

## Fluxo

```
Início → OtaViewModel.checkForUpdates()
           ↓
       GetDeviceInfoUseCase → codename (ex: popsicle)
           ↓
       XiaomiEuService.checkLatestRom(codename)
           ↓
           GET RSS feed do SourceForge
           XmlPullParser: parse <item> com title, link, pubDate, media:content, media:hash
           ↓
           Filtra: primeiro item cujo title contém "_POPSICLE_" (case-insensitive)
           ↓
           Extrai versão do filename: _OS{major}.{minor}.{patch}.{build}_
           Regex: _OS(\d+)\.(\d+)\.(\d+)\.(\d+)_
           ↓
       Compara versão instalada vs ROM:
           extractNumericParts() → 4 componentes numéricos
           isNumericNewer() → comparação lexicográfica nos 4-tuples
           ⚠️ NÃO usa VersionComparator line-gate — compara APENAS os 4 números.
           Sufixos sempre diferem (xiaomi.eu rebaseia China ROMs).
           ↓
       Se mais nova → UpdateState.Available
       Se igual     → UpdateState.Idle
           ↓
       Download: OkHttp nativo (SourceForge link 302→mirror)
       Sem WebView, sem interação do usuário
           ↓
       Arquivo salvo em Downloads/HyperOSUpdater/
       NÃO há instalação automática da ROM
```

## Comparação de Versões

Diferentemente da comparação de apps (que usa `VersionComparator` com gate de linha), a comparação
de ROMs do xiaomi.eu é puramente numérica:

```kotlin
// OtaViewModel.extractNumericParts("OS3.0.9.0.WPBCNXM") → [3, 0, 9, 0]
fun extractNumericParts(version: String): List<Int> {
    val cleaned = version.removePrefix("OS")
    return (cleaned.split(".").mapNotNull { it.toIntOrNull() } + List(4) { 0 }).take(4)
}

// OtaViewModel.isNumericNewer(installed, candidate) → true se candidate > installed
fun isNumericNewer(installed: List<Int>, candidate: List<Int>): Boolean {
    for (i in 0 until 4) {
        if (candidate[i] > installed[i]) return true
        if (candidate[i] < installed[i]) return false
    }
    return false
}
```

O sufixo (ex: `WPBCNXM` vs `MIXM`) é **ignorado** — o xiaomi.eu sempre tem sufixo diferente do stock.
Apenas os 4 componentes numéricos determinam se há update.

## Decisões de Design

1. **RSS, não API** — O SourceForge não tem API REST; o RSS é parseado com XmlPullParser.
   Cada `<item>` contém title (nome do arquivo), link (URL SourceForge), pubDate, media:content (tamanho), e media:hash (MD5).

2. **Download nativo** — O link do SourceForge redireciona (302) para um mirror; o OkHttp segue o redirect automaticamente.
   Não é necessário WebView.

3. **Sem instalação automática** — O download da ROM é salvo em `Downloads/HyperOSUpdater/`.
   A instalação deve ser feita manualmente pelo usuário via recovery/updater do sistema.

4. **Verificação manual apenas** — `OtaCheckWorker` não é agendado. O usuário abre a aba "OS Updates" e clica em "Check for Updates".

## Código Antigo (API Xiaomi — não usado)

O código da API oficial da Xiaomi (`update.miui.com`) permanece no projeto mas não está conectado à UI:

**Request (não usado):**
```
POST https://update.miui.com/updates/miotaV3.php
d=popsicle & b=F & c=CN & v=OS3.0.17.0.WPBCNXM & is_global=0 & r=CN & pn=popsicle
```

**Status:** HTTP 400 para popsicle. API pode não suportar este codename ainda.

## Arquivos Relevantes

- [data/remote/XiaomiEuService.kt](../app/src/main/java/com/hyperos/updater/data/remote/XiaomiEuService.kt) — RSS SourceForge, XmlPullParser, filtro por codename
- [ui/screens/ota/OtaViewModel.kt](../app/src/main/java/com/hyperos/updater/ui/screens/ota/OtaViewModel.kt) — extractNumericParts, isNumericNewer, download
- [ui/screens/ota/OtaTab.kt](../app/src/main/java/com/hyperos/updater/ui/screens/ota/OtaTab.kt) — UI da aba OS Updates
- [ui/MainScreen.kt](../app/src/main/java/com/hyperos/updater/ui/MainScreen.kt) — 4 abas (OS Updates em idx=0, Updates em idx=2 como default)
- [data/remote/OtaApi.kt](../app/src/main/java/com/hyperos/updater/data/remote/OtaApi.kt) — API Xiaomi (NÃO USADA, mantida em disco)
- [domain/usecase/CheckOtaUpdateUseCase.kt](../app/src/main/java/com/hyperos/updater/domain/usecase/CheckOtaUpdateUseCase.kt) — Caso de uso antigo (NÃO USADO)
