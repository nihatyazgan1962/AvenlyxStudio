#include "timelinewidget.h"

#include <QDirIterator>
#include <QDragEnterEvent>
#include <QDragLeaveEvent>
#include <QDragMoveEvent>
#include <QDropEvent>
#include <QFileInfo>
#include <QFontMetrics>
#include <QLinearGradient>
#include <QMimeData>
#include <QMouseEvent>
#include <QPainter>
#include <QPainterPath>
#include <QPaintEvent>
#include <QUrl>
#include <QtGlobal>

#include <cmath>

TimelineWidget::TimelineWidget(QWidget* parent)
    : QWidget(parent) {
    setMouseTracking(true);
    setAcceptDrops(true);
    setFocusPolicy(Qt::ClickFocus);
    refreshLayout();
}

void TimelineWidget::setProject(StudioProject* project) {
    m_project = project;
    m_selectedClipId = -1;
    m_playheadSec = 0.0;
    refreshLayout();
}

double TimelineWidget::playheadSec() const {
    return m_playheadSec;
}

void TimelineWidget::setPlayheadSec(double sec) {
    const double clamped = qMax(0.0, sec);
    if (!qFuzzyCompare(m_playheadSec + 1.0, clamped + 1.0)) {
        m_playheadSec = clamped;
        update();
    }
}

int TimelineWidget::selectedClipId() const {
    return m_selectedClipId;
}

void TimelineWidget::setSelectedClipId(int clipId) {
    if (m_selectedClipId != clipId) {
        m_selectedClipId = clipId;
        update();
    }
}

void TimelineWidget::setPixelsPerSecond(double pps) {
    const double clamped = qBound(10.0, pps, 250.0);
    if (!qFuzzyCompare(m_pixelsPerSecond, clamped)) {
        m_pixelsPerSecond = clamped;
        refreshLayout();
    }
}

double TimelineWidget::pixelsPerSecond() const {
    return m_pixelsPerSecond;
}

void TimelineWidget::refreshLayout() {
    const int trackCount = m_project ? m_project->tracks.size() : 4;
    const double projDur = m_project ? m_project->totalDurationSec() : 0.0;
    const double displaySec = qMax(45.0, qMax(projDur + 12.0, m_playheadSec + 10.0));

    const int minW = kHeaderWidth + qRound(displaySec * m_pixelsPerSecond);
    const int minH = kRulerHeight + trackCount * (kTrackHeight + kTrackGap) + 16;

    setMinimumSize(minW, minH);
    resize(qMax(width(), minW), qMax(height(), minH));
    updateGeometry();
    update();
}

double TimelineWidget::timeToX(double sec) const {
    return static_cast<double>(kHeaderWidth) + sec * m_pixelsPerSecond;
}

double TimelineWidget::xToTime(double x) const {
    return qMax(0.0, (x - static_cast<double>(kHeaderWidth)) / m_pixelsPerSecond);
}

int TimelineWidget::trackY(int trackIndex) const {
    return kRulerHeight + 4 + trackIndex * (kTrackHeight + kTrackGap);
}

int TimelineWidget::trackIndexAtY(int y) const {
    if (!m_project || m_project->tracks.isEmpty()) return -1;
    if (y < kRulerHeight + 4) return -1;
    const int idx = (y - (kRulerHeight + 4)) / (kTrackHeight + kTrackGap);
    if (idx < 0 || idx >= m_project->tracks.size()) return -1;
    return idx;
}

QRect TimelineWidget::muteButtonRect(int trackIndex) const {
    const int y = trackY(trackIndex);
    return QRect(kHeaderWidth - 56, y + (kTrackHeight - 24) / 2, 23, 24);
}

QRect TimelineWidget::soloButtonRect(int trackIndex) const {
    const int y = trackY(trackIndex);
    return QRect(kHeaderWidth - 29, y + (kTrackHeight - 24) / 2, 23, 24);
}

QRectF TimelineWidget::clipRectFor(const TimelineClip& clip) const {
    const double x = timeToX(clip.timelineStartSec);
    const double w = qMax(12.0, clip.clipDuration() * m_pixelsPerSecond);
    const int y = trackY(clip.trackIndex);
    return QRectF(x, y + 4, w, kTrackHeight - 8);
}

