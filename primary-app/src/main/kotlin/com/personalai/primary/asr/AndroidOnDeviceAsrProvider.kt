package com.personalai.primary.asr

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FileOutputStream
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device speech recognizer using Android 13+ `RecognizerIntent.EXTRA_AUDIO_SOURCE`
 * with automatic offline language pack discovery (`checkRecognitionSupport`), graceful fallback
 * across installed packs (e.g., `en-US` / `en-IN` / `en-GB` / `hi-IN`), and automatic/manual
 * pack downloads (`triggerModelDownload`) via Android's built-in Speech Services.
 */
@Singleton
class AndroidOnDeviceAsrProvider @Inject constructor(
    @ApplicationContext private val context: Context
) : AsrProvider {

    override val providerType: AsrProviderType = AsrProviderType.ANDROID_ON_DEVICE

    private val _packStatus = MutableStateFlow(OfflineSpeechPackStatus())
    val packStatus: StateFlow<OfflineSpeechPackStatus> = _packStatus.asStateFlow()

    private val supportMutex = Mutex()
    private val downloadMutex = Mutex()

    @Volatile
    private var lastSupportCheckMs: Long = 0L

    /**
     * Queries Android's on-device SpeechRecognizer for installed, pending, and supported offline
     * language packs (API 33+).
     */
    suspend fun inspectOfflineSpeechStatus(forceRefresh: Boolean = false): OfflineSpeechPackStatus {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            val unsupported = OfflineSpeechPackStatus(
                isSupportedByDevice = false,
                lastMessage = "Requires Android 13+ (API 33) for on-device WAV recognition.",
                lastCheckedAtMs = System.currentTimeMillis()
            )
            _packStatus.value = unsupported
            return unsupported
        }

        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            val unavailable = OfflineSpeechPackStatus(
                isSupportedByDevice = false,
                lastMessage = "Android on-device speech recognition is not available on this device.",
                lastCheckedAtMs = System.currentTimeMillis()
            )
            _packStatus.value = unavailable
            return unavailable
        }

        return supportMutex.withLock {
            val now = System.currentTimeMillis()
            val current = _packStatus.value
            if (!forceRefresh &&
                current.installedLanguages.isNotEmpty() &&
                now - lastSupportCheckMs < SUPPORT_CACHE_TTL_MS
            ) {
                return@withLock current
            }

            _packStatus.update { it.copy(isSupportedByDevice = true, isChecking = true) }
            val result = queryRecognitionSupportApi33()
            val checkedAt = System.currentTimeMillis()
            lastSupportCheckMs = checkedAt

            val updated = if (result != null) {
                OfflineSpeechPackStatus(
                    isSupportedByDevice = true,
                    isChecking = false,
                    isDownloading = _packStatus.value.isDownloading,
                    downloadProgressPercent = _packStatus.value.downloadProgressPercent,
                    activeDownloadLanguage = _packStatus.value.activeDownloadLanguage,
                    installedLanguages = result.installedOnDeviceLanguages.distinct(),
                    pendingLanguages = result.pendingOnDeviceLanguages.distinct(),
                    supportedLanguages = result.supportedOnDeviceLanguages.distinct(),
                    lastMessage = when {
                        result.installedOnDeviceLanguages.isNotEmpty() ->
                            "Installed offline packs: ${result.installedOnDeviceLanguages.joinToString(", ")}"
                        result.pendingOnDeviceLanguages.isNotEmpty() ->
                            "Offline packs downloading: ${result.pendingOnDeviceLanguages.joinToString(", ")}"
                        else ->
                            "No offline speech packs downloaded yet. Tap Download Packs (Wi-Fi recommended)."
                    },
                    lastCheckedAtMs = checkedAt
                )
            } else {
                _packStatus.value.copy(
                    isSupportedByDevice = true,
                    isChecking = false,
                    lastMessage = _packStatus.value.lastMessage
                        ?: "Could not query Speech Services pack list; will probe device locale directly.",
                    lastCheckedAtMs = checkedAt
                )
            }
            _packStatus.value = updated
            updated
        }
    }

    /**
     * Triggers download of recommended offline English / Hindi speech packs via Android Speech Services.
     */
    suspend fun downloadRecommendedOfflinePacks(
        languageMode: AsrLanguageMode,
        explicitTags: List<String>? = null
    ): Result<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return Result.failure(UnsupportedOperationException("On-device speech pack download requires Android 13+."))
        }
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            return Result.failure(IllegalStateException("Android on-device speech recognition is unavailable on this device."))
        }

        val status = inspectOfflineSpeechStatus(forceRefresh = true)
        val deviceLocaleTag = Locale.getDefault().toLanguageTag()
        val plan = OfflineSpeechLanguageResolver.resolve(
            mode = languageMode,
            installedLanguages = status.installedLanguages,
            pendingLanguages = status.pendingLanguages,
            supportedLanguages = status.supportedLanguages,
            deviceLocaleTag = deviceLocaleTag
        )

        val tagsToDownload = (explicitTags?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
            ?: plan.missingPreferredTags.ifEmpty {
                listOfNotNull(plan.primaryDownloadTag ?: OfflineSpeechLanguageResolver.defaultDownloadTag(languageMode, deviceLocaleTag))
            }).distinct()

        if (tagsToDownload.isEmpty()) {
            return Result.success("Offline speech packs are already installed (${status.installedLanguages.joinToString(", ")}).")
        }

        val completed = mutableListOf<String>()
        val scheduled = mutableListOf<String>()
        val failed = mutableListOf<String>()

        for (tag in tagsToDownload) {
            when (val outcome = downloadSingleLanguagePack(tag, waitTimeoutMs = 75_000L)) {
                is ModelDownloadOutcome.Succeeded -> completed.add(tag)
                is ModelDownloadOutcome.Scheduled -> scheduled.add(tag)
                is ModelDownloadOutcome.Failed -> failed.add("$tag (${outcome.reason})")
            }
        }

        // Refresh installed list after download attempt
        inspectOfflineSpeechStatus(forceRefresh = true)

        return when {
            completed.isNotEmpty() -> {
                val extra = if (scheduled.isNotEmpty()) " • Scheduled: ${scheduled.joinToString(", ")}" else ""
                val msg = "Downloaded offline speech pack(s): ${completed.joinToString(", ")}$extra."
                _packStatus.update { it.copy(lastMessage = msg) }
                Result.success(msg)
            }
            scheduled.isNotEmpty() -> {
                val msg = "Download scheduled by Android Speech Services for ${scheduled.joinToString(", ")}. Keep Wi-Fi on and retry in ~30s."
                _packStatus.update { it.copy(lastMessage = msg) }
                Result.success(msg)
            }
            else -> {
                val msg = "Could not download offline speech pack(s): ${failed.joinToString("; ")}. Ensure Wi-Fi is connected and Speech Recognition & Synthesis by Google is enabled."
                _packStatus.update { it.copy(lastMessage = msg) }
                Result.failure(IllegalStateException(msg))
            }
        }
    }

    override suspend fun transcribe(request: AsrRequest): Result<AsrResult> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return Result.failure(
                UnsupportedOperationException(
                    "On-device WAV stream recognition requires Android 13+ (API 33). Switch to Local Loopback Whisper in Settings."
                )
            )
        }
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            return Result.failure(
                IllegalStateException(
                    "Android on-device speech recognition is unavailable on this phone. Install offline English/Hindi speech packs or configure Local Loopback Whisper (127.0.0.1) in Settings."
                )
            )
        }

        val pcmOffset = findWavDataOffset(request.wavBytes)
        val pcmLength = (request.wavBytes.size - pcmOffset).coerceAtLeast(0)
        if (pcmLength <= 0) {
            return Result.failure(IllegalArgumentException("WAV payload contains no PCM audio frames"))
        }

        val deviceLocaleTag = Locale.getDefault().toLanguageTag()
        var status = inspectOfflineSpeechStatus(forceRefresh = _packStatus.value.installedLanguages.isEmpty())
        var plan = OfflineSpeechLanguageResolver.resolve(
            mode = request.languageMode,
            installedLanguages = status.installedLanguages,
            pendingLanguages = status.pendingLanguages,
            supportedLanguages = status.supportedLanguages,
            deviceLocaleTag = deviceLocaleTag
        )
        Log.d(
            TAG,
            "seg=${request.segmentId} mode=${request.languageMode} deviceLocale=$deviceLocaleTag " +
                "installed=${status.installedLanguages} pending=${status.pendingLanguages} " +
                "candidates=${plan.candidateTags} allowedDetect=${plan.allowedDetectionTags} " +
                "primaryDownload=${plan.primaryDownloadTag}"
        )

        var autoDownloadOutcome: ModelDownloadOutcome? = null
        var autoDownloadedTag: String? = null

        // If zero offline packs are installed yet (or user chose Hindi with no hi-* pack installed),
        // trigger an automatic download via Android Speech Services and wait briefly if API 34+ completes it live.
        val needsImmediateHindiPack = request.languageMode == AsrLanguageMode.HINDI &&
            plan.candidateTags.none { OfflineSpeechLanguageResolver.normalizeTag(it).lowercase(Locale.ROOT).startsWith("hi") }

        if ((plan.candidateTags.isEmpty() || needsImmediateHindiPack) && plan.primaryDownloadTag != null) {
            val dlTag = plan.primaryDownloadTag
            Log.i(TAG, "Auto-triggering offline speech pack download for $dlTag (installed=${status.installedLanguages})")
            autoDownloadedTag = dlTag
            autoDownloadOutcome = downloadSingleLanguagePack(
                languageTag = dlTag,
                waitTimeoutMs = if (plan.candidateTags.isEmpty()) 60_000L else 20_000L
            )
            if (autoDownloadOutcome is ModelDownloadOutcome.Succeeded) {
                status = inspectOfflineSpeechStatus(forceRefresh = true)
                plan = OfflineSpeechLanguageResolver.resolve(
                    mode = request.languageMode,
                    installedLanguages = status.installedLanguages,
                    pendingLanguages = status.pendingLanguages,
                    supportedLanguages = status.supportedLanguages,
                    deviceLocaleTag = deviceLocaleTag
                )
            }
        }

        // Build ordered attempt list: (languageTag, allowedDetectionTags)
        val attempts = buildList {
            if (plan.candidateTags.isNotEmpty()) {
                val first = plan.candidateTags.first()
                if (plan.canEnableMultiLanguageDetection) {
                    add(first to plan.allowedDetectionTags)
                    // Fallback without EXTRA_ENABLE_LANGUAGE_DETECTION in case OEM SODA rejects multi-lang detection
                    add(first to emptyList())
                } else {
                    add(first to emptyList())
                }
                plan.candidateTags.drop(1).forEach { fallbackTag ->
                    add(fallbackTag to emptyList())
                }
            } else {
                // Probe device locale + standard packs directly even if checkRecognitionSupport reported none
                OfflineSpeechLanguageResolver.fallbackProbeTags(request.languageMode, deviceLocaleTag).forEach { tag ->
                    add(tag to emptyList())
                }
            }
        }

        val triedTags = mutableListOf<String>()
        var lastLangError: LanguagePackUnavailableException? = null

        for ((langTag, allowedDetect) in attempts) {
            triedTags.add(langTag)
            val attemptResult = transcribeOnce(
                request = request,
                pcmOffset = pcmOffset,
                pcmLength = pcmLength,
                languageTag = langTag,
                allowedDetectionTags = allowedDetect
            )
            if (attemptResult.isSuccess) {
                return attemptResult
            }
            val err = attemptResult.exceptionOrNull()
            if (err is LanguagePackUnavailableException) {
                Log.w(TAG, "Language pack attempt failed for $langTag (code=${err.errorCode}); trying next candidate")
                lastLangError = err
                lastSupportCheckMs = 0L // Invalidate cache on pack error
                continue
            }
            return attemptResult
        }

        // All candidate language tags failed with ERROR_LANGUAGE_UNAVAILABLE / NOT_SUPPORTED.
        // Ensure a download has been triggered if we haven't tried yet.
        val targetDownloadTag = autoDownloadedTag
            ?: plan.primaryDownloadTag
            ?: OfflineSpeechLanguageResolver.defaultDownloadTag(request.languageMode, deviceLocaleTag)

        if (autoDownloadOutcome == null) {
            autoDownloadOutcome = downloadSingleLanguagePack(targetDownloadTag, waitTimeoutMs = 45_000L)
            if (autoDownloadOutcome is ModelDownloadOutcome.Succeeded) {
                inspectOfflineSpeechStatus(forceRefresh = true)
                val retryResult = transcribeOnce(
                    request = request,
                    pcmOffset = pcmOffset,
                    pcmLength = pcmLength,
                    languageTag = targetDownloadTag,
                    allowedDetectionTags = emptyList()
                )
                if (retryResult.isSuccess) return retryResult
            }
        }

        val friendlyMsg = when (autoDownloadOutcome) {
            is ModelDownloadOutcome.Scheduled ->
                "Offline speech pack ($targetDownloadTag) is downloading via Android Speech Services. Keep Wi-Fi on and tap Retry Failed in ~30s."
            is ModelDownloadOutcome.Failed ->
                "Offline speech pack unavailable (tried ${triedTags.distinct().joinToString(", ")}). Tap 'Download Speech Pack' on Wi-Fi or install offline voice data in Android Settings → System → Languages → On-device speech recognition."
            else ->
                lastLangError?.message
                    ?: "Offline speech pack unavailable (${triedTags.distinct().joinToString(", ")}). Tap 'Download Speech Pack' in Settings."
        }
        _packStatus.update { it.copy(lastMessage = friendlyMsg) }
        return Result.failure(IllegalStateException(friendlyMsg))
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun queryRecognitionSupportApi33(): RecognitionSupport? {
        val deferred = CompletableDeferred<RecognitionSupport?>()
        var recognizer: SpeechRecognizer? = null
        return try {
            withContext(Dispatchers.Main) {
                recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                val probeIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }
                recognizer!!.checkRecognitionSupport(
                    probeIntent,
                    context.mainExecutor,
                    object : RecognitionSupportCallback {
                        override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                            Log.d(
                                TAG,
                                "checkRecognitionSupport installed=${recognitionSupport.installedOnDeviceLanguages} " +
                                    "pending=${recognitionSupport.pendingOnDeviceLanguages} " +
                                    "supportedCount=${recognitionSupport.supportedOnDeviceLanguages.size}"
                            )
                            if (!deferred.isCompleted) deferred.complete(recognitionSupport)
                        }

                        override fun onError(error: Int) {
                            Log.w(TAG, "checkRecognitionSupport failed code=$error")
                            if (!deferred.isCompleted) deferred.complete(null)
                        }
                    }
                )
            }
            withTimeoutOrNull(8_000L) { deferred.await() }
        } catch (t: Throwable) {
            Log.w(TAG, "checkRecognitionSupport threw: ${t.message}")
            null
        } finally {
            withContext(Dispatchers.Main) {
                runCatching { recognizer?.destroy() }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun downloadSingleLanguagePack(
        languageTag: String,
        waitTimeoutMs: Long
    ): ModelDownloadOutcome = downloadMutex.withLock {
        _packStatus.update {
            it.copy(
                isDownloading = true,
                activeDownloadLanguage = languageTag,
                downloadProgressPercent = 0,
                lastMessage = "Downloading offline speech pack ($languageTag)..."
            )
        }

        var recognizer: SpeechRecognizer? = null
        return try {
            val downloadIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val deferred = CompletableDeferred<ModelDownloadOutcome>()
                withContext(Dispatchers.Main) {
                    recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    recognizer!!.triggerModelDownload(
                        downloadIntent,
                        context.mainExecutor,
                        object : ModelDownloadListener {
                            override fun onProgress(completedPercent: Int) {
                                val pct = completedPercent.coerceIn(0, 100)
                                Log.d(TAG, "triggerModelDownload($languageTag) progress=$pct%")
                                _packStatus.update {
                                    it.copy(
                                        isDownloading = true,
                                        activeDownloadLanguage = languageTag,
                                        downloadProgressPercent = pct,
                                        lastMessage = "Downloading offline speech pack ($languageTag): $pct%"
                                    )
                                }
                            }

                            override fun onSuccess() {
                                Log.i(TAG, "triggerModelDownload($languageTag) succeeded")
                                lastSupportCheckMs = 0L
                                if (!deferred.isCompleted) deferred.complete(ModelDownloadOutcome.Succeeded)
                            }

                            override fun onScheduled() {
                                Log.i(TAG, "triggerModelDownload($languageTag) scheduled in background")
                                lastSupportCheckMs = 0L
                                if (!deferred.isCompleted) deferred.complete(ModelDownloadOutcome.Scheduled)
                            }

                            override fun onError(error: Int) {
                                Log.w(TAG, "triggerModelDownload($languageTag) error=$error")
                                if (!deferred.isCompleted) {
                                    deferred.complete(ModelDownloadOutcome.Failed("code=$error"))
                                }
                            }
                        }
                    )
                }
                withTimeoutOrNull(waitTimeoutMs) { deferred.await() } ?: ModelDownloadOutcome.Scheduled
            } else {
                withContext(Dispatchers.Main) {
                    recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    recognizer!!.triggerModelDownload(downloadIntent)
                }
                lastSupportCheckMs = 0L
                ModelDownloadOutcome.Scheduled
            }
        } catch (t: Throwable) {
            Log.w(TAG, "triggerModelDownload($languageTag) threw: ${t.message}")
            ModelDownloadOutcome.Failed(t.message ?: "error")
        } finally {
            withContext(Dispatchers.Main) {
                runCatching { recognizer?.destroy() }
            }
            _packStatus.update {
                it.copy(
                    isDownloading = false,
                    activeDownloadLanguage = null,
                    downloadProgressPercent = null
                )
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun transcribeOnce(
        request: AsrRequest,
        pcmOffset: Int,
        pcmLength: Int,
        languageTag: String,
        allowedDetectionTags: List<String>
    ): Result<AsrResult> {
        val pipe = ParcelFileDescriptor.createPipe()
        val readFd = pipe[0]
        val writeFd = pipe[1]
        val completion = CompletableDeferred<Result<AsrResult>>()

        val segmentTexts = mutableListOf<String>()
        val segmentConfidences = mutableListOf<Float>()
        var latestPartialText = ""
        var latestDetectedLang: String? = null

        fun buildFinalSuccess(): Result<AsrResult> {
            val joined = if (segmentTexts.isNotEmpty()) {
                segmentTexts.joinToString(" ").trim()
            } else {
                latestPartialText.trim()
            }
            val avgConf = if (segmentConfidences.isNotEmpty()) {
                segmentConfidences.average().toFloat().coerceIn(0.1f, 1f)
            } else if (joined.isNotBlank()) {
                0.78f
            } else {
                0.0f
            }
            val lang = LanguageDetector.normalizeAndRefine(
                providerLanguage = latestDetectedLang ?: request.languageMode.isoHint ?: languageTag,
                transcriptText = joined
            )
            Log.d(
                TAG,
                "Finished seg=${request.segmentId} pack=$languageTag lang=$lang conf=$avgConf segments=${segmentTexts.size} text='${joined.take(120)}'"
            )
            return Result.success(
                AsrResult(
                    text = joined,
                    detectedLanguage = lang,
                    confidence = avgConf,
                    providerName = "Android On-Device ($languageTag)"
                )
            )
        }

        fun collectBundle(bundle: Bundle?, sourceLabel: String) {
            val matches = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val scores = bundle?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
            val best = matches?.firstOrNull()?.trim().orEmpty()
            // Google SODA emits -1.0f when confidence is unavailable; ignore non-positive sentinel values.
            val conf = scores?.firstOrNull()?.takeIf { it > 0f }?.coerceIn(0.05f, 1f)
            Log.d(TAG, "$sourceLabel seg=${request.segmentId} pack=$languageTag matches=${matches?.size ?: 0} conf=$conf best='${best.take(100)}'")
            if (best.isNotBlank()) {
                segmentTexts.add(best)
                if (conf != null) segmentConfidences.add(conf)
                latestPartialText = ""
            }
        }

        var recognizer: SpeechRecognizer? = null
        try {
            return coroutineScope {
                withContext(Dispatchers.Main) {
                    recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    recognizer!!.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) = Unit
                        override fun onBeginningOfSpeech() = Unit
                        override fun onRmsChanged(rmsdB: Float) = Unit
                        override fun onBufferReceived(buffer: ByteArray?) = Unit
                        override fun onEndOfSpeech() = Unit
                        override fun onEvent(eventType: Int, params: Bundle?) = Unit

                        override fun onPartialResults(partialResults: Bundle?) {
                            val partial = partialResults
                                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.firstOrNull()
                                ?.trim()
                                .orEmpty()
                            if (partial.isNotBlank()) {
                                latestPartialText = partial
                                Log.d(TAG, "onPartial seg=${request.segmentId}: '${partial.take(80)}'")
                            }
                        }

                        override fun onLanguageDetection(results: Bundle) {
                            latestDetectedLang = results.getString(SpeechRecognizer.DETECTED_LANGUAGE)
                            Log.d(TAG, "onLanguageDetection seg=${request.segmentId}: $latestDetectedLang")
                        }

                        override fun onSegmentResults(segmentResults: Bundle) {
                            collectBundle(segmentResults, "onSegmentResults")
                        }

                        override fun onEndOfSegmentedSession() {
                            Log.d(TAG, "onEndOfSegmentedSession seg=${request.segmentId} pack=$languageTag")
                            if (!completion.isCompleted) {
                                completion.complete(buildFinalSuccess())
                            }
                        }

                        override fun onError(error: Int) {
                            Log.w(TAG, "onError seg=${request.segmentId} pack=$languageTag code=$error collected=${segmentTexts.size} partial='${latestPartialText.take(60)}'")
                            if (completion.isCompleted) return
                            if (segmentTexts.isNotEmpty() || latestPartialText.isNotBlank()) {
                                completion.complete(buildFinalSuccess())
                                return
                            }
                            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                                completion.complete(buildFinalSuccess())
                                return
                            }
                            when (error) {
                                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
                                    completion.complete(
                                        Result.failure(
                                            LanguagePackUnavailableException(
                                                languageTag = languageTag,
                                                errorCode = error,
                                                message = "Offline language pack not installed ($languageTag)"
                                            )
                                        )
                                    )
                                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                                    completion.complete(
                                        Result.failure(
                                            LanguagePackUnavailableException(
                                                languageTag = languageTag,
                                                errorCode = error,
                                                message = "Offline language pack unavailable ($languageTag)"
                                            )
                                        )
                                    )
                                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                                SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
                                SpeechRecognizer.ERROR_CLIENT,
                                SpeechRecognizer.ERROR_AUDIO ->
                                    completion.complete(
                                        Result.failure(
                                            TransientAsrException(
                                                errorCode = error,
                                                message = describeSpeechError(error)
                                            )
                                        )
                                    )
                                else ->
                                    completion.complete(
                                        Result.failure(IllegalStateException(describeSpeechError(error)))
                                    )
                            }
                        }

                        override fun onResults(results: Bundle?) {
                            collectBundle(results, "onResults")
                            if (!completion.isCompleted) {
                                completion.complete(buildFinalSuccess())
                            }
                        }
                    })

                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readFd)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
                        putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                            allowedDetectionTags.size >= 2
                        ) {
                            putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                            putExtra(
                                RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES,
                                ArrayList(allowedDetectionTags)
                            )
                        }
                    }
                    recognizer!!.startListening(intent)
                }

                // Structured IO child job: prepends 250ms leading silence so Google SODA warms up before
                // first syllable, paces short clips gently to prevent cold-start NO_MATCH, and appends 1.2s tail.
                val writerJob = async(Dispatchers.IO) {
                    runCatching {
                        FileOutputStream(writeFd.fileDescriptor).use { out ->
                            val chunkBytes = 3_200 // 100ms at 16kHz 16-bit mono
                            val silence = ByteArray(chunkBytes)
                            val isShortClip = pcmLength < 128_000 // < 4.0s
                            val chunkDelayMs = if (isShortClip) 12L else 6L

                            // 300ms leading silence pad (prevents clipped first words & SODA warm-up drops)
                            repeat(3) {
                                out.write(silence)
                                delay(chunkDelayMs)
                            }
                            var pos = pcmOffset
                            val end = pcmOffset + pcmLength
                            while (pos < end) {
                                val len = minOf(chunkBytes, end - pos)
                                out.write(request.wavBytes, pos, len)
                                pos += len
                                delay(chunkDelayMs)
                            }
                            // 1.2s trailing silence flushes the on-device endpointer completely.
                            repeat(12) {
                                out.write(silence)
                                delay(chunkDelayMs)
                            }
                            out.flush()
                        }
                    }.onFailure { err ->
                        Log.w(TAG, "Pipe write ended early for seg=${request.segmentId}: ${err.message}")
                    }
                    runCatching { writeFd.close() }
                }

                val result = withTimeoutOrNull(45_000L) { completion.await() }
                    ?: if (segmentTexts.isNotEmpty() || latestPartialText.isNotBlank()) {
                        buildFinalSuccess()
                    } else {
                        Result.failure(TransientAsrException(-1, "On-device ASR timed out"))
                    }
                writerJob.cancel()
                result
            }
        } finally {
            runCatching { readFd.close() }
            runCatching { writeFd.close() }
            withContext(Dispatchers.Main) {
                runCatching { recognizer?.cancel() }
                runCatching { recognizer?.destroy() }
            }
            // Brief cooldown prevents Google SODA blockingReconnect stalls on back-to-back segments.
            delay(250L)
        }
    }

    private fun findWavDataOffset(wav: ByteArray): Int {
        if (wav.size < 44) return 0
        var pos = 12
        while (pos + 8 <= wav.size) {
            val tag = String(wav, pos, 4, Charsets.US_ASCII)
            val size = (wav[pos + 4].toInt() and 0xFF) or
                ((wav[pos + 5].toInt() and 0xFF) shl 8) or
                ((wav[pos + 6].toInt() and 0xFF) shl 16) or
                ((wav[pos + 7].toInt() and 0xFF) shl 24)
            if (tag == "data") return pos + 8
            pos += 8 + size + (size and 1)
        }
        return 44
    }

    private fun describeSpeechError(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "On-device ASR audio stream error (code=$code)"
        SpeechRecognizer.ERROR_CLIENT -> "On-device ASR client interrupted (code=$code)"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Speech service missing permission (code=$code)"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech service attempted network unexpectedly (code=$code)"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "On-device speech recognizer busy (code=$code)"
        SpeechRecognizer.ERROR_SERVER -> "On-device speech service internal error (code=$code)"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Too many rapid speech recognition requests (code=$code)"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "On-device speech service disconnected (code=$code)"
        else -> "On-device ASR error code=$code"
    }

    private class LanguagePackUnavailableException(
        val languageTag: String,
        val errorCode: Int,
        message: String
    ) : IllegalStateException(message)

    class TransientAsrException(
        val errorCode: Int,
        message: String
    ) : IllegalStateException(message)

    private sealed interface ModelDownloadOutcome {
        data object Succeeded : ModelDownloadOutcome
        data object Scheduled : ModelDownloadOutcome
        data class Failed(val reason: String) : ModelDownloadOutcome
    }

    companion object {
        private const val TAG = "AndroidOnDeviceAsr"
        private const val SUPPORT_CACHE_TTL_MS = 30_000L
    }
}
