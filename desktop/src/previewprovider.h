#pragma once

#include "projectmodel.h"

#include <QHash>
#include <QImage>
#include <QList>
#include <QObject>
#include <QStringList>
#include <QVector>

class QProcess;

class PreviewProvider : public QObject {
    Q_OBJECT
public:
    explicit PreviewProvider(QObject* parent = nullptr);
    ~PreviewProvider() override;

    void requestFrame(const QString& filePath, double timestampSec, const FilterAdjustments& filters = {});
    void requestWaveform(int clipId, const QString& filePath);

    static bool hasActiveFilters(const FilterAdjustments& filters);
    static QStringList buildVideoFilterArgs(const FilterAdjustments& filters);
    static QString formatCommandLine(const QStringList& args);
    static QString normalizeVideoCodec(const QString& codec);

signals:
    void frameReady(const QString& filePath, double timestampSec, const QImage& frame);
    void waveformReady(int clipId, const QVector<float>& peaks);

private:
    struct WaveformJob {
        int clipId = 0;
        QString filePath;
    };

    void startFrameCapture(const QString& filePath, double timestampSec,
                           const FilterAdjustments& filters, bool allowSeek,
                           double seekSec, bool isRetry);
    void pumpWaveformQueue();
    static QVector<float> computeNormalizedPeaks(const QByteArray& pcmBytes, int peakCount = 240);

    QProcess* m_frameProcess = nullptr;
    quint64 m_frameRequestId = 0;

    QList<WaveformJob> m_waveformQueue;
    QHash<int, QProcess*> m_activeWaveforms;
};
