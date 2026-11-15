package com.formatci.mediastudio.domain.model

import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToLong

enum class MediaType(val labelTr: String) {
    VIDEO("Video"),
    AUDIO("Ses"),
    IMAGE("Resim"),
    UNKNOWN("Bilinmeyen")
}

enum class StudioTool(
    val titleTr: String,
    val subtitleTr: String,
    val mediaType: MediaType
) {
    VIDEO_CONVERT(
        titleTr = "Video Dönüştür",
        subtitleTr = "MP4 (H.264 / HEVC) ve WebM formatlarına dönüştür",
        mediaType = MediaType.VIDEO
    ),
    VIDEO_EDIT(
        titleTr = "Video Düzenle",
        subtitleTr = "Döndür, renk filtresi, hız ve filigran uygula",
        mediaType = MediaType.VIDEO
    ),
    VIDEO_MERGE(
        titleTr = "Videoları Birleştir",
        subtitleTr = "Birden fazla video klibini art arda tek MP4 yap",
        mediaType = MediaType.VIDEO
    ),
    VIDEO_TRIM_SPLIT(
        titleTr = "Video Kes / Böl",
        subtitleTr = "Başlangıç ve bitiş noktalarından hassas kırpma",
        mediaType = MediaType.VIDEO
    ),
    VIDEO_EXTRACT_AUDIO(
        titleTr = "Videodan Ses Çıkar",
        subtitleTr = "Videodaki ses kanalını M4A veya WAV olarak kaydet",
        mediaType = MediaType.VIDEO
    ),
    VIDEO_ADD_AUDIO(
        titleTr = "Videoya Ses Ekle",
        subtitleTr = "Videonun üzerine fon müziği veya harici ses yerleştir",
        mediaType = MediaType.VIDEO
    ),
    AUDIO_CONVERT(
        titleTr = "Ses Dönüştür",
        subtitleTr = "M4A (AAC), WAV ve cihaz destekli ses formatlarına çevir",
        mediaType = MediaType.AUDIO
    ),
    AUDIO_EDIT_MERGE(
        titleTr = "Ses Düzenle / Birleştir",
        subtitleTr = "Ses kes, fade ekle, birleştir veya iki sesi miksle",
        mediaType = MediaType.AUDIO
    ),
    IMAGE_CONVERT(
        titleTr = "Resim Dönüştür",
        subtitleTr = "JPG, PNG, WebP ve BMP arasında çevir",
        mediaType = MediaType.IMAGE
    ),
    IMAGE_COMPRESS(
        titleTr = "Resim Sıkıştır",
        subtitleTr = "Kalite ve çözünürlüğü düşürerek boyut küçült",
        mediaType = MediaType.IMAGE
    ),
    SLIDESHOW_MAKER(
        titleTr = "Fotoğraflardan Video",
        subtitleTr = "Fotoğraf dizisini ve müziği MP4 slayt videosuna çevir",
        mediaType = MediaType.VIDEO
    ),
    BATCH_CONVERT(
        titleTr = "Toplu Dönüştür",
        subtitleTr = "Birden fazla dosyayı tek kuyrukta sırayla işle",
        mediaType = MediaType.UNKNOWN
    )
}

enum class JobStatus(val labelTr: String) {
    QUEUED("Sırada"),
    PREPARING("Hazırlanıyor"),
    PROCESSING("İşleniyor"),
    COMPLETED("Tamamlandı"),
    CANCELLED("İptal Edildi"),
    FAILED("Hata");

    val isTerminal: Boolean
        get() = this == COMPLETED || this == CANCELLED || this == FAILED

    val isActive: Boolean
        get() = this == QUEUED || this == PREPARING || this == PROCESSING
}

enum class QualityProfile(
    val labelTr: String,
    val bitrateMultiplier: Float,
    val imageQuality: Int
) {
    SMALL_FILE("Küçük Dosya", 0.55f, 70),
    BALANCED("Dengeli", 0.85f, 85),
    HIGH_QUALITY("Yüksek Kalite", 1.25f, 95)
}

enum class AspectRatioOption(
    val labelTr: String,
    val ratioW: Int,
    val ratioH: Int
) {
    ORIGINAL("Orijinal", 0, 0),
    RATIO_16_9("16:9 Yatay", 16, 9),
    RATIO_9_16("9:16 Dikey", 9, 16),
    RATIO_1_1("1:1 Kare", 1, 1),
    RATIO_4_3("4:3 Klasik", 4, 3)
}

