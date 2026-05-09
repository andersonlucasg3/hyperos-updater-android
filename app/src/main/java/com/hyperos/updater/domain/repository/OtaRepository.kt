package com.hyperos.updater.domain.repository

import com.hyperos.updater.domain.model.OtaUpdate

interface OtaRepository {
    suspend fun checkForUpdate(
        codename: String,
        branch: String,
        region: String,
        currentVersion: String,
        isGlobal: Int
    ): OtaUpdate?
}
