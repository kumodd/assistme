package com.personalai.primary.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class PrimaryPermissionStatus(
    val permission: String,
    val title: String,
    val rationale: String,
    val granted: Boolean
)

data class PrimaryPermissionsSnapshot(
    val items: List<PrimaryPermissionStatus>,
    val canUseBle: Boolean
)

@Singleton
class PrimaryPermissionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun requiredRuntimePermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun hasBlePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            isGranted(Manifest.permission.BLUETOOTH_SCAN) &&
                isGranted(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            isGranted(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    fun getSnapshot(): PrimaryPermissionsSnapshot {
        val items = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(
                    PrimaryPermissionStatus(
                        permission = Manifest.permission.BLUETOOTH_SCAN,
                        title = "Bluetooth Scan",
                        rationale = "Required to discover your dedicated Device B recorder over BLE.",
                        granted = isGranted(Manifest.permission.BLUETOOTH_SCAN)
                    )
                )
                add(
                    PrimaryPermissionStatus(
                        permission = Manifest.permission.BLUETOOTH_CONNECT,
                        title = "Bluetooth Connect",
                        rationale = "Required to connect, pair, control recording, and sync encrypted files.",
                        granted = isGranted(Manifest.permission.BLUETOOTH_CONNECT)
                    )
                )
            } else {
                add(
                    PrimaryPermissionStatus(
                        permission = Manifest.permission.ACCESS_FINE_LOCATION,
                        title = "Location (Android 10-11 BLE)",
                        rationale = "Required by Android 10-11 Bluetooth scanning APIs.",
                        granted = isGranted(Manifest.permission.ACCESS_FINE_LOCATION)
                    )
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(
                    PrimaryPermissionStatus(
                        permission = Manifest.permission.POST_NOTIFICATIONS,
                        title = "Notifications",
                        rationale = "Used for task reminders, sync status, and interruption alerts.",
                        granted = isGranted(Manifest.permission.POST_NOTIFICATIONS)
                    )
                )
            }
        }
        return PrimaryPermissionsSnapshot(
            items = items,
            canUseBle = hasBlePermissions()
        )
    }

    fun createAppDetailsIntent(): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}")
    )

    private fun isGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}
