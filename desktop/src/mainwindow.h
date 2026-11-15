#pragma once

#include "converterworker.h"
#include "formats.h"
#include "mediaprobe.h"
#include "previewprovider.h"
#include "projectmodel.h"
#include "timelineexporter.h"

#include <QCheckBox>
#include <QComboBox>
#include <QDoubleSpinBox>
#include <QElapsedTimer>
#include <QLabel>
#include <QLineEdit>
#include <QList>
#include <QMainWindow>
#include <QPlainTextEdit>
#include <QProgressBar>
#include <QPushButton>
#include <QScrollArea>
#include <QSlider>
#include <QSpinBox>
#include <QSplitter>
#include <QTabWidget>
#include <QTableWidget>
#include <QTimer>

class PreviewWidget;
class TimelineWidget;

class MainWindow : public QMainWindow {
    Q_OBJECT
public:
    explicit MainWindow(QWidget* parent = nullptr);

protected:
    void dragEnterEvent(QDragEnterEvent* event) override;
    void dropEvent(QDropEvent* event) override;
    void keyPressEvent(QKeyEvent* event) override;

private slots:
    // Media pool & batch converter
    void onAddFiles();
    void onAddFolder();
    void onClearList();
    void onBrowseOutputDir();
    void onApplyBatchFormat();
    void onAddSelectedToTimeline();
    void onMediaTableSelectionChanged();
    void onMediaTableDoubleClicked(int row, int column);
    void onResolutionPresetChanged(int index);
    void onStartConversion();
    void onCancelConversion();

    // Batch worker slots
    void onTaskStarted(int row);
    void onTaskProgress(int row, int percent);
    void onTaskFinished(int row, bool success, const QString& message, qint64 outputSizeBytes);
    void onAllFinished(int successCount, int failCount);

    // Timeline & preview slots
    void onPlayheadChanged(double sec);
    void onClipSelected(int clipId);
    void onProjectModified();
    void onFilesDroppedOnTrack(const QStringList& paths, int trackIndex, double dropTimeSec);

    void onPlayPauseToggled(bool playing);
    void onSeekRequested(double sec);
    void onStepRequested(double deltaSec);
    void onMarkInRequested();
    void onMarkOutRequested();
    void onPlaybackTick();

    void onPreviewFrameReady(const QString& filePath, double timestampSec, const QImage& frame);
    void onWaveformReady(int clipId, const QVector<float>& peaks);

    // Timeline toolbar & export
    void onAddVideoTrack();
    void onAddAudioTrack();
    void onSplitClip();
    void onDeleteSelectedClip();
    void onZoomChanged(int value);
    void onStartTimelineExport();
    void onCancelTimelineExport();
    void onExportProgress(int percent, const QString& statusText);
    void onExportFinished(bool success, const QString& outputPath, const QString& message);

    // Inspector & effects
    void onEffectsChanged();
    void onResetEffects();
    void onAudioStreamComboChanged(int index);

    // Bottom bar & logs
    void onOpenOutputFolder();
    void onToggleLog(bool checked);
    void onAppendFfmpegLog(const QString& cmdLine);

private:
    void setupUi();
    QWidget* createMediaPoolPane();
    QWidget* createInspectorPane();
    QWidget* createTimelinePane();
    void applyDarkTheme();

    QList<MediaInfo> probeAndRegisterFiles(const QStringList& paths, bool addToPoolTable);
    void addFilePaths(const QStringList& paths);
    int appendMediaToPool(const MediaInfo& info);
    QString buildOutputPath(const MediaInfo& info, const QString& targetExt) const;
    ConversionOptions currentOptions() const;
    FilterAdjustments currentFiltersFromUi() const;
    void loadClipIntoInspector(const TimelineClip* clip);
    void populateAudioStreamsCombo(const QList<AudioStreamInfo>& streams, int selectedIndex);
    void setUiBusy(bool batchBusy, bool exportBusy);

    void requestPreviewAtPlayhead();
    void requestPreviewForPoolRow(int row, double timestampSec = -1.0);
    const TimelineClip* activeVideoClipAt(double sec) const;
    const TimelineClip* activeAudioClipAt(double sec) const;
    bool isTrackEffectivelyMuted(int trackIndex) const;

    // Core data & workers
    StudioProject m_project;
    QList<MediaInfo> m_items;
    QStringList m_lastOutputDirs;
    QList<int> m_batchRowProgress;
    int m_batchTotalTasks = 0;

    ConverterWorker* m_worker = nullptr;
    TimelineExporter* m_exporter = nullptr;
    PreviewProvider* m_previewProvider = nullptr;

