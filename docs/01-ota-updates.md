# Atualizações OTA do Sistema

## Fonte de Dados

**API Oficial da Xiaomi:** `https://update.miui.com/updates/miotaV3.php`

Esta é a mesma API que o sistema HyperOS usa nativamente para verificar updates.

## Fluxo

```
Início → DeviceRepository.getDeviceInfo()
           ↓ 
           Detecta: codename, miuiVersion, region
           ↓
       OtaRepository.checkForUpdate()
           ↓
           POST para update.miui.com
           Parâmetros: d=popsicle, b=F, c=CN, v=versao_atual, is_global=0
           ↓
           Resposta JSON: version, filesize, md5, changelog, CDN URLs
           ↓
       Compara versão atual vs nova
           ↓
       Se nova → UpdateState.Available
       Se igual → UpdateState.Idle
           ↓
       Download: OkHttp streaming com progresso
           ↓
       MD5 verification (opcional)
           ↓
       Arquivo salvo em filesDir/downloads/
```

## API

### Request
```
POST https://update.miui.com/updates/miotaV3.php
Content-Type: application/x-www-form-urlencoded

d=popsicle          → codename do dispositivo
b=F                 → branch (F=Stable, X=Beta)
c=CN                → região (CN=China, GL=Global)
v=OS3.0.17.0.WPBCNXM → versão atual instalada
is_global=0         → 0=China ROM, 1=Global ROM
r=CN                → região code
pn=popsicle         → product name
```

### Response
```json
{
  "version": "OS3.0.306.0.WPBCNXM",
  "android": "16",
  "branch": "F",
  "description": "<html>Changelog...</html>",
  "filesize": "6029087338",
  "md5": "abc123...",
  "filename": "popsicle-ota_full-OS3.0.306.0.WPBCNXM.zip",
  "ultimateota": "https://ultimateota.d.miui.com/...",
  "superota": "https://superota.d.miui.com/...",
  "cdnorg": "https://cdnorg.d.miui.com/...",
  "aliyuncs": "https://aliyuncs.com/..."
}
```

## Decisões de Design

1. **Nunca cachear URLs de download** — Os links são tokenizados e expiram em ~4 dias.
   As URLs são sempre obtidas frescas da API no momento do download.

2. **Múltiplos CDNs** — Tentamos os 4 mirrors (ultimateota, superota, cdnorg, aliyuncs)
   em ordem. Se o primeiro falhar, o usuário pode tentar o próximo.

3. **Download apenas** — A instalação da ROM OTA precisa ser feita pelo sistema.
   O app apenas faz o download do pacote e notifica o usuário.

## Arquivos Relevantes

- [data/remote/OtaApi.kt](../app/src/main/java/com/hyperos/updater/data/remote/OtaApi.kt) — Interface Retrofit
- [data/remote/dto/OtaResponse.kt](../app/src/main/java/com/hyperos/updater/data/remote/dto/OtaResponse.kt) — DTO
- [data/repository/OtaRepositoryImpl.kt](../app/src/main/java/com/hyperos/updater/data/repository/OtaRepositoryImpl.kt) — Implementação
- [domain/usecase/CheckOtaUpdateUseCase.kt](../app/src/main/java/com/hyperos/updater/domain/usecase/CheckOtaUpdateUseCase.kt) — Caso de uso
- [ui/screens/ota/OtaViewModel.kt](../app/src/main/java/com/hyperos/updater/ui/screens/ota/OtaViewModel.kt) — ViewModel
- [ui/screens/ota/OtaScreen.kt](../app/src/main/java/com/hyperos/updater/ui/screens/ota/OtaScreen.kt) — UI
