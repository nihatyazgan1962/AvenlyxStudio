package com.formatci.mediastudio.media.capability

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import com.formatci.mediastudio.domain.model.CodecSupportInfo
import com.formatci.mediastudio.domain.model.EngineCapabilities

/**
 * Cihazdaki gerçek donanım ve yazılım MediaCodec encoder/decoder yeteneklerini tarar.
 * Desteklenmeyen formatları asla destekli göstermez.
 */
class EngineCapabilityRegistry {

    @Volatile
    private var cachedCapabilities: EngineCapabilities? = null

    fun getCapabilities(forceRefresh: Boolean = false): EngineCapabilities {
        val current = cachedCapabilities
        if (current != null && !forceRefresh) return current
        return scanDeviceCapabilities().also { cachedCapabilities = it }
    }

    fun isVideoCodecSupported(mimeType: String): Boolean {
        val normalized = mimeType.lowercase()
        val caps = getCapabilities()
        return caps.codecDetails.any {
            it.mimeType.equals(normalized, ignoreCase = true) && it.isEncoderAvailable
        }
    }

    fun isAudioOutputSupported(extension: String): Boolean {
        return extension.lowercase().trimStart('.') in getCapabilities().supportedAudioOutputFormats
    }

    fun isVideoOutputSupported(extension: String): Boolean {
        return extension.lowercase().trimStart('.') in getCapabilities().supportedVideoOutputFormats
    }

