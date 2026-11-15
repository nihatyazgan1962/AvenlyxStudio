#include "previewprovider.h"
#include "formats.h"

#include <QByteArray>
#include <QFileInfo>
#include <QProcess>
#include <QtEndian>
#include <QtGlobal>

#include <memory>
#include <utility>

namespace {
constexpr int kMaxConcurrentWaveforms = 2;
constexpr int kWaveformPeakCount = 240;
constexpr qsizetype kMaxWaveformPcmBytes = 128 * 1024 * 1024;
} // namespace

PreviewProvider::PreviewProvider(QObject* parent)
    : QObject(parent) {
}

PreviewProvider::~PreviewProvider() {
    if (m_frameProcess) {
        m_frameProcess->disconnect(this);
        if (m_frameProcess->state() != QProcess::NotRunning) {
            m_frameProcess->kill();
            m_frameProcess->waitForFinished(300);
        }
        m_frameProcess = nullptr;
    }

    for (QProcess* proc : std::as_const(m_activeWaveforms)) {
        if (proc) {
            proc->disconnect(this);
            if (proc->state() != QProcess::NotRunning) {
                proc->kill();
                proc->waitForFinished(300);
            }
        }
    }
    m_activeWaveforms.clear();
    m_waveformQueue.clear();
}

bool PreviewProvider::hasActiveFilters(const FilterAdjustments& filters) {
    const int rot = ((filters.rotateDegrees % 360) + 360) % 360;
    return qAbs(filters.brightness) > 1e-4
        || qAbs(filters.contrast - 1.0) > 1e-4
        || qAbs(filters.saturation - 1.0) > 1e-4
        || rot != 0
        || filters.flipH
        || filters.flipV
        || filters.grayscale
        || filters.sepia;
}

QStringList PreviewProvider::buildVideoFilterArgs(const FilterAdjustments& filters) {
    QStringList vf;

    const int rot = ((filters.rotateDegrees % 360) + 360) % 360;
    if (rot == 90) {
        vf << QStringLiteral("transpose=1");
    } else if (rot == 180) {
        vf << QStringLiteral("transpose=1") << QStringLiteral("transpose=1");
    } else if (rot == 270) {
        vf << QStringLiteral("transpose=2");
    }

    if (filters.flipH) {
        vf << QStringLiteral("hflip");
    }
    if (filters.flipV) {
        vf << QStringLiteral("vflip");
    }

    const double brightness = qBound(-1.0, filters.brightness, 1.0);
    const double contrast = qBound(0.2, filters.contrast, 2.0);
    const double saturation = qBound(0.0, filters.saturation, 3.0);

    if (qAbs(brightness) > 1e-4 || qAbs(contrast - 1.0) > 1e-4 || qAbs(saturation - 1.0) > 1e-4) {
        vf << QStringLiteral("eq=brightness=%1:contrast=%2:saturation=%3")
                  .arg(QString::number(brightness, 'f', 3),
                       QString::number(contrast, 'f', 3),
                       QString::number(saturation, 'f', 3));
    }

    if (filters.grayscale) {
        vf << QStringLiteral("hue=s=0");
    }
    if (filters.sepia) {
        vf << QStringLiteral("colorchannelmixer=.393:.769:.189:0:.349:.686:.168:0:.272:.534:.131");
    }

    return vf;
}

QString PreviewProvider::formatCommandLine(const QStringList& args) {
    QStringList quoted;
    quoted.reserve(args.size() + 1);
    quoted << QStringLiteral("ffmpeg");
    for (const QString& arg : args) {
        if (arg.isEmpty() || arg.contains(QLatin1Char(' ')) || arg.contains(QLatin1Char(';'))
            || arg.contains(QLatin1Char('"')) || arg.contains(QLatin1Char('\''))) {
            QString escaped = arg;
            escaped.replace(QLatin1Char('"'), QStringLiteral("\\\""));
            quoted << (QLatin1Char('"') + escaped + QLatin1Char('"'));
        } else {
            quoted << arg;
        }
    }
    return quoted.join(QLatin1Char(' '));
}

QString PreviewProvider::normalizeVideoCodec(const QString& codec) {
    const QString trimmed = codec.trimmed();
    if (trimmed == QLatin1String("flv1")) {
        return QStringLiteral("flv");
    }
    return trimmed;
}

