package com.formatci.mediastudio.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.formatci.mediastudio.core.error.StudioErrorMapper
import com.formatci.mediastudio.core.files.FileStorageHelper
import com.formatci.mediastudio.data.preferences.UserPreferencesRepository
import com.formatci.mediastudio.data.repository.StudioRepository
import com.formatci.mediastudio.domain.model.AppSettings
import com.formatci.mediastudio.domain.model.AspectRatioOption
import com.formatci.mediastudio.domain.model.AudioFitMode
import com.formatci.mediastudio.domain.model.EngineCapabilities
import com.formatci.mediastudio.domain.model.JobEstimate
import com.formatci.mediastudio.domain.model.MediaFormatUtils
import com.formatci.mediastudio.domain.model.MediaInfo
import com.formatci.mediastudio.domain.model.MediaInput
import com.formatci.mediastudio.domain.model.MediaJob
import com.formatci.mediastudio.domain.model.MediaJobSettings
import com.formatci.mediastudio.domain.model.MediaType
import com.formatci.mediastudio.domain.model.OutputRecord
import com.formatci.mediastudio.domain.model.QualityProfile
import com.formatci.mediastudio.domain.model.StudioTool
import com.formatci.mediastudio.domain.model.ThemePreference
import com.formatci.mediastudio.domain.model.TimelineClipItem
import com.formatci.mediastudio.domain.model.TimelineProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

enum class StudioDestination(val labelTr: String) {
    HOME("Ana Sayfa"),
    STUDIO("Stüdyo"),
    QUEUE("Kuyruk"),
    HISTORY("Çıktılarım"),
    SETTINGS("Ayarlar")
}

enum class StudioWorkspaceTab(val labelTr: String) {
    PICKER("Medya & Ayrıntı"),
    VIDEO("Video Stüdyosu"),
    AUDIO("Ses Stüdyosu"),
    IMAGE("Resim & Slayt")
}

enum class HistorySortOrder(val labelTr: String) {
    NEWEST("En Yeni"),
    OLDEST("En Eski"),
    LARGEST("En Büyük"),
    NAME_ASC("Ada Göre (A-Z)")
}

enum class PickerTargetRole {
    GENERAL_IMPORT,
    VIDEO_CLIPS,
    VIDEO_EXTERNAL_AUDIO,
    AUDIO_TRACKS,
    IMAGE_FILES,
    SLIDESHOW_AUDIO
}

sealed class StudioUiEvent {
    data class ShowMessage(
        val message: String,
        val actionLabel: String? = null,
        val targetDestination: StudioDestination? = null
    ) : StudioUiEvent()

    data class OpenOutput(val uri: Uri, val mimeType: String) : StudioUiEvent()
    data class ShareOutput(val uri: Uri, val mimeType: String, val title: String) : StudioUiEvent()
    data class RequestSaveAs(
        val suggestedName: String,
        val mimeType: String,
        val sourceUriString: String
    ) : StudioUiEvent()
}

data class StudioUiState(
    val activeDestination: StudioDestination = StudioDestination.HOME,
    val activeStudioTab: StudioWorkspaceTab = StudioWorkspaceTab.PICKER,
    val activeTool: StudioTool = StudioTool.VIDEO_CONVERT,
    val importedMedia: List<MediaInfo> = emptyList(),
    val selectedMediaIds: Set<String> = emptySet(),
    val focusedMediaId: String? = null,
    val isImporting: Boolean = false,
    val extractedFramePath: String? = null,
    val extractedFrameSourceName: String? = null,
    val selectedTimelineClipId: String? = null,
    val videoEstimate: JobEstimate? = null,
    val audioInputs: List<MediaInfo> = emptyList(),
    val focusedAudioId: String? = null,
    val audioSettings: MediaJobSettings = MediaJobSettings(outputFormat = "m4a"),
    val audioEstimate: JobEstimate? = null,
    val imageInputs: List<MediaInfo> = emptyList(),
    val focusedImageId: String? = null,
    val imageSettings: MediaJobSettings = MediaJobSettings(outputFormat = "jpg", imageQuality = 85),
    val slideshowMode: Boolean = false,
    val slideshowBackgroundAudio: MediaInfo? = null,
    val imageEstimate: JobEstimate? = null,
    val historyFilter: MediaType? = null,
    val historySortOrder: HistorySortOrder = HistorySortOrder.NEWEST,
    val historySearchQuery: String = "",
    val cacheSizeBytes: Long = 0L,
    val availableStorageBytes: Long = 0L
) {
    val focusedMedia: MediaInfo?
        get() = importedMedia.firstOrNull { it.input.id == focusedMediaId } ?: importedMedia.firstOrNull()

    val selectedMediaList: List<MediaInfo>
        get() = importedMedia.filter { it.input.id in selectedMediaIds }

    val focusedAudio: MediaInfo?
        get() = audioInputs.firstOrNull { it.input.id == focusedAudioId } ?: audioInputs.firstOrNull()

    val focusedImage: MediaInfo?
        get() = imageInputs.firstOrNull { it.input.id == focusedImageId } ?: imageInputs.firstOrNull()
}