enum class AudioFitMode(val labelTr: String) {
    TRIM("Videoya Göre Kırp"),
    LOOP("Döngüye Al"),
    PAD_SILENCE("Sessizlikle Tamamla")
}

enum class ThemePreference(val labelTr: String) {
    SYSTEM("Sistem"),
    LIGHT("Açık"),
    DARK("Koyu")
}

data class MediaInput(
    val id: String = UUID.randomUUID().toString(),
    val uriString: String,
    val displayName: String,
    val extension: String = "",
    val mimeType: String = "",
    val sizeBytes: Long = 0L,
    val mediaType: MediaType = MediaType.UNKNOWN,
    val importedAtMs: Long = System.currentTimeMillis()
)

data class MediaInfo(
    val input: MediaInput,
    val mediaType: MediaType = input.mediaType,
    val detectedMimeType: String = input.mimeType,
    val detectedFormat: String = input.extension,
    val sizeBytes: Long = input.sizeBytes,
    val durationMs: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Float = 0f,
    val rotationDegrees: Int = 0,
    val videoBitrateBps: Long = 0L,
    val audioBitrateBps: Long = 0L,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val sampleRateHz: Int = 0,
    val channelCount: Int = 0,
    val hasVideo: Boolean = false,
    val hasAudio: Boolean = false,
    val videoTrackCount: Int = 0,
    val audioTrackCount: Int = 0,
    val isExtensionMismatch: Boolean = false,
    val mismatchWarning: String? = null,
    val thumbnailPath: String? = null,
    val exifSummary: Map<String, String> = emptyMap(),
    val waveformPeaks: List<Float> = emptyList(),
    val inspectedAtMs: Long = System.currentTimeMillis()
) {
    val displayResolution: String
        get() = if (width > 0 && height > 0) "${width}x${height}" else "-"

    val formattedDuration: String
        get() = MediaFormatUtils.formatDurationMs(durationMs)

    val formattedSize: String
        get() = MediaFormatUtils.formatBytes(sizeBytes)
}

data class MediaJobSettings(
    val outputFormat: String = "mp4",
    val outputFileName: String = "",
    val qualityProfile: QualityProfile = QualityProfile.BALANCED,
    val videoCodec: String = "video/avc",
    val targetWidth: Int? = null,
    val targetHeight: Int? = null,
    val targetFps: Int? = null,
    val videoBitrateBps: Int? = null,
    val aspectRatio: AspectRatioOption = AspectRatioOption.ORIGINAL,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long? = null,
    val playbackSpeed: Float = 1.0f,
    val rotateDegrees: Int = 0,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val grayscale: Boolean = false,
    val sepia: Boolean = false,
    val watermarkText: String = "",
    val watermarkOpacity: Float = 0.8f,
    val removeAudio: Boolean = false,
    val audioVolume: Float = 1.0f,
    val audioCodec: String = "audio/mp4a-latm",
    val audioBitrateBps: Int = 192_000,
    val audioSampleRateHz: Int = 44_100,
    val audioChannels: Int = 2,
    val fadeInMs: Long = 0L,
    val fadeOutMs: Long = 0L,
    val secondaryAudioUri: String? = null,
    val secondaryAudioVolume: Float = 0.7f,
    val audioFitMode: AudioFitMode = AudioFitMode.TRIM,
    val mixAudioInsteadOfConcat: Boolean = false,
    val imageQuality: Int = 85,
    val imageScalePercent: Int = 100,
    val stripExif: Boolean = false,
    val jpegBackgroundColorArgb: Int = 0xFFFFFFFF.toInt(),
    val slideDurationMs: Long = 3_000L,
    val slideshowFps: Int = 30
)

data class TimelineClipItem(
    val clipId: String = UUID.randomUUID().toString(),
    val input: MediaInput,
    val durationMs: Long = 0L,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = durationMs,
    val speed: Float = 1.0f,
    val volume: Float = 1.0f,
    val muted: Boolean = false,
    val rotateDegrees: Int = 0,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val grayscale: Boolean = false,
    val sepia: Boolean = false,
    val watermarkText: String = "",
    val thumbnailPath: String? = null
) {
    val effectiveDurationMs: Long
        get() {
            val rawSpan = (trimEndMs - trimStartMs).coerceAtLeast(100L)
            return (rawSpan / speed.coerceIn(0.25f, 4.0f)).roundToLong().coerceAtLeast(100L)
        }
}

