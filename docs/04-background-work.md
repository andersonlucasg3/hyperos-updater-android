# Verificações em Background

## WorkManager

O app usa WorkManager para executar verificações periódicas mesmo quando
o app não está aberto.

### Workers

| Worker | Frequência | O que faz |
|--------|-----------|-----------|
| `OtaCheckWorker` | A cada 12h | Consulta API Xiaomi, notifica se há ROM nova |
| `AppCheckWorker` | A cada 24h | Verifica apps do sistema + terceiros, notifica com contagem |

### Constraints
Ambos os workers exigem:
- Rede disponível (`NetworkType.CONNECTED`)
- Bateria não baixa (`requiresBatteryNotLow`)

### Agendamento
```kotlin
WorkerScheduler.scheduleAll(context)
// Chamado em HyperOsApp.onCreate() na inicialização do app
```

Usa `ExistingPeriodicWorkPolicy.KEEP` — se já estiver agendado, mantém.

### Notificações

Dois canais de notificação:
- **`ota_updates`** (IMPORTANCE_HIGH) — para atualizações de ROM
- **`app_updates`** (IMPORTANCE_DEFAULT) — para atualizações de apps

Cada notificação tem um PendingIntent que abre a tela correspondente
(OTA, System Apps, etc.) quando tocada.

### Permissão de Notificações (Android 13+)
Em dispositivos com Android 13+, a permissão `POST_NOTIFICATIONS` é
solicitada em runtime. Se não concedida, as notificações são suprimidas
silenciosamente (sem crash).

## Intervalos Configuráveis

O usuário pode alterar o intervalo de verificação nas Settings:
- 6h, 12h, 24h, 48h
- O intervalo se aplica apenas ao AppCheckWorker
- OTA sempre verifica a cada 12h

## Arquivos Relevantes

- [worker/OtaCheckWorker.kt](../app/src/main/java/com/hyperos/updater/worker/OtaCheckWorker.kt)
- [worker/AppCheckWorker.kt](../app/src/main/java/com/hyperos/updater/worker/AppCheckWorker.kt)
- [worker/WorkerScheduler.kt](../app/src/main/java/com/hyperos/updater/worker/WorkerScheduler.kt)
- [worker/NotificationHelper.kt](../app/src/main/java/com/hyperos/updater/worker/NotificationHelper.kt)
- [HyperOsApp.kt](../app/src/main/java/com/hyperos/updater/HyperOsApp.kt) — Cria canais + agenda workers
