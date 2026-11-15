package com.formatci.mediastudio.media.inspector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.formatci.mediastudio.core.error.StudioErrorCode
import com.formatci.mediastudio.core.error.StudioException
import com.formatci.mediastudio.core.files.FileStorageHelper
import com.formatci.mediastudio.domain.model.MediaFormatUtils
import com.formatci.mediastudio.domain.model.MediaInfo
import com.formatci.mediastudio.domain.model.MediaInput
import com.formatci.mediastudio.domain.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * İçerik çözümleyici, codec/track denetçisi, EXIF/küçük resim üreticisi ve
 * MediaExtractor + MediaCodec tabanlı gerçek ses dalga formu (waveformPeaks) çıkarıcısı.
 */
class MediaInspector(
    private val context: Context,
    private val fileHelper: FileStorageHelper
) {

    suspend fun inspect(input: MediaInput): MediaInfo = withContext(Dispatchers.IO) {
        val uri = runCatching { Uri.parse(input.uriString) }.getOrNull()
            ?: throw StudioException(StudioErrorCode.FILE_NOT_FOUND, input.uriString)

        if (!fileHelper.canReadUri(uri)) {
            throw StudioException(StudioErrorCode.URI_PERMISSION_LOST, input.uriString)
        }

        val resolvedInput = runCatching { fileHelper.resolveMediaInput(uri) }
            .getOrElse { input }
        val mergedInput = input.copy(
            displayName = resolvedInput.displayName.ifBlank { input.displayName },
            extension = resolvedInput.extension.ifBlank { input.extension },
            mimeType = resolvedInput.mimeType.ifBlank { input.mimeType },
            sizeBytes = if (resolvedInput.sizeBytes > 0L) resolvedInput.sizeBytes else input.sizeBytes
        )

        val ext = mergedInput.extension.ifBlank {
            MediaFormatUtils.extensionFromFileName(mergedInput.displayName)
        }
        val sniffedMime = sniffMimeFromHeader(uri)
        val resolverMime = mergedInput.mimeType
            .takeIf { it.isNotBlank() && it != "application/octet-stream" }
        val effectiveMime = sniffedMime ?: resolverMime.orEmpty()

        val inferredType = MediaFormatUtils.inferMediaType(ext, effectiveMime)
        val mismatch = MediaFormatUtils.isExtensionMismatch(ext, effectiveMime)
        val mismatchWarning = if (mismatch) {
            "Dosya uzantısı (.$ext) gerçek medya içeriğiyle ($effectiveMime) uyuşmuyor."
        } else {
            null
        }

        return@withContext when {
            effectiveMime.startsWith("image/") || inferredType == MediaType.IMAGE -> {
                inspectImage(mergedInput, uri, ext, effectiveMime, mismatch, mismatchWarning)
            }
            else -> {
                inspectAudioOrVideo(mergedInput, uri, ext, effectiveMime, mismatch, mismatchWarning)
            }
        }
    }

    suspend fun extractFrameToFile(uriString: String, positionMs: Long): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val uri = Uri.parse(uriString)
                if (!fileHelper.canReadUri(uri)) {
                    throw StudioException(StudioErrorCode.URI_PERMISSION_LOST, uriString)
                }

                val retriever = MediaMetadataRetriever()
                var frame: Bitmap? = null
                val outFile = fileHelper.createThumbnailFile("kare_${positionMs}ms", "png")
                try {
                    retriever.setDataSource(context, uri)
                    val targetUs = positionMs.coerceAtLeast(0L) * 1000L
                    frame = retriever.getFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_CLOSEST)
                        ?: retriever.getFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: throw StudioException(
                            StudioErrorCode.CORRUPT_MEDIA,
                            "Belirtilen zaman konumundan video karesi alınamadı ($positionMs ms)"
                        )

                    FileOutputStream(outFile).use { out ->
                        if (!frame.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                            throw StudioException(StudioErrorCode.OUTPUT_CREATE_FAILED, "Kare PNG olarak yazılamadı")
                        }
                        out.flush()
                    }
                    outFile.absolutePath
                } catch (t: Throwable) {
                    fileHelper.deleteFileQuietly(outFile)
                    throw t
                } finally {
                    frame?.recycle()
                    runCatching { retriever.release() }
                }
            }
        }

    private suspend fun inspectImage(
        input: MediaInput,
        uri: Uri,
        ext: String,
        detectedMime: String,
        mismatch: Boolean,
        mismatchWarning: String?
    ): MediaInfo {
        currentCoroutineContext().ensureActive()

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        fileHelper.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }

        val rawWidth = bounds.outWidth.coerceAtLeast(0)
        val rawHeight = bounds.outHeight.coerceAtLeast(0)
        val actualMime = bounds.outMimeType?.takeIf { it.isNotBlank() } ?: detectedMime
        val finalMismatch = MediaFormatUtils.isExtensionMismatch(ext, actualMime) || mismatch
        val finalWarning = if (finalMismatch && actualMime.isNotBlank()) {
            "Dosya uzantısı (.$ext) gerçek resim biçimiyle ($actualMime) uyuşmuyor."
        } else {
            mismatchWarning
        }

        var rotation = 0
        var flipH = false
        val exifMap = linkedMapOf<String, String>()

        runCatching {
            fileHelper.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                val orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
                when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> rotation = 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> rotation = 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> rotation = 270
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> flipH = true
                    ExifInterface.ORIENTATION_TRANSPOSE -> {
                        rotation = 90
                        flipH = true
                    }
                    ExifInterface.ORIENTATION_TRANSVERSE -> {
                        rotation = 270
                        flipH = true
                    }
                }
                exif.getAttribute(ExifInterface.TAG_MAKE)?.takeIf { it.isNotBlank() }?.let {
                    exifMap["Üretici"] = it.trim()
                }
                exif.getAttribute(ExifInterface.TAG_MODEL)?.takeIf { it.isNotBlank() }?.let {
                    exifMap["Model"] = it.trim()
                }
                exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { exifMap["Çekim Tarihi"] = it.trim() }
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME)?.takeIf { it.isNotBlank() }?.let {
                        exifMap["Tarih"] = it.trim()
                    }
                exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)?.takeIf { it.isNotBlank() }?.let {
                    exifMap["ISO"] = it.trim()
                }
                exif.getAttribute(ExifInterface.TAG_F_NUMBER)?.takeIf { it.isNotBlank() }?.let {
                    exifMap["Diyafram"] = "f/$it"
                }
                exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.takeIf { it.isNotBlank() }?.let {
                    exifMap["Pozlama"] = "${it}s"
                }
                if (rotation != 0) {
                    exifMap["Yönlendirme"] = "${rotation}°"
                }
                val hasLatLong = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE) != null
                exifMap["Konum (GPS)"] = if (hasLatLong) "Mevcut (EXIF temizlenebilir)" else "Yok"
            }
        }

        if (rawWidth <= 0 || rawHeight <= 0) {
            throw StudioException(StudioErrorCode.CORRUPT_MEDIA, "Resim boyutları okunamadı: ${input.displayName}")
        }

        val effectiveWidth = if (rotation == 90 || rotation == 270) rawHeight else rawWidth
        val effectiveHeight = if (rotation == 90 || rotation == 270) rawWidth else rawHeight
        val thumbPath = createImageThumbnail(uri, rawWidth, rawHeight, rotation, flipH)

        return MediaInfo(
            input = input.copy(mediaType = MediaType.IMAGE, mimeType = actualMime),
            mediaType = MediaType.IMAGE,
            detectedMimeType = actualMime,
            detectedFormat = formatLabelFromMime(actualMime, ext),
            sizeBytes = input.sizeBytes,
            durationMs = 0L,
            width = effectiveWidth,
            height = effectiveHeight,
            rotationDegrees = rotation,
            hasVideo = false,
            hasAudio = false,
            isExtensionMismatch = finalMismatch,
            mismatchWarning = finalWarning,
            thumbnailPath = thumbPath,
            exifSummary = exifMap
        )
    }

    private suspend fun inspectAudioOrVideo(
        input: MediaInput,
        uri: Uri,
        ext: String,
        detectedMime: String,
        mismatch: Boolean,
        mismatchWarning: String?
    ): MediaInfo {
        currentCoroutineContext().ensureActive()

        val extractor = MediaExtractor()
        var videoTracks = 0
        var audioTracks = 0
        var videoCodec: String? = null
        var audioCodec: String? = null
        var width = 0
        var height = 0
        var fps = 0f
        var videoBitrate = 0L
        var audioBitrate = 0L
        var sampleRate = 0
        var channelCount = 0
        var durationMs = 0L
        var rotation = 0
        var containerMime = detectedMime
        var firstAudioTrackIndex = -1

        try {
            extractor.setDataSource(context, uri, null)
            val trackCount = extractor.trackCount
            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                val trackDurationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    (format.getLong(MediaFormat.KEY_DURATION) / 1000L).coerceAtLeast(0L)
                } else {
                    0L
                }
                if (trackDurationMs > durationMs) {
                    durationMs = trackDurationMs
                }

                when {
                    mime.startsWith("video/") -> {
                        videoTracks++
                        if (videoCodec == null) {
                            videoCodec = mime
                            if (format.containsKey(MediaFormat.KEY_WIDTH)) {
                                width = format.getInteger(MediaFormat.KEY_WIDTH)
                            }
                            if (format.containsKey(MediaFormat.KEY_HEIGHT)) {
                                height = format.getInteger(MediaFormat.KEY_HEIGHT)
                            }
                            if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                                fps = runCatching {
                                    format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
                                }.getOrElse {
                                    runCatching { format.getFloat(MediaFormat.KEY_FRAME_RATE) }.getOrDefault(0f)
                                }
                            }
                            if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                                videoBitrate = format.getInteger(MediaFormat.KEY_BIT_RATE).toLong()
                            }
                            if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                                rotation = format.getInteger(MediaFormat.KEY_ROTATION)
                            }
                        }
                    }

                    mime.startsWith("audio/") -> {
                        audioTracks++
                        if (audioCodec == null) {
                            firstAudioTrackIndex = i
                            audioCodec = mime
                            if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                                sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            }
                            if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                                channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            }
                            if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                                audioBitrate = format.getInteger(MediaFormat.KEY_BIT_RATE).toLong()
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            // Bazı kapsayıcılar yalnızca MediaMetadataRetriever ile çözülebilir; aşağıda devam et
            if (detectedMime.isBlank()) {
                throw StudioException(StudioErrorCode.CORRUPT_MEDIA, t.message, t)
            }
        } finally {
            runCatching { extractor.release() }
        }

        val retriever = MediaMetadataRetriever()
        var thumbPath: String? = null
        try {
            retriever.setDataSource(context, uri)
            if (durationMs <= 0L) {
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            }
            if (width <= 0) {
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: 0
            }
            if (height <= 0) {
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: 0
            }
            if (rotation == 0) {
                rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0
            }
            if (fps <= 0f) {
                fps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                    ?.toFloatOrNull() ?: 0f
            }
            val totalBitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toLongOrNull() ?: 0L
            if (videoTracks > 0 && videoBitrate <= 0L && totalBitrate > 0L) {
                videoBitrate = (totalBitrate - audioBitrate).coerceAtLeast(0L)
            } else if (videoTracks == 0 && audioBitrate <= 0L && totalBitrate > 0L) {
                audioBitrate = totalBitrate
            }
            val retMime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            if (!retMime.isNullOrBlank()) {
                containerMime = retMime
            }
            val hasVidMeta = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            val hasAudMeta = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            if (videoTracks == 0 && hasVidMeta) videoTracks = 1
            if (audioTracks == 0 && hasAudMeta) audioTracks = 1

            if (videoTracks > 0) {
                thumbPath = createVideoThumbnail(retriever, durationMs)
            }
        } catch (_: Throwable) {
            // Retriever kısmi metadata sağlamadıysa extractor verileriyle devam et.
        } finally {
            runCatching { retriever.release() }
        }

        if (videoTracks == 0 && audioTracks == 0) {
            throw StudioException(
                StudioErrorCode.CORRUPT_MEDIA,
                "Medya akışı (video veya ses izi) bulunamadı: ${input.displayName}"
            )
        }

        val finalType = if (videoTracks > 0) MediaType.VIDEO else MediaType.AUDIO
        val finalMime = containerMime.ifBlank {
            if (finalType == MediaType.VIDEO) "video/mp4" else "audio/mp4"
        }

        val waveformPeaks = if (finalType == MediaType.AUDIO && firstAudioTrackIndex >= 0) {
            extractAudioWaveformPeaks(uri, firstAudioTrackIndex, durationMs, targetPoints = 100)
        } else {
            emptyList()
        }

        val actualMismatch = MediaFormatUtils.isExtensionMismatch(ext, finalMime) || mismatch
        val actualWarning = if (actualMismatch) {
            "Dosya uzantısı (.$ext) gerçek akış türüyle ($finalMime) uyuşmuyor."
        } else {
            mismatchWarning
        }

        return MediaInfo(
            input = input.copy(mediaType = finalType, mimeType = finalMime),
            mediaType = finalType,
            detectedMimeType = finalMime,
            detectedFormat = formatLabelFromMime(finalMime, ext),
            sizeBytes = input.sizeBytes,
            durationMs = durationMs,
            width = width,
            height = height,
            fps = if (fps > 0f) fps else if (finalType == MediaType.VIDEO) 30f else 0f,
            rotationDegrees = rotation,
            videoBitrateBps = videoBitrate,
            audioBitrateBps = audioBitrate,
            videoCodec = videoCodec,
            audioCodec = audioCodec,
            sampleRateHz = sampleRate,
            channelCount = channelCount,
            hasVideo = videoTracks > 0,
            hasAudio = audioTracks > 0,
            videoTrackCount = videoTracks,
            audioTrackCount = audioTracks,
            isExtensionMismatch = actualMismatch,
            mismatchWarning = actualWarning,
            thumbnailPath = thumbPath,
            waveformPeaks = waveformPeaks
        )
    }

    /**
     * MediaExtractor + MediaCodec ile PCM örnekleri çözerek 80-120 noktalı gerçek
     * ses dalga formu tepe noktalarını çıkarır. Uzun dosyalarda seyrek örnekleme yapar.
     */
    private suspend fun extractAudioWaveformPeaks(
        uri: Uri,
        audioTrackIndex: Int,
        durationMs: Long,
        targetPoints: Int = 100
    ): List<Float> {
        val points = targetPoints.coerceIn(80, 120)
        val peaks = FloatArray(points)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            extractor.setDataSource(context, uri, null)
            extractor.selectTrack(audioTrackIndex)
            val format = extractor.getTrackFormat(audioTrackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return emptyList()
            val trackDurationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else {
                durationMs * 1000L
            }.coerceAtLeast(1_000_000L)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            val timeoutUs = 8_000L
            var currentPcmEncoding = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                format.containsKey(MediaFormat.KEY_PCM_ENCODING)
            ) {
                format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }

            if (trackDurationUs > 90_000_000L) {
                // Uzun dosyalarda bellek ve CPU koruması için 100 pencereye atlamalı örnekleme yap
                for (bucket in 0 until points) {
                    currentCoroutineContext().ensureActive()
                    val seekUs = (trackDurationUs * bucket) / points
                    extractor.seekTo(seekUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    codec.flush()

                    var bucketMax = 0f
                    var framesDecoded = 0
                    var inputDone = false

                    while (framesDecoded < 12) {
                        if (!inputDone) {
                            val inIdx = codec.dequeueInputBuffer(timeoutUs)
                            if (inIdx >= 0) {
                                val inBuf = codec.getInputBuffer(inIdx)
                                val sampleSize = if (inBuf != null) extractor.readSampleData(inBuf, 0) else -1
                                if (sampleSize < 0) {
                                    codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                } else {
                                    codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                                    extractor.advance()
                                }
                            }
                        }

                        val outIdx = codec.dequeueOutputBuffer(info, timeoutUs)
                        when {
                            outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                val outFmt = codec.outputFormat
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                                    outFmt.containsKey(MediaFormat.KEY_PCM_ENCODING)
                                ) {
                                    currentPcmEncoding = outFmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                                }
                            }
                            outIdx >= 0 -> {
                                val outBuf = codec.getOutputBuffer(outIdx)
                                if (outBuf != null && info.size > 0) {
                                    val amp = peakAmplitudeFromBuffer(outBuf, info.offset, info.size, currentPcmEncoding)
                                    if (amp > bucketMax) bucketMax = amp
                                    framesDecoded++
                                }
                                codec.releaseOutputBuffer(outIdx, false)
                                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                            }
                            outIdx == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone -> break
                        }
                    }
                    peaks[bucket] = bucketMax
                }
            } else {
                // Kısa/orta uzunluklu dosyalarda baştan sona gerçek örnekleri 100 kovada topla
                var inputDone = false
                var outputDone = false
                var safetyIterations = 0

                while (!outputDone && safetyIterations < 30_000) {
                    safetyIterations++
                    currentCoroutineContext().ensureActive()

                    if (!inputDone) {
                        val inIdx = codec.dequeueInputBuffer(timeoutUs)
                        if (inIdx >= 0) {
                            val inBuf = codec.getInputBuffer(inIdx)
                            val sampleSize = if (inBuf != null) extractor.readSampleData(inBuf, 0) else -1
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    val outIdx = codec.dequeueOutputBuffer(info, timeoutUs)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outFmt = codec.outputFormat
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                                outFmt.containsKey(MediaFormat.KEY_PCM_ENCODING)
                            ) {
                                currentPcmEncoding = outFmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            }
                        }
                        outIdx >= 0 -> {
                            val outBuf = codec.getOutputBuffer(outIdx)
                            if (outBuf != null && info.size > 0) {
                                val bucket = ((info.presentationTimeUs.coerceAtLeast(0L) * points) / trackDurationUs)
                                    .toInt()
                                    .coerceIn(0, points - 1)
                                val amp = peakAmplitudeFromBuffer(outBuf, info.offset, info.size, currentPcmEncoding)
                                if (amp > peaks[bucket]) peaks[bucket] = amp
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                outputDone = true
                            }
                        }
                    }
                }
            }

            val globalPeak = peaks.maxOrNull()?.coerceAtLeast(0.05f) ?: 1f
            return peaks.map { value ->
                (value / globalPeak).coerceIn(0.04f, 1.0f)
            }
        } catch (_: Throwable) {
            return emptyList()
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun peakAmplitudeFromBuffer(
        buffer: ByteBuffer,
        offset: Int,
        size: Int,
        pcmEncoding: Int
    ): Float {
        val dup = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        dup.position(offset)
        dup.limit(min(buffer.capacity(), offset + size))

        var maxPeak = 0f
        when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val stride = 16 // Bellek/CPU optimizasyonu için her 4 float örnekte 1 örnekle
                while (dup.remaining() >= 4) {
                    val v = abs(dup.float)
                    if (v > maxPeak) maxPeak = v
                    val skip = min(dup.remaining(), stride - 4)
                    if (skip > 0) dup.position(dup.position() + skip)
                }
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                while (dup.hasRemaining()) {
                    val sample = ((dup.get().toInt() and 0xFF) - 128) / 128f
                    val v = abs(sample)
                    if (v > maxPeak) maxPeak = v
                }
            }
            else -> {
                val stepBytes = if (size > 4096) 8 else 2
                while (dup.remaining() >= 2) {
                    val sample = dup.short.toInt() / 32768f
                    val v = abs(sample)
                    if (v > maxPeak) maxPeak = v
                    val skip = min(dup.remaining(), stepBytes - 2)
                    if (skip > 0) dup.position(dup.position() + skip)
                }
            }
        }
        return maxPeak.coerceIn(0f, 1f)
    }

    private fun createImageThumbnail(
        uri: Uri,
        rawW: Int,
        rawH: Int,
        rotation: Int,
        flipH: Boolean
    ): String? {
        return runCatching {
            val sampleSize = calculateInSampleSize(rawW, rawH, 320, 320)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val decoded = fileHelper.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, opts)
            } ?: return@runCatching null

            val transformed = if (rotation != 0 || flipH) {
                val matrix = Matrix().apply {
                    if (flipH) postScale(-1f, 1f)
                    if (rotation != 0) postRotate(rotation.toFloat())
                }
                Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                    .also { if (it !== decoded) decoded.recycle() }
            } else {
                decoded
            }

            val scaled = scaleBitmapDown(transformed, 320)
            val thumbFile = fileHelper.createThumbnailFile("img_thumb", "jpg")
            FileOutputStream(thumbFile).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
                out.flush()
            }
            scaled.recycle()
            thumbFile.absolutePath
        }.getOrNull()
    }

    private fun createVideoThumbnail(retriever: MediaMetadataRetriever, durationMs: Long): String? {
        return runCatching {
            val timeUs = min(1_500_000L, max(0L, durationMs * 300L))
            val raw = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return@runCatching null

            val scaled = scaleBitmapDown(raw, 360)
            val thumbFile = fileHelper.createThumbnailFile("vid_thumb", "jpg")
            FileOutputStream(thumbFile).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
                out.flush()
            }
            scaled.recycle()
            thumbFile.absolutePath
        }.getOrNull()
    }

    private fun scaleBitmapDown(source: Bitmap, maxSide: Int): Bitmap {
        val w = source.width
        val h = source.height
        if (w <= maxSide && h <= maxSide) return source
        val ratio = maxSide.toFloat() / max(w, h).toFloat()
        val targetW = (w * ratio).toInt().coerceAtLeast(1)
        val targetH = (h * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, targetW, targetH, true).also {
            if (it !== source) source.recycle()
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int, reqW: Int, reqH: Int): Int {
        var inSampleSize = 1
        if (height > reqH || width > reqW) {
            val halfH = height / 2
            val halfW = width / 2
            while ((halfH / inSampleSize) >= reqH && (halfW / inSampleSize) >= reqW) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }

    private fun sniffMimeFromHeader(uri: Uri): String? {
        return runCatching {
            val header = ByteArray(32)
            val read = fileHelper.openInputStream(uri)?.use { it.read(header) } ?: 0
            if (read < 4) return@runCatching null

            when {
                read >= 8 && header[0] == 0x89.toByte() && header[1] == 0x50.toByte() &&
                    header[2] == 0x4E.toByte() && header[3] == 0x47.toByte() -> "image/png"

                header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() &&
                    header[2] == 0xFF.toByte() -> "image/jpeg"

                header[0] == 'G'.code.toByte() && header[1] == 'I'.code.toByte() &&
                    header[2] == 'F'.code.toByte() -> "image/gif"

                header[0] == 'B'.code.toByte() && header[1] == 'M'.code.toByte() -> "image/bmp"

                read >= 12 && header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() &&
                    header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte() -> {
                    val tag = String(header, 8, 4, Charsets.US_ASCII)
                    when (tag) {
                        "WEBP" -> "image/webp"
                        "WAVE" -> "audio/wav"
                        "AVI " -> "video/x-msvideo"
                        else -> null
                    }
                }

                header[0] == 'f'.code.toByte() && header[1] == 'L'.code.toByte() &&
                    header[2] == 'a'.code.toByte() && header[3] == 'C'.code.toByte() -> "audio/flac"

                header[0] == 'O'.code.toByte() && header[1] == 'g'.code.toByte() &&
                    header[2] == 'g'.code.toByte() && header[3] == 'S'.code.toByte() -> "audio/ogg"

                (header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() &&
                    header[2] == '3'.code.toByte()) ||
                    (header[0] == 0xFF.toByte() && (header[1].toInt() and 0xE0) == 0xE0) -> "audio/mpeg"

                header[0] == 0x1A.toByte() && header[1] == 0x45.toByte() &&
                    header[2] == 0xDF.toByte() && header[3] == 0xA3.toByte() -> "video/webm"

                read >= 12 && header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte() &&
                    header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte() -> {
                    val brand = String(header, 8, 4, Charsets.US_ASCII)
                    when {
                        brand.startsWith("M4A") -> "audio/mp4"
                        brand.startsWith("heic") || brand.startsWith("heif") || brand.startsWith("mif1") -> "image/heic"
                        brand.startsWith("qt") -> "video/quicktime"
                        else -> "video/mp4"
                    }
                }

                else -> null
            }
        }.getOrNull()
    }

    private fun formatLabelFromMime(mime: String, extFallback: String): String {
        val m = mime.lowercase(Locale.ROOT)
        return when {
            "jpeg" in m || "jpg" in m -> "JPEG"
            "png" in m -> "PNG"
            "webp" in m -> "WebP"
            "bmp" in m -> "BMP"
            "heic" in m || "heif" in m -> "HEIC"
            "webm" in m -> "WebM"
            "matroska" in m -> "MKV"
            "quicktime" in m -> "MOV"
            "wav" in m -> "WAV (PCM)"
            "flac" in m -> "FLAC"
            "opus" in m -> "OPUS"
            "ogg" in m -> "OGG"
            "mpeg" in m && m.startsWith("audio/") -> "MP3"
            "mp4" in m && extFallback.equals("m4a", ignoreCase = true) -> "M4A"
            "mp4" in m -> "MP4"
            else -> extFallback.uppercase(Locale.ROOT).ifBlank { m }
        }
    }
}
