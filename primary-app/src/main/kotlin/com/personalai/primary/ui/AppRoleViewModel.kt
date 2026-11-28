package com.personalai.primary.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personalai.primary.data.prefs.AppDeviceRole
import com.personalai.primary.data.prefs.PrimarySettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppRoleViewModel @Inject constructor(
    private val settingsRepository: PrimarySettingsRepository
) : ViewModel() {

    /**
     * Emits null briefly while DataStore initializes so there is zero UI flash on app launch,
     * then emits UNSELECTED, PRIMARY, or SECONDARY.
     */
    val selectedRole: StateFlow<AppDeviceRole?> = settingsRepository.deviceRoleFlow
        .map<AppDeviceRole, AppDeviceRole?> { it }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = null
        )

    fun selectRole(role: AppDeviceRole) {
        viewModelScope.launch {
            settingsRepository.setDeviceRole(role)
        }
    }
}
