package com.personalai.primary.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.personalai.primary.asr.AsrLanguageMode
import com.personalai.primary.asr.AsrProviderType
import com.personalai.primary.asr.UoaiAsrScript
import com.personalai.primary.audio.AudioRetentionPolicy
import com.personalai.primary.memory.LlmProviderType
import com.personalai.primary.memory.uoai.UoaiConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.primaryDataStore: DataStore<Preferences> by preferencesDataStore(name = "primary_prefs")

enum class AppDeviceRole(val title: String, val subtitle: String) {
    UNSELECTED("Not Selected", "Choose Primary or Secondary mode"),
    PRIMARY("Primary (Sunbo — Your 2nd Mind)", "Offline transcription, AI memory vault, insights & Ask Sunbo"),
    SECONDARY("Secondary (Recorder Companion)", "Dedicated encrypted Silero VAD voice recorder & BLE sync");

    companion object {
        fun parseOrDefault(raw: String?): AppDeviceRole {
            if (raw.isNullOrBlank()) return UNSELECTED
            return runCatching { valueOf(raw.trim().uppercase()) }.getOrDefault(UNSELECTED)
        }
    }
}

data class PrimaryAppSettings(
    val asrProviderType: AsrProviderType = AsrProviderType.UOAI_PROVIDER,
    val asrLanguageMode: AsrLanguageMode = AsrLanguageMode.HINGLISH,
    val uoaiAsrProviderId: String = UoaiConstants.AUDIO_PROVIDER_GEMMA4,
    val uoaiAsrModel: String = "",
    val uoaiAsrScript: UoaiAsrScript = UoaiAsrScript.CANONICAL,
    val localWhisperBaseUrl: String = "http://127.0.0.1:8080",
    val llmProviderType: LlmProviderType = LlmProviderType.UOAI_OFFLINE,
    val audioRetentionPolicy: AudioRetentionPolicy = AudioRetentionPolicy.DAYS_3,
    val keepBackgroundLinkAlive: Boolean = true,
    val autoSyncOnReconnect: Boolean = true,
    val autoTranscribeSynced: Boolean = true,
    val autoExtractMemory: Boolean = true,
    val lastSyncedSequence: Long = 0L
)

