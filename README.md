# HyperOS Updater

Android app for Xiaomi devices running HyperOS that manages app and system updates.

**Target device:** Xiaomi 17 Pro Max (popsicle), HyperOS 3.1 China ROM, Android 16 (API 36).

## Features

- **Root Install** — silent install via `su` with `-i com.android.vending` (simula Play Store), stdin pipe, 120s timeout
- **9 Update Sources** — APKPure, APKCombo, Aptoide (API v7), F-Droid, APKMirror, GitHub, MemeOs, Uptodown, Tencent MyApp — consultadas em paralelo por app
- **OS Updates** — aba OS Updates verifica xiaomi.eu ROMs via RSS do SourceForge; comparação numérica de 4 componentes (sem line-gate); download nativo (sem WebView), sem instalação automática
- **Find & Install** — busca apps por nome em múltiplas fontes, download e instalação
- **MemeOS Direct Download** — bypass do countdown de 20s; URL assinada resolvida via 2 HTTP GETs (sem WebView)
- **Assisted WebView Download** — navegação livre na página de download com captura passiva de URL + replay de headers
- **Auto-Update** — toggle "Atualização automática" nas Settings; baixa e instala via Root apenas de fontes com URL direta (Aptoide, GitHub, F-Droid, MemeOS)
- **Download Manager** — downloads persistentes com progresso, velocidade, cancelamento; nomes inteligentes (`<App>-<versão>.<ext>` quando URL não tem nome útil); erros surfacados nos cards ("Erro: ..."); cache inválido (HTML/parcial) é re-baixado automaticamente
- **Background Checks** — WorkManager a cada 24h para apps; respeita filtros de hide/skip
- **Hide/Skip** — "Hide this app" (persiste em DataStore); "Skip this version" (some enquanto latestVersion == versão ignorada); Settings → "Apps ocultos" com labels + search; "Versões ignoradas" com per-entry unskip
- **Per-App Recheck** — botão Refresh em cada card da UpdatesTab re-verifica apenas aquele app (8 fontes, mesma lógica do scan completo); spinner enquanto verifica; não afeta o estado global de scanning
- **Self-Update** — verificação manual de novas versões via GitHub Releases (`api.github.com/repos/andersonlucasg3/hyperos-updater-android/releases/latest`); download e instalação do APK via DownloadManager (key fixa `SELFUPDATE`, root install chain); estados Idle/Checking/UpToDate/Available/Error/NoRelease
- **App Icons** — ícones reais dos apps nas listas: `PackageAppIcon` (PackageManager, cache por `remember`) na lista de Updates; `UrlAppIcon` (Coil + placeholder Android) nas listas de Search
- **Filtros** — chip "Updatable" filtra apenas apps com atualização disponível (persistido em `updatable_filter_enabled`); chip "Sistema" controla exibição de apps de sistema (persistido em `show_system_apps`) e também ESCOPA o scan (desligado = apenas third-party)
- **Scan UX** — progresso determinado "x de y" durante o scan; auto-scan roda apenas uma vez por abertura do app (`checkAllAppsIfNeeded`); troca de abas não reescaneia; botão manual de refresh inalterado
- **Estado INSTALLING** — barra indeterminada + "Instalando..." nos cards (sem barra 0% que desaparecia); botão cancelar substituído por spinner durante a instalação
- **App Detail Page** — página dedicada acessada pelo botão info (ⓘ) nos cards; cabeçalho (ícone, nome, package, versão/código instalados, instalador, badge sistema); status da versão com recheck automático; "Versões por Fonte" com download inline (mesmas regras de roteamento das abas: MEMEOS resolve-direct/WebView fallback, APTOIDE/GITHUB/FDROID/TENCENT direto, APKMIRROR/APKCOMBO/APKPURE/UPTODOWN WebView); "Histórico de versões" colapsável por fonte (MemeOS/F-Droid/GitHub/APKMirror com endpoints dedicados de histórico; demais fontes mostram latest + link "abrir página"); ações (Pular versão, Ocultar app, Verificar novamente); badge "instalada" na versão corrente
- **Busca → Detail** — resultados de busca abrem a detail page com extras SEARCH_*; mostra info do resultado + botão de download; compara com versão instalada se disponível
- **Material 3 UI** — dynamic color, dark mode, 3 abas (Find & Install, Updates, Settings)

## Architecture

- **Language:** Kotlin 2.1 + Jetpack Compose
- **DI:** Hilt 2.53 (KSP)
- **Networking:** Retrofit 2.11 + Moshi 1.15 + OkHttp 4.12
- **Database:** Room 2.7
- **Background:** WorkManager 2.10
- **Scraping:** Jsoup 1.18
- **Installation:** Root (su stdin) → PackageInstaller.Session → Intent fallback
- **Architecture:** Clean Architecture (data / domain / ui layers)

See [docs/](docs/) for detailed documentation.

## Requirements

- Android 12+ (API 31)
- Xiaomi device running HyperOS / MIUI
- Root (recomendado, para instalação silenciosa)

## Setup

### Build
```bash
./gradlew assembleDebug
```

### Root (for silent install)
1. Install Magisk / KernelSU / APatch on your device
2. Open HyperOS Updater → Settings → Root → "Solicitar acesso root" to trigger the grant dialog
3. Status should show "Root disponível" (green)
4. The per-candidate su probe results are shown below the status for debugging (useful with KernelSU)

## Current Status (v1)

### Working
- App scanning com 9 fontes em paralelo (APKPure, APKCombo, Aptoide, F-Droid, APKMirror, GitHub, MemeOs, Uptodown, Tencent)
- Verificação de ROMs xiaomi.eu via RSS SourceForge (OS Updates)
- Busca por nome em múltiplas fontes (Find & Install)
- Instalação root via su com stdin pipe e `-i com.android.vending` (método primário)
- PackageInstaller.Session + Intent fallback
- Download persistente com progresso, velocidade, cancelamento
- WebView assistido: captura passiva de URL de download + replay de headers
- MemeOS direct download: bypass do countdown de 20s via 2 HTTP GETs (resolveDirectDownloadUrl)
- Auto-update via Root para fontes com URL direta (Aptoide, GitHub, F-Droid, MemeOS)
- Self-update do app via GitHub Releases (check manual na Settings → "Atualização do app")
- Ícones reais de apps nas listas (PackageManager cache na Updates, Coil na Search)
- Filtros "Updatable" e "Sistema" (persistidos, "Sistema" também escopa o scan)
- Scan com progresso determinado ("x de y"); auto-scan único por abertura
- Detecção de XAPK/APKM por conteúdo (ZIP sem AndroidManifest.xml → renomeia .xapk)
- Estado INSTALLING com spinner + barra indeterminada (sem 0% que desaparecia)
- 51 testes unitários (`VersionComparatorTest`)

### Known Issues
- **OTA:** código antigo não removido mas desligado do v1 (aba OTA removida, worker cancelado)
- **Xiaomi GetApps:** avaliado e NÃO adicionado como fonte — `app.market.xiaomi.com/apm/app` retorna HTTP 400 "参数不能为空" (requer params/assinatura não documentados); precisaria de MITM reverse engineering
- **APKCombo:** download via WebView assistido apenas (página `/download/apk` bloqueia OkHttp/Cloudflare, mas funciona no WebView)
- **APKPure:** retorna HTTP 403 para muitos pacotes de sistema
- **Uptodown:** scraping best-effort, sem mapeamento confiável package→URL

## License

MIT
