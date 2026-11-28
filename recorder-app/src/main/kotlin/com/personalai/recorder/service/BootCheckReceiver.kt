package com.personalai.recorder.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.personalai.recorder.ble.PairingCredentialsStore
import com.personalai.recorder.data.db.DiagnosticSeverity
import com.personalai.recorder.data.prefs.RecorderPreferencesRepository
import com.personalai.recorder.data.repo.RecorderRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Inspects persisted session state after reboot or package replacement without ever launching
 * a background microphone foreground service (explicitly prohibited by Android 14/15/16 policies
 * and product privacy guarantees).
 */
@AndroidEntryPoint
class BootCheckReceiver : BroadcastReceiver() {

    @Inject lateinit var repository: RecorderRepository
    @Inject lateinit var prefs: RecorderPreferencesRepository
    @Inject lateinit var pairingStore: PairingCredentialsStore
    @Inject lateinit var notificationHelper: NotificationHelper

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val interrupted = repository.reconcileUncleanShutdownIfAny(
                    isServiceCurrentlyRunning = RecorderService.isServiceRunning
                )
                repository.resetInterruptedSyncItems()
                repository.pruneOldDiagnosticEvents()
                val settings = runCatching { prefs.settingsFlow.first() }.getOrNull()
                val isPairedSecondary = settings?.onboardingCompleted == true && pairingStore.getMasterKeyBytes() != null
                repository.logEvent(
                    category = "SYSTEM_BOOT",
                    severity = DiagnosticSeverity.INFO,
                    message = "Handled $action (interruptedSessionFound=$interrupted, pairedSecondary=$isPairedSecondary); microphone remains off until user arms."
                )
                if (interrupted) {
                    notificationHelper.postAlertNotification(
                        title = "Recorder interrupted by reboot/update",
                        message = "Microphone is currently OFF. Open Sunbo (Secondary mode) to re-arm capture & Bluetooth sync."
                    )
                } else if (isPairedSecondary && settings?.bleHostDesired == true) {
                    notificationHelper.postAlertNotification(
                        title = "Sunbo Secondary restarted",
                        message = "Tap to resume Bluetooth sync host so your Primary phone can reconnect automatically."
                    )
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