void PreviewProvider::requestFrame(const QString& filePath, double timestampSec, const FilterAdjustments& filters) {
    const QFileInfo fi(filePath);
    if (filePath.isEmpty() || !fi.exists() || !fi.isFile()) {
        return;
    }

    // Cancel any in-flight frame capture so only the latest scrub/play request runs.
    if (m_frameProcess) {
        QProcess* oldProc = m_frameProcess;
        m_frameProcess = nullptr;
        oldProc->disconnect(this);
        if (oldProc->state() != QProcess::NotRunning) {
            connect(oldProc, &QProcess::finished, oldProc, &QObject::deleteLater);
            oldProc->kill();
        } else {
            oldProc->deleteLater();
        }
    }

    ++m_frameRequestId;

    const QString ext = Formats::normalizeExt(fi.suffix());
    const MediaCategory cat = Formats::detectCategory(ext);
    const bool isStillImage = (cat == MediaCategory::Image && ext != QLatin1String("gif"));
    const double clampedTime = qMax(0.0, timestampSec);

    startFrameCapture(filePath, clampedTime, filters, !isStillImage && clampedTime > 0.0, clampedTime, false);
}

void PreviewProvider::startFrameCapture(const QString& filePath, double timestampSec,
                                        const FilterAdjustments& filters, bool allowSeek,
                                        double seekSec, bool isRetry) {
    const quint64 reqId = m_frameRequestId;
    const QFileInfo fi(filePath);
    const QString ext = Formats::normalizeExt(fi.suffix());

    QStringList args;
    args << QStringLiteral("-v") << QStringLiteral("error");
    if (allowSeek && seekSec > 0.0) {
        args << QStringLiteral("-ss") << QString::number(seekSec, 'f', 3);
    }
    args << QStringLiteral("-i") << filePath
         << QStringLiteral("-frames:v") << QStringLiteral("1");

    QStringList vf = buildVideoFilterArgs(filters);
    vf << QStringLiteral("scale='min(1280,iw)':-1:flags=bilinear");
    args << QStringLiteral("-vf") << vf.join(QLatin1Char(','));

    args << QStringLiteral("-f") << QStringLiteral("image2pipe")
         << QStringLiteral("-vcodec") << QStringLiteral("png")
         << QStringLiteral("-");

    auto* proc = new QProcess(this);
    m_frameProcess = proc;

    auto buffer = std::make_shared<QByteArray>();
    connect(proc, &QProcess::readyReadStandardOutput, this, [proc, buffer]() {
        buffer->append(proc->readAllStandardOutput());
    });

    connect(proc, &QProcess::finished, this,
            [this, proc, reqId, filePath, timestampSec, filters, allowSeek, seekSec, isRetry, ext, buffer]
            (int exitCode, QProcess::ExitStatus exitStatus) {
        if (m_frameProcess == proc) {
            m_frameProcess = nullptr;
        }
        buffer->append(proc->readAllStandardOutput());
        proc->deleteLater();

        if (reqId != m_frameRequestId) {
            return;
        }

        if (exitStatus == QProcess::NormalExit && exitCode == 0 && !buffer->isEmpty()) {
            QImage img;
            if (img.loadFromData(*buffer, "PNG") && !img.isNull()) {
                emit frameReady(filePath, timestampSec, img);
                return;
            }
        }

        // Retry once when seeking past end-of-stream or into single-frame GIF.
        if (allowSeek && !isRetry) {
            if (ext == QLatin1String("gif")) {
                startFrameCapture(filePath, timestampSec, filters, false, 0.0, true);
                return;
            }
            if (seekSec > 0.05) {
                startFrameCapture(filePath, timestampSec, filters, true, qMax(0.0, seekSec - 0.25), true);
                return;
            }
        }

        emit frameReady(filePath, timestampSec, QImage());
    });

    connect(proc, &QProcess::errorOccurred, this,
            [this, proc, reqId, filePath, timestampSec](QProcess::ProcessError error) {
        if (error == QProcess::FailedToStart) {
            if (m_frameProcess == proc) {
                m_frameProcess = nullptr;
            }
            proc->deleteLater();
            if (reqId == m_frameRequestId) {
                emit frameReady(filePath, timestampSec, QImage());
            }
        }
    });

    proc->start(QStringLiteral("ffmpeg"), args);
}

