#pragma once

#include <QImage>
#include <QString>
#include <QWidget>

class QLabel;
class QPushButton;
class QSlider;
class PreviewCanvas;

class PreviewWidget : public QWidget {
    Q_OBJECT
public:
    explicit PreviewWidget(QWidget* parent = nullptr);

    void setFrame(const QImage& frame);
    void setTimecode(double currentSec, double totalSec);
    void setPlaying(bool playing);
    void setOverlayTitle(const QString& title);
    bool isPlaying() const { return m_playing; }

signals:
    void playPauseToggled(bool playing);
    void seekRequested(double sec);
    void stepRequested(double deltaSec);
    void markInRequested();
    void markOutRequested();

private:
    void setupUi();
    void updateTimecodeLabel();

    PreviewCanvas* m_canvas = nullptr;
    QSlider* m_scrubSlider = nullptr;
    QLabel* m_lblTimecode = nullptr;

    QPushButton* m_btnRestart = nullptr;
    QPushButton* m_btnStepBack = nullptr;
    QPushButton* m_btnPlayPause = nullptr;
    QPushButton* m_btnStepFwd = nullptr;
    QPushButton* m_btnMarkIn = nullptr;
    QPushButton* m_btnMarkOut = nullptr;

    double m_currentSec = 0.0;
    double m_totalSec = 0.0;
    bool m_playing = false;
    bool m_scrubbing = false;
};