class StudioViewModel(
    private val appContext: Context,
    private val repository: StudioRepository,
    private val fileHelper: FileStorageHelper,
    private val prefs: UserPreferencesRepository
) : ViewModel() {

    val jobsFlow: StateFlow<List<MediaJob>> = repository.jobsFlow
    val historyFlow: StateFlow<List<OutputRecord>> = repository.historyFlow
    val recentFilesFlow: StateFlow<List<MediaInput>> = repository.recentFilesFlow
    val activeTimelineFlow: StateFlow<TimelineProject> = repository.activeTimelineFlow
    val canUndoFlow: StateFlow<Boolean> = repository.canUndoFlow
    val canRedoFlow: StateFlow<Boolean> = repository.canRedoFlow
    val settingsFlow: StateFlow<AppSettings> = repository.settingsFlow

    val capabilities: EngineCapabilities by lazy { repository.capabilities() }

    private val _uiState = MutableStateFlow(StudioUiState())
    val uiState: StateFlow<StudioUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<StudioUiEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<StudioUiEvent> = _events.asSharedFlow()

    private var pendingSaveAsSourceUri: String? = null
    private var defaultFormatsApplied = false

    init {
        refreshStorageMetrics()
        viewModelScope.launch {
            settingsFlow.collect { settings ->
                if (!defaultFormatsApplied) {
                    defaultFormatsApplied = true
                    _uiState.update { state ->
                        state.copy(
                            audioSettings = state.audioSettings.copy(
                                outputFormat = settings.defaultAudioFormat,
                                qualityProfile = settings.defaultQuality
                            ),
                            imageSettings = state.imageSettings.copy(
                                outputFormat = settings.defaultImageFormat,
                                qualityProfile = settings.defaultQuality,
                                imageQuality = settings.defaultQuality.imageQuality
                            )
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            activeTimelineFlow.collect { project ->
                val currentSelected = _uiState.value.selectedTimelineClipId
                val resolvedClipId = if (project.clips.any { it.clipId == currentSelected }) {
                    currentSelected
                } else {
                    project.clips.firstOrNull()?.clipId
                }
                _uiState.update { it.copy(selectedTimelineClipId = resolvedClipId) }
                refreshVideoEstimate(project)
            }
        }
    }

    fun navigateTo(destination: StudioDestination) {
        _uiState.update { it.copy(activeDestination = destination) }
        if (destination == StudioDestination.SETTINGS) {
            refreshStorageMetrics()
        }
    }

    fun selectStudioTab(tab: StudioWorkspaceTab) {
        _uiState.update { it.copy(activeDestination = StudioDestination.STUDIO, activeStudioTab = tab) }
    }

    fun openTool(tool: StudioTool) {
        val targetTab = when (tool) {
            StudioTool.VIDEO_CONVERT,
            StudioTool.VIDEO_EDIT,
            StudioTool.VIDEO_MERGE,
            StudioTool.VIDEO_TRIM_SPLIT,
            StudioTool.VIDEO_EXTRACT_AUDIO,
            StudioTool.VIDEO_ADD_AUDIO -> StudioWorkspaceTab.VIDEO

            StudioTool.AUDIO_CONVERT,
            StudioTool.AUDIO_EDIT_MERGE -> StudioWorkspaceTab.AUDIO

            StudioTool.IMAGE_CONVERT,
            StudioTool.IMAGE_COMPRESS,
            StudioTool.SLIDESHOW_MAKER -> StudioWorkspaceTab.IMAGE

            StudioTool.BATCH_CONVERT -> StudioWorkspaceTab.PICKER
        }

        _uiState.update { state ->
            state.copy(
                activeDestination = StudioDestination.STUDIO,
                activeStudioTab = targetTab,
                activeTool = tool,
                slideshowMode = tool == StudioTool.SLIDESHOW_MAKER
            )
        }

        if (tool == StudioTool.VIDEO_EXTRACT_AUDIO) {
            val defaultAudioFmt = settingsFlow.value.defaultAudioFormat
            updateTimelineExportSettings {
                it.copy(outputFormat = if (defaultAudioFmt in capabilities.supportedAudioOutputFormats) defaultAudioFmt else "m4a")
            }
        } else if (tool.mediaType == MediaType.VIDEO && tool != StudioTool.SLIDESHOW_MAKER) {
            val currentFmt = activeTimelineFlow.value.exportSettings.outputFormat
            if (currentFmt !in capabilities.supportedVideoOutputFormats) {
                updateTimelineExportSettings { it.copy(outputFormat = "mp4") }
            }
        }
    }

    fun importUris(uris: List<Uri>, role: PickerTargetRole = PickerTargetRole.GENERAL_IMPORT) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true) }
            val imported = mutableListOf<MediaInfo>()
            val failures = mutableListOf<String>()

            for (uri in uris) {
                val res = repository.importMedia(uri)
                res.onSuccess { info -> imported.add(info) }
                    .onFailure { err ->
                        val mapped = StudioErrorMapper.fromThrowable(err)
                        failures.add("${mapped.titleTr}: ${mapped.messageTr}")
                    }
            }

            if (imported.isNotEmpty()) {
                routeImportedMedia(imported, role)
            }

            _uiState.update { it.copy(isImporting = false) }
            refreshStorageMetrics()

            if (failures.isNotEmpty()) {
                emitMessage(failures.first())
            } else if (imported.isNotEmpty()) {
                val mismatch = imported.firstOrNull { it.isExtensionMismatch }
                if (mismatch != null && !mismatch.mismatchWarning.isNullOrBlank()) {
                    emitMessage(mismatch.mismatchWarning)
                } else {
                    emitMessage("${imported.size} dosya stüdyoya eklendi.")
                }
            }
        }
    }

    fun importFromRecent(input: MediaInput, role: PickerTargetRole = PickerTargetRole.GENERAL_IMPORT) {
        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true) }
            val uri = runCatching { Uri.parse(input.uriString) }.getOrNull()
            val result = if (uri != null && fileHelper.canReadUri(uri)) {
                repository.inspectInput(input)
            } else {
                repository.inspectInput(input)
            }
            _uiState.update { it.copy(isImporting = false) }
            result.onSuccess { info ->
                routeImportedMedia(listOf(info), role)
                emitMessage("${info.input.displayName} yüklendi.")
            }.onFailure { err ->
                val mapped = StudioErrorMapper.fromThrowable(err)
                emitMessage("${mapped.titleTr}: ${mapped.messageTr} ${mapped.suggestionTr}")
            }
        }
    }

    private fun routeImportedMedia(items: List<MediaInfo>, role: PickerTargetRole) {
        _uiState.update { state ->
            val merged = (items + state.importedMedia)
                .distinctBy { it.input.uriString }
                .take(40)
            val newSelectedIds = state.selectedMediaIds + items.map { it.input.id }
            state.copy(
                importedMedia = merged,
                selectedMediaIds = newSelectedIds,
                focusedMediaId = items.firstOrNull()?.input?.id ?: state.focusedMediaId
            )
        }

        when (role) {
            PickerTargetRole.VIDEO_CLIPS -> {
                val videos = items.filter { it.mediaType == MediaType.VIDEO || it.hasVideo }
                if (videos.isEmpty()) {
                    emitMessage("Seçilen dosyalar video akışı içermiyor.")
                } else {
                    addVideosToTimeline(videos)
                }
            }

            PickerTargetRole.VIDEO_EXTERNAL_AUDIO -> {
                val audio = items.firstOrNull { it.hasAudio || it.mediaType == MediaType.AUDIO }
                if (audio == null) {
                    emitMessage("Seçilen dosya ses akışı içermiyor.")
                } else {
                    setExternalAudioForTimeline(audio)
                }
            }

            PickerTargetRole.AUDIO_TRACKS -> {
                val audios = items.filter { it.hasAudio || it.mediaType == MediaType.AUDIO }
                if (audios.isEmpty()) {
                    emitMessage("Seçilen dosyalar ses akışı içermiyor.")
                } else {
                    addAudiosToStudio(audios)
                }
            }

            PickerTargetRole.IMAGE_FILES -> {
                val images = items.filter { it.mediaType == MediaType.IMAGE }
                if (images.isEmpty()) {
                    emitMessage("Seçilen dosyalar geçerli bir resim değil.")
                } else {
                    addImagesToStudio(images)
                }
            }

            PickerTargetRole.SLIDESHOW_AUDIO -> {
                val audio = items.firstOrNull { it.hasAudio || it.mediaType == MediaType.AUDIO }
                if (audio == null) {
                    emitMessage("Fon müziği için geçerli bir ses dosyası seçin.")
                } else {
                    setSlideshowAudio(audio)
                }
            }

            PickerTargetRole.GENERAL_IMPORT -> {
                val videos = items.filter { it.mediaType == MediaType.VIDEO || it.hasVideo }
                val audios = items.filter { it.mediaType == MediaType.AUDIO && !it.hasVideo }
                val images = items.filter { it.mediaType == MediaType.IMAGE }
                if (videos.isNotEmpty()) addVideosToTimeline(videos)
                if (audios.isNotEmpty()) addAudiosToStudio(audios)
                if (images.isNotEmpty()) addImagesToStudio(images)
            }
        }
    }

    fun focusMedia(mediaId: String) {
        _uiState.update { it.copy(focusedMediaId = mediaId) }
    }

    fun toggleMediaSelection(mediaId: String) {
        _uiState.update { state ->
            val updated = if (mediaId in state.selectedMediaIds) {
                state.selectedMediaIds - mediaId
            } else {
                state.selectedMediaIds + mediaId
            }
            state.copy(selectedMediaIds = updated, focusedMediaId = mediaId)
        }
    }

    fun removeImportedMedia(mediaId: String) {
        _uiState.update { state ->
            val filtered = state.importedMedia.filterNot { it.input.id == mediaId }
            val nextFocus = if (state.focusedMediaId == mediaId) filtered.firstOrNull()?.input?.id else state.focusedMediaId
            state.copy(
                importedMedia = filtered,
                selectedMediaIds = state.selectedMediaIds - mediaId,
                focusedMediaId = nextFocus
            )
        }
    }

    fun openMediaWithTool(info: MediaInfo, tool: StudioTool) {
        when (info.mediaType) {
            MediaType.VIDEO -> addVideosToTimeline(listOf(info))
            MediaType.AUDIO -> addAudiosToStudio(listOf(info))
            MediaType.IMAGE -> addImagesToStudio(listOf(info))
            MediaType.UNKNOWN -> Unit
        }
        openTool(tool)
    }

    fun extractVideoFrame(uriString: String, positionMs: Long, sourceName: String) {
        viewModelScope.launch {
            val result = repository.extractFrameToFile(uriString, positionMs.coerceAtLeast(0L))
            result.onSuccess { path ->
                _uiState.update {
                    it.copy(extractedFramePath = path, extractedFrameSourceName = sourceName)
                }
                emitMessage("Kare çıkarıldı (${MediaFormatUtils.formatDurationMs(positionMs)}).")
            }.onFailure { err ->
                val mapped = StudioErrorMapper.fromThrowable(err)
                emitMessage("${mapped.titleTr}: ${mapped.messageTr}")
            }
        }
    }

    fun dismissExtractedFrame() {
        _uiState.update { it.copy(extractedFramePath = null, extractedFrameSourceName = null) }
    }

    fun saveExtractedFrameToGallery() {
        val path = _uiState.value.extractedFramePath ?: return
        val base = _uiState.value.extractedFrameSourceName ?: "video_kare"
        viewModelScope.launch {
            runCatching {
                val file = File(path)
                val ext = file.extension.ifBlank { "jpg" }
                fileHelper.saveTempFileToPublicStorage(
                    tempFile = file,
                    desiredBaseName = "${MediaFormatUtils.sanitizeBaseName(base)}_kare",
                    extension = ext,
                    mediaType = MediaType.IMAGE
                )
            }.onSuccess { saved ->
                emitMessage("Kare Resimler/FormatciStudio klasörüne kaydedildi: ${saved.displayName}")
            }.onFailure { err ->
                val mapped = StudioErrorMapper.fromThrowable(err)
                emitMessage("${mapped.titleTr}: ${mapped.messageTr}")
            }
        }
    }

    fun importExtractedFrameToImageStudio() {
        val path = _uiState.value.extractedFramePath ?: return
        val file = File(path)
        if (!file.exists()) {
            emitMessage("Çıkarılan kare dosyası artık önbellekte bulunamadı.")
            return
        }
        importUris(listOf(Uri.fromFile(file)), PickerTargetRole.IMAGE_FILES)
        _uiState.update {
            it.copy(
                extractedFramePath = null,
                extractedFrameSourceName = null,
                activeDestination = StudioDestination.STUDIO,
                activeStudioTab = StudioWorkspaceTab.IMAGE,
                activeTool = StudioTool.IMAGE_CONVERT,
                slideshowMode = false
            )
        }
    }

    fun enqueueBatchForSelected(targetTool: StudioTool = StudioTool.BATCH_CONVERT) {
        val selected = _uiState.value.selectedMediaList.ifEmpty { _uiState.value.importedMedia }
        if (selected.isEmpty()) {
            emitMessage("Toplu dönüştürme için önce dosya seçin.")
            return
        }
        val settings = settingsFlow.value
        val jobSettings = MediaJobSettings(
            qualityProfile = settings.defaultQuality,
            imageQuality = settings.defaultQuality.imageQuality
        )
        viewModelScope.launch {
            val jobs = repository.enqueueBatch(
                tool = targetTool,
                inputs = selected.map { it.input },
                settings = jobSettings
            )
            emitMessage(
                message = "${jobs.size} dosya toplu işlem kuyruğuna eklendi.",
                actionLabel = "Kuyruğu Gör",
                targetDestination = StudioDestination.QUEUE
            )
        }
    }

    // region Video Studio & Timeline
    fun addVideosToTimeline(videos: List<MediaInfo>) {
        val existingClips = activeTimelineFlow.value.clips
        val newClips = videos.map { info ->
            val dur = info.durationMs.coerceAtLeast(1_000L)
            TimelineClipItem(
                clipId = UUID.randomUUID().toString(),
                input = info.input,
                durationMs = dur,
                trimStartMs = 0L,
                trimEndMs = dur,
                thumbnailPath = info.thumbnailPath
            )
        }
        repository.updateTimeline(recordUndo = true) { project ->
            project.copy(clips = project.clips + newClips)
        }
        if (existingClips.isEmpty() && newClips.isNotEmpty()) {
            _uiState.update { it.copy(selectedTimelineClipId = newClips.first().clipId) }
        }
    }

    fun selectTimelineClip(clipId: String) {
        _uiState.update { it.copy(selectedTimelineClipId = clipId) }
    }

    fun moveTimelineClip(clipId: String, moveUp: Boolean) {
        repository.updateTimeline(recordUndo = true) { project ->
            val list = project.clips.toMutableList()
            val idx = list.indexOfFirst { it.clipId == clipId }
            if (idx < 0) return@updateTimeline project
            val swap = if (moveUp) idx - 1 else idx + 1
            if (swap !in list.indices) return@updateTimeline project
            val item = list.removeAt(idx)
            list.add(swap, item)
            project.copy(clips = list)
        }
    }

    fun removeTimelineClip(clipId: String) {
        repository.updateTimeline(recordUndo = true) { project ->
            project.copy(clips = project.clips.filterNot { it.clipId == clipId })
        }
    }

    fun updateTimelineClip(clipId: String, transform: (TimelineClipItem) -> TimelineClipItem) {
        repository.updateTimeline(recordUndo = true) { project ->
            project.copy(
                clips = project.clips.map { clip ->
                    if (clip.clipId == clipId) transform(clip) else clip
                }
            )
        }
    }

    fun splitTimelineClipAt(clipId: String, splitOffsetMs: Long) {
        repository.updateTimeline(recordUndo = true) { project ->
            val list = project.clips.toMutableList()
            val idx = list.indexOfFirst { it.clipId == clipId }
            if (idx < 0) return@updateTimeline project
            val clip = list[idx]
            if (splitOffsetMs <= clip.trimStartMs + 250L || splitOffsetMs >= clip.trimEndMs - 250L) {
                return@updateTimeline project
            }
            val firstHalf = clip.copy(trimEndMs = splitOffsetMs)
            val secondHalf = clip.copy(
                clipId = UUID.randomUUID().toString(),
                trimStartMs = splitOffsetMs
            )
            list[idx] = firstHalf
            list.add(idx + 1, secondHalf)
            project.copy(clips = list)
        }
    }

    fun setExternalAudioForTimeline(audio: MediaInfo?) {
        repository.updateTimeline(recordUndo = true) { project ->
            project.copy(
                externalAudioInput = audio?.input,
                exportSettings = project.exportSettings.copy(secondaryAudioUri = audio?.input?.uriString)
            )
        }
    }

    fun updateExternalAudioConfig(volume: Float, fitMode: AudioFitMode) {
        repository.updateTimeline(recordUndo = true) { project ->
            project.copy(
                externalAudioVolume = volume.coerceIn(0f, 2f),
                audioFitMode = fitMode,
                exportSettings = project.exportSettings.copy(
                    secondaryAudioVolume = volume.coerceIn(0f, 2f),
                    audioFitMode = fitMode
                )
            )
        }
    }

    fun updateTimelineExportSettings(transform: (MediaJobSettings) -> MediaJobSettings) {
        repository.updateTimeline(recordUndo = true) { project ->
            project.copy(exportSettings = transform(project.exportSettings))
        }
    }

    fun clearTimeline() {
        repository.updateTimeline(recordUndo = true) {
            TimelineProject(exportSettings = MediaJobSettings(outputFormat = settingsFlow.value.defaultVideoFormat))
        }
    }

    fun undoTimeline() {
        if (!repository.undoTimeline()) {
            emitMessage("Geri alınacak değişiklik yok.")
        }
    }

    fun redoTimeline() {
        if (!repository.redoTimeline()) {
            emitMessage("Yinelenecek değişiklik yok.")
        }
    }

    private fun refreshVideoEstimate(project: TimelineProject = activeTimelineFlow.value) {
        if (project.clips.isEmpty()) {
            _uiState.update { it.copy(videoEstimate = null) }
            return
        }
        viewModelScope.launch {
            val job = buildVideoJobFromProject(project, _uiState.value.activeTool)
            val estimate = repository.estimateJob(job)
            _uiState.update { it.copy(videoEstimate = estimate) }
        }
    }

    fun enqueueVideoJob(requestedTool: StudioTool) {
        val project = activeTimelineFlow.value
        if (project.clips.isEmpty()) {
            emitMessage("Lütfen önce zaman çizelgesine en az bir video klibi ekleyin.")
            return
        }
        if (requestedTool == StudioTool.VIDEO_MERGE && project.clips.size < 2) {
            emitMessage("Birleştirme için zaman çizelgesinde en az 2 video klibi olmalıdır.")
            return
        }
        if (requestedTool == StudioTool.VIDEO_ADD_AUDIO && project.externalAudioInput == null) {
            emitMessage("Videoya ses eklemek için önce harici bir ses dosyası seçin.")
            return
        }

        val job = buildVideoJobFromProject(project, requestedTool)
        viewModelScope.launch {
            repository.enqueueJob(job)
            emitMessage(
                message = "${requestedTool.titleTr} işlemi kuyruğa eklendi.",
                actionLabel = "Kuyruğu Aç",
                targetDestination = StudioDestination.QUEUE
            )
        }
    }

    private fun buildVideoJobFromProject(project: TimelineProject, tool: StudioTool): MediaJob {
        val firstClip = project.clips.firstOrNull()
        val isAudioExtract = tool == StudioTool.VIDEO_EXTRACT_AUDIO
        val targetExt = if (isAudioExtract) {
            if (project.exportSettings.outputFormat in capabilities.supportedAudioOutputFormats) {
                project.exportSettings.outputFormat
            } else {
                "m4a"
            }
        } else {
            if (project.exportSettings.outputFormat in capabilities.supportedVideoOutputFormats) {
                project.exportSettings.outputFormat
            } else {
                "mp4"
            }
        }
        val baseTitle = firstClip?.input?.displayName ?: "video"
        val resolvedSettings = project.exportSettings.copy(
            outputFormat = targetExt,
            trimStartMs = firstClip?.trimStartMs ?: 0L,
            trimEndMs = firstClip?.trimEndMs,
            playbackSpeed = firstClip?.speed ?: project.exportSettings.playbackSpeed,
            rotateDegrees = firstClip?.rotateDegrees ?: project.exportSettings.rotateDegrees,
            flipHorizontal = firstClip?.flipHorizontal ?: project.exportSettings.flipHorizontal,
            flipVertical = firstClip?.flipVertical ?: project.exportSettings.flipVertical,
            brightness = firstClip?.brightness ?: project.exportSettings.brightness,
            contrast = firstClip?.contrast ?: project.exportSettings.contrast,
            saturation = firstClip?.saturation ?: project.exportSettings.saturation,
            grayscale = firstClip?.grayscale ?: project.exportSettings.grayscale,
            sepia = firstClip?.sepia ?: project.exportSettings.sepia,
            watermarkText = firstClip?.watermarkText?.ifBlank { project.exportSettings.watermarkText }
                ?: project.exportSettings.watermarkText,
            audioVolume = firstClip?.volume ?: project.exportSettings.audioVolume,
            removeAudio = if (isAudioExtract) false else (firstClip?.muted == true || project.exportSettings.removeAudio),
            secondaryAudioUri = project.externalAudioInput?.uriString ?: project.exportSettings.secondaryAudioUri,
            secondaryAudioVolume = project.externalAudioVolume,
            audioFitMode = project.audioFitMode
        )

        return MediaJob(
            tool = tool,
            title = if (project.clips.size > 1) {
                "${tool.titleTr} (${project.clips.size} klip)"
            } else {
                "${tool.titleTr}: $baseTitle"
            },
            inputs = project.clips.map { it.input },
            settings = resolvedSettings,
            timelineClips = project.clips
        )
    }
    // endregion

    // region Audio Studio
    fun addAudiosToStudio(audios: List<MediaInfo>) {
        _uiState.update { state ->
            val combined = (state.audioInputs + audios).distinctBy { it.input.uriString }
            val focused = state.focusedAudioId ?: combined.firstOrNull()?.input?.id
            val firstDur = combined.firstOrNull { it.input.id == focused }?.durationMs ?: 0L
            val nextSettings = if (state.audioInputs.isEmpty() && firstDur > 0L) {
                state.audioSettings.copy(trimStartMs = 0L, trimEndMs = firstDur)
            } else {
                state.audioSettings
            }
            state.copy(
                audioInputs = combined,
                focusedAudioId = focused,
                audioSettings = nextSettings
            )
        }
        refreshAudioEstimate()
    }

    fun selectFocusedAudio(id: String) {
        _uiState.update { state ->
            val target = state.audioInputs.firstOrNull { it.input.id == id }
            val dur = target?.durationMs ?: 0L
            state.copy(
                focusedAudioId = id,
                audioSettings = state.audioSettings.copy(
                    trimStartMs = 0L,
                    trimEndMs = if (dur > 0L) dur else null
                )
            )
        }
        refreshAudioEstimate()
    }

    fun moveAudioItem(id: String, moveUp: Boolean) {
        _uiState.update { state ->
            val list = state.audioInputs.toMutableList()
            val idx = list.indexOfFirst { it.input.id == id }
            if (idx < 0) return@update state
            val swap = if (moveUp) idx - 1 else idx + 1
            if (swap !in list.indices) return@update state
            val item = list.removeAt(idx)
            list.add(swap, item)
            state.copy(audioInputs = list)
        }
        refreshAudioEstimate()
    }

    fun removeAudioItem(id: String) {
        _uiState.update { state ->
            val nextList = state.audioInputs.filterNot { it.input.id == id }
            val nextFocus = if (state.focusedAudioId == id) nextList.firstOrNull()?.input?.id else state.focusedAudioId
            state.copy(audioInputs = nextList, focusedAudioId = nextFocus)
        }
        refreshAudioEstimate()
    }

    fun updateAudioSettings(transform: (MediaJobSettings) -> MediaJobSettings) {
        _uiState.update { state ->
            state.copy(audioSettings = transform(state.audioSettings))
        }
        refreshAudioEstimate()
    }

    private fun refreshAudioEstimate() {
        val state = _uiState.value
        if (state.audioInputs.isEmpty()) {
            _uiState.update { it.copy(audioEstimate = null) }
            return
        }
        viewModelScope.launch {
            val job = buildAudioJob(state, StudioTool.AUDIO_EDIT_MERGE)
            val est = repository.estimateJob(job)
            _uiState.update { it.copy(audioEstimate = est) }
        }
    }

    fun enqueueAudioJob(tool: StudioTool) {
        val state = _uiState.value
        if (state.audioInputs.isEmpty()) {
            emitMessage("Lütfen önce en az bir ses dosyası ekleyin.")
            return
        }
        if (tool == StudioTool.AUDIO_EDIT_MERGE && state.audioSettings.mixAudioInsteadOfConcat && state.audioInputs.size < 2) {
            emitMessage("İki sesi mikslemek için en az 2 ses dosyası ekleyin.")
            return
        }
        val job = buildAudioJob(state, tool)
        viewModelScope.launch {
            repository.enqueueJob(job)
            emitMessage(
                message = "${tool.titleTr} kuyruğa eklendi.",
                actionLabel = "Kuyruğu Aç",
                targetDestination = StudioDestination.QUEUE
            )
        }
    }

    private fun buildAudioJob(state: StudioUiState, tool: StudioTool): MediaJob {
        val focused = state.focusedAudio ?: state.audioInputs.first()
        val isMulti = state.audioInputs.size > 1 && tool == StudioTool.AUDIO_EDIT_MERGE
        val resolvedInputs = if (isMulti && !state.audioSettings.mixAudioInsteadOfConcat) {
            state.audioInputs.map { it.input }
        } else {
            listOf(focused.input)
        }
        val secondUri = if (state.audioSettings.mixAudioInsteadOfConcat && state.audioInputs.size >= 2) {
            state.audioInputs.firstOrNull { it.input.id != focused.input.id }?.input?.uriString
        } else {
            null
        }
        val fmt = if (state.audioSettings.outputFormat in capabilities.supportedAudioOutputFormats) {
            state.audioSettings.outputFormat
        } else {
            "m4a"
        }
        val codec = when (fmt) {
            "opus" -> "audio/opus"
            "flac" -> "audio/flac"
            "wav" -> "audio/raw"
            else -> "audio/mp4a-latm"
        }
        val resolvedSettings = state.audioSettings.copy(
            outputFormat = fmt,
            audioCodec = codec,
            secondaryAudioUri = secondUri
        )
        return MediaJob(
            tool = tool,
            title = if (isMulti) "${tool.titleTr} (${state.audioInputs.size} ses)" else "${tool.titleTr}: ${focused.input.displayName}",
            inputs = resolvedInputs,
            settings = resolvedSettings
        )
    }
    // endregion

    // region Image & Slideshow Studio
    fun addImagesToStudio(images: List<MediaInfo>) {
        _uiState.update { state ->
            val combined = (state.imageInputs + images).distinctBy { it.input.uriString }
            val focused = state.focusedImageId ?: combined.firstOrNull()?.input?.id
            state.copy(imageInputs = combined, focusedImageId = focused)
        }
        refreshImageEstimate()
    }

    fun selectFocusedImage(id: String) {
        _uiState.update { it.copy(focusedImageId = id) }
        refreshImageEstimate()
    }

    fun moveImageItem(id: String, moveUp: Boolean) {
        _uiState.update { state ->
            val list = state.imageInputs.toMutableList()
            val idx = list.indexOfFirst { it.input.id == id }
            if (idx < 0) return@update state
            val swap = if (moveUp) idx - 1 else idx + 1
            if (swap !in list.indices) return@update state
            val item = list.removeAt(idx)
            list.add(swap, item)
            state.copy(imageInputs = list)
        }
        refreshImageEstimate()
    }

    fun removeImageItem(id: String) {
        _uiState.update { state ->
            val next = state.imageInputs.filterNot { it.input.id == id }
            val nextFocus = if (state.focusedImageId == id) next.firstOrNull()?.input?.id else state.focusedImageId
            state.copy(imageInputs = next, focusedImageId = nextFocus)
        }
        refreshImageEstimate()
    }

    fun setSlideshowMode(enabled: Boolean) {
        _uiState.update {
            it.copy(
                slideshowMode = enabled,
                activeTool = if (enabled) StudioTool.SLIDESHOW_MAKER else StudioTool.IMAGE_CONVERT
            )
        }
        refreshImageEstimate()
    }

    fun setSlideshowAudio(info: MediaInfo?) {
        _uiState.update {
            it.copy(
                slideshowBackgroundAudio = info,
                imageSettings = it.imageSettings.copy(secondaryAudioUri = info?.input?.uriString)
            )
        }
    }

    fun updateImageSettings(transform: (MediaJobSettings) -> MediaJobSettings) {
        _uiState.update { state ->
            state.copy(imageSettings = transform(state.imageSettings))
        }
        refreshImageEstimate()
    }

    private fun refreshImageEstimate() {
        val state = _uiState.value
        if (state.imageInputs.isEmpty()) {
            _uiState.update { it.copy(imageEstimate = null) }
            return
        }
        viewModelScope.launch {
            val job = if (state.slideshowMode) {
                buildSlideshowJob(state)
            } else {
                val target = state.focusedImage ?: state.imageInputs.first()
                MediaJob(
                    tool = StudioTool.IMAGE_CONVERT,
                    title = target.input.displayName,
                    inputs = listOf(target.input),
                    settings = state.imageSettings
                )
            }
            val est = repository.estimateJob(job)
            _uiState.update { it.copy(imageEstimate = est) }
        }
    }

    fun enqueueImageConvert(compressMode: Boolean, batchAll: Boolean) {
        val state = _uiState.value
        if (state.imageInputs.isEmpty()) {
            emitMessage("Lütfen önce en az bir resim dosyası seçin.")
            return
        }
        val tool = if (compressMode) StudioTool.IMAGE_COMPRESS else StudioTool.IMAGE_CONVERT
        viewModelScope.launch {
            if (batchAll && state.imageInputs.size > 1) {
                val jobs = repository.enqueueBatch(
                    tool = tool,
                    inputs = state.imageInputs.map { it.input },
                    settings = state.imageSettings.copy(outputFileName = "")
                )
                emitMessage(
                    message = "${jobs.size} resim toplu kuyruğa eklendi.",
                    actionLabel = "Kuyruğu Aç",
                    targetDestination = StudioDestination.QUEUE
                )
            } else {
                val target = state.focusedImage ?: state.imageInputs.first()
                repository.enqueueJob(
                    tool = tool,
                    title = "${tool.titleTr}: ${target.input.displayName}",
                    inputs = listOf(target.input),
                    settings = state.imageSettings
                )
                emitMessage(
                    message = "${target.input.displayName} kuyruğa eklendi.",
                    actionLabel = "Kuyruğu Aç",
                    targetDestination = StudioDestination.QUEUE
                )
            }
        }
    }

    fun enqueueSlideshowJob() {
        val state = _uiState.value
        if (state.imageInputs.isEmpty()) {
            emitMessage("Slayt videosu oluşturmak için en az 1 fotoğraf ekleyin.")
            return
        }
        val job = buildSlideshowJob(state)
        viewModelScope.launch {
            repository.enqueueJob(job)
            emitMessage(
                message = "Slayt videosu kuyruğa eklendi.",
                actionLabel = "Kuyruğu Aç",
                targetDestination = StudioDestination.QUEUE
            )
        }
    }

    private fun buildSlideshowJob(state: StudioUiState): MediaJob {
        val settings = state.imageSettings.copy(
            outputFormat = "mp4",
            videoCodec = "video/avc",
            secondaryAudioUri = state.slideshowBackgroundAudio?.input?.uriString
        )
        return MediaJob(
            tool = StudioTool.SLIDESHOW_MAKER,
            title = "Fotoğraflardan Video (${state.imageInputs.size} kare)",
            inputs = state.imageInputs.map { it.input },
            settings = settings
        )
    }
    // endregion

    // region Queue, History & Settings
    fun moveJobOrder(jobId: String, moveUp: Boolean) {
        viewModelScope.launch { repository.moveJobOrder(jobId, moveUp) }
    }

    fun cancelJob(jobId: String) {
        viewModelScope.launch { repository.cancelJob(jobId) }
    }

    fun retryJob(jobId: String) {
        viewModelScope.launch {
            repository.retryJob(jobId)
            emitMessage("İş yeniden kuyruğa alındı.")
        }
    }

    fun clearFinishedJobs() {
        viewModelScope.launch {
            repository.clearFinishedJobs()
            emitMessage("Tamamlanan ve durdurulan işler temizlendi.")
        }
    }

    fun setHistoryFilter(mediaType: MediaType?) {
        _uiState.update { it.copy(historyFilter = mediaType) }
    }

    fun setHistorySortOrder(order: HistorySortOrder) {
        _uiState.update { it.copy(historySortOrder = order) }
    }

    fun setHistorySearchQuery(query: String) {
        _uiState.update { it.copy(historySearchQuery = query) }
    }

    fun renameHistoryRecord(recordId: String, newName: String) {
        if (newName.isBlank()) {
            emitMessage("Dosya adı boş olamaz.")
            return
        }
        viewModelScope.launch {
            repository.renameHistoryRecord(recordId, newName)
                .onSuccess { updated -> emitMessage("Dosya adı güncellendi: ${updated.displayName}") }
                .onFailure { err ->
                    val mapped = StudioErrorMapper.fromThrowable(err)
                    emitMessage("${mapped.titleTr}: ${mapped.messageTr}")
                }
        }
    }

    fun deleteHistoryRecord(recordId: String, deleteFile: Boolean) {
        viewModelScope.launch {
            val ok = repository.deleteHistoryRecord(recordId, deleteFile)
            if (ok) {
                emitMessage(if (deleteFile) "Kayıt ve dosya silindi." else "Kayıt listeden kaldırıldı.")
            }
        }
    }

    fun openUri(uriString: String, mimeType: String) {
        val shareable = fileHelper.getShareableUri(uriString)
        _events.tryEmit(StudioUiEvent.OpenOutput(shareable, mimeType))
    }

    fun shareUri(uriString: String, mimeType: String, title: String) {
        val shareable = fileHelper.getShareableUri(uriString)
        _events.tryEmit(StudioUiEvent.ShareOutput(shareable, mimeType, title))
    }

    fun requestSaveAs(sourceUriString: String, suggestedName: String, mimeType: String) {
        pendingSaveAsSourceUri = sourceUriString
        _events.tryEmit(StudioUiEvent.RequestSaveAs(suggestedName, mimeType, sourceUriString))
    }

    fun completeSaveAs(targetUri: Uri?) {
        val sourceStr = pendingSaveAsSourceUri
        pendingSaveAsSourceUri = null
        if (targetUri == null || sourceStr.isNullOrBlank()) return

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val sourceUri = Uri.parse(sourceStr)
                    val input = fileHelper.openInputStream(sourceUri)
                        ?: throw IllegalStateException("Kaynak dosya okunamadı")
                    val output = appContext.contentResolver.openOutputStream(targetUri, "wt")
                        ?: throw IllegalStateException("Hedef konum yazılamadı")
                    input.use { src ->
                        output.use { dst ->
                            src.copyTo(dst, 64 * 1024)
                            dst.flush()
                        }
                    }
                }
            }.onSuccess {
                emitMessage("Dosya seçilen konuma kaydedildi.")
            }.onFailure { err ->
                val mapped = StudioErrorMapper.fromThrowable(err)
                emitMessage("${mapped.titleTr}: ${mapped.messageTr}")
            }
        }
    }

    fun setThemePreference(theme: ThemePreference) {
        viewModelScope.launch { prefs.setThemePreference(theme) }
    }

    fun setSimpleMode(enabled: Boolean) {
        viewModelScope.launch { prefs.setSimpleMode(enabled) }
    }

    fun setDefaultQuality(quality: QualityProfile) {
        viewModelScope.launch { prefs.setDefaultQuality(quality) }
    }

    fun setDefaultVideoFormat(format: String) {
        viewModelScope.launch { prefs.setDefaultVideoFormat(format) }
    }

    fun setDefaultAudioFormat(format: String) {
        viewModelScope.launch { prefs.setDefaultAudioFormat(format) }
    }

    fun setDefaultImageFormat(format: String) {
        viewModelScope.launch { prefs.setDefaultImageFormat(format) }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setNotificationsEnabled(enabled) }
    }

    fun setAutoCleanCache(enabled: Boolean) {
        viewModelScope.launch { prefs.setAutoCleanCache(enabled) }
    }

    fun refreshStorageMetrics() {
        viewModelScope.launch(Dispatchers.IO) {
            val cache = repository.getCacheSizeBytes()
            val avail = fileHelper.getAvailableStorageBytes()
            _uiState.update { it.copy(cacheSizeBytes = cache, availableStorageBytes = avail) }
        }
    }

    fun clearTempCache() {
        viewModelScope.launch {
            val freed = repository.clearTempCache(keepThumbnails = false)
            refreshStorageMetrics()
            emitMessage("Önbellek temizlendi (${MediaFormatUtils.formatBytes(freed)} yer açıldı).")
        }
    }

    fun exportDiagnosticReport() {
        viewModelScope.launch {
            repository.exportDiagnosticLog()
                .onSuccess { uri ->
                    _events.tryEmit(
                        StudioUiEvent.ShareOutput(
                            uri = uri,
                            mimeType = "text/plain",
                            title = "AVENLYX Studio Tanı ve Hata Raporunu Paylaş"
                        )
                    )
                }
                .onFailure { err ->
                    val mapped = StudioErrorMapper.fromThrowable(err)
                    emitMessage("${mapped.titleTr}: ${mapped.messageTr}")
                }
        }
    }
    // endregion

    fun emitMessage(
        message: String,
        actionLabel: String? = null,
        targetDestination: StudioDestination? = null
    ) {
        _events.tryEmit(StudioUiEvent.ShowMessage(message, actionLabel, targetDestination))
    }

    class Factory(
        private val appContext: Context,
        private val repository: StudioRepository,
        private val fileHelper: FileStorageHelper,
        private val prefs: UserPreferencesRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return StudioViewModel(appContext, repository, fileHelper, prefs) as T
        }
    }
}