int TimelineWidget::hitTestClip(const QPoint& pos) const {
    if (!m_project) return -1;
    for (int i = m_project->clips.size() - 1; i >= 0; --i) {
        const auto& clip = m_project->clips[i];
        if (clip.trackIndex < 0 || clip.trackIndex >= m_project->tracks.size()) continue;
        if (clipRectFor(clip).contains(pos)) {
            return clip.id;
        }
    }
    return -1;
}

double TimelineWidget::snapTime(double candidateSec, int ignoreClipId, bool disableSnap) const {
    if (disableSnap || !m_project) return qMax(0.0, candidateSec);

    const double thresholdSec = 8.0 / m_pixelsPerSecond;
    double best = candidateSec;
    double bestDist = thresholdSec;

    auto testTarget = [&](double target) {
        const double d = std::abs(candidateSec - target);
        if (d < bestDist) {
            bestDist = d;
            best = target;
        }
    };

    testTarget(0.0);
    testTarget(m_playheadSec);

    for (const auto& c : m_project->clips) {
        if (c.id == ignoreClipId) continue;
        testTarget(c.timelineStartSec);
        testTarget(c.timelineStartSec + c.clipDuration());
    }

    return qMax(0.0, best);
}

QString TimelineWidget::trackBadgeText(int trackIndex) const {
    if (!m_project || trackIndex < 0 || trackIndex >= m_project->tracks.size()) return {};
    const auto type = m_project->tracks[trackIndex].type;
    int count = 0;
    for (int i = 0; i <= trackIndex; ++i) {
        if (m_project->tracks[i].type == type) ++count;
    }
    return (type == MediaCategory::Audio ? "A" : "V") + QString::number(count);
}

bool TimelineWidget::isTrackEffectivelyMuted(int trackIndex) const {
    if (!m_project || trackIndex < 0 || trackIndex >= m_project->tracks.size()) return false;
    const auto& tr = m_project->tracks[trackIndex];
    if (tr.muted) return true;

    bool anySameTypeSolo = false;
    for (const auto& t : m_project->tracks) {
        if (t.type == tr.type && t.solo) {
            anySameTypeSolo = true;
            break;
        }
    }
    return anySameTypeSolo && !tr.solo;
}

