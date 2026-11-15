#include "mediaprobe.h"

#include <QFileInfo>
#include <QImageIOHandler>
#include <QImageReader>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QProcess>
#include <QStringList>
#include <QVariant>
#include <cmath>

namespace {

int jsonToInt(const QJsonValue& val) {
    if (val.isDouble()) {
        return val.toInt();
    }
    if (val.isString()) {
        bool ok = false;
        const int parsed = val.toString().toInt(&ok);
        return ok ? parsed : 0;
    }
    return val.toVariant().toInt();
}

double jsonToDouble(const QJsonValue& val) {
    if (val.isDouble()) {
        return val.toDouble();
    }
    if (val.isString()) {
        bool ok = false;
        const double parsed = val.toString().toDouble(&ok);
        return (ok && std::isfinite(parsed)) ? parsed : 0.0;
    }
    const double v = val.toVariant().toDouble();
    return std::isfinite(v) ? v : 0.0;
}

double parseFrameRate(const QString& fracStr) {
    const QStringList frac = fracStr.split(QLatin1Char('/'), Qt::SkipEmptyParts);
    if (frac.size() == 2) {
        const double num = frac[0].toDouble();
        const double den = frac[1].toDouble();
        if (den > 0.0 && num > 0.0 && std::isfinite(num / den)) {
            return num / den;
        }
    } else if (frac.size() == 1) {
        const double val = frac[0].toDouble();
        if (val > 0.0 && std::isfinite(val)) {
            return val;
        }
    }
    return 0.0;
}

double parseDurationTag(const QString& text) {
    const QString trimmed = text.trimmed();
    if (trimmed.isEmpty()) {
        return 0.0;
    }
    // Try HH:MM:SS(.fff) format often found in MKV/WebM tags
    const QStringList parts = trimmed.split(QLatin1Char(':'), Qt::SkipEmptyParts);
    if (parts.size() == 3) {
        bool okH = false, okM = false, okS = false;
        const double h = parts[0].toDouble(&okH);
        const double m = parts[1].toDouble(&okM);
        const double s = parts[2].toDouble(&okS);
        if (okH && okM && okS) {
            const double total = h * 3600.0 + m * 60.0 + s;
            return (total > 0.0 && std::isfinite(total)) ? total : 0.0;
        }
    }
    bool ok = false;
    const double val = trimmed.toDouble(&ok);
    return (ok && val > 0.0 && std::isfinite(val)) ? val : 0.0;
}

QString readTagCaseInsensitive(const QJsonObject& tags, const QString& key) {
    QString v = tags.value(key).toString();
    if (v.isEmpty()) {
        v = tags.value(key.toUpper()).toString();
    }
    if (v.isEmpty()) {
        v = tags.value(key.toLower()).toString();
    }
    return v.trimmed();
}

} // namespace

QString MediaInfo::formatBytes(qint64 bytes) {
    if (bytes < 0) {
        bytes = 0;
    }
    if (bytes < 1024) {
        return QStringLiteral("%1 B").arg(bytes);
    }
    const double kb = bytes / 1024.0;
    if (kb < 1024.0) {
        return QStringLiteral("%1 KB").arg(kb, 0, 'f', 1);
    }
    const double mb = kb / 1024.0;
    if (mb < 1024.0) {
        return QStringLiteral("%1 MB").arg(mb, 0, 'f', 2);
    }
    const double gb = mb / 1024.0;
    return QStringLiteral("%1 GB").arg(gb, 0, 'f', 2);
}

QString MediaInfo::formatDuration(double sec) {
    if (!std::isfinite(sec) || sec <= 0.0) {
        return {};
    }
    const qint64 total = static_cast<qint64>(std::llround(sec));
    const qint64 h = total / 3600;
    const qint64 m = (total % 3600) / 60;
    const qint64 s = total % 60;
    if (h > 0) {
        return QStringLiteral("%1:%2:%3")
            .arg(h, 2, 10, QLatin1Char('0'))
            .arg(m, 2, 10, QLatin1Char('0'))
            .arg(s, 2, 10, QLatin1Char('0'));
    }
    return QStringLiteral("%1:%2")
        .arg(m, 2, 10, QLatin1Char('0'))
        .arg(s, 2, 10, QLatin1Char('0'));
}

QString MediaInfo::formatTimecode(double sec) {
    if (!std::isfinite(sec) || sec < 0.0) {
        sec = 0.0;
    }
    const qint64 totalCentisec = static_cast<qint64>(std::llround(sec * 100.0));
    const qint64 cs = totalCentisec % 100;
    const qint64 totalSec = totalCentisec / 100;
    const qint64 s = totalSec % 60;
    const qint64 m = (totalSec / 60) % 60;
    const qint64 h = totalSec / 3600;
    return QStringLiteral("%1:%2:%3.%4")
        .arg(h, 2, 10, QLatin1Char('0'))
        .arg(m, 2, 10, QLatin1Char('0'))
        .arg(s, 2, 10, QLatin1Char('0'))
        .arg(cs, 2, 10, QLatin1Char('0'));
}

QString MediaInfo::summaryText() const {
    QStringList parts;
    parts << Formats::categoryNameTr(category);
    if (width > 0 && height > 0) {
        parts << QStringLiteral("%1x%2").arg(width).arg(height);
    }
    if (durationSec > 0.0) {
        const QString dur = formatDuration(durationSec);
        if (!dur.isEmpty()) {
            parts << dur;
        }
    }
    if (!vcodec.isEmpty()) {
        parts << vcodec.toUpper();
    } else if (!acodec.isEmpty()) {
        parts << acodec.toUpper();
    }
    if (audioStreams.size() > 1) {
        parts << QStringLiteral("%1 ses").arg(audioStreams.size());
    }
    parts << formatBytes(sizeBytes);
    return parts.join(QStringLiteral(" • "));
}

