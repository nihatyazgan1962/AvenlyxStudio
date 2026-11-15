#pragma once

#include "projectmodel.h"

#include <QPoint>
#include <QRect>
#include <QStringList>
#include <QWidget>

class TimelineWidget : public QWidget {
    Q_OBJECT
public:
    explicit TimelineWidget(QWidget* parent = nullptr);

    void setProject(StudioProject* project);
    double playheadSec() const;
    void setPlayheadSec(double sec);
    int selectedClipId() const;
    void setSelectedClipId(int clipId);
    void setPixelsPerSecond(double pps);
    double pixelsPerSecond() const;
    void refreshLayout();

signals:
    void playheadChanged(double sec);
    void clipSelected(int clipId);
    void projectModified();
    void filesDroppedOnTrack(const QStringList& paths, int trackIndex, double dropTimeSec);

protected:
    void paintEvent(QPaintEvent* event) override;
    void mousePressEvent(QMouseEvent* event) override;
    void mouseMoveEvent(QMouseEvent* event) override;
    void mouseReleaseEvent(QMouseEvent* event) override;
    void dragEnterEvent(QDragEnterEvent* event) override;
    void dragMoveEvent(QDragMoveEvent* event) override;
    void dragLeaveEvent(QDragLeaveEvent* event) override;
    void dropEvent(QDropEvent* event) override;

private:
    enum class DragMode {
        None,
        ScrubPlayhead,
        MoveClip,
        TrimLeft,
        TrimRight
    };

    static constexpr int kRulerHeight = 30;
    static constexpr int kHeaderWidth = 164;
    static constexpr int kTrackHeight = 66;
    static constexpr int kTrackGap = 4;
    static constexpr int kTrimHandlePx = 8;

    double timeToX(double sec) const;
    double xToTime(double x) const;
    int trackY(int trackIndex) const;
    int trackIndexAtY(int y) const;
    QRect muteButtonRect(int trackIndex) const;
    QRect soloButtonRect(int trackIndex) const;
    QRectF clipRectFor(const TimelineClip& clip) const;
    int hitTestClip(const QPoint& pos) const;
    double snapTime(double candidateSec, int ignoreClipId, bool disableSnap) const;
    QString trackBadgeText(int trackIndex) const;
    bool isTrackEffectivelyMuted(int trackIndex) const;
    void updateHoverCursor(const QPoint& pos);

    StudioProject* m_project = nullptr;
    double m_playheadSec = 0.0;
    double m_pixelsPerSecond = 60.0;
    int m_selectedClipId = -1;

    DragMode m_dragMode = DragMode::None;
    int m_dragClipId = -1;
    QPoint m_dragStartPos;
    double m_dragOrigStartSec = 0.0;
    double m_dragOrigInSec = 0.0;
    double m_dragOrigOutSec = 0.0;
    int m_dragOrigTrackIndex = 0;
    bool m_dragDirty = false;

    bool m_dropActive = false;
    int m_dropHoverTrack = -1;
    double m_dropHoverTimeSec = 0.0;
};
