#include "formats.h"

#include <QHash>

QString Formats::normalizeExt(const QString& ext) {
    QString clean = ext.trimmed().toLower();
    while (clean.startsWith(QLatin1Char('.'))) {
        clean.remove(0, 1);
    }

    static const QHash<QString, QString> aliases = {
        {QStringLiteral("jpeg"), QStringLiteral("jpg")},
        {QStringLiteral("jpe"),  QStringLiteral("jpg")},
        {QStringLiteral("jfif"), QStringLiteral("jpg")},
        {QStringLiteral("heif"), QStringLiteral("heic")},
        {QStringLiteral("tif"),  QStringLiteral("tiff")},
        {QStringLiteral("mpeg"), QStringLiteral("mpg")},
        {QStringLiteral("m2ts"), QStringLiteral("ts")},
        {QStringLiteral("mts"),  QStringLiteral("ts")},
        {QStringLiteral("oga"),  QStringLiteral("ogg")},
        {QStringLiteral("aif"),  QStringLiteral("aiff")},
        {QStringLiteral("wave"), QStringLiteral("wav")}
    };
    return aliases.value(clean, clean);
}

QList<FormatInfo> Formats::imageFormats() {
    static const QList<FormatInfo> list = {
        {QStringLiteral("png"),  QStringLiteral("PNG"),         MediaCategory::Image, {}, {}, true},
        {QStringLiteral("jpg"),  QStringLiteral("JPG / JPEG"),  MediaCategory::Image, {}, {}, false},
        {QStringLiteral("webp"), QStringLiteral("WebP"),        MediaCategory::Image, {}, {}, true},
        {QStringLiteral("avif"), QStringLiteral("AVIF"),        MediaCategory::Image, {}, {}, true},
        {QStringLiteral("heic"), QStringLiteral("HEIC"),        MediaCategory::Image, {}, {}, true},
        {QStringLiteral("bmp"),  QStringLiteral("BMP"),         MediaCategory::Image, {}, {}, false},
        {QStringLiteral("tiff"), QStringLiteral("TIFF"),        MediaCategory::Image, {}, {}, true},
        {QStringLiteral("gif"),  QStringLiteral("GIF"),         MediaCategory::Image, {}, {}, true},
        {QStringLiteral("ico"),  QStringLiteral("ICO (Simge)"), MediaCategory::Image, {}, {}, true},
        {QStringLiteral("tga"),  QStringLiteral("TGA"),         MediaCategory::Image, {}, {}, true},
        {QStringLiteral("ppm"),  QStringLiteral("PPM"),         MediaCategory::Image, {}, {}, false},
        {QStringLiteral("pdf"),  QStringLiteral("PDF (Belge)"), MediaCategory::Image, {}, {}, false}
    };
    return list;
}

QList<FormatInfo> Formats::videoFormats() {
    static const QList<FormatInfo> list = {
        {QStringLiteral("mp4"),  QStringLiteral("MP4 (H.264/AAC)"),   MediaCategory::Video, QStringLiteral("libx264"),    QStringLiteral("aac"),        false},
        {QStringLiteral("mkv"),  QStringLiteral("MKV (Matroska)"),    MediaCategory::Video, QStringLiteral("libx264"),    QStringLiteral("aac"),        false},
        {QStringLiteral("webm"), QStringLiteral("WebM (VP9/Opus)"),   MediaCategory::Video, QStringLiteral("libvpx-vp9"), QStringLiteral("libopus"),    false},
        {QStringLiteral("mov"),  QStringLiteral("MOV (QuickTime)"),   MediaCategory::Video, QStringLiteral("libx264"),    QStringLiteral("aac"),        false},
        {QStringLiteral("avi"),  QStringLiteral("AVI"),               MediaCategory::Video, QStringLiteral("mpeg4"),      QStringLiteral("libmp3lame"), false},
        {QStringLiteral("m4v"),  QStringLiteral("M4V"),               MediaCategory::Video, QStringLiteral("libx264"),    QStringLiteral("aac"),        false},
        {QStringLiteral("flv"),  QStringLiteral("FLV (Flash)"),       MediaCategory::Video, QStringLiteral("flv1"),       QStringLiteral("libmp3lame"), false},
        {QStringLiteral("wmv"),  QStringLiteral("WMV (Windows)"),     MediaCategory::Video, QStringLiteral("wmv2"),       QStringLiteral("wmav2"),      false},
        {QStringLiteral("3gp"),  QStringLiteral("3GP (Mobil)"),       MediaCategory::Video, QStringLiteral("libx264"),    QStringLiteral("aac"),        false},
        {QStringLiteral("ts"),   QStringLiteral("MPEG-TS"),           MediaCategory::Video, QStringLiteral("libx264"),    QStringLiteral("aac"),        false},
        {QStringLiteral("mpg"),  QStringLiteral("MPG / MPEG"),        MediaCategory::Video, QStringLiteral("mpeg2video"), QStringLiteral("mp2"),        false},
        {QStringLiteral("ogv"),  QStringLiteral("OGV (Ogg Video)"),   MediaCategory::Video, QStringLiteral("libtheora"),  QStringLiteral("libvorbis"),  false},
        {QStringLiteral("gif"),  QStringLiteral("GIF (Animasyonlu)"), MediaCategory::Video, QStringLiteral("gif"),        {},                           true}
    };
    return list;
}

