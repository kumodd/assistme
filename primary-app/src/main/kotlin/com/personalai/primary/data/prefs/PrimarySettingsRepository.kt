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
import com.personalai.primary.audio.AudioRetentionPolicy
import com.personalai.primary.memory.LlmProviderType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.primaryDataStore: DataStore<Preferences> by preferencesDataStore(name = "primary_prefs")

data class PrimaryAppSettings(
    val asrProviderType: AsrProviderType = AsrProviderType.ANDROID_ON_DEVICE,
    val asrLanguageMode: AsrLanguageMode = AsrLanguageMode.AUTO,
    val localWhisperBaseUrl: String = "http://127.0.0.1:8080",
    val llmProviderType: LlmProviderType = LlmProviderType.UOAI_OFFLINE,
    val audioRetentionPolicy: AudioRetentionPolicy = AudioRetentionPolicy.DAYS_3,
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
        val ASR_PROVIDER = stringPreferencesKey("asr_provider")
        val ASR_LANGUAGE = stringPreferencesKey("asr_language")
        val LOCAL_WHISPER_URL = stringPreferencesKey("local_whisper_url")
        val LLM_PROVIDER = stringPreferencesKey("llm_provider")
        val AUDIO_RETENTION = stringPreferencesKey("audio_retention_policy")
        val AUTO_SYNC = booleanPreferencesKey("auto_sync_on_reconnect")
        val AUTO_TRANSCRIBE = booleanPreferencesKey("auto_transcribe_synced")
        val AUTO_EXTRACT_MEMORY = booleanPreferencesKey("auto_extract_memory")
        val LAST_SYNCED_SEQ = longPreferencesKey("last_synced_seq")
    }

    val settingsFlow: Flow<PrimaryAppSettings> = context.primaryDataStore.data.map { prefs ->
        val asrProvider = prefs[Keys.ASR_PROVIDER]?.let {
            runCatching { AsrProviderType.valueOf(it) }.getOrNull()
        } ?: AsrProviderType.ANDROID_ON_DEVICE
        val lang = prefs[Keys.ASR_LANGUAGE]?.let {
            runCatching { AsrLanguageMode.valueOf(it) }.getOrNull()
        } ?: AsrLanguageMode.AUTO
        val llmProvider = prefs[Keys.LLM_PROVIDER]?.let {
            runCatching { LlmProviderType.valueOf(it) }.getOrNull()
        } ?: LlmProviderType.UOAI_OFFLINE
        val retention = prefs[Keys.AUDIO_RETENTION]?.let {
            runCatching { AudioRetentionPolicy.valueOf(it) }.getOrNull()
        } ?: AudioRetentionPolicy.DAYS_3

        PrimaryAppSettings(
            asrProviderType = asrProvider,
            asrLanguageMode = lang,
            localWhisperBaseUrl = prefs[Keys.LOCAL_WHISPER_URL] ?: "http://127.0.0.1:8080",
            llmProviderType = llmProvider,
            audioRetentionPolicy = retention,
            autoSyncOnReconnect = prefs[Keys.AUTO_SYNC] ?: true,
            autoTranscribeSynced = prefs[Keys.AUTO_TRANSCRIBE] ?: true,
            autoExtractMemory = prefs[Keys.AUTO_EXTRACT_MEMORY] ?: true,
            lastSyncedSequence = prefs[Keys.LAST_SYNCED_SEQ] ?: 0L
        )
    }

    suspend fun getSettings(): PrimaryAppSettings = settingsFlow.first()

    suspend fun setAsrProvider(type: AsrProviderType) {
        context.primaryDataStore.edit { it[Keys.ASR_PROVIDER] = type.name }
    }

    suspend fun setAsrLanguage(mode: AsrLanguageMode) {
        context.primaryDataStore.edit { it[Keys.ASR_LANGUAGE] = mode.name }
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