void TimelineWidget::paintEvent(QPaintEvent* event) {
    Q_UNUSED(event);
    QPainter p(this);
    p.setRenderHint(QPainter::Antialiasing, true);

    const int w = width();
    const int h = height();

    // Base background
    p.fillRect(rect(), QColor("#0d121d"));

    // Header column background
    p.fillRect(0, 0, kHeaderWidth, h, QColor("#151d2e"));

    // Ruler background
    p.fillRect(kHeaderWidth, 0, w - kHeaderWidth, kRulerHeight, QColor("#162033"));
    p.setPen(QColor("#28354d"));
    p.drawLine(0, kRulerHeight, w, kRulerHeight);
    p.drawLine(kHeaderWidth, 0, kHeaderWidth, h);

    // Top-left corner playhead timecode badge
    {
        QFont tcFont("Consolas", 9);
        tcFont.setStyleHint(QFont::Monospace);
        tcFont.setBold(true);
        p.setFont(tcFont);
        p.setPen(QColor("#f87171"));
        p.drawText(QRect(8, 0, kHeaderWidth - 16, kRulerHeight),
                   Qt::AlignVCenter | Qt::AlignLeft,
                   MediaInfo::formatTimecode(m_playheadSec));
    }

    // Determine adaptive ruler tick interval
    double majorStep = 5.0;
    int minorDivs = 5;
    if (m_pixelsPerSecond >= 140.0) {
        majorStep = 1.0;
        minorDivs = 5;
    } else if (m_pixelsPerSecond >= 70.0) {
        majorStep = 2.0;
        minorDivs = 4;
    } else if (m_pixelsPerSecond >= 30.0) {
        majorStep = 5.0;
        minorDivs = 5;
    } else if (m_pixelsPerSecond >= 14.0) {
        majorStep = 10.0;
        minorDivs = 5;
    } else {
        majorStep = 20.0;
        minorDivs = 4;
    }

    const double maxVisibleSec = xToTime(w) + majorStep;
    const double minorStep = majorStep / minorDivs;

    // Track lanes & headers
    const int trackCount = m_project ? m_project->tracks.size() : 0;
    for (int i = 0; i < trackCount; ++i) {
        const auto& tr = m_project->tracks[i];
        const int y = trackY(i);

        // Lane background
        QColor laneColor = (i % 2 == 0) ? QColor("#111726") : QColor("#131a2b");
        if (m_dropActive && m_dropHoverTrack == i) {
            laneColor = QColor("#1a2942");
        }
        p.fillRect(kHeaderWidth + 1, y, w - kHeaderWidth - 1, kTrackHeight, laneColor);

        // Lane separator
        p.setPen(QColor("#1e293b"));
        p.drawLine(0, y + kTrackHeight + 2, w, y + kTrackHeight + 2);

        // Track header box
        QRect hdrRect(0, y, kHeaderWidth, kTrackHeight);
        p.fillRect(hdrRect, QColor("#172033"));
        p.setPen(QColor("#26334d"));
        p.drawLine(0, y + kTrackHeight, kHeaderWidth, y + kTrackHeight);

        // Track type pill (V1, V2, A1, A2...)
        const QString badge = trackBadgeText(i);
        const QColor badgeBg = (tr.type == MediaCategory::Audio) ? QColor("#065f46") : QColor("#1d4ed8");
        const QColor badgeBorder = (tr.type == MediaCategory::Audio) ? QColor("#10b981") : QColor("#60a5fa");
        QRect badgeRect(8, y + 10, 28, 22);
        p.setPen(QPen(badgeBorder, 1));
        p.setBrush(badgeBg);
        p.drawRoundedRect(badgeRect, 4, 4);

        QFont bFont = p.font();
        bFont.setFamily("Segoe UI");
        bFont.setPointSize(8);
        bFont.setBold(true);
        p.setFont(bFont);
        p.setPen(QColor("#ffffff"));
        p.drawText(badgeRect, Qt::AlignCenter, badge);

        // Track title (strip redundant "V1 (" prefix if already in badge)
        QString displayTitle = tr.name;
        const int openParen = displayTitle.indexOf('(');
        const int closeParen = displayTitle.lastIndexOf(')');
        if (openParen > 0 && closeParen > openParen + 1) {
            displayTitle = displayTitle.mid(openParen + 1, closeParen - openParen - 1).trimmed();
        }
        QFont tFont = p.font();
        tFont.setPointSize(9);
        tFont.setBold(true);
        p.setFont(tFont);
        p.setPen(isTrackEffectivelyMuted(i) ? QColor("#64748b") : QColor("#e2e8f0"));
        QRect nameRect(42, y + 8, kHeaderWidth - 102, 24);
        const QString elidedName = QFontMetrics(tFont).elidedText(displayTitle, Qt::ElideRight, nameRect.width());
        p.drawText(nameRect, Qt::AlignVCenter | Qt::AlignLeft, elidedName);

        // Subtle track subtitle
        QFont sFont = p.font();
        sFont.setPointSize(7);
        sFont.setBold(false);
        p.setFont(sFont);
        p.setPen(QColor("#64748b"));
        p.drawText(QRect(8, y + 38, kHeaderWidth - 70, 18), Qt::AlignVCenter | Qt::AlignLeft,
                   tr.type == MediaCategory::Audio ? "Ses Kanalı" : "Video / Görsel");

        // Mute [M] button
        const QRect mRect = muteButtonRect(i);
        p.setPen(QPen(tr.muted ? QColor("#ef4444") : QColor("#334155"), 1));
        p.setBrush(tr.muted ? QColor("#b91c1c") : QColor("#1e293b"));
        p.drawRoundedRect(mRect, 4, 4);
        p.setFont(bFont);
        p.setPen(tr.muted ? QColor("#ffffff") : QColor("#94a3b8"));
        p.drawText(mRect, Qt::AlignCenter, "M");

        // Solo [S] button
        const QRect sRect = soloButtonRect(i);
        p.setPen(QPen(tr.solo ? QColor("#f59e0b") : QColor("#334155"), 1));
        p.setBrush(tr.solo ? QColor("#d97706") : QColor("#1e293b"));
        p.drawRoundedRect(sRect, 4, 4);
        p.setPen(tr.solo ? QColor("#ffffff") : QColor("#94a3b8"));
        p.drawText(sRect, Qt::AlignCenter, "S");
    }

    // Vertical grid lines & ruler ticks
    QFont rulerFont = p.font();
    rulerFont.setFamily("Consolas");
    rulerFont.setPointSize(8);
    rulerFont.setBold(false);
    p.setFont(rulerFont);

    const int totalMinorSteps = static_cast<int>(std::ceil(maxVisibleSec / minorStep));
    for (int stepIdx = 0; stepIdx <= totalMinorSteps; ++stepIdx) {
        const double t = stepIdx * minorStep;
        const int x = qRound(timeToX(t));
        if (x < kHeaderWidth || x > w) continue;

        const bool isMajor = (stepIdx % minorDivs) == 0;
        if (isMajor) {
            // Subtle grid line across tracks
            p.setPen(QColor("#1a2438"));
            p.drawLine(x, kRulerHeight + 1, x, h);

            // Major tick
            p.setPen(QColor("#64748b"));
            p.drawLine(x, kRulerHeight - 12, x, kRulerHeight);

            const int totalSec = qRound(t);
            const int mins = (totalSec / 60) % 60;
            const int hrs = totalSec / 3600;
            const int secs = totalSec % 60;
            const QString label = hrs > 0
                ? QString("%1:%2:%3").arg(hrs).arg(mins, 2, 10, QChar('0')).arg(secs, 2, 10, QChar('0'))
                : QString("%1:%2").arg(mins, 2, 10, QChar('0')).arg(secs, 2, 10, QChar('0'));
            p.setPen(QColor("#94a3b8"));
            p.drawText(x + 4, 2, 64, kRulerHeight - 10, Qt::AlignLeft | Qt::AlignVCenter, label);
        } else {
            p.setPen(QColor("#334155"));
            p.drawLine(x, kRulerHeight - 6, x, kRulerHeight);
        }
    }

    // Clips
    if (m_project) {
        for (const auto& clip : m_project->clips) {
            if (clip.trackIndex < 0 || clip.trackIndex >= m_project->tracks.size()) continue;

            const QRectF r = clipRectFor(clip);
            if (r.right() < kHeaderWidth || r.left() > w) continue;

            // Clip palette by media category
            QColor topCol("#1d4ed8"), botCol("#1e3a8a"), headerCol("#2563eb"), borderCol("#3b82f6"), waveCol("#93c5fd");
            if (clip.media.category == MediaCategory::Image) {
                topCol = QColor("#6d28d9");
                botCol = QColor("#4c1d95");
                headerCol = QColor("#7c3aed");
                borderCol = QColor("#8b5cf6");
                waveCol = QColor("#d8b4fe");
            } else if (clip.media.category == MediaCategory::Audio || clip.trackType == MediaCategory::Audio) {
                topCol = QColor("#047857");
                botCol = QColor("#064e3b");
                headerCol = QColor("#059669");
                borderCol = QColor("#10b981");
                waveCol = QColor("#6ee7b7");
            }

            const bool trackMuted = isTrackEffectivelyMuted(clip.trackIndex);
            const bool clipMuted = clip.muted || trackMuted;
            if (clipMuted) {
                topCol = topCol.darker(155);
                botCol = botCol.darker(155);
                headerCol = headerCol.darker(155);
                waveCol = waveCol.darker(165);
            }

            const bool selected = (clip.id == m_selectedClipId);

            p.save();
            QPainterPath clipPath;
            clipPath.addRoundedRect(r, 5, 5);
            p.setClipPath(clipPath);

            QLinearGradient grad(r.topLeft(), r.bottomLeft());
            grad.setColorAt(0.0, topCol);
            grad.setColorAt(1.0, botCol);
            p.fillRect(r, grad);

            // Top title strip
            QRectF titleStrip(r.left(), r.top(), r.width(), 20);
            p.fillRect(titleStrip, headerCol);

            // Waveform rendering
            if (!clip.waveformPeaks.isEmpty() && r.width() > 14.0) {
                const bool isAudioOnly = (clip.media.category == MediaCategory::Audio || clip.trackType == MediaCategory::Audio);
                const QRectF waveRect = isAudioOnly
                    ? QRectF(r.left() + 3, r.top() + 23, r.width() - 6, r.height() - 26)
                    : QRectF(r.left() + 3, r.bottom() - 23, r.width() - 6, 20);

                if (waveRect.height() > 4 && waveRect.width() > 4) {
                    double startRatio = 0.0;
                    double endRatio = 1.0;
                    if (clip.media.durationSec > 0.001) {
                        startRatio = qBound(0.0, clip.inPointSec / clip.media.durationSec, 1.0);
                        endRatio = qBound(startRatio, clip.outPointSec / clip.media.durationSec, 1.0);
                    }

                    const int peakCount = clip.waveformPeaks.size();
                    const double stepPx = 3.0;
                    const int barCount = qMax(1, static_cast<int>(waveRect.width() / stepPx));
                    const double midY = waveRect.center().y();
                    const double halfH = waveRect.height() * 0.48;

                    p.setPen(Qt::NoPen);
                    p.setBrush(QColor(waveCol.red(), waveCol.green(), waveCol.blue(), 185));

                    for (int b = 0; b < barCount; ++b) {
                        const double u = (barCount == 1) ? 0.5 : static_cast<double>(b) / (barCount - 1);
                        const double srcRatio = startRatio + u * (endRatio - startRatio);
                        const int idx = qBound(0, static_cast<int>(srcRatio * (peakCount - 1)), peakCount - 1);
                        const double amp = qBound(0.06, static_cast<double>(clip.waveformPeaks[idx]) * qBound(0.2, clip.volume, 1.5), 1.0);
                        const double barH = amp * halfH * 2.0;
                        const double bx = waveRect.left() + b * stepPx;
                        p.drawRect(QRectF(bx, midY - barH * 0.5, 2.0, barH));
                    }
                }
            }

            // Fade-in / Fade-out visual ramps
            if (clip.fadeInSec > 0.01) {
                const double fadeW = qMin(r.width() * 0.45, clip.fadeInSec * m_pixelsPerSecond);
                QPolygonF poly;
                poly << r.topLeft() << QPointF(r.left() + fadeW, r.top()) << r.bottomLeft();
                p.setPen(Qt::NoPen);
                p.setBrush(QColor(15, 23, 42, 110));
                p.drawPolygon(poly);
            }
            if (clip.fadeOutSec > 0.01) {
                const double fadeW = qMin(r.width() * 0.45, clip.fadeOutSec * m_pixelsPerSecond);
                QPolygonF poly;
                poly << r.topRight() << QPointF(r.right() - fadeW, r.top()) << r.bottomRight();
                p.setPen(Qt::NoPen);
                p.setBrush(QColor(15, 23, 42, 110));
                p.drawPolygon(poly);
            }

            // Clip text
            QFont clipFont = p.font();
            clipFont.setFamily("Segoe UI");
            clipFont.setPointSize(8);
            clipFont.setBold(true);
            p.setFont(clipFont);
            p.setPen(QColor("#f8fafc"));
            const QString labelText = QString("%1 (%2s)")
                .arg(clip.media.fileName)
                .arg(clip.clipDuration(), 0, 'f', 1);
            const QRectF textBox = titleStrip.adjusted(6, 0, -6, 0);
            if (textBox.width() > 14) {
                const QString elided = QFontMetrics(clipFont).elidedText(labelText, Qt::ElideRight, qRound(textBox.width()));
                p.drawText(textBox, Qt::AlignVCenter | Qt::AlignLeft, elided);
            }

            // Active filter badge
            const auto& flt = clip.filters;
            const bool hasFx = !qFuzzyIsNull(flt.brightness) || !qFuzzyCompare(flt.contrast, 1.0)
                || !qFuzzyCompare(flt.saturation, 1.0) || flt.rotateDegrees != 0
                || flt.flipH || flt.flipV || flt.grayscale || flt.sepia;
            if (hasFx && r.width() > 48) {
                QRectF fxPill(r.left() + 6, r.bottom() - 19, 26, 14);
                p.setPen(Qt::NoPen);
                p.setBrush(QColor(250, 204, 21, 220));
                p.drawRoundedRect(fxPill, 3, 3);
                QFont fxFont = clipFont;
                fxFont.setPointSize(7);
                p.setFont(fxFont);
                p.setPen(QColor("#0f172a"));
                p.drawText(fxPill, Qt::AlignCenter, "FX");
            }

            // Selected trim edge handles
            if (selected && r.width() >= 20) {
                p.setPen(Qt::NoPen);
                p.setBrush(QColor("#facc15"));
                p.drawRect(QRectF(r.left(), r.top(), 4.0, r.height()));
                p.drawRect(QRectF(r.right() - 4.0, r.top(), 4.0, r.height()));
            }

            p.restore();

            // Outer border
            p.setPen(QPen(selected ? QColor("#facc15") : borderCol, selected ? 2.0 : 1.0));
            p.setBrush(Qt::NoBrush);
            p.drawRoundedRect(r, 5, 5);
        }
    }

    // Drag-and-drop vertical guide
    if (m_dropActive && m_dropHoverTrack >= 0) {
        const int dropX = qRound(timeToX(m_dropHoverTimeSec));
        if (dropX >= kHeaderWidth) {
            p.setPen(QPen(QColor("#38bdf8"), 2, Qt::DashLine));
            p.drawLine(dropX, kRulerHeight, dropX, h);
        }
    }

    // Playhead line + cap
    const int px = qRound(timeToX(m_playheadSec));
    if (px >= kHeaderWidth - 6 && px <= w + 6) {
        p.setPen(QPen(QColor("#ef4444"), 2));
        p.drawLine(px, kRulerHeight - 2, px, h);

        QPolygon cap;
        cap << QPoint(px - 6, 4)
            << QPoint(px + 6, 4)
            << QPoint(px + 6, 15)
            << QPoint(px, 24)
            << QPoint(px - 6, 15);
        p.setPen(QPen(QColor("#fca5a5"), 1));
        p.setBrush(QColor("#ef4444"));
        p.drawPolygon(cap);
    }
}

