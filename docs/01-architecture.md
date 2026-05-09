# Architecture

## Layer Structure

```
com.hyperos.updater
├── HyperOsApp.kt              @HiltAndroidApp + Configuration.Provider
├── di/                        Hilt DI modules
│   ├── AppModule.kt           Context, PackageManager
│   ├── NetworkModule.kt       OkHttpClient, Moshi, Retrofit, API services
│   ├── DatabaseModule.kt      Room DB + DAOs
│   ├── InstallerModule.kt     ApkInstaller bindings (Shizuku + fallback)
│   └── RepositoryModule.kt    Interface → Implementation bindings
│
├── data/                      DATA LAYER
│   ├── local/                 Room: AppDatabase, entities, DAOs
│   ├── remote/                Retrofit APIs, Jsoup scrapers, DTOs
│   └── repository/            Repository implementations
│
├── domain/                    DOMAIN LAYER (pure Kotlin)
│   ├── model/                 OtaUpdate, AppUpdate, AppInfo, DeviceInfo, UpdateState
│   ├── repository/            Repository interfaces
│   ├── usecase/               Business logic (CheckOta, Download, Install, etc.)
│   ├── installer/             ApkInstaller interface + Shizuku/PackageManager impls
│   └── DownloadManager.kt     Singleton download orchestrator
│
├── ui/                        UI LAYER (Jetpack Compose)
│   ├── MainActivity.kt
│   ├── DownloadActivity.kt    WebView-based CDN URL capture
│   ├── navigation/            Screen routes + NavHost
│   ├── theme/                 Material3 theme, colors, typography
│   ├── screens/               home, search, apps, detail, downloads, settings
│   └── components/            AppListItem, SourceBadge, DownloadProgressSheet
│
├── worker/                    WorkManager workers + NotificationHelper
└── util/                      VersionComparator, XiaomiApps, ShizukuHelper, Extensions
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

### Shizuku via Reflection
Shizuku 13 made `newProcess()` private. Reflection is used to access it. The `ShizukuProvider` in AndroidManifest must NOT have `android:permission` restriction (blocks binder delivery).
