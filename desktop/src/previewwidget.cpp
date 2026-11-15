#include "previewwidget.h"
#include "mediaprobe.h"

#include <QFont>
#include <QHBoxLayout>
#include <QLabel>
#include <QPainter>
#include <QPaintEvent>
#include <QPushButton>
#include <QSignalBlocker>
#include <QSlider>
#include <QStyle>
#include <QVBoxLayout>
#include <QtGlobal>

class PreviewCanvas : public QWidget {
public:
    explicit PreviewCanvas(QWidget* parent = nullptr)
        : QWidget(parent) {
        setMinimumSize(320, 200);
        setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Expanding);
    }

    void setFrame(const QImage& frame) {
        m_frame = frame;
        update();
    }

    void setOverlayTitle(const QString& title) {
        m_overlayTitle = title;
        update();
    }

protected:
    void paintEvent(QPaintEvent* /*event*/) override {
        QPainter p(this);
        p.setRenderHint(QPainter::Antialiasing, true);

        const QRect fullRect = rect();
        p.fillRect(fullRect, QColor("#05070c"));

        // Subtle monitor frame border
        p.setPen(QPen(QColor("#1e293b"), 1));
        p.drawRect(fullRect.adjusted(0, 0, -1, -1));

        const QRect contentRect = fullRect.adjusted(8, 8, -8, -8);

        if (m_frame.isNull()) {
            // Subtle studio safe-area corner markers
            p.setPen(QPen(QColor("#1e293b"), 1));
            const int m = 24;
            const int len = 18;
            const QRect safe = contentRect.adjusted(m, m, -m, -m);
            if (safe.width() > 60 && safe.height() > 60) {
                p.drawLine(safe.topLeft(), safe.topLeft() + QPoint(len, 0));
                p.drawLine(safe.topLeft(), safe.topLeft() + QPoint(0, len));
                p.drawLine(safe.topRight(), safe.topRight() + QPoint(-len, 0));
                p.drawLine(safe.topRight(), safe.topRight() + QPoint(0, len));
                p.drawLine(safe.bottomLeft(), safe.bottomLeft() + QPoint(len, 0));
                p.drawLine(safe.bottomLeft(), safe.bottomLeft() + QPoint(0, -len));
                p.drawLine(safe.bottomRight(), safe.bottomRight() + QPoint(-len, 0));
                p.drawLine(safe.bottomRight(), safe.bottomRight() + QPoint(0, -len));
            }

            QFont titleFont = p.font();
            titleFont.setPointSize(11);
            titleFont.setBold(true);
            p.setFont(titleFont);
            p.setPen(QColor("#64748b"));
            p.drawText(contentRect.adjusted(0, -12, 0, -12), Qt::AlignCenter, "STÜDYO ÖNİZLEME MONİTÖRÜ");

            QFont subFont = p.font();
            subFont.setPointSize(9);
            subFont.setBold(false);
            p.setFont(subFont);
            p.setPen(QColor("#475569"));
            p.drawText(contentRect.adjusted(16, 16, -16, 16), Qt::AlignCenter | Qt::TextWordWrap,
                       "Medya havuzundan bir dosya seçin veya zaman çizelgesine klip ekleyin");
        } else {
            QSize scaled = m_frame.size().scaled(contentRect.size(), Qt::KeepAspectRatio);
            QRect targetRect(
                contentRect.left() + (contentRect.width() - scaled.width()) / 2,
                contentRect.top() + (contentRect.height() - scaled.height()) / 2,
                scaled.width(),
                scaled.height()
            );

            if (m_frame.hasAlphaChannel()) {
                // Dark checkerboard background for transparent images
                const int tile = 10;
                p.save();
                p.setClipRect(targetRect);
                for (int y = targetRect.top(); y <= targetRect.bottom(); y += tile) {
                    for (int x = targetRect.left(); x <= targetRect.right(); x += tile) {
                        bool dark = (((x - targetRect.left()) / tile) + ((y - targetRect.top()) / tile)) % 2 == 0;
                        p.fillRect(x, y, tile, tile, dark ? QColor("#181e29") : QColor("#232b3b"));
                    }
                }
                p.restore();
            }

            p.setRenderHint(QPainter::SmoothPixmapTransform, true);
            p.drawImage(targetRect, m_frame);

            p.setPen(QPen(QColor("#334155"), 1));
            p.drawRect(targetRect);
        }

        if (!m_overlayTitle.isEmpty()) {
            QFont badgeFont = p.font();
            badgeFont.setPointSize(8);
            badgeFont.setBold(true);
            p.setFont(badgeFont);
            QFontMetrics fm(badgeFont);
            const QString clipped = fm.elidedText(m_overlayTitle, Qt::ElideMiddle, qMax(120, contentRect.width() - 24));
            const QRect textBounds = fm.boundingRect(clipped);
            QRect pill(contentRect.left() + 6, contentRect.top() + 6,
                       textBounds.width() + 16, textBounds.height() + 8);
            p.setPen(Qt::NoPen);
            p.setBrush(QColor(15, 23, 42, 195));
            p.drawRoundedRect(pill, 4, 4);
            p.setPen(QColor("#cbd5e1"));
            p.drawText(pill, Qt::AlignCenter, clipped);
        }
    }

private:
    QImage m_frame;
    QString m_overlayTitle;
};

PreviewWidget::PreviewWidget(QWidget* parent)
    : QWidget(parent) {
    setupUi();
}