void TimelineWidget::updateHoverCursor(const QPoint& pos) {
    if (!m_project) {
        setCursor(Qt::ArrowCursor);
        return;
    }

    if (pos.x() < kHeaderWidth && pos.y() >= kRulerHeight) {
        const int tr = trackIndexAtY(pos.y());
        if (tr >= 0 && (muteButtonRect(tr).contains(pos) || soloButtonRect(tr).contains(pos))) {
            setCursor(Qt::PointingHandCursor);
        } else {
            setCursor(Qt::ArrowCursor);
        }
        return;
    }

    if (pos.y() < kRulerHeight && pos.x() >= kHeaderWidth) {
        setCursor(Qt::PointingHandCursor);
        return;
    }

    const int cid = hitTestClip(pos);
    if (cid >= 0) {
        const TimelineClip* c = m_project->findClip(cid);
        if (c) {
            const QRectF r = clipRectFor(*c);
            if (r.width() >= 18.0 && (pos.x() - r.left() <= kTrimHandlePx || r.right() - pos.x() <= kTrimHandlePx)) {
                setCursor(Qt::SizeHorCursor);
                return;
            }
            setCursor(Qt::OpenHandCursor);
            return;
        }
    }

    setCursor(Qt::ArrowCursor);
}

void TimelineWidget::mousePressEvent(QMouseEvent* event) {
    if (event->button() != Qt::LeftButton || !m_project) {
        QWidget::mousePressEvent(event);
        return;
    }

    const QPoint pos = event->pos();

    // Header [M] / [S] buttons
    if (pos.x() < kHeaderWidth && pos.y() >= kRulerHeight) {
        const int tr = trackIndexAtY(pos.y());
        if (tr >= 0) {
            if (muteButtonRect(tr).contains(pos)) {
                m_project->tracks[tr].muted = !m_project->tracks[tr].muted;
                emit projectModified();
                update();
                return;
            }
            if (soloButtonRect(tr).contains(pos)) {
                m_project->tracks[tr].solo = !m_project->tracks[tr].solo;
                emit projectModified();
                update();
                return;
            }
        }
        return;
    }

    // Ruler scrub
    if (pos.y() < kRulerHeight && pos.x() >= kHeaderWidth) {
        m_dragMode = DragMode::ScrubPlayhead;
        m_playheadSec = xToTime(pos.x());
        emit playheadChanged(m_playheadSec);
        update();
        return;
    }

    // Clip hit test
    if (pos.x() >= kHeaderWidth && pos.y() >= kRulerHeight) {
        const int hitId = hitTestClip(pos);
        if (hitId >= 0) {
            m_selectedClipId = hitId;
            emit clipSelected(hitId);

            TimelineClip* c = m_project->findClip(hitId);
            if (c) {
                const QRectF r = clipRectFor(*c);
                m_dragClipId = hitId;
                m_dragStartPos = pos;
                m_dragOrigStartSec = c->timelineStartSec;
                m_dragOrigInSec = c->inPointSec;
                m_dragOrigOutSec = c->outPointSec;
                m_dragOrigTrackIndex = c->trackIndex;
                m_dragDirty = false;

                if (r.width() >= 18.0 && (pos.x() - r.left() <= kTrimHandlePx)) {
                    m_dragMode = DragMode::TrimLeft;
                    setCursor(Qt::SizeHorCursor);
                } else if (r.width() >= 18.0 && (r.right() - pos.x() <= kTrimHandlePx)) {
                    m_dragMode = DragMode::TrimRight;
                    setCursor(Qt::SizeHorCursor);
                } else {
                    m_dragMode = DragMode::MoveClip;
                    setCursor(Qt::ClosedHandCursor);
                }
            }
            update();
            return;
        }

        // Empty track click: deselect & seek playhead
        if (m_selectedClipId != -1) {
            m_selectedClipId = -1;
            emit clipSelected(-1);
        }
        m_dragMode = DragMode::ScrubPlayhead;
        m_playheadSec = xToTime(pos.x());
        emit playheadChanged(m_playheadSec);
        update();
    }
}

