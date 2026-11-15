#pragma once

#include "formats.h"
#include "mediaprobe.h"
#include "projectmodel.h"

#include <QImage>
#include <QList>
#include <QObject>
#include <QProcess>

struct ConversionOptions {
    int quality = 85;
    int width = 0;
    int height = 0;
    int fps = 0;
    QString videoCodec;
    QString audioBitrate = QStringLiteral("192k");
    int sampleRate = 0;
    int audioChannels = 0;
    int selectedAudioStream = -1;
    bool noAudio = false;
    bool stripMetadata = false;
    double trimStartSec = 0.0;
    double trimEndSec = 0.0;
    double volume = 1.0;
    double fadeInSec = 0.0;
    double fadeOutSec = 0.0;
    FilterAdjustments filters;
};

struct ConversionTask {
    int row = -1;
    MediaInfo media;
    QString targetExt;
    QString outputPath;
    ConversionOptions options;
};

class ConverterWorker : public QObject {
    Q_OBJECT
public:
    explicit ConverterWorker(QObject* parent = nullptr);
    ~ConverterWorker() override;

    void startQueue(const QList<ConversionTask>& tasks);
    void cancel();
    bool isRunning() const { return m_running; }

signals:
    void taskStarted(int row);
    void taskProgress(int row, int percent);
    void taskFinished(int row, bool success, const QString& message, qint64 outputSizeBytes);
    void allFinished(int successCount, int failCount);
    void ffmpegCommandLogged(const QString& cmdLine);

private slots:
    void onProcessReadyReadStdout();
    void onProcessFinished(int exitCode, QProcess::ExitStatus exitStatus);

private:
    void processNext();
    QImage loadImageForQt(const ConversionTask& task, QString& errorOut);
    bool convertImageToPdf(const ConversionTask& task, QString& errorOut);
    bool convertImageViaQt(const ConversionTask& task, QString& errorOut);
    QStringList buildFfmpegArgs(const ConversionTask& task) const;
    static double expectedOutputDurationSec(const ConversionTask& task);
    static QStringList buildAudioFilterArgs(const ConversionTask& task, double expectedDurationSec);

    QList<ConversionTask> m_queue;
    int m_currentIndex = -1;
    bool m_running = false;
    bool m_cancelled = false;
    int m_successCount = 0;
    int m_failCount = 0;

    QProcess* m_process = nullptr;
    QByteArray m_stdoutBuffer;
};