void PreviewWidget::setupUi() {
    auto* root = new QVBoxLayout(this);
    root->setContentsMargins(0, 0, 0, 0);
    root->setSpacing(6);

    m_canvas = new PreviewCanvas(this);
    root->addWidget(m_canvas, 1);

    // Scrub slider
    m_scrubSlider = new QSlider(Qt::Horizontal, this);
    m_scrubSlider->setObjectName("previewScrubber");
    m_scrubSlider->setRange(0, 10000);
    m_scrubSlider->setValue(0);
    m_scrubSlider->setToolTip("Zaman çizelgesinde gezinmek için sürükleyin");
    root->addWidget(m_scrubSlider);

    connect(m_scrubSlider, &QSlider::sliderPressed, this, [this]() {
        m_scrubbing = true;
    });
    connect(m_scrubSlider, &QSlider::sliderReleased, this, [this]() {
        m_scrubbing = false;
        if (m_totalSec > 0.0) {
            const double sec = (static_cast<double>(m_scrubSlider->value()) / 10000.0) * m_totalSec;
            emit seekRequested(sec);
        }
    });
    connect(m_scrubSlider, &QSlider::sliderMoved, this, [this](int val) {
        if (m_totalSec > 0.0) {
            const double sec = (static_cast<double>(val) / 10000.0) * m_totalSec;
            emit seekRequested(sec);
        }
    });

    // Transport & Timecode bar
    auto* transportRow = new QHBoxLayout();
    transportRow->setContentsMargins(2, 0, 2, 0);
    transportRow->setSpacing(6);

    m_lblTimecode = new QLabel("00:00:00.00 / 00:00:00.00", this);
    m_lblTimecode->setObjectName("timecodeLabel");
    QFont monoFont("Consolas", 10);
    monoFont.setStyleHint(QFont::Monospace);
    monoFont.setBold(true);
    m_lblTimecode->setFont(monoFont);
    m_lblTimecode->setMinimumWidth(185);
    transportRow->addWidget(m_lblTimecode);

    transportRow->addStretch();

    m_btnRestart = new QPushButton("Başa Sar", this);
    m_btnRestart->setToolTip("Zaman imlecini başa sar (00:00:00.00)");

    m_btnStepBack = new QPushButton("-1sn", this);
    m_btnStepBack->setToolTip("1 saniye geri git");

    m_btnPlayPause = new QPushButton("Oynat", this);
    m_btnPlayPause->setObjectName("btnPlayPause");
    m_btnPlayPause->setMinimumWidth(86);
    m_btnPlayPause->setToolTip("Önizlemeyi oynat / duraklat");

    m_btnStepFwd = new QPushButton("+1sn", this);
    m_btnStepFwd->setToolTip("1 saniye ileri git");

    m_btnMarkIn = new QPushButton("Giriş [I]", this);
    m_btnMarkIn->setToolTip("Giriş (In) noktasını mevcut konuma ayarla");

    m_btnMarkOut = new QPushButton("Çıkış [O]", this);
    m_btnMarkOut->setToolTip("Çıkış (Out) noktasını mevcut konuma ayarla");

    transportRow->addWidget(m_btnRestart);
    transportRow->addWidget(m_btnStepBack);
    transportRow->addWidget(m_btnPlayPause);
    transportRow->addWidget(m_btnStepFwd);
    transportRow->addSpacing(8);
    transportRow->addWidget(m_btnMarkIn);
    transportRow->addWidget(m_btnMarkOut);

    root->addLayout(transportRow);

    connect(m_btnRestart, &QPushButton::clicked, this, [this]() {
        emit seekRequested(0.0);
    });
    connect(m_btnStepBack, &QPushButton::clicked, this, [this]() {
        emit stepRequested(-1.0);
    });
    connect(m_btnPlayPause, &QPushButton::clicked, this, [this]() {
        const bool nextState = !m_playing;
        setPlaying(nextState);
        emit playPauseToggled(nextState);
    });
    connect(m_btnStepFwd, &QPushButton::clicked, this, [this]() {
        emit stepRequested(1.0);
    });
    connect(m_btnMarkIn, &QPushButton::clicked, this, &PreviewWidget::markInRequested);
    connect(m_btnMarkOut, &QPushButton::clicked, this, &PreviewWidget::markOutRequested);
}

void PreviewWidget::setFrame(const QImage& frame) {
    m_canvas->setFrame(frame);
}

void PreviewWidget::setOverlayTitle(const QString& title) {
    m_canvas->setOverlayTitle(title);
}

void PreviewWidget::setTimecode(double currentSec, double totalSec) {
    m_currentSec = qMax(0.0, currentSec);
    m_totalSec = qMax(0.0, totalSec);
    updateTimecodeLabel();

    if (!m_scrubbing) {
        QSignalBlocker blocker(m_scrubSlider);
        if (m_totalSec > 0.0001) {
            const double ratio = qBound(0.0, m_currentSec / m_totalSec, 1.0);
            m_scrubSlider->setValue(qRound(ratio * 10000.0));
            m_scrubSlider->setEnabled(true);
        } else {
            m_scrubSlider->setValue(0);
            m_scrubSlider->setEnabled(false);
        }
    }
}

void PreviewWidget::setPlaying(bool playing) {
    m_playing = playing;
    m_btnPlayPause->setText(playing ? "Duraklat" : "Oynat");
    m_btnPlayPause->setProperty("playing", playing);
    m_btnPlayPause->style()->unpolish(m_btnPlayPause);
    m_btnPlayPause->style()->polish(m_btnPlayPause);
}

void PreviewWidget::updateTimecodeLabel() {
    const QString curStr = MediaInfo::formatTimecode(m_currentSec);
    const QString totStr = MediaInfo::formatTimecode(m_totalSec);
    m_lblTimecode->setText(QString("%1 / %2").arg(curStr, totStr));
}