void TimelineWidget::mouseMoveEvent(QMouseEvent* event) {
    const QPoint pos = event->pos();

    if (m_dragMode == DragMode::None) {
        updateHoverCursor(pos);
        QWidget::mouseMoveEvent(event);
        return;
    }

    if (m_dragMode == DragMode::ScrubPlayhead) {
        m_playheadSec = xToTime(pos.x());
        emit playheadChanged(m_playheadSec);
        update();
        return;
    }

    if (!m_project) return;
    TimelineClip* clip = m_project->findClip(m_dragClipId);
    if (!clip) return;

    const double deltaSec = static_cast<double>(pos.x() - m_dragStartPos.x()) / m_pixelsPerSecond;
    const bool disableSnap = event->modifiers().testFlag(Qt::ShiftModifier);

    if (m_dragMode == DragMode::MoveClip) {
        const double rawStart = qMax(0.0, m_dragOrigStartSec + deltaSec);
        const double dur = clip->clipDuration();

        double snappedStart = snapTime(rawStart, clip->id, disableSnap);
        if (qFuzzyCompare(snappedStart + 1.0, rawStart + 1.0)) {
            // Try snapping the right edge too
            const double snappedEnd = snapTime(rawStart + dur, clip->id, disableSnap);
            if (!qFuzzyCompare(snappedEnd + 1.0, rawStart + dur + 1.0)) {
                snappedStart = qMax(0.0, snappedEnd - dur);
            }
        }
        clip->timelineStartSec = snappedStart;

        const int hoverTrack = trackIndexAtY(pos.y());
        if (hoverTrack >= 0 && hoverTrack < m_project->tracks.size()) {
            const MediaCategory desiredTrackType = (clip->media.category == MediaCategory::Audio)
                ? MediaCategory::Audio
                : MediaCategory::Video;
            if (m_project->tracks[hoverTrack].type == desiredTrackType) {
                clip->trackIndex = hoverTrack;
            }
        }

        m_dragDirty = true;
        update();
    } else if (m_dragMode == DragMode::TrimLeft) {
        const double origDur = qMax(0.1, m_dragOrigOutSec - m_dragOrigInSec);
        const double maxTrimDelta = origDur - 0.2;
        double minTrimDelta = -m_dragOrigStartSec;
        if (clip->media.category != MediaCategory::Image) {
            minTrimDelta = qMax(minTrimDelta, -m_dragOrigInSec);
        }

        const double clampedDelta = qBound(minTrimDelta, deltaSec, maxTrimDelta);
        const double candidateStart = m_dragOrigStartSec + clampedDelta;
        const double snappedStart = snapTime(candidateStart, clip->id, disableSnap);
        const double snappedDelta = qBound(minTrimDelta, snappedStart - m_dragOrigStartSec, maxTrimDelta);

        clip->timelineStartSec = m_dragOrigStartSec + snappedDelta;
        if (clip->media.category == MediaCategory::Image) {
            clip->inPointSec = 0.0;
            clip->outPointSec = qMax(0.2, m_dragOrigOutSec - snappedDelta);
        } else {
            clip->inPointSec = m_dragOrigInSec + snappedDelta;
        }

        m_dragDirty = true;
        update();
    } else if (m_dragMode == DragMode::TrimRight) {
        const double minOut = m_dragOrigInSec + 0.2;
        double maxOut = 36000.0;
        if (clip->media.category != MediaCategory::Image && clip->media.durationSec > 0.0) {
            maxOut = qMax(minOut, clip->media.durationSec);
        }

        const double rawOut = qBound(minOut, m_dragOrigOutSec + deltaSec, maxOut);
        const double rawEndTime = clip->timelineStartSec + (rawOut - clip->inPointSec);
        const double snappedEndTime = snapTime(rawEndTime, clip->id, disableSnap);
        const double snappedOut = qBound(minOut, clip->inPointSec + (snappedEndTime - clip->timelineStartSec), maxOut);

        clip->outPointSec = snappedOut;
        m_dragDirty = true;
        update();
    }
}

