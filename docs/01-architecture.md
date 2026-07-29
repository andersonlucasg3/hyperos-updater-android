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
│   ├── screens/               Find&Install (search), Updates (apps), Settings
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
Shizuku foi completamente removido do app. O provider, permissão, meta-data, dependências e código foram deletados. Root é o único método de instalação privilegiada. O fallback não-privilegiado é PackageInstaller.Session → Intent ACTION_VIEW.

### Root Install (primary)
`RootApkInstaller` uses `su` with `pm install -S <size> -r -d -i com.android.vending` and stdin pipe. This is the only privileged install method. `-i com.android.vending` makes Android treat the install as Play Store-sourced, avoiding some system restrictions. 120s timeout on `waitFor()` to handle Magisk grant prompts.
