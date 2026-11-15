#pragma once

#include "projectmodel.h"

#include <QObject>
#include <QProcess>

struct ExportSettings {
    QString outputPath;
    QString formatExt = QStringLiteral("mp4");
    int width = 1920;
    int height = 1080;
    int fps = 30;
    int quality = 85;
    QString audioBitrate = QStringLiteral("192k");
    int sampleRate = 48000;
};

class TimelineExporter : public QObject {
    Q_OBJECT
public:
    explicit TimelineExporter(QObject* parent = nullptr);
    ~TimelineExporter() override;

    void startExport(const StudioProject& project, const ExportSettings& settings);
    void cancel();
    bool isRunning() const { return m_running; }

signals:
    void exportProgress(int percent, const QString& statusText);
    void exportFinished(bool success, const QString& outputPath, const QString& message);
    void ffmpegCommandLogged(const QString& cmdLine);

private slots:
    void onProcessReadyReadStdout();
    void onProcessFinished(int exitCode, QProcess::ExitStatus exitStatus);

private:
    QStringList buildExportArgs(const StudioProject& project, const ExportSettings& settings,
                                double totalDurationSec, QString& errorOut) const;

    QProcess* m_process = nullptr;
    QByteArray m_stdoutBuffer;
    QString m_outputPath;
    double m_totalDurationSec = 0.0;
    bool m_running = false;
    bool m_cancelled = false;
};
