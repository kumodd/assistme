package com.personalai.primary.ui

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personalai.primary.asr.AndroidOnDeviceAsrProvider
import com.personalai.primary.asr.AsrLanguageMode
import com.personalai.primary.asr.AsrProgressSnapshot
import com.personalai.primary.asr.AsrProviderType
import com.personalai.primary.asr.OfflineSpeechPackStatus
import com.personalai.primary.asr.TranscriptionCoordinator
import com.personalai.primary.asr.UoaiAsrScript
import com.personalai.primary.audio.AudioExportManager
import com.personalai.primary.audio.AudioPlaybackHelper
import com.personalai.primary.audio.AudioRetentionManager
import com.personalai.primary.audio.AudioRetentionPolicy
import com.personalai.primary.audio.ExportedAudioResult
import com.personalai.primary.ble.BleClientController
import com.personalai.primary.ble.BleScannerManager
import com.personalai.primary.ble.CommandFeedback
import com.personalai.primary.ble.DiscoveredRecorderDevice
import com.personalai.primary.ble.PendingSasConfirmation
import com.personalai.primary.data.db.AsrProcessingStatus
import com.personalai.primary.data.db.AskAiAuditEntity
import com.personalai.primary.data.db.CommandAuditDao
import com.personalai.primary.data.db.CommandAuditEntity
import com.personalai.primary.data.db.EntityNodeEntity
import com.personalai.primary.data.db.MemoryCategory
import com.personalai.primary.data.db.MemoryDao
import com.personalai.primary.data.db.MemoryItemEntity
import com.personalai.primary.data.db.PairedRecorderDao
import com.personalai.primary.data.db.PairedRecorderEntity
import com.personalai.primary.data.db.SegmentWithTranscript
import com.personalai.primary.data.db.SummaryEntity
import com.personalai.primary.data.db.SummaryType
import com.personalai.primary.data.db.SyncedContentDao
import com.personalai.primary.data.db.SyncedSegmentEntity
import com.personalai.primary.data.db.TaskReminderEntity
import com.personalai.primary.data.db.TaskReminderStatus
import com.personalai.primary.data.db.TranscriptDao
import com.personalai.primary.data.prefs.PrimaryAppSettings
import com.personalai.primary.data.prefs.PrimarySettingsRepository
import com.personalai.primary.memory.AskAiAnswer
import com.personalai.primary.memory.LlmProviderType
import com.personalai.primary.memory.MemoryCoordinator
import com.personalai.primary.memory.MemoryEngineStatusSnapshot
import com.personalai.primary.memory.SearchFilter
import com.personalai.primary.memory.SecondMindInsightsEngine
import com.personalai.primary.memory.SecondMindInsightsSnapshot
import com.personalai.primary.memory.uoai.UoaiAudioModelInfo
import com.personalai.primary.memory.uoai.UoaiAudioProviderInfo
import com.personalai.primary.memory.uoai.UoaiAudioStatusResponse
import com.personalai.primary.memory.uoai.UoaiClient
import com.personalai.primary.memory.uoai.UoaiConnectionState
import com.personalai.primary.memory.uoai.UoaiRegistrationResult
import com.personalai.primary.permissions.PrimaryPermissionManager
import com.personalai.primary.permissions.PrimaryPermissionsSnapshot
import com.personalai.primary.security.PrimaryEncryptedAudioStorage
import com.personalai.primary.sync.PrimarySyncService
import com.personalai.primary.sync.SyncManager
import com.personalai.primary.sync.SyncProgressSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import com.personalai.protocol.BatteryInfoPayload
import com.personalai.protocol.BleConnectionState
import com.personalai.protocol.DeviceInfoPayload
import com.personalai.protocol.RecorderConfigPayload
import com.personalai.protocol.RecorderStatusPayload
import com.personalai.protocol.StopMode
import com.personalai.protocol.StorageInfoPayload
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class MemoryVaultFilter(val label: String) {
    ACTIVE("Active"),
    PINNED("Pinned"),
    ARCHIVED("Archived")
}

