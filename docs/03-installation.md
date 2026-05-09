# Instalação de Atualizações

## Estratégia de Instalação

```
Instalar APK
├─ Shizuku disponível?
│  ├─ SIM → pm install -r [--user 0 para apps do sistema]
│  │        Instalação silenciosa via shell com privilégios ADB
│  └─ NÃO → PackageManagerInstaller
│            FileProvider URI + ACTION_VIEW intent
│            Abre o instalador de pacotes do Android
│            Usuário clica em "Instalar"
```

## Shizuku

### O que é
Shizuku é um app que permite que apps normais usem APIs do sistema diretamente
com privilégios ADB/root. Ele inicia um processo Java com `app_process` e expõe
um binder IPC para outros apps.

### Como usar com HyperOS Updater
1. Instale o app [Shizuku](https://github.com/RikkaApps/Shizuku/releases)
2. Ative o Wireless Debugging nas Opções de Desenvolvedor
3. No Shizuku, faça o pareamento via "Pairing"
4. Inicie o Shizuku
5. HyperOS Updater detecta automaticamente e usa para instalação silenciosa

### Comandos de Instalação
- **Apps do sistema:** `pm install -r -d --user 0 /path/to/app.apk`
  - `-r`: substitui app existente
  - `-d`: permite downgrade
  - `--user 0`: instala para o usuário do sistema
- **Apps de terceiros:** `pm install -r /path/to/app.apk`

### Fallback quando Shizuku não está disponível
Se Shizuku não estiver rodando ou não estiver instalado, o app usa o
PackageInstaller padrão do Android:

```kotlin
val uri = FileProvider.getUriForFile(context, authority, apkFile)
val intent = Intent(Intent.ACTION_VIEW).apply {
    setDataAndType(uri, "application/vnd.android.package-archive")
    flags = FLAG_GRANT_READ_URI_PERMISSION or FLAG_ACTIVITY_NEW_TASK
}
context.startActivity(intent)
```

Isso abre a tela de instalação do sistema onde o usuário clica em "Instalar".

## OTA ROM

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
- [domain/installer/ShizukuApkInstaller.kt](../app/src/main/java/com/hyperos/updater/domain/installer/ShizukuApkInstaller.kt) — Shizuku impl
- [domain/installer/PackageManagerInstaller.kt](../app/src/main/java/com/hyperos/updater/domain/installer/PackageManagerInstaller.kt) — Fallback impl
- [domain/usecase/InstallApkUseCase.kt](../app/src/main/java/com/hyperos/updater/domain/usecase/InstallApkUseCase.kt) — Orquestrador
- [domain/usecase/DownloadUpdateUseCase.kt](../app/src/main/java/com/hyperos/updater/domain/usecase/DownloadUpdateUseCase.kt) — Download
