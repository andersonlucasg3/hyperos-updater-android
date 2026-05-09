# HyperOS Updater — Visão Geral

## Objetivo

App Android nativo para gerenciar atualizações em dispositivos Xiaomi rodando HyperOS/MIUI.
Três tipos de atualização são suportados:

1. **OTA do Sistema** — Atualizações da ROM HyperOS
2. **Apps do Sistema** — Apps proprietários da Xiaomi (Launcher, Gallery, Security, etc.)
3. **Apps de Terceiros** — Apps instalados pelo usuário

## Funcionamento Geral

```
┌─────────────────────────────────────────────────────┐
│                    HyperOS Updater                   │
├───────────────┬─────────────────┬───────────────────┤
│  OTA ROM      │  Apps do Sistema│  Apps de Terceiros│
│  (Xiaomi API) │  (APKMirror)    │  (APKPure)        │
├───────────────┴─────────────────┴───────────────────┤
│  Download Manager (OkHttp + progresso)               │
├─────────────────────────────────────────────────────┤
│  Instalação (Shizuku ou PackageInstaller)            │
├─────────────────────────────────────────────────────┤
│  Background (WorkManager a cada 12h/24h)             │
└─────────────────────────────────────────────────────┘
```

## Dispositivo Alvo

- **Modelo:** Xiaomi 17 Pro Max
- **Codename:** popsicle
- **ROM:** HyperOS 3.1 China (Android 16, API 36)
- **Google Play Services:** Não disponível

## Stack Técnica

| Camada | Tecnologia |
|--------|-----------|
| Linguagem | Kotlin 2.1 |
| UI | Jetpack Compose + Material 3 |
| DI | Hilt (KSP) |
| Rede | Retrofit + Moshi + OkHttp |
| Scraping | Jsoup |
| Banco Local | Room |
| Background | WorkManager |
| Instalação | Shizuku 13 API + PackageInstaller |
| Build | Gradle 8.9 + AGP 8.7.3 |

## Arquitetura

Clean Architecture com 3 camadas em módulo único:

- **data/** — Implementações: Room, Retrofit, scraping, repositórios
- **domain/** — Interfaces, modelos puros, casos de uso, installer abstraction
- **ui/** — Compose screens, ViewModels, navegação, tema
- **di/** — Módulos Hilt para injeção de dependências
- **worker/** — WorkManager workers e scheduler
- **util/** — Utilitários (VersionComparator, XiaomiApps, etc.)
