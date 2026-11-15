#include "converterworker.h"
#include "previewprovider.h"

#include <QDir>
#include <QFileInfo>
#include <QImage>
#include <QImageReader>
#include <QImageWriter>
#include <QMarginsF>
#include <QMetaObject>
#include <QPageSize>
#include <QPainter>
#include <QPdfWriter>
#include <QRect>
#include <QSizeF>
#include <QTemporaryFile>
#include <QtGlobal>

namespace {

QImage scaleQtImage(const QImage& source, int targetWidth, int targetHeight, bool keepAspectIfBoth) {
    if (targetWidth <= 0 && targetHeight <= 0) {
        return source;
    }
    if (targetWidth > 0 && targetHeight <= 0) {
        return source.scaledToWidth(targetWidth, Qt::SmoothTransformation);
    }
    if (targetHeight > 0 && targetWidth <= 0) {
        return source.scaledToHeight(targetHeight, Qt::SmoothTransformation);
    }
    const Qt::AspectRatioMode mode = keepAspectIfBoth ? Qt::KeepAspectRatio : Qt::IgnoreAspectRatio;
    return source.scaled(targetWidth, targetHeight, mode, Qt::SmoothTransformation);
}

QString evenDimensionString(int value) {
    if (value <= 0) {
        return QStringLiteral("-2");
    }
    const int even = qMax(2, value - (value % 2));
    return QString::number(even);
}

} // namespace

ConverterWorker::ConverterWorker(QObject* parent)
    : QObject(parent)
    , m_process(new QProcess(this)) {
    connect(m_process, &QProcess::readyReadStandardOutput,
            this, &ConverterWorker::onProcessReadyReadStdout);
    connect(m_process, &QProcess::finished,
            this, &ConverterWorker::onProcessFinished);
}

ConverterWorker::~ConverterWorker() {
    if (m_process && m_process->state() != QProcess::NotRunning) {
        m_process->disconnect(this);
        m_process->kill();
        m_process->waitForFinished(500);
    }
}

void ConverterWorker::startQueue(const QList<ConversionTask>& tasks) {
    if (m_running || tasks.isEmpty()) {
        return;
    }
    m_queue = tasks;
    m_currentIndex = -1;
    m_running = true;
    m_cancelled = false;
    m_successCount = 0;
    m_failCount = 0;
    processNext();
}

void ConverterWorker::cancel() {
    if (!m_running) {
        return;
    }
    m_cancelled = true;
    if (m_process->state() != QProcess::NotRunning) {
        m_process->kill();
    }
}

double ConverterWorker::expectedOutputDurationSec(const ConversionTask& task) {
    const double start = qMax(0.0, task.options.trimStartSec);
    double end = task.options.trimEndSec;
    if (end > start) {
        if (task.media.durationSec > 0.0) {
            end = qMin(end, task.media.durationSec);
        }
        return qMax(0.0, end - start);
    }
    if (task.media.durationSec > 0.0) {
        return qMax(0.0, task.media.durationSec - start);
    }
    return 0.0;
}

QStringList ConverterWorker::buildAudioFilterArgs(const ConversionTask& task, double expectedDurationSec) {
    QStringList af;
    if (qAbs(task.options.volume - 1.0) > 1e-4) {
        af << QStringLiteral("volume=%1").arg(QString::number(qMax(0.0, task.options.volume), 'f', 3));
    }
    if (task.options.fadeInSec > 0.0) {
        const double fadeIn = (expectedDurationSec > 0.0)
            ? qMin(task.options.fadeInSec, expectedDurationSec)
            : task.options.fadeInSec;
        if (fadeIn > 0.0) {
            af << QStringLiteral("afade=t=in:st=0:d=%1").arg(QString::number(fadeIn, 'f', 3));
        }
    }
    if (task.options.fadeOutSec > 0.0 && expectedDurationSec > 0.0) {
        const double fadeOut = qMin(task.options.fadeOutSec, expectedDurationSec);
        const double fadeStart = qMax(0.0, expectedDurationSec - fadeOut);
        if (fadeOut > 0.0) {
            af << QStringLiteral("afade=t=out:st=%1:d=%2")
                      .arg(QString::number(fadeStart, 'f', 3),
                           QString::number(fadeOut, 'f', 3));
        }
    }
    return af;
}

