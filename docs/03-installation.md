# Instalação de Atualizações

## Estratégia de Instalação (v1.5.0+)

**Toda instalação é delegada ao instalador do sistema.** A cadeia Root→Session→Intent foi removida do dispatch path. O fluxo atual:

```
Download concluído
  ↓
adjustArchiveType(file) — detecta bundle por conteúdo ZIP, renomeia .xapk se necessário
  ↓
isWearOsApk(file) — bloqueia APKs de relógio (PackageManager + byte-scan)
  ↓
isLoneSplitApk(file) — bloqueia splits isolados (PackageInfo.splitNames, v1.5.1)
  ↓
executeInstall(file) → PackageManagerInstaller.openInstallIntent(file)
  ↓
  ├─ .apk → ACTION_VIEW direto (application/vnd.android.package-archive)
  │         Abre o instalador de pacotes do sistema
  │
  └─ .xapk/.apkm/.apks → Intent.createChooser(ACTION_VIEW, "Instalar com…")
                          (application/octet-stream, v1.5.2)
                          Usuário escolhe instalador SAI-style;
                          evita que o instalador stock MIUI capture
                          o intent e falhe com MISSING_SPLIT
  ↓
Polling de confirmação: a cada 2s por 60s, verifica versionCode instalado
  ↓
Status: COMPLETED (instalado) ou ERROR (falha)
```

## Por que delegação?

O problema original com Root/Session:

- **Root (`su pm install`)**: exige Magisk/KernelSU, trava em prompt de concessão, não funciona em todos os dispositivos
- **PackageInstaller.Session**: `session.commit()` exige `PendingIntent.FLAG_MUTABLE` (framework preenche status extras), mas Android 14+ proíbe `FLAG_MUTABLE` com intents implícitos → o pattern correto é `Intent(action).setPackage(pkg)` + `FLAG_MUTABLE|FLAG_UPDATE_CURRENT`; na prática, implementação frágil entre versões de Android

A delegação ao instalador do sistema resolve todos esses problemas de uma vez:
- Funciona em qualquer dispositivo (root não necessário)
- O instalador do sistema gerencia permissões, assinaturas e sessões corretamente
- Bundles são roteados para instaladores SAI-style via chooser explícito

## Ordem das Guardas (pós-download)

### 1. `adjustArchiveType(file)`
CDNs frequentemente servem bundles sem extensão. Abre o ZIP e verifica:
- Se há `.apk` interno E **não** há `AndroidManifest.xml` na raiz → é bundle → renomeia `.xapk`
- APK real sempre tem `AndroidManifest.xml` na raiz do ZIP

### 2. `isWearOsApk(file)`
Bloqueia APKs de Wear OS (relógio):
- `PackageManager.getPackageArchiveInfo()` → `reqFeatures` → `android.hardware.type.watch`
- Fallback: byte-scan do `AndroidManifest.xml` (UTF-8 + UTF-16LE)
- Bundles: varre APKs internos recursivamente
- Se detectado → `ERROR` com mensagem "Este APK é para Wear OS (relógio), não para o telefone"

### 3. `isLoneSplitApk(file)` (v1.5.1)
Detecta APKs que são apenas um split (config/DPI/ABI) de um app:
- `PackageManager.getPackageArchiveInfo()` → `PackageInfo.splitNames`
- Se `splitNames` não for nulo/vazio → é um split isolado, não um APK standalone
- Bloqueado com mensagem PT: "Este arquivo é apenas uma parte (split) do app — baixe o pacote completo/bundle na página de variantes"
- Fail-soft: erro de leitura → `false` (deixa instalar)
- Apenas `.apk` é verificado (bundles legitimamente contêm splits)

### 4. `executeInstall(file)` → delegação
Após passar por todas as guardas, o arquivo é entregue ao instalador do sistema.

## ERROR State — Botão do Instalador do Sistema (v1.4.9)

Quando a instalação falha (ex: usuário cancelou o dialog do instalador), o card mostra estado `ERROR`. Se o arquivo baixado é um APK simples (não bundle) e existe no disco:

- `DownloadProgress.canUseSystemInstaller = true` → aparece botão `InstallMobile` ("Abrir instalador do sistema") para re-tentar
- Bundles: `canUseSystemInstaller = false` → apenas botão de dismiss

## Root — Uso Atual

Root **não** é mais usado para instalação. O `RootApkInstaller` ainda existe no código com seus métodos `@Deprecated`, mas a única função que usa root ativamente é:

- **`LogShareHelper.runLogcat()`** — captura logcat completo do sistema via `su -c logcat` (apenas quando root disponível)

O Settings mostra o texto: *"Root é usado apenas para captura completa de logs (Compartilhar Logs). A instalação de apps é delegada ao instalador do sistema."*

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
- FileProvider para acesso seguro aos arquivos (sem expor caminhos reais)
- Permissão REQUEST_INSTALL_PACKAGES solicitada no manifest

## Arquivos Relevantes

- [domain/installer/ApkInstaller.kt](../app/src/main/java/com/hyperos/updater/domain/installer/ApkInstaller.kt) — Interface
- [domain/installer/PackageManagerInstaller.kt](../app/src/main/java/com/hyperos/updater/domain/installer/PackageManagerInstaller.kt) — Delegação via ACTION_VIEW + FileProvider
- [domain/installer/RootApkInstaller.kt](../app/src/main/java/com/hyperos/updater/domain/installer/RootApkInstaller.kt) — @Deprecated, root apenas para LogShareHelper
- [domain/DownloadManager.kt](../app/src/main/java/com/hyperos/updater/domain/DownloadManager.kt) — Orquestrador: guardas + executeInstall + polling
- [di/InstallerModule.kt](../app/src/main/java/com/hyperos/updater/di/InstallerModule.kt) — Bindings (@Named root/fallback)
