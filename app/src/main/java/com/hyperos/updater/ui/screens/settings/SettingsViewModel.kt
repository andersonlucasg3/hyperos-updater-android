package com.hyperos.updater.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hyperos.updater.domain.repository.PreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencesRepository: PreferencesRepository
) : ViewModel() {

    val shizukuEnabled: StateFlow<Boolean> = preferencesRepository.shizukuEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val checkInterval: StateFlow<Int> = preferencesRepository.checkIntervalHours
        .stateIn(viewModelScope, SharingStarted.Eagerly, 24)

    fun setShizukuEnabled(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setShizukuEnabled(enabled) }
    }

    fun setCheckInterval(hours: Int) {
        viewModelScope.launch { preferencesRepository.setCheckIntervalHours(hours) }
    }
}
