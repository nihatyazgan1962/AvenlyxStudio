#include "timelineexporter.h"
#include "formats.h"
#include "previewprovider.h"

#include <QDir>
#include <QFileInfo>
#include <QtGlobal>

#include <algorithm>

namespace {

const TimelineTrack* findTrackForIndex(const QList<TimelineTrack>& tracks, int trackIndex) {
    if (trackIndex >= 0 && trackIndex < tracks.size() && tracks[trackIndex].index == trackIndex) {
        return &tracks[trackIndex];
    }
    for (const auto& track : tracks) {
        if (track.index == trackIndex) {
            return &track;
        }
    }
    return nullptr;
}

int evenDim(int val, int fallback) {
    const int base = (val > 0) ? val : fallback;
    return qMax(2, base - (base % 2));
}

struct ActiveClipPlan {
    TimelineClip clip;
    bool includeVideo = false;
    bool includeAudio = false;
    double effectiveVolume = 1.0;
    int inputIndex = -1;
};

} // namespace

TimelineExporter::TimelineExporter(QObject* parent)
    : QObject(parent)
    , m_process(new QProcess(this)) {
    connect(m_process, &QProcess::readyReadStandardOutput,
            this, &TimelineExporter::onProcessReadyReadStdout);
    connect(m_process, &QProcess::finished,
            this, &TimelineExporter::onProcessFinished);
}

TimelineExporter::~TimelineExporter() {
    if (m_process && m_process->state() != QProcess::NotRunning) {
        m_process->disconnect(this);
        m_process->kill();
        m_process->waitForFinished(500);
    }
}

void TimelineExporter::startExport(const StudioProject& project, const ExportSettings& settings) {
    if (m_running) {
        return;
    }

    m_outputPath = settings.outputPath.trimmed();
    if (m_outputPath.isEmpty()) {
        emit exportFinished(false, m_outputPath, QStringLiteral("Çıktı dosya yolu belirtilmedi."));
        return;
    }

    m_totalDurationSec = project.totalDurationSec();
    if (project.clips.isEmpty() || m_totalDurationSec <= 0.0) {
        emit exportFinished(false, m_outputPath, QStringLiteral("Zaman çizelgesinde dışa aktarılacak klip yok."));
        return;
    }

    QString buildErr;
    const QStringList args = buildExportArgs(project, settings, m_totalDurationSec, buildErr);
    if (args.isEmpty()) {
        emit exportFinished(false, m_outputPath, buildErr);
        return;
    }

    QDir().mkpath(QFileInfo(m_outputPath).absolutePath());

    m_stdoutBuffer.clear();
    m_running = true;
    m_cancelled = false;

    emit exportProgress(1, QStringLiteral("Render başlatılıyor..."));
    emit ffmpegCommandLogged(PreviewProvider::formatCommandLine(args));

    m_process->start(QStringLiteral("ffmpeg"), args);
    if (!m_process->waitForStarted(3000)) {
        m_running = false;
        emit exportFinished(false, m_outputPath, QStringLiteral("ffmpeg başlatılamadı."));
    }
}

void TimelineExporter::cancel() {
    if (!m_running) {
        return;
    }
    m_cancelled = true;
    if (m_process->state() != QProcess::NotRunning) {
        m_process->kill();
    }
}

