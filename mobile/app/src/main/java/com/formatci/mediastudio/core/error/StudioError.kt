package com.formatci.mediastudio.core.error

import android.media.MediaCodec
import kotlinx.coroutines.CancellationException
import java.io.FileNotFoundException
import java.io.IOException

enum class StudioErrorCode {
    FILE_NOT_FOUND,
    URI_PERMISSION_LOST,
    CORRUPT_MEDIA,
    EXTENSION_MISMATCH,
    CODEC_UNSUPPORTED,
    NO_ENCODER,
    LOW_STORAGE,
    IO_READ_ERROR,
    THERMAL_OR_RESOURCE,
    CANCELLED,
    SERVICE_STOPPED,
    OUTPUT_CREATE_FAILED,
    INVALID_RESOLUTION_FPS,
    DURATION_MISMATCH
}

data class StudioErrorInfo(
    val code: StudioErrorCode,
    val titleTr: String,
    val messageTr: String,
    val suggestionTr: String,
    val technicalDetail: String? = null
)

class StudioException(
    val error: StudioErrorInfo,
    cause: Throwable? = null
) : Exception(error.messageTr, cause) {
    constructor(
        code: StudioErrorCode,
        detail: String? = null,
        cause: Throwable? = null
    ) : this(StudioErrorMapper.fromCode(code, detail), cause)
}

object StudioErrorMapper {

    fun fromCode(code: StudioErrorCode, detail: String? = null): StudioErrorInfo {
        val base = when (code) {
            StudioErrorCode.FILE_NOT_FOUND -> StudioErrorInfo(
                code = code,
                titleTr = "Dosya Bulunamadı",
                messageTr = "Seçilen medya dosyası artık konumunda bulunamıyor veya silinmiş.",
                suggestionTr = "Dosyayı seçiciden yeniden seçip tekrar deneyin."
            )

            StudioErrorCode.URI_PERMISSION_LOST -> StudioErrorInfo(
                code = code,
                titleTr = "Dosya Erişim İzni Kaybedildi",
                messageTr = "Android bu dosya için verilen geçici okuma iznini sonlandırmış.",
                suggestionTr = "Dosyayı \"Medya Seç\" üzerinden tekrar içe aktarın."
            )

            StudioErrorCode.CORRUPT_MEDIA -> StudioErrorInfo(
                code = code,
                titleTr = "Bozuk veya Okunamayan Medya",
                messageTr = "Dosya başlığı veya medya akışı bozuk olduğu için çözümlenemedi.",
                suggestionTr = "Dosyanın başka bir oynatıcıda açıldığını doğrulayın veya farklı bir kaynak dosya kullanın."
            )

            StudioErrorCode.EXTENSION_MISMATCH -> StudioErrorInfo(
                code = code,
                titleTr = "Dosya Uzantısı Uyumsuzluğu",
                messageTr = "Dosyanın uzantısı gerçek içindeki medya biçimiyle uyuşmuyor.",
                suggestionTr = "Dosya uzantısını gerçek biçimine uygun şekilde düzeltip yeniden deneyin."
            )

            StudioErrorCode.CODEC_UNSUPPORTED -> StudioErrorInfo(
                code = code,
                titleTr = "Desteklenmeyen Kodek veya Format",
                messageTr = "Bu dosyanın video/ses sıkıştırma biçimi cihazın yerel çözücüleri tarafından desteklenmiyor.",
                suggestionTr = "Çıktı formatı olarak uyumlu MP4 (H.264) veya WAV/M4A seçin."
            )

            StudioErrorCode.NO_ENCODER -> StudioErrorInfo(
                code = code,
                titleTr = "Uygun Kodlayıcı (Encoder) Bulunamadı",
                messageTr = "Seçilen hedef kodek (ör. HEVC/VP9/OPUS) için bu cihazda çalışan donanım veya yazılım kodlayıcı yok.",
                suggestionTr = "Video için H.264 (MP4), ses için M4A (AAC) veya WAV formatını seçin."
            )

            StudioErrorCode.LOW_STORAGE -> StudioErrorInfo(
                code = code,
                titleTr = "Yetersiz Depolama Alanı",
                messageTr = "Çıktı dosyasını ve geçici işlem verilerini yazmak için cihazda yeterli boş alan yok.",
                suggestionTr = "Ayarlar'dan geçici önbelleği temizleyin veya cihazda yer açıp tekrar deneyin."
            )

            StudioErrorCode.IO_READ_ERROR -> StudioErrorInfo(
                code = code,
                titleTr = "Dosya Okuma / Yazma Hatası",
                messageTr = "Medya verisi okunurken beklenmeyen bir giriş/çıkış (I/O) kesintisi oluştu.",
                suggestionTr = "Dosya bulut/USB deposundaysa önce cihaz hafızasına indirip tekrar deneyin."
            )

            StudioErrorCode.THERMAL_OR_RESOURCE -> StudioErrorInfo(
                code = code,
                titleTr = "Cihaz Kaynağı veya Isınma Sınırı",
                messageTr = "Yüksek çözünürlük veya bellek/donanım kısıtı nedeniyle medya kodlayıcı durduruldu.",
                suggestionTr = "Çözünürlüğü (ör. 720p) veya kalite profilini düşürüp arka plan uygulamalarını kapatın."
            )

            StudioErrorCode.CANCELLED -> StudioErrorInfo(
                code = code,
                titleTr = "İşlem İptal Edildi",
                messageTr = "Dönüştürme işlemi kullanıcı isteğiyle durduruldu ve geçici dosyalar temizlendi.",
                suggestionTr = "Dilerseniz Kuyruk sekmesinden \"Tekrar Dene\" ile yeniden başlatabilirsiniz."
            )

            StudioErrorCode.SERVICE_STOPPED -> StudioErrorInfo(
                code = code,
                titleTr = "Arka Plan İşlemi Kesintiye Uğradı",
                messageTr = "İşlem devam ederken uygulama veya sistem arka plan servisi sonlandırıldı.",
                suggestionTr = "Kuyruk sekmesindeki \"Tekrar Dene\" düğmesiyle kaldığınız işi yeniden başlatabilirsiniz."
            )

            StudioErrorCode.OUTPUT_CREATE_FAILED -> StudioErrorInfo(
                code = code,
                titleTr = "Çıktı Dosyası Oluşturulamadı",
                messageTr = "İşlenen medya cihazın indirilenler/medya klasörüne kaydedilemedi.",
                suggestionTr = "Depolama alanını ve dosya adını kontrol edip işlemi yeniden deneyin."
            )

            StudioErrorCode.INVALID_RESOLUTION_FPS -> StudioErrorInfo(
                code = code,
                titleTr = "Geçersiz Çözünürlük veya FPS Değeri",
                messageTr = "Seçilen genişlik, yükseklik veya kare hızı donanım kodlayıcı sınırlarıyla uyumsuz.",
                suggestionTr = "Çift sayı olan standart çözünürlükleri (1080p, 720p, 480p) ve 24/30/60 FPS seçin."
            )

            StudioErrorCode.DURATION_MISMATCH -> StudioErrorInfo(
                code = code,
                titleTr = "Süre veya Kesme Aralığı Geçersiz",
                messageTr = "Başlangıç zamanı bitiş zamanından büyük ya da seçilen aralık işlenemeyecek kadar kısa.",
                suggestionTr = "Kesme tutamaçlarını en az 0.2 saniye aralık kalacak şekilde ayarlayın."
            )
        }
        return base.copy(technicalDetail = detail?.takeIf { it.isNotBlank() })
    }

