package com.hyperos.updater.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.hyperos.updater.data.local.entity.UpdateHistoryEntity

@Dao
interface UpdateHistoryDao {
    @Query("SELECT * FROM update_history WHERE packageName = :pkg ORDER BY installedAt DESC")
    suspend fun getByPackage(pkg: String): List<UpdateHistoryEntity>

    @Insert
    suspend fun insert(entry: UpdateHistoryEntity)
}
