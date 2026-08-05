# HyperOS Updater

Android app for Xiaomi devices running HyperOS that manages app and system updates.

**Target device:** Xiaomi 17 Pro Max (popsicle), HyperOS 3.1 China ROM, Android 16 (API 36).

## Features

- **Delegated Install** — toda instalação delegada ao instalador do sistema via `ACTION_VIEW` + `FileProvider`; bundles (.xapk/.apkm/.apks) abrem chooser "Instalar com…" para selecionar instalador SAI-style (v1.5.2); APKs simples abrem diretamente no instalador padrão
- **9 Update Sources** — APKPure, APKCombo, Aptoide (API v7), F-Droid, APKMirror, GitHub, MemeOs, Uptodown, Tencent MyApp — consultadas em paralelo por app
- **Log Sharing** — captura de crashes não-tratados (`CrashLogger`, 10 arquivos em `filesDir/crash`) + compartilhamento de logs via `LogShareHelper` (header + app logcat + logcat completo via root + últimos 3 crashes → `cacheDir/share` txt + `ACTION_SEND`); botão "Compartilhar logs" nas Settings → "Suporte"
- **OS Updates** — aba OS Updates verifica xiaomi.eu ROMs via RSS do SourceForge; comparação numérica de 4 componentes (sem line-gate); download nativo (sem WebView), sem instalação automática
- **Find & Install** — busca agregada por nome em múltiplas fontes (APKMirror, APKPure, APKCombo, Aptoide, MemeOS, Uptodown); resultados agrupados por nome normalizado (exact match, sem fuzzy) com todos os source badges no card; quick-download usa bestSource (prioridade: APTOIDE > MEMEOS > GITHUB > FDROID > TENCENT); botão info (ⓘ) abre detail page com hits de todas as fontes
- **MemeOS Direct Download** — bypass do countdown de 20s; URL assinada resolvida via 2 HTTP GETs (sem WebView)
- **Assisted WebView Download** — navegação livre na página de download com captura passiva de URL + replay de headers
- **Auto-Update** — toggle "Atualização automática" nas Settings; baixa atualizações em background (download-only via WorkManager, apenas fontes com URL direta: Aptoide, GitHub, F-Droid, MemeOS, Tencent); registra no Downloads tab como AWAITING_INSTALL; notificação "Atualizações prontas" para o usuário instalar via instalador do sistema
- **Download Manager** — downloads persistentes com progresso, velocidade, cancelamento; nomes inteligentes (`<App>-<versão>.<ext>` quando URL não tem nome útil); erros surfacados nos cards ("Erro: ..."); cache inválido (HTML/parcial) é re-baixado automaticamente
- **Background Checks** — WorkManager a cada 24h para apps; respeita filtros de hide/skip
- **Hide/Skip** — "Hide this app" (persiste em DataStore); "Skip this version" (some enquanto latestVersion == versão ignorada); Settings → "Apps ocultos" com labels + search; "Versões ignoradas" com per-entry unskip
- **Per-App Recheck** — botão Refresh em cada card da UpdatesTab re-verifica apenas aquele app (8 fontes, mesma lógica do scan completo); spinner enquanto verifica; não afeta o estado global de scanning
- **Self-Update** — verificação manual de novas versões via GitHub Releases (`api.github.com/repos/andersonlucasg3/hyperos-updater-android/releases/latest`); download e instalação do APK via DownloadManager (key fixa `SELFUPDATE`, instalador do sistema); estados Idle/Checking/UpToDate/Available/Error/NoRelease
- **xiaomi.eu Signature Gate** — detecção de apps de sistema re-assinados com test-key do AOSP (ROMs xiaomi.eu); badge "ROM custom" na lista; scan de sistema pula esses apps (sem request inútil ao MemeOS); página de detalhe explica "sem fonte de atualização compatível" e omite histórico do MemeOS
- **App Icons** — ícones reais dos apps nas listas: `PackageAppIcon` (PackageManager, cache por `remember`) na lista de Updates; `UrlAppIcon` (Coil + placeholder Android) nas listas de Search
- **Filtros** — chip "Updatable" filtra apenas apps com atualização disponível (persistido em `updatable_filter_enabled`); chip "Sistema" controla exibição de apps de sistema (persistido em `show_system_apps`) e também ESCOPA o scan (desligado = apenas third-party)
- **Scan UX** — progresso determinado "x de y" durante o scan; auto-scan roda apenas uma vez por abertura do app (`checkAllAppsIfNeeded`); troca de abas não reescaneia; botão manual de refresh inalterado
- **Estado INSTALLING** — barra indeterminada + "Instalando..." nos cards (sem barra 0% que desaparecia); botão cancelar substituído por spinner durante a instalação
- **Wear OS Protection** — two-layer guard against watch APKs: listing filter (regex on titles/variant names in search results, RSS feed and history lists) + hard install guard (manifest byte-scan for `android.hardware.type.watch` via PackageManager → zip fallback → bundle inner-APK scan; blocks install with clear error message)
- **App Detail Page** — página dedicada acessada pelo botão info (ⓘ) nos cards; cabeçalho (ícone, nome, package, versão/código instalados, instalador, badge sistema); status da versão com recheck automático (ao abrir); search-origin sem app instalado mostra "Disponível" com versão e badge da fonte; "Versões por Fonte" com download inline: installed-app mostra sourceVersions (fontes com versão mais nova), search-origin mostra todos os searchHits agregados (pipe-encoded `EXTRA_SEARCH_HITS`); download roteado pelas mesmas regras das abas; "Histórico de versões" colapsável por fonte — CARREGADO INCONDICIONALMENTE (v1.1.1 fix: não mais restrito a sourceVersions; apps atualizados também recebem histórico); MemeOS `getAppHistory` full, F-Droid `getVersionHistory` todas, GitHub `getReleaseHistory`, APKMirror `getRecentVersions`; demais fontes (APKPure/APKCombo/Aptoide/Uptodown/Tencent) mostram latest + link "abrir página de versões"; ações (Pular versão, Ocultar app, Verificar novamente); badge "instalada" na versão corrente
- **Busca → Detail** — resultados de busca abrem a detail page com `EXTRA_SEARCH_HITS` (pipe-joined `SOURCE|VERSION|URL`); "Versões por Fonte" renderiza todos os hits do agrupamento; download por fonte individual; compara com versão instalada se packageName disponível
- **Material 3 UI** — dynamic color, dark mode, 3 abas (Find & Install, Updates, Settings)