@Singleton
class PrimarySettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val APP_DEVICE_ROLE = stringPreferencesKey("app_device_role")
        val ASR_PROVIDER = stringPreferencesKey("asr_provider")
        val ASR_LANGUAGE = stringPreferencesKey("asr_language")
        val UOAI_ASR_PROVIDER_ID = stringPreferencesKey("uoai_asr_provider_id")
        val UOAI_ASR_MODEL = stringPreferencesKey("uoai_asr_model")
        val UOAI_ASR_SCRIPT = stringPreferencesKey("uoai_asr_script")
        val LOCAL_WHISPER_URL = stringPreferencesKey("local_whisper_url")
        val LLM_PROVIDER = stringPreferencesKey("llm_provider")
        val AUDIO_RETENTION = stringPreferencesKey("audio_retention_policy")
        val KEEP_BACKGROUND_LINK = booleanPreferencesKey("keep_background_link_alive")
        val AUTO_SYNC = booleanPreferencesKey("auto_sync_on_reconnect")
        val AUTO_TRANSCRIBE = booleanPreferencesKey("auto_transcribe_synced")
        val AUTO_EXTRACT_MEMORY = booleanPreferencesKey("auto_extract_memory")
        val LAST_SYNCED_SEQ = longPreferencesKey("last_synced_seq")
    }

    val settingsFlow: Flow<PrimaryAppSettings> = context.primaryDataStore.data.map { prefs ->
        val asrProvider = prefs[Keys.ASR_PROVIDER]?.let {
            runCatching { AsrProviderType.valueOf(it) }.getOrNull()
        } ?: AsrProviderType.UOAI_PROVIDER
        val lang = prefs[Keys.ASR_LANGUAGE]?.let {
            runCatching { AsrLanguageMode.valueOf(it) }.getOrNull()
        } ?: AsrLanguageMode.HINGLISH
        val uoaiScript = prefs[Keys.UOAI_ASR_SCRIPT]?.let {
            runCatching { UoaiAsrScript.valueOf(it) }.getOrNull()
        } ?: UoaiAsrScript.CANONICAL
        val llmProvider = prefs[Keys.LLM_PROVIDER]?.let {
            runCatching { LlmProviderType.valueOf(it) }.getOrNull()
        } ?: LlmProviderType.UOAI_OFFLINE
        val retention = prefs[Keys.AUDIO_RETENTION]?.let {
            runCatching { AudioRetentionPolicy.valueOf(it) }.getOrNull()
        } ?: AudioRetentionPolicy.DAYS_3

        PrimaryAppSettings(
            asrProviderType = asrProvider,
            asrLanguageMode = lang,
            uoaiAsrProviderId = prefs[Keys.UOAI_ASR_PROVIDER_ID]?.trim()?.takeIf { it.isNotEmpty() }
                ?: UoaiConstants.AUDIO_PROVIDER_GEMMA4,
            uoaiAsrModel = prefs[Keys.UOAI_ASR_MODEL]?.trim().orEmpty(),
            uoaiAsrScript = uoaiScript,
            localWhisperBaseUrl = prefs[Keys.LOCAL_WHISPER_URL] ?: "http://127.0.0.1:8080",
            llmProviderType = llmProvider,
            audioRetentionPolicy = retention,
            keepBackgroundLinkAlive = prefs[Keys.KEEP_BACKGROUND_LINK] ?: true,
            autoSyncOnReconnect = prefs[Keys.AUTO_SYNC] ?: true,
            autoTranscribeSynced = prefs[Keys.AUTO_TRANSCRIBE] ?: true,
            autoExtractMemory = prefs[Keys.AUTO_EXTRACT_MEMORY] ?: true,
            lastSyncedSequence = prefs[Keys.LAST_SYNCED_SEQ] ?: 0L
        )
    }

    val deviceRoleFlow: Flow<AppDeviceRole> = context.primaryDataStore.data.map { prefs ->
        AppDeviceRole.parseOrDefault(prefs[Keys.APP_DEVICE_ROLE])
    }

    suspend fun getDeviceRole(): AppDeviceRole = deviceRoleFlow.first()

    suspend fun setDeviceRole(role: AppDeviceRole) {
        context.primaryDataStore.edit { it[Keys.APP_DEVICE_ROLE] = role.name }
    }

    suspend fun getSettings(): PrimaryAppSettings = settingsFlow.first()

    suspend fun setAsrProvider(type: AsrProviderType) {
        context.primaryDataStore.edit { it[Keys.ASR_PROVIDER] = type.name }
    }

    suspend fun setAsrLanguage(mode: AsrLanguageMode) {
        context.primaryDataStore.edit { it[Keys.ASR_LANGUAGE] = mode.name }
    }

    suspend fun setUoaiAsrProviderId(providerId: String) {
        val clean = providerId.trim().ifEmpty { UoaiConstants.AUDIO_PROVIDER_GEMMA4 }
        context.primaryDataStore.edit {
            it[Keys.UOAI_ASR_PROVIDER_ID] = clean
            // Reset custom model override when switching provider so the provider's preferred default applies.
            it[Keys.UOAI_ASR_MODEL] = ""
        }
    }

    suspend fun setUoaiAsrModel(model: String) {
        context.primaryDataStore.edit { it[Keys.UOAI_ASR_MODEL] = model.trim() }
    }

    suspend fun setUoaiAsrScript(script: UoaiAsrScript) {
        context.primaryDataStore.edit { it[Keys.UOAI_ASR_SCRIPT] = script.name }
    }

    suspend fun setLocalWhisperUrl(url: String) {
        context.primaryDataStore.edit { it[Keys.LOCAL_WHISPER_URL] = url.trim() }
    }

    suspend fun setLlmProvider(type: LlmProviderType) {
        context.primaryDataStore.edit { it[Keys.LLM_PROVIDER] = type.name }
    }

    suspend fun setAudioRetentionPolicy(policy: AudioRetentionPolicy) {
        context.primaryDataStore.edit { it[Keys.AUDIO_RETENTION] = policy.name }
    }

    suspend fun setKeepBackgroundLinkAlive(enabled: Boolean) {
        context.primaryDataStore.edit { it[Keys.KEEP_BACKGROUND_LINK] = enabled }
    }

    suspend fun setAutoSyncOnReconnect(enabled: Boolean) {
        context.primaryDataStore.edit { it[Keys.AUTO_SYNC] = enabled }
    }

    suspend fun setAutoTranscribeSynced(enabled: Boolean) {
        context.primaryDataStore.edit { it[Keys.AUTO_TRANSCRIBE] = enabled }
    }

    suspend fun setAutoExtractMemory(enabled: Boolean) {
        context.primaryDataStore.edit { it[Keys.AUTO_EXTRACT_MEMORY] = enabled }
    }

    suspend fun updateLastSyncedSequence(seq: Long) {
        context.primaryDataStore.edit { prefs ->
            val prev = prefs[Keys.LAST_SYNCED_SEQ] ?: 0L
            if (seq > prev) {
                prefs[Keys.LAST_SYNCED_SEQ] = seq
            }
        }
    }

    suspend fun resetSyncCursor() {
        context.primaryDataStore.edit { it[Keys.LAST_SYNCED_SEQ] = 0L }
    }
}
