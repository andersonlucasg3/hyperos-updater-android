package com.hyperos.updater.di

import com.hyperos.updater.domain.installer.ApkInstaller
import com.hyperos.updater.domain.installer.PackageManagerInstaller
import com.hyperos.updater.domain.installer.RootApkInstaller
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
    @Named("root")
    fun provideRootInstaller(installer: RootApkInstaller): ApkInstaller = installer

    @Provides
    @Singleton
    @Named("fallback")
    fun provideFallbackInstaller(installer: PackageManagerInstaller): ApkInstaller = installer
}
