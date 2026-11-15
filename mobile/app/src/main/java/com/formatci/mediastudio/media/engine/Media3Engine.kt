package com.formatci.mediastudio.media.engine

import android.content.Context
import android.graphics.Color
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.TextOverlay
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.formatci.mediastudio.core.error.StudioErrorCode
import com.formatci.mediastudio.core.error.StudioErrorMapper
import com.formatci.mediastudio.core.error.StudioException
import com.formatci.mediastudio.core.files.FileStorageHelper
import com.formatci.mediastudio.domain.model.AspectRatioOption
import com.formatci.mediastudio.domain.model.AudioFitMode
import com.formatci.mediastudio.domain.model.EngineCapabilities
import com.formatci.mediastudio.domain.model.JobEstimate
import com.formatci.mediastudio.domain.model.MediaFormatUtils
import com.formatci.mediastudio.domain.model.MediaInfo
import com.formatci.mediastudio.domain.model.MediaInput
import com.formatci.mediastudio.domain.model.MediaJob
import com.formatci.mediastudio.domain.model.MediaJobEvent
import com.formatci.mediastudio.domain.model.MediaJobSettings
import com.formatci.mediastudio.domain.model.MediaType
import com.formatci.mediastudio.domain.model.StudioTool
import com.formatci.mediastudio.domain.model.TimelineClipItem
import com.formatci.mediastudio.media.capability.EngineCapabilityRegistry
import com.formatci.mediastudio.media.inspector.MediaInspector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * AndroidX Media3 1.5.1 Transformer, Composition ve Effect pipeline'ı tabanlı video motoru.
 * Tek Transformer oturumunu Mutex ile sıraya alır; geçici dosyada üretip başarılı olunca
 * FileStorageHelper üzerinden MediaStore/SAF çıktısına atomik taşır.
 */
