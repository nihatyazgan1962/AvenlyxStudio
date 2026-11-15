package com.formatci.mediastudio.media.engine

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import com.formatci.mediastudio.core.error.StudioErrorCode
import com.formatci.mediastudio.core.error.StudioErrorMapper
import com.formatci.mediastudio.core.error.StudioException
import com.formatci.mediastudio.core.files.FileStorageHelper
import com.formatci.mediastudio.domain.model.AudioFitMode
import com.formatci.mediastudio.domain.model.EngineCapabilities
import com.formatci.mediastudio.domain.model.JobEstimate
import com.formatci.mediastudio.domain.model.MediaFormatUtils
import com.formatci.mediastudio.domain.model.MediaInfo
import com.formatci.mediastudio.domain.model.MediaInput
import com.formatci.mediastudio.domain.model.MediaJob
import com.formatci.mediastudio.domain.model.MediaJobEvent
import com.formatci.mediastudio.domain.model.MediaType
import com.formatci.mediastudio.domain.model.StudioTool
import com.formatci.mediastudio.media.capability.EngineCapabilityRegistry
import com.formatci.mediastudio.media.inspector.MediaInspector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Saf ses dönüştürme, kesme, birleştirme, iki sesi miksleme, fade-in/out, ses seviyesi,
 * mono/stereo kanal dönüşümü ve videodan WAV/OPUS/FLAC/M4A ses çıkarma için
 * Android MediaExtractor + MediaCodec + MediaMuxer + RIFF WAV yazıcısı motoru.
 */
