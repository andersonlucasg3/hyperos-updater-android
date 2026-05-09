package com.hyperos.updater.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.hyperos.updater.data.local.entity.OtaUpdateEntity

@Dao
interface OtaUpdateDao {
    @Query("SELECT * FROM ota_updates WHERE id = 1")
    suspend fun getLatest(): OtaUpdateEntity?

    @Upsert
    suspend fun upsert(update: OtaUpdateEntity)

    @Query("DELETE FROM ota_updates")
    suspend fun clear()
}
