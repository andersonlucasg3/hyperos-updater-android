package com.hyperos.updater.util

object XiaomiApps {

    // Recognized Xiaomi/HyperOS system app package names.
    // These are identified as "System" apps in the UI but checked via APKPure by package name.
    private val systemPackages = setOf(
        // Launcher & System UI
        "com.miui.home", "com.miui.system", "com.android.systemui",
        "com.miui.notification", "com.miui.miwallpaper", "com.miui.aod",

        // Core Apps
        "com.miui.gallery", "com.miui.notes", "com.miui.player",
        "com.miui.video", "com.miui.compass", "com.miui.calculator",
        "com.miui.screenrecorder", "com.miui.weather2", "com.miui.cleaner",
        "com.miui.powerkeeper", "com.miui.securitycenter", "com.miui.securityadd",
        "com.miui.guardprovider", "com.miui.securityinput",

        // Tools
        "com.xiaomi.scanner", "com.xiaomi.smarthome", "com.xiaomi.shop",
        "com.xiaomi.discover", "com.miui.miservice", "com.miui.bugreport",
        "com.xiaomi.password", "com.xiaomi.passwords", "com.miui.password",

        // Themes
        "com.android.thememanager", "com.miui.personalization",

        // Media
        "com.android.soundrecorder", "com.miui.mediaeditor", "com.miui.cit",

        // File & Storage
        "com.android.fileexplorer", "com.miui.backup", "com.miui.cloudservice",
        "com.miui.cloudbackup", "com.miui.cleanmaster",

        // Communication
        "com.android.contacts", "com.android.mms", "com.android.deskclock",
        "com.android.calendar", "com.android.email", "com.android.browser",
        "com.miui.voiceassist", "com.miui.virtualsim",

        // Account & Services
        "com.xiaomi.account", "com.xiaomi.payment", "com.xiaomi.simactivate",
        "com.xiaomi.finddevice", "com.xiaomi.xmsf", "com.xiaomi.xmsfkeeper",

        // Connectivity
        "com.xiaomi.mi_connect_service", "com.milink.service",
        "com.xiaomi.midrop", "com.xiaomi.miplay", "com.xiaomi.bluetooth",
        "com.xiaomi.mirror", "com.miui.mishare.connectivity",

        // AI & Assistant
        "com.xiaomi.aiasst.service", "com.xiaomi.aiasst.vision",
        "com.xiaomi.mis", "com.miui.translation", "com.miui.translationservice",

        // Gaming
        "com.xiaomi.ugd", "com.xiaomi.gamecenter", "com.xiaomi.gameservice",

        // Health
        "com.xiaomi.wearable", "com.xiaomi.mihealth", "com.xiaomi.health",

        // Camera
        "com.android.camera", "com.miui.camera",

        // Docs
        "com.miui.print", "com.miui.pdf", "com.miui.wps",

        // Accessibility & Settings
        "com.miui.accessibility", "com.miui.misound",
        "com.xiaomi.mibrain", "com.xiaomi.mibrain.speech",

        // Keyboard
        "com.miui.inputmethod", "com.baidu.input_mi",
        "com.sohu.inputmethod.sogou.xiaomi", "com.iflytek.inputmethod.miui",

        // Cast
        "com.miui.cast", "com.xiaomi.wirelessdisplay",

        // Framework
        "com.xiaomi.market", "com.miui.core", "com.miui.daemon",
        "com.miui.rom", "com.xiaomi.joyose", "com.xiaomi.metok",

        // Global/China Mi Apps
        "com.xiaomi.mipicks", "com.mi.globalbrowser",
        "com.mi.android.globalminusscreen", "com.mi.global.shop",

        // POCO
        "com.mi.android.globallauncher", "com.mi.globallayout",

        // Redmi
        "com.mi.android.go.globallauncher", "com.mi.android.go.globalminusscreen"
    )

    fun isXiaomiSystemApp(packageName: String): Boolean = packageName in systemPackages
}
