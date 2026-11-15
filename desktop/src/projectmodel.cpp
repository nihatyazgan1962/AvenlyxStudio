#include "projectmodel.h"

#include <QtGlobal>

StudioProject::StudioProject() {
    clear();
}

void StudioProject::clear() {
    clips.clear();
    tracks.clear();
    nextClipId = 1;

    tracks.append({0, QStringLiteral("V1 (Video 1)"),       MediaCategory::Video, false, false, false, 1.0});
    tracks.append({1, QStringLiteral("V2 (Görsel/Katman)"), MediaCategory::Video, false, false, false, 1.0});
    tracks.append({2, QStringLiteral("A1 (Ses 1)"),         MediaCategory::Audio, false, false, false, 1.0});
    tracks.append({3, QStringLiteral("A2 (Ses 2)"),         MediaCategory::Audio, false, false, false, 1.0});
}

double StudioProject::totalDurationSec() const {
    double total = 0.0;
    for (const auto& clip : clips) {
        total = qMax(total, clip.timelineStartSec + clip.clipDuration());
    }
    return total;
}

int StudioProject::addClip(const MediaInfo& media, int trackIndex, double timelineStartSec) {
    if (tracks.isEmpty()) {
        return -1;
    }

    const MediaCategory desiredTrackType =
        (media.category == MediaCategory::Audio) ? MediaCategory::Audio : MediaCategory::Video;

    int targetTrack = -1;
    if (trackIndex >= 0 && trackIndex < tracks.size()) {
        if (tracks[trackIndex].type == desiredTrackType) {
            targetTrack = trackIndex;
        } else {
            for (int i = 0; i < tracks.size(); ++i) {
                if (tracks[i].type == desiredTrackType) {
                    targetTrack = i;
                    break;
                }
            }
        }
    } else if (trackIndex < 0) {
        for (int i = 0; i < tracks.size(); ++i) {
            if (tracks[i].type == desiredTrackType) {
                targetTrack = i;
                break;
            }
        }
    }

    if (targetTrack < 0 || targetTrack >= tracks.size()) {
        return -1;
    }

    double startSec = timelineStartSec;
    if (startSec < 0.0) {
        startSec = 0.0;
        for (const auto& existing : clips) {
            if (existing.trackIndex == targetTrack) {
                startSec = qMax(startSec, existing.timelineStartSec + existing.clipDuration());
            }
        }
    }

    TimelineClip clip;
    clip.id = nextClipId++;
    clip.media = media;
    clip.trackIndex = targetTrack;
    clip.trackType = tracks[targetTrack].type;
    clip.timelineStartSec = startSec;
    clip.inPointSec = 0.0;

    if (media.category == MediaCategory::Image) {
        clip.outPointSec = (media.durationSec > 0.1) ? media.durationSec : 5.0;
    } else {
        clip.outPointSec = (media.durationSec > 0.0) ? qMax(0.1, media.durationSec) : 5.0;
    }

    clip.selectedAudioStream = 0;
    clips.append(clip);
    return clip.id;
}

bool StudioProject::removeClip(int clipId) {
    for (int i = 0; i < clips.size(); ++i) {
        if (clips[i].id == clipId) {
            clips.removeAt(i);
            return true;
        }
    }
    return false;
}

int StudioProject::splitClip(int clipId, double splitTimelineSec) {
    int idx = -1;
    for (int i = 0; i < clips.size(); ++i) {
        if (clips[i].id == clipId) {
            idx = i;
            break;
        }
    }
    if (idx < 0) {
        return -1;
    }

    const double start = clips[idx].timelineStartSec;
    const double dur = clips[idx].clipDuration();
    const double offset = splitTimelineSec - start;

    if (offset < 0.1 || (dur - offset) < 0.1) {
        return -1;
    }

    const double splitMediaPoint = clips[idx].inPointSec + offset;

    TimelineClip rightClip = clips[idx];
    rightClip.id = nextClipId++;
    rightClip.timelineStartSec = splitTimelineSec;
    rightClip.inPointSec = splitMediaPoint;
    rightClip.fadeInSec = 0.0;
    rightClip.fadeOutSec = qMin(rightClip.fadeOutSec, rightClip.clipDuration());

    clips[idx].outPointSec = splitMediaPoint;
    clips[idx].fadeOutSec = 0.0;
    clips[idx].fadeInSec = qMin(clips[idx].fadeInSec, clips[idx].clipDuration());

    clips.append(rightClip);
    return rightClip.id;
}

TimelineClip* StudioProject::findClip(int clipId) {
    for (auto& clip : clips) {
        if (clip.id == clipId) {
            return &clip;
        }
    }
    return nullptr;
}

const TimelineClip* StudioProject::findClip(int clipId) const {
    for (const auto& clip : clips) {
        if (clip.id == clipId) {
            return &clip;
        }
    }
    return nullptr;
}

void StudioProject::addTrack(MediaCategory type) {
    const MediaCategory trackType =
        (type == MediaCategory::Audio) ? MediaCategory::Audio : MediaCategory::Video;

    int typeCount = 0;
    int insertIdx = tracks.size();

    if (trackType == MediaCategory::Video) {
        insertIdx = 0;
        for (int i = 0; i < tracks.size(); ++i) {
            if (tracks[i].type == MediaCategory::Video) {
                ++typeCount;
                insertIdx = i + 1;
            }
        }
    } else {
        for (const auto& track : tracks) {
            if (track.type == MediaCategory::Audio) {
                ++typeCount;
            }
        }
    }

    TimelineTrack newTrack;
    newTrack.index = insertIdx;
    newTrack.type = trackType;
    newTrack.name = (trackType == MediaCategory::Video)
        ? QStringLiteral("V%1 (Video %1)").arg(typeCount + 1)
        : QStringLiteral("A%1 (Ses %1)").arg(typeCount + 1);

    tracks.insert(insertIdx, newTrack);

    for (int i = 0; i < tracks.size(); ++i) {
        tracks[i].index = i;
    }

    for (auto& clip : clips) {
        if (clip.trackIndex >= insertIdx) {
            ++clip.trackIndex;
        }
    }
}
