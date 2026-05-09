package com.hyperos.updater.di

import com.hyperos.updater.data.repository.AppUpdateRepositoryImpl
import com.hyperos.updater.data.repository.DeviceRepositoryImpl
import com.hyperos.updater.data.repository.OtaRepositoryImpl
import com.hyperos.updater.data.repository.PreferencesRepositoryImpl
import com.hyperos.updater.domain.repository.AppUpdateRepository
import com.hyperos.updater.domain.repository.DeviceRepository
import com.hyperos.updater.domain.repository.OtaRepository
import com.hyperos.updater.domain.repository.PreferencesRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindOtaRepository(impl: OtaRepositoryImpl): OtaRepository

    @Binds
    @Singleton
    abstract fun bindAppUpdateRepository(impl: AppUpdateRepositoryImpl): AppUpdateRepository

    @Binds
    @Singleton
    abstract fun bindDeviceRepository(impl: DeviceRepositoryImpl): DeviceRepository

    @Binds
    @Singleton
    abstract fun bindPreferencesRepository(impl: PreferencesRepositoryImpl): PreferencesRepository
}