class AndroidPlatformEngine(
    private val context: Context,
    private val fileHelper: FileStorageHelper,
    private val capabilityRegistry: EngineCapabilityRegistry,
    private val inspector: MediaInspector
) : MediaEngine {

    private val cancelledJobs = ConcurrentHashMap.newKeySet<String>()

    override suspend fun inspect(input: MediaInput): MediaInfo = inspector.inspect(input)

    override fun capabilities(): EngineCapabilities = capabilityRegistry.getCapabilities()

    override suspend fun extractFrameToFile(uriString: String, positionMs: Long): Result<String> {
        return inspector.extractFrameToFile(uriString, positionMs)
    }

    override suspend fun estimate(job: MediaJob): JobEstimate = withContext(Dispatchers.IO) {
        val warnings = mutableListOf<String>()
        var totalDurationMs = 0L
        var totalInputBytes = 0L

        job.inputs.forEachIndexed { idx, input ->
            totalInputBytes += input.sizeBytes
            val info = runCatching { inspector.inspect(input) }.getOrNull()
            if (info != null) {
                if (info.isExtensionMismatch && info.mismatchWarning != null) {
                    warnings.add(info.mismatchWarning)
                }
                val dur = if (idx == 0 && job.settings.trimEndMs != null && job.settings.trimEndMs > job.settings.trimStartMs) {
                    job.settings.trimEndMs - job.settings.trimStartMs
                } else {
                    (info.durationMs - (if (idx == 0) job.settings.trimStartMs else 0L)).coerceAtLeast(500L)
                }
                if (job.settings.mixAudioInsteadOfConcat && idx > 0) {
                    if (dur > totalDurationMs) totalDurationMs = dur
                } else {
                    totalDurationMs += dur
                }
            }
        }

        if (totalDurationMs <= 0L) totalDurationMs = 15_000L

        val outExt = resolveAudioOutputFormat(job.settings.outputFormat)
        val estOutBytes = MediaFormatUtils.estimateOutputBytes(
            tool = job.tool,
            durationMs = totalDurationMs,
            inputSizeBytes = totalInputBytes,
            settings = job.settings.copy(outputFormat = outExt)
        )
        // Ara PCM dosyası için geçici depolama ihtiyacını hesaba kat
        val pcmApproxBytes = (totalDurationMs / 1000.0 * job.settings.audioSampleRateHz * job.settings.audioChannels * 2).toLong()
        val requiredFree = (estOutBytes + pcmApproxBytes + 8L * 1024L * 1024L).coerceAtLeast(12L * 1024L * 1024L)
        val hasEnough = fileHelper.hasEnoughStorage(requiredFree)
        if (!hasEnough) {
            warnings.add("Geçici PCM ve ses çıktısı için depolama alanı yetersiz olabilir.")
        }

        JobEstimate(
            estimatedOutputBytes = estOutBytes,
            estimatedDurationMs = totalDurationMs,
            estimatedProcessingTimeMs = (totalDurationMs / 4L).coerceAtLeast(1_500L),
            requiredFreeSpaceBytes = requiredFree,
            hasEnoughStorage = hasEnough,
            warnings = warnings
        )
    }

    override fun execute(job: MediaJob): Flow<MediaJobEvent> = flow {
        cancelledJobs.remove(job.id)
        emit(MediaJobEvent.Started(job.id, "Ses akışları çözümleniyor..."))

        val tempFiles = mutableListOf<File>()
        try {
            validateJob(job)
            val est = estimate(job)
            fileHelper.ensureEnoughStorage(est.requiredFreeSpaceBytes)

            val settings = job.settings
            val outExt = resolveAudioOutputFormat(settings.outputFormat)
            if (!capabilityRegistry.isAudioOutputSupported(outExt)) {
                throw StudioException(
                    StudioErrorCode.NO_ENCODER,
                    "Bu cihazda ${outExt.uppercase(Locale.ROOT)} ses kodlayıcısı desteklenmiyor."
                )
            }

            val targetSampleRate = when (outExt) {
                "opus" -> 48_000
                else -> settings.audioSampleRateHz.coerceIn(8_000, 96_000)
            }
            val targetChannels = settings.audioChannels.coerceIn(1, 2)

            val isMixMode = job.tool == StudioTool.AUDIO_EDIT_MERGE &&
                (settings.mixAudioInsteadOfConcat || !settings.secondaryAudioUri.isNullOrBlank()) &&
                (job.inputs.size >= 2 || !settings.secondaryAudioUri.isNullOrBlank())

            val masterPcmFile = fileHelper.createTempOutputFile("pcm_master_${job.id.take(6)}", "raw")
                .also { tempFiles.add(it) }

            if (isMixMode) {
                val primaryInput = job.inputs.first()
                val secondaryUri = settings.secondaryAudioUri?.takeIf { it.isNotBlank() }
                    ?: job.inputs[1].uriString
                val secondaryInput = if (!settings.secondaryAudioUri.isNullOrBlank()) {
                    MediaInput(uriString = secondaryUri, displayName = "ikinci_ses", mediaType = MediaType.AUDIO)
                } else {
                    job.inputs[1]
                }

                val pcmA = fileHelper.createTempOutputFile("pcm_a_${job.id.take(6)}", "raw")
                    .also { tempFiles.add(it) }
                val pcmB = fileHelper.createTempOutputFile("pcm_b_${job.id.take(6)}", "raw")
                    .also { tempFiles.add(it) }

                emit(MediaJobEvent.Progress(job.id, 0.05f, isIndeterminate = false, message = "Birinci ses çözümleniyor..."))
                decodeToPcmFile(
                    jobId = job.id,
                    uri = Uri.parse(primaryInput.uriString),
                    outFile = pcmA,
                    targetSampleRate = targetSampleRate,
                    targetChannels = targetChannels,
                    trimStartMs = settings.trimStartMs,
                    trimEndMs = settings.trimEndMs
                ) { frac ->
                    emit(
                        MediaJobEvent.Progress(
                            jobId = job.id,
                            progress = 0.05f + frac * 0.25f,
                            isIndeterminate = false,
                            message = "Birinci ses PCM'e dönüştürülüyor (%${(frac * 100).toInt()})"
                        )
                    )
                }

                emit(MediaJobEvent.Progress(job.id, 0.30f, isIndeterminate = false, message = "İkinci ses çözümleniyor..."))
                decodeToPcmFile(
                    jobId = job.id,
                    uri = Uri.parse(secondaryInput.uriString),
                    outFile = pcmB,
                    targetSampleRate = targetSampleRate,
                    targetChannels = targetChannels,
                    trimStartMs = 0L,
                    trimEndMs = null
                ) { frac ->
                    emit(
                        MediaJobEvent.Progress(
                            jobId = job.id,
                            progress = 0.30f + frac * 0.25f,
                            isIndeterminate = false,
                            message = "İkinci ses PCM'e dönüştürülüyor (%${(frac * 100).toInt()})"
                        )
                    )
                }

                emit(MediaJobEvent.Progress(job.id, 0.56f, isIndeterminate = false, message = "Ses katmanları miksleniyor..."))
                mixPcmFiles(
                    jobId = job.id,
                    pcmA = pcmA,
                    pcmB = pcmB,
                    outPcm = masterPcmFile,
                    weightA = 1.0f,
                    weightB = settings.secondaryAudioVolume.coerceIn(0f, 2.0f),
                    fitMode = settings.audioFitMode
                )
            } else {
                // Tek dosya dönüşüm/kesme, videodan ses çıkarma veya çoklu ses birleştirme
                val sources = job.inputs
                val totalSources = sources.size.coerceAtLeast(1)
                BufferedOutputStream(FileOutputStream(masterPcmFile), 64 * 1024).use { pcmOut ->
                    sources.forEachIndexed { idx, input ->
                        checkCancelled(job.id)
                        val startTrim = if (idx == 0) settings.trimStartMs else 0L
                        val endTrim = if (idx == 0) settings.trimEndMs else null
                        val baseProgress = 0.04f + (idx.toFloat() / totalSources) * 0.50f
                        val spanProgress = 0.50f / totalSources

                        decodeToPcmStream(
                            jobId = job.id,
                            uri = Uri.parse(input.uriString),
                            pcmOut = pcmOut,
                            targetSampleRate = targetSampleRate,
                            targetChannels = targetChannels,
                            trimStartMs = startTrim,
                            trimEndMs = endTrim
                        ) { frac ->
                            val pct = (baseProgress + frac * spanProgress).coerceIn(0.04f, 0.54f)
                            emit(
                                MediaJobEvent.Progress(
                                    jobId = job.id,
                                    progress = pct,
                                    isIndeterminate = false,
                                    message = if (totalSources > 1) {
                                        "Parça ${idx + 1}/$totalSources çözümleniyor (%${(pct * 100).toInt()})"
                                    } else {
                                        "Ses akışı çözümleniyor (%${(pct * 100).toInt()})"
                                    }
                                )
                            )
                        }
                    }
                    pcmOut.flush()
                }
            }

            checkCancelled(job.id)
            val totalPcmBytes = masterPcmFile.length()
            val bytesPerFrame = targetChannels * 2L
            val totalFrames = (totalPcmBytes / bytesPerFrame).coerceAtLeast(1L)
            val actualDurationMs = (totalFrames * 1000L) / targetSampleRate

            if (totalPcmBytes <= 0L) {
                throw StudioException(StudioErrorCode.CORRUPT_MEDIA, "Çözümlenen ses verisi boş.")
            }

            val tempOutFile = fileHelper.createTempOutputFile("aud_${job.id.take(6)}", outExt)
                .also { tempFiles.add(it) }

            when (outExt) {
                "wav" -> {
                    emit(MediaJobEvent.Progress(job.id, 0.58f, isIndeterminate = false, message = "WAV dosyası yazılıyor..."))
                    writePcmToWav(
                        jobId = job.id,
                        pcmFile = masterPcmFile,
                        wavFile = tempOutFile,
                        sampleRate = targetSampleRate,
                        channels = targetChannels,
                        volume = settings.audioVolume,
                        fadeInMs = settings.fadeInMs,
                        fadeOutMs = settings.fadeOutMs
                    ) { frac ->
                        emit(
                            MediaJobEvent.Progress(
                                jobId = job.id,
                                progress = 0.58f + frac * 0.36f,
                                isIndeterminate = false,
                                message = "WAV yazılıyor (%${((0.58f + frac * 0.36f) * 100).toInt()})"
                            )
                        )
                    }
                }

                "m4a" -> {
                    emit(MediaJobEvent.Progress(job.id, 0.58f, isIndeterminate = false, message = "M4A (AAC) kodlanıyor..."))
                    encodePcmWithMediaCodecMuxer(
                        jobId = job.id,
                        pcmFile = masterPcmFile,
                        outFile = tempOutFile,
                        mimeType = "audio/mp4a-latm",
                        muxerFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
                        sampleRate = targetSampleRate,
                        channels = targetChannels,
                        bitrate = settings.audioBitrateBps.coerceIn(64_000, 320_000),
                        volume = settings.audioVolume,
                        fadeInMs = settings.fadeInMs,
                        fadeOutMs = settings.fadeOutMs
                    ) { frac ->
                        val p = 0.58f + frac * 0.36f
                        emit(MediaJobEvent.Progress(job.id, p, false, "M4A kodlanıyor (%${(p * 100).toInt()})"))
                    }
                }

                "opus" -> {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                        throw StudioException(StudioErrorCode.NO_ENCODER, "OPUS OGG paketleme Android 10+ gerektirir.")
                    }
                    emit(MediaJobEvent.Progress(job.id, 0.58f, isIndeterminate = false, message = "OPUS kodlanıyor..."))
                    encodePcmWithMediaCodecMuxer(
                        jobId = job.id,
                        pcmFile = masterPcmFile,
                        outFile = tempOutFile,
                        mimeType = "audio/opus",
                        muxerFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG,
                        sampleRate = targetSampleRate,
                        channels = targetChannels,
                        bitrate = settings.audioBitrateBps.coerceIn(32_000, 256_000),
                        volume = settings.audioVolume,
                        fadeInMs = settings.fadeInMs,
                        fadeOutMs = settings.fadeOutMs
                    ) { frac ->
                        val p = 0.58f + frac * 0.36f
                        emit(MediaJobEvent.Progress(job.id, p, false, "OPUS kodlanıyor (%${(p * 100).toInt()})"))
                    }
                }

                "flac" -> {
                    emit(MediaJobEvent.Progress(job.id, 0.58f, isIndeterminate = false, message = "FLAC kodlanıyor..."))
                    encodePcmToRawFlac(
                        jobId = job.id,
                        pcmFile = masterPcmFile,
                        outFile = tempOutFile,
                        sampleRate = targetSampleRate,
                        channels = targetChannels,
                        volume = settings.audioVolume,
                        fadeInMs = settings.fadeInMs,
                        fadeOutMs = settings.fadeOutMs
                    ) { frac ->
                        val p = 0.58f + frac * 0.36f
                        emit(MediaJobEvent.Progress(job.id, p, false, "FLAC kodlanıyor (%${(p * 100).toInt()})"))
                    }
                }

                else -> throw StudioException(StudioErrorCode.NO_ENCODER, "Desteklenmeyen ses çıktı formatı: $outExt")
            }

            checkCancelled(job.id)
            emit(MediaJobEvent.Progress(job.id, 0.96f, isIndeterminate = false, message = "Çıktı kaydediliyor..."))

            val baseName = settings.outputFileName.ifBlank {
                job.inputs.firstOrNull()?.displayName ?: job.title
            }
            val saved = fileHelper.saveTempFileToPublicStorage(
                tempFile = tempOutFile,
                desiredBaseName = baseName,
                extension = outExt,
                mediaType = MediaType.AUDIO
            )

            emit(
                MediaJobEvent.Completed(
                    jobId = job.id,
                    outputUriString = saved.uriString,
                    outputDisplayName = saved.displayName,
                    outputMimeType = MediaFormatUtils.mimeTypeForExtension(outExt),
                    outputFormat = outExt,
                    outputSizeBytes = saved.sizeBytes,
                    durationMs = actualDurationMs,
                    width = 0,
                    height = 0,
                    mediaType = MediaType.AUDIO
                )
            )
        } catch (_: CancellationException) {
            cancelledJobs.add(job.id)
            emit(MediaJobEvent.Cancelled(job.id))
        } catch (t: Throwable) {
            if (cancelledJobs.contains(job.id)) {
                emit(MediaJobEvent.Cancelled(job.id))
            } else {
                val mapped = StudioErrorMapper.fromThrowable(t)
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
            cancelledJobs.remove(job.id)
            tempFiles.forEach { fileHelper.deleteFileQuietly(it) }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun cancel(jobId: String) {
        cancelledJobs.add(jobId)
    }

    private suspend fun decodeToPcmFile(
        jobId: String,
        uri: Uri,
        outFile: File,
        targetSampleRate: Int,
        targetChannels: Int,
        trimStartMs: Long,
        trimEndMs: Long?,
        onProgress: suspend (Float) -> Unit
    ) {
        BufferedOutputStream(FileOutputStream(outFile), 64 * 1024).use { out ->
            decodeToPcmStream(
                jobId = jobId,
                uri = uri,
                pcmOut = out,
                targetSampleRate = targetSampleRate,
                targetChannels = targetChannels,
                trimStartMs = trimStartMs,
                trimEndMs = trimEndMs,
                onProgress = onProgress
            )
            out.flush()
        }
    }

    private suspend fun decodeToPcmStream(
        jobId: String,
        uri: Uri,
        pcmOut: BufferedOutputStream,
        targetSampleRate: Int,
        targetChannels: Int,
        trimStartMs: Long,
        trimEndMs: Long?,
        onProgress: suspend (Float) -> Unit
    ) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            extractor.setDataSource(context, uri, null)
            var audioTrackIndex = -1
            var trackFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    trackFormat = fmt
                    break
                }
            }
            if (audioTrackIndex < 0 || trackFormat == null) {
                throw StudioException(StudioErrorCode.CORRUPT_MEDIA, "Dosyada ses kanalı bulunamadı.")
            }

            extractor.selectTrack(audioTrackIndex)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME)
                ?: throw StudioException(StudioErrorCode.CODEC_UNSUPPORTED, "Ses MIME türü boş")

            val trackDurationUs = if (trackFormat.containsKey(MediaFormat.KEY_DURATION)) {
                trackFormat.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(1_000_000L)
            } else {
                30_000_000L
            }

            val trimStartUs = trimStartMs.coerceAtLeast(0L) * 1000L
            val trimEndUs = if (trimEndMs != null && trimEndMs > trimStartMs) trimEndMs * 1000L else Long.MAX_VALUE
            val expectedSpanUs = (min(trackDurationUs, trimEndUs) - trimStartUs).coerceAtLeast(500_000L)

            if (trimStartUs > 0L) {
                extractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }

            var inSampleRate = if (trackFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                44_100
            }
            var inChannels = if (trackFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else {
                2
            }
            var pcmEncoding = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                trackFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)
            ) {
                trackFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(trackFormat, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            val timeoutUs = 10_000L
            var inputDone = false
            var outputDone = false
            var lastReportedPct = -1f
            val resampler = AudioPcmUtils.StreamingLinearResampler(targetSampleRate, targetChannels)

            while (!outputDone) {
                checkCancelled(jobId)

                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(timeoutUs)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        val sampleSize = if (inBuf != null) extractor.readSampleData(inBuf, 0) else -1
                        val sampleTime = extractor.sampleTime
                        if (sampleSize < 0 || sampleTime > trimEndUs + 200_000L) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sampleSize, sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, timeoutUs)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val fmt = codec.outputFormat
                        if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            inSampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            inChannels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                            fmt.containsKey(MediaFormat.KEY_PCM_ENCODING)
                        ) {
                            pcmEncoding = fmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                    }

                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0) {
                            val ptsUs = info.presentationTimeUs
                            if (ptsUs >= trimStartUs - 20_000L && ptsUs <= trimEndUs) {
                                val normalizedShorts = AudioPcmUtils.extractInterleavedShorts(outBuf, info.offset, info.size, pcmEncoding)
                                resampler.processAndWrite(
                                    inputShorts = normalizedShorts,
                                    inSampleRate = inSampleRate,
                                    inChannels = inChannels,
                                    outStream = pcmOut
                                )
                            }
                            val frac = ((ptsUs - trimStartUs).toFloat() / expectedSpanUs.toFloat()).coerceIn(0f, 1f)
                            if (frac - lastReportedPct >= 0.04f) {
                                lastReportedPct = frac
                                onProgress(frac)
                            }
                            if (ptsUs >= trimEndUs) {
                                outputDone = true
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }
            onProgress(1f)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private suspend fun mixPcmFiles(
        jobId: String,
        pcmA: File,
        pcmB: File,
        outPcm: File,
        weightA: Float,
        weightB: Float,
        fitMode: AudioFitMode
    ) {
        val lenA = pcmA.length()
        val lenB = pcmB.length()
        val totalOutBytes = when (fitMode) {
            AudioFitMode.TRIM, AudioFitMode.LOOP -> lenA
            AudioFitMode.PAD_SILENCE -> maxOf(lenA, lenB)
        }.coerceAtLeast(2L)

        val bufSize = 16 * 1024
        val bufA = ByteArray(bufSize)
        val bufB = ByteArray(bufSize)
        val outBuf = ByteArray(bufSize)

        FileInputStream(pcmA).use { inA ->
            var inB = FileInputStream(pcmB)
            try {
                BufferedOutputStream(FileOutputStream(outPcm), 64 * 1024).use { out ->
                    var written = 0L
                    while (written < totalOutBytes) {
                        checkCancelled(jobId)
                        val chunk = min(bufSize.toLong(), totalOutBytes - written).toInt()
                        val readA = readFullyOrLess(inA, bufA, chunk)
                        val readB = if (fitMode == AudioFitMode.LOOP && lenB > 0L) {
                            var filled = 0
                            while (filled < chunk) {
                                val r = inB.read(bufB, filled, chunk - filled)
                                if (r <= 0) {
                                    inB.close()
                                    inB = FileInputStream(pcmB)
                                    val r2 = inB.read(bufB, filled, chunk - filled)
                                    if (r2 <= 0) break
                                    filled += r2
                                } else {
                                    filled += r
                                }
                            }
                            filled
                        } else {
                            readFullyOrLess(inB, bufB, chunk)
                        }

                        val samples = chunk / 2
                        for (i in 0 until samples) {
                            val idx = i * 2
                            val sA = if (idx + 1 < readA) {
                                ((bufA[idx].toInt() and 0xFF) or (bufA[idx + 1].toInt() shl 8)).toShort().toInt()
                            } else 0
                            val sB = if (idx + 1 < readB) {
                                ((bufB[idx].toInt() and 0xFF) or (bufB[idx + 1].toInt() shl 8)).toShort().toInt()
                            } else 0

                            val mixed = (sA * weightA + sB * weightB).roundToInt()
                                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                            outBuf[idx] = (mixed and 0xFF).toByte()
                            outBuf[idx + 1] = ((mixed ushr 8) and 0xFF).toByte()
                        }
                        out.write(outBuf, 0, samples * 2)
                        written += samples * 2L
                    }
                    out.flush()
                }
            } finally {
                runCatching { inB.close() }
            }
        }
    }

    private suspend fun writePcmToWav(
        jobId: String,
        pcmFile: File,
        wavFile: File,
        sampleRate: Int,
        channels: Int,
        volume: Float,
        fadeInMs: Long,
        fadeOutMs: Long,
        onProgress: suspend (Float) -> Unit
    ) {
        val bytesPerFrame = channels * 2L
        val totalFrames = (pcmFile.length() / bytesPerFrame).coerceAtLeast(1L)
        val fadeInFrames = (fadeInMs.coerceAtLeast(0L) * sampleRate) / 1000L
        val fadeOutFrames = (fadeOutMs.coerceAtLeast(0L) * sampleRate) / 1000L

        BufferedInputStream(FileInputStream(pcmFile), 64 * 1024).use { input ->
            BufferedOutputStream(FileOutputStream(wavFile), 64 * 1024).use { output ->
                // 44 byte RIFF/WAV başlığı için yer tutucu yaz
                output.write(ByteArray(44))

                val buffer = ByteArray(16 * 1024)
                var frameIndex = 0L
                var totalDataBytes = 0L
                var lastPct = -1f

                while (true) {
                    checkCancelled(jobId)
                    val read = input.read(buffer)
                    if (read <= 0) break
                    val usableBytes = read - (read % (channels * 2))
                    if (usableBytes <= 0) continue

                    val framesInChunk = usableBytes / (channels * 2)
                    applyGainAndFadesInPlace(
                        pcmBytes = buffer,
                        usableBytes = usableBytes,
                        channels = channels,
                        startFrameIndex = frameIndex,
                        totalFrames = totalFrames,
                        baseVolume = volume,
                        fadeInFrames = fadeInFrames,
                        fadeOutFrames = fadeOutFrames
                    )
                    output.write(buffer, 0, usableBytes)
                    frameIndex += framesInChunk
                    totalDataBytes += usableBytes

                    val frac = (frameIndex.toFloat() / totalFrames.toFloat()).coerceIn(0f, 1f)
                    if (frac - lastPct >= 0.05f) {
                        lastPct = frac
                        onProgress(frac)
                    }
                }
                output.flush()
                writeWavHeader(wavFile, totalDataBytes, sampleRate, channels, bitsPerSample = 16)
            }
        }
        onProgress(1f)
    }

    private fun writeWavHeader(
        wavFile: File,
        dataBytes: Long,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ) {
        val byteRate = sampleRate.toLong() * channels * bitsPerSample / 8L
        val blockAlign = (channels * bitsPerSample / 8).toShort()
        val riffChunkSize = (36L + dataBytes).coerceAtMost(0xFFFFFFFFL)

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put('R'.code.toByte()); put('I'.code.toByte()); put('F'.code.toByte()); put('F'.code.toByte())
            putInt(riffChunkSize.toInt())
            put('W'.code.toByte()); put('A'.code.toByte()); put('V'.code.toByte()); put('E'.code.toByte())
            put('f'.code.toByte()); put('m'.code.toByte()); put('t'.code.toByte()); put(' '.code.toByte())
            putInt(16) // PCM alt başlık boyutu
            putShort(1) // AudioFormat = 1 (PCM)
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate.toInt())
            putShort(blockAlign)
            putShort(bitsPerSample.toShort())
            put('d'.code.toByte()); put('a'.code.toByte()); put('t'.code.toByte()); put('a'.code.toByte())
            putInt(dataBytes.coerceAtMost(0xFFFFFFFFL).toInt())
        }

        RandomAccessFile(wavFile, "rw").use { raf ->
            raf.seek(0L)
            raf.write(header.array())
        }
    }

    private suspend fun encodePcmWithMediaCodecMuxer(
        jobId: String,
        pcmFile: File,
        outFile: File,
        mimeType: String,
        muxerFormat: Int,
        sampleRate: Int,
        channels: Int,
        bitrate: Int,
        volume: Float,
        fadeInMs: Long,
        fadeOutMs: Long,
        onProgress: suspend (Float) -> Unit
    ) {
        val bytesPerFrame = channels * 2L
        val totalFrames = (pcmFile.length() / bytesPerFrame).coerceAtLeast(1L)
        val fadeInFrames = (fadeInMs.coerceAtLeast(0L) * sampleRate) / 1000L
        val fadeOutFrames = (fadeOutMs.coerceAtLeast(0L) * sampleRate) / 1000L

        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        try {
            val format = MediaFormat.createAudioFormat(mimeType, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                if (mimeType == "audio/mp4a-latm") {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                }
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32 * 1024)
            }

            encoder = MediaCodec.createEncoderByType(mimeType)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            muxer = MediaMuxer(outFile.absolutePath, muxerFormat)
            var trackIndex = -1
            val bufferInfo = MediaCodec.BufferInfo()
            val timeoutUs = 10_000L

            BufferedInputStream(FileInputStream(pcmFile), 64 * 1024).use { pcmIn ->
                val chunkBuf = ByteArray(8 * 1024)
                var inputDone = false
                var outputDone = false
                var fedFrames = 0L
                var lastPct = -1f

                while (!outputDone) {
                    checkCancelled(jobId)

                    if (!inputDone) {
                        val inIdx = encoder.dequeueInputBuffer(timeoutUs)
                        if (inIdx >= 0) {
                            val inBuf = encoder.getInputBuffer(inIdx)
                            if (inBuf == null) {
                                encoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                inBuf.clear()
                                val maxRead = min(chunkBuf.size, inBuf.remaining())
                                val alignedMax = maxRead - (maxRead % (channels * 2))
                                val read = if (alignedMax > 0) pcmIn.read(chunkBuf, 0, alignedMax) else -1
                                if (read <= 0) {
                                    val eosPts = (fedFrames * 1_000_000L) / sampleRate
                                    encoder.queueInputBuffer(inIdx, 0, 0, eosPts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                } else {
                                    val usable = read - (read % (channels * 2))
                                    applyGainAndFadesInPlace(
                                        pcmBytes = chunkBuf,
                                        usableBytes = usable,
                                        channels = channels,
                                        startFrameIndex = fedFrames,
                                        totalFrames = totalFrames,
                                        baseVolume = volume,
                                        fadeInFrames = fadeInFrames,
                                        fadeOutFrames = fadeOutFrames
                                    )
                                    inBuf.put(chunkBuf, 0, usable)
                                    val ptsUs = (fedFrames * 1_000_000L) / sampleRate
                                    fedFrames += usable / bytesPerFrame
                                    encoder.queueInputBuffer(inIdx, 0, usable, ptsUs, 0)

                                    val frac = (fedFrames.toFloat() / totalFrames.toFloat()).coerceIn(0f, 1f)
                                    if (frac - lastPct >= 0.05f) {
                                        lastPct = frac
                                        onProgress(frac)
                                    }
                                }
                            }
                        }
                    }

                    val outIdx = encoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (muxerStarted) {
                                throw StudioException(StudioErrorCode.OUTPUT_CREATE_FAILED, "Encoder output format iki kez değişti")
                            }
                            trackIndex = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }

                        outIdx >= 0 -> {
                            val outBuf = encoder.getOutputBuffer(outIdx)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                bufferInfo.size = 0
                            }
                            if (outBuf != null && bufferInfo.size > 0 && muxerStarted && trackIndex >= 0) {
                                outBuf.position(bufferInfo.offset)
                                outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(trackIndex, outBuf, bufferInfo)
                            }
                            encoder.releaseOutputBuffer(outIdx, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                outputDone = true
                            }
                        }
                    }
                }
            }
            onProgress(1f)
        } finally {
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            if (muxerStarted) {
                runCatching { muxer?.stop() }
            }
            runCatching { muxer?.release() }
        }
    }

    private suspend fun encodePcmToRawFlac(
        jobId: String,
        pcmFile: File,
        outFile: File,
        sampleRate: Int,
        channels: Int,
        volume: Float,
        fadeInMs: Long,
        fadeOutMs: Long,
        onProgress: suspend (Float) -> Unit
    ) {
        val bytesPerFrame = channels * 2L
        val totalFrames = (pcmFile.length() / bytesPerFrame).coerceAtLeast(1L)
        val fadeInFrames = (fadeInMs.coerceAtLeast(0L) * sampleRate) / 1000L
        val fadeOutFrames = (fadeOutMs.coerceAtLeast(0L) * sampleRate) / 1000L

        var encoder: MediaCodec? = null
        try {
            val format = MediaFormat.createAudioFormat("audio/flac", sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, 5)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32 * 1024)
            }
            encoder = MediaCodec.createEncoderByType("audio/flac")
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            val info = MediaCodec.BufferInfo()
            val timeoutUs = 10_000L
            BufferedInputStream(FileInputStream(pcmFile), 64 * 1024).use { pcmIn ->
                BufferedOutputStream(FileOutputStream(outFile), 64 * 1024).use { flacOut ->
                    val chunkBuf = ByteArray(8 * 1024)
                    val outTmp = ByteArray(32 * 1024)
                    var inputDone = false
                    var outputDone = false
                    var fedFrames = 0L
                    var lastPct = -1f

                    while (!outputDone) {
                        checkCancelled(jobId)

                        if (!inputDone) {
                            val inIdx = encoder.dequeueInputBuffer(timeoutUs)
                            if (inIdx >= 0) {
                                val inBuf = encoder.getInputBuffer(inIdx)
                                if (inBuf == null) {
                                    encoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                } else {
                                    inBuf.clear()
                                    val maxRead = min(chunkBuf.size, inBuf.remaining())
                                    val alignedMax = maxRead - (maxRead % (channels * 2))
                                    val read = if (alignedMax > 0) pcmIn.read(chunkBuf, 0, alignedMax) else -1
                                    if (read <= 0) {
                                        val eosPts = (fedFrames * 1_000_000L) / sampleRate
                                        encoder.queueInputBuffer(inIdx, 0, 0, eosPts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                        inputDone = true
                                    } else {
                                        val usable = read - (read % (channels * 2))
                                        applyGainAndFadesInPlace(
                                            pcmBytes = chunkBuf,
                                            usableBytes = usable,
                                            channels = channels,
                                            startFrameIndex = fedFrames,
                                            totalFrames = totalFrames,
                                            baseVolume = volume,
                                            fadeInFrames = fadeInFrames,
                                            fadeOutFrames = fadeOutFrames
                                        )
                                        inBuf.put(chunkBuf, 0, usable)
                                        val ptsUs = (fedFrames * 1_000_000L) / sampleRate
                                        fedFrames += usable / bytesPerFrame
                                        encoder.queueInputBuffer(inIdx, 0, usable, ptsUs, 0)

                                        val frac = (fedFrames.toFloat() / totalFrames.toFloat()).coerceIn(0f, 1f)
                                        if (frac - lastPct >= 0.05f) {
                                            lastPct = frac
                                            onProgress(frac)
                                        }
                                    }
                                }
                            }
                        }

                        val outIdx = encoder.dequeueOutputBuffer(info, timeoutUs)
                        if (outIdx >= 0) {
                            val outBuf = encoder.getOutputBuffer(outIdx)
                            if (outBuf != null && info.size > 0) {
                                outBuf.position(info.offset)
                                outBuf.limit(info.offset + info.size)
                                var remaining = info.size
                                while (remaining > 0) {
                                    val take = min(remaining, outTmp.size)
                                    outBuf.get(outTmp, 0, take)
                                    flacOut.write(outTmp, 0, take)
                                    remaining -= take
                                }
                            }
                            encoder.releaseOutputBuffer(outIdx, false)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                outputDone = true
                            }
                        }
                    }
                    flacOut.flush()
                }
            }
            onProgress(1f)
        } finally {
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
        }
    }

    private fun applyGainAndFadesInPlace(
        pcmBytes: ByteArray,
        usableBytes: Int,
        channels: Int,
        startFrameIndex: Long,
        totalFrames: Long,
        baseVolume: Float,
        fadeInFrames: Long,
        fadeOutFrames: Long
    ) {
        val safeVol = baseVolume.coerceIn(0f, 3.0f)
        val needsScale = safeVol != 1.0f || fadeInFrames > 0L || fadeOutFrames > 0L
        if (!needsScale) return

        val frames = usableBytes / (channels * 2)
        for (f in 0 until frames) {
            val globalFrame = startFrameIndex + f
            var gain = safeVol
            if (fadeInFrames > 0L && globalFrame < fadeInFrames) {
                gain *= (globalFrame.toFloat() / fadeInFrames.toFloat()).coerceIn(0f, 1f)
            }
            if (fadeOutFrames > 0L && globalFrame >= totalFrames - fadeOutFrames) {
                val rem = (totalFrames - globalFrame).coerceAtLeast(0L)
                gain *= (rem.toFloat() / fadeOutFrames.toFloat()).coerceIn(0f, 1f)
            }
            if (gain != 1.0f) {
                for (c in 0 until channels) {
                    val idx = (f * channels + c) * 2
                    val raw = ((pcmBytes[idx].toInt() and 0xFF) or (pcmBytes[idx + 1].toInt() shl 8)).toShort().toInt()
                    val scaled = (raw * gain).roundToInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    pcmBytes[idx] = (scaled and 0xFF).toByte()
                    pcmBytes[idx + 1] = ((scaled ushr 8) and 0xFF).toByte()
                }
            }
        }
    }

    private fun readFullyOrLess(input: FileInputStream, buf: ByteArray, length: Int): Int {
        var total = 0
        while (total < length) {
            val r = input.read(buf, total, length - total)
            if (r <= 0) break
            total += r
        }
        return total
    }

    private fun resolveAudioOutputFormat(raw: String): String {
        return when (val ext = raw.lowercase(Locale.ROOT).trimStart('.')) {
            "wav", "m4a", "opus", "flac" -> ext
            "aac" -> "m4a"
            else -> "m4a"
        }
    }

    private fun validateJob(job: MediaJob) {
        if (job.inputs.isEmpty()) {
            throw StudioException(StudioErrorCode.FILE_NOT_FOUND, "Ses girişi seçilmedi")
        }
        job.inputs.forEach { input ->
            val uri = runCatching { Uri.parse(input.uriString) }.getOrNull()
                ?: throw StudioException(StudioErrorCode.FILE_NOT_FOUND, input.uriString)
            if (!fileHelper.canReadUri(uri)) {
                throw StudioException(StudioErrorCode.URI_PERMISSION_LOST, input.uriString)
            }
        }
        job.settings.secondaryAudioUri?.takeIf { it.isNotBlank() }?.let { raw ->
            val uri = Uri.parse(raw)
            if (!fileHelper.canReadUri(uri)) {
                throw StudioException(StudioErrorCode.URI_PERMISSION_LOST, raw)
            }
        }
        val trimEnd = job.settings.trimEndMs
        if (trimEnd != null && trimEnd <= job.settings.trimStartMs + 100L) {
            throw StudioException(
                StudioErrorCode.DURATION_MISMATCH,
                "Ses kesme aralığı geçersiz (${job.settings.trimStartMs}ms - ${trimEnd}ms)"
            )
        }
    }

    private suspend fun checkCancelled(jobId: String) {
        currentCoroutineContext().ensureActive()
        if (cancelledJobs.contains(jobId)) {
            throw CancellationException("Kullanıcı ses işlemini iptal etti")
        }
    }
}

