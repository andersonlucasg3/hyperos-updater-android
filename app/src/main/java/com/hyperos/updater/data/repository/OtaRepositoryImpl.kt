package com.hyperos.updater.data.repository

import android.os.Build
import android.util.Log
import com.hyperos.updater.data.local.dao.OtaUpdateDao
import com.hyperos.updater.data.local.entity.OtaUpdateEntity
import com.hyperos.updater.data.remote.OtaApi
import com.hyperos.updater.domain.model.OtaUpdate
import com.hyperos.updater.domain.repository.OtaRepository
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OtaRepositoryImpl @Inject constructor(
    private val otaApi: OtaApi,
    private val otaUpdateDao: OtaUpdateDao
) : OtaRepository {

    override suspend fun checkForUpdate(
        codename: String,
        branch: String,
        region: String,
        currentVersion: String,
        isGlobal: Int
    ): OtaUpdate? {
        val androidVer = Build.VERSION.RELEASE?.split(".")?.firstOrNull()?.toIntOrNull() ?: 16
        val sdk = if (Build.VERSION.SDK_INT >= 31) Build.VERSION.SDK_INT else 36

        val response = tryLegacyPost(codename, branch, region, currentVersion, isGlobal, androidVer, sdk)
            ?: tryLegacyGet(codename, branch, region, currentVersion, isGlobal, androidVer, sdk)
            ?: return null

        val newVersion = response.version ?: return null
        if (newVersion == currentVersion || newVersion.isEmpty()) return null

        val fileSize = response.filesize?.toLongOrNull() ?: 0L
        val downloadUrl = response.ultimateota
            ?: response.superota
            ?: response.cdnorg
            ?: response.aliyuncs

        // Always return the update even if no download URL (might be restricted)
        otaUpdateDao.upsert(
            OtaUpdateEntity(
                version = newVersion,
                androidVersion = response.androidVersion ?: "",
                branch = response.branch ?: branch,
                fileSize = fileSize,
                md5 = response.md5,
                changelog = response.changelog,
                filename = response.filename,
                checkedAt = System.currentTimeMillis(),
                installedVersion = currentVersion
            )
        )

        return OtaUpdate(
            version = newVersion,
            androidVersion = response.androidVersion ?: "",
            branch = response.branch ?: branch,
            fileSize = fileSize,
            md5 = response.md5,
            changelog = response.changelog,
            downloadUrl = downloadUrl,
            filename = response.filename,
            publishedDate = null
        )
    }

    private suspend fun tryLegacyPost(
        codename: String, branch: String, region: String,
        currentVersion: String, isGlobal: Int,
        androidVer: Int, sdk: Int
    ) = try {
        Log.d("OtaRepo", "POST legacy: d=$codename v=$currentVersion")
        otaApi.checkUpdateLegacy(codename, branch, region, currentVersion, isGlobal, region, codename, androidVer, sdk)
    } catch (e: HttpException) {
        Log.w("OtaRepo", "Legacy POST failed: HTTP ${e.code()} ${e.message()}")
        null
    } catch (e: Exception) {
        Log.w("OtaRepo", "Legacy POST error: ${e.message}")
        null
    }

    private suspend fun tryLegacyGet(
        codename: String, branch: String, region: String,
        currentVersion: String, isGlobal: Int,
        androidVer: Int, sdk: Int
    ) = try {
        Log.d("OtaRepo", "GET legacy")
        otaApi.checkUpdateLegacyGet(codename, branch, region, currentVersion, isGlobal, region, codename, androidVer, sdk)
    } catch (e: HttpException) {
        Log.w("OtaRepo", "Legacy GET failed: HTTP ${e.code()}")
        null
    } catch (e: Exception) {
        Log.w("OtaRepo", "Legacy GET error: ${e.message}")
        null
    }
}
