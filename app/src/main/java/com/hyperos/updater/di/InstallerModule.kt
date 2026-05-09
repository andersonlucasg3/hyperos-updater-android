package com.hyperos.updater.di

import com.hyperos.updater.domain.installer.ApkInstaller
import com.hyperos.updater.domain.installer.PackageManagerInstaller
import com.hyperos.updater.domain.installer.ShizukuApkInstaller
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object InstallerModule {

    @Provides
    @Singleton
    @Named("shizuku")
    fun provideShizukuInstaller(installer: ShizukuApkInstaller): ApkInstaller = installer

    @Provides
    @Singleton
    @Named("fallback")
    fun provideFallbackInstaller(installer: PackageManagerInstaller): ApkInstaller = installer
}