data class TimelineProject(
    val id: String = ACTIVE_PROJECT_ID,
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val name: String = "Yeni Stüdyo Projesi",
    val clips: List<TimelineClipItem> = emptyList(),
    val externalAudioInput: MediaInput? = null,
    val externalAudioVolume: Float = 0.7f,
    val audioFitMode: AudioFitMode = AudioFitMode.TRIM,
    val exportSettings: MediaJobSettings = MediaJobSettings(),
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    val totalDurationMs: Long
        get() = clips.sumOf { it.effectiveDurationMs }

    companion object {
        const val ACTIVE_PROJECT_ID = "active_project"
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

data class MediaJob(
    val id: String = UUID.randomUUID().toString(),
    val tool: StudioTool,
    val title: String,
    val inputs: List<MediaInput>,
    val settings: MediaJobSettings = MediaJobSettings(),
    val timelineClips: List<TimelineClipItem> = emptyList(),
    val status: JobStatus = JobStatus.QUEUED,
    val progress: Float = 0f,
    val isIndeterminate: Boolean = false,
    val statusMessage: String = "Kuyrukta bekliyor",
    val queueOrder: Int = 0,
    val createdAtMs: Long = System.currentTimeMillis(),
    val startedAtMs: Long? = null,
    val finishedAtMs: Long? = null,
    val outputRecordId: String? = null,
    val outputUriString: String? = null,
    val outputDisplayName: String? = null,
    val outputSizeBytes: Long? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val errorSuggestion: String? = null,
    val wasInterrupted: Boolean = false
)

data class JobEstimate(
    val estimatedOutputBytes: Long,
    val estimatedDurationMs: Long,
    val estimatedProcessingTimeMs: Long,
    val requiredFreeSpaceBytes: Long,
    val hasEnoughStorage: Boolean,
    val disclaimerTr: String = "Bu değer yaklaşık bir tahmindir; gerçek dosya boyutu sahne karmaşıklığına göre değişebilir.",
    val warnings: List<String> = emptyList()
)

sealed class MediaJobEvent {
    abstract val jobId: String

    data class Started(
        override val jobId: String,
        val message: String = "İşlem başlatılıyor..."
    ) : MediaJobEvent()

    data class Progress(
        override val jobId: String,
        val progress: Float,
        val isIndeterminate: Boolean = false,
        val message: String = "İşleniyor..."
    ) : MediaJobEvent()

    data class Completed(
        override val jobId: String,
        val outputUriString: String,
        val outputDisplayName: String,
        val outputMimeType: String,
        val outputFormat: String,
        val outputSizeBytes: Long,
        val durationMs: Long = 0L,
        val width: Int = 0,
        val height: Int = 0,
        val mediaType: MediaType = MediaType.UNKNOWN
    ) : MediaJobEvent()

    data class Failed(
        override val jobId: String,
        val errorCode: String,
        val message: String,
        val suggestion: String
    ) : MediaJobEvent()

    data class Cancelled(
        override val jobId: String,
        val message: String = "İşlem iptal edildi."
    ) : MediaJobEvent()
}

data class CodecSupportInfo(
    val mimeType: String,
    val displayName: String,
    val codecName: String? = null,
    val isEncoderAvailable: Boolean = false,
    val isDecoderAvailable: Boolean = false,
    val isHardwareAccelerated: Boolean = false,
    val noteTr: String = ""
)

data class EngineCapabilities(
    val codecDetails: List<CodecSupportInfo> = emptyList(),
    val supportedVideoOutputFormats: List<String> = listOf("mp4"),
    val supportedVideoCodecs: List<String> = listOf("video/avc"),
    val supportedAudioOutputFormats: List<String> = listOf("m4a", "wav"),
    val supportedImageOutputFormats: List<String> = listOf("jpg", "png", "webp", "bmp"),
    val supportsHevcEncode: Boolean = false,
    val supportsVp9Encode: Boolean = false,
    val supportsAv1Encode: Boolean = false,
    val supportsOpusEncode: Boolean = false,
    val supportsFlacEncode: Boolean = false,
    val supportsCrossfade: Boolean = false,
    val supportsReversePlayback: Boolean = false,
    val deviceSummaryTr: String = ""
)

data class OutputRecord(
    val id: String = UUID.randomUUID().toString(),
    val jobId: String,
    val tool: StudioTool,
    val mediaType: MediaType,
    val displayName: String,
    val format: String,
    val mimeType: String,
    val uriString: String,
    val sizeBytes: Long,
    val durationMs: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val createdAtMs: Long = System.currentTimeMillis()
)

data class AppSettings(
    val themePreference: ThemePreference = ThemePreference.SYSTEM,
    val simpleMode: Boolean = true,
    val defaultQuality: QualityProfile = QualityProfile.BALANCED,
    val defaultVideoFormat: String = "mp4",
    val defaultAudioFormat: String = "m4a",
    val defaultImageFormat: String = "jpg",
    val notificationsEnabled: Boolean = true,
    val autoCleanCache: Boolean = false
)

object MediaFormatUtils {

    private val videoExtensions = setOf(
        "mp4", "m4v", "mkv", "webm", "mov", "avi", "3gp", "3g2", "ts", "mts", "flv", "wmv", "mpg", "mpeg", "ogv"
    )
    private val audioExtensions = setOf(
        "m4a", "mp3", "wav", "aac", "ogg", "oga", "opus", "flac", "amr", "aiff", "wma", "mka", "3ga"
    )
    private val imageExtensions = setOf(
        "jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif", "tiff", "tif", "dng"
    )

    fun extensionFromFileName(name: String): String {
        val clean = name.substringAfterLast('/').substringAfterLast('\\')
        val dotIndex = clean.lastIndexOf('.')
        if (dotIndex <= 0 || dotIndex >= clean.length - 1) return ""
        return clean.substring(dotIndex + 1).lowercase(Locale.ROOT).trim()
    }

    fun inferMediaType(extension: String, mimeType: String? = null): MediaType {
        val ext = extension.lowercase(Locale.ROOT).trimStart('.')
        val mime = mimeType?.lowercase(Locale.ROOT)?.trim().orEmpty()

        if (mime.startsWith("video/")) return MediaType.VIDEO
        if (mime.startsWith("image/")) return MediaType.IMAGE
        if (mime.startsWith("audio/")) return MediaType.AUDIO

        return when {
            ext in videoExtensions -> MediaType.VIDEO
            ext in audioExtensions -> MediaType.AUDIO
            ext in imageExtensions -> MediaType.IMAGE
            else -> MediaType.UNKNOWN
        }
    }

    fun mimeTypeForExtension(extension: String): String {
        return when (extension.lowercase(Locale.ROOT).trimStart('.')) {
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            "3gp" -> "video/3gpp"
            "avi" -> "video/x-msvideo"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "wav" -> "audio/wav"
            "mp3" -> "audio/mpeg"
            "ogg", "oga" -> "audio/ogg"
            "opus" -> "audio/opus"
            "flac" -> "audio/flac"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "gif" -> "image/gif"
            "heic" -> "image/heic"
            "heif" -> "image/heif"
            else -> "application/octet-stream"
        }
    }

    fun isExtensionMismatch(extension: String, detectedMimeType: String?): Boolean {
        val ext = extension.lowercase(Locale.ROOT).trimStart('.')
        val mime = detectedMimeType?.lowercase(Locale.ROOT)?.trim().orEmpty()
        if (ext.isBlank() || mime.isBlank() || mime == "application/octet-stream") return false

        val extType = inferMediaType(ext, null)
        val mimeType = inferMediaType("", mime)
        if (extType != MediaType.UNKNOWN && mimeType != MediaType.UNKNOWN && extType != mimeType) {
            // m4a bazen container nedeniyle video/mp4 veya audio/mp4 raporlanabilir
            if (ext == "m4a" && mime == "video/mp4") return false
            return true
        }

        val expectedSubtypes = when (ext) {
            "jpg", "jpeg" -> setOf("jpeg", "jpg")
            "png" -> setOf("png")
            "webp" -> setOf("webp")
            "bmp" -> setOf("bmp", "x-ms-bmp")
            "gif" -> setOf("gif")
            "heic", "heif" -> setOf("heic", "heif")
            "mp4", "m4v" -> setOf("mp4", "x-m4v")
            "webm" -> setOf("webm")
            "mkv" -> setOf("x-matroska", "matroska")
            "mov" -> setOf("quicktime")
            "wav" -> setOf("wav", "x-wav", "vnd.wave", "raw")
            "mp3" -> setOf("mpeg", "mp3")
            "flac" -> setOf("flac", "x-flac")
            "ogg", "oga", "opus" -> setOf("ogg", "opus")
            "m4a", "aac" -> setOf("mp4", "mp4a-latm", "aac", "x-aac")
            else -> emptySet()
        }
        if (expectedSubtypes.isEmpty()) return false
        val actualSubtype = mime.substringAfter('/', "")
        return actualSubtype.isNotBlank() && actualSubtype !in expectedSubtypes
    }

    fun sanitizeBaseName(rawName: String, fallback: String = "avenlyx_cikti"): String {
        val withoutExt = rawName.substringBeforeLast('.').trim()
        val cleaned = withoutExt
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]+"), "_")
            .replace(Regex("\\s+"), "_")
            .trim('_', '.')
        return cleaned.ifBlank { fallback }.take(80)
    }

    fun buildUniqueDisplayName(
        baseName: String,
        extension: String,
        existingNames: Set<String>
    ): String {
        val safeBase = sanitizeBaseName(baseName)
        val ext = extension.lowercase(Locale.ROOT).trimStart('.')
        val suffix = if (ext.isNotBlank()) ".$ext" else ""
        val lowerExisting = existingNames.map { it.lowercase(Locale.ROOT) }.toSet()

        val initial = "$safeBase$suffix"
        if (initial.lowercase(Locale.ROOT) !in lowerExisting) return initial

        var index = 1
        while (index < 10_000) {
            val candidate = "${safeBase}_$index$suffix"
            if (candidate.lowercase(Locale.ROOT) !in lowerExisting) return candidate
            index++
        }
        return "${safeBase}_${System.currentTimeMillis()}$suffix"
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unitIdx = 0
        while (value >= 1024.0 && unitIdx < units.lastIndex) {
            value /= 1024.0
            unitIdx++
        }
        return if (unitIdx == 0) {
            "${bytes} B"
        } else {
            String.format(Locale.getDefault(), "%.1f %s", value, units[unitIdx])
        }
    }

    fun formatDurationMs(durationMs: Long): String {
        if (durationMs <= 0L) return "00:00"
        val totalSeconds = durationMs / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
        }
    }

    fun estimateOutputBytes(
        tool: StudioTool,
        durationMs: Long,
        inputSizeBytes: Long,
        settings: MediaJobSettings
    ): Long {
        val safeDurationSec = max(1.0, durationMs / 1000.0)
        val outExt = settings.outputFormat.lowercase(Locale.ROOT)

        return when {
            tool == StudioTool.IMAGE_CONVERT || tool == StudioTool.IMAGE_COMPRESS -> {
                val baseSize = max(16_384L, inputSizeBytes)
                val scaleFactor = (settings.imageScalePercent.coerceIn(10, 200) / 100.0).let { it * it }
                val formatFactor = when (outExt) {
                    "png" -> 1.35
                    "bmp" -> 3.5
                    "webp" -> 0.55 * (settings.imageQuality.coerceIn(10, 100) / 85.0)
                    else -> 0.65 * (settings.imageQuality.coerceIn(10, 100) / 85.0)
                }
                (baseSize * scaleFactor * formatFactor).roundToLong().coerceAtLeast(8_192L)
            }

            tool == StudioTool.VIDEO_EXTRACT_AUDIO || tool == StudioTool.AUDIO_CONVERT || tool == StudioTool.AUDIO_EDIT_MERGE -> {
                if (outExt == "wav") {
                    val sampleRate = settings.audioSampleRateHz.coerceIn(8_000, 96_000)
                    val channels = settings.audioChannels.coerceIn(1, 2)
                    (safeDurationSec * sampleRate * channels * 2 + 44).roundToLong()
                } else {
                    val bps = settings.audioBitrateBps.coerceIn(48_000, 512_000)
                    ((safeDurationSec * bps) / 8.0 * 1.03).roundToLong().coerceAtLeast(4_096L)
                }
            }

            else -> {
                val baseVideoBitrate = settings.videoBitrateBps?.toDouble()
                    ?: (2_600_000.0 * settings.qualityProfile.bitrateMultiplier)
                val audioBitrate = if (settings.removeAudio) 0.0 else settings.audioBitrateBps.toDouble()
                val speedAdjustedSec = safeDurationSec / settings.playbackSpeed.coerceIn(0.25f, 4.0f)
                (((baseVideoBitrate + audioBitrate) * speedAdjustedSec) / 8.0 * 1.04)
                    .roundToLong()
                    .coerceAtLeast(32_768L)
            }
        }
    }
}

