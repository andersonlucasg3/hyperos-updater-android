package com.hyperos.updater.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hyperos.updater.domain.repository.PreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("settings")

@Singleton
class PreferencesRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : PreferencesRepository {

    override val checkIntervalHours: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_INTERVAL] ?: 24
    }

    override suspend fun setCheckIntervalHours(hours: Int) {
        context.dataStore.edit { prefs -> prefs[KEY_INTERVAL] = hours }
    }

    override suspend fun addToBlacklist(packageName: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_BLACKLIST] ?: emptySet()
            prefs[KEY_BLACKLIST] = current + packageName
        }
    }

    override suspend fun removeFromBlacklist(packageName: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_BLACKLIST] ?: emptySet()
            prefs[KEY_BLACKLIST] = current - packageName
        }
    }

    override fun isBlacklisted(packageName: String): Flow<Boolean> =
        blacklistedPackages.map { packageName in it }

    override val blacklistedPackages: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[KEY_BLACKLIST] ?: emptySet()
    }

    override suspend fun skipVersion(packageName: String, versionName: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_SKIPPED_VERSIONS] ?: emptySet()
            prefs[KEY_SKIPPED_VERSIONS] = current + "$packageName|$versionName"
        }
    }

    override suspend fun removeSkippedVersion(packageName: String, versionName: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_SKIPPED_VERSIONS] ?: emptySet()
            prefs[KEY_SKIPPED_VERSIONS] = current - "$packageName|$versionName"
        }
    }

    override fun isSkippedVersion(packageName: String, versionName: String): Flow<Boolean> =
        skippedVersions.map { "$packageName|$versionName" in it }

    override val skippedVersions: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[KEY_SKIPPED_VERSIONS] ?: emptySet()
    }

    override val autoUpdateEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_AUTO_UPDATE] ?: false
    }

    override suspend fun setAutoUpdateEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_AUTO_UPDATE] = enabled }
    }

    override val updatableFilterEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_UPDATABLE_FILTER] ?: true
    }

    override suspend fun setUpdatableFilterEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_UPDATABLE_FILTER] = enabled }
    }

    override val showSystemApps: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_SHOW_SYSTEM_APPS] ?: true
    }

    override suspend fun setShowSystemApps(show: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_SHOW_SYSTEM_APPS] = show }
    }

    companion object {
        private val KEY_INTERVAL = intPreferencesKey("check_interval_hours")
        private val KEY_BLACKLIST = stringSetPreferencesKey("blacklisted_packages")
        private val KEY_AUTO_UPDATE = booleanPreferencesKey("auto_update_enabled")
        private val KEY_SKIPPED_VERSIONS = stringSetPreferencesKey("skipped_versions")
        private val KEY_UPDATABLE_FILTER = booleanPreferencesKey("updatable_filter_enabled")
        private val KEY_SHOW_SYSTEM_APPS = booleanPreferencesKey("show_system_apps")
    }
}
