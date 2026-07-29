package com.hyperos.updater.data.repository

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import com.hyperos.updater.data.local.dao.TrackedAppDao
import com.hyperos.updater.data.remote.ApkComboResult
import com.hyperos.updater.data.remote.ApkComboService
import com.hyperos.updater.data.remote.ApkPureResult
import com.hyperos.updater.data.remote.ApkMirrorService
import com.hyperos.updater.data.remote.ApkPureService
import com.hyperos.updater.data.remote.AptoideService
import com.hyperos.updater.data.remote.FDroidService
import com.hyperos.updater.data.remote.GitHubService
import com.hyperos.updater.data.remote.MemeOsService
import com.hyperos.updater.data.remote.TencentService
import com.hyperos.updater.data.remote.UptodownService
import com.hyperos.updater.domain.model.AppInfo
import com.hyperos.updater.domain.model.AppType
import com.hyperos.updater.domain.model.AppUpdate
import com.hyperos.updater.domain.model.SourceVersion
import com.hyperos.updater.domain.model.UpdateSource
import com.hyperos.updater.domain.repository.AppUpdateRepository
import com.hyperos.updater.util.VersionComparator
import com.hyperos.updater.util.XiaomiApps
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppUpdateRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val trackedAppDao: TrackedAppDao,
    private val apkPureService: ApkPureService,
    private val apkComboService: ApkComboService,
    private val aptoideService: AptoideService,
    private val fDroidService: FDroidService,
    private val apkMirrorService: ApkMirrorService,
    private val gitHubService: GitHubService,
    private val memeOsService: MemeOsService,
    private val uptodownService: UptodownService,
    private val tencentService: TencentService
) : AppUpdateRepository {

    override suspend fun getInstalledApps(appType: AppType): List<AppInfo> =
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val packages = pm.getInstalledPackages(0) ?: emptyList()
            val selfPkg = context.packageName

            packages.mapNotNull { pkg ->
                val info = pkg.applicationInfo ?: return@mapNotNull null
                val flags = info.flags
                val isSystem = (flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val isUpdatedSystem = (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                val isXiaomi = XiaomiApps.isXiaomiSystemApp(pkg.packageName)

                if (pkg.packageName == selfPkg) return@mapNotNull null

                val matches = when (appType) {
                    AppType.SYSTEM -> isSystem || isUpdatedSystem || isXiaomi
                    AppType.THIRD_PARTY -> !isSystem && !isUpdatedSystem && !isXiaomi
                }
                if (!matches) return@mapNotNull null

                val version = pkg.versionName
                if (version == null || version.isBlank() || version.all { it == '0' || it == '.' }) return@mapNotNull null

                AppInfo(
                    packageName = pkg.packageName,
                    appName = info.loadLabel(pm)?.toString() ?: pkg.packageName,
                    versionName = version,
                    versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        pkg.longVersionCode
                    } else {
                        @Suppress("DEPRECATION") pkg.versionCode.toLong()
                    },
                    isSystemApp = isSystem || isUpdatedSystem
                )
            }
        }

    override fun checkSystemAppUpdates(): Flow<AppUpdate> = flow {
        val installed = getInstalledApps(AppType.SYSTEM)
        val catalog = memeOsService.fetchSystemAppsCatalog(forceRefresh = true)
        val semaphore = Semaphore(6)

        supervisorScope {
            installed.map { app ->
                async {
                    semaphore.withPermit {
                        checkOneSystemApp(app, catalog)
                    }
                }
            }.forEach { deferred ->
                emit(deferred.await())
            }
        }
    }

    private suspend fun checkOneSystemApp(app: AppInfo, catalog: Map<String, String>): AppUpdate {
        try {
            trackedAppDao.updateCurrentVersion(app.packageName, app.versionName, System.currentTimeMillis())

            if (app.packageName !in catalog) {
                return untrackedSystemApp(app)
            }

            val details = try { memeOsService.getAppDetails(app.packageName) } catch (_: Exception) { null }
                ?: return untrackedSystemApp(app)

            // Xiaomi versionCodes aren't monotonic across regions/branches (gl vs cn use
            // different epochs), so versionName has veto: code only decides when the
            // semantic comparison doesn't indicate a downgrade. versionCode must never
            // cross version LINES either (e.g. 0.0.0 vs 0.0.0-R are different lines).
            val sameLine = VersionComparator.isSameLine(app.versionName, details.versionName)
            val semanticNewer = VersionComparator.isNewer(app.versionName, details.versionName)
            val semanticOlder = VersionComparator.isNewer(details.versionName, app.versionName)
            val codeNewer = details.versionCode > 0 && app.versionCode > 0 && details.versionCode > app.versionCode
            val hasUpdate = sameLine && (semanticNewer || (codeNewer && !semanticOlder))
            if (hasUpdate) {
                Log.i("MemeOs", "UPDATE ${app.packageName}: ${app.versionName} (${app.versionCode}) → ${details.versionName} (${details.versionCode})")
            }

            return AppUpdate(
                packageName = app.packageName,
                appName = app.appName,
                currentVersion = app.versionName,
                latestVersion = if (hasUpdate) details.versionName else app.versionName,
                latestVersionCode = if (details.versionCode > 0) details.versionCode else app.versionCode,
                fileSize = details.fileSizeBytes,
                downloadUrl = details.downloadUrl,
                changelog = null,
                publishedDate = details.publishedDate,
                updateSource = UpdateSource.MEMEOS,
                appType = AppType.SYSTEM,
                sourceVersions = listOf(SourceVersion(UpdateSource.MEMEOS, details.versionName, details.downloadUrl))
            )
        } catch (e: Exception) {
            Log.e("AppUpdateRepo", "System app check failed for ${app.packageName}", e)
            return untrackedSystemApp(app)
        }
    }

    private fun untrackedSystemApp(app: AppInfo): AppUpdate = AppUpdate(
        packageName = app.packageName,
        appName = app.appName,
        currentVersion = app.versionName,
        latestVersion = app.versionName,
        latestVersionCode = app.versionCode,
        fileSize = null,
        downloadUrl = null,
        changelog = null,
        publishedDate = null,
        updateSource = UpdateSource.UNTRACKED,
        appType = AppType.SYSTEM
    )

    override fun checkThirdPartyAppUpdates(): Flow<AppUpdate> = flow {
        val installed = getInstalledApps(AppType.THIRD_PARTY)
        checkWithSources(installed, AppType.THIRD_PARTY).collect { emit(it) }
    }

    override suspend fun recheckApp(packageName: String, appType: AppType): AppUpdate =
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val pkgInfo = try {
                pm.getPackageInfo(packageName, 0)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }

            if (pkgInfo == null) {
                return@withContext AppUpdate(
                    packageName = packageName,
                    appName = packageName,
                    currentVersion = "",
                    latestVersion = "",
                    latestVersionCode = 0L,
                    fileSize = null,
                    downloadUrl = null,
                    changelog = null,
                    publishedDate = null,
                    updateSource = UpdateSource.UNTRACKED,
                    appType = appType
                )
            }

            val info = pkgInfo.applicationInfo
            val versionName = pkgInfo.versionName
            if (info == null || versionName == null ||
                versionName.isBlank() ||
                versionName.all { it == '0' || it == '.' }
            ) {
                return@withContext AppUpdate(
                    packageName = packageName,
                    appName = info?.loadLabel(pm)?.toString() ?: packageName,
                    currentVersion = versionName ?: "",
                    latestVersion = versionName ?: "",
                    latestVersionCode = 0L,
                    fileSize = null,
                    downloadUrl = null,
                    changelog = null,
                    publishedDate = null,
                    updateSource = UpdateSource.UNTRACKED,
                    appType = appType
                )
            }

            val app = AppInfo(
                packageName = pkgInfo.packageName,
                appName = info.loadLabel(pm)?.toString() ?: pkgInfo.packageName,
                versionName = versionName,
                versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    pkgInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION") pkgInfo.versionCode.toLong()
                },
                isSystemApp = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                    (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            )

            when (appType) {
                AppType.SYSTEM -> {
                    val catalog = memeOsService.fetchSystemAppsCatalog(forceRefresh = false)
                    checkOneSystemApp(app, catalog)
                }
                AppType.THIRD_PARTY -> checkOneThirdPartyApp(app, appType)
            }
        }

    private fun checkWithSources(
        apps: List<AppInfo>,
        appType: AppType
    ): Flow<AppUpdate> = flow {
        val semaphore = Semaphore(6)

        supervisorScope {
            apps.map { app ->
                async {
                    semaphore.withPermit {
                        checkOneThirdPartyApp(app, appType)
                    }
                }
            }.forEach { deferred ->
                emit(deferred.await())
            }
        }
    }

    /**
     * Two-phase third-party update check.
     *
     * Phase 1 (cheap JSON APIs, parallel): Aptoide, F-Droid, GitHub, Tencent.
     *   If ANY phase-1 source returns a non-null result — the app is KNOWN to at
     *   least one API — we skip phase 2 entirely and build the AppUpdate from
     *   phase-1 results alone (same pickBest / isNewer plumbing).
     *
     * Phase 2 (HTML scrapers, parallel): APKPure, APKCombo, APKMirror, MemeOS,
     *   Uptodown. Only runs when every phase-1 source returned null (app unknown
     *   to all JSON APIs). These scrapers are ~4-8× slower than JSON APIs, so
     *   avoiding them in the common case saves significant wall-clock time.
     *
     * Trade-off: less cross-checking when an API resolves the app — a version
     *   that only exists on a scraper-only source will be missed if ANY API
     *   already knows the package. In practice the JSON APIs (especially F-Droid
     *   and Aptoide) cover the vast majority of packages.
     */
    private suspend fun checkOneThirdPartyApp(app: AppInfo, appType: AppType = AppType.THIRD_PARTY): AppUpdate =
        coroutineScope {
            try {
                trackedAppDao.updateCurrentVersion(app.packageName, app.versionName, System.currentTimeMillis())

                // ---- Phase 1: cheap JSON APIs ----
                val aptoideDeferred = async { tryAptoide(app.packageName) }
                val fdroidDeferred = async { tryFDroid(app.packageName) }
                val githubDeferred = async { tryGitHub(app.packageName) }
                val tencentDeferred = async { tryTencent(app.packageName) }
                val aptoideResult = aptoideDeferred.await()
                val fdroidResult = fdroidDeferred.await()
                val githubResult = githubDeferred.await()
                val tencentResult = tencentDeferred.await()

                val phase1Results = listOfNotNull(aptoideResult, fdroidResult, githubResult, tencentResult)

                // ---- Phase 2: HTML scrapers (only when no API knows this app) ----
                val pureResult: SourceResult?
                val comboResult: SourceResult?
                val mirrorResult: SourceResult?
                val memeosResult: SourceResult?
                val uptodownResult: SourceResult?

                if (phase1Results.isEmpty()) {
                    Log.d("AppUpdateRepo", "Phase 2 scraping for ${app.packageName} — no API source knows this app")
                    val pureDeferred = async { tryApkPure(app.packageName) }
                    val comboDeferred = async { tryApkCombo(app.packageName) }
                    val mirrorDeferred = async { tryApkMirror(app) }
                    val memeosDeferred = async { tryMemeOs(app.packageName) }
                    val uptodownDeferred = async { tryUptodown(app) }
                    pureResult = pureDeferred.await()
                    comboResult = comboDeferred.await()
                    mirrorResult = mirrorDeferred.await()
                    memeosResult = memeosDeferred.await()
                    uptodownResult = uptodownDeferred.await()
                } else {
                    Log.d("AppUpdateRepo", "Phase 2 skipped for ${app.packageName} — found in ${phase1Results.joinToString { it.source.name }}")
                    pureResult = null
                    comboResult = null
                    mirrorResult = null
                    memeosResult = null
                    uptodownResult = null
                }

                // Collect all source versions that are genuinely newer than installed
                val allSourceResults = listOfNotNull(pureResult, comboResult, aptoideResult, fdroidResult, mirrorResult, githubResult, memeosResult, uptodownResult, tencentResult)
                val sourceVersions = allSourceResults
                    .filter { VersionComparator.isNewer(app.versionName, it.versionName) }
                    .map { SourceVersion(it.source, it.versionName, it.downloadUrl) }

                // If F-Droid found it with real versionCode > installed, it's an update —
                // but only within the same version line (code must not cross lines)
                val hasUpdate = (fdroidResult != null && fdroidResult.versionCode > app.versionCode &&
                    VersionComparator.isSameLine(app.versionName, fdroidResult.versionName)) ||
                    sourceVersions.any { VersionComparator.isNewer(app.versionName, it.version) }

                // Best = highest version from NEWER sources only
                val best = if (hasUpdate) pickBest(
                    pureResult?.takeIf { sourceVersions.any { sv -> sv.source == UpdateSource.APKPURE } },
                    comboResult?.takeIf { sourceVersions.any { sv -> sv.source == UpdateSource.APKCOMBO } },
                    aptoideResult?.takeIf { sourceVersions.any { sv -> sv.source == UpdateSource.APTOIDE } },
                    fdroidResult?.takeIf { sourceVersions.any { sv -> sv.source == UpdateSource.FDROID } },
                    mirrorResult?.takeIf { sourceVersions.any { sv -> sv.source == UpdateSource.APKMIRROR } },
                    githubResult?.takeIf { sourceVersions.any { sv -> sv.source == UpdateSource.GITHUB } },
                    uptodownResult?.takeIf { sourceVersions.any { sv -> sv.source == UpdateSource.UPTODOWN } },
                    memeosResult?.takeIf { sourceVersions.any { sv -> sv.source == UpdateSource.MEMEOS } },
                    tencentResult?.takeIf { sourceVersions.any { sv -> sv.source == UpdateSource.TENCENT } }
                ) else null

                // Use real versionCode from FDroid if available, else best source
                val realVersionCode = fdroidResult?.versionCode ?: best?.versionCode ?: app.versionCode
                val primarySource = best?.source ?: UpdateSource.UNTRACKED

                AppUpdate(
                    packageName = app.packageName,
                    appName = app.appName,
                    currentVersion = app.versionName,
                    latestVersion = if (hasUpdate) best?.versionName ?: app.versionName else app.versionName,
                    latestVersionCode = realVersionCode,
                    fileSize = best?.fileSize,
                    downloadUrl = best?.downloadUrl,
                    changelog = null,
                    publishedDate = null,
                    updateSource = primarySource,
                    appType = appType,
                    sourceVersions = sourceVersions
                )
            } catch (e: Exception) {
                Log.e("AppUpdateRepo", "Third-party check failed for ${app.packageName}", e)
                AppUpdate(
                    packageName = app.packageName,
                    appName = app.appName,
                    currentVersion = app.versionName,
                    latestVersion = app.versionName,
                    latestVersionCode = app.versionCode,
                    fileSize = null,
                    downloadUrl = null,
                    changelog = null,
                    publishedDate = null,
                    updateSource = UpdateSource.UNTRACKED,
                    appType = appType
                )
            }
        }

    private fun pickBest(
        pure: SourceResult?, combo: SourceResult?, aptoide: SourceResult?, fdroid: SourceResult?,
        mirror: SourceResult?, github: SourceResult?, uptodown: SourceResult?, memeos: SourceResult? = null,
        tencent: SourceResult? = null
    ): SourceResult? {
        val list = listOfNotNull(pure, combo, aptoide, fdroid, mirror, github, uptodown, memeos, tencent)
        if (list.isEmpty()) return null
        if (list.size == 1) return list.first()
        // APKCombo is last resort — many listings have no actual download
        val nonCombo = list.filter { it.source != UpdateSource.APKCOMBO }
        val candidates = nonCombo.ifEmpty { list }
        return candidates.maxWithOrNull { a, b ->
            VersionComparator.compare(a.versionName, a.versionCode, b.versionName, b.versionCode)
        }
    }

    private suspend fun tryApkPure(pkg: String): SourceResult? = try {
        val r = apkPureService.checkVersion(pkg) ?: return null
        SourceResult(r.versionName, 0L, r.downloadUrl, null, UpdateSource.APKPURE)
    } catch (_: Exception) { null }

    private suspend fun tryApkCombo(pkg: String): SourceResult? = try {
        val r = apkComboService.search(pkg) ?: return null
        SourceResult(r.versionName, r.versionCode, r.downloadUrl, r.fileSize, UpdateSource.APKCOMBO)
    } catch (_: Exception) { null }

    private suspend fun tryAptoide(pkg: String): SourceResult? = try {
        val r = aptoideService.checkVersion(pkg) ?: return null
        SourceResult(r.versionName, r.versionCode, r.downloadUrl, r.fileSize, UpdateSource.APTOIDE)
    } catch (_: Exception) { null }

    private suspend fun tryUptodown(app: AppInfo): SourceResult? = try {
        val r = uptodownService.checkVersion(app.packageName) ?: return null
        SourceResult(r.versionName, r.versionCode, r.downloadUrl, null, UpdateSource.UPTODOWN)
    } catch (_: Exception) { null }

    private suspend fun tryFDroid(pkg: String): SourceResult? = try {
        val r = fDroidService.checkVersion(pkg) ?: return null
        SourceResult(r.versionName, r.versionCode, r.downloadUrl, null, UpdateSource.FDROID)
    } catch (_: Exception) { null }

    private suspend fun tryApkMirror(app: AppInfo): SourceResult? = try {
        val items = apkMirrorService.searchByName(app.appName)
        // Find the item whose name best matches the installed app
        val match = items.firstOrNull { item ->
            item.appName.lowercase().contains(app.appName.lowercase().take(4)) ||
            app.appName.lowercase().contains(item.appName.lowercase().take(4))
        } ?: items.firstOrNull() ?: return null
        val version = match.version ?: return null
        SourceResult(version, 0L, match.pageUrl, null, UpdateSource.APKMIRROR)
    } catch (_: Exception) { null }

    private suspend fun tryGitHub(pkg: String): SourceResult? = try {
        val r = gitHubService.checkRelease(pkg) ?: return null
        SourceResult(r.versionName, r.versionCode, r.downloadUrl, null, UpdateSource.GITHUB)
    } catch (_: Exception) { null }

    private suspend fun tryMemeOs(pkg: String): SourceResult? = try {
        val r = memeOsService.checkVersion(pkg) ?: return null
        SourceResult(r.versionName, 0L, r.downloadUrl, null, UpdateSource.MEMEOS)
    } catch (_: Exception) { null }

    private suspend fun tryTencent(pkg: String): SourceResult? = try {
        val r = tencentService.checkVersion(pkg) ?: return null
        SourceResult(r.versionName, 0L, r.downloadUrl, null, UpdateSource.TENCENT)
    } catch (_: Exception) { null }
}

private data class SourceResult(
    val versionName: String,
    val versionCode: Long,
    val downloadUrl: String?,
    val fileSize: Long?,
    val source: UpdateSource
)