## Architecture

- **Language:** Kotlin 2.1 + Jetpack Compose
- **DI:** Hilt 2.53 (KSP)
- **Networking:** Retrofit 2.11 + Moshi 1.15 + OkHttp 4.12
- **Database:** Room 2.7
- **Background:** WorkManager 2.10
- **Scraping:** Jsoup 1.18
- **Installation:** System installer delegation (ACTION_VIEW + FileProvider; chooser for bundles)
- **Architecture:** Clean Architecture (data / domain / ui layers)

See [docs/](docs/) for detailed documentation.

## Requirements

- Android 12+ (API 31)
- Xiaomi device running HyperOS / MIUI
- Root (recomendado, para captura completa de logs via LogShareHelper)

## Setup

### Build
```bash
./gradlew assembleDebug
```

### Root (for full logcat capture)
1. Install Magisk / KernelSU / APatch on your device
2. Open HyperOS Updater → Settings → "Compartilhar logs" — root é usado apenas para logcat completo
3. Para diagnóstico, Settings → "Suporte" → "Compartilhar logs" mostra o status do root no header do arquivo de log

## Current Status (v1)

### Working
- App scanning com 9 fontes em pipeline de duas fases: APIs JSON baratas primeiro (Aptoide, F-Droid, GitHub, Tencent); scrapers HTML (APKPure, APKCombo, APKMirror, MemeOS, Uptodown) só rodam quando nenhuma API encontrou versão genuinamente mais nova — scan ~4-8× mais rápido no caso comum
- OkHttp tuning: ConnectionPool 32 idle/5min, maxRequestsPerHost 16, connect 10s/read 15s/write 15s, cache HTTP 20MB em disco, sem callTimeout (não mataria downloads grandes — só gap de leitura)
- Verificação de ROMs xiaomi.eu via RSS SourceForge (OS Updates)
- Busca por nome em múltiplas fontes (Find & Install)
- Instalação delegada ao instalador do sistema: `ACTION_VIEW` + `FileProvider`; bundles abrem chooser "Instalar com…" (v1.5.2)
- Download persistente com progresso, velocidade, cancelamento
- WebView assistido: captura passiva de URL de download + replay de headers
- MemeOS direct download: bypass do countdown de 20s via 2 HTTP GETs (resolveDirectDownloadUrl)
- Auto-update download-only via WorkManager: baixa em background, registra como AWAITING_INSTALL, notifica "Atualizações prontas"
- Self-update do app via GitHub Releases (check manual na Settings → "Atualização do app")
- Ícones reais de apps nas listas (PackageManager cache na Updates, Coil na Search)
- Filtros "Updatable" e "Sistema" (persistidos, "Sistema" também escopa o scan)
- Scan com progresso determinado ("x de y"); auto-scan único por abertura
- Detecção de XAPK/APKM por conteúdo (ZIP sem AndroidManifest.xml → renomeia .xapk)
- Guarda de lone-split APK: detecta splits via `PackageInfo.splitNames` e bloqueia com mensagem PT (v1.5.1)
- Estado INSTALLING com spinner + barra indeterminada (sem 0% que desaparecia)
- ERROR state oferece "abrir instalador do sistema" para APKs simples (canUseSystemInstaller); bundles mostram erro sem botão de instalação (v1.4.9)
- Compartilhamento de logs: CrashLogger (10 crashes) + LogShareHelper (header + app logcat + root logcat + crashes → ACTION_SEND) (v1.4.1)
- AppNameMatcher: matching por tiers (exact normalized → all-words whole-word; sem prefix matching) para tryApkMirror (v1.4.5)
- VersionComparator: segmentos VCS-hash tratados como build metadata, não como linha de versão (v1.4.4)
- 51 testes unitários (`VersionComparatorTest`)
- Wear OS detection: 22 tests (`WearOsDetectorTest`) — listing regex + byte-scan (UTF-8/UTF-16LE)
- Proteção Wear OS em duas camadas: filtro de listing (regex em títulos/nomes de variantes) + guarda de instalação (scan de manifest com fallback zip, inclusive bundles XAPK/APKM)