MediaInfo MediaProbe::probe(const QString& filePath) {
    MediaInfo info;
    QFileInfo fi(filePath);
    info.filePath = fi.absoluteFilePath();
    info.fileName = fi.fileName();
    info.sourceExt = Formats::normalizeExt(fi.suffix());
    info.category = Formats::detectCategory(info.sourceExt);
    info.sizeBytes = fi.exists() ? fi.size() : 0;

    if (!fi.exists() || !fi.isFile()) {
        return info;
    }

    if (info.category == MediaCategory::Image) {
        QImageReader reader(info.filePath);
        QSize sz = reader.size();
        if (sz.isValid() && !sz.isEmpty()) {
            if (reader.transformation() & QImageIOHandler::TransformationRotate90) {
                sz.transpose();
            }
            info.width = sz.width();
            info.height = sz.height();
            return info;
        }
    }

    // Fallback (e.g. HEIC/AVIF without Qt plugin) or Video/Audio: use ffprobe
    QProcess proc;
    const QStringList args = {
        QStringLiteral("-v"), QStringLiteral("quiet"),
        QStringLiteral("-print_format"), QStringLiteral("json"),
        QStringLiteral("-show_format"),
        QStringLiteral("-show_streams"),
        info.filePath
    };
    proc.start(QStringLiteral("ffprobe"), args);
    if (proc.waitForFinished(8000) && proc.exitStatus() == QProcess::NormalExit && proc.exitCode() == 0) {
        const QJsonDocument doc = QJsonDocument::fromJson(proc.readAllStandardOutput());
        if (doc.isObject()) {
            const QJsonObject root = doc.object();
            const QJsonObject fmt = root.value(QStringLiteral("format")).toObject();
            if (info.category != MediaCategory::Image) {
                info.durationSec = jsonToDouble(fmt.value(QStringLiteral("duration")));
            }

            double streamMaxDuration = 0.0;
            int audioOrderIndex = 0;

            const QJsonArray streams = root.value(QStringLiteral("streams")).toArray();
            for (const QJsonValue& val : streams) {
                const QJsonObject s = val.toObject();
                const QString type = s.value(QStringLiteral("codec_type")).toString();

                if (type == QLatin1String("video")) {
                    const bool isAttachedPic =
                        jsonToInt(s.value(QStringLiteral("disposition")).toObject().value(QStringLiteral("attached_pic"))) == 1;
                    if (isAttachedPic) {
                        continue;
                    }
                    if (info.vcodec.isEmpty()) {
                        info.vcodec = s.value(QStringLiteral("codec_name")).toString();
                        info.width = jsonToInt(s.value(QStringLiteral("width")));
                        info.height = jsonToInt(s.value(QStringLiteral("height")));

                        if (info.category != MediaCategory::Image) {
                            const double avgFps = parseFrameRate(s.value(QStringLiteral("avg_frame_rate")).toString());
                            const double rawFps = parseFrameRate(s.value(QStringLiteral("r_frame_rate")).toString());
                            info.fps = (avgFps > 0.0 && avgFps <= 1000.0) ? avgFps : rawFps;
                        }
                    }
                    if (info.category != MediaCategory::Image) {
                        double sd = jsonToDouble(s.value(QStringLiteral("duration")));
                        if (sd <= 0.0) {
                            sd = parseDurationTag(readTagCaseInsensitive(s.value(QStringLiteral("tags")).toObject(), QStringLiteral("DURATION")));
                        }
                        if (sd > streamMaxDuration) {
                            streamMaxDuration = sd;
                        }
                    }
                } else if (type == QLatin1String("audio")) {
                    AudioStreamInfo asi;
                    asi.streamIndex = audioOrderIndex++;
                    asi.codec = s.value(QStringLiteral("codec_name")).toString();
                    asi.sampleRate = jsonToInt(s.value(QStringLiteral("sample_rate")));
                    asi.channels = jsonToInt(s.value(QStringLiteral("channels")));

                    const QJsonObject tags = s.value(QStringLiteral("tags")).toObject();
                    asi.language = readTagCaseInsensitive(tags, QStringLiteral("language"));
                    asi.title = readTagCaseInsensitive(tags, QStringLiteral("title"));

                    info.audioStreams.append(asi);

                    if (info.acodec.isEmpty()) {
                        info.acodec = asi.codec;
                        info.sampleRate = asi.sampleRate;
                        info.channels = asi.channels;
                    }

                    if (info.category != MediaCategory::Image) {
                        double sd = jsonToDouble(s.value(QStringLiteral("duration")));
                        if (sd <= 0.0) {
                            sd = parseDurationTag(readTagCaseInsensitive(tags, QStringLiteral("DURATION")));
                        }
                        if (sd > streamMaxDuration) {
                            streamMaxDuration = sd;
                        }
                    }
                }
            }

            if (info.category != MediaCategory::Image && info.durationSec <= 0.0 && streamMaxDuration > 0.0) {
                info.durationSec = streamMaxDuration;
            }

            if (info.category == MediaCategory::Unknown) {
                if (!info.vcodec.isEmpty() && info.vcodec != QLatin1String("bin_data")) {
                    info.category = MediaCategory::Video;
                } else if (!info.acodec.isEmpty()) {
                    info.category = MediaCategory::Audio;
                }
            }
        }
    }

    return info;
}