data class PrimaryUiState(
    val permissions: PrimaryPermissionsSnapshot,
    val pairedDevice: PairedRecorderEntity? = null,
    val bleState: BleConnectionState = BleConnectionState.DISCONNECTED,
    val remoteStatus: RecorderStatusPayload? = null,
    val remoteBattery: BatteryInfoPayload? = null,
    val remoteStorage: StorageInfoPayload? = null,
    val remoteDeviceInfo: DeviceInfoPayload? = null,
    val remoteConfig: RecorderConfigPayload = RecorderConfigPayload(),
    val isScanning: Boolean = false,
    val discoveredDevices: List<DiscoveredRecorderDevice> = emptyList(),
    val pendingSas: PendingSasConfirmation? = null,
    val lastError: String? = null,
    val lastFeedback: CommandFeedback? = null,
    val auditLog: List<CommandAuditEntity> = emptyList(),
    val appSettings: PrimaryAppSettings = PrimaryAppSettings(),
    val syncProgress: SyncProgressSnapshot = SyncProgressSnapshot(),
    val asrProgress: AsrProgressSnapshot = AsrProgressSnapshot(),
    val offlineSpeechPacks: OfflineSpeechPackStatus = OfflineSpeechPackStatus(),
    val segmentsWithTranscripts: List<SegmentWithTranscript> = emptyList(),
    val spokenTranscriptIds: Set<String> = emptySet(),
    val totalSyncedCount: Int = 0,
    val pendingAsrCount: Int = 0,
    val failedAsrCount: Int = 0,
    val activeAudioChunkCount: Int = 0,
    val activeAudioBytes: Long = 0L,
    val searchQuery: String = "",
    val languageFilter: String = "ALL",
    val playingSegmentId: String? = null,
    val playbackProgress: Float = 0f,
    val exportingSegmentId: String? = null,
    val lastExportedAudio: ExportedAudioResult? = null,
    val storageNotice: String? = null,
    // 2nd Mind Memory & Insights
    val memoryStatus: MemoryEngineStatusSnapshot = MemoryEngineStatusSnapshot(),
    val memories: List<MemoryItemEntity> = emptyList(),
    val archivedMemories: List<MemoryItemEntity> = emptyList(),
    val filteredMemories: List<MemoryItemEntity> = emptyList(),
    val memoryVaultFilter: MemoryVaultFilter = MemoryVaultFilter.ACTIVE,
    val memorySearchQuery: String = "",
    val selectedMemoryCategory: MemoryCategory? = null,
    val selectedEntityFilter: String? = null,
    val topEntities: List<EntityNodeEntity> = emptyList(),
    val tasks: List<TaskReminderEntity> = emptyList(),
    val summaries: List<SummaryEntity> = emptyList(),
    val insights: SecondMindInsightsSnapshot = SecondMindInsightsSnapshot(),
    val aiProductivityBrief: String? = null,
    val isGeneratingBrief: Boolean = false,
    val latestAskAiAnswer: AskAiAnswer? = null,
    val askAiHistory: List<AskAiAuditEntity> = emptyList(),
    val highlightedTranscriptId: String? = null,
    // Universal Offline AI Provider (com.uoai.provider)
    val uoaiConnection: UoaiConnectionState? = null,
    val uoaiAudioStatus: UoaiAudioStatusResponse? = null,
    val uoaiAudioProviders: List<UoaiAudioProviderInfo> = emptyList(),
    val uoaiAudioModels: List<UoaiAudioModelInfo> = emptyList(),
    val isManagingUoaiAudioModel: Boolean = false,
    val uoaiNotice: String? = null,
    val streamingAskText: String = ""
)