void ConverterWorker::processNext() {
    if (m_cancelled) {
        m_running = false;
        emit allFinished(m_successCount, m_failCount);
        return;
    }

    ++m_currentIndex;
    if (m_currentIndex >= m_queue.size()) {
        m_running = false;
        emit allFinished(m_successCount, m_failCount);
        return;
    }

    const ConversionTask& task = m_queue[m_currentIndex];
    emit taskStarted(task.row);
    emit taskProgress(task.row, 5);

    const QFileInfo outInfo(task.outputPath);
    QDir().mkpath(outInfo.absolutePath());

    const QString target = Formats::normalizeExt(task.targetExt);
    if (task.media.category == MediaCategory::Image) {
        if (target == QLatin1String("pdf")) {
            QString err;
            const bool ok = convertImageToPdf(task, err);
            if (ok) {
                ++m_successCount;
                emit taskProgress(task.row, 100);
                emit taskFinished(task.row, true, QStringLiteral("Tamamlandı"), QFileInfo(task.outputPath).size());
            } else {
                ++m_failCount;
                emit taskFinished(task.row, false, err, 0);
            }
            QMetaObject::invokeMethod(this, &ConverterWorker::processNext, Qt::QueuedConnection);
            return;
        }
        if (target == QLatin1String("ico")) {
            QString err;
            const bool ok = convertImageViaQt(task, err);
            if (ok) {
                ++m_successCount;
                emit taskProgress(task.row, 100);
                emit taskFinished(task.row, true, QStringLiteral("Tamamlandı"), QFileInfo(task.outputPath).size());
                QMetaObject::invokeMethod(this, &ConverterWorker::processNext, Qt::QueuedConnection);
                return;
            }
            // Fall through to ffmpeg if Qt ICO plugin is unavailable or failed.
        }
    }

    m_stdoutBuffer.clear();
    const QStringList args = buildFfmpegArgs(task);
    emit ffmpegCommandLogged(PreviewProvider::formatCommandLine(args));

    m_process->start(QStringLiteral("ffmpeg"), args);
    if (!m_process->waitForStarted(3000)) {
        ++m_failCount;
        emit taskFinished(task.row, false, QStringLiteral("ffmpeg başlatılamadı"), 0);
        QMetaObject::invokeMethod(this, &ConverterWorker::processNext, Qt::QueuedConnection);
    }
}

QImage ConverterWorker::loadImageForQt(const ConversionTask& task, QString& errorOut) {
    const bool filtersActive = PreviewProvider::hasActiveFilters(task.options.filters);

    QImage img;
    QString readerErr;
    if (!filtersActive) {
        QImageReader reader(task.media.filePath);
        reader.setAutoTransform(true);
        img = reader.read();
        readerErr = reader.errorString();
    }

    // Use ffmpeg when filters are active or when Qt lacks a decoder plugin (AVIF/HEIC/WebP).
    if (img.isNull()) {
        QTemporaryFile tmp(QDir::tempPath() + QStringLiteral("/formatci_XXXXXX.png"));
        tmp.setAutoRemove(true);
        if (tmp.open()) {
            const QString tmpPath = tmp.fileName();
            tmp.close();

            QStringList args = {
                QStringLiteral("-y"),
                QStringLiteral("-hide_banner"),
                QStringLiteral("-i"),
                task.media.filePath
            };
            const QStringList vf = PreviewProvider::buildVideoFilterArgs(task.options.filters);
            if (!vf.isEmpty()) {
                args << QStringLiteral("-vf") << vf.join(QLatin1Char(','));
            }
            args << QStringLiteral("-frames:v") << QStringLiteral("1") << tmpPath;

            emit ffmpegCommandLogged(PreviewProvider::formatCommandLine(args));

            QProcess dec;
            dec.start(QStringLiteral("ffmpeg"), args);
            if (dec.waitForFinished(15000) && dec.exitStatus() == QProcess::NormalExit && dec.exitCode() == 0) {
                img.load(tmpPath);
            }
        }
    }

    if (img.isNull()) {
        errorOut = readerErr.isEmpty()
            ? QStringLiteral("Resim okunamadı")
            : QStringLiteral("Resim okunamadı: %1").arg(readerErr);
    }
    return img;
}

bool ConverterWorker::convertImageToPdf(const ConversionTask& task, QString& errorOut) {
    QImage img = loadImageForQt(task, errorOut);
    if (img.isNull()) {
        return false;
    }

    img = scaleQtImage(img, task.options.width, task.options.height, false);

    QPdfWriter writer(task.outputPath);
    writer.setPageSize(QPageSize(QSizeF(img.width(), img.height()), QPageSize::Point));
    writer.setPageMargins(QMarginsF(0, 0, 0, 0));
    writer.setResolution(72);

    QPainter painter(&writer);
    if (!painter.isActive()) {
        errorOut = QStringLiteral("PDF dosyası yazılamadı");
        return false;
    }
    painter.drawImage(QRect(0, 0, writer.width(), writer.height()), img);
    painter.end();
    return true;
}