void PreviewProvider::requestWaveform(int clipId, const QString& filePath) {
    const QFileInfo fi(filePath);
    if (clipId <= 0 || filePath.isEmpty() || !fi.exists() || !fi.isFile()) {
        return;
    }

    // Remove any queued duplicate job for the same clipId.
    for (qsizetype i = m_waveformQueue.size() - 1; i >= 0; --i) {
        if (m_waveformQueue[i].clipId == clipId) {
            m_waveformQueue.removeAt(i);
        }
    }

    // Cancel any currently running extraction for the same clipId.
    if (QProcess* existing = m_activeWaveforms.take(clipId)) {
        existing->disconnect(this);
        if (existing->state() != QProcess::NotRunning) {
            connect(existing, &QProcess::finished, existing, &QObject::deleteLater);
            existing->kill();
        } else {
            existing->deleteLater();
        }
    }

    m_waveformQueue.append({clipId, filePath});
    pumpWaveformQueue();
}

void PreviewProvider::pumpWaveformQueue() {
    while (m_activeWaveforms.size() < kMaxConcurrentWaveforms && !m_waveformQueue.isEmpty()) {
        const WaveformJob job = m_waveformQueue.takeFirst();
        const int clipId = job.clipId;

        const QStringList args = {
            QStringLiteral("-v"), QStringLiteral("error"),
            QStringLiteral("-i"), job.filePath,
            QStringLiteral("-vn"),
            QStringLiteral("-ac"), QStringLiteral("1"),
            QStringLiteral("-ar"), QStringLiteral("8000"),
            QStringLiteral("-f"), QStringLiteral("s16le"),
            QStringLiteral("-")
        };

        auto* proc = new QProcess(this);
        m_activeWaveforms.insert(clipId, proc);

        auto pcmBuffer = std::make_shared<QByteArray>();
        connect(proc, &QProcess::readyReadStandardOutput, this, [proc, pcmBuffer]() {
            if (pcmBuffer->size() < kMaxWaveformPcmBytes) {
                pcmBuffer->append(proc->readAllStandardOutput());
            } else {
                proc->readAllStandardOutput();
            }
        });

        connect(proc, &QProcess::finished, this,
                [this, proc, clipId, pcmBuffer](int exitCode, QProcess::ExitStatus exitStatus) {
            if (m_activeWaveforms.value(clipId) == proc) {
                m_activeWaveforms.remove(clipId);
            }
            if (pcmBuffer->size() < kMaxWaveformPcmBytes) {
                pcmBuffer->append(proc->readAllStandardOutput());
            }
            proc->deleteLater();

            if (exitStatus == QProcess::NormalExit && exitCode == 0 && pcmBuffer->size() >= 2) {
                emit waveformReady(clipId, computeNormalizedPeaks(*pcmBuffer, kWaveformPeakCount));
            } else {
                emit waveformReady(clipId, {});
            }

            pumpWaveformQueue();
        });

        connect(proc, &QProcess::errorOccurred, this, [this, proc, clipId](QProcess::ProcessError error) {
            if (error == QProcess::FailedToStart) {
                if (m_activeWaveforms.value(clipId) == proc) {
                    m_activeWaveforms.remove(clipId);
                }
                proc->deleteLater();
                emit waveformReady(clipId, {});
                pumpWaveformQueue();
            }
        });

        proc->start(QStringLiteral("ffmpeg"), args);
    }
}

QVector<float> PreviewProvider::computeNormalizedPeaks(const QByteArray& pcmBytes, int peakCount) {
    QVector<float> peaks(peakCount, 0.0f);
    const qsizetype totalSamples = pcmBytes.size() / 2;
    if (totalSamples <= 0 || peakCount <= 0) {
        return peaks;
    }

    const uchar* raw = reinterpret_cast<const uchar*>(pcmBytes.constData());
    for (int i = 0; i < peakCount; ++i) {
        const qsizetype startSample = (static_cast<qsizetype>(i) * totalSamples) / peakCount;
        qsizetype endSample = (static_cast<qsizetype>(i + 1) * totalSamples) / peakCount;
        if (endSample <= startSample) {
            endSample = qMin(totalSamples, startSample + 1);
        }

        int maxAbs = 0;
        for (qsizetype s = startSample; s < endSample; ++s) {
            const qint16 sample = qFromLittleEndian<qint16>(raw + (s * 2));
            const int absVal = (sample == -32768) ? 32768 : qAbs(static_cast<int>(sample));
            if (absVal > maxAbs) {
                maxAbs = absVal;
            }
        }
        peaks[i] = qBound(0.0f, static_cast<float>(maxAbs) / 32768.0f, 1.0f);
    }

    return peaks;
}
