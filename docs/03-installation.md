# Instalação de Atualizações

## Estratégia de Instalação

```
Instalar APK
├─ Root disponível?
│  ├─ SIM → su + pm install -S <size> -r -d -i com.android.vending (stdin pipe, 120s timeout)
│  │        Instalação silenciosa simulando origem Play Store
│  └─ NÃO → PackageInstaller.Session (unattended, best-effort)
│            └─ Fallback: FileProvider URI + ACTION_VIEW intent
│                        Abre o instalador de pacotes do Android
│                        Usuário clica em "Instalar"
```

## Root (método primário)

### O que é
Quando o dispositivo tem root (Magisk/KernelSU/APatch), o app usa `su` para executar
`pm install` com privilégios de root. O APK é enviado via stdin pipe.

### Comando de Instalação
```
su -c "pm install -S <size> -r -d -i com.android.vending"
```
- `-S <size>`: tamanho do APK em bytes (para stdin)
- `-r`: substitui app existente
- `-d`: permite downgrade
- `-i com.android.vending`: simulador de origem Play Store (evita restrições)

### Timeout
O `waitFor()` tem timeout de 120 segundos. Se o Magisk mostrar prompt de concessão
e o usuário não interagir, o processo será encerrado após 120s.

### Verificação de Disponibilidade (diagnoseAvailability)

O Settings agora usa `RootApkInstaller.diagnoseAvailability()` que faz probe de **5 candidatos su** em ordem:

1. `su` (PATH — primeiro candidato, recebe o timeout longo)
2. `/system/bin/su`
3. `/system/xbin/su`
4. `/sbin/su`
5. `/su/bin/su`

O **primeiro candidato** recebe o timeout de prompt longo:
- 60s ao clicar "Solicitar acesso root" (tempo para o usuário responder ao dialog KernelSU/Magisk)
- 10s ao clicar "Verificar novamente" ou no refresh automático

Os demais candidatos são probes rápidos (5s). O resultado (`RootDiagnosis`) inclui o status de cada candidato: `OK <caminho>` em caso de sucesso, ou `exit=N err=...` / `timeout` / exceção em caso de falha.

O Settings mostra o resultado per-candidate (`rootDiagnosis`) abaixo do status "Root disponível"/"Root não disponível" para debug on-device. Isso é particularmente útil com **KernelSU** onde o comportamento de `su` pode variar.

`checkAvailability()` (usado internamente pelo `install()`) chama `diagnoseAvailability(10, 5)` — 10s no primeiro candidato, 5s nos demais.

O botão "Solicitar acesso root" executa `su -c echo ok` com timeout de 60s no primeiro candidato para forçar o Magisk/KernelSU a mostrar o dialog de concessão.
"Verificar novamente" chama `RootApkInstaller.resetAvailability()` e re-executa `diagnoseAvailability(10, 5)`.

## Fallback (sem Root)

Se o Root não estiver disponível, o app tenta:

1. **PackageInstaller.Session** — instalação unattended via API do Android (best-effort, pode falhar silenciosamente)
2. **Intent ACTION_VIEW** — abre o instalador de pacotes do sistema:

```kotlin
val uri = FileProvider.getUriForFile(context, authority, apkFile)
val intent = Intent(Intent.ACTION_VIEW).apply {
    setDataAndType(uri, "application/vnd.android.package-archive")
    flags = FLAG_GRANT_READ_URI_PERMISSION or FLAG_ACTIVITY_NEW_TASK
}
context.startActivity(intent)
```

Isso abre a tela de instalação do sistema onde o usuário clica em "Instalar".
Após o Intent, o app faz polling a cada 2s por até 60s para confirmar a instalação.

## OTA ROM (desligada no v1)

> **Nota (v1):** A aba OTA foi removida e o `OtaCheckWorker` não é mais agendado.
> O código OTA ainda existe no projeto mas não está conectado à UI.

Para atualizações do sistema, **não fazemos instalação automática**.
O app apenas:
1. Faz o download do pacote `.zip` da ROM
2. Verifica o MD5 (se disponível)
3. Salva em `filesDir/downloads/`
4. Notifica o usuário para usar o updater do sistema

A instalação de ROM precisa ser feita pelo próprio sistema HyperOS
(modo Recovery ou escolhendo o pacote nas configurações de atualização).

## Segurança
- MD5 verification após download (quando hash disponível)
- FileProvider para acesso seguro aos arquivos (sem expor caminhos reais)
- Permissão REQUEST_INSTALL_PACKAGES solicitada no manifest

## Arquivos Relevantes

- [domain/installer/ApkInstaller.kt](../app/src/main/java/com/hyperos/updater/domain/installer/ApkInstaller.kt) — Interface
- [domain/installer/RootApkInstaller.kt](../app/src/main/java/com/hyperos/updater/domain/installer/RootApkInstaller.kt) — Root impl (su stdin pipe)
- [domain/installer/PackageManagerInstaller.kt](../app/src/main/java/com/hyperos/updater/domain/installer/PackageManagerInstaller.kt) — Fallback impl (Intent)
- [di/InstallerModule.kt](../app/src/main/java/com/hyperos/updater/di/InstallerModule.kt) — Bindings (@Named root/fallback)
- [domain/usecase/InstallApkUseCase.kt](../app/src/main/java/com/hyperos/updater/domain/usecase/InstallApkUseCase.kt) — Chain: root → fallback