/**
 * AndroidPlatformEngine ve Media3Engine WebM/Opus dönüşümünün paylaştığı PCM çıkarıcı ve doğrusal yeniden örnekleyici.
 */
internal object AudioPcmUtils {
    fun extractInterleavedShorts(
        buffer: ByteBuffer,
        offset: Int,
        size: Int,
        pcmEncoding: Int
    ): ShortArray {
        val dup = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        dup.position(offset)
        dup.limit(min(buffer.capacity(), offset + size))

        return when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val count = dup.remaining() / 4
                ShortArray(count) {
                    (dup.float.coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()
                }
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                val count = dup.remaining()
                ShortArray(count) {
                    (((dup.get().toInt() and 0xFF) - 128) shl 8).toShort()
                }
            }
            else -> {
                val count = dup.remaining() / 2
                ShortArray(count) { dup.short }
            }
        }
    }

    class StreamingLinearResampler(
        private val targetSampleRate: Int,
        private val targetChannels: Int
    ) {
        private var prevFrame = FloatArray(targetChannels)
        private var hasPrev = false
        private var fractionalPos = 0.0
        private var outByteBuf = ByteArray(16 * 1024)

        fun processAndWrite(
            inputShorts: ShortArray,
            inSampleRate: Int,
            inChannels: Int,
            outStream: OutputStream
        ) {
            if (inChannels <= 0 || inputShorts.isEmpty()) return
            val inFrames = inputShorts.size / inChannels
            if (inFrames <= 0) return

            if (inSampleRate == targetSampleRate && inChannels == targetChannels) {
                val neededBytes = inputShorts.size * 2
                if (outByteBuf.size < neededBytes) outByteBuf = ByteArray(neededBytes)
                for (i in inputShorts.indices) {
                    val v = inputShorts[i].toInt()
                    outByteBuf[i * 2] = (v and 0xFF).toByte()
                    outByteBuf[i * 2 + 1] = ((v ushr 8) and 0xFF).toByte()
                }
                outStream.write(outByteBuf, 0, neededBytes)
                return
            }

            val converted = FloatArray(inFrames * targetChannels)
            for (f in 0 until inFrames) {
                val base = f * inChannels
                when {
                    inChannels == targetChannels -> {
                        for (c in 0 until targetChannels) converted[f * targetChannels + c] = inputShorts[base + c].toFloat()
                    }
                    inChannels == 1 && targetChannels == 2 -> {
                        val s = inputShorts[base].toFloat()
                        converted[f * 2] = s
                        converted[f * 2 + 1] = s
                    }
                    inChannels >= 2 && targetChannels == 1 -> {
                        var sum = 0f
                        for (c in 0 until inChannels) sum += inputShorts[base + c].toFloat()
                        converted[f] = sum / inChannels
                    }
                    else -> {
                        for (c in 0 until targetChannels) {
                            converted[f * targetChannels + c] = inputShorts[base + min(c, inChannels - 1)].toFloat()
                        }
                    }
                }
            }

            if (inSampleRate == targetSampleRate) {
                writeFloatFrames(converted, inFrames, outStream)
                return
            }

            val step = inSampleRate.toDouble() / targetSampleRate.toDouble()
            val estimatedOutFrames = ((inFrames / step) + 4).toInt()
            val neededBytes = estimatedOutFrames * targetChannels * 2
            if (outByteBuf.size < neededBytes) outByteBuf = ByteArray(neededBytes)

            var outBytePos = 0
            while (fractionalPos < inFrames - 1) {
                val idx0 = fractionalPos.toInt()
                val frac = (fractionalPos - idx0).toFloat()
                for (c in 0 until targetChannels) {
                    val s0 = if (idx0 < 0 && hasPrev) prevFrame[c] else converted[idx0.coerceAtLeast(0) * targetChannels + c]
                    val s1 = converted[(idx0 + 1).coerceAtMost(inFrames - 1) * targetChannels + c]
                    val interp = (s0 + (s1 - s0) * frac).roundToInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    outByteBuf[outBytePos++] = (interp and 0xFF).toByte()
                    outByteBuf[outBytePos++] = ((interp ushr 8) and 0xFF).toByte()
                }
                fractionalPos += step
            }
            if (outBytePos > 0) {
                outStream.write(outByteBuf, 0, outBytePos)
            }

            for (c in 0 until targetChannels) {
                prevFrame[c] = converted[(inFrames - 1) * targetChannels + c]
            }
            hasPrev = true
            fractionalPos -= inFrames
        }

        private fun writeFloatFrames(framesData: FloatArray, frameCount: Int, outStream: OutputStream) {
            val needed = frameCount * targetChannels * 2
            if (outByteBuf.size < needed) outByteBuf = ByteArray(needed)
            var outIdx = 0
            for (i in 0 until frameCount * targetChannels) {
                val s = framesData[i].roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                outByteBuf[outIdx++] = (s and 0xFF).toByte()
                outByteBuf[outIdx++] = ((s ushr 8) and 0xFF).toByte()
            }
            outStream.write(outByteBuf, 0, outIdx)
        }
    }
}
