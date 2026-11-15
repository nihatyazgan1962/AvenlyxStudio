#pragma once

#include "formats.h"

#include <QList>
#include <QString>

struct AudioStreamInfo {
    int streamIndex = 0;
    QString codec;
    int sampleRate = 0;
    int channels = 0;
    QString language;
    QString title;
};

struct MediaInfo {
    QString filePath;
    QString fileName;
    QString sourceExt;
    MediaCategory category = MediaCategory::Unknown;
    qint64 sizeBytes = 0;
    int width = 0;
    int height = 0;
    double durationSec = 0.0;
    double fps = 0.0;
    QString vcodec;
    QString acodec;
    int sampleRate = 0;
    int channels = 0;
    QList<AudioStreamInfo> audioStreams;

    QString summaryText() const;
    static QString formatBytes(qint64 bytes);
    static QString formatDuration(double sec);
    static QString formatTimecode(double sec);
};

class MediaProbe {
public:
    static MediaInfo probe(const QString& filePath);
};