@OptIn(UnstableApi::class)
class Media3Engine(
    private val context: Context,
    private val fileHelper: FileStorageHelper,
    private val capabilityRegistry: EngineCapabilityRegistry,
    private val inspector: MediaInspector
) : MediaEngine {

    private val executionMutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activeTransformers = ConcurrentHashMap<String, Transformer>()
    private val cancelledJobs = ConcurrentHashMap.newKeySet<String>()

    override suspend fun inspect(input: MediaInput): MediaInfo = inspector.inspect(input)

    override fun capabilities(): EngineCapabilities = capabilityRegistry.getCapabilities()

    override suspend fun extractFrameToFile(uriString: String, positionMs: Long): Result<String> {
        return inspector.extractFrameToFile(uriString, positionMs)
    }

    override suspend fun estimate(job: MediaJob): JobEstimate = withContext(Dispatchers.IO) {
        val warnings = mutableListOf<String>()
        val inputs = job.inputs
        var totalDurationMs = 0L
        var totalInputBytes = 0L

        if (job.tool == StudioTool.SLIDESHOW_MAKER) {
            totalDurationMs = (inputs.size.coerceAtLeast(1) * job.settings.slideDurationMs.coerceAtLeast(500L))
            totalInputBytes = inputs.sumOf { it.sizeBytes }.coerceAtLeast(128_000L)
        } else if (job.timelineClips.isNotEmpty()) {
            totalDurationMs = job.timelineClips.sumOf { it.effectiveDurationMs }
            totalInputBytes = job.timelineClips.sumOf { it.input.sizeBytes }.coerceAtLeast(65_536L)
        } else {
            inputs.forEach { input ->
                totalInputBytes += input.sizeBytes
                val info = runCatching { inspector.inspect(input) }.getOrNull()
                if (info != null) {
                    if (info.isExtensionMismatch && info.mismatchWarning != null) {
                        warnings.add(info.mismatchWarning)
                    }
                    val clipDur = if (job.settings.trimEndMs != null && job.settings.trimEndMs > job.settings.trimStartMs) {
                        job.settings.trimEndMs - job.settings.trimStartMs
                    } else {
                        (info.durationMs - job.settings.trimStartMs).coerceAtLeast(500L)
                    }
                    totalDurationMs += clipDur
                }
            }
        }

        if (totalDurationMs <= 0L) totalDurationMs = 10_000L
        val estBytes = MediaFormatUtils.estimateOutputBytes(
            tool = job.tool,
            durationMs = totalDurationMs,
            inputSizeBytes = totalInputBytes,
            settings = job.settings
        )
        val requiredFree = (estBytes * 2.2).toLong().coerceAtLeast(16L * 1024L * 1024L)
        val hasEnough = fileHelper.hasEnoughStorage(requiredFree)
        if (!hasEnough) {
            warnings.add("Depolama alanı sınırda: tahmini çıktı ve geçici işlem için yer açmanız önerilir.")
        }

        val speedFactor = if (job.settings.videoCodec == MimeTypes.VIDEO_H265) 1.6 else 0.9
        val estProcessingMs = (totalDurationMs * speedFactor).toLong().coerceAtLeast(2_000L)

        JobEstimate(
            estimatedOutputBytes = estBytes,
            estimatedDurationMs = totalDurationMs,
            estimatedProcessingTimeMs = estProcessingMs,
            requiredFreeSpaceBytes = requiredFree,
            hasEnoughStorage = hasEnough,
            warnings = warnings
        )
    }

    override fun execute(job: MediaJob): Flow<MediaJobEvent> = flow {
        cancelledJobs.remove(job.id)
        emit(MediaJobEvent.Started(job.id, "Medya kaynakları ve kodek uyumluluğu doğrulanıyor..."))

        var tempOutputFile: File? = null
        var transformerStageFile: File? = null
        var activeTransformer: Transformer? = null

        try {
            validateJobInputs(job)
            val est = estimate(job)
            fileHelper.ensureEnoughStorage(est.requiredFreeSpaceBytes)

            val settings = job.settings
            val isAudioOnly = job.tool == StudioTool.VIDEO_EXTRACT_AUDIO
            val outExt = resolveOutputExtension(job, isAudioOnly)
            val outMime = MediaFormatUtils.mimeTypeForExtension(outExt)
            val outMediaType = if (isAudioOnly) MediaType.AUDIO else MediaType.VIDEO
            val isWebm = outExt == "webm"

            val requestedVideoMime = resolveTargetVideoMime(outExt, settings.videoCodec)
            if (!isAudioOnly && !capabilityRegistry.isVideoCodecSupported(requestedVideoMime)) {
                throw StudioException(
                    StudioErrorCode.NO_ENCODER,
                    "Hedef video kodeği desteklenmiyor: $requestedVideoMime"
                )
            }
            if (isWebm && !capabilityRegistry.isVideoOutputSupported("webm")) {
                throw StudioException(
                    StudioErrorCode.NO_ENCODER,
                    "Bu cihazda WebM (VP9 + Opus) dışa aktarım desteği bulunmuyor."
                )
            }

            tempOutputFile = fileHelper.createTempOutputFile("m3_${job.id.take(6)}", outExt)
            transformerStageFile = if (isWebm) {
                fileHelper.createTempOutputFile("m3_stage_${job.id.take(6)}", "mp4")
            } else {
                null
            }
            val transformerDestPath = (transformerStageFile ?: tempOutputFile).absolutePath
            val stageVideoMime = if (isWebm) MimeTypes.VIDEO_H264 else requestedVideoMime
            val vBitrate = settings.videoBitrateBps
                ?: (2_600_000f * settings.qualityProfile.bitrateMultiplier).roundToInt()
            val stageVideoBitrate = if (isWebm) {
                (vBitrate * 1.4f).roundToInt().coerceIn(2_000_000, 35_000_000)
            } else {
                vBitrate.coerceIn(300_000, 35_000_000)
            }
            val aBitrate = settings.audioBitrateBps.coerceIn(64_000, 320_000)

            executionMutex.withLock {
                if (cancelledJobs.contains(job.id)) {
                    throw CancellationException("İş kuyrukta iptal edildi")
                }

                val builtSpec = buildCompositionAndOptions(job, isAudioOnly, outExt)
                val completion = CompletableDeferred<ExportResult>()

                val transformer = withContext(Dispatchers.Main) {
                    val encoderFactory = DefaultEncoderFactory.Builder(context)
                        .setRequestedVideoEncoderSettings(
                            VideoEncoderSettings.Builder()
                                .setBitrate(stageVideoBitrate)
                                .build()
                        )
                        .setRequestedAudioEncoderSettings(
                            AudioEncoderSettings.Builder()
                                .setBitrate(if (isWebm) aBitrate.coerceAtLeast(160_000) else aBitrate)
                                .build()
                        )
                        .setEnableFallback(true)
                        .build()

                    val builder = Transformer.Builder(context)
                        .setLooper(Looper.getMainLooper())
                        .setEncoderFactory(encoderFactory)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                if (!completion.isCompleted) {
                                    completion.complete(exportResult)
                                }
                            }

                            override fun onError(
                                composition: Composition,
                                exportResult: ExportResult,
                                exportException: ExportException
                            ) {
                                if (!completion.isCompleted) {
                                    completion.completeExceptionally(exportException)
                                }
                            }
                        })

                    if (!isAudioOnly) {
                        builder.setVideoMimeType(stageVideoMime)
                    }
                    if (!builtSpec.allAudioRemoved) {
                        builder.setAudioMimeType(MimeTypes.AUDIO_AAC)
                    }

                    builder.build().also { created ->
                        activeTransformer = created
                        activeTransformers[job.id] = created
                        created.start(builtSpec.composition, transformerDestPath)
                    }
                }

                emit(MediaJobEvent.Progress(job.id, 0.02f, isIndeterminate = true, message = "Kodlama başlatılıyor..."))

                val holder = ProgressHolder()
                var lastProgress = 0.02f
                val stageMaxProgress = if (isWebm) 0.54f else 0.94f
                while (!completion.isCompleted) {
                    if (cancelledJobs.contains(job.id)) {
                        withContext(Dispatchers.Main) { transformer.cancel() }
                        throw CancellationException("Kullanıcı işlemi iptal etti")
                    }

                    val progressState = withContext(Dispatchers.Main) {
                        transformer.getProgress(holder)
                    }

                    when (progressState) {
                        Transformer.PROGRESS_STATE_AVAILABLE -> {
                            val pct = ((holder.progress / 100f) * stageMaxProgress).coerceIn(0.02f, stageMaxProgress)
                            if (pct >= lastProgress) lastProgress = pct
                            emit(
                                MediaJobEvent.Progress(
                                    jobId = job.id,
                                    progress = lastProgress,
                                    isIndeterminate = false,
                                    message = if (isWebm) {
                                        "Düzenlemeler hazırlanıyor (%${(lastProgress * 100).toInt()})"
                                    } else {
                                        "Medya işleniyor (%${(lastProgress * 100).toInt()})"
                                    }
                                )
                            )
                        }
                        Transformer.PROGRESS_STATE_WAITING_FOR_AVAILABILITY -> {
                            emit(
                                MediaJobEvent.Progress(
                                    jobId = job.id,
                                    progress = lastProgress,
                                    isIndeterminate = true,
                                    message = "Kareler ve ses akışı hazırlanıyor..."
                                )
                            )
                        }
                    }
                    delay(250L)
                }

                val exportResult = completion.await()

                val stageSource = transformerStageFile
                if (isWebm && stageSource != null) {
                    emit(
                        MediaJobEvent.Progress(
                            jobId = job.id,
                            progress = 0.55f,
                            isIndeterminate = false,
                            message = "WebM (VP9 + Opus) kodlanıyor..."
                        )
                    )
                    val progressChannel = Channel<Float>(Channel.CONFLATED)
                    coroutineScope {
                        val webmJob = async(Dispatchers.Default) {
                            try {
                                transcodeMp4ToWebm(
                                    jobId = job.id,
                                    sourceMp4 = stageSource,
                                    destWebm = tempOutputFile,
                                    videoBitrate = vBitrate.coerceIn(300_000, 25_000_000),
                                    audioBitrate = aBitrate.coerceIn(24_000, 256_000),
                                    durationMs = exportResult.durationMs
                                ) { frac ->
                                    progressChannel.trySend(frac)
                                }
                            } finally {
                                progressChannel.close()
                            }
                        }
                        for (frac in progressChannel) {
                            val mappedPct = (0.55f + frac.coerceIn(0f, 1f) * 0.40f).coerceIn(0.55f, 0.95f)
                            emit(
                                MediaJobEvent.Progress(
                                    jobId = job.id,
                                    progress = mappedPct,
                                    isIndeterminate = false,
                                    message = "WebM (VP9 + Opus) kodlanıyor (%${(mappedPct * 100).toInt()})"
                                )
                            )
                        }
                        webmJob.await()
                    }
                }

                emit(
                    MediaJobEvent.Progress(
                        jobId = job.id,
                        progress = 0.96f,
                        isIndeterminate = false,
                        message = "Çıktı cihaz deposuna kaydediliyor..."
                    )
                )

                val baseName = settings.outputFileName.ifBlank {
                    job.inputs.firstOrNull()?.displayName ?: job.title
                }
                val saved = fileHelper.saveTempFileToPublicStorage(
                    tempFile = tempOutputFile,
                    desiredBaseName = baseName,
                    extension = outExt,
                    mediaType = outMediaType
                )

                emit(
                    MediaJobEvent.Completed(
                        jobId = job.id,
                        outputUriString = saved.uriString,
                        outputDisplayName = saved.displayName,
                        outputMimeType = outMime,
                        outputFormat = outExt,
                        outputSizeBytes = saved.sizeBytes,
                        durationMs = exportResult.durationMs.coerceAtLeast(0L),
                        width = if (isAudioOnly) 0 else exportResult.width.coerceAtLeast(0),
                        height = if (isAudioOnly) 0 else exportResult.height.coerceAtLeast(0),
                        mediaType = outMediaType
                    )
                )
            }
        } catch (_: CancellationException) {
            cancelledJobs.add(job.id)
            activeTransformer?.let { t -> mainHandler.post { runCatching { t.cancel() } } }
            emit(MediaJobEvent.Cancelled(job.id))
        } catch (t: Throwable) {
            if (cancelledJobs.contains(job.id)) {
                emit(MediaJobEvent.Cancelled(job.id))
            } else {
                val mapped = mapMedia3Throwable(t)
                emit(
                    MediaJobEvent.Failed(
                        jobId = job.id,
                        errorCode = mapped.code.name,
                        message = mapped.messageTr,
                        suggestion = mapped.suggestionTr
                    )
                )
            }
        } finally {
            activeTransformers.remove(job.id)?.let { t ->
                if (cancelledJobs.contains(job.id)) {
                    mainHandler.post { runCatching { t.cancel() } }
                }
            }
            cancelledJobs.remove(job.id)
            fileHelper.deleteFileQuietly(transformerStageFile)
            fileHelper.deleteFileQuietly(tempOutputFile)
        }
    }

    override suspend fun cancel(jobId: String) {
        cancelledJobs.add(jobId)
        val running = activeTransformers[jobId] ?: return
        mainHandler.post { runCatching { running.cancel() } }
    }

    private data class BuiltCompositionSpec(
        val composition: Composition,
        val allAudioRemoved: Boolean
    )

    private suspend fun buildCompositionAndOptions(
        job: MediaJob,
        isAudioOnly: Boolean,
        outExt: String
    ): BuiltCompositionSpec {
        val settings = job.settings

        if (isAudioOnly) {
            val primary = job.inputs.first()
            val mediaItem = buildClippedMediaItem(
                uriString = primary.uriString,
                trimStartMs = settings.trimStartMs,
                trimEndMs = settings.trimEndMs
            )
            val audioProcessors = buildAudioProcessors(
                volume = settings.audioVolume,
                speed = 1f,
                removeAudio = false
            )
            val edited = EditedMediaItem.Builder(mediaItem)
                .setRemoveVideo(true)
                .setEffects(Effects(audioProcessors, emptyList()))
                .build()
            val seq = EditedMediaItemSequence.Builder(edited).build()
            return BuiltCompositionSpec(
                composition = Composition.Builder(seq).build(),
                allAudioRemoved = false
            )
        }

        if (job.tool == StudioTool.SLIDESHOW_MAKER) {
            return buildSlideshowComposition(job)
        }

        val clips: List<TimelineClipItem> = if (job.timelineClips.isNotEmpty()) {
            job.timelineClips
        } else {
            job.inputs.map { input ->
                TimelineClipItem(
                    input = input,
                    durationMs = 0L,
                    trimStartMs = settings.trimStartMs,
                    trimEndMs = settings.trimEndMs ?: 0L,
                    speed = settings.playbackSpeed,
                    volume = settings.audioVolume,
                    muted = settings.removeAudio,
                    rotateDegrees = settings.rotateDegrees,
                    flipHorizontal = settings.flipHorizontal,
                    flipVertical = settings.flipVertical,
                    brightness = settings.brightness,
                    contrast = settings.contrast,
                    saturation = settings.saturation,
                    grayscale = settings.grayscale,
                    sepia = settings.sepia,
                    watermarkText = settings.watermarkText
                )
            }
        }

        val isMultiClip = clips.size > 1
        val normalizeResolution = if (isMultiClip) resolveCommonCanvasSize(clips.first(), settings) else null
        val allClipsMuted = clips.all { it.muted || it.volume <= 0.01f }
        var usesSdrOnlyColorEffects = false

        val editedItems = clips.map { clip ->
            val effectiveEnd = clip.trimEndMs.takeIf { it > clip.trimStartMs }
            val mediaItem = buildClippedMediaItem(
                uriString = clip.input.uriString,
                trimStartMs = clip.trimStartMs,
                trimEndMs = effectiveEnd
            )
            val videoEffects = mutableListOf<Effect>()
            val audioProcessors = mutableListOf<AudioProcessor>()

            // Çoklu klip birleştirmede VideoFrameProcessor tutarlılığı için ortak boyut uygula
            if (normalizeResolution != null) {
                videoEffects.add(
                    Presentation.createForWidthAndHeight(
                        normalizeResolution.first,
                        normalizeResolution.second,
                        Presentation.LAYOUT_SCALE_TO_FIT
                    )
                )
            } else {
                addTargetPresentationEffect(videoEffects, settings)
            }

            addTransformAndFilterEffects(
                videoEffects = videoEffects,
                rotateDegrees = clip.rotateDegrees,
                flipHorizontal = clip.flipHorizontal,
                flipVertical = clip.flipVertical,
                brightness = clip.brightness,
                contrast = clip.contrast,
                saturation = clip.saturation,
                grayscale = clip.grayscale,
                sepia = clip.sepia,
                watermarkText = clip.watermarkText,
                watermarkOpacity = settings.watermarkOpacity
            )

            if (abs(clip.brightness) > 0.01f || clip.grayscale || clip.sepia || clip.watermarkText.isNotBlank()) {
                usesSdrOnlyColorEffects = true
            }

            val clampedSpeed = clip.speed.coerceIn(0.25f, 4.0f)
            if (abs(clampedSpeed - 1.0f) > 0.02f) {
                val speedPair = Effects.createExperimentalSpeedChangingEffect(
                    constantSpeedProvider(clampedSpeed)
                )
                if (!allClipsMuted && !(clip.muted || clip.volume <= 0.01f)) {
                    audioProcessors.add(speedPair.first)
                }
                videoEffects.add(speedPair.second)
            }

            if (!allClipsMuted) {
                val clipVol = if (clip.muted) 0f else clip.volume
                audioProcessors.addAll(
                    buildAudioProcessors(volume = clipVol, speed = 1f, removeAudio = false)
                )
            }

            EditedMediaItem.Builder(mediaItem)
                .setRemoveAudio(allClipsMuted)
                .setEffects(Effects(audioProcessors, videoEffects))
                .build()
        }

        val primaryVideoSequence = EditedMediaItemSequence.Builder(editedItems).build()
        val sequences = mutableListOf(primaryVideoSequence)
        var forceAudioTrack = false
        var allAudioRemoved = allClipsMuted

        val secondaryAudioUri = settings.secondaryAudioUri?.takeIf { it.isNotBlank() }
        if (secondaryAudioUri != null && (job.tool == StudioTool.VIDEO_ADD_AUDIO || job.timelineClips.isNotEmpty())) {
            val totalVideoMs = estimateClipsTotalDurationMs(clips)
            val audioSeqSpec = buildSecondaryAudioSequence(
                audioUriString = secondaryAudioUri,
                targetDurationMs = totalVideoMs,
                volume = settings.secondaryAudioVolume,
                fitMode = settings.audioFitMode
            )
            sequences.add(audioSeqSpec.sequence)
            forceAudioTrack = audioSeqSpec.requiresForceAudioTrack
            allAudioRemoved = false
        }

        val compBuilder = Composition.Builder(sequences)
        if (usesSdrOnlyColorEffects) {
            compBuilder.setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
        }
        if (forceAudioTrack) {
            compBuilder.experimentalSetForceAudioTrack(true)
        }

        return BuiltCompositionSpec(
            composition = compBuilder.build(),
            allAudioRemoved = allAudioRemoved
        )
    }

    private suspend fun buildSlideshowComposition(job: MediaJob): BuiltCompositionSpec {
        val settings = job.settings
        val slideDurationMs = settings.slideDurationMs.coerceIn(500L, 30_000L)
        val fps = settings.slideshowFps.coerceIn(15, 60)
        val (outW, outH) = resolveSlideshowDimensions(settings)

        val sharedEffects = mutableListOf<Effect>(
            Presentation.createForWidthAndHeight(outW, outH, Presentation.LAYOUT_SCALE_TO_FIT)
        )
        addTransformAndFilterEffects(
            videoEffects = sharedEffects,
            rotateDegrees = settings.rotateDegrees,
            flipHorizontal = settings.flipHorizontal,
            flipVertical = settings.flipVertical,
            brightness = settings.brightness,
            contrast = settings.contrast,
            saturation = settings.saturation,
            grayscale = settings.grayscale,
            sepia = settings.sepia,
            watermarkText = settings.watermarkText,
            watermarkOpacity = settings.watermarkOpacity
        )

        val imageItems = job.inputs.map { input ->
            val mediaItem = MediaItem.Builder()
                .setUri(Uri.parse(input.uriString))
                .setImageDurationMs(slideDurationMs)
                .build()
            EditedMediaItem.Builder(mediaItem)
                .setDurationUs(slideDurationMs * 1000L)
                .setFrameRate(fps)
                .setRemoveAudio(true)
                .setEffects(Effects(emptyList(), sharedEffects))
                .build()
        }

        val imageSeq = EditedMediaItemSequence.Builder(imageItems).build()
        val sequences = mutableListOf(imageSeq)
        var forceAudioTrack = false
        var allAudioRemoved = true

        val bgAudioUri = settings.secondaryAudioUri?.takeIf { it.isNotBlank() }
        if (bgAudioUri != null) {
            val totalDurationMs = imageItems.size * slideDurationMs
            val audioSeqSpec = buildSecondaryAudioSequence(
                audioUriString = bgAudioUri,
                targetDurationMs = totalDurationMs,
                volume = settings.secondaryAudioVolume,
                fitMode = settings.audioFitMode
            )
            sequences.add(audioSeqSpec.sequence)
            forceAudioTrack = audioSeqSpec.requiresForceAudioTrack
            allAudioRemoved = false
        }

        val compBuilder = Composition.Builder(sequences)
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
        if (forceAudioTrack) {
            compBuilder.experimentalSetForceAudioTrack(true)
        }

        return BuiltCompositionSpec(
            composition = compBuilder.build(),
            allAudioRemoved = allAudioRemoved
        )
    }

    private data class SecondaryAudioSequenceSpec(
        val sequence: EditedMediaItemSequence,
        val requiresForceAudioTrack: Boolean
    )

    private suspend fun buildSecondaryAudioSequence(
        audioUriString: String,
        targetDurationMs: Long,
        volume: Float,
        fitMode: AudioFitMode
    ): SecondaryAudioSequenceSpec {
        val parsedUri = Uri.parse(audioUriString)
        if (!fileHelper.canReadUri(parsedUri)) {
            throw StudioException(StudioErrorCode.URI_PERMISSION_LOST, audioUriString)
        }
        val audioInfo = runCatching {
            inspector.inspect(
                MediaInput(uriString = audioUriString, displayName = "harici_ses", mediaType = MediaType.AUDIO)
            )
        }.getOrNull()

        val audioDurationMs = audioInfo?.durationMs ?: 0L
        val mediaBuilder = MediaItem.Builder().setUri(parsedUri)
        if (fitMode != AudioFitMode.LOOP && targetDurationMs > 0L && (audioDurationMs == 0L || audioDurationMs > targetDurationMs)) {
            mediaBuilder.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(0L)
                    .setEndPositionMs(targetDurationMs)
                    .build()
            )
        }

        val processors = buildAudioProcessors(volume = volume, speed = 1f, removeAudio = false)
        val editedAudio = EditedMediaItem.Builder(mediaBuilder.build())
            .setRemoveVideo(true)
            .setEffects(Effects(processors, emptyList()))
            .build()

        val seqBuilder = EditedMediaItemSequence.Builder(editedAudio)
        var requiresForceAudio = false

        when (fitMode) {
            AudioFitMode.LOOP -> {
                seqBuilder.setIsLooping(true)
            }
            AudioFitMode.PAD_SILENCE -> {
                if (audioDurationMs > 0L && targetDurationMs > audioDurationMs + 150L) {
                    seqBuilder.addGap((targetDurationMs - audioDurationMs) * 1000L)
                    requiresForceAudio = true
                }
            }
            AudioFitMode.TRIM -> Unit
        }

        return SecondaryAudioSequenceSpec(
            sequence = seqBuilder.build(),
            requiresForceAudioTrack = requiresForceAudio
        )
    }

    private suspend fun estimateClipsTotalDurationMs(clips: List<TimelineClipItem>): Long {
        var total = 0L
        for (clip in clips) {
            val dur = if (clip.durationMs > 0L) {
                clip.effectiveDurationMs
            } else {
                val info = runCatching { inspector.inspect(clip.input) }.getOrNull()
                val raw = info?.durationMs ?: 10_000L
                val end = if (clip.trimEndMs > clip.trimStartMs) clip.trimEndMs else raw
                ((end - clip.trimStartMs).coerceAtLeast(200L) / clip.speed.coerceIn(0.25f, 4f)).toLong()
            }
            total += dur
        }
        return total.coerceAtLeast(500L)
    }

    private suspend fun resolveCommonCanvasSize(
        firstClip: TimelineClipItem,
        settings: MediaJobSettings
    ): Pair<Int, Int> {
        val reqW = settings.targetWidth
        val reqH = settings.targetHeight
        if (reqW != null && reqH != null && reqW > 0 && reqH > 0) {
            return makeEven(reqW) to makeEven(reqH)
        }
        if (settings.aspectRatio != AspectRatioOption.ORIGINAL) {
            val h = makeEven(reqH ?: 720)
            val w = makeEven((h * settings.aspectRatio.ratioW.toFloat() / settings.aspectRatio.ratioH).roundToInt())
            return w to h
        }
        val info = runCatching { inspector.inspect(firstClip.input) }.getOrNull()
        val rawW = info?.width?.takeIf { it > 0 } ?: 1280
        val rawH = info?.height?.takeIf { it > 0 } ?: 720
        val rotated = firstClip.rotateDegrees == 90 || firstClip.rotateDegrees == 270
        val w = if (rotated) rawH else rawW
        val h = if (rotated) rawW else rawH
        return makeEven(w.coerceIn(320, 1920)) to makeEven(h.coerceIn(240, 1920))
    }

    private fun resolveSlideshowDimensions(settings: MediaJobSettings): Pair<Int, Int> {
        val reqW = settings.targetWidth
        val reqH = settings.targetHeight
        if (reqW != null && reqH != null && reqW > 0 && reqH > 0) {
            return makeEven(reqW) to makeEven(reqH)
        }
        val base = when (settings.qualityProfile) {
            com.formatci.mediastudio.domain.model.QualityProfile.HIGH_QUALITY -> 1080
            com.formatci.mediastudio.domain.model.QualityProfile.BALANCED -> 720
            com.formatci.mediastudio.domain.model.QualityProfile.SMALL_FILE -> 540
        }
        return when (settings.aspectRatio) {
            AspectRatioOption.RATIO_9_16 -> makeEven(base) to makeEven((base * 16f / 9f).roundToInt())
            AspectRatioOption.RATIO_1_1 -> makeEven(base) to makeEven(base)
            AspectRatioOption.RATIO_4_3 -> makeEven((base * 4f / 3f).roundToInt()) to makeEven(base)
            AspectRatioOption.RATIO_16_9, AspectRatioOption.ORIGINAL ->
                makeEven((base * 16f / 9f).roundToInt()) to makeEven(base)
        }
    }

    private fun addTargetPresentationEffect(
        videoEffects: MutableList<Effect>,
        settings: MediaJobSettings
    ) {
        val reqW = settings.targetWidth
        val reqH = settings.targetHeight
        when {
            reqW != null && reqH != null && reqW > 0 && reqH > 0 -> {
                videoEffects.add(
                    Presentation.createForWidthAndHeight(
                        makeEven(reqW),
                        makeEven(reqH),
                        Presentation.LAYOUT_SCALE_TO_FIT
                    )
                )
            }
            reqH != null && reqH > 0 -> {
                val evenH = makeEven(reqH)
                if (settings.aspectRatio != AspectRatioOption.ORIGINAL) {
                    val evenW = makeEven((evenH * settings.aspectRatio.ratioW.toFloat() / settings.aspectRatio.ratioH).roundToInt())
                    videoEffects.add(
                        Presentation.createForWidthAndHeight(evenW, evenH, Presentation.LAYOUT_SCALE_TO_FIT)
                    )
                } else {
                    videoEffects.add(Presentation.createForHeight(evenH))
                }
            }
            settings.aspectRatio != AspectRatioOption.ORIGINAL -> {
                val ratio = settings.aspectRatio.ratioW.toFloat() / settings.aspectRatio.ratioH.toFloat()
                videoEffects.add(Presentation.createForAspectRatio(ratio, Presentation.LAYOUT_SCALE_TO_FIT))
            }
        }
    }

    private fun addTransformAndFilterEffects(
        videoEffects: MutableList<Effect>,
        rotateDegrees: Int,
        flipHorizontal: Boolean,
        flipVertical: Boolean,
        brightness: Float,
        contrast: Float,
        saturation: Float,
        grayscale: Boolean,
        sepia: Boolean,
        watermarkText: String,
        watermarkOpacity: Float
    ) {
        val normRot = ((rotateDegrees % 360) + 360) % 360
        if (normRot != 0 || flipHorizontal || flipVertical) {
            // OpenGL NDC koordinatları Y-yukarı olduğu için saat yönü döndürmeyi eşle
            val glRot = ((360 - normRot) % 360).toFloat()
            videoEffects.add(
                ScaleAndRotateTransformation.Builder()
                    .setRotationDegrees(glRot)
                    .setScale(if (flipHorizontal) -1f else 1f, if (flipVertical) -1f else 1f)
                    .build()
            )
        }

        if (abs(brightness) > 0.01f) {
            videoEffects.add(Brightness(brightness.coerceIn(-0.95f, 0.95f)))
        }
        if (abs(contrast - 1f) > 0.01f) {
            // Media3 Contrast [-1, 1] aralığındadır; 0f değişiklik yapmaz.
            videoEffects.add(Contrast((contrast - 1f).coerceIn(-0.9f, 0.9f)))
        }
        if (abs(saturation - 1f) > 0.01f && !grayscale) {
            // HslAdjustment saturation [-100, 100] aralığındadır.
            val satDelta = ((saturation - 1f) * 100f).coerceIn(-100f, 100f)
            videoEffects.add(
                HslAdjustment.Builder()
                    .adjustSaturation(satDelta)
                    .build()
            )
        }
        if (grayscale) {
            videoEffects.add(RgbFilter.createGrayscaleFilter())
        } else if (sepia) {
            videoEffects.add(SepiaRgbMatrix())
        }

        if (watermarkText.isNotBlank()) {
            val cleanText = " ${watermarkText.trim()} "
            val spannable = SpannableString(cleanText).apply {
                setSpan(ForegroundColorSpan(Color.WHITE), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(
                    BackgroundColorSpan(Color.argb(135, 16, 20, 28)),
                    0,
                    length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            val overlaySettings = OverlaySettings.Builder()
                .setBackgroundFrameAnchor(0f, -0.80f)
                .setOverlayFrameAnchor(0f, 0f)
                .setAlphaScale(watermarkOpacity.coerceIn(0.1f, 1f))
                .build()
            val textOverlay = TextOverlay.createStaticTextOverlay(spannable, overlaySettings)
            videoEffects.add(OverlayEffect(listOf(textOverlay)))
        }
    }

    private fun buildAudioProcessors(
        volume: Float,
        speed: Float,
        removeAudio: Boolean
    ): List<AudioProcessor> {
        if (removeAudio) return emptyList()
        val list = mutableListOf<AudioProcessor>()
        val clampedVol = volume.coerceIn(0f, 2.0f)
        if (abs(clampedVol - 1.0f) > 0.01f) {
            val mixer = ChannelMixingAudioProcessor()
            for (ch in 1..8) {
                mixer.putChannelMixingMatrix(ChannelMixingMatrix.create(ch, ch).scaleBy(clampedVol))
            }
            list.add(mixer)
        }
        if (abs(speed - 1.0f) > 0.02f) {
            val speedPair = Effects.createExperimentalSpeedChangingEffect(
                constantSpeedProvider(speed.coerceIn(0.25f, 4.0f))
            )
            list.add(speedPair.first)
        }
        return list
    }

    private fun buildClippedMediaItem(
        uriString: String,
        trimStartMs: Long,
        trimEndMs: Long?
    ): MediaItem {
        val builder = MediaItem.Builder().setUri(Uri.parse(uriString))
        val safeStart = trimStartMs.coerceAtLeast(0L)
        if (safeStart > 0L || (trimEndMs != null && trimEndMs > safeStart)) {
            val clipBuilder = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(safeStart)
            if (trimEndMs != null && trimEndMs > safeStart) {
                clipBuilder.setEndPositionMs(trimEndMs)
            }
            builder.setClippingConfiguration(clipBuilder.build())
        }
        return builder.build()
    }

    private fun validateJobInputs(job: MediaJob) {
        val urisToCheck = buildList {
            job.inputs.forEach { add(it.uriString) }
            job.timelineClips.forEach { add(it.input.uriString) }
            job.settings.secondaryAudioUri?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
        if (urisToCheck.isEmpty()) {
            throw StudioException(StudioErrorCode.FILE_NOT_FOUND, "İşlenecek medya girişi seçilmedi")
        }
        for (rawUri in urisToCheck) {
            val uri = runCatching { Uri.parse(rawUri) }.getOrNull()
                ?: throw StudioException(StudioErrorCode.FILE_NOT_FOUND, rawUri)
            if (!fileHelper.canReadUri(uri)) {
                throw StudioException(StudioErrorCode.URI_PERMISSION_LOST, rawUri)
            }
        }
        val trimEnd = job.settings.trimEndMs
        if (trimEnd != null && trimEnd <= job.settings.trimStartMs + 100L) {
            throw StudioException(
                StudioErrorCode.DURATION_MISMATCH,
                "Kesme aralığı geçersiz: başlangıç=${job.settings.trimStartMs}ms, bitiş=${trimEnd}ms"
            )
        }
        val reqW = job.settings.targetWidth
        val reqH = job.settings.targetHeight
        if ((reqW != null && reqW < 16) || (reqH != null && reqH < 16)) {
            throw StudioException(StudioErrorCode.INVALID_RESOLUTION_FPS, "Hedef çözünürlük çok küçük")
        }
        val reqFps = job.settings.targetFps
        if (reqFps != null && reqFps !in 1..120) {
            throw StudioException(StudioErrorCode.INVALID_RESOLUTION_FPS, "Geçersiz FPS: $reqFps")
        }
    }

    private fun resolveOutputExtension(job: MediaJob, isAudioOnly: Boolean): String {
        val requested = job.settings.outputFormat.lowercase(Locale.ROOT).trimStart('.')
        return if (isAudioOnly) {
            when (requested) {
                "m4a", "aac" -> "m4a"
                else -> "m4a"
            }
        } else {
            when (requested) {
                "webm" -> "webm"
                else -> "mp4"
            }
        }
    }

    private fun resolveTargetVideoMime(outExt: String, requestedCodec: String): String {
        if (outExt == "webm") return MimeTypes.VIDEO_VP9
        return when (requestedCodec.lowercase(Locale.ROOT)) {
            MimeTypes.VIDEO_H265, "hevc", "h265" -> MimeTypes.VIDEO_H265
            MimeTypes.VIDEO_AV1, "av1" -> MimeTypes.VIDEO_AV1
            else -> MimeTypes.VIDEO_H264
        }
    }

    private fun makeEven(value: Int): Int = (value.coerceAtLeast(16) and 1.inv()).coerceAtLeast(16)

    private fun constantSpeedProvider(speed: Float): SpeedProvider = object : SpeedProvider {
        override fun getSpeed(timeUs: Long): Float = speed
        override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
    }

    private fun mapMedia3Throwable(t: Throwable): com.formatci.mediastudio.core.error.StudioErrorInfo {
        if (t is ExportException) {
            val detail = "${t.errorCodeName}: ${t.message ?: t.cause?.message ?: "Detay yok"}"
            val code = when (t.errorCode) {
                ExportException.ERROR_CODE_IO_FILE_NOT_FOUND -> StudioErrorCode.FILE_NOT_FOUND
                ExportException.ERROR_CODE_IO_NO_PERMISSION -> StudioErrorCode.URI_PERMISSION_LOST
                ExportException.ERROR_CODE_IO_UNSPECIFIED,
                ExportException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> StudioErrorCode.IO_READ_ERROR
                ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> StudioErrorCode.CODEC_UNSUPPORTED
                ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
                ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED -> StudioErrorCode.NO_ENCODER
                ExportException.ERROR_CODE_MUXING_FAILED,
                ExportException.ERROR_CODE_MUXING_TIMEOUT,
                ExportException.ERROR_CODE_MUXING_APPEND -> StudioErrorCode.OUTPUT_CREATE_FAILED
                else -> {
                    val lower = detail.lowercase(Locale.ROOT)
                    when {
                        "resource" in lower || "reclaimed" in lower || "thermal" in lower ->
                            StudioErrorCode.THERMAL_OR_RESOURCE
                        else -> StudioErrorMapper.fromThrowable(t).code
                    }
                }
            }
            return StudioErrorMapper.fromCode(code, detail)
        }
        return StudioErrorMapper.fromThrowable(t)
    }

    /**
     * OpenGL sütun-öncelikli 4x4 Sepya renk matrisi.
     */
    private class SepiaRgbMatrix : RgbMatrix {
        private val matrix = floatArrayOf(
            0.393f, 0.349f, 0.272f, 0f,
            0.769f, 0.686f, 0.534f, 0f,
            0.189f, 0.168f, 0.131f, 0f,
            0f, 0f, 0f, 1f
        )

        override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray = matrix
    }

    /**
     * Android 10+ cihazlarda Transformer ara çıktısını (H.264 + AAC MP4)
     * Android Platform MediaCodec + MediaMuxer(MUXER_OUTPUT_WEBM) ile gerçek VP9 + Opus WebM'e dönüştürür.
     */
    private suspend fun transcodeMp4ToWebm(
        jobId: String,
        sourceMp4: File,
        destWebm: File,
        videoBitrate: Int,
        audioBitrate: Int,
        durationMs: Long,
        onFraction: (Float) -> Unit
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw StudioException(StudioErrorCode.NO_ENCODER, "WebM (VP9 + Opus) dışa aktarımı Android 10 ve üzeri gerektirir.")
        }

        val probe = MediaExtractor()
        var videoTrackIdx = -1
        var audioTrackIdx = -1
        var srcVideoFmt: MediaFormat? = null
        var srcAudioFmt: MediaFormat? = null
        try {
            probe.setDataSource(sourceMp4.absolutePath)
            for (i in 0 until probe.trackCount) {
                val fmt = probe.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") && videoTrackIdx < 0) {
                    videoTrackIdx = i
                    srcVideoFmt = fmt
                } else if (mime.startsWith("audio/") && audioTrackIdx < 0) {
                    audioTrackIdx = i
                    srcAudioFmt = fmt
                }
            }
        } finally {
            runCatching { probe.release() }
        }

        val vFmt = srcVideoFmt
        if (videoTrackIdx < 0 || vFmt == null) {
            throw StudioException(StudioErrorCode.CORRUPT_MEDIA, "WebM dönüşümü için video akışı bulunamadı.")
        }

        val expectedTracks = 1 + (if (audioTrackIdx >= 0 && srcAudioFmt != null) 1 else 0)
        val totalDurationUs = durationMs.coerceAtLeast(1_000L) * 1_000L
        val muxer = MediaMuxer(destWebm.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM)
        val coordinator = WebmTrackCoordinator(muxer, expectedTracks)

        try {
            coroutineScope {
                val vTask = async(Dispatchers.Default) {
                    transcodeVideoTrackToVp9(
                        jobId = jobId,
                        sourcePath = sourceMp4.absolutePath,
                        trackIndex = videoTrackIdx,
                        srcFormat = vFmt,
                        videoBitrate = videoBitrate,
                        totalDurationUs = totalDurationUs,
                        coordinator = coordinator,
                        onFraction = onFraction
                    )
                }
                val aTask = if (audioTrackIdx >= 0 && srcAudioFmt != null) {
                    async(Dispatchers.Default) {
                        transcodeAudioTrackToOpus(
                            jobId = jobId,
                            sourcePath = sourceMp4.absolutePath,
                            trackIndex = audioTrackIdx,
                            srcFormat = srcAudioFmt,
                            audioBitrate = audioBitrate,
                            coordinator = coordinator
                        )
                    }
                } else {
                    null
                }
                vTask.await()
                aTask?.await()
            }
            onFraction(1f)
        } finally {
            coordinator.release()
        }
    }

    private suspend fun transcodeVideoTrackToVp9(
        jobId: String,
        sourcePath: String,
        trackIndex: Int,
        srcFormat: MediaFormat,
        videoBitrate: Int,
        totalDurationUs: Long,
        coordinator: WebmTrackCoordinator,
        onFraction: (Float) -> Unit
    ) {
        val srcMime = srcFormat.getString(MediaFormat.KEY_MIME)
            ?: throw StudioException(StudioErrorCode.CODEC_UNSUPPORTED, "Video MIME tipi bulunamadı.")
        val rawW = if (srcFormat.containsKey(MediaFormat.KEY_WIDTH)) srcFormat.getInteger(MediaFormat.KEY_WIDTH) else 1280
        val rawH = if (srcFormat.containsKey(MediaFormat.KEY_HEIGHT)) srcFormat.getInteger(MediaFormat.KEY_HEIGHT) else 720
        val fps = if (srcFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            srcFormat.getInteger(MediaFormat.KEY_FRAME_RATE).coerceIn(10, 60)
        } else {
            30
        }
        val width = makeEven(rawW)
        val height = makeEven(rawH)

        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var inputSurface: android.view.Surface? = null

        try {
            extractor.setDataSource(sourcePath)
            extractor.selectTrack(trackIndex)

            val encFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_VP9, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_VP9)
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = encoder.createInputSurface()
            encoder.start()

            decoder = MediaCodec.createDecoderByType(srcMime)
            decoder.configure(srcFormat, inputSurface, null, 0)
            decoder.start()

            val decInfo = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()
            var decInputDone = false
            var decOutputDone = false
            var encDone = false
            var muxTrackIdx = -1
            var lastReported = -1f

            while (!encDone) {
                checkWebmCancelled(jobId)

                if (!decInputDone) {
                    val inIdx = decoder.dequeueInputBuffer(2_000L)
                    if (inIdx >= 0) {
                        val inBuf = decoder.getInputBuffer(inIdx)
                        val size = if (inBuf != null) extractor.readSampleData(inBuf, 0) else -1
                        if (size < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            decInputDone = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                if (!decOutputDone) {
                    val decOutIdx = decoder.dequeueOutputBuffer(decInfo, 2_000L)
                    if (decOutIdx >= 0) {
                        val doRender = decInfo.size > 0 && (decInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        if (doRender) {
                            decoder.releaseOutputBuffer(decOutIdx, decInfo.presentationTimeUs * 1_000L)
                        } else {
                            decoder.releaseOutputBuffer(decOutIdx, false)
                        }
                        if ((decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            decOutputDone = true
                            encoder.signalEndOfInputStream()
                        }
                    }
                }

                drain@ while (!encDone) {
                    val encOutIdx = encoder.dequeueOutputBuffer(encInfo, if (decOutputDone) 5_000L else 1_000L)
                    when {
                        encOutIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> break@drain
                        encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            muxTrackIdx = coordinator.registerTrack(encoder.outputFormat)
                        }
                        encOutIdx >= 0 -> {
                            val outBuf = encoder.getOutputBuffer(encOutIdx)
                            if ((encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                encInfo.size = 0
                            }
                            if (outBuf != null && encInfo.size > 0 && muxTrackIdx >= 0) {
                                coordinator.writeSample(muxTrackIdx, outBuf, encInfo)
                                val frac = (encInfo.presentationTimeUs.toFloat() / totalDurationUs.toFloat()).coerceIn(0f, 1f)
                                if (frac - lastReported >= 0.03f) {
                                    lastReported = frac
                                    onFraction(frac)
                                }
                            }
                            encoder.releaseOutputBuffer(encOutIdx, false)
                            if ((encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                encDone = true
                            }
                        }
                    }
                }
            }
            if (muxTrackIdx < 0) {
                coordinator.onTrackEndedWithoutFormat()
            }
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { inputSurface?.release() }
            runCatching { extractor.release() }
        }
    }

    private suspend fun transcodeAudioTrackToOpus(
        jobId: String,
        sourcePath: String,
        trackIndex: Int,
        srcFormat: MediaFormat,
        audioBitrate: Int,
        coordinator: WebmTrackCoordinator
    ) {
        val srcMime = srcFormat.getString(MediaFormat.KEY_MIME)
            ?: throw StudioException(StudioErrorCode.CODEC_UNSUPPORTED, "Ses MIME tipi bulunamadı.")
        val targetRate = 48_000
        var inRate = if (srcFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) srcFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44_100
        var inChannels = if (srcFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) srcFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
        val targetChannels = inChannels.coerceIn(1, 2)
        val bytesPerFrame = targetChannels * 2
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null

        try {
            extractor.setDataSource(sourcePath)
            extractor.selectTrack(trackIndex)

            val opusFmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, targetRate, targetChannels).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, audioBitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32 * 1024)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            encoder.configure(opusFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            decoder = MediaCodec.createDecoderByType(srcMime)
            decoder.configure(srcFormat, null, null, 0)
            decoder.start()

            val resampler = AudioPcmUtils.StreamingLinearResampler(targetRate, targetChannels)
            val scratchOut = ByteArrayOutputStream(16 * 1024)
            var pendingPcm = ByteArray(64 * 1024)
            var pendingOffset = 0
            var pendingLen = 0

            val decInfo = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()
            var decInputDone = false
            var decOutputDone = false
            var encInputDone = false
            var encDone = false
            var fedFrames = 0L
            var muxTrackIdx = -1

            fun appendPcm(bytes: ByteArray) {
                if (bytes.isEmpty()) return
                if (pendingOffset > 0 && pendingOffset + pendingLen + bytes.size > pendingPcm.size) {
                    System.arraycopy(pendingPcm, pendingOffset, pendingPcm, 0, pendingLen)
                    pendingOffset = 0
                }
                if (pendingOffset + pendingLen + bytes.size > pendingPcm.size) {
                    val newCap = max(pendingPcm.size * 2, pendingLen + bytes.size + 4096)
                    val grown = ByteArray(newCap)
                    System.arraycopy(pendingPcm, pendingOffset, grown, 0, pendingLen)
                    pendingPcm = grown
                    pendingOffset = 0
                }
                System.arraycopy(bytes, 0, pendingPcm, pendingOffset + pendingLen, bytes.size)
                pendingLen += bytes.size
            }

            while (!encDone) {
                checkWebmCancelled(jobId)

                if (!decInputDone) {
                    val inIdx = decoder.dequeueInputBuffer(2_000L)
                    if (inIdx >= 0) {
                        val inBuf = decoder.getInputBuffer(inIdx)
                        val size = if (inBuf != null) extractor.readSampleData(inBuf, 0) else -1
                        if (size < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            decInputDone = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                if (!decOutputDone && pendingLen < 128 * 1024) {
                    val decOutIdx = decoder.dequeueOutputBuffer(decInfo, 2_000L)
                    when {
                        decOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val fmt = decoder.outputFormat
                            if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) inRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) inChannels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && fmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                pcmEncoding = fmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            }
                        }
                        decOutIdx >= 0 -> {
                            val decBuf = decoder.getOutputBuffer(decOutIdx)
                            if (decBuf != null && decInfo.size > 0) {
                                val shorts = AudioPcmUtils.extractInterleavedShorts(decBuf, decInfo.offset, decInfo.size, pcmEncoding)
                                scratchOut.reset()
                                resampler.processAndWrite(shorts, inRate, inChannels, scratchOut)
                                appendPcm(scratchOut.toByteArray())
                            }
                            decoder.releaseOutputBuffer(decOutIdx, false)
                            if ((decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                decOutputDone = true
                            }
                        }
                    }
                }

                if (!encInputDone && (pendingLen >= bytesPerFrame || decOutputDone)) {
                    val encInIdx = encoder.dequeueInputBuffer(2_000L)
                    if (encInIdx >= 0) {
                        val encInBuf = encoder.getInputBuffer(encInIdx)
                        val maxCap = encInBuf?.remaining() ?: 0
                        val alignedCap = maxCap - (maxCap % bytesPerFrame)
                        val chunkLen = min(pendingLen, alignedCap)
                        val alignedChunk = chunkLen - (chunkLen % bytesPerFrame)
                        if (encInBuf != null && alignedChunk > 0) {
                            encInBuf.clear()
                            encInBuf.put(pendingPcm, pendingOffset, alignedChunk)
                            val ptsUs = (fedFrames * 1_000_000L) / targetRate
                            fedFrames += alignedChunk / bytesPerFrame
                            encoder.queueInputBuffer(encInIdx, 0, alignedChunk, ptsUs, 0)
                            pendingOffset += alignedChunk
                            pendingLen -= alignedChunk
                            if (pendingLen == 0) pendingOffset = 0
                        } else {
                            val eosPts = (fedFrames * 1_000_000L) / targetRate
                            encoder.queueInputBuffer(encInIdx, 0, 0, eosPts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            encInputDone = true
                        }
                    }
                }

                drainEnc@ while (!encDone) {
                    val encOutIdx = encoder.dequeueOutputBuffer(encInfo, if (encInputDone) 5_000L else 1_000L)
                    when {
                        encOutIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> break@drainEnc
                        encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            muxTrackIdx = coordinator.registerTrack(encoder.outputFormat)
                        }
                        encOutIdx >= 0 -> {
                            val encOutBuf = encoder.getOutputBuffer(encOutIdx)
                            if ((encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                encInfo.size = 0
                            }
                            if (encOutBuf != null && encInfo.size > 0 && muxTrackIdx >= 0) {
                                coordinator.writeSample(muxTrackIdx, encOutBuf, encInfo)
                            }
                            encoder.releaseOutputBuffer(encOutIdx, false)
                            if ((encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                encDone = true
                            }
                        }
                    }
                }
            }
            if (muxTrackIdx < 0) {
                coordinator.onTrackEndedWithoutFormat()
            }
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { extractor.release() }
        }
    }

    private suspend fun checkWebmCancelled(jobId: String) {
        currentCoroutineContext().ensureActive()
        if (cancelledJobs.contains(jobId)) {
            throw CancellationException("Kullanıcı WebM kodlamasını iptal etti")
        }
    }

    /**
     * Video (VP9) ve ses (Opus) akışlarının MediaMuxer.start() öncesi güvenle senkronize edilmesini sağlar.
     */
    private class WebmTrackCoordinator(
        private val muxer: MediaMuxer,
        initialExpectedTracks: Int
    ) {
        private data class PendingSample(
            val trackIndex: Int,
            val data: ByteArray,
            val info: MediaCodec.BufferInfo
        )

        private val lock = Any()
        private var expectedTracks = initialExpectedTracks.coerceAtLeast(1)
        private var registeredTracks = 0
        private var started = false
        private val pending = mutableListOf<PendingSample>()

        fun registerTrack(format: MediaFormat): Int {
            synchronized(lock) {
                val idx = muxer.addTrack(format)
                registeredTracks++
                startAndFlushIfReadyLocked()
                return idx
            }
        }

        fun onTrackEndedWithoutFormat() {
            synchronized(lock) {
                if (expectedTracks > 1) {
                    expectedTracks--
                }
                startAndFlushIfReadyLocked()
            }
        }

        fun writeSample(trackIndex: Int, outBuf: ByteBuffer, info: MediaCodec.BufferInfo) {
            outBuf.position(info.offset)
            outBuf.limit(info.offset + info.size)
            synchronized(lock) {
                if (started) {
                    muxer.writeSampleData(trackIndex, outBuf, info)
                } else {
                    val copy = ByteArray(info.size)
                    outBuf.get(copy)
                    val copyInfo = MediaCodec.BufferInfo().apply {
                        set(0, copy.size, info.presentationTimeUs, info.flags)
                    }
                    pending.add(PendingSample(trackIndex, copy, copyInfo))
                }
            }
        }

        private fun startAndFlushIfReadyLocked() {
            if (!started && registeredTracks >= expectedTracks && registeredTracks > 0) {
                muxer.start()
                started = true
                pending.forEach { sample ->
                    muxer.writeSampleData(sample.trackIndex, ByteBuffer.wrap(sample.data), sample.info)
                }
                pending.clear()
            }
        }

        fun release() {
            synchronized(lock) {
                pending.clear()
                if (started) {
                    started = false
                    runCatching { muxer.stop() }
                }
                runCatching { muxer.release() }
            }
        }
    }
}
