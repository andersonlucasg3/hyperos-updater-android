# Architecture

## Layer Structure

```
com.hyperos.updater
├── HyperOsApp.kt              @HiltAndroidApp + Configuration.Provider
├── di/                        Hilt DI modules
│   ├── AppModule.kt           Context, PackageManager
│   ├── NetworkModule.kt       OkHttpClient, Moshi, Retrofit, API services
│   ├── DatabaseModule.kt      Room DB + DAOs
│   ├── InstallerModule.kt     ApkInstaller bindings (Root + fallback)
│   └── RepositoryModule.kt    Interface → Implementation bindings
│
├── data/                      DATA LAYER
│   ├── local/                 Room: AppDatabase, entities, DAOs
│   ├── remote/                Retrofit APIs, Jsoup scrapers, DTOs
│   │   ├── SelfUpdateService.kt  GitHub Releases check for self-update
│   │   └── ...
│   └── repository/            Repository implementations
│
├── domain/                    DOMAIN LAYER (pure Kotlin)
│   ├── model/                 OtaUpdate, AppUpdate, AppInfo, DeviceInfo, UpdateState
│   ├── repository/            Repository interfaces
│   ├── usecase/               Business logic (CheckOta, Download, Install, etc.)
│   ├── installer/             ApkInstaller interface + Root/PackageManager impls
│   └── DownloadManager.kt     Singleton download orchestrator
│
├── ui/                        UI LAYER (Jetpack Compose)
│   ├── MainActivity.kt
│   ├── DownloadActivity.kt    WebView-based CDN URL capture
│   ├── navigation/            Screen routes + NavHost
│   ├── theme/                 Material3 theme, colors, typography
│   ├── screens/               Find&Install (search), Updates (apps), Detail (AppDetailActivity), Settings
│   └── components/            AppListItem, AppIcon (PackageAppIcon + UrlAppIcon), SourceBadge, DownloadProgressSheet
│
├── worker/                    WorkManager workers + NotificationHelper
└── util/                      VersionComparator, XiaomiApps, Extensions
```

## Key Design Decisions

### Clean Architecture in Single Module
Separation by packages within single `:app` module. Multi-module complexity is not justified for a personal-use app.

### StateFlow for UI State
All ViewModels expose `StateFlow<UiState>`. Screens collect with `collectAsState()`. One-way data flow: User Action → ViewModel → UseCase → Repository → State → UI.

### Hilt for DI
- `@HiltAndroidApp` on Application
- `@HiltViewModel` on all ViewModels
- `@HiltWorker` on WorkManager workers
- `@Binds` for interface → implementation
- `@Provides` for third-party objects (Retrofit, Room, etc.)

### callbackFlow for Downloads
Download progress uses `callbackFlow` with `trySend()` instead of `flow {}` to avoid Dispatchers.IO → Main emission violations.

### Shizuku via Reflection (REMOVED)
Shizuku foi completamente removido do app. O provider, permissão, meta-data, dependências e código foram deletados.

### Install Delegation (v1.5.0+)
Toda instalação é delegada ao instalador do sistema. `PackageManagerInstaller.openInstallIntent()` usa `ACTION_VIEW` + `FileProvider`; bundles abrem via `Intent.createChooser("Instalar com…")` (v1.5.2) para evitar que o instalador stock MIUI capture o intent e falhe com MISSING_SPLIT. Métodos antigos de root/session (`RootApkInstaller`, session install) estão `@Deprecated` no código. Root é usado apenas para captura de logcat completo no `LogShareHelper`.

### Root — Apenas para Logs
`RootApkInstaller` ainda existe com `diagnoseAvailability()` para verificar disponibilidade, mas a instalação via `su pm install` não é mais usada. Root é usado exclusivamente para `LogShareHelper.runLogcat()` (logcat completo do sistema, 3000 linhas).
