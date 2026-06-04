package com.hyperos.updater.data.repository

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.hyperos.updater.data.local.dao.TrackedAppDao
import com.hyperos.updater.data.remote.ApkComboResult
import com.hyperos.updater.data.remote.ApkComboService
import com.hyperos.updater.data.remote.ApkPureResult
import com.hyperos.updater.data.remote.ApkPureService
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
    private val apkComboService: ApkComboService
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
        val semaphore = Semaphore(8) // 8 concurrent apps, each queries 2 sources
        val now = System.currentTimeMillis()

        coroutineScope {
            apps.map { app ->
                async {
                    semaphore.withPermit {
                        trackedAppDao.updateCurrentVersion(app.packageName, app.versionName, now)

                        // Query both sources in parallel
                        val pureDeferred = async { tryApkPure(app.packageName) }
                        val comboDeferred = async { tryApkCombo(app.packageName) }
                        val pureResult = pureDeferred.await()
                        val comboResult = comboDeferred.await()

                        // Collect all source versions
                        val sourceVersions = listOfNotNull(pureResult, comboResult).map {
                            SourceVersion(it.source, it.versionName, it.downloadUrl)
                        }

                        // Best = highest version from any source
                        val best = pickBest(pureResult, comboResult)
                        val foundSources = pureResult != null || comboResult != null

                        AppUpdate(
                            packageName = app.packageName,
                            appName = app.appName,
                            currentVersion = app.versionName,
                            latestVersion = best?.versionName ?: app.versionName,
                            latestVersionCode = best?.versionCode ?: app.versionCode,
                            fileSize = best?.fileSize,
                            downloadUrl = best?.downloadUrl,
                            changelog = null,
                            publishedDate = null,
                            updateSource = best?.source ?: if (foundSources) pureResult?.source ?: comboResult!!.source else UpdateSource.UNTRACKED,
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
        pure: SourceResult?,
        combo: SourceResult?
    ): SourceResult? {
        if (pure == null && combo == null) return null
        if (pure == null) return combo
        if (combo == null) return pure
        return if (VersionComparator.isNewer(pure.versionName, combo.versionName)) combo else pure
    }

    private suspend fun tryApkPure(pkg: String): SourceResult? = try {
        val r = apkPureService.checkVersion(pkg) ?: return null
        SourceResult(r.versionName, 0L, r.downloadUrl, null, UpdateSource.APKPURE)
    } catch (_: Exception) { null }

    private suspend fun tryApkCombo(pkg: String): SourceResult? = try {
        val r = apkComboService.search(pkg) ?: return null
        SourceResult(r.versionName, r.versionCode, r.downloadUrl, r.fileSize, UpdateSource.APKCOMBO)
    } catch (_: Exception) { null }
}

private data class SourceResult(
    val versionName: String,
    val versionCode: Long,
    val downloadUrl: String?,
    val fileSize: Long?,
    val source: UpdateSource
)
