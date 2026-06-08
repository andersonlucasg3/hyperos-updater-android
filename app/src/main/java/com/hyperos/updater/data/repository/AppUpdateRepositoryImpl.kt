package com.hyperos.updater.data.repository

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.hyperos.updater.data.local.dao.TrackedAppDao
import com.hyperos.updater.data.remote.ApkComboResult
import com.hyperos.updater.data.remote.ApkComboService
import com.hyperos.updater.data.remote.ApkPureResult
import com.hyperos.updater.data.remote.ApkMirrorService
import com.hyperos.updater.data.remote.ApkPureService
import com.hyperos.updater.data.remote.FDroidService
import com.hyperos.updater.data.remote.GitHubService
import com.hyperos.updater.data.remote.MemeOsService
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
    private val fDroidService: FDroidService,
    private val apkMirrorService: ApkMirrorService,
    private val gitHubService: GitHubService,
    private val memeOsService: MemeOsService
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

                AppInfo(
                    packageName = pkg.packageName,
                    appName = info.loadLabel(pm)?.toString() ?: pkg.packageName,
                    versionName = pkg.versionName ?: "0",
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
        checkWithSources(installed, AppType.SYSTEM).collect { emit(it) }
    }

    override fun checkThirdPartyAppUpdates(): Flow<AppUpdate> = flow {
        val installed = getInstalledApps(AppType.THIRD_PARTY)
        checkWithSources(installed, AppType.THIRD_PARTY).collect { emit(it) }
    }

    private fun checkWithSources(
        apps: List<AppInfo>,
        appType: AppType
    ): Flow<AppUpdate> = flow {
        val semaphore = Semaphore(6) // 6 concurrent apps, each queries 4 sources
        val now = System.currentTimeMillis()

        coroutineScope {
            apps.map { app ->
                async {
                    semaphore.withPermit {
                        trackedAppDao.updateCurrentVersion(app.packageName, app.versionName, now)

                        // Query all six sources in parallel
                        val pureDeferred = async { tryApkPure(app.packageName) }
                        val comboDeferred = async { tryApkCombo(app.packageName) }
                        val fdroidDeferred = async { tryFDroid(app.packageName) }
                        val mirrorDeferred = async { tryApkMirror(app) }
                        val githubDeferred = async { tryGitHub(app.packageName) }
                        val memeosDeferred = async { tryMemeOs(app.packageName) }
                        val pureResult = pureDeferred.await()
                        val comboResult = comboDeferred.await()
                        val fdroidResult = fdroidDeferred.await()
                        val mirrorResult = mirrorDeferred.await()
                        val githubResult = githubDeferred.await()
                        val memeosResult = memeosDeferred.await()

                        // Collect all source versions that are genuinely newer than installed
                        val allSourceResults = listOfNotNull(pureResult, comboResult, fdroidResult, mirrorResult, githubResult, memeosResult)
                        val sourceVersions = allSourceResults
                            .filter { VersionComparator.isNewer(app.versionName, it.versionName) }
                            .map { SourceVersion(it.source, it.versionName, it.downloadUrl) }

                        // If F-Droid found it with real versionCode > installed, it's an update
                        val hasUpdate = (fdroidResult != null && fdroidResult.versionCode > app.versionCode) ||
                            sourceVersions.any { VersionComparator.isNewer(app.versionName, it.version) }

                        // Best = highest version from NEWER sources only
                        val best = if (hasUpdate) pickBest(
                            pureResult?.takeIf { sourceVersions.any { sv -> sv.source == UpdateSource.APKPURE } },
                            comboResult?.takeIf { sourceVersions.any { sv -> sv.source == UpdateSource.APKCOMBO } },
                            fdroidResult?.takeIf { sourceVersions.any { sv -> sv.source == UpdateSource.FDROID } },
                            mirrorResult?.takeIf { sourceVersions.any { sv -> sv.source == UpdateSource.APKMIRROR } },
                            githubResult?.takeIf { sourceVersions.any { sv -> sv.source == UpdateSource.GITHUB } },
                            memeosResult?.takeIf { sourceVersions.any { sv -> sv.source == UpdateSource.MEMEOS } }
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
                    }
                }
            }.forEach { deferred ->
                emit(deferred.await())
            }
        }
    }

    private fun pickBest(
        pure: SourceResult?, combo: SourceResult?, fdroid: SourceResult?, mirror: SourceResult?, github: SourceResult?, memeos: SourceResult? = null
    ): SourceResult? {
        val list = listOfNotNull(pure, combo, fdroid, mirror, github, memeos)
        if (list.isEmpty()) return null
        if (list.size == 1) return list.first()
        // APKCombo is last resort — many listings have no actual download
        val nonCombo = list.filter { it.source != UpdateSource.APKCOMBO }
        val candidates = nonCombo.ifEmpty { list }
        return candidates.maxWithOrNull { a, b ->
            if (a.versionCode > 0 && b.versionCode > 0) a.versionCode.compareTo(b.versionCode)
            else if (VersionComparator.isNewer(a.versionName, b.versionName)) 1 else -1
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
}

private data class SourceResult(
    val versionName: String,
    val versionCode: Long,
    val downloadUrl: String?,
    val fileSize: Long?,
    val source: UpdateSource
)
