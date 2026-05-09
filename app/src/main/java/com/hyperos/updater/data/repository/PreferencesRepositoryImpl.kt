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

    override val shizukuEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_SHIZUKU] ?: true
    }

    override val checkIntervalHours: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_INTERVAL] ?: 24
    }

    override suspend fun setShizukuEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_SHIZUKU] = enabled }
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

    companion object {
        private val KEY_SHIZUKU = booleanPreferencesKey("shizuku_enabled")
        private val KEY_INTERVAL = intPreferencesKey("check_interval_hours")
        private val KEY_BLACKLIST = stringSetPreferencesKey("blacklisted_packages")
    }
}
