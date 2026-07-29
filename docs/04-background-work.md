# Verificações em Background

## WorkManager

O app usa WorkManager para executar verificações periódicas mesmo quando
o app não está aberto.

### Workers (v1)

| Worker | Frequência | O que faz |
|--------|-----------|-----------|
| `AppCheckWorker` | A cada 24h | Verifica apps do sistema + terceiros. Se `auto_update_enabled=false`: notifica com contagem. Se `true`: baixa e instala via Root (apenas fontes com URL direta), notifica com sumário de resultados. |

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

### Auto-Update (AppCheckWorker)
Quando `auto_update_enabled = true` (toggle "Atualização automática" nas Settings):
1. Coleta todos os updates disponíveis (sistema + terceiros)
2. Filtra apenas fontes com URL direta: `{APTOIDE, GITHUB, FDROID, MEMEOS, TENCENT}`
3. Faz download de cada APK para `cacheDir/auto_update/` (MemeOS: resolve URL assinada via `resolveDirectDownloadUrl()` antes do download)
4. Instala via Root (nunca Intent fallback — não funciona em background)
5. Notifica com `NotificationHelper.showAutoUpdateResults(success, fail, skipped)`

### Notificações

Dois canais de notificação:
- **`ota_updates`** (IMPORTANCE_HIGH) — para atualizações de ROM (canal mantido, mas OTA desligada no v1)
- **`app_updates`** (IMPORTANCE_DEFAULT) — para atualizações de apps + resultados de auto-update

### Permissão de Notificações (Android 13+)
Em dispositivos com Android 13+, a permissão `POST_NOTIFICATIONS` é
solicitada em runtime. Se não concedida, as notificações são suprimidas
silenciosamente (sem crash).

## Intervalos Configuráveis

O usuário pode alterar o intervalo de verificação nas Settings:
- 6h, 12h, 24h, 48h
- O intervalo se aplica ao AppCheckWorker

## Arquivos Relevantes

- [worker/AppCheckWorker.kt](../app/src/main/java/com/hyperos/updater/worker/AppCheckWorker.kt) — Auto-update + notificação
- [worker/WorkerScheduler.kt](../app/src/main/java/com/hyperos/updater/worker/WorkerScheduler.kt) — Agenda app_check, cancela ota_check
- [worker/NotificationHelper.kt](../app/src/main/java/com/hyperos/updater/worker/NotificationHelper.kt) — Notificações (incl. showAutoUpdateResults)
- [HyperOsApp.kt](../app/src/main/java/com/hyperos/updater/HyperOsApp.kt) — Cria canais + agenda workers