bool ConverterWorker::convertImageViaQt(const ConversionTask& task, QString& errorOut) {
    QImage img = loadImageForQt(task, errorOut);
    if (img.isNull()) {
        return false;
    }

    const QString target = Formats::normalizeExt(task.targetExt);
    if (target == QLatin1String("ico")
        && (task.options.width > 0 || task.options.height > 0 || img.width() > 256 || img.height() > 256)) {
        const int targetW = task.options.width > 0 ? qMin(task.options.width, 256) : 256;
        const int targetH = task.options.height > 0 ? qMin(task.options.height, 256) : 256;
        img = scaleQtImage(img, targetW, targetH, true);
    } else {
        img = scaleQtImage(img, task.options.width, task.options.height, false);
    }

    QImageWriter writer(task.outputPath, target.toUtf8());
    writer.setQuality(task.options.quality);
    if (!writer.write(img)) {
        errorOut = writer.errorString();
        return false;
    }
    return true;
}

QStringList ConverterWorker::buildFfmpegArgs(const ConversionTask& task) const {
    const QString target = Formats::normalizeExt(task.targetExt);
    const FormatInfo fmt = Formats::findFormat(target);

    const double trimStart = qMax(0.0, task.options.trimStartSec);
    const bool hasTrimEnd = (task.options.trimEndSec > trimStart) && (task.options.trimEndSec > 0.0);
    const double expectedDur = expectedOutputDurationSec(task);

    QStringList args;
    args << QStringLiteral("-y") << QStringLiteral("-hide_banner");

    if (task.media.category != MediaCategory::Image && trimStart > 0.0) {
        args << QStringLiteral("-ss") << QString::number(trimStart, 'f', 3);
    }

    args << QStringLiteral("-i") << task.media.filePath;

    if (task.media.category != MediaCategory::Image && hasTrimEnd) {
        const double clipLen = task.options.trimEndSec - trimStart;
        args << QStringLiteral("-t") << QString::number(clipLen, 'f', 3);
    }

    args << QStringLiteral("-progress") << QStringLiteral("pipe:1") << QStringLiteral("-nostats");

    if (task.options.stripMetadata) {
        args << QStringLiteral("-map_metadata") << QStringLiteral("-1");
    }

    // 1) Image target
    if (task.media.category == MediaCategory::Image) {
        QStringList vf = PreviewProvider::buildVideoFilterArgs(task.options.filters);
        if (task.options.width > 0 || task.options.height > 0) {
            const QString w = task.options.width > 0 ? QString::number(task.options.width) : QStringLiteral("-1");
            const QString h = task.options.height > 0 ? QString::number(task.options.height) : QStringLiteral("-1");
            vf << QStringLiteral("scale=%1:%2:flags=lanczos").arg(w, h);
        } else if (target == QLatin1String("ico")) {
            vf << QStringLiteral("scale='min(256,iw)':'min(256,ih)':force_original_aspect_ratio=decrease");
        }
        if (!vf.isEmpty()) {
            args << QStringLiteral("-vf") << vf.join(QLatin1Char(','));
        }
        if (target != QLatin1String("gif") && target != QLatin1String("webp")) {
            args << QStringLiteral("-frames:v") << QStringLiteral("1");
        }

        if (target == QLatin1String("jpg")) {
            const int qv = qBound(2, 31 - qRound((task.options.quality / 100.0) * 29.0), 31);
            args << QStringLiteral("-q:v") << QString::number(qv);
        } else if (target == QLatin1String("webp")) {
            args << QStringLiteral("-quality") << QString::number(qBound(1, task.options.quality, 100));
        } else if (target == QLatin1String("avif")) {
            const int crf = qBound(0, qRound(63.0 - (task.options.quality / 100.0) * 53.0), 63);
            args << QStringLiteral("-c:v") << QStringLiteral("libaom-av1")
                 << QStringLiteral("-still-picture") << QStringLiteral("1")
                 << QStringLiteral("-crf") << QString::number(crf);
        }

        args << task.outputPath;
        return args;
    }

    // 2) Audio target (Audio -> Audio OR Video -> Audio extraction)
    if (fmt.category == MediaCategory::Audio) {
        args << QStringLiteral("-vn");
        if (task.options.selectedAudioStream >= 0) {
            args << QStringLiteral("-map")
                 << QStringLiteral("0:a:%1?").arg(task.options.selectedAudioStream);
        }

        const QStringList af = buildAudioFilterArgs(task, expectedDur);
        if (!af.isEmpty()) {
            args << QStringLiteral("-af") << af.join(QLatin1Char(','));
        }

        const QString acodec = fmt.acodec.isEmpty() ? QStringLiteral("aac") : fmt.acodec;
        args << QStringLiteral("-c:a") << acodec;
        if (target != QLatin1String("wav") && target != QLatin1String("flac")
            && target != QLatin1String("aiff") && !task.options.audioBitrate.isEmpty()) {
            args << QStringLiteral("-b:a") << task.options.audioBitrate;
        }
        if (task.options.sampleRate > 0) {
            args << QStringLiteral("-ar") << QString::number(task.options.sampleRate);
        }
        if (task.options.audioChannels > 0) {
            args << QStringLiteral("-ac") << QString::number(task.options.audioChannels);
        }

        args << task.outputPath;
        return args;
    }

    // 3) Animated GIF target from Video
    if (target == QLatin1String("gif")) {
        const int fps = task.options.fps > 0 ? task.options.fps : 15;
        QStringList vf = PreviewProvider::buildVideoFilterArgs(task.options.filters);
        vf << QStringLiteral("fps=%1").arg(fps);

        if (task.options.width > 0 || task.options.height > 0) {
            const QString w = task.options.width > 0 ? QString::number(task.options.width) : QStringLiteral("-1");
            const QString h = task.options.height > 0 ? QString::number(task.options.height) : QStringLiteral("-1");
            vf << QStringLiteral("scale=%1:%2:flags=lanczos").arg(w, h);
        } else {
            vf << QStringLiteral("scale=480:-1:flags=lanczos");
        }

        const QString filterChain = vf.join(QLatin1Char(','))
            + QStringLiteral(",split[s0][s1];[s0]palettegen[p];[s1][p]paletteuse");
        args << QStringLiteral("-an")
             << QStringLiteral("-vf") << filterChain
             << QStringLiteral("-loop") << QStringLiteral("0")
             << task.outputPath;
        return args;
    }

    // 4) Video target
    QString rawCodec = task.options.videoCodec.trimmed();
    if (rawCodec.isEmpty()
        || rawCodec.compare(QStringLiteral("auto"), Qt::CaseInsensitive) == 0
        || rawCodec.compare(QStringLiteral("Otomatik"), Qt::CaseInsensitive) == 0) {
        rawCodec = fmt.vcodec.isEmpty() ? QStringLiteral("libx264") : fmt.vcodec;
    }
    const QString vcodec = PreviewProvider::normalizeVideoCodec(rawCodec);

    if (task.options.noAudio) {
        args << QStringLiteral("-map") << QStringLiteral("0:v:0?");
    } else if (task.options.selectedAudioStream >= 0) {
        args << QStringLiteral("-map") << QStringLiteral("0:v:0")
             << QStringLiteral("-map") << QStringLiteral("0:a:%1?").arg(task.options.selectedAudioStream);
    }

    args << QStringLiteral("-c:v") << vcodec;
    if (vcodec == QLatin1String("libx264") || vcodec == QLatin1String("libx265")) {
        const int crf = qBound(14, qRound(36.0 - (task.options.quality / 100.0) * 22.0), 36);
        args << QStringLiteral("-crf") << QString::number(crf)
             << QStringLiteral("-preset") << QStringLiteral("medium")
             << QStringLiteral("-pix_fmt") << QStringLiteral("yuv420p");
        if (vcodec == QLatin1String("libx265")
            && (target == QLatin1String("mp4") || target == QLatin1String("mov") || target == QLatin1String("m4v"))) {
            args << QStringLiteral("-tag:v") << QStringLiteral("hvc1");
        }
    } else if (vcodec == QLatin1String("libvpx-vp9")) {
        const int crf = qBound(18, qRound(42.0 - (task.options.quality / 100.0) * 24.0), 42);
        args << QStringLiteral("-crf") << QString::number(crf)
             << QStringLiteral("-b:v") << QStringLiteral("0")
             << QStringLiteral("-pix_fmt") << QStringLiteral("yuv420p");
    } else {
        const int qscale = qBound(2, qRound(15.0 - (task.options.quality / 100.0) * 13.0), 15);
        args << QStringLiteral("-q:v") << QString::number(qscale);
    }

    QStringList vf = PreviewProvider::buildVideoFilterArgs(task.options.filters);
    if (task.options.width > 0 || task.options.height > 0) {
        const QString w = evenDimensionString(task.options.width);
        const QString h = evenDimensionString(task.options.height);
        vf << QStringLiteral("scale=%1:%2:flags=lanczos").arg(w, h);
    } else if (vcodec == QLatin1String("libx264") || vcodec == QLatin1String("libx265")
               || vcodec == QLatin1String("libvpx-vp9")) {
        vf << QStringLiteral("pad=ceil(iw/2)*2:ceil(ih/2)*2");
    }
    if (!vf.isEmpty()) {
        args << QStringLiteral("-vf") << vf.join(QLatin1Char(','));
    }

    if (task.options.fps > 0) {
        args << QStringLiteral("-r") << QString::number(task.options.fps);
    }

    if (task.options.noAudio) {
        args << QStringLiteral("-an");
    } else {
        const QStringList af = buildAudioFilterArgs(task, expectedDur);
        if (!af.isEmpty()) {
            args << QStringLiteral("-af") << af.join(QLatin1Char(','));
        }

        const QString acodec = fmt.acodec.isEmpty() ? QStringLiteral("aac") : fmt.acodec;
        args << QStringLiteral("-c:a") << acodec;
        if (!acodec.startsWith(QLatin1String("pcm_")) && acodec != QLatin1String("flac")
            && !task.options.audioBitrate.isEmpty()) {
            args << QStringLiteral("-b:a") << task.options.audioBitrate;
        }
        if (task.options.sampleRate > 0) {
            args << QStringLiteral("-ar") << QString::number(task.options.sampleRate);
        }
        if (task.options.audioChannels > 0) {
            args << QStringLiteral("-ac") << QString::number(task.options.audioChannels);
        }
    }

    if (target == QLatin1String("mp4") || target == QLatin1String("m4v") || target == QLatin1String("mov")) {
        args << QStringLiteral("-movflags") << QStringLiteral("+faststart");
    }

    args << task.outputPath;
    return args;
}