    private fun scanDeviceCapabilities(): EngineCapabilities {
        val codecInfos: Array<MediaCodecInfo> = try {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos ?: emptyArray()
        } catch (_: Throwable) {
            emptyArray()
        }

        val avc = inspectCodec(
            codecInfos = codecInfos,
            mimeType = "video/avc",
            displayName = "H.264 (AVC)",
            noteWhenSupported = "Tüm telefon, TV ve tarayıcılarla en uyumlu video kodeği",
            noteWhenMissing = "H.264 kodlayıcı tespit edilemedi"
        )
        val hevc = inspectCodec(
            codecInfos = codecInfos,
            mimeType = "video/hevc",
            displayName = "H.265 (HEVC)",
            noteWhenSupported = "Daha küçük dosya boyutu; bazı eski cihazlarda oynatımı sınırlı olabilir",
            noteWhenMissing = "Bu cihazda HEVC donanım/yazılım kodlayıcı bulunmuyor"
        )
        val vp9 = inspectCodec(
            codecInfos = codecInfos,
            mimeType = "video/x-vnd.on2.vp9",
            displayName = "VP9 (WebM)",
            noteWhenSupported = "WebM konteyneri için açık kaynak video kodeği",
            noteWhenMissing = "Bu cihazda VP9 kodlayıcı bulunmuyor"
        )
        val av1 = inspectCodec(
            codecInfos = codecInfos,
            mimeType = "video/av01",
            displayName = "AV1",
            noteWhenSupported = "Yüksek sıkıştırma verimli yeni nesil kodek",
            noteWhenMissing = "Bu cihazda AV1 kodlayıcı bulunmuyor"
        )
        val aac = inspectCodec(
            codecInfos = codecInfos,
            mimeType = "audio/mp4a-latm",
            displayName = "AAC (M4A)",
            noteWhenSupported = "Tüm cihazlarla yüksek uyumlu sıkıştırılmış ses",
            noteWhenMissing = "AAC kodlayıcı tespit edilemedi"
        )
        val opus = inspectCodec(
            codecInfos = codecInfos,
            mimeType = "audio/opus",
            displayName = "OPUS",
            noteWhenSupported = "Düşük bitrate değerlerinde net ses ve WebM ses desteği",
            noteWhenMissing = "Bu cihazda OPUS kodlayıcı bulunmuyor"
        )
        val flac = inspectCodec(
            codecInfos = codecInfos,
            mimeType = "audio/flac",
            displayName = "FLAC",
            noteWhenSupported = "Kayıpsız sıkıştırılmış ses kodlaması",
            noteWhenMissing = "Bu cihazda FLAC kodlayıcı bulunmuyor (yalnızca çözücü olabilir)"
        )

        val supportsHevc = hevc.isEncoderAvailable
        val supportsAv1 = av1.isEncoderAvailable
        // WebM muxing (VP9 + Opus) API 29+ MediaMuxer ile güvenle desteklenir.
        val supportsWebmMux = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val supportsVp9 = vp9.isEncoderAvailable && opus.isEncoderAvailable && supportsWebmMux

        val videoCodecs = buildList {
            if (avc.isEncoderAvailable || codecInfos.isEmpty()) add("video/avc")
            if (supportsHevc) add("video/hevc")
            if (supportsVp9) add("video/x-vnd.on2.vp9")
            if (supportsAv1) add("video/av01")
        }

        val videoFormats = buildList {
            add("mp4")
            if (supportsVp9) add("webm")
        }

        // WAV PCM yerleşik RIFF yazıcısı sayesinde her cihazda %100 garantilidir.
        // M4A (AAC) kodlayıcı varsa eklenir; OPUS (API 29+ OGG muxer) ve FLAC yalnızca encoder varsa eklenir.
        val supportsOpusOutput = opus.isEncoderAvailable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val supportsFlacOutput = flac.isEncoderAvailable

        val audioFormats = buildList {
            if (aac.isEncoderAvailable || codecInfos.isEmpty()) add("m4a")
            add("wav")
            if (supportsOpusOutput) add("opus")
            if (supportsFlacOutput) add("flac")
        }

        val codecDetails = listOf(avc, hevc, vp9, av1, aac, opus, flac)
        val hwEncCount = codecDetails.count { it.isEncoderAvailable && it.isHardwareAccelerated }
        val activeVideoLabels = buildList {
            if ("video/avc" in videoCodecs) add("H.264")
            if (supportsHevc) add("HEVC")
            if (supportsVp9) add("VP9")
            if (supportsAv1) add("AV1")
        }

        val summary = buildString {
            append("Video: ${activeVideoLabels.joinToString(", ")}")
            append(" | Ses: ${audioFormats.joinToString(", ") { it.uppercase() }}")
            append(" | Donanım hızlandırma: $hwEncCount kodlayıcı")
        }

        return EngineCapabilities(
            codecDetails = codecDetails,
            supportedVideoOutputFormats = videoFormats,
            supportedVideoCodecs = videoCodecs,
            supportedAudioOutputFormats = audioFormats,
            supportedImageOutputFormats = listOf("jpg", "png", "webp", "bmp"),
            supportsHevcEncode = supportsHevc,
            supportsVp9Encode = supportsVp9,
            supportsAv1Encode = supportsAv1,
            supportsOpusEncode = supportsOpusOutput,
            supportsFlacEncode = supportsFlacOutput,
            supportsCrossfade = false,
            supportsReversePlayback = false,
            deviceSummaryTr = summary
        )
    }

    private fun inspectCodec(
        codecInfos: Array<MediaCodecInfo>,
        mimeType: String,
        displayName: String,
        noteWhenSupported: String,
        noteWhenMissing: String
    ): CodecSupportInfo {
        val matching = codecInfos.filter { info ->
            info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
        }
        val encoders = matching.filter { it.isEncoder }
        val decoders = matching.filter { !it.isEncoder }

        val preferredEncoder = encoders.firstOrNull { isHardwareCodec(it) } ?: encoders.firstOrNull()
        val encAvailable = preferredEncoder != null
        val decAvailable = decoders.isNotEmpty()
        val hwAccelerated = preferredEncoder?.let { isHardwareCodec(it) } ?: false

        return CodecSupportInfo(
            mimeType = mimeType,
            displayName = displayName,
            codecName = preferredEncoder?.name,
            isEncoderAvailable = encAvailable,
            isDecoderAvailable = decAvailable,
            isHardwareAccelerated = hwAccelerated,
            noteTr = if (encAvailable) noteWhenSupported else noteWhenMissing
        )
    }

    private fun isHardwareCodec(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return info.isHardwareAccelerated
        }
        val name = info.name.lowercase()
        return !name.startsWith("omx.google.") &&
            !name.startsWith("c2.android.") &&
            !name.contains(".sw.")
    }
}