    // Playback & preview sync
    QTimer* m_playTimer = nullptr;
    QElapsedTimer m_playElapsed;
    QElapsedTimer m_previewRequestTimer;
    bool m_previewInFlight = false;
    bool m_previewPoolMode = false;
    int m_previewPoolRow = -1;
    double m_previewPoolTimeSec = 0.0;
    QString m_expectedPreviewPath;
    bool m_updatingInspector = false;

    // Splitters
    QSplitter* m_mainSplitter = nullptr;
    QSplitter* m_topSplitter = nullptr;

    // Left: Media Pool & Batch Queue
    QPushButton* m_btnAddFiles = nullptr;
    QPushButton* m_btnAddFolder = nullptr;
    QPushButton* m_btnClear = nullptr;
    QCheckBox* m_chkSameAsSource = nullptr;
    QLineEdit* m_editOutputDir = nullptr;
    QPushButton* m_btnBrowseOut = nullptr;
    QComboBox* m_cmbBatchCategory = nullptr;
    QComboBox* m_cmbBatchFormat = nullptr;
    QPushButton* m_btnApplyBatch = nullptr;
    QTableWidget* m_table = nullptr;
    QPushButton* m_btnAddToTimeline = nullptr;
    QPushButton* m_btnConvert = nullptr;
    QPushButton* m_btnCancelConvert = nullptr;

    // Center: Preview
    PreviewWidget* m_previewWidget = nullptr;

    // Right: Inspector Tabs
    QTabWidget* m_inspectorTabs = nullptr;

    // Tab 1: Conversion & Codec
    QSlider* m_sliderQuality = nullptr;
    QLabel* m_lblQualityVal = nullptr;
    QComboBox* m_cmbResPreset = nullptr;
    QSpinBox* m_spinWidth = nullptr;
    QSpinBox* m_spinHeight = nullptr;
    QComboBox* m_cmbVideoCodec = nullptr;
    QComboBox* m_cmbFps = nullptr;
    QComboBox* m_cmbAudioBitrate = nullptr;
    QComboBox* m_cmbSampleRate = nullptr;
    QComboBox* m_cmbAudioStream = nullptr;
    QDoubleSpinBox* m_spinTrimStart = nullptr;
    QDoubleSpinBox* m_spinTrimEnd = nullptr;
    QCheckBox* m_chkNoAudio = nullptr;
    QCheckBox* m_chkStripMeta = nullptr;

    // Tab 2: Effects & Audio Mixer
    QLabel* m_lblEffectsTarget = nullptr;
    QPushButton* m_btnResetEffects = nullptr;
    QSlider* m_sliderBrightness = nullptr;
    QLabel* m_lblBrightnessVal = nullptr;
    QSlider* m_sliderContrast = nullptr;
    QLabel* m_lblContrastVal = nullptr;
    QSlider* m_sliderSaturation = nullptr;
    QLabel* m_lblSaturationVal = nullptr;
    QComboBox* m_cmbRotate = nullptr;
    QCheckBox* m_chkFlipH = nullptr;
    QCheckBox* m_chkFlipV = nullptr;
    QCheckBox* m_chkGrayscale = nullptr;
    QCheckBox* m_chkSepia = nullptr;
    QSlider* m_sliderVolume = nullptr;
    QLabel* m_lblVolumeVal = nullptr;
    QDoubleSpinBox* m_spinFadeIn = nullptr;
    QDoubleSpinBox* m_spinFadeOut = nullptr;

    // Bottom: Timeline panel
    QPushButton* m_btnAddVideoTrack = nullptr;
    QPushButton* m_btnAddAudioTrack = nullptr;
    QPushButton* m_btnSplitClip = nullptr;
    QPushButton* m_btnDeleteClip = nullptr;
    QSlider* m_sliderZoom = nullptr;
    QLabel* m_lblZoomVal = nullptr;
    QComboBox* m_cmbTimelineFormat = nullptr;
    QPushButton* m_btnExportTimeline = nullptr;
    QPushButton* m_btnCancelExport = nullptr;
    QScrollArea* m_timelineScroll = nullptr;
    TimelineWidget* m_timelineWidget = nullptr;

    // Status & Log drawer
    QLabel* m_lblStatus = nullptr;
    QProgressBar* m_globalProgress = nullptr;
    QPushButton* m_btnToggleLog = nullptr;
    QPushButton* m_btnOpenOutput = nullptr;
    QWidget* m_logContainer = nullptr;
    QPlainTextEdit* m_txtLog = nullptr;
    QPushButton* m_btnClearLog = nullptr;
};