void ConverterWorker::onProcessReadyReadStdout() {
    m_stdoutBuffer.append(m_process->readAllStandardOutput());
    qsizetype idx = -1;
    while ((idx = m_stdoutBuffer.indexOf('\n')) != -1) {
        const QString line = QString::fromUtf8(m_stdoutBuffer.left(idx)).trimmed();
        m_stdoutBuffer.remove(0, idx + 1);

        if (m_currentIndex < 0 || m_currentIndex >= m_queue.size()) {
            continue;
        }
        const ConversionTask& task = m_queue[m_currentIndex];
        const double expectedDur = expectedOutputDurationSec(task);

        if (line.startsWith(QLatin1String("out_time_us="))) {
            bool ok = false;
            const qint64 us = line.mid(12).toLongLong(&ok);
            if (ok && expectedDur > 0.0) {
                const int pct = qBound(5, static_cast<int>((us / (expectedDur * 1000000.0)) * 100.0), 99);
                emit taskProgress(task.row, pct);
            }
        } else if (line == QLatin1String("progress=end")) {
            emit taskProgress(task.row, 99);
        }
    }
}

void ConverterWorker::onProcessFinished(int exitCode, QProcess::ExitStatus exitStatus) {
    if (m_currentIndex < 0 || m_currentIndex >= m_queue.size()) {
        return;
    }
    const ConversionTask& task = m_queue[m_currentIndex];

    if (m_cancelled) {
        ++m_failCount;
        emit taskFinished(task.row, false, QStringLiteral("İptal edildi"), 0);
        processNext();
        return;
    }

    if (exitStatus == QProcess::NormalExit && exitCode == 0) {
        ++m_successCount;
        emit taskProgress(task.row, 100);
        const qint64 outSize = QFileInfo(task.outputPath).size();
        emit taskFinished(task.row, true, QStringLiteral("Tamamlandı"), outSize);
    } else {
        ++m_failCount;
        const QString err = QString::fromUtf8(m_process->readAllStandardError()).trimmed();
        const QStringList lines = err.split(QLatin1Char('\n'), Qt::SkipEmptyParts);
        const QString shortErr = lines.isEmpty()
            ? QStringLiteral("Çıkış kodu: %1").arg(exitCode)
            : lines.last();
        emit taskFinished(task.row, false, shortErr, 0);
    }

    processNext();
}