QStringList TimelineExporter::buildExportArgs(const StudioProject& project, const ExportSettings& settings,
                                              double totalDurationSec, QString& errorOut) const {
    const QString targetExt = Formats::normalizeExt(settings.formatExt.isEmpty() ? QStringLiteral("mp4") : settings.formatExt);
    const FormatInfo fmt = Formats::findFormat(targetExt);
    const bool isAudioTarget = (fmt.category == MediaCategory::Audio);
    const bool isGifTarget = (targetExt == QLatin1String("gif"));

    const int rawW = settings.width > 0 ? settings.width : 1920;
    const int rawH = settings.height > 0 ? settings.height : 1080;
    const int outW = (isGifTarget || isAudioTarget) ? rawW : evenDim(rawW, 1920);
    const int outH = (isGifTarget || isAudioTarget) ? rawH : evenDim(rawH, 1080);
    const int outFps = settings.fps > 0 ? settings.fps : (isGifTarget ? 15 : 30);
    const int sampleRate = settings.sampleRate > 0 ? settings.sampleRate : 48000;

    bool anyVideoSolo = false;
    bool anyAudioSolo = false;
    for (const auto& track : project.tracks) {
        if (!track.solo) {
            continue;
        }
        if (track.type == MediaCategory::Video) {
            anyVideoSolo = true;
            // Only solo audio when the soloed video track actually contains an audio-producing clip.
            for (const auto& clip : project.clips) {
                if (clip.trackIndex == track.index
                    && (clip.media.category == MediaCategory::Audio
                        || (clip.media.category == MediaCategory::Video
                            && (!clip.media.audioStreams.isEmpty() || !clip.media.acodec.isEmpty())))) {
                    anyAudioSolo = true;
                    break;
                }
            }
        } else if (track.type == MediaCategory::Audio) {
            anyAudioSolo = true;
        }
    }

    QList<ActiveClipPlan> plans;
    plans.reserve(project.clips.size());

    for (const auto& clip : project.clips) {
        if (!QFileInfo::exists(clip.media.filePath)) {
            errorOut = QStringLiteral("Medya dosyası bulunamadı: %1").arg(clip.media.fileName);
            return {};
        }

        const TimelineTrack* track = findTrackForIndex(project.tracks, clip.trackIndex);
        const bool trackMuted = track ? track->muted : false;
        const bool trackSolo = track ? track->solo : false;
        const double trackVolume = track ? track->volume : 1.0;

        const bool isVisual = (clip.media.category == MediaCategory::Video || clip.media.category == MediaCategory::Image)
                           && (clip.trackType != MediaCategory::Audio);
        const bool includeVideo = !isAudioTarget && isVisual && !trackMuted && (!anyVideoSolo || trackSolo);

        const bool hasAudio = (clip.media.category == MediaCategory::Audio)
                           || (clip.media.category == MediaCategory::Video
                               && (!clip.media.audioStreams.isEmpty() || !clip.media.acodec.isEmpty()));
        const double effVol = qMax(0.0, clip.volume * trackVolume);
        const bool includeAudio = !isGifTarget && hasAudio && !trackMuted && !clip.muted
                               && (!anyAudioSolo || trackSolo) && (effVol > 1e-4);

        if (includeVideo || includeAudio) {
            plans.append({clip, includeVideo, includeAudio, effVol, -1});
        }
    }

    std::stable_sort(plans.begin(), plans.end(), [](const ActiveClipPlan& a, const ActiveClipPlan& b) {
        if (a.clip.trackIndex != b.clip.trackIndex) {
            return a.clip.trackIndex < b.clip.trackIndex;
        }
        if (!qFuzzyCompare(a.clip.timelineStartSec, b.clip.timelineStartSec)) {
            return a.clip.timelineStartSec < b.clip.timelineStartSec;
        }
        return a.clip.id < b.clip.id;
    });

    QStringList args;
    args << QStringLiteral("-y") << QStringLiteral("-hide_banner");

    int nextInputIdx = 0;
    for (auto& plan : plans) {
        const TimelineClip& clip = plan.clip;
        const double dur = clip.clipDuration();
        plan.inputIndex = nextInputIdx++;

        if (clip.media.category == MediaCategory::Image) {
            args << QStringLiteral("-stream_loop") << QStringLiteral("-1")
                 << QStringLiteral("-t") << QString::number(dur, 'f', 3)
                 << QStringLiteral("-i") << clip.media.filePath;
        } else {
            if (clip.inPointSec > 0.0001) {
                args << QStringLiteral("-ss") << QString::number(clip.inPointSec, 'f', 3);
            }
            args << QStringLiteral("-t") << QString::number(dur, 'f', 3)
                 << QStringLiteral("-i") << clip.media.filePath;
        }
    }

    args << QStringLiteral("-progress") << QStringLiteral("pipe:1") << QStringLiteral("-nostats");

    QStringList filterStages;
    QString finalVideoLabel;
    QString finalAudioLabel;

    // 1) Visual compositing graph
    if (!isAudioTarget) {
        filterStages << QStringLiteral("color=c=black:s=%1x%2:r=%3:d=%4[base]")
                            .arg(outW)
                            .arg(outH)
                            .arg(outFps)
                            .arg(QString::number(totalDurationSec, 'f', 3));

        QString currentBase = QStringLiteral("base");
        int visualSeq = 0;

        for (const auto& plan : plans) {
            if (!plan.includeVideo) {
                continue;
            }
            const TimelineClip& clip = plan.clip;
            const double dur = clip.clipDuration();
            const double start = qMax(0.0, clip.timelineStartSec);
            const double end = start + dur;
            const QString vLabel = QStringLiteral("v%1").arg(visualSeq);

            QStringList chain;
            chain << QStringLiteral("trim=duration=%1").arg(QString::number(dur, 'f', 3))
                  << QStringLiteral("setpts=PTS-STARTPTS");
            chain.append(PreviewProvider::buildVideoFilterArgs(clip.filters));
            chain << QStringLiteral("fps=%1").arg(outFps)
                  << QStringLiteral("scale=%1:%2:force_original_aspect_ratio=decrease:flags=lanczos")
                         .arg(outW)
                         .arg(outH)
                  << QStringLiteral("pad=%1:%2:(ow-iw)/2:(oh-ih)/2:color=black@0")
                         .arg(outW)
                         .arg(outH)
                  << QStringLiteral("setsar=1")
                  << QStringLiteral("format=yuva420p")
                  << QStringLiteral("setpts=PTS+%1/TB").arg(QString::number(start, 'f', 3));

            filterStages << QStringLiteral("[%1:v]%2[%3]")
                                .arg(plan.inputIndex)
                                .arg(chain.join(QLatin1Char(',')))
                                .arg(vLabel);

            const QString ovLabel = QStringLiteral("ov%1").arg(visualSeq);
            filterStages << QStringLiteral("[%1][%2]overlay=0:0:enable='between(t,%3,%4)':eof_action=pass[%5]")
                                .arg(currentBase,
                                     vLabel,
                                     QString::number(start, 'f', 3),
                                     QString::number(end, 'f', 3),
                                     ovLabel);
            currentBase = ovLabel;
            ++visualSeq;
        }

        if (isGifTarget) {
            const int gifFps = qBound(1, outFps, 30);
            filterStages << QStringLiteral("[%1]fps=%2,split[gs0][gs1];[gs0]palettegen[gp];[gs1][gp]paletteuse[outv]")
                                .arg(currentBase)
                                .arg(gifFps);
        } else {
            filterStages << QStringLiteral("[%1]format=yuv420p[outv]").arg(currentBase);
        }
        finalVideoLabel = QStringLiteral("[outv]");
    }

    // 2) Audio mixing graph
    if (!isGifTarget) {
        QStringList audioInputs;
        int audioSeq = 0;

        for (const auto& plan : plans) {
            if (!plan.includeAudio) {
                continue;
            }
            const TimelineClip& clip = plan.clip;
            const double dur = clip.clipDuration();
            const qint64 delayMs = qMax<qint64>(0, qRound64(clip.timelineStartSec * 1000.0));

            int streamIdx = 0;
            if (clip.media.category == MediaCategory::Video && !clip.media.audioStreams.isEmpty()) {
                streamIdx = qBound(0, clip.selectedAudioStream, static_cast<int>(clip.media.audioStreams.size()) - 1);
            } else if (clip.media.category == MediaCategory::Video && clip.selectedAudioStream > 0) {
                streamIdx = clip.selectedAudioStream;
            }

            QStringList aChain;
            aChain << QStringLiteral("atrim=duration=%1").arg(QString::number(dur, 'f', 3))
                   << QStringLiteral("asetpts=PTS-STARTPTS")
                   << QStringLiteral("aresample=%1").arg(sampleRate)
                   << QStringLiteral("aformat=sample_fmts=fltp:channel_layouts=stereo");

            if (qAbs(plan.effectiveVolume - 1.0) > 1e-4) {
                aChain << QStringLiteral("volume=%1").arg(QString::number(plan.effectiveVolume, 'f', 3));
            }
            if (clip.fadeInSec > 0.0) {
                const double fi = qMin(clip.fadeInSec, dur);
                if (fi > 0.0) {
                    aChain << QStringLiteral("afade=t=in:st=0:d=%1").arg(QString::number(fi, 'f', 3));
                }
            }
            if (clip.fadeOutSec > 0.0) {
                const double fo = qMin(clip.fadeOutSec, dur);
                const double foStart = qMax(0.0, dur - fo);
                if (fo > 0.0) {
                    aChain << QStringLiteral("afade=t=out:st=%1:d=%2")
                                  .arg(QString::number(foStart, 'f', 3),
                                       QString::number(fo, 'f', 3));
                }
            }
            if (delayMs > 0) {
                aChain << QStringLiteral("adelay=%1:all=1").arg(delayMs);
            }

            const QString aLabel = QStringLiteral("a%1").arg(audioSeq++);
            filterStages << QStringLiteral("[%1:a:%2]%3[%4]")
                                .arg(plan.inputIndex)
                                .arg(streamIdx)
                                .arg(aChain.join(QLatin1Char(',')))
                                .arg(aLabel);
            audioInputs << QStringLiteral("[%1]").arg(aLabel);
        }

        if (audioInputs.isEmpty()) {
            if (isAudioTarget) {
                filterStages << QStringLiteral("anullsrc=r=%1:cl=stereo,atrim=duration=%2[outa]")
                                    .arg(sampleRate)
                                    .arg(QString::number(totalDurationSec, 'f', 3));
                finalAudioLabel = QStringLiteral("[outa]");
            }
        } else if (audioInputs.size() == 1) {
            filterStages << QStringLiteral("%1apad=whole_dur=%2[outa]")
                                .arg(audioInputs.first(),
                                     QString::number(totalDurationSec, 'f', 3));
            finalAudioLabel = QStringLiteral("[outa]");
        } else {
            filterStages << QStringLiteral("%1amix=inputs=%2:duration=longest:dropout_transition=0:normalize=0,apad=whole_dur=%3[outa]")
                                .arg(audioInputs.join(QString()))
                                .arg(audioInputs.size())
                                .arg(QString::number(totalDurationSec, 'f', 3));
            finalAudioLabel = QStringLiteral("[outa]");
        }
    }

    if (filterStages.isEmpty()) {
        errorOut = QStringLiteral("Dışa aktarılacak aktif video veya ses akışı bulunamadı.");
        return {};
    }

    args << QStringLiteral("-filter_complex") << filterStages.join(QLatin1Char(';'));

    if (!finalVideoLabel.isEmpty()) {
        args << QStringLiteral("-map") << finalVideoLabel;
    }
    if (!finalAudioLabel.isEmpty()) {
        args << QStringLiteral("-map") << finalAudioLabel;
    }

    if (isAudioTarget) {
        const QString acodec = fmt.acodec.isEmpty() ? QStringLiteral("aac") : fmt.acodec;
        args << QStringLiteral("-vn") << QStringLiteral("-c:a") << acodec;
        if (targetExt != QLatin1String("wav") && targetExt != QLatin1String("flac")
            && targetExt != QLatin1String("aiff") && !settings.audioBitrate.isEmpty()) {
            args << QStringLiteral("-b:a") << settings.audioBitrate;
        }
        args << QStringLiteral("-ar") << QString::number(sampleRate);
    } else if (isGifTarget) {
        args << QStringLiteral("-an") << QStringLiteral("-loop") << QStringLiteral("0");
    } else {
        const QString rawVcodec = fmt.vcodec.isEmpty() ? QStringLiteral("libx264") : fmt.vcodec;
        const QString vcodec = PreviewProvider::normalizeVideoCodec(rawVcodec);
        args << QStringLiteral("-c:v") << vcodec;

        if (vcodec == QLatin1String("libx264") || vcodec == QLatin1String("libx265")) {
            const int crf = qBound(14, qRound(36.0 - (settings.quality / 100.0) * 22.0), 36);
            args << QStringLiteral("-crf") << QString::number(crf)
                 << QStringLiteral("-preset") << QStringLiteral("medium")
                 << QStringLiteral("-pix_fmt") << QStringLiteral("yuv420p");
            if (vcodec == QLatin1String("libx265")
                && (targetExt == QLatin1String("mp4") || targetExt == QLatin1String("mov")
                    || targetExt == QLatin1String("m4v"))) {
                args << QStringLiteral("-tag:v") << QStringLiteral("hvc1");
            }
        } else if (vcodec == QLatin1String("libvpx-vp9")) {
            const int crf = qBound(18, qRound(42.0 - (settings.quality / 100.0) * 24.0), 42);
            args << QStringLiteral("-crf") << QString::number(crf)
                 << QStringLiteral("-b:v") << QStringLiteral("0")
                 << QStringLiteral("-pix_fmt") << QStringLiteral("yuv420p");
        } else {
            const int qscale = qBound(2, qRound(15.0 - (settings.quality / 100.0) * 13.0), 15);
            args << QStringLiteral("-q:v") << QString::number(qscale);
        }

        if (finalAudioLabel.isEmpty()) {
            args << QStringLiteral("-an");
        } else {
            const QString acodec = fmt.acodec.isEmpty() ? QStringLiteral("aac") : fmt.acodec;
            args << QStringLiteral("-c:a") << acodec;
            if (!acodec.startsWith(QLatin1String("pcm_")) && acodec != QLatin1String("flac")
                && !settings.audioBitrate.isEmpty()) {
                args << QStringLiteral("-b:a") << settings.audioBitrate;
            }
            args << QStringLiteral("-ar") << QString::number(sampleRate);
        }

        if (targetExt == QLatin1String("mp4") || targetExt == QLatin1String("m4v")
            || targetExt == QLatin1String("mov")) {
            args << QStringLiteral("-movflags") << QStringLiteral("+faststart");
        }
    }

    args << QStringLiteral("-t") << QString::number(totalDurationSec, 'f', 3)
         << m_outputPath;
    return args;
}

