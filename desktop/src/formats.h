#pragma once

#include <QList>
#include <QString>
#include <QStringList>

enum class MediaCategory {
    Unknown,
    Image,
    Video,
    Audio
};

struct FormatInfo {
    QString ext;
    QString label;
    MediaCategory category = MediaCategory::Unknown;
    QString vcodec;
    QString acodec;
    bool supportsAlpha = false;
};

class Formats {
public:
    static QString normalizeExt(const QString& ext);
    static MediaCategory detectCategory(const QString& ext);
    static QString categoryNameTr(MediaCategory cat);

    static QList<FormatInfo> imageFormats();
    static QList<FormatInfo> videoFormats();
    static QList<FormatInfo> audioFormats();
    static QList<FormatInfo> validTargetsFor(MediaCategory sourceCat);

    static FormatInfo findFormat(const QString& ext);
    static QStringList allSupportedInputExtensions();
};
