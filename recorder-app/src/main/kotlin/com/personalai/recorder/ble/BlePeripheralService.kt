package com.personalai.recorder.ble

import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.personalai.recorder.data.db.DiagnosticSeverity
import com.personalai.recorder.data.repo.RecorderRepository
import com.personalai.recorder.domain.state.RecorderStateMachine
import com.personalai.recorder.service.NotificationHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Independent BLE GATT server host declared with `foregroundServiceType="connectedDevice"`.
 * Decoupled from `RecorderService` (`microphone` FGS) so BLE disconnection never affects
 * recording and BLE control remains available without violating microphone FGS restrictions.
 */
@AndroidEntryPoint
class BlePeripheralService : LifecycleService() {

    @Inject lateinit var blePeripheralManager: BlePeripheralManager
    @Inject lateinit var pairingStore: PairingCredentialsStore
    @Inject lateinit var stateMachine: RecorderStateMachine
    @Inject lateinit var repository: RecorderRepository
    @Inject lateinit var notificationHelper: NotificationHelper

    private var statusObserverJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        isHostRunning = true
        notificationHelper.ensureChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP_BLE_HOST) {
            blePeripheralManager.stopHosting()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = notificationHelper.buildBleHostNotification(
            bleStateLabel = stateMachine.bleState.value.name,
            pairedDeviceName = pairingStore.uiState.value.pairedClientName
        )

        try {
            val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            }
            ServiceCompat.startForeground(
                this,
                NotificationHelper.NOTIFICATION_ID_BLE_HOST,
                notification,
                fgsType
            )
        } catch (e: Throwable) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
                lifecycleScope.launch {
                    repository.logEvent("BLE_FGS", DiagnosticSeverity.ERROR, "Blocked starting connectedDevice FGS: ${e.message}")
                }
            }
            stopSelf()
            return START_NOT_STICKY
        }

        val startRes = blePeripheralManager.startHosting()
        if (startRes.isFailure) {
            lifecycleScope.launch {
                repository.logEvent(
                    "BLE_HOST",
                    DiagnosticSeverity.ERROR,
                    "BLE Host start failed: ${startRes.exceptionOrNull()?.message}"
                )
            }
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        observeNotificationUpdates()
        return START_NOT_STICKY
    }

    private fun observeNotificationUpdates() {
        statusObserverJob?.cancel()
        statusObserverJob = lifecycleScope.launch {
            combine(stateMachine.bleState, pairingStore.uiState) { ble, pairing ->
                ble to pairing.pairedClientName
            }.collect { (ble, peerName) ->
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(
                    NotificationHelper.NOTIFICATION_ID_BLE_HOST,
                    notificationHelper.buildBleHostNotification(ble.name, peerName)
                )
            }
        }
    }

    override fun onDestroy() {
        isHostRunning = false
        statusObserverJob?.cancel()
        blePeripheralManager.stopHosting()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START_BLE_HOST = "com.personalai.recorder.action.START_BLE_HOST"
        const val ACTION_STOP_BLE_HOST = "com.personalai.recorder.action.STOP_BLE_HOST"

        @Volatile
        var isHostRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, BlePeripheralService::class.java).apply {
                action = ACTION_START_BLE_HOST
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, BlePeripheralService::class.java).apply {
                action = ACTION_STOP_BLE_HOST
            }
            context.startService(intent)
        }
    }
}
