package com.hyperos.updater.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.hyperos.updater.data.local.dao.OtaUpdateDao
import com.hyperos.updater.data.local.dao.TrackedAppDao
import com.hyperos.updater.data.local.dao.UpdateHistoryDao
import com.hyperos.updater.data.local.entity.OtaUpdateEntity
import com.hyperos.updater.data.local.entity.TrackedAppEntity
import com.hyperos.updater.data.local.entity.UpdateHistoryEntity

@Database(
    entities = [OtaUpdateEntity::class, TrackedAppEntity::class, UpdateHistoryEntity::class],
    version = 1
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun otaUpdateDao(): OtaUpdateDao
    abstract fun trackedAppDao(): TrackedAppDao
    abstract fun updateHistoryDao(): UpdateHistoryDao
}
