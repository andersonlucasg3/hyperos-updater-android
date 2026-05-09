# Arquitetura do Projeto

## Estrutura de Pacotes

```
com.hyperos.updater
├── HyperOsApp.kt                    @HiltAndroidApp
├── di/                              Hilt DI modules
│   ├── AppModule.kt                 Context, PackageManager, NotificationManager
│   ├── NetworkModule.kt             OkHttpClient, Moshi, Retrofit, API services
│   ├── DatabaseModule.kt            Room DB, DAOs
│   ├── InstallerModule.kt           ApkInstaller bindings (Shizuku + fallback)
│   └── RepositoryModule.kt          Interface → Implementation bindings
│
├── data/                            DATA LAYER
│   ├── local/                       Room
│   │   ├── AppDatabase.kt
│   │   ├── entity/                  OtaUpdateEntity, TrackedAppEntity, UpdateHistoryEntity
│   │   └── dao/                     OtaUpdateDao, TrackedAppDao
│   ├── remote/                      Network
│   │   ├── OtaApi.kt                Retrofit interface (Xiaomi OTA)
│   │   ├── ApkMirrorService.kt      OkHttp + XmlPullParser + Jsoup
│   │   ├── ApkPureService.kt        OkHttp + Jsoup
│   │   └── dto/                     OtaResponse, ApkMirrorRssItem
│   └── repository/                  Implementations
│       ├── OtaRepositoryImpl.kt
│       ├── AppUpdateRepositoryImpl.kt
│       ├── DeviceRepositoryImpl.kt
│       └── PreferencesRepositoryImpl.kt
│
├── domain/                          DOMAIN LAYER (pure Kotlin)
│   ├── model/                       Domain models
│   │   ├── OtaUpdate.kt, AppUpdate.kt, AppInfo.kt
│   │   ├── DeviceInfo.kt, UpdateState.kt, AppType.kt
│   ├── repository/                  Repository interfaces
│   │   ├── OtaRepository.kt, AppUpdateRepository.kt
│   │   ├── DeviceRepository.kt, PreferencesRepository.kt
│   ├── usecase/                     Business logic
│   │   ├── CheckOtaUpdateUseCase.kt, CheckSystemAppUpdatesUseCase.kt
│   │   ├── CheckThirdPartyAppUpdatesUseCase.kt
│   │   ├── DownloadUpdateUseCase.kt, InstallApkUseCase.kt
│   │   └── GetDeviceInfoUseCase.kt
│   └── installer/                   Installation abstraction
│       ├── ApkInstaller.kt (interface)
│       ├── ShizukuApkInstaller.kt
│       └── PackageManagerInstaller.kt
│
├── ui/                              UI LAYER (Jetpack Compose)
│   ├── MainActivity.kt
│   ├── navigation/                  Screen routes + NavHost
│   ├── theme/                       Material3 theme, colors, typography
│   ├── screens/                     
│   │   ├── home/                    Dashboard
│   │   ├── ota/                     OTA ROM update
│   │   ├── apps/                    App list (system + third-party)
│   │   ├── detail/                  App detail
│   │   └── settings/               Preferences
│   └── components/                  Reusable composables
│       ├── AppListItem.kt
│       └── ShizukuStatusBanner.kt
│
├── worker/                          Background work
│   ├── OtaCheckWorker.kt
│   ├── AppCheckWorker.kt
│   ├── WorkerScheduler.kt
│   └── NotificationHelper.kt
│
└── util/                            Utilities
    ├── VersionComparator.kt
    ├── XiaomiApps.kt
    ├── NetworkUtils.kt
    └── Extensions.kt
```

## Padrões e Decisões

### Clean Architecture (módulo único)
Separação por pacotes dentro de um único módulo Gradle.
Para um app pessoal/single-dev, a complexidade de build de múltiplos
módulos não se justifica.

### Repository Pattern
Interfaces em `domain/repository/`, implementações em `data/repository/`.
Hilt faz o binding via `RepositoryModule.kt`.

### Use Cases
Cada operação de negócio é um Use Case separado.
ViewModels dependem de Use Cases, não de Repositories diretamente.
Isso mantém a lógica de negócio testável e reutilizável.

### State Management
- ViewModels expõem `StateFlow<UiState>`
- Screens coletam com `collectAsState()`
- One-way data flow: User Action → ViewModel → Use Case → Repository → State → UI

### Dependency Injection (Hilt)
- `@HiltAndroidApp` em HyperOsApp
- `@AndroidEntryPoint` em MainActivity
- `@HiltViewModel` em todos ViewModels
- `@HiltWorker` em todos Workers
- `@Inject constructor` em todas as classes
- `@Binds` para interfaces → implementações
- `@Provides` para objetos de terceiros (Retrofit, Room, etc.)

### Decisões Críticas

1. **Sem cache de URLs de download** — tokens expiram em ~4 dias
2. **Shizuku com reflection** — API 13 tornou `newProcess` privado
3. **Moshi com KSP** — mais rápido que reflexão runtime
4. **XmlPullParser para RSS** — nativo do Android, sem dependência extra
5. **Jsoup para scraping** — robusto contra HTML malformado
6. **Device codename via getprop** — mais confiável que Build.DEVICE em alguns dispositivos
