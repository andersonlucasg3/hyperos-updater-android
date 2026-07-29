# HyperOS Updater — Visão Geral

## Objetivo

App Android nativo para gerenciar atualizações em dispositivos Xiaomi rodando HyperOS/MIUI.
Três abas principais:

```
┌─────────────────────────────────────────────────────────────────┐
│                    HyperOS Updater (v1)                         │
├────────────────┬───────────────────────┬────────────────────────┤
│  Find & Install│  Apps do Sistema      │  Apps de Terceiros     │
│  (busca)       │  (MemeOs Updates)     │  (8 fontes paralelo)   │
├────────────────┴───────────────────────┴────────────────────────┤
│  Download Manager (OkHttp + progresso)                          │
├─────────────────────────────────────────────────────────────────┤
│  Instalação (Root su → PackageInstaller.Session → Intent)      │
├─────────────────────────────────────────────────────────────────┤
│  Background (WorkManager a cada 24h + Auto-Update opcional)     │
└─────────────────────────────────────────────────────────────────┘
```

1. **Find & Install** — Busca apps por nome em múltiplas fontes
2. **Updates** — Lista apps instalados com atualizações disponíveis (aba padrão)
3. **Settings** — Configurações (Root, Auto-Update, About)

**Nota (v1):** A aba OTA foi removida. O código OTA ainda existe mas não está conectado à UI nem ao worker scheduler.

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
| Instalação | Root (su) → PackageInstaller.Session → Intent |
| Build | Gradle 8.9 + AGP 8.7.3 |

## Arquitetura

Clean Architecture com 3 camadas em módulo único:

- **data/** — Implementações: Room, Retrofit, scraping, repositórios
- **domain/** — Interfaces, modelos puros, casos de uso, installer abstraction
- **ui/** — Compose screens, ViewModels, navegação, tema
- **di/** — Módulos Hilt para injeção de dependências
- **worker/** — WorkManager workers e scheduler
- **util/** — Utilitários (VersionComparator, XiaomiApps, etc.)
