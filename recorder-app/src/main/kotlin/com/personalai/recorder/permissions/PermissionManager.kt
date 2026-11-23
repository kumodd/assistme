package com.personalai.recorder.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class PermissionItemStatus(
    val permission: String,
    val title: String,
    val rationale: String,
    val isRequiredForRecording: Boolean,
    val isGranted: Boolean
)

data class PermissionSnapshot(
    val items: List<PermissionItemStatus>,
    val isBatteryOptimizationIgnored: Boolean,
    val canRecordAudio: Boolean,
    val allRecommendedGranted: Boolean,
    val oemGuidanceTitle: String,
    val oemGuidanceBody: String
)

@Singleton
class PermissionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun requiredRuntimePermissions(): List<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun getSnapshot(): PermissionSnapshot {
        val items = buildList {
            add(
                PermissionItemStatus(
                    permission = Manifest.permission.RECORD_AUDIO,
                    title = "Microphone",
                    rationale = "Required to capture local speech segments using Silero VAD.",
                    isRequiredForRecording = true,
                    isGranted = isGranted(Manifest.permission.RECORD_AUDIO)
                )
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(
                    PermissionItemStatus(
                        permission = Manifest.permission.POST_NOTIFICATIONS,
                        title = "Notifications",
                        rationale = "Required on Android 13+ to display the persistent recording indicator and emergency stop button.",
                        isRequiredForRecording = false,
                        isGranted = isGranted(Manifest.permission.POST_NOTIFICATIONS)
                    )
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(
                    PermissionItemStatus(
                        permission = Manifest.permission.BLUETOOTH_ADVERTISE,
                        title = "Bluetooth Advertise",
                        rationale = "Allows Device A to discover and connect to this recorder over BLE.",
                        isRequiredForRecording = false,
                        isGranted = isGranted(Manifest.permission.BLUETOOTH_ADVERTISE)
                    )
                )
                add(
                    PermissionItemStatus(
                        permission = Manifest.permission.BLUETOOTH_CONNECT,
                        title = "Bluetooth Connect",
                        rationale = "Allows Device A to pair, send authenticated commands, and sync encrypted recordings.",
                        isRequiredForRecording = false,
                        isGranted = isGranted(Manifest.permission.BLUETOOTH_CONNECT)
                    )
                )
                add(
                    PermissionItemStatus(
                        permission = Manifest.permission.BLUETOOTH_SCAN,
                        title = "Bluetooth Scan",
                        rationale = "Supports BLE peer verification during pairing.",
                        isRequiredForRecording = false,
                        isGranted = isGranted(Manifest.permission.BLUETOOTH_SCAN)
                    )
                )
            } else {
                add(
                    PermissionItemStatus(
                        permission = Manifest.permission.ACCESS_FINE_LOCATION,
                        title = "Location (Android 10-11 BLE)",
                        rationale = "Required by Android 10-11 Bluetooth APIs for BLE discovery.",
                        isRequiredForRecording = false,
                        isGranted = isGranted(Manifest.permission.ACCESS_FINE_LOCATION)
                    )
                )
            }
        }

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val ignoringDoze = pm.isIgnoringBatteryOptimizations(context.packageName)
        val (oemTitle, oemBody) = buildOemGuidance()

        return PermissionSnapshot(
            items = items,
            isBatteryOptimizationIgnored = ignoringDoze,
            canRecordAudio = items.first { it.permission == Manifest.permission.RECORD_AUDIO }.isGranted,
            allRecommendedGranted = items.all { it.isGranted },
            oemGuidanceTitle = oemTitle,
            oemGuidanceBody = oemBody
        )
    }

    fun createBatteryOptimizationIntent(): Intent {
        return Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        )
    }

    fun createAppDetailsIntent(): Intent {
        return Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )
    }

    private fun isGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun buildOemGuidance(): Pair<String, String> {
        val mfg = Build.MANUFACTURER.lowercase()
        return when {
            mfg.contains("oneplus") || mfg.contains("oppo") || mfg.contains("realme") -> {
                "OnePlus / Oppo (OxygenOS / ColorOS)" to
                    "Android battery exemption alone does not stop ColorOS App Freeze. Open App Info -> Battery usage -> Allow foreground activity & Allow background activity, and disable Sleep standby optimization if available."
            }
            mfg.contains("xiaomi") || mfg.contains("redmi") || mfg.contains("poco") -> {
                "Xiaomi / Redmi (MIUI / HyperOS)" to
                    "Enable Autostart and set Battery saver to 'No restrictions' in App Info; otherwise MIUI/HyperOS may terminate services shortly after screen off."
            }
            mfg.contains("vivo") || mfg.contains("iqoo") -> {
                "Vivo / iQOO (FuntouchOS / OriginOS)" to
                    "Allow High background power consumption in Battery / iManager settings to reduce background kills."
            }
            mfg.contains("samsung") -> {
                "Samsung (One UI)" to
                    "Add this app to 'Never auto sleeping apps' under Device Care -> Battery -> Background usage limits so One UI does not suspend an idle secondary phone."
            }
            else -> {
                "Battery Optimization (${Build.MANUFACTURER} ${Build.MODEL})" to
                    "Setting battery mode to Unrestricted improves Doze reliability, though Android does not guarantee uninterrupted background execution under severe memory or thermal pressure."
            }
        }
    }
}