void TimelineWidget::mouseReleaseEvent(QMouseEvent* event) {
    if (event->button() == Qt::LeftButton && m_dragMode != DragMode::None) {
        const bool modified = m_dragDirty;
        m_dragMode = DragMode::None;
        m_dragClipId = -1;
        m_dragDirty = false;
        updateHoverCursor(event->pos());
        if (modified) {
            refreshLayout();
            emit projectModified();
        }
    }
    QWidget::mouseReleaseEvent(event);
}

void TimelineWidget::dragEnterEvent(QDragEnterEvent* event) {
    if (event->mimeData()->hasUrls()) {
        m_dropActive = true;
        event->acceptProposedAction();
    }
}

void TimelineWidget::dragMoveEvent(QDragMoveEvent* event) {
    if (!event->mimeData()->hasUrls()) return;
    const QPoint pos = event->position().toPoint();
    m_dropActive = true;
    m_dropHoverTimeSec = xToTime(pos.x());
    int tr = trackIndexAtY(pos.y());
    if (tr < 0 && m_project && !m_project->tracks.isEmpty()) {
        tr = 0;
    }
    m_dropHoverTrack = tr;
    event->acceptProposedAction();
    update();
}

void TimelineWidget::dragLeaveEvent(QDragLeaveEvent* event) {
    Q_UNUSED(event);
    m_dropActive = false;
    m_dropHoverTrack = -1;
    update();
}

void TimelineWidget::dropEvent(QDropEvent* event) {
    m_dropActive = false;
    const int savedTrack = m_dropHoverTrack;
    m_dropHoverTrack = -1;
    update();

    if (!event->mimeData()->hasUrls()) return;

    QStringList paths;
    for (const QUrl& url : event->mimeData()->urls()) {
        const QString local = url.toLocalFile();
        if (local.isEmpty()) continue;
        QFileInfo fi(local);
        if (fi.isDir()) {
            QDirIterator it(local, QDir::Files, QDirIterator::Subdirectories);
            while (it.hasNext()) {
                paths << it.next();
            }
        } else if (fi.isFile()) {
            paths << local;
        }
    }

    if (!paths.isEmpty()) {
        const QPoint pos = event->position().toPoint();
        int tr = trackIndexAtY(pos.y());
        if (tr < 0) tr = qMax(0, savedTrack);
        const double dropSec = xToTime(pos.x());
        emit filesDroppedOnTrack(paths, tr, dropSec);
        event->acceptProposedAction();
    }
}
