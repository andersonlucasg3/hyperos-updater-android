# Shizuku Setup

## Why Shizuku?

Shizuku provides ADB-level permissions without root. It enables:
- Silent APK installation via `pm install`
- Split APK installation via `pm install-create` + `install-write` + `install-commit`
- Bypasses package installer UI

## Setup Steps

### 1. Install Shizuku
Download from [shizuku.rikka.app](https://shizuku.rikka.app) or the release page.

### 2. Start Shizuku
Open Shizuku app → tap "Start" → choose "Wireless debugging":
- Enable Developer Options on your device
- Enable Wireless Debugging
- Tap "Pair device with pairing code"
- Enter the pairing code in Shizuku

### 3. Authorize HyperOS Updater
- Open HyperOS Updater → Settings (⚙) → Shizuku section
- Tap "Open Shizuku"
- In Shizuku: Authorized apps → enable HyperOS Updater
- Go back → tap "Refresh Status"
- Should show green "Connected"

### 4. Verify
Home screen top bar shows:
- 🛡️ green = Shizuku connected
- ⚠️ yellow/red = not configured

## Technical Details

### Manifest Configuration
```xml
<provider
    android:name="rikka.shizuku.ShizukuProvider"
    android:authorities="${applicationId}.shizuku"
    android:enabled="true"
    android:exported="true" />

<meta-data
    android:name="moe.shizuku.client.V3_SUPPORT"
    android:value="true" />

<uses-permission android:name="moe.shizuku.manager.permission.API_V23" />
```

**Critical:** The provider must NOT have `android:permission` attribute. Adding permission to the provider blocks Shizuku from delivering the Binder.

### API Usage
Shizuku 13 made `newProcess()` private. Our code uses reflection:
```kotlin
val method = Shizuku::class.java.declaredMethods.firstOrNull {
    it.name == "newProcess" && it.parameterTypes.size == 3
}
method?.isAccessible = true
val process = method.invoke(null, cmd, null, null) as? Process
```

### Install Methods

**Single APK:**
```
pm install -r -d (via stdin pipe)
```

**Split APK (APKM/XAPK/APKS):**
```
pm install-create -r -d
pm install-write -S <size> <sessionId> <name> -   (per split, via stdin)
pm install-commit <sessionId>
```

### SELinux / Permission Issues

HyperOS has strict SELinux policies:
- `system_server` (pm install) can only read files from `/data/local/tmp/`
- App private directories (`/data/data/...`) are inaccessible to shell UID
- Solution: pipe APK content via stdin instead of file path

### Known Issue

Split APK session install (`install-create` + `install-write` + `install-commit`) fails on HyperOS 3.1. Exit code 1 with no error output. Single APK via stdin pipe works.
