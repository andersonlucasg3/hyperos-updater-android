# Arquitetura do Projeto

## Estrutura de Pacotes

```
com.hyperos.updater
├── HyperOsApp.kt                    @HiltAndroidApp
├── di/                              Hilt DI modules
│   ├── AppModule.kt                 Context, PackageManager, NotificationManager
│   ├── NetworkModule.kt             OkHttpClient, Moshi, Retrofit, API services
│   ├── DatabaseModule.kt            Room DB, DAOs
│   ├── InstallerModule.kt           ApkInstaller bindings (Root + fallback)
│   └── RepositoryModule.kt          Interface → Implementation bindings
│
├── data/                            DATA LAYER
│   ├── local/                       Room
│   │   ├── AppDatabase.kt
│   │   ├── entity/                  OtaUpdateEntity, TrackedAppEntity, UpdateHistoryEntity
│   │   └── dao/                     OtaUpdateDao, TrackedAppDao
│   ├── remote/                      Network
│   │   ├── OtaApi.kt                Retrofit interface (Xiaomi OTA — desligada no v1)
│   │   ├── ApkMirrorService.kt      OkHttp + XmlPullParser + Jsoup
│   │   ├── ApkPureService.kt        OkHttp + Jsoup
│   │   ├── AptoideService.kt        Aptoide API v7
│   │   ├── UptodownService.kt       Jsoup scraping
│   │   ├── FDroidService.kt         F-Droid REST API
│   │   ├── GitHubService.kt         GitHub releases API
│   │   ├── MemeOsService.kt         MemeOs Updates (catálogo + direct download)
│   │   ├── TencentService.kt        Tencent MyApp (应用宝)
│   │   ├── SelfUpdateService.kt     GitHub Releases check (self-update do app)
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
│       ├── RootApkInstaller.kt (su stdin pipe)
│       └── PackageManagerInstaller.kt
│
├── ui/                              UI LAYER (Jetpack Compose)
│   ├── MainActivity.kt
│   ├── navigation/                  Screen routes + NavHost
│   ├── theme/                       Material3 theme, colors, typography
│   ├── screens/                     
│   │   ├── search/                 Find & Install (busca)
│   │   ├── apps/                   Updates (apps instalados)
│   │   ├── detail/                 App detail
│   │   └── settings/               Preferences
│   └── components/                  Reusable composables
│       ├── AppListItem.kt
│       ├── AppIcon.kt                PackageAppIcon (PackageManager) + UrlAppIcon (Coil)
│       ├── DownloadsBadge.kt
│       └── DownloadProgressSheet.kt
│
├── worker/                          Background work (v1)
│   ├── AppCheckWorker.kt            Auto-update + notificação
│   ├── WorkerScheduler.kt           Agenda app_check, cancela ota_check
│   └── NotificationHelper.kt        Notificações (incl. showAutoUpdateResults)
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
2. **Root como único instalador privilegiado** — su stdin pipe com `-i com.android.vending` (simula Play Store)
3. **Moshi com KSP** — mais rápido que reflexão runtime
4. **XmlPullParser para RSS** — nativo do Android, sem dependência extra
5. **Jsoup para scraping** — robusto contra HTML malformado
6. **Device codename via getprop** — mais confiável que Build.DEVICE em alguns dispositivos
7. **8 fontes em paralelo** — `supervisorScope` + `async` com `Semaphore(6)`, `pickBest` versionName-first
8. **WebView assistido** — captura passiva de URL + replay de headers (Referer, User-Agent, Cookie)
9. **MemeOS direct download** — bypass do countdown de 20s via 2 HTTP GETs (`resolveDirectDownloadUrl`), resolve URL assinada sem WebView
10. **Auto-update apenas Root** — nunca Intent fallback em background; apenas fontes com URL direta