    fun fromThrowable(
        throwable: Throwable,
        fallbackCode: StudioErrorCode = StudioErrorCode.CORRUPT_MEDIA
    ): StudioErrorInfo {
        if (throwable is StudioException) {
            return throwable.error
        }
        if (throwable is CancellationException) {
            return fromCode(StudioErrorCode.CANCELLED, throwable.message)
        }

        val msg = (throwable.message ?: throwable.cause?.message ?: "").lowercase()
        val detail = "${throwable::class.java.simpleName}: ${throwable.message ?: "Detay yok"}"

        val code = when {
            throwable is SecurityException || "permission" in msg || "security" in msg ->
                StudioErrorCode.URI_PERMISSION_LOST

            throwable is FileNotFoundException || "enoent" in msg || "not found" in msg ->
                StudioErrorCode.FILE_NOT_FOUND

            "enospc" in msg || "no space" in msg || "storage" in msg || "disk full" in msg ->
                StudioErrorCode.LOW_STORAGE

            throwable is OutOfMemoryError || "thermal" in msg || "insufficient resources" in msg ||
                "resource" in msg || "reclaimed" in msg ->
                StudioErrorCode.THERMAL_OR_RESOURCE

            "encoder" in msg || "no suitable encoder" in msg ->
                StudioErrorCode.NO_ENCODER

            "unsupported" in msg || "mime" in msg || "format not supported" in msg ->
                StudioErrorCode.CODEC_UNSUPPORTED

            "resolution" in msg || "frame rate" in msg || "fps" in msg || "dimensions" in msg ->
                StudioErrorCode.INVALID_RESOLUTION_FPS

            "duration" in msg || "clipping" in msg || "trim" in msg ->
                StudioErrorCode.DURATION_MISMATCH

            "mediastore" in msg || "output" in msg || "create file" in msg ->
                StudioErrorCode.OUTPUT_CREATE_FAILED

            throwable is MediaCodec.CodecException ->
                if (throwable.isTransient) StudioErrorCode.THERMAL_OR_RESOURCE else StudioErrorCode.CODEC_UNSUPPORTED

            throwable is IOException ->
                StudioErrorCode.IO_READ_ERROR

            else -> fallbackCode
        }

        return fromCode(code, detail)
    }
}