void TimelineExporter::onProcessReadyReadStdout() {
    m_stdoutBuffer.append(m_process->readAllStandardOutput());
    qsizetype idx = -1;
    while ((idx = m_stdoutBuffer.indexOf('\n')) != -1) {
        const QString line = QString::fromUtf8(m_stdoutBuffer.left(idx)).trimmed();
        m_stdoutBuffer.remove(0, idx + 1);

        if (line.startsWith(QLatin1String("out_time_us="))) {
            bool ok = false;
            const qint64 us = line.mid(12).toLongLong(&ok);
            if (ok && m_totalDurationSec > 0.0) {
                const int pct = qBound(2, static_cast<int>((us / (m_totalDurationSec * 1000000.0)) * 100.0), 99);
                emit exportProgress(pct, QStringLiteral("Dışa aktarılıyor... %1%").arg(pct));
            }
        } else if (line == QLatin1String("progress=end")) {
            emit exportProgress(99, QStringLiteral("Dosya sonlandırılıyor..."));
        }
    }
}

void TimelineExporter::onProcessFinished(int exitCode, QProcess::ExitStatus exitStatus) {
    m_running = false;

    if (m_cancelled) {
        emit exportFinished(false, m_outputPath, QStringLiteral("Dışa aktarma iptal edildi."));
        return;
    }

    if (exitStatus == QProcess::NormalExit && exitCode == 0) {
        emit exportProgress(100, QStringLiteral("Tamamlandı"));
        emit exportFinished(true, m_outputPath, QStringLiteral("Zaman çizelgesi başarıyla dışa aktarıldı."));
    } else {
        const QString err = QString::fromUtf8(m_process->readAllStandardError()).trimmed();
        const QStringList lines = err.split(QLatin1Char('\n'), Qt::SkipEmptyParts);
        const QString shortErr = lines.isEmpty()
            ? QStringLiteral("FFmpeg çıkış kodu: %1").arg(exitCode)
            : lines.last();
        emit exportFinished(false, m_outputPath, shortErr);
    }
}
