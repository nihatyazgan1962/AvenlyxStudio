package com.formatci.mediastudio.media.engine

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.formatci.mediastudio.core.error.StudioErrorCode
import com.formatci.mediastudio.core.error.StudioErrorMapper
import com.formatci.mediastudio.core.error.StudioException
import com.formatci.mediastudio.core.files.FileStorageHelper
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
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * ImageDecoder / BitmapFactory (inSampleSize bellek korumalı) + Matrix + Canvas/Paint +
 * ExifInterface + yerleşik 24-bit BMP kodlayıcı tabanlı çevrimdışı resim işleme motoru.
 */
class ImageEngine(
    private val context: Context,
    private val fileHelper: FileStorageHelper,
    private val inspector: MediaInspector,
    private val capabilityRegistry: EngineCapabilityRegistry = EngineCapabilityRegistry()
) : MediaEngine {

    private val cancelledJobs = ConcurrentHashMap.newKeySet<String>()

    override suspend fun inspect(input: MediaInput): MediaInfo = inspector.inspect(input)

    override fun capabilities(): EngineCapabilities = capabilityRegistry.getCapabilities()

    override suspend fun extractFrameToFile(uriString: String, positionMs: Long): Result<String> {
        return inspector.extractFrameToFile(uriString, positionMs)
    }

    override suspend fun estimate(job: MediaJob): JobEstimate = withContext(Dispatchers.IO) {
        val warnings = mutableListOf<String>()
        val input = job.inputs.firstOrNull()
        val info = input?.let { runCatching { inspector.inspect(it) }.getOrNull() }
        if (info?.isExtensionMismatch == true && info.mismatchWarning != null) {
            warnings.add(info.mismatchWarning)
        }
        val inputSize = info?.sizeBytes ?: input?.sizeBytes ?: 128_000L
        val estBytes = MediaFormatUtils.estimateOutputBytes(
            tool = job.tool,
            durationMs = 0L,
            inputSizeBytes = inputSize,
            settings = job.settings
        )
        val requiredFree = (estBytes * 2L).coerceAtLeast(8L * 1024L * 1024L)
        val hasEnough = fileHelper.hasEnoughStorage(requiredFree)
        if (!hasEnough) {
            warnings.add("Resim çıktısı için yeterli depolama alanı olmayabilir.")
        }
        JobEstimate(
            estimatedOutputBytes = estBytes,
            estimatedDurationMs = 0L,
            estimatedProcessingTimeMs = 1_200L,
            requiredFreeSpaceBytes = requiredFree,
            hasEnoughStorage = hasEnough,
            warnings = warnings
        )
    }

    override fun execute(job: MediaJob): Flow<MediaJobEvent> = flow {
        cancelledJobs.remove(job.id)
        emit(MediaJobEvent.Started(job.id, "Resim dosyası çözümleniyor..."))

        var decodedBitmap: Bitmap? = null
        var renderedBitmap: Bitmap? = null
        var tempOutFile: File? = null

        try {
            val input = job.inputs.firstOrNull()
                ?: throw StudioException(StudioErrorCode.FILE_NOT_FOUND, "Resim dosyası seçilmedi.")
            val uri = Uri.parse(input.uriString)
            if (!fileHelper.canReadUri(uri)) {
                throw StudioException(StudioErrorCode.URI_PERMISSION_LOST, input.uriString)
            }

            val est = estimate(job)
            fileHelper.ensureEnoughStorage(est.requiredFreeSpaceBytes)

            val settings = job.settings
            val outExt = normalizeImageExt(settings.outputFormat)
            val effectiveQuality = resolveQuality(job.tool, settings)

            checkCancelled(job.id)
            emit(MediaJobEvent.Progress(job.id, 0.15f, isIndeterminate = false, message = "Görsel belleğe alınıyor..."))

            val sourceExifTags = if (!settings.stripExif && outExt == "jpg") {
                readSafeExifTags(uri)
            } else {
                emptyMap()
            }

            decodedBitmap = decodeBitmapSafely(uri, settings)
            checkCancelled(job.id)
            emit(MediaJobEvent.Progress(job.id, 0.45f, isIndeterminate = false, message = "Döndürme, renk ve filigran uygulanıyor..."))

            renderedBitmap = renderProcessedBitmap(decodedBitmap, settings, outExt)
            if (renderedBitmap !== decodedBitmap) {
                decodedBitmap.recycle()
                decodedBitmap = null
            }

            checkCancelled(job.id)
            emit(MediaJobEvent.Progress(job.id, 0.72f, isIndeterminate = false, message = "${outExt.uppercase(Locale.ROOT)} olarak kodlanıyor..."))

            tempOutFile = fileHelper.createTempOutputFile("img_${job.id.take(6)}", outExt)
            encodeBitmapToFile(renderedBitmap, tempOutFile, outExt, effectiveQuality)

            if (outExt == "jpg" && !settings.stripExif && sourceExifTags.isNotEmpty()) {
                applySafeExifTags(tempOutFile, sourceExifTags)
            }

            checkCancelled(job.id)
            emit(MediaJobEvent.Progress(job.id, 0.92f, isIndeterminate = false, message = "Galeri/MediaStore'a kaydediliyor..."))

            val outW = renderedBitmap.width
            val outH = renderedBitmap.height
            renderedBitmap.recycle()
            renderedBitmap = null

            val baseName = settings.outputFileName.ifBlank { input.displayName }
            val saved = fileHelper.saveTempFileToPublicStorage(
                tempFile = tempOutFile,
                desiredBaseName = baseName,
                extension = outExt,
                mediaType = MediaType.IMAGE
            )

            emit(
                MediaJobEvent.Completed(
                    jobId = job.id,
                    outputUriString = saved.uriString,
                    outputDisplayName = saved.displayName,
                    outputMimeType = MediaFormatUtils.mimeTypeForExtension(outExt),
                    outputFormat = outExt,
                    outputSizeBytes = saved.sizeBytes,
                    durationMs = 0L,
                    width = outW,
                    height = outH,
                    mediaType = MediaType.IMAGE
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
            decodedBitmap?.recycle()
            renderedBitmap?.recycle()
            cancelledJobs.remove(job.id)
            fileHelper.deleteFileQuietly(tempOutFile)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun cancel(jobId: String) {
        cancelledJobs.add(jobId)
    }

    private fun decodeBitmapSafely(uri: Uri, settings: MediaJobSettings): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        fileHelper.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }

        val maxSideLimit = 4096
        val requestedMax = max(
            settings.targetWidth ?: 0,
            settings.targetHeight ?: 0
        ).takeIf { it > 0 }?.coerceAtMost(maxSideLimit) ?: maxSideLimit

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val decoded = runCatching {
                val source = if (uri.scheme == ContentResolver.SCHEME_FILE && uri.path != null) {
                    ImageDecoder.createSource(File(uri.path!!))
                } else {
                    ImageDecoder.createSource(context.contentResolver, uri)
                }
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = true
                    val srcW = info.size.width.coerceAtLeast(1)
                    val srcH = info.size.height.coerceAtLeast(1)
                    val sample = calculateInSampleSize(srcW, srcH, requestedMax, requestedMax)
                    if (sample > 1) {
                        decoder.setTargetSampleSize(sample)
                    }
                }
            }.getOrNull()
            if (decoded != null) return decoded
        }

        val rawW = bounds.outWidth.coerceAtLeast(1)
        val rawH = bounds.outHeight.coerceAtLeast(1)
        val sampleSize = calculateInSampleSize(rawW, rawH, requestedMax, requestedMax)
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        val bitmap = fileHelper.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOpts)
        } ?: throw StudioException(StudioErrorCode.CORRUPT_MEDIA, "Resim çözümlenemedi.")

        val exifRot = readExifTransform(uri)
        if (exifRot.first == 0 && !exifRot.second) return bitmap

        val matrix = Matrix().apply {
            if (exifRot.second) postScale(-1f, 1f)
            if (exifRot.first != 0) postRotate(exifRot.first.toFloat())
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            .also { if (it !== bitmap) bitmap.recycle() }
    }

    private fun renderProcessedBitmap(
        source: Bitmap,
        settings: MediaJobSettings,
        outExt: String
    ): Bitmap {
        // 1. Döndürme ve aynalama
        val normRot = ((settings.rotateDegrees % 360) + 360) % 360
        var working = if (normRot != 0 || settings.flipHorizontal || settings.flipVertical) {
            val m = Matrix().apply {
                if (settings.flipHorizontal) postScale(-1f, 1f)
                if (settings.flipVertical) postScale(1f, -1f)
                if (normRot != 0) postRotate(normRot.toFloat())
            }
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, m, true)
        } else {
            source
        }

        // 2. Ölçekleme (% veya piksel cinsinden hedef boyut)
        val (targetW, targetH) = resolveTargetDimensions(working.width, working.height, settings)
        if (targetW != working.width || targetH != working.height) {
            val scaled = Bitmap.createScaledBitmap(working, targetW, targetH, true)
            if (working !== source && working !== scaled) working.recycle()
            working = scaled
        }

        val needsOpaqueBg = outExt == "jpg" || outExt == "bmp"
        val hasColorEdits = abs(settings.brightness) > 0.01f ||
            abs(settings.contrast - 1f) > 0.01f ||
            abs(settings.saturation - 1f) > 0.01f ||
            settings.grayscale ||
            settings.sepia
        val hasWatermark = settings.watermarkText.isNotBlank()

        if (!needsOpaqueBg && !hasColorEdits && !hasWatermark && working.config == Bitmap.Config.ARGB_8888) {
            return working
        }

        val outBitmap = Bitmap.createBitmap(working.width, working.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outBitmap)

        if (needsOpaqueBg) {
            canvas.drawColor(settings.jpegBackgroundColorArgb)
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (hasColorEdits) {
            paint.colorFilter = ColorMatrixColorFilter(buildColorMatrix(settings))
        }
        canvas.drawBitmap(working, 0f, 0f, paint)

        if (working !== source) {
            working.recycle()
        }

        if (hasWatermark) {
            drawWatermark(canvas, outBitmap.width, outBitmap.height, settings.watermarkText.trim(), settings.watermarkOpacity)
        }

        return outBitmap
    }

    private fun resolveTargetDimensions(
        currentW: Int,
        currentH: Int,
        settings: MediaJobSettings
    ): Pair<Int, Int> {
        val reqW = settings.targetWidth
        val reqH = settings.targetHeight
        if (reqW != null && reqH != null && reqW > 0 && reqH > 0) {
            return reqW.coerceIn(16, 4096) to reqH.coerceIn(16, 4096)
        }
        if (reqW != null && reqW > 0) {
            val clampedW = reqW.coerceIn(16, 4096)
            val propH = ((currentH.toDouble() / currentW.toDouble()) * clampedW).roundToInt().coerceIn(16, 4096)
            return clampedW to propH
        }
        if (reqH != null && reqH > 0) {
            val clampedH = reqH.coerceIn(16, 4096)
            val propW = ((currentW.toDouble() / currentH.toDouble()) * clampedH).roundToInt().coerceIn(16, 4096)
            return propW to clampedH
        }
        val scalePct = settings.imageScalePercent.coerceIn(10, 200)
        if (scalePct != 100) {
            val sw = ((currentW * scalePct) / 100f).roundToInt().coerceIn(16, 4096)
            val sh = ((currentH * scalePct) / 100f).roundToInt().coerceIn(16, 4096)
            return sw to sh
        }
        return currentW to currentH
    }

    private fun buildColorMatrix(settings: MediaJobSettings): ColorMatrix {
        val cm = ColorMatrix()
        if (settings.grayscale) {
            cm.setSaturation(0f)
        } else if (settings.sepia) {
            cm.set(
                floatArrayOf(
                    0.393f, 0.769f, 0.189f, 0f, 0f,
                    0.349f, 0.686f, 0.168f, 0f, 0f,
                    0.272f, 0.534f, 0.131f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        } else if (abs(settings.saturation - 1f) > 0.01f) {
            cm.setSaturation(settings.saturation.coerceIn(0f, 3f))
        }

        if (abs(settings.contrast - 1f) > 0.01f || abs(settings.brightness) > 0.01f) {
            val c = settings.contrast.coerceIn(0.2f, 2.5f)
            val b = settings.brightness.coerceIn(-1f, 1f) * 255f
            val t = (-0.5f * c + 0.5f) * 255f + b
            val bcMatrix = ColorMatrix(
                floatArrayOf(
                    c, 0f, 0f, 0f, t,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            cm.postConcat(bcMatrix)
        }
        return cm
    }

    private fun drawWatermark(
        canvas: Canvas,
        width: Int,
        height: Int,
        text: String,
        opacity: Float
    ) {
        val alpha = (opacity.coerceIn(0.1f, 1f) * 255).roundToInt()
        val textSize = (min(width, height) * 0.042f).coerceIn(18f, 140f)
        val padH = textSize * 0.6f
        val padV = textSize * 0.35f
        val margin = textSize * 0.9f

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.alpha = alpha
            this.textSize = textSize
            isFakeBoldText = true
        }
        val textWidth = textPaint.measureText(text)
        val fm = textPaint.fontMetrics
        val boxW = textWidth + padH * 2f
        val boxH = (fm.descent - fm.ascent) + padV * 2f

        val left = (width - boxW - margin).coerceAtLeast(margin)
        val top = (height - boxH - margin).coerceAtLeast(margin)
        val bgRect = RectF(left, top, left + boxW, top + boxH)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb((alpha * 0.55f).roundToInt(), 15, 20, 30)
        }
        canvas.drawRoundRect(bgRect, textSize * 0.35f, textSize * 0.35f, bgPaint)

        val textY = top + padV - fm.ascent
        canvas.drawText(text, left + padH, textY, textPaint)
    }

    private fun encodeBitmapToFile(bitmap: Bitmap, outFile: File, ext: String, quality: Int) {
        when (ext) {
            "bmp" -> writeBmp24(bitmap, outFile)
            "png" -> {
                FileOutputStream(outFile).use { out ->
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                        throw StudioException(StudioErrorCode.OUTPUT_CREATE_FAILED, "PNG sıkıştırması başarısız")
                    }
                    out.flush()
                }
            }
            "webp" -> {
                val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (quality >= 100) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
                FileOutputStream(outFile).use { out ->
                    if (!bitmap.compress(format, quality.coerceIn(10, 100), out)) {
                        throw StudioException(StudioErrorCode.OUTPUT_CREATE_FAILED, "WebP sıkıştırması başarısız")
                    }
                    out.flush()
                }
            }
            else -> {
                FileOutputStream(outFile).use { out ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(10, 100), out)) {
                        throw StudioException(StudioErrorCode.OUTPUT_CREATE_FAILED, "JPEG sıkıştırması başarısız")
                    }
                    out.flush()
                }
            }
        }
    }

    /**
     * Satır tabanlı akış ile bellek dostu 24-bit Windows BMP (BI_RGB) kodlayıcısı.
     */
    private fun writeBmp24(bitmap: Bitmap, outFile: File) {
        val width = bitmap.width
        val height = bitmap.height
        val rowBytesUnpadded = width * 3
        val rowPadding = (4 - (rowBytesUnpadded % 4)) % 4
        val rowStride = rowBytesUnpadded + rowPadding
        val pixelDataSize = rowStride.toLong() * height.toLong()
        val totalFileSize = 54L + pixelDataSize

        val header = ByteBuffer.allocate(54).order(ByteOrder.LITTLE_ENDIAN).apply {
            put('B'.code.toByte()); put('M'.code.toByte())
            putInt(totalFileSize.toInt())
            putShort(0); putShort(0)
            putInt(54)
            putInt(40) // BITMAPINFOHEADER
            putInt(width)
            putInt(height) // Pozitif yükseklik: alttan üste satır sırası
            putShort(1)
            putShort(24)
            putInt(0) // BI_RGB
            putInt(pixelDataSize.toInt())
            putInt(2835)
            putInt(2835)
            putInt(0)
            putInt(0)
        }

        val rowPixels = IntArray(width)
        val rowOut = ByteArray(rowStride)

        BufferedOutputStream(FileOutputStream(outFile), 64 * 1024).use { out ->
            out.write(header.array())
            for (y in height - 1 downTo 0) {
                bitmap.getPixels(rowPixels, 0, width, 0, y, width, 1)
                var outIdx = 0
                for (x in 0 until width) {
                    val argb = rowPixels[x]
                    rowOut[outIdx++] = (argb and 0xFF).toByte() // B
                    rowOut[outIdx++] = ((argb ushr 8) and 0xFF).toByte() // G
                    rowOut[outIdx++] = ((argb ushr 16) and 0xFF).toByte() // R
                }
                for (p in 0 until rowPadding) {
                    rowOut[outIdx++] = 0
                }
                out.write(rowOut, 0, rowStride)
            }
            out.flush()
        }
    }

    private fun readExifTransform(uri: Uri): Pair<Int, Boolean> {
        return runCatching {
            fileHelper.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90 to false
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180 to false
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270 to false
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> 0 to true
                    ExifInterface.ORIENTATION_TRANSPOSE -> 90 to true
                    ExifInterface.ORIENTATION_TRANSVERSE -> 270 to true
                    else -> 0 to false
                }
            } ?: (0 to false)
        }.getOrDefault(0 to false)
    }

    private fun readSafeExifTags(uri: Uri): Map<String, String> {
        val tags = listOf(
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_ISO_SPEED_RATINGS,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_FLASH
        )
        return runCatching {
            val result = linkedMapOf<String, String>()
            fileHelper.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                tags.forEach { tag ->
                    exif.getAttribute(tag)?.takeIf { it.isNotBlank() }?.let { value ->
                        result[tag] = value
                    }
                }
            }
            result
        }.getOrDefault(emptyMap())
    }

    private fun applySafeExifTags(targetJpeg: File, tags: Map<String, String>) {
        runCatching {
            val exif = ExifInterface(targetJpeg.absolutePath)
            tags.forEach { (tag, value) -> exif.setAttribute(tag, value) }
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            exif.saveAttributes()
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

    private fun normalizeImageExt(raw: String): String {
        return when (raw.lowercase(Locale.ROOT).trimStart('.')) {
            "png" -> "png"
            "webp" -> "webp"
            "bmp" -> "bmp"
            else -> "jpg"
        }
    }

    private fun resolveQuality(tool: StudioTool, settings: MediaJobSettings): Int {
        val explicit = settings.imageQuality.coerceIn(10, 100)
        return if (tool == StudioTool.IMAGE_COMPRESS && explicit == 85) {
            settings.qualityProfile.imageQuality
        } else {
            explicit
        }
    }

    private suspend fun checkCancelled(jobId: String) {
        currentCoroutineContext().ensureActive()
        if (cancelledJobs.contains(jobId)) {
            throw CancellationException("Kullanıcı resim işlemini iptal etti")
        }
    }
}
