package com.personalai.recorder.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.personalai.protocol.AudioSourcePreset
import com.personalai.protocol.RecorderConfigPayload
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.recorderDataStore: DataStore<Preferences> by preferencesDataStore(name = "recorder_prefs")

data class ActiveSessionMarker(
    val sessionId: String,
    val lastHeartbeatMs: Long,
    val cleanShutdown: Boolean
)

data class RecorderAppSettings(
    val onboardingCompleted: Boolean = false,
    val privacyKillSwitch: Boolean = false,
    val autoDeleteSyncedSegments: Boolean = false,
    val bleHostDesired: Boolean = true,
    val config: RecorderConfigPayload = RecorderConfigPayload()
)

@Singleton
class RecorderPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val PRIVACY_KILL_SWITCH = booleanPreferencesKey("privacy_kill_switch")
        val AUTO_DELETE_SYNCED = booleanPreferencesKey("auto_delete_synced")
        val BLE_HOST_DESIRED = booleanPreferencesKey("ble_host_desired")

        val VAD_THRESHOLD = floatPreferencesKey("vad_threshold")
        val PRE_ROLL_MS = intPreferencesKey("pre_roll_ms")
        val SILENCE_TIMEOUT_MS = intPreferencesKey("silence_timeout_ms")
        val MAX_SEGMENT_MS = intPreferencesKey("max_segment_ms")
        val MIN_SPEECH_MS = intPreferencesKey("min_speech_ms")
        val AUDIO_SOURCE = stringPreferencesKey("audio_source")
        val MIN_FREE_STORAGE_MB = longPreferencesKey("min_free_storage_mb")
        val MIN_BATTERY_PCT = intPreferencesKey("min_battery_pct")
        val LOCAL_ONLY_PAUSE_SYNC = booleanPreferencesKey("local_only_pause_sync")

        val ACTIVE_SESSION_ID = stringPreferencesKey("active_session_id")
        val LAST_HEARTBEAT_MS = longPreferencesKey("last_heartbeat_ms")
        val CLEAN_SHUTDOWN = booleanPreferencesKey("clean_shutdown")
        val NEXT_SEQUENCE_NUMBER = longPreferencesKey("next_sequence_number")
    }

    val settingsFlow: Flow<RecorderAppSettings> = context.recorderDataStore.data.map { prefs ->
        val audioSource = prefs[Keys.AUDIO_SOURCE]?.let { raw ->
            runCatching { AudioSourcePreset.valueOf(raw) }.getOrNull()
        } ?: AudioSourcePreset.VOICE_RECOGNITION

        RecorderAppSettings(
            onboardingCompleted = prefs[Keys.ONBOARDING_COMPLETED] ?: false,
            privacyKillSwitch = prefs[Keys.PRIVACY_KILL_SWITCH] ?: false,
            autoDeleteSyncedSegments = prefs[Keys.AUTO_DELETE_SYNCED] ?: false,
            bleHostDesired = prefs[Keys.BLE_HOST_DESIRED] ?: true,
            config = RecorderConfigPayload(
                vadThreshold = prefs[Keys.VAD_THRESHOLD] ?: 0.50f,
                preRollMs = prefs[Keys.PRE_ROLL_MS] ?: 640,
                silenceTimeoutMs = prefs[Keys.SILENCE_TIMEOUT_MS] ?: 1_600,
                maxSegmentDurationMs = prefs[Keys.MAX_SEGMENT_MS] ?: 45_000,
                minSpeechDurationMs = prefs[Keys.MIN_SPEECH_MS] ?: 800,
                audioSource = audioSource,
                minFreeStorageMb = prefs[Keys.MIN_FREE_STORAGE_MB] ?: 250L,
                minBatteryPctToRecord = prefs[Keys.MIN_BATTERY_PCT] ?: 8,
                localOnlyPauseSync = prefs[Keys.LOCAL_ONLY_PAUSE_SYNC] ?: false
            )
        )
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.recorderDataStore.edit { it[Keys.ONBOARDING_COMPLETED] = completed }
    }

    suspend fun setPrivacyKillSwitch(disabled: Boolean) {
        context.recorderDataStore.edit { it[Keys.PRIVACY_KILL_SWITCH] = disabled }
    }

    suspend fun setAutoDeleteSynced(enabled: Boolean) {
        context.recorderDataStore.edit { it[Keys.AUTO_DELETE_SYNCED] = enabled }
    }

    suspend fun setBleHostDesired(desired: Boolean) {
        context.recorderDataStore.edit { it[Keys.BLE_HOST_DESIRED] = desired }
    }

    suspend fun updateConfig(config: RecorderConfigPayload) {
        context.recorderDataStore.edit { prefs ->
            prefs[Keys.VAD_THRESHOLD] = config.vadThreshold.coerceIn(0.15f, 0.90f)
            prefs[Keys.PRE_ROLL_MS] = config.preRollMs.coerceIn(100, 2_000)
            prefs[Keys.SILENCE_TIMEOUT_MS] = config.silenceTimeoutMs.coerceIn(300, 5_000)
            prefs[Keys.MAX_SEGMENT_MS] = config.maxSegmentDurationMs.coerceIn(5_000, 120_000)
            prefs[Keys.MIN_SPEECH_MS] = config.minSpeechDurationMs.coerceIn(100, 3_000)
            prefs[Keys.AUDIO_SOURCE] = config.audioSource.name
            prefs[Keys.MIN_FREE_STORAGE_MB] = config.minFreeStorageMb.coerceAtLeast(50L)
            prefs[Keys.MIN_BATTERY_PCT] = config.minBatteryPctToRecord.coerceIn(2, 30)
            prefs[Keys.LOCAL_ONLY_PAUSE_SYNC] = config.localOnlyPauseSync
        }
    }

    suspend fun allocateNextSequenceNumber(minFloor: Long = 0L): Long {
        var allocated = 1L
        context.recorderDataStore.edit { prefs ->
            val current = maxOf(prefs[Keys.NEXT_SEQUENCE_NUMBER] ?: 0L, minFloor)
            allocated = current + 1L
            prefs[Keys.NEXT_SEQUENCE_NUMBER] = allocated
        }
        return allocated
    }

    suspend fun markSessionRunning(sessionId: String, nowMs: Long) {
        context.recorderDataStore.edit { prefs ->
            prefs[Keys.ACTIVE_SESSION_ID] = sessionId
            prefs[Keys.LAST_HEARTBEAT_MS] = nowMs
            prefs[Keys.CLEAN_SHUTDOWN] = false
        }
    }

    suspend fun updateSessionHeartbeat(sessionId: String, nowMs: Long) {
        context.recorderDataStore.edit { prefs ->
            if (prefs[Keys.ACTIVE_SESSION_ID] == sessionId) {
                prefs[Keys.LAST_HEARTBEAT_MS] = nowMs
            }
        }
    }

    suspend fun markSessionCleanShutdown(sessionId: String) {
        context.recorderDataStore.edit { prefs ->
            if (prefs[Keys.ACTIVE_SESSION_ID] == sessionId) {
                prefs[Keys.CLEAN_SHUTDOWN] = true
            }
        }
    }

    suspend fun readActiveSessionMarker(): ActiveSessionMarker? {
        val prefs = context.recorderDataStore.data.first()
        val id = prefs[Keys.ACTIVE_SESSION_ID] ?: return null
        return ActiveSessionMarker(
            sessionId = id,
            lastHeartbeatMs = prefs[Keys.LAST_HEARTBEAT_MS] ?: 0L,
            cleanShutdown = prefs[Keys.CLEAN_SHUTDOWN] ?: true
        )
    }

    suspend fun clearActiveSessionMarker() {
        context.recorderDataStore.edit { prefs ->
            prefs.remove(Keys.ACTIVE_SESSION_ID)
            prefs.remove(Keys.LAST_HEARTBEAT_MS)
            prefs[Keys.CLEAN_SHUTDOWN] = true
        }
    }
}
