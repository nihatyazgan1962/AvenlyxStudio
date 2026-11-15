#pragma once

#include "mediaprobe.h"

#include <QList>
#include <QString>
#include <QVector>
#include <QtGlobal>

struct FilterAdjustments {
    double brightness = 0.0; // -1.0..1.0
    double contrast = 1.0;   // 0.2..2.0
    double saturation = 1.0; // 0.0..3.0
    int rotateDegrees = 0;   // 0, 90, 180, 270
    bool flipH = false;
    bool flipV = false;
    bool grayscale = false;
    bool sepia = false;
};

struct TimelineClip {
    int id = 0;
    MediaInfo media;
    int trackIndex = 0;
    MediaCategory trackType = MediaCategory::Video;
    double timelineStartSec = 0.0;
    double inPointSec = 0.0;
    double outPointSec = 5.0;
    double volume = 1.0;
    bool muted = false;
    double fadeInSec = 0.0;
    double fadeOutSec = 0.0;
    int selectedAudioStream = 0;
    FilterAdjustments filters;
    QVector<float> waveformPeaks;

    double clipDuration() const { return qMax(0.1, outPointSec - inPointSec); }
};

struct TimelineTrack {
    int index = 0;
    QString name;
    MediaCategory type = MediaCategory::Video;
    bool muted = false;
    bool solo = false;
    bool locked = false;
    double volume = 1.0;
};

class StudioProject {
public:
    QList<TimelineTrack> tracks;
    QList<TimelineClip> clips;
    int nextClipId = 1;

    StudioProject();

    double totalDurationSec() const;
    int addClip(const MediaInfo& media, int trackIndex, double timelineStartSec = -1.0);
    bool removeClip(int clipId);
    int splitClip(int clipId, double splitTimelineSec);
    TimelineClip* findClip(int clipId);
    const TimelineClip* findClip(int clipId) const;
    void addTrack(MediaCategory type);
    void clear();
};
