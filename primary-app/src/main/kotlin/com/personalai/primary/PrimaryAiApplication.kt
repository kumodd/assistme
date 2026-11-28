package com.personalai.primary

import android.app.Application
import com.personalai.primary.audio.AudioRetentionManager
import com.personalai.primary.data.prefs.AppDeviceRole
import com.personalai.primary.data.prefs.PrimarySettingsRepository
import com.personalai.primary.sync.PrimarySyncService
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class PrimaryAiApplication : Application() {

    @Inject
    lateinit var audioRetentionManager: AudioRetentionManager

    @Inject
    lateinit var settingsRepo: PrimarySettingsRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        audioRetentionManager.schedulePeriodicCleanup()
        appScope.launch {
            runCatching {
                if (settingsRepo.getDeviceRole() == AppDeviceRole.PRIMARY) {
                    audioRetentionManager.enforceRetentionPolicy()
                    PrimarySyncService.enqueueOfflineProcessingFallback(this@PrimaryAiApplication)
                }
            }
        }
    }
}
