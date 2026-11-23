package com.personalai.primary

import android.app.Application
import com.personalai.primary.audio.AudioRetentionManager
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

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        audioRetentionManager.schedulePeriodicCleanup()
        appScope.launch {
            runCatching { audioRetentionManager.enforceRetentionPolicy() }
        }
    }
}
