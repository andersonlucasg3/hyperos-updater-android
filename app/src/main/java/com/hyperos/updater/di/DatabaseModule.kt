package com.hyperos.updater.di

import android.content.Context
import androidx.room.Room
import com.hyperos.updater.data.local.AppDatabase
import com.hyperos.updater.data.local.dao.OtaUpdateDao
import com.hyperos.updater.data.local.dao.TrackedAppDao
import com.hyperos.updater.data.local.dao.UpdateHistoryDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "hyperos_updater.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideOtaUpdateDao(db: AppDatabase): OtaUpdateDao = db.otaUpdateDao()

    @Provides
    fun provideTrackedAppDao(db: AppDatabase): TrackedAppDao = db.trackedAppDao()

    @Provides
    fun provideUpdateHistoryDao(db: AppDatabase): UpdateHistoryDao = db.updateHistoryDao()
}
