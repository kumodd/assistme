package com.personalai.recorder.health

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import com.personalai.recorder.security.EncryptedSegmentStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class DeviceHealthSnapshot(
    val batteryPct: Int,
    val isCharging: Boolean,
    val isPowerSaveMode: Boolean,
    val thermalStatus: Int,
    val thermalLabel: String,
    val totalStorageBytes: Long,
    val availableStorageBytes: Long,
    val heapUsedMb: Long,
    val heapMaxMb: Long
)

@Singleton
class DeviceHealthMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val segmentStorage: EncryptedSegmentStorage
) {
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    fun captureHealth(): DeviceHealthSnapshot {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val batteryPct = if (level >= 0 && scale > 0) (level * 100) / scale else -1

        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val thermal = powerManager.currentThermalStatus
        val storage = segmentStorage.getStorageStats()
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)
        val maxMb = runtime.maxMemory() / (1024L * 1024L)

        return DeviceHealthSnapshot(
            batteryPct = batteryPct,
            isCharging = isCharging,
            isPowerSaveMode = powerManager.isPowerSaveMode,
            thermalStatus = thermal,
            thermalLabel = mapThermalLabel(thermal),
            totalStorageBytes = storage.totalBytes,
            availableStorageBytes = storage.availableBytes,
            heapUsedMb = usedMb,
            heapMaxMb = maxMb
        )
    }

    private fun mapThermalLabel(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "Nominal"
        PowerManager.THERMAL_STATUS_LIGHT -> "Light"
        PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown"
        else -> "Unknown ($status)"
    }
}