@OptIn(FlowPreview::class)
@HiltViewModel
class PrimaryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bleClient: BleClientController,
    private val scanner: BleScannerManager,
    private val permissionManager: PrimaryPermissionManager,
    private val syncManager: SyncManager,
    private val transcriptionCoordinator: TranscriptionCoordinator,
    private val androidOnDeviceAsrProvider: AndroidOnDeviceAsrProvider,
    private val memoryCoordinator: MemoryCoordinator,
    private val uoaiClient: UoaiClient,
    private val settingsRepo: PrimarySettingsRepository,
    private val audioStorage: PrimaryEncryptedAudioStorage,
    private val audioPlayback: AudioPlaybackHelper,
    private val audioRetentionManager: AudioRetentionManager,
    private val audioExportManager: AudioExportManager,
    private val pairedDao: PairedRecorderDao,
    auditDao: CommandAuditDao,
    private val syncedContentDao: SyncedContentDao,
    private val transcriptDao: TranscriptDao,
    private val memoryDao: MemoryDao
) : ViewModel() {

    // Scoped high-frequency playback progress flow so only the playing card recomposes (~6 Hz).
    val playbackProgressFlow: StateFlow<Float> = audioPlayback.playbackProgress

    private val _permissions = MutableStateFlow(permissionManager.getSnapshot())
    private val _lastFeedback = MutableStateFlow<CommandFeedback?>(null)
    private val _searchQuery = MutableStateFlow("")
    private val _languageFilter = MutableStateFlow("ALL")

    private val _memorySearchQuery = MutableStateFlow("")
    private val _selectedMemoryCategory = MutableStateFlow<MemoryCategory?>(null)
    private val _selectedEntityFilter = MutableStateFlow<String?>(null)
    private val _memoryVaultFilter = MutableStateFlow(MemoryVaultFilter.ACTIVE)
    private val _searchResultOverride = MutableStateFlow<List<MemoryItemEntity>?>(null)

    private val _latestAskAnswer = MutableStateFlow<AskAiAnswer?>(null)
    private val _highlightedTranscriptId = MutableStateFlow<String?>(null)
    private val _uoaiNotice = MutableStateFlow<String?>(null)
    private val _streamingAskText = MutableStateFlow("")
    private val _aiBrief = MutableStateFlow<String?>(null)
    private val _isGeneratingBrief = MutableStateFlow(false)
    private val _exportingSegmentId = MutableStateFlow<String?>(null)
    private val _lastExportedAudio = MutableStateFlow<ExportedAudioResult?>(null)
    private val _storageNotice = MutableStateFlow<String?>(null)
    private val _isManagingUoaiAudioModel = MutableStateFlow(false)

    private var activeAskJob: Job? = null
    private var memorySearchJob: Job? = null

    private val allSegmentsFlow = syncedContentDao.observeSegmentsWithTranscripts(300)
        .distinctUntilChanged()
    private val activeMemoriesFlow = memoryDao.observeActiveMemories(300)
        .distinctUntilChanged()
    private val archivedMemoriesFlow = memoryDao.observeArchivedMemories(200)
        .distinctUntilChanged()
    private val entitiesFlow = memoryDao.observeTopEntities(50)
        .distinctUntilChanged()
    private val tasksFlow = memoryDao.observeTasks()
        .distinctUntilChanged()

    // Heavy analytics run purely on Dispatchers.Default and only recompute when underlying records change —
    // completely decoupled from high-frequency SSE token streaming or BLE telemetry.
    private val insightsFlow: StateFlow<SecondMindInsightsSnapshot> = combine(
        allSegmentsFlow,
        activeMemoriesFlow,
        tasksFlow,
        entitiesFlow
    ) { segs, mems, tasks, ents ->
        SecondMindInsightsEngine.compute(segs, mems, tasks, ents)
    }
        .flowOn(Dispatchers.Default)
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), SecondMindInsightsSnapshot())

    // Off-main-thread transcript filtering + derived audio metadata
    private val filteredSegmentsFlow = combine(
        allSegmentsFlow,
        _searchQuery.debounce(150L),
        _languageFilter
    ) { items, query, langFilter ->
        val spokenItems = items.filterNot { it.segment.sessionId == "manual_notes" }
        val spokenIds = spokenItems.mapNotNull { it.transcript?.transcriptId }.toHashSet()
        val failedCount = spokenItems.count { it.segment.asrStatus == AsrProcessingStatus.FAILED }
        Triple(filterSegments(spokenItems, query, langFilter), spokenIds, failedCount)
    }
        .flowOn(Dispatchers.Default)
        .distinctUntilChanged()

    private val bleGroup = combine(
        bleClient.bleState,
        bleClient.remoteStatus,
        bleClient.remoteBattery,
        bleClient.remoteStorage,
        bleClient.remoteDeviceInfo
    ) { ble, status, batt, storage, info ->
        BleGroup(ble, status, batt, storage, info)
    }

    private val scanGroup = combine(
        scanner.isScanning,
        scanner.discovered,
        scanner.scanError,
        bleClient.pendingSas,
        bleClient.lastError
    ) { scanning, discovered, scanErr, sas, clientErr ->
        ScanGroup(scanning, discovered, scanErr ?: clientErr, sas)
    }

    private val syncAsrGroup = combine(
        settingsRepo.settingsFlow,
        syncManager.progress,
        transcriptionCoordinator.progress,
        audioPlayback.playingSegmentId,
        combine(
            _exportingSegmentId,
            _lastExportedAudio,
            _storageNotice,
            androidOnDeviceAsrProvider.packStatus
        ) { expId, exp, notice, speechPacks ->
            AudioActionBundle(expId, exp, notice, speechPacks)
        }
    ) { settings, syncProg, asrProg, playingId, audioActions ->
        SyncAsrGroup(settings, syncProg, asrProg, playingId, 0f, audioActions)
    }

    private val contentGroup = combine(
        filteredSegmentsFlow,
        syncedContentDao.observeTotalSyncedCount(),
        syncedContentDao.observePendingAsrCount(),
        combine(syncedContentDao.observeActiveAudioChunkCount(), syncedContentDao.observeActiveAudioBytes()) { c, b -> c to b },
        combine(_searchQuery, _languageFilter) { q, f -> q to f }
    ) { (filteredItems, spokenIds, failedCount), totalSynced, pendingAsr, (activeChunks, activeBytes), (query, langFilter) ->
        ContentGroup(filteredItems, spokenIds, totalSynced, pendingAsr, failedCount, activeChunks, activeBytes, query, langFilter)
    }

    private val memoryListsGroup = combine(
        memoryCoordinator.status,
        combine(activeMemoriesFlow, archivedMemoriesFlow) { act, arch -> act to arch },
        insightsFlow,
        tasksFlow,
        memoryDao.observeSummaries(40)
    ) { status, (memories, archived), insights, tasks, summaries ->
        MemoryListsGroup(status, memories, archived, insights, tasks, summaries)
    }

    private val memoryFiltersFlow = combine(
        _memorySearchQuery,
        _selectedMemoryCategory,
        _selectedEntityFilter,
        _memoryVaultFilter,
        _searchResultOverride
    ) { q, cat, ent, vault, override ->
        MemoryFilterBundle(q, cat, ent, vault, override)
    }

    private val uoaiAudioBundleFlow = combine(
        uoaiClient.audioStatus,
        uoaiClient.audioProviders,
        uoaiClient.audioModels,
        _isManagingUoaiAudioModel
    ) { audioStatus, audioProviders, audioModels, managingModel ->
        UoaiAudioBundle(audioStatus, audioProviders, audioModels, managingModel)
    }

    private val memoryQueryGroup = combine(
        memoryFiltersFlow,
        combine(_latestAskAnswer, _streamingAskText) { ans, stream -> ans to stream },
        combine(_aiBrief, _isGeneratingBrief) { brief, busy -> brief to busy },
        combine(
            memoryDao.observeAskAiHistory(30),
            _highlightedTranscriptId,
            uoaiClient.connectionState,
            _uoaiNotice,
            uoaiAudioBundleFlow
        ) { h, hi, uoaiConn, uoaiNotice, uoaiAudio ->
            UoaiHistoryBundle(h, hi, uoaiConn, uoaiNotice, uoaiAudio)
        }
    ) { filters, (latestAsk, streamText), (brief, briefBusy), bundle ->
        MemoryQueryGroup(
            filters = filters,
            latestAsk = latestAsk,
            askHistory = bundle.askHistory,
            highlightedTranscriptId = bundle.highlightedTranscriptId,
            uoaiConnection = bundle.uoaiConnection,
            uoaiAudioStatus = bundle.uoaiAudio.status,
            uoaiAudioProviders = bundle.uoaiAudio.providers,
            uoaiAudioModels = bundle.uoaiAudio.models,
            isManagingUoaiAudioModel = bundle.uoaiAudio.isManagingModel,
            uoaiNotice = bundle.uoaiNotice,
            streamingAskText = streamText,
            aiBrief = brief,
            isGeneratingBrief = briefBusy
        )
    }

    private val coreCombined = combine(
        _permissions,
        pairedDao.observePairedDevice(),
        bleGroup,
        scanGroup,
        combine(bleClient.remoteConfig, auditDao.observeRecent(30), _lastFeedback) { cfg, audit, fb ->
            Triple(cfg, audit, fb)
        }
    ) { perms, paired, ble, scan, (cfg, audit, fb) ->
        CoreGroup(perms, paired, ble, scan, cfg ?: RecorderConfigPayload(), audit, fb)
    }

    val uiState: StateFlow<PrimaryUiState> = combine(
        coreCombined,
        syncAsrGroup,
        contentGroup,
        memoryListsGroup,
        memoryQueryGroup
    ) { core, syncAsr, content, memLists, memQuery ->
        val f = memQuery.filters
        val baseMemories = when (f.vaultFilter) {
            MemoryVaultFilter.ARCHIVED -> memLists.archivedMemories
            MemoryVaultFilter.PINNED -> memLists.memories.filter { it.isPinned }
            MemoryVaultFilter.ACTIVE -> {
                if (f.searchOverride != null && f.searchQuery.isNotBlank()) {
                    f.searchOverride
                } else {
                    memLists.memories
                }
            }
        }
        val entityLower = f.selectedEntity?.trim()?.lowercase()
        val filteredMemories = baseMemories.filter { m ->
            val catOk = f.selectedCategory == null || m.category == f.selectedCategory
            val entOk = entityLower.isNullOrEmpty() ||
                m.entitiesCsv.lowercase().contains(entityLower) ||
                m.title.lowercase().contains(entityLower) ||
                m.content.lowercase().contains(entityLower)
            val archivedSearchOk = if (f.vaultFilter != MemoryVaultFilter.ACTIVE && f.searchQuery.isNotBlank()) {
                val q = f.searchQuery.trim().lowercase()
                m.title.lowercase().contains(q) || m.content.lowercase().contains(q)
            } else {
                true
            }
            catOk && entOk && archivedSearchOk
        }

        PrimaryUiState(
            permissions = core.perms,
            pairedDevice = core.paired,
            bleState = core.ble.ble,
            remoteStatus = core.ble.status,
            remoteBattery = core.ble.battery,
            remoteStorage = core.ble.storage,
            remoteDeviceInfo = core.ble.info,
            remoteConfig = core.config,
            isScanning = core.scan.scanning,
            discoveredDevices = core.scan.discovered,
            pendingSas = core.scan.pendingSas,
            lastError = core.scan.error,
            lastFeedback = core.feedback,
            auditLog = core.audit,
            appSettings = syncAsr.settings,
            syncProgress = syncAsr.syncProgress,
            asrProgress = syncAsr.asrProgress,
            offlineSpeechPacks = syncAsr.audioActions.speechPacks,
            segmentsWithTranscripts = content.items,
            spokenTranscriptIds = content.spokenTranscriptIds,
            totalSyncedCount = content.totalSynced,
            pendingAsrCount = content.pendingAsr,
            failedAsrCount = content.failedAsrCount,
            activeAudioChunkCount = content.activeAudioChunkCount,
            activeAudioBytes = content.activeAudioBytes,
            searchQuery = content.query,
            languageFilter = content.langFilter,
            playingSegmentId = syncAsr.playingSegmentId,
            playbackProgress = syncAsr.playbackProgress,
            exportingSegmentId = syncAsr.audioActions.exportingSegmentId,
            lastExportedAudio = syncAsr.audioActions.lastExportedAudio,
            storageNotice = syncAsr.audioActions.storageNotice,
            memoryStatus = memLists.status,
            memories = memLists.memories,
            archivedMemories = memLists.archivedMemories,
            filteredMemories = filteredMemories,
            memoryVaultFilter = f.vaultFilter,
            memorySearchQuery = f.searchQuery,
            selectedMemoryCategory = f.selectedCategory,
            selectedEntityFilter = f.selectedEntity,
            topEntities = memLists.insights.cleanTopEntities,
            tasks = memLists.tasks,
            summaries = memLists.summaries,
            insights = memLists.insights,
            aiProductivityBrief = memQuery.aiBrief,
            isGeneratingBrief = memQuery.isGeneratingBrief,
            latestAskAiAnswer = memQuery.latestAsk,
            askAiHistory = memQuery.askHistory,
            highlightedTranscriptId = memQuery.highlightedTranscriptId,
            uoaiConnection = memQuery.uoaiConnection,
            uoaiAudioStatus = memQuery.uoaiAudioStatus,
            uoaiAudioProviders = memQuery.uoaiAudioProviders,
            uoaiAudioModels = memQuery.uoaiAudioModels,
            isManagingUoaiAudioModel = memQuery.isManagingUoaiAudioModel,
            uoaiNotice = memQuery.uoaiNotice,
            streamingAskText = memQuery.streamingAskText
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = PrimaryUiState(permissions = _permissions.value)
        )

    init {
        viewModelScope.launch {
            bleClient.commandFeedback.collect { fb -> _lastFeedback.value = fb }
        }
        viewModelScope.launch {
            audioPlayback.lastError.collect { err ->
                if (!err.isNullOrBlank()) {
                    _storageNotice.value = err
                    audioPlayback.clearError()
                }
            }
        }
        refreshUoaiConnection()
        refreshOfflineSpeechPacks()
        ensureBackgroundLinkIfNeeded()
    }

    fun ensureBackgroundLinkIfNeeded() {
        viewModelScope.launch(Dispatchers.IO) {
            val paired = pairedDao.getPairedDevice() ?: return@launch
            if (!permissionManager.hasBlePermissions()) return@launch
            val settings = settingsRepo.getSettings()
            if (settings.keepBackgroundLinkAlive) {
                if (!PrimarySyncService.isServiceRunning) {
                    PrimarySyncService.start(context)
                }
            }
            bleClient.ensureConnectedToPaired()
        }
    }

    private fun filterSegments(
        all: List<SegmentWithTranscript>,
        query: String,
        langFilter: String
    ): List<SegmentWithTranscript> {
        val q = query.trim().lowercase()
        return all.filter { item ->
            if (item.segment.sessionId == "manual_notes") return@filter false
            val langOk = when (langFilter) {
                "EN" -> item.transcript?.language?.startsWith("en") == true || item.transcript?.language == "hi-en"
                "HI" -> item.transcript?.language?.startsWith("hi") == true
                "PENDING" -> item.segment.asrStatus == AsrProcessingStatus.PENDING ||
                    item.segment.asrStatus == AsrProcessingStatus.PROCESSING
                "FAILED" -> item.segment.asrStatus == AsrProcessingStatus.FAILED
                "AUDIO_ONLY" -> !item.segment.audioDeleted
                else -> true
            }
            val queryOk = q.isEmpty() ||
                item.transcript?.text?.lowercase()?.contains(q) == true ||
                item.segment.sequenceNumber.toString().contains(q)
            langOk && queryOk
        }
    }

    fun refreshPermissions() {
        _permissions.value = permissionManager.getSnapshot()
    }

    fun requiredPermissions(): Array<String> = permissionManager.requiredRuntimePermissions().toTypedArray()
    fun appDetailsIntent(): Intent = permissionManager.createAppDetailsIntent()

    fun startScan() = scanner.startScan()
    fun stopScan() = scanner.stopScan()
    fun beginPairing(address: String) {
        scanner.stopScan()
        bleClient.beginPairing(address)
    }

    fun confirmSas() {
        bleClient.confirmSasAndSavePairing()
        viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(500L)
            if (settingsRepo.getSettings().keepBackgroundLinkAlive) {
                PrimarySyncService.start(context)
            }
        }
    }
    fun cancelSas() = bleClient.cancelPendingPairing()
    fun connectPaired() {
        bleClient.connectToPairedRecorder(autoConnect = false)
        viewModelScope.launch(Dispatchers.IO) {
            if (settingsRepo.getSettings().keepBackgroundLinkAlive && !PrimarySyncService.isServiceRunning) {
                PrimarySyncService.start(context)
            }
        }
    }
    fun disconnect() {
        PrimarySyncService.stop(context)
        bleClient.disconnect(userInitiated = true)
    }
    fun unpair() {
        PrimarySyncService.stop(context)
        bleClient.unpair()
        viewModelScope.launch { settingsRepo.resetSyncCursor() }
    }

    /**
     * Cleanly stops active Primary background sync service, BLE scanning/connections, audio playback,
     * and Ask AI streaming before switching this device into Secondary Recorder mode.
     */
    fun prepareForRoleSwitch() {
        PrimarySyncService.stop(context)
        scanner.stopScan()
        bleClient.disconnect(userInitiated = true)
        audioPlayback.stop()
        stopAskAi()
    }

    fun startRecording() = bleClient.startRecording()
    fun stopRecording(mode: StopMode) = bleClient.stopRecording(mode)
    fun refreshAll() {
        bleClient.refreshStatus()
        bleClient.refreshBattery()
        bleClient.refreshStorage()
        bleClient.refreshDeviceInfo()
    }

    fun ping() = bleClient.ping()
    fun syncNow() = syncManager.triggerSyncNow()
    fun setConfig(cfg: RecorderConfigPayload) = bleClient.setConfig(cfg)
    fun setLocalOnlyPauseSync(paused: Boolean) = bleClient.setLocalOnlyPauseSync(paused)
    fun clearFeedback() { _lastFeedback.value = null }
    fun clearLastError() {
        scanner.clearError()
        bleClient.clearError()
    }

    fun setSearchQuery(q: String) { _searchQuery.value = q }
    fun setLanguageFilter(filter: String) { _languageFilter.value = filter }
    fun toggleAudioPlayback(segment: SyncedSegmentEntity) = audioPlayback.togglePlayback(segment)
    fun retrySegmentAsr(segmentId: String) = transcriptionCoordinator.retrySegment(segmentId)
    fun retryAllFailedAsr() = transcriptionCoordinator.retryAllFailed()
    fun runPendingAsrNow() = transcriptionCoordinator.enqueuePendingProcessing()

    fun refreshOfflineSpeechPacks() {
        viewModelScope.launch(Dispatchers.IO) {
            androidOnDeviceAsrProvider.inspectOfflineSpeechStatus(forceRefresh = true)
        }
    }

    fun downloadOfflineSpeechPacks() {
        viewModelScope.launch(Dispatchers.IO) {
            val mode = settingsRepo.getSettings().asrLanguageMode
            androidOnDeviceAsrProvider.downloadRecommendedOfflinePacks(mode)
                .onSuccess { msg ->
                    _storageNotice.value = msg
                    transcriptionCoordinator.retryAllFailed()
                }
                .onFailure { err ->
                    _storageNotice.value = err.message ?: "Offline speech pack download failed."
                }
        }
    }

    // Audio Retention (1d / 3d / 5d / Keep All) & Download Specific Chunk to Device
    fun setAudioRetentionPolicy(policy: AudioRetentionPolicy) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepo.setAudioRetentionPolicy(policy)
            val report = audioRetentionManager.enforceRetentionPolicy()
            _storageNotice.value = if (report.deletedChunks > 0) {
                "Policy set to ${policy.shortLabel} • Pruned ${report.deletedChunks} verified audio chunk(s) (${formatBytesMb(report.freedBytes)} freed; transcripts kept)."
            } else if (policy.isImmediateAfterTranscription) {
                "Auto-delete set to After ASR. Verified audio chunks are deleted immediately after transcription; failed/inaudible clips stay preserved."
            } else {
                "Auto-delete policy set to ${policy.displayName}. Transcripts & memories are always preserved."
            }
        }
    }

    fun purgeExpiredAudioNow() {
        viewModelScope.launch(Dispatchers.IO) {
            val report = audioRetentionManager.enforceRetentionPolicy()
            _storageNotice.value = if (report.deletedChunks > 0) {
                "Cleaned ${report.deletedChunks} verified audio chunk(s), freed ${formatBytesMb(report.freedBytes)}. Transcripts kept intact."
            } else if (report.policy == AudioRetentionPolicy.KEEP_FOREVER) {
                "Policy is Keep All — choose After ASR, an hourly/daily window, or delete individual chunks."
            } else {
                "No verified transcribed audio chunks eligible under ${report.policy.shortLabel} policy."
            }
        }
    }

    fun deleteSpecificChunkAudio(segment: SyncedSegmentEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            audioRetentionManager.deleteSpecificChunkAudio(segment.segmentId)
                .onSuccess { freed ->
                    _storageNotice.value = "Deleted audio for #${segment.sequenceNumber} (${formatBytesMb(freed)} freed). Transcript & memories preserved."
                }
                .onFailure { err ->
                    _storageNotice.value = "Could not delete audio: ${err.message}"
                }
        }
    }

    fun downloadSpecificChunk(segment: SyncedSegmentEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            _exportingSegmentId.value = segment.segmentId
            audioExportManager.downloadChunkToDevice(segment)
                .onSuccess { exported ->
                    _lastExportedAudio.value = exported
                    _storageNotice.value = "Saved ${exported.fileName} to ${exported.relativeFolder} (${formatBytesMb(exported.sizeBytes)})."
                }
                .onFailure { err ->
                    _storageNotice.value = "Download failed: ${err.message}"
                }
            _exportingSegmentId.value = null
        }
    }

    fun buildShareExportedIntent(exported: ExportedAudioResult): Intent =
        audioExportManager.buildShareAudioIntent(exported)

    fun clearExportFeedback() { _lastExportedAudio.value = null }
    fun clearStorageNotice() { _storageNotice.value = null }

    // 2nd Mind Memory Management, Insights, Summaries, Tasks, Ask AI
    fun extractPendingMemories() = memoryCoordinator.enqueuePendingExtraction()
    fun retryFailedMemoryExtraction() = memoryCoordinator.retryFailedExtractions()

    fun setMemoryVaultFilter(filter: MemoryVaultFilter) {
        _memoryVaultFilter.value = filter
    }

    fun setEntityFilter(entityName: String?) {
        _selectedEntityFilter.value = if (_selectedEntityFilter.value == entityName) null else entityName
    }

    fun setMemorySearchQuery(query: String) {
        _memorySearchQuery.value = query
        runHybridMemorySearch(query, _selectedMemoryCategory.value)
    }

    fun setMemoryCategoryFilter(category: MemoryCategory?) {
        _selectedMemoryCategory.value = category
        runHybridMemorySearch(_memorySearchQuery.value, category)
    }

    private fun runHybridMemorySearch(query: String, category: MemoryCategory?) {
        memorySearchJob?.cancel()
        if (query.isBlank()) {
            _searchResultOverride.value = null
            return
        }
        memorySearchJob = viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(140L)
            val hits = memoryCoordinator.searchMemories(query, SearchFilter(category = category))
            _searchResultOverride.value = hits.map { it.memory }
        }
    }

    fun togglePinMemory(memoryId: String, currentlyPinned: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            memoryCoordinator.togglePinMemory(memoryId, !currentlyPinned)
        }
    }

    fun editMemory(memoryId: String, title: String, content: String, category: MemoryCategory) {
        viewModelScope.launch(Dispatchers.IO) {
            memoryCoordinator.editMemory(memoryId, title, content, category)
        }
    }

    fun archiveMemory(memoryId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            memoryCoordinator.archiveMemory(memoryId)
        }
    }

    fun restoreArchivedMemory(memoryId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            memoryCoordinator.restoreArchivedMemory(memoryId)
        }
    }

    fun deleteMemoryPermanently(memoryId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            memoryCoordinator.deleteMemoryPermanently(memoryId)
        }
    }

    fun promoteMemoryToTask(memory: MemoryItemEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            memoryCoordinator.promoteMemoryToTask(memory)
            _storageNotice.value = "Added \"${memory.title.take(36)}\" to Open Tasks."
        }
    }

    fun captureManualMemory(
        title: String,
        content: String,
        category: MemoryCategory,
        pin: Boolean,
        createTask: Boolean
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            memoryCoordinator.captureManualMemory(title, content, category, pin, createTask)
                .onSuccess {
                    _storageNotice.value = "Saved thought to your Sunbo 2nd Mind Vault."
                }
                .onFailure { err ->
                    _storageNotice.value = "Could not save note: ${err.message}"
                }
        }
    }

    fun generateSummary(type: SummaryType) {
        viewModelScope.launch(Dispatchers.IO) {
            memoryCoordinator.generateSummary(type)
        }
    }

    fun generateProductivityBrief() {
        if (_isGeneratingBrief.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _isGeneratingBrief.value = true
            memoryCoordinator.generateProductivityBrief(insightsFlow.value)
                .onSuccess { _aiBrief.value = it }
                .onFailure { _aiBrief.value = "Unable to build brief: ${it.message}" }
            _isGeneratingBrief.value = false
        }
    }

    fun askAi(question: String) {
        if (question.isBlank()) return
        activeAskJob?.cancel()
        _streamingAskText.value = ""
        activeAskJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val ans = memoryCoordinator.askQuestion(question) { partial ->
                    _streamingAskText.value = partial
                }
                _latestAskAnswer.value = ans
                _streamingAskText.value = ""
            } catch (_: CancellationException) {
                val partial = _streamingAskText.value
                _streamingAskText.value = ""
                if (partial.isNotBlank()) {
                    _uoaiNotice.value = "Generation stopped."
                }
            }
        }
    }

    fun stopAskAi() {
        activeAskJob?.cancel()
        activeAskJob = null
    }

    fun refreshUoaiConnection() {
        viewModelScope.launch(Dispatchers.IO) {
            uoaiClient.checkConnection()
        }
    }

    fun onUoaiRegistrationResult(resultCode: Int, data: Intent?) {
        when (val res = uoaiClient.handleRegistrationResult(resultCode, data)) {
            is UoaiRegistrationResult.Approved -> {
                _uoaiNotice.value = null
                refreshUoaiConnection()
                viewModelScope.launch(Dispatchers.IO) {
                    if (settingsRepo.getSettings().asrProviderType == AsrProviderType.UOAI_PROVIDER) {
                        transcriptionCoordinator.enqueuePendingProcessing()
                    }
                }
            }
            is UoaiRegistrationResult.Denied -> {
                _uoaiNotice.value = "UOAI access denied (${res.errorCode}): ${res.message}"
                refreshUoaiConnection()
            }
        }
    }

    fun clearUoaiCredentials() {
        viewModelScope.launch(Dispatchers.IO) {
            uoaiClient.clearCredentials()
            _uoaiNotice.value = "UOAI credentials cleared."
            uoaiClient.checkConnection()
        }
    }

    fun updateTaskStatus(taskId: String, status: TaskReminderStatus) {
        viewModelScope.launch(Dispatchers.IO) {
            memoryCoordinator.updateTaskStatus(taskId, status)
        }
    }

    fun updateTaskPriority(taskId: String, priority: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            memoryCoordinator.updateTaskPriority(taskId, priority)
        }
    }

    fun createManualTask(title: String, notes: String, dueLabel: String?, priority: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            memoryCoordinator.createManualTask(title, notes, dueLabel, priority)
                .onSuccess { _storageNotice.value = "Added \"${it.title.take(36)}\" to Tasks." }
                .onFailure { _storageNotice.value = "Could not add task: ${it.message}" }
        }
    }

    fun editTask(task: TaskReminderEntity, title: String, notes: String, dueLabel: String?, priority: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            memoryCoordinator.editTask(task, title, notes, dueLabel, priority)
            _storageNotice.value = "Updated task \"${title.trim().take(36)}\"."
        }
    }

    fun highlightTranscript(transcriptId: String?) {
        _highlightedTranscriptId.value = transcriptId
    }

    // Settings (100% On-Device / Loopback Privacy Only)
    fun setAsrProvider(type: AsrProviderType) {
        viewModelScope.launch {
            settingsRepo.setAsrProvider(type)
            if (type == AsrProviderType.UOAI_PROVIDER) {
                refreshUoaiConnection()
            }
        }
    }
    fun setAsrLanguage(mode: AsrLanguageMode) { viewModelScope.launch { settingsRepo.setAsrLanguage(mode) } }
    fun setUoaiAsrProviderId(providerId: String) {
        viewModelScope.launch {
            settingsRepo.setUoaiAsrProviderId(providerId)
            refreshUoaiConnection()
        }
    }
    fun setUoaiAsrModel(model: String) { viewModelScope.launch { settingsRepo.setUoaiAsrModel(model) } }
    fun setUoaiAsrScript(script: UoaiAsrScript) { viewModelScope.launch { settingsRepo.setUoaiAsrScript(script) } }

    fun loadUoaiAudioModel(model: String) {
        if (_isManagingUoaiAudioModel.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _isManagingUoaiAudioModel.value = true
            uoaiClient.loadAudioModel(model)
                .onSuccess { msg -> _storageNotice.value = msg }
                .onFailure { err ->
                    _storageNotice.value = if (err is com.personalai.primary.memory.uoai.UoaiException.PermissionDenied) {
                        "Missing 'asr_model_control' permission in UOAI Provider → Clients. Load the model directly inside UOAI Provider or enable asr_model_control."
                    } else {
                        "Could not load UOAI audio model: ${err.message}"
                    }
                }
            _isManagingUoaiAudioModel.value = false
        }
    }

    fun unloadUoaiAudioModels() {
        if (_isManagingUoaiAudioModel.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _isManagingUoaiAudioModel.value = true
            uoaiClient.unloadAudioModel(force = false)
                .onSuccess { msg -> _storageNotice.value = msg }
                .onFailure { err ->
                    _storageNotice.value = "Could not unload UOAI audio models: ${err.message}"
                }
            _isManagingUoaiAudioModel.value = false
        }
    }
    fun setLlmProvider(type: LlmProviderType) { viewModelScope.launch { settingsRepo.setLlmProvider(type) } }
    fun setAutoExtractMemory(enabled: Boolean) { viewModelScope.launch { settingsRepo.setAutoExtractMemory(enabled) } }
    fun setLocalWhisperUrl(url: String) { viewModelScope.launch { settingsRepo.setLocalWhisperUrl(url) } }
    fun setKeepBackgroundLinkAlive(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepo.setKeepBackgroundLinkAlive(enabled)
            if (enabled) {
                if (pairedDao.getPairedDevice() != null && permissionManager.hasBlePermissions()) {
                    PrimarySyncService.start(context)
                }
            } else {
                PrimarySyncService.stop(context)
            }
        }
    }
    fun setAutoSyncOnReconnect(enabled: Boolean) { viewModelScope.launch { settingsRepo.setAutoSyncOnReconnect(enabled) } }
    fun setAutoTranscribeSynced(enabled: Boolean) { viewModelScope.launch { settingsRepo.setAutoTranscribeSynced(enabled) } }

    fun wipeAllLocalSyncedData() {
        viewModelScope.launch(Dispatchers.IO) {
            audioPlayback.stop()
            audioStorage.wipeAllSyncedAudio()
            memoryCoordinator.clearSearchCache()
            memoryDao.deleteAllAskAiHistory()
            memoryDao.deleteAllEmbeddings()
            memoryDao.deleteAllSummaries()
            memoryDao.deleteAllTasks()
            memoryDao.deleteAllMemories()
            memoryDao.deleteAllEntities()
            transcriptDao.deleteAll()
            syncedContentDao.deleteAllSegments()
            syncedContentDao.deleteAllSessions()
            settingsRepo.resetSyncCursor()
            _latestAskAnswer.value = null
            _searchResultOverride.value = null
            _aiBrief.value = null
        }
    }

    override fun onCleared() {
        audioPlayback.stop()
        super.onCleared()
    }

    private fun formatBytesMb(bytes: Long): String {
        if (bytes < 1024L * 1024L) {
            return "${(bytes / 1024L).coerceAtLeast(1L)} KB"
        }
        return "${"%.2f".format(bytes / (1024.0 * 1024.0))} MB"
    }

    private data class BleGroup(
        val ble: BleConnectionState,
        val status: RecorderStatusPayload?,
        val battery: BatteryInfoPayload?,
        val storage: StorageInfoPayload?,
        val info: DeviceInfoPayload?
    )

    private data class ScanGroup(
        val scanning: Boolean,
        val discovered: List<DiscoveredRecorderDevice>,
        val error: String?,
        val pendingSas: PendingSasConfirmation?
    )

    private data class AudioActionBundle(
        val exportingSegmentId: String?,
        val lastExportedAudio: ExportedAudioResult?,
        val storageNotice: String?,
        val speechPacks: OfflineSpeechPackStatus
    )

    private data class SyncAsrGroup(
        val settings: PrimaryAppSettings,
        val syncProgress: SyncProgressSnapshot,
        val asrProgress: AsrProgressSnapshot,
        val playingSegmentId: String?,
        val playbackProgress: Float,
        val audioActions: AudioActionBundle
    )

    private data class ContentGroup(
        val items: List<SegmentWithTranscript>,
        val spokenTranscriptIds: Set<String>,
        val totalSynced: Int,
        val pendingAsr: Int,
        val failedAsrCount: Int,
        val activeAudioChunkCount: Int,
        val activeAudioBytes: Long,
        val query: String,
        val langFilter: String
    )

    private data class MemoryListsGroup(
        val status: MemoryEngineStatusSnapshot,
        val memories: List<MemoryItemEntity>,
        val archivedMemories: List<MemoryItemEntity>,
        val insights: SecondMindInsightsSnapshot,
        val tasks: List<TaskReminderEntity>,
        val summaries: List<SummaryEntity>
    )

    private data class MemoryFilterBundle(
        val searchQuery: String,
        val selectedCategory: MemoryCategory?,
        val selectedEntity: String?,
        val vaultFilter: MemoryVaultFilter,
        val searchOverride: List<MemoryItemEntity>?
    )

    private data class UoaiAudioBundle(
        val status: UoaiAudioStatusResponse?,
        val providers: List<UoaiAudioProviderInfo>,
        val models: List<UoaiAudioModelInfo>,
        val isManagingModel: Boolean
    )

    private data class UoaiHistoryBundle(
        val askHistory: List<AskAiAuditEntity>,
        val highlightedTranscriptId: String?,
        val uoaiConnection: UoaiConnectionState?,
        val uoaiNotice: String?,
        val uoaiAudio: UoaiAudioBundle
    )

    private data class MemoryQueryGroup(
        val filters: MemoryFilterBundle,
        val latestAsk: AskAiAnswer?,
        val askHistory: List<AskAiAuditEntity>,
        val highlightedTranscriptId: String?,
        val uoaiConnection: UoaiConnectionState?,
        val uoaiAudioStatus: UoaiAudioStatusResponse?,
        val uoaiAudioProviders: List<UoaiAudioProviderInfo>,
        val uoaiAudioModels: List<UoaiAudioModelInfo>,
        val isManagingUoaiAudioModel: Boolean,
        val uoaiNotice: String?,
        val streamingAskText: String,
        val aiBrief: String?,
        val isGeneratingBrief: Boolean
    )

    private data class CoreGroup(
        val perms: PrimaryPermissionsSnapshot,
        val paired: PairedRecorderEntity?,
        val ble: BleGroup,
        val scan: ScanGroup,
        val config: RecorderConfigPayload,
        val audit: List<CommandAuditEntity>,
        val feedback: CommandFeedback?
    )
}