### Known Issues
- **OTA:** código antigo não removido mas desligado do v1 (aba OTA removida, worker cancelado)
- **Xiaomi GetApps:** avaliado e NÃO adicionado como fonte — `app.market.xiaomi.com/apm/app` retorna HTTP 400 "参数不能为空" (requer params/assinatura não documentados); precisaria de MITM reverse engineering
- **APKCombo:** download via WebView assistido apenas (página `/download/apk` bloqueia OkHttp/Cloudflare, mas funciona no WebView); busca por nome impossível (Cloudflare 403 — apenas package-name)
- **APKPure:** retorna HTTP 403 para muitos pacotes de sistema
- **Uptodown:** scraping best-effort, sem mapeamento confiável package→URL; **busca por nome QUEBRADA** (todos os padrões de URL conhecidos retornam HTTP 404/410 — o site removeu/relocou a feature de busca); serviço mantido mas não funcional para search
- **MemeOS:** busca retorna vazio para nomes não-Xiaomi (comportamento esperado — catálogo apenas de apps de sistema Xiaomi)
- **xiaomi.eu system apps:** ROMs xiaomi.eu re-assinam todos os apps de sistema com a test-key do AOSP (DN: `CN=Android, O=Android`). APKs oficiais assinados pela Xiaomi (MemeOs) são incompatíveis (signature mismatch → `INSTALL_FAILED_UPDATE_INCOMPATIBLE`). Não existe fonte pública de APKs individuais assinados pelo xiaomi.eu — apenas ROMs completas. Apps detectados com essa assinatura são marcados "ROM custom" e pulados do scan de sistema. Quando/se surgir uma fonte compatível, o gate já está pronto para plugá-la.
- **Split/bundle install:** resolvido por delegação ao instalador do sistema (v1.5.0). Métodos antigos de root/session estão `@Deprecated` no código mas removidos do dispatch chain. Bundles abrem chooser "Instalar com…" (v1.5.2) para evitar que o instalador stock MIUI capture o intent e falhe com MISSING_SPLIT.
- **Auto-update:** agora é download-only (v1.5.0). Instalação silenciosa removida — sem root/session disponível em background. Worker baixa em background, registra em Downloads tab como AWAITING_INSTALL, notifica "Atualizações prontas" para o usuário instalar manualmente.

## License

MIT
