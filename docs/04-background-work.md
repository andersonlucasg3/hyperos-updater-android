# Verificações em Background

## WorkManager

O app usa WorkManager para executar verificações periódicas mesmo quando
o app não está aberto.

### Workers (v1)

| Worker | Frequência | O que faz |
|--------|-----------|-----------|
| `AppCheckWorker` | A cada 24h | Verifica apps do sistema + terceiros. Se `auto_update_enabled=false`: notifica com contagem. Se `true`: **baixa em background** (download-only) apenas fontes com URL direta, registra downloads como `AWAITING_INSTALL` no `DownloadManager`, notifica com "Atualizações prontas" para o usuário instalar via instalador do sistema. |

> **Nota (v1):** `OtaCheckWorker` foi removido do scheduler. O código ainda existe mas o
> `WorkerScheduler.scheduleAll()` cancela `ota_check` e agenda apenas `app_check`.

### Constraints
- Rede disponível (`NetworkType.CONNECTED`)
- Bateria não baixa (`requiresBatteryNotLow`)

### Agendamento
```kotlin
WorkerScheduler.scheduleAll(context)
// Chamado em HyperOsApp.onCreate() na inicialização do app
// Cancela "ota_check" de builds anteriores, agenda "app_check"
```

Usa `ExistingPeriodicWorkPolicy.KEEP` — se já estiver agendado, mantém.

### Auto-Update — Download-Only (v1.5.0+)

**Instalação silenciosa foi removida** — root/session não estão mais no dispatch chain. O worker agora é **download-only**:

Quando `auto_update_enabled = true` (toggle "Atualização automática" nas Settings):
1. Coleta todos os updates disponíveis (sistema + terceiros)
2. Filtra apenas fontes com URL direta: `{APTOIDE, GITHUB, FDROID, MEMEOS, TENCENT}`
3. Para cada update com URL direta:
   - MEMEOS: resolve URL assinada via `resolveDirectDownloadUrl()` antes do download
   - Faz download para `downloadsDirectory` (Downloads/HyperOSUpdater)
   - Registra no `DownloadManager` via `registerCompletedDownload()` → status `AWAITING_INSTALL`
4. Notifica com `NotificationHelper.showDownloadsReady(successCount, failCount, skippedCount)`
   - Título: "Atualizações prontas"
   - Texto: "N atualização(ões) pronta(s) para instalar — toque para instalar"
   - Intent abre a aba Downloads

O usuário toca a notificação → vê os downloads na aba Downloads → toca cada item para instalar via instalador do sistema.

Settings auto-update subtitle (v1.5.0): *"Baixa atualizações automaticamente; a instalação abre no instalador do sistema"*

### Notificações

Dois canais de notificação:
- **`ota_updates`** (IMPORTANCE_HIGH) — para atualizações de ROM (canal mantido, mas OTA desligada no v1)
- **`app_updates`** (IMPORTANCE_DEFAULT) — para atualizações de apps + notificação "Atualizações prontas"

**Notificações implementadas no `NotificationHelper`:**
| Método | Canal | Uso |
|--------|-------|-----|
| `showAppUpdatesAvailable(count)` | `app_updates` | Auto-update OFF — notifica contagem de updates |
| `showDownloadsReady(success, fail, skipped)` | `app_updates` | Auto-update ON — notifica downloads concluídos (v1.5.0+) |
| `showAutoUpdateResults(success, fail, skipped, details)` | `app_updates` | Legado (não mais usado; substituído por `showDownloadsReady`) |
| `showOtaUpdateAvailable(version, changelog)` | `ota_updates` | OTA ROM (canal mantido, não usado no v1) |

### Permissão de Notificações (Android 13+)
Em dispositivos com Android 13+, a permissão `POST_NOTIFICATIONS` é
solicitada em runtime. Se não concedida, as notificações são suprimidas
silenciosamente (sem crash).

## Intervalos Configuráveis

O usuário pode alterar o intervalo de verificação nas Settings:
- 6h, 12h, 24h, 48h
- O intervalo se aplica ao AppCheckWorker

## Arquivos Relevantes

- [worker/AppCheckWorker.kt](../app/src/main/java/com/hyperos/updater/worker/AppCheckWorker.kt) — Auto-update download-only + notificação
- [worker/WorkerScheduler.kt](../app/src/main/java/com/hyperos/updater/worker/WorkerScheduler.kt) — Agenda app_check, cancela ota_check
- [worker/NotificationHelper.kt](../app/src/main/java/com/hyperos/updater/worker/NotificationHelper.kt) — Notificações (showAppUpdatesAvailable, showDownloadsReady, showAutoUpdateResults)
- [HyperOsApp.kt](../app/src/main/java/com/hyperos/updater/HyperOsApp.kt) — Cria canais + agenda workers
