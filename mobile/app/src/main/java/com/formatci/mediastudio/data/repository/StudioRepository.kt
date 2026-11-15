package com.formatci.mediastudio.data.repository

import android.content.Context
import android.net.Uri
import android.os.Build
import com.formatci.mediastudio.core.error.StudioErrorCode
import com.formatci.mediastudio.core.error.StudioErrorMapper
import com.formatci.mediastudio.core.error.StudioException
import com.formatci.mediastudio.core.files.FileStorageHelper
import com.formatci.mediastudio.data.database.StudioDatabase
import com.formatci.mediastudio.data.preferences.UserPreferencesRepository
import com.formatci.mediastudio.domain.model.AppSettings
import com.formatci.mediastudio.domain.model.EngineCapabilities
import com.formatci.mediastudio.domain.model.JobEstimate
import com.formatci.mediastudio.domain.model.JobStatus
import com.formatci.mediastudio.domain.model.MediaFormatUtils
import com.formatci.mediastudio.domain.model.MediaInfo
import com.formatci.mediastudio.domain.model.MediaInput
import com.formatci.mediastudio.domain.model.MediaJob
import com.formatci.mediastudio.domain.model.MediaJobEvent
import com.formatci.mediastudio.domain.model.MediaJobSettings
import com.formatci.mediastudio.domain.model.MediaType
import com.formatci.mediastudio.domain.model.OutputRecord
import com.formatci.mediastudio.domain.model.StudioTool
import com.formatci.mediastudio.domain.model.TimelineClipItem
import com.formatci.mediastudio.domain.model.TimelineProject
import com.formatci.mediastudio.media.engine.MediaEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class StudioRepository(
    private val context: Context,
    private val engine: MediaEngine,
    private val db: StudioDatabase,
    private val fileHelper: FileStorageHelper,
    private val prefs: UserPreferencesRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateMutex = Mutex()
    private val queueMutex = Mutex()
    private val timelineLock = Any()
    private val initialLoadComplete = CompletableDeferred<Unit>()

    private val _jobsFlow = MutableStateFlow<List<MediaJob>>(emptyList())
    val jobsFlow: StateFlow<List<MediaJob>> = _jobsFlow.asStateFlow()

    private val _historyFlow = MutableStateFlow<List<OutputRecord>>(emptyList())
    val historyFlow: StateFlow<List<OutputRecord>> = _historyFlow.asStateFlow()

    private val _recentFilesFlow = MutableStateFlow<List<MediaInput>>(emptyList())
    val recentFilesFlow: StateFlow<List<MediaInput>> = _recentFilesFlow.asStateFlow()

    private val _activeTimelineFlow = MutableStateFlow(TimelineProject())
    val activeTimelineFlow: StateFlow<TimelineProject> = _activeTimelineFlow.asStateFlow()

    private val _canUndoFlow = MutableStateFlow(false)
    val canUndoFlow: StateFlow<Boolean> = _canUndoFlow.asStateFlow()

    private val _canRedoFlow = MutableStateFlow(false)
    val canRedoFlow: StateFlow<Boolean> = _canRedoFlow.asStateFlow()

    val settingsFlow: StateFlow<AppSettings> = prefs.settingsFlow.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = AppSettings()
    )

    private val undoStack = ArrayDeque<TimelineProject>()
    private val redoStack = ArrayDeque<TimelineProject>()
    private val maxUndoDepth = 30

    private val diagnosticEvents = ArrayDeque<String>()
    private val activeCancelledIds = ConcurrentHashMap.newKeySet<String>()
    @Volatile
    private var activeRunnerJob: Job? = null
    @Volatile
    private var currentExecutingJobId: String? = null
    @Volatile
    private var lastPersistedProgress = -1f

    var onProcessingStateChanged: ((activeJob: MediaJob?, activeOrQueuedCount: Int) -> Unit)? = null

    init {
        scope.launch {
            try {
                val recoveredJobs = db.recoverInterruptedJobs()
                val outputs = db.getAllOutputs()
                val recent = db.getRecentFiles()
                val savedProject = db.loadProject()

                _jobsFlow.value = sortJobsForDisplay(recoveredJobs)
                _historyFlow.value = outputs
                _recentFilesFlow.value = recent
                if (savedProject != null) {
                    _activeTimelineFlow.value = savedProject
                }
                recordDiagnostic("StudioRepository başlatıldı. Kurtarılan iş sayısı: ${recoveredJobs.size}")
            } catch (t: Throwable) {
                recordDiagnostic("Başlangıç yükleme hatası: ${t.message}")
            } finally {
                initialLoadComplete.complete(Unit)
            }

            if (_jobsFlow.value.any { it.status == JobStatus.QUEUED }) {
                triggerQueueProcessing()
            }
        }
    }

    suspend fun importMedia(uri: Uri): Result<MediaInfo> = withContext(Dispatchers.IO) {
        initialLoadComplete.await()
        runCatching {
            fileHelper.takePersistablePermissionSafely(uri)
            val resolved = fileHelper.resolveMediaInput(uri)
            if (!fileHelper.canReadUri(uri)) {
                throw StudioException(StudioErrorCode.URI_PERMISSION_LOST, uri.toString())
            }
            val info = engine.inspect(resolved)
            val enrichedInput = resolved.copy(
                mediaType = if (info.mediaType != MediaType.UNKNOWN) info.mediaType else resolved.mediaType,
                mimeType = info.detectedMimeType.ifBlank { resolved.mimeType },
                sizeBytes = if (info.sizeBytes > 0L) info.sizeBytes else resolved.sizeBytes
            )
            db.upsertRecentFile(enrichedInput)
            _recentFilesFlow.value = db.getRecentFiles()
            recordDiagnostic("Medya içe aktarıldı: ${enrichedInput.displayName} (${enrichedInput.mediaType.name})")
            info.copy(input = enrichedInput)
        }.onFailure { err ->
            val mapped = StudioErrorMapper.fromThrowable(err)
            recordDiagnostic("Medya içe aktarma hatası [${mapped.code}]: ${mapped.technicalDetail ?: mapped.messageTr}")
        }
    }

    suspend fun importMultipleMedia(uris: List<Uri>): List<Result<MediaInfo>> {
        return uris.map { importMedia(it) }
    }

    suspend fun inspectInput(input: MediaInput): Result<MediaInfo> = withContext(Dispatchers.IO) {
        runCatching { engine.inspect(input) }
    }

    suspend fun extractFrameToFile(uriString: String, positionMs: Long): Result<String> {
        return engine.extractFrameToFile(uriString, positionMs)
    }

    suspend fun enqueueJob(
        tool: StudioTool,
        title: String,
        inputs: List<MediaInput>,
        settings: MediaJobSettings = MediaJobSettings(),
        timelineClips: List<TimelineClipItem> = emptyList()
    ): MediaJob {
        val job = MediaJob(
            id = UUID.randomUUID().toString(),
            tool = tool,
            title = title.ifBlank { tool.titleTr },
            inputs = inputs,
            settings = settings,
            timelineClips = timelineClips,
            status = JobStatus.QUEUED,
            statusMessage = "Kuyrukta bekliyor"
        )
        return enqueueJob(job)
    }

    suspend fun enqueueJob(job: MediaJob): MediaJob {
        initialLoadComplete.await()
        val prepared = stateMutex.withLock {
            val nextOrder = (_jobsFlow.value.maxOfOrNull { it.queueOrder } ?: 0) + 1
            val queued = job.copy(
                status = JobStatus.QUEUED,
                progress = 0f,
                isIndeterminate = false,
                statusMessage = "Kuyrukta bekliyor",
                queueOrder = nextOrder,
                errorCode = null,
                errorMessage = null,
                errorSuggestion = null,
                wasInterrupted = false
            )
            withContext(Dispatchers.IO) { db.upsertJob(queued) }
            _jobsFlow.value = sortJobsForDisplay(_jobsFlow.value + queued)
            queued
        }
        recordDiagnostic("İş kuyruğa eklendi: ${prepared.title} [${prepared.tool.name}]")
        notifyProcessingObserver()
        triggerQueueProcessing()
        return prepared
    }

    suspend fun enqueueBatch(
        tool: StudioTool,
        inputs: List<MediaInput>,
        settings: MediaJobSettings = MediaJobSettings()
    ): List<MediaJob> {
        initialLoadComplete.await()
        if (inputs.isEmpty()) return emptyList()

        val created = stateMutex.withLock {
            var order = (_jobsFlow.value.maxOfOrNull { it.queueOrder } ?: 0) + 1
            val batchJobs = inputs.map { input ->
                val resolvedTool = if (tool == StudioTool.BATCH_CONVERT) {
                    when (input.mediaType) {
                        MediaType.VIDEO -> StudioTool.VIDEO_CONVERT
                        MediaType.AUDIO -> StudioTool.AUDIO_CONVERT
                        MediaType.IMAGE -> StudioTool.IMAGE_CONVERT
                        MediaType.UNKNOWN -> StudioTool.BATCH_CONVERT
                    }
                } else {
                    tool
                }
                val perInputSettings = if (settings.outputFileName.isBlank()) {
                    settings.copy(outputFileName = MediaFormatUtils.sanitizeBaseName(input.displayName))
                } else {
                    settings
                }
                MediaJob(
                    id = UUID.randomUUID().toString(),
                    tool = resolvedTool,
                    title = "${resolvedTool.titleTr}: ${input.displayName}",
                    inputs = listOf(input),
                    settings = perInputSettings,
                    status = JobStatus.QUEUED,
                    statusMessage = "Toplu kuyrukta bekliyor",
                    queueOrder = order++
                )
            }
            withContext(Dispatchers.IO) { db.upsertJobs(batchJobs) }
            _jobsFlow.value = sortJobsForDisplay(_jobsFlow.value + batchJobs)
            batchJobs
        }
        recordDiagnostic("Toplu iş eklendi: ${created.size} dosya [${tool.name}]")
        notifyProcessingObserver()
        triggerQueueProcessing()
        return created
    }

    suspend fun moveJobOrder(jobId: String, moveUp: Boolean): Boolean {
        initialLoadComplete.await()
        val changed = stateMutex.withLock {
            val current = _jobsFlow.value.toMutableList()
            val queued = current.filter { it.status == JobStatus.QUEUED }.sortedBy { it.queueOrder }
            val index = queued.indexOfFirst { it.id == jobId }
            if (index < 0) return@withLock false

            val swapIndex = if (moveUp) index - 1 else index + 1
            if (swapIndex !in queued.indices) return@withLock false

            val first = queued[index]
            val second = queued[swapIndex]
            val updatedFirst = first.copy(queueOrder = second.queueOrder)
            val updatedSecond = second.copy(queueOrder = first.queueOrder)

            val replaced = current.map { item ->
                when (item.id) {
                    updatedFirst.id -> updatedFirst
                    updatedSecond.id -> updatedSecond
                    else -> item
                }
            }
            withContext(Dispatchers.IO) {
                db.upsertJobs(listOf(updatedFirst, updatedSecond))
            }
            _jobsFlow.value = sortJobsForDisplay(replaced)
            true
        }
        return changed
    }

    suspend fun cancelJob(jobId: String) {
        initialLoadComplete.await()
        activeCancelledIds.add(jobId)
        val target = _jobsFlow.value.firstOrNull { it.id == jobId } ?: return

        if (target.status == JobStatus.QUEUED) {
            updateJobState(
                jobId = jobId,
                persist = true
            ) {
                it.copy(
                    status = JobStatus.CANCELLED,
                    isIndeterminate = false,
                    statusMessage = "Kullanıcı tarafından iptal edildi",
                    finishedAtMs = System.currentTimeMillis()
                )
            }
            recordDiagnostic("Bekleyen iş iptal edildi: ${target.title}")
            notifyProcessingObserver()
            return
        }

        if (target.status == JobStatus.PROCESSING || target.status == JobStatus.PREPARING) {
            updateJobState(jobId = jobId, persist = false) {
                it.copy(statusMessage = "İptal ediliyor...")
            }
            runCatching { engine.cancel(jobId) }
            recordDiagnostic("Çalışan iş için iptal istendi: ${target.title}")
        }
    }

    suspend fun retryJob(jobId: String) {
        initialLoadComplete.await()
        val retried = stateMutex.withLock {
            val existing = _jobsFlow.value.firstOrNull { it.id == jobId } ?: return@withLock null
            if (!existing.status.isTerminal) return@withLock null
            activeCancelledIds.remove(jobId)
            val nextOrder = (_jobsFlow.value.maxOfOrNull { it.queueOrder } ?: 0) + 1
            val updated = existing.copy(
                status = JobStatus.QUEUED,
                progress = 0f,
                isIndeterminate = false,
                statusMessage = "Yeniden kuyruğa alındı",
                queueOrder = nextOrder,
                startedAtMs = null,
                finishedAtMs = null,
                errorCode = null,
                errorMessage = null,
                errorSuggestion = null,
                wasInterrupted = false
            )
            withContext(Dispatchers.IO) { db.upsertJob(updated) }
            _jobsFlow.value = sortJobsForDisplay(
                _jobsFlow.value.map { if (it.id == jobId) updated else it }
            )
            updated
        }
        if (retried != null) {
            recordDiagnostic("İş yeniden kuyruğa alındı: ${retried.title}")
            notifyProcessingObserver()
            triggerQueueProcessing()
        }
    }

    suspend fun clearFinishedJobs() {
        initialLoadComplete.await()
        stateMutex.withLock {
            withContext(Dispatchers.IO) { db.clearFinishedJobs() }
            _jobsFlow.value = _jobsFlow.value.filter { !it.status.isTerminal }
        }
    }

    suspend fun deleteHistoryRecord(recordId: String, deleteFile: Boolean = true): Boolean {
        initialLoadComplete.await()
        val record = _historyFlow.value.firstOrNull { it.id == recordId } ?: return false
        if (deleteFile) {
            fileHelper.deleteOutputByUri(record.uriString)
        }
        withContext(Dispatchers.IO) { db.deleteOutput(recordId) }
        _historyFlow.value = _historyFlow.value.filterNot { it.id == recordId }
        recordDiagnostic("Çıktı kaydı silindi: ${record.displayName} (dosya silindi=$deleteFile)")
        return true
    }

    suspend fun renameHistoryRecord(recordId: String, newName: String): Result<OutputRecord> {
        initialLoadComplete.await()
        val record = _historyFlow.value.firstOrNull { it.id == recordId }
            ?: return Result.failure(StudioException(StudioErrorCode.FILE_NOT_FOUND))

        val renameResult = fileHelper.renameOutput(record.uriString, newName)
        return renameResult.map { saved ->
            val updated = record.copy(
                displayName = saved.displayName,
                uriString = saved.uriString,
                sizeBytes = if (saved.sizeBytes > 0L) saved.sizeBytes else record.sizeBytes
            )
            withContext(Dispatchers.IO) {
                db.updateOutputName(record.id, updated.displayName, updated.uriString)
            }
            _historyFlow.value = _historyFlow.value.map { if (it.id == recordId) updated else it }
            recordDiagnostic("Çıktı yeniden adlandırıldı: ${record.displayName} -> ${updated.displayName}")
            updated
        }
    }

    fun updateTimeline(project: TimelineProject, recordUndo: Boolean = true) {
        val persisted: TimelineProject
        synchronized(timelineLock) {
            val current = _activeTimelineFlow.value
            if (current == project) return
            if (recordUndo) {
                undoStack.addLast(current)
                while (undoStack.size > maxUndoDepth) {
                    undoStack.removeFirst()
                }
                redoStack.clear()
            }
            persisted = project.copy(updatedAtMs = System.currentTimeMillis())
            _activeTimelineFlow.value = persisted
            _canUndoFlow.value = undoStack.isNotEmpty()
            _canRedoFlow.value = redoStack.isNotEmpty()
        }
        scope.launch { db.saveProject(persisted) }
    }

    fun updateTimeline(recordUndo: Boolean = true, transform: (TimelineProject) -> TimelineProject) {
        val persisted: TimelineProject
        synchronized(timelineLock) {
            val current = _activeTimelineFlow.value
            val next = transform(current)
            if (next == current) return
            if (recordUndo) {
                undoStack.addLast(current)
                while (undoStack.size > maxUndoDepth) {
                    undoStack.removeFirst()
                }
                redoStack.clear()
            }
            persisted = next.copy(updatedAtMs = System.currentTimeMillis())
            _activeTimelineFlow.value = persisted
            _canUndoFlow.value = undoStack.isNotEmpty()
            _canRedoFlow.value = redoStack.isNotEmpty()
        }
        scope.launch { db.saveProject(persisted) }
    }

    fun undoTimeline(): Boolean {
        val restored: TimelineProject
        synchronized(timelineLock) {
            if (undoStack.isEmpty()) return false
            val current = _activeTimelineFlow.value
            redoStack.addLast(current)
            restored = undoStack.removeLast()
            _activeTimelineFlow.value = restored
            _canUndoFlow.value = undoStack.isNotEmpty()
            _canRedoFlow.value = redoStack.isNotEmpty()
        }
        scope.launch { db.saveProject(restored) }
        return true
    }

    fun redoTimeline(): Boolean {
        val restored: TimelineProject
        synchronized(timelineLock) {
            if (redoStack.isEmpty()) return false
            val current = _activeTimelineFlow.value
            undoStack.addLast(current)
            restored = redoStack.removeLast()
            _activeTimelineFlow.value = restored
            _canUndoFlow.value = undoStack.isNotEmpty()
            _canRedoFlow.value = redoStack.isNotEmpty()
        }
        scope.launch { db.saveProject(restored) }
        return true
    }

    suspend fun estimateJob(job: MediaJob): JobEstimate = withContext(Dispatchers.IO) {
        runCatching { engine.estimate(job) }.getOrElse {
            val inputSum = job.inputs.sumOf { it.sizeBytes }.coerceAtLeast(32_768L)
            val estBytes = MediaFormatUtils.estimateOutputBytes(
                tool = job.tool,
                durationMs = 10_000L,
                inputSizeBytes = inputSum,
                settings = job.settings
            )
            JobEstimate(
                estimatedOutputBytes = estBytes,
                estimatedDurationMs = 10_000L,
                estimatedProcessingTimeMs = 5_000L,
                requiredFreeSpaceBytes = estBytes * 2,
                hasEnoughStorage = fileHelper.hasEnoughStorage(estBytes * 2)
            )
        }
    }

    fun capabilities(): EngineCapabilities = engine.capabilities()

    suspend fun clearTempCache(keepThumbnails: Boolean = false): Long = withContext(Dispatchers.IO) {
        val freed = fileHelper.clearTempCache(keepThumbnails)
        recordDiagnostic("Geçici önbellek temizlendi: ${MediaFormatUtils.formatBytes(freed)}")
        freed
    }

    fun getCacheSizeBytes(): Long = fileHelper.getCacheSizeBytes()

    suspend fun exportDiagnosticLog(): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val caps = capabilities()
            val jobsSnapshot = _jobsFlow.value
            val availBytes = fileHelper.getAvailableStorageBytes()
            val cacheBytes = fileHelper.getCacheSizeBytes()

            val report = buildString {
                appendLine("=== FORMATÇI Medya Stüdyosu — Çevrimdışı Hata ve Tanı Raporu ===")
                appendLine("Oluşturulma: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())}")
                appendLine("Cihaz: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})")
                appendLine("Kullanılabilir Depolama: ${MediaFormatUtils.formatBytes(availBytes)}")
                appendLine("Önbellek Boyutu: ${MediaFormatUtils.formatBytes(cacheBytes)}")
                appendLine("Aktif Yürütülen İş ID: ${currentExecutingJobId ?: "-"}")
                appendLine()
                appendLine("--- Motor Yetenekleri ---")
                appendLine("Özet: ${caps.deviceSummaryTr}")
                appendLine("Video Çıktıları: ${caps.supportedVideoOutputFormats.joinToString()}")
                appendLine("Video Kodekleri: ${caps.supportedVideoCodecs.joinToString()}")
                appendLine("Ses Çıktıları: ${caps.supportedAudioOutputFormats.joinToString()}")
                appendLine("Resim Çıktıları: ${caps.supportedImageOutputFormats.joinToString()}")
                appendLine("Crossfade Desteği: ${caps.supportsCrossfade} | Ters Oynatma: ${caps.supportsReversePlayback}")
                caps.codecDetails.forEach { codec ->
                    appendLine(
                        "  * ${codec.displayName} (${codec.mimeType}): enc=${codec.isEncoderAvailable}, " +
                            "dec=${codec.isDecoderAvailable}, hw=${codec.isHardwareAccelerated}, codec=${codec.codecName ?: "-"}"
                    )
                }
                appendLine()
                appendLine("--- Kuyruk Özeti (Son 20 İş) ---")
                jobsSnapshot.take(20).forEach { job ->
                    appendLine(
                        "  [${job.status.name}] ${job.tool.name} | ${job.title} | " +
                            "ilerleme=${(job.progress * 100).toInt()}% | hata=${job.errorCode ?: "-"} ${job.errorMessage ?: ""}"
                    )
                }
                appendLine()
                appendLine("--- Olay Günlüğü ---")
                synchronized(diagnosticEvents) {
                    diagnosticEvents.forEach { appendLine("  $it") }
                }
            }
            fileHelper.writeDiagnosticReportFile(report)
        }
    }

    private fun triggerQueueProcessing() {
        val currentRunner = activeRunnerJob
        if (currentRunner?.isActive == true) return

        activeRunnerJob = scope.launch {
            queueMutex.withLock {
                while (true) {
                    val nextJob = stateMutex.withLock {
                        _jobsFlow.value
                            .filter { it.status == JobStatus.QUEUED }
                            .minByOrNull { it.queueOrder }
                    } ?: break

                    if (activeCancelledIds.remove(nextJob.id)) {
                        updateJobState(nextJob.id, persist = true) {
                            it.copy(
                                status = JobStatus.CANCELLED,
                                statusMessage = "Kullanıcı tarafından iptal edildi",
                                finishedAtMs = System.currentTimeMillis()
                            )
                        }
                        continue
                    }

                    executeSingleJob(nextJob)
                }
            }
            notifyProcessingObserver()
        }
    }

    private suspend fun executeSingleJob(job: MediaJob) {
        currentExecutingJobId = job.id
        lastPersistedProgress = -1f

        val now = System.currentTimeMillis()
        updateJobState(job.id, persist = true) {
            it.copy(
                status = JobStatus.PREPARING,
                progress = 0f,
                isIndeterminate = true,
                statusMessage = "Medya ve depolama kontrol ediliyor...",
                startedAtMs = now
            )
        }
        notifyProcessingObserver()

        try {
            engine.execute(job).collect { event ->
                when (event) {
                    is MediaJobEvent.Started -> {
                        updateJobState(job.id, persist = true) {
                            it.copy(
                                status = JobStatus.PROCESSING,
                                statusMessage = event.message,
                                isIndeterminate = false
                            )
                        }
                        notifyProcessingObserver()
                    }

                    is MediaJobEvent.Progress -> {
                        val clamped = event.progress.coerceIn(0f, 0.999f)
                        val shouldPersist = kotlin.math.abs(clamped - lastPersistedProgress) >= 0.08f
                        if (shouldPersist) {
                            lastPersistedProgress = clamped
                        }
                        updateJobState(job.id, persist = shouldPersist) {
                            it.copy(
                                status = JobStatus.PROCESSING,
                                progress = clamped,
                                isIndeterminate = event.isIndeterminate,
                                statusMessage = event.message
                            )
                        }
                        notifyProcessingObserver()
                    }

                    is MediaJobEvent.Completed -> {
                        val record = OutputRecord(
                            id = UUID.randomUUID().toString(),
                            jobId = job.id,
                            tool = job.tool,
                            mediaType = event.mediaType,
                            displayName = event.outputDisplayName,
                            format = event.outputFormat,
                            mimeType = event.outputMimeType,
                            uriString = event.outputUriString,
                            sizeBytes = event.outputSizeBytes,
                            durationMs = event.durationMs,
                            width = event.width,
                            height = event.height,
                            createdAtMs = System.currentTimeMillis()
                        )
                        db.insertOutput(record)
                        _historyFlow.value = db.getAllOutputs()

                        updateJobState(job.id, persist = true) {
                            it.copy(
                                status = JobStatus.COMPLETED,
                                progress = 1f,
                                isIndeterminate = false,
                                statusMessage = "Tamamlandı: ${event.outputDisplayName}",
                                finishedAtMs = System.currentTimeMillis(),
                                outputRecordId = record.id,
                                outputUriString = event.outputUriString,
                                outputDisplayName = event.outputDisplayName,
                                outputSizeBytes = event.outputSizeBytes
                            )
                        }
                        recordDiagnostic("İş tamamlandı: ${job.title} -> ${event.outputDisplayName}")

                        if (settingsFlow.value.autoCleanCache) {
                            fileHelper.clearTempCache(keepThumbnails = true)
                        }
                    }

                    is MediaJobEvent.Failed -> {
                        updateJobState(job.id, persist = true) {
                            it.copy(
                                status = JobStatus.FAILED,
                                isIndeterminate = false,
                                statusMessage = event.message,
                                finishedAtMs = System.currentTimeMillis(),
                                errorCode = event.errorCode,
                                errorMessage = event.message,
                                errorSuggestion = event.suggestion
                            )
                        }
                        recordDiagnostic("İş başarısız [${event.errorCode}]: ${job.title} - ${event.message}")
                    }

                    is MediaJobEvent.Cancelled -> {
                        updateJobState(job.id, persist = true) {
                            it.copy(
                                status = JobStatus.CANCELLED,
                                isIndeterminate = false,
                                statusMessage = event.message,
                                finishedAtMs = System.currentTimeMillis()
                            )
                        }
                        recordDiagnostic("İş iptal edildi: ${job.title}")
                    }
                }
            }
        } catch (t: Throwable) {
            val mapped = StudioErrorMapper.fromThrowable(t)
            val cancelled = mapped.code == StudioErrorCode.CANCELLED || activeCancelledIds.remove(job.id)
            updateJobState(job.id, persist = true) {
                if (cancelled) {
                    it.copy(
                        status = JobStatus.CANCELLED,
                        isIndeterminate = false,
                        statusMessage = "İşlem iptal edildi",
                        finishedAtMs = System.currentTimeMillis()
                    )
                } else {
                    it.copy(
                        status = JobStatus.FAILED,
                        isIndeterminate = false,
                        statusMessage = mapped.messageTr,
                        finishedAtMs = System.currentTimeMillis(),
                        errorCode = mapped.code.name,
                        errorMessage = mapped.messageTr,
                        errorSuggestion = mapped.suggestionTr
                    )
                }
            }
            recordDiagnostic("İş yürütme istisnası [${mapped.code}]: ${mapped.technicalDetail ?: mapped.messageTr}")
        } finally {
            activeCancelledIds.remove(job.id)
            currentExecutingJobId = null
            notifyProcessingObserver()
        }
    }

    private suspend fun updateJobState(
        jobId: String,
        persist: Boolean,
        transform: (MediaJob) -> MediaJob
    ) {
        var updatedJob: MediaJob? = null
        stateMutex.withLock {
            val updatedList = _jobsFlow.value.map { item ->
                if (item.id == jobId) {
                    transform(item).also { updatedJob = it }
                } else {
                    item
                }
            }
            _jobsFlow.value = sortJobsForDisplay(updatedList)
        }
        val toSave = updatedJob
        if (persist && toSave != null) {
            withContext(Dispatchers.IO) { db.upsertJob(toSave) }
        }
    }

    private fun sortJobsForDisplay(list: List<MediaJob>): List<MediaJob> {
        return list.sortedWith(
            compareBy<MediaJob>(
                {
                    when (it.status) {
                        JobStatus.PROCESSING, JobStatus.PREPARING -> 0
                        JobStatus.QUEUED -> 1
                        JobStatus.FAILED, JobStatus.CANCELLED, JobStatus.COMPLETED -> 2
                    }
                },
                { if (it.status == JobStatus.QUEUED) it.queueOrder else 0 },
                { if (it.status.isTerminal) -(it.finishedAtMs ?: it.createdAtMs) else it.createdAtMs }
            )
        )
    }

    private fun notifyProcessingObserver() {
        val snapshot = _jobsFlow.value
        val active = snapshot.firstOrNull {
            it.status == JobStatus.PROCESSING || it.status == JobStatus.PREPARING
        }
        val activeCount = snapshot.count { it.status.isActive }
        onProcessingStateChanged?.invoke(active, activeCount)
    }

    private fun recordDiagnostic(message: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
        synchronized(diagnosticEvents) {
            diagnosticEvents.addLast("[$ts] $message")
            while (diagnosticEvents.size > 200) {
                diagnosticEvents.removeFirst()
            }
        }
    }
}