QList<FormatInfo> Formats::audioFormats() {
    static const QList<FormatInfo> list = {
        {QStringLiteral("mp3"),  QStringLiteral("MP3"),                  MediaCategory::Audio, {}, QStringLiteral("libmp3lame"), false},
        {QStringLiteral("wav"),  QStringLiteral("WAV (Kayıpsız)"),       MediaCategory::Audio, {}, QStringLiteral("pcm_s16le"),  false},
        {QStringLiteral("flac"), QStringLiteral("FLAC (Kayıpsız)"),      MediaCategory::Audio, {}, QStringLiteral("flac"),       false},
        {QStringLiteral("aac"),  QStringLiteral("AAC"),                  MediaCategory::Audio, {}, QStringLiteral("aac"),        false},
        {QStringLiteral("m4a"),  QStringLiteral("M4A (AAC)"),            MediaCategory::Audio, {}, QStringLiteral("aac"),        false},
        {QStringLiteral("ogg"),  QStringLiteral("OGG (Vorbis)"),         MediaCategory::Audio, {}, QStringLiteral("libvorbis"),  false},
        {QStringLiteral("opus"), QStringLiteral("OPUS"),                 MediaCategory::Audio, {}, QStringLiteral("libopus"),    false},
        {QStringLiteral("wma"),  QStringLiteral("WMA"),                  MediaCategory::Audio, {}, QStringLiteral("wmav2"),      false},
        {QStringLiteral("aiff"), QStringLiteral("AIFF"),                 MediaCategory::Audio, {}, QStringLiteral("pcm_s16be"),  false},
        {QStringLiteral("ac3"),  QStringLiteral("AC3 (Dolby)"),          MediaCategory::Audio, {}, QStringLiteral("ac3"),        false},
        {QStringLiteral("mka"),  QStringLiteral("MKA (Matroska Audio)"), MediaCategory::Audio, {}, QStringLiteral("aac"),        false}
    };
    return list;
}

MediaCategory Formats::detectCategory(const QString& ext) {
    const QString norm = normalizeExt(ext);
    if (norm.isEmpty()) {
        return MediaCategory::Unknown;
    }
    for (const auto& f : imageFormats()) {
        if (f.ext == norm) {
            return MediaCategory::Image;
        }
    }
    for (const auto& f : videoFormats()) {
        if (f.ext == norm && f.ext != QLatin1String("gif")) {
            return MediaCategory::Video;
        }
    }
    for (const auto& f : audioFormats()) {
        if (f.ext == norm) {
            return MediaCategory::Audio;
        }
    }
    return MediaCategory::Unknown;
}

QString Formats::categoryNameTr(MediaCategory cat) {
    switch (cat) {
        case MediaCategory::Image: return QStringLiteral("Resim");
        case MediaCategory::Video: return QStringLiteral("Video");
        case MediaCategory::Audio: return QStringLiteral("Ses");
        default:                   return QStringLiteral("Bilinmeyen");
    }
}

QList<FormatInfo> Formats::validTargetsFor(MediaCategory sourceCat) {
    if (sourceCat == MediaCategory::Image) {
        return imageFormats();
    }
    if (sourceCat == MediaCategory::Video) {
        QList<FormatInfo> list = videoFormats();
        list.append(audioFormats());
        return list;
    }
    if (sourceCat == MediaCategory::Audio) {
        return audioFormats();
    }
    return {};
}

FormatInfo Formats::findFormat(const QString& ext) {
    const QString norm = normalizeExt(ext);
    if (norm.isEmpty()) {
        return {};
    }
    for (const auto& f : imageFormats()) {
        if (f.ext == norm) {
            return f;
        }
    }
    for (const auto& f : videoFormats()) {
        if (f.ext == norm) {
            return f;
        }
    }
    for (const auto& f : audioFormats()) {
        if (f.ext == norm) {
            return f;
        }
    }
    return {};
}

QStringList Formats::allSupportedInputExtensions() {
    QStringList exts;
    auto appendUnique = [&exts](const QString& e) {
        if (!exts.contains(e)) {
            exts.append(e);
        }
    };

    for (const auto& f : imageFormats()) {
        if (f.ext != QLatin1String("pdf")) {
            appendUnique(f.ext);
        }
    }
    appendUnique(QStringLiteral("jpeg"));
    appendUnique(QStringLiteral("jpe"));
    appendUnique(QStringLiteral("jfif"));
    appendUnique(QStringLiteral("heif"));
    appendUnique(QStringLiteral("tif"));

    for (const auto& f : videoFormats()) {
        appendUnique(f.ext);
    }
    appendUnique(QStringLiteral("mpeg"));
    appendUnique(QStringLiteral("m2ts"));
    appendUnique(QStringLiteral("mts"));

    for (const auto& f : audioFormats()) {
        appendUnique(f.ext);
    }
    appendUnique(QStringLiteral("oga"));
    appendUnique(QStringLiteral("aif"));
    appendUnique(QStringLiteral("wave"));

    return exts;
}
