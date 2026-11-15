#include "mainwindow.h"
#include "previewwidget.h"
#include "timelinewidget.h"

#include <QAbstractItemView>
#include <QAbstractSpinBox>
#include <QDateTime>
#include <QDesktopServices>
#include <QDir>
#include <QDirIterator>
#include <QDragEnterEvent>
#include <QDropEvent>
#include <QFileDialog>
#include <QFileInfo>
#include <QFormLayout>
#include <QGroupBox>
#include <QHBoxLayout>
#include <QHeaderView>
#include <QKeyEvent>
#include <QMessageBox>
#include <QMimeData>
#include <QSet>
#include <QSignalBlocker>
#include <QStandardPaths>
#include <QUrl>
#include <QVBoxLayout>
#include <QtGlobal>

#include <algorithm>

MainWindow::MainWindow(QWidget* parent)
    : QMainWindow(parent)
    , m_worker(new ConverterWorker(this))
    , m_exporter(new TimelineExporter(this))
    , m_previewProvider(new PreviewProvider(this))
    , m_playTimer(new QTimer(this)) {
    setWindowTitle("Avenlyx Studio — Çok Kanallı Medya Stüdyosu & Dönüştürücü");
    resize(1460, 900);
    setMinimumSize(1120, 700);
    setAcceptDrops(true);

    setupUi();
    applyDarkTheme();

    m_timelineWidget->setProject(&m_project);
    m_previewWidget->setTimecode(0.0, 0.0);

    // Batch converter connections
    connect(m_worker, &ConverterWorker::taskStarted, this, &MainWindow::onTaskStarted);
    connect(m_worker, &ConverterWorker::taskProgress, this, &MainWindow::onTaskProgress);
    connect(m_worker, &ConverterWorker::taskFinished, this, &MainWindow::onTaskFinished);
    connect(m_worker, &ConverterWorker::allFinished, this, &MainWindow::onAllFinished);
    connect(m_worker, &ConverterWorker::ffmpegCommandLogged, this, &MainWindow::onAppendFfmpegLog);

    // Timeline exporter connections
    connect(m_exporter, &TimelineExporter::exportProgress, this, &MainWindow::onExportProgress);
    connect(m_exporter, &TimelineExporter::exportFinished, this, &MainWindow::onExportFinished);
    connect(m_exporter, &TimelineExporter::ffmpegCommandLogged, this, &MainWindow::onAppendFfmpegLog);

    // Preview provider connections
    connect(m_previewProvider, &PreviewProvider::frameReady, this, &MainWindow::onPreviewFrameReady);
    connect(m_previewProvider, &PreviewProvider::waveformReady, this, &MainWindow::onWaveformReady);

    // Playback timer
    m_playTimer->setInterval(66);
    connect(m_playTimer, &QTimer::timeout, this, &MainWindow::onPlaybackTick);
}

void MainWindow::setupUi() {
    auto* central = new QWidget(this);
    setCentralWidget(central);
    auto* rootLayout = new QVBoxLayout(central);
    rootLayout->setContentsMargins(10, 8, 10, 8);
    rootLayout->setSpacing(6);

    // Top Studio Header
    auto* headerLayout = new QHBoxLayout();
    headerLayout->setContentsMargins(2, 0, 2, 2);
    auto* titleLbl = new QLabel("Avenlyx Studio", this);
    titleLbl->setObjectName("appTitle");
    auto* badgeLbl = new QLabel("v2.0 NLE", this);
    badgeLbl->setObjectName("versionBadge");
    auto* subLbl = new QLabel("Çok Kanallı Video/Ses Editörü & Evrensel Toplu Dönüştürücü", this);
    subLbl->setObjectName("appSubtitle");
    headerLayout->addWidget(titleLbl);
    headerLayout->addSpacing(8);
    headerLayout->addWidget(badgeLbl);
    headerLayout->addStretch();
    headerLayout->addWidget(subLbl);
    rootLayout->addLayout(headerLayout);

    // Main Vertical Splitter (Top 3-pane studio / Bottom timeline)
    m_mainSplitter = new QSplitter(Qt::Vertical, this);
    m_mainSplitter->setChildrenCollapsible(false);

    // Top Horizontal Splitter
    m_topSplitter = new QSplitter(Qt::Horizontal, m_mainSplitter);
    m_topSplitter->setChildrenCollapsible(false);

    QWidget* leftPane = createMediaPoolPane();

    auto* previewPane = new QGroupBox("Stüdyo Önizleme Monitörü", m_topSplitter);
    auto* previewLayout = new QVBoxLayout(previewPane);
    previewLayout->setContentsMargins(8, 12, 8, 8);
    m_previewWidget = new PreviewWidget(previewPane);
    previewLayout->addWidget(m_previewWidget);

    QWidget* rightPane = createInspectorPane();

    m_topSplitter->addWidget(leftPane);
    m_topSplitter->addWidget(previewPane);
    m_topSplitter->addWidget(rightPane);
    m_topSplitter->setSizes({460, 520, 390});

    QWidget* timelinePane = createTimelinePane();

    m_mainSplitter->addWidget(m_topSplitter);
    m_mainSplitter->addWidget(timelinePane);
    m_mainSplitter->setSizes({510, 310});

    rootLayout->addWidget(m_mainSplitter, 1);

    // Bottom status bar + global progress + log toggle + open output folder
    auto* bottomBar = new QHBoxLayout();
    bottomBar->setSpacing(8);

    m_lblStatus = new QLabel("Hazır. Dosyaları sürükleyip bırakın veya Medya Havuzuna ekleyin.", this);
    m_lblStatus->setObjectName("statusLabel");

    m_globalProgress = new QProgressBar(this);
    m_globalProgress->setRange(0, 100);
    m_globalProgress->setValue(0);
    m_globalProgress->setFixedWidth(180);

    m_btnToggleLog = new QPushButton("FFmpeg Günlüğü", this);
    m_btnToggleLog->setCheckable(true);
    m_btnToggleLog->setToolTip("FFmpeg komut günlüğünü göster/gizle");

    m_btnOpenOutput = new QPushButton("Çıktı Klasörünü Aç", this);

    bottomBar->addWidget(m_lblStatus, 1);
    bottomBar->addWidget(m_globalProgress);
    bottomBar->addWidget(m_btnToggleLog);
    bottomBar->addWidget(m_btnOpenOutput);
    rootLayout->addLayout(bottomBar);

    // Collapsible FFmpeg Log Drawer
    m_logContainer = new QWidget(this);
    m_logContainer->setVisible(false);
    auto* logLayout = new QVBoxLayout(m_logContainer);
    logLayout->setContentsMargins(0, 2, 0, 0);
    logLayout->setSpacing(4);

    auto* logHeader = new QHBoxLayout();
    auto* logLbl = new QLabel("FFmpeg Komut Günlüğü:", m_logContainer);
    logLbl->setStyleSheet("color: #93c5fd; font-weight: 600; font-size: 11px;");
    m_btnClearLog = new QPushButton("Günlüğü Temizle", m_logContainer);
    m_btnClearLog->setMaximumHeight(24);
    logHeader->addWidget(logLbl);
    logHeader->addStretch();
    logHeader->addWidget(m_btnClearLog);
    logLayout->addLayout(logHeader);

    m_txtLog = new QPlainTextEdit(m_logContainer);
    m_txtLog->setReadOnly(true);
    m_txtLog->setMaximumBlockCount(400);
    m_txtLog->setFixedHeight(110);
    QFont logFont("Consolas", 9);
    logFont.setStyleHint(QFont::Monospace);
    m_txtLog->setFont(logFont);
    logLayout->addWidget(m_txtLog);

    rootLayout->addWidget(m_logContainer);

    // PreviewWidget signals
    connect(m_previewWidget, &PreviewWidget::playPauseToggled, this, &MainWindow::onPlayPauseToggled);
    connect(m_previewWidget, &PreviewWidget::seekRequested, this, &MainWindow::onSeekRequested);
    connect(m_previewWidget, &PreviewWidget::stepRequested, this, &MainWindow::onStepRequested);
    connect(m_previewWidget, &PreviewWidget::markInRequested, this, &MainWindow::onMarkInRequested);
    connect(m_previewWidget, &PreviewWidget::markOutRequested, this, &MainWindow::onMarkOutRequested);

    // TimelineWidget signals
    connect(m_timelineWidget, &TimelineWidget::playheadChanged, this, &MainWindow::onPlayheadChanged);
    connect(m_timelineWidget, &TimelineWidget::clipSelected, this, &MainWindow::onClipSelected);
    connect(m_timelineWidget, &TimelineWidget::projectModified, this, &MainWindow::onProjectModified);
    connect(m_timelineWidget, &TimelineWidget::filesDroppedOnTrack, this, &MainWindow::onFilesDroppedOnTrack);

    // Bottom signals
    connect(m_btnOpenOutput, &QPushButton::clicked, this, &MainWindow::onOpenOutputFolder);
    connect(m_btnToggleLog, &QPushButton::toggled, this, &MainWindow::onToggleLog);
    connect(m_btnClearLog, &QPushButton::clicked, m_txtLog, &QPlainTextEdit::clear);
}

QWidget* MainWindow::createMediaPoolPane() {
    auto* box = new QGroupBox("Medya Havuzu & Toplu Dönüştürme Kuyruğu", m_topSplitter);
    auto* layout = new QVBoxLayout(box);
    layout->setContentsMargins(8, 12, 8, 8);
    layout->setSpacing(6);

    // Action row
    auto* topActions = new QHBoxLayout();
    topActions->setSpacing(6);
    m_btnAddFiles = new QPushButton("+ Dosya Ekle", box);
    m_btnAddFolder = new QPushButton("+ Klasör Ekle", box);
    m_btnClear = new QPushButton("Temizle", box);
    topActions->addWidget(m_btnAddFiles);
    topActions->addWidget(m_btnAddFolder);
    topActions->addWidget(m_btnClear);
    topActions->addStretch();
    layout->addLayout(topActions);

    // Output directory row
    auto* outRow = new QHBoxLayout();
    outRow->setSpacing(6);
    m_chkSameAsSource = new QCheckBox("Kaynak Klasör", box);
    m_chkSameAsSource->setChecked(true);
    m_chkSameAsSource->setToolTip("Dönüştürülen dosyaları kaynakla aynı klasöre kaydet");

    m_editOutputDir = new QLineEdit(box);
    m_editOutputDir->setPlaceholderText("Çıktı klasörü...");
    m_editOutputDir->setText(QStandardPaths::writableLocation(QStandardPaths::DesktopLocation) + "/Avenlyx_Cikti");
    m_editOutputDir->setEnabled(false);

    m_btnBrowseOut = new QPushButton("Gözat...", box);
    m_btnBrowseOut->setEnabled(false);

    outRow->addWidget(m_chkSameAsSource);
    outRow->addWidget(m_editOutputDir, 1);
    outRow->addWidget(m_btnBrowseOut);
    layout->addLayout(outRow);

    // Batch format assignment row
    auto* batchRow = new QHBoxLayout();
    batchRow->setSpacing(6);

    m_cmbBatchCategory = new QComboBox(box);
    m_cmbBatchCategory->addItem("Resim", static_cast<int>(MediaCategory::Image));
    m_cmbBatchCategory->addItem("Video", static_cast<int>(MediaCategory::Video));
    m_cmbBatchCategory->addItem("Ses", static_cast<int>(MediaCategory::Audio));

    m_cmbBatchFormat = new QComboBox(box);
    auto populateBatchFormats = [this]() {
        m_cmbBatchFormat->clear();
        const auto cat = static_cast<MediaCategory>(m_cmbBatchCategory->currentData().toInt());
        for (const auto& f : Formats::validTargetsFor(cat)) {
            m_cmbBatchFormat->addItem(f.label, f.ext);
        }
    };
    populateBatchFormats();

    m_btnApplyBatch = new QPushButton("Toplu Format Ata", box);

    batchRow->addWidget(m_cmbBatchCategory);
    batchRow->addWidget(m_cmbBatchFormat, 1);
    batchRow->addWidget(m_btnApplyBatch);
    layout->addLayout(batchRow);

    // Queue table
    m_table = new QTableWidget(0, 5, box);
    m_table->setHorizontalHeaderLabels({"Dosya", "Bilgi", "Hedef Format", "İlerleme", "Durum"});
    m_table->horizontalHeader()->setSectionResizeMode(0, QHeaderView::Stretch);
    m_table->horizontalHeader()->setSectionResizeMode(1, QHeaderView::Stretch);
    m_table->horizontalHeader()->setSectionResizeMode(2, QHeaderView::Fixed);
    m_table->setColumnWidth(2, 130);
    m_table->horizontalHeader()->setSectionResizeMode(3, QHeaderView::Fixed);
    m_table->setColumnWidth(3, 86);
    m_table->horizontalHeader()->setSectionResizeMode(4, QHeaderView::ResizeToContents);
    m_table->setSelectionBehavior(QAbstractItemView::SelectRows);
    m_table->setSelectionMode(QAbstractItemView::ExtendedSelection);
    m_table->setEditTriggers(QAbstractItemView::NoEditTriggers);
    m_table->verticalHeader()->setVisible(false);
    m_table->setAlternatingRowColors(true);
    m_table->setAcceptDrops(false);
    m_table->viewport()->setAcceptDrops(false);
    m_table->setToolTip("Önizlemek için tıklayın, zaman çizelgesine eklemek için çift tıklayın");
    layout->addWidget(m_table, 1);

    // Bottom pool actions
    auto* footerRow = new QHBoxLayout();
    footerRow->setSpacing(6);

    m_btnAddToTimeline = new QPushButton("Zaman Çizelgesine Ekle", box);
    m_btnAddToTimeline->setToolTip("Seçili medyaları (seçim yoksa tümünü) zaman çizelgesine ekle");

    m_btnConvert = new QPushButton("Toplu Dönüştür", box);
    m_btnConvert->setObjectName("btnPrimary");

    m_btnCancelConvert = new QPushButton("Durdur", box);
    m_btnCancelConvert->setObjectName("btnDanger");
    m_btnCancelConvert->setEnabled(false);

    footerRow->addWidget(m_btnAddToTimeline);
    footerRow->addStretch();
    footerRow->addWidget(m_btnCancelConvert);
    footerRow->addWidget(m_btnConvert);
    layout->addLayout(footerRow);

    connect(m_chkSameAsSource, &QCheckBox::toggled, this, [this](bool checked) {
        m_editOutputDir->setEnabled(!checked);
        m_btnBrowseOut->setEnabled(!checked);
    });
    connect(m_cmbBatchCategory, QOverload<int>::of(&QComboBox::currentIndexChanged),
            this, [populateBatchFormats](int) { populateBatchFormats(); });
    connect(m_btnAddFiles, &QPushButton::clicked, this, &MainWindow::onAddFiles);
    connect(m_btnAddFolder, &QPushButton::clicked, this, &MainWindow::onAddFolder);
    connect(m_btnClear, &QPushButton::clicked, this, &MainWindow::onClearList);
    connect(m_btnBrowseOut, &QPushButton::clicked, this, &MainWindow::onBrowseOutputDir);
    connect(m_btnApplyBatch, &QPushButton::clicked, this, &MainWindow::onApplyBatchFormat);
    connect(m_btnAddToTimeline, &QPushButton::clicked, this, &MainWindow::onAddSelectedToTimeline);
    connect(m_btnConvert, &QPushButton::clicked, this, &MainWindow::onStartConversion);
    connect(m_btnCancelConvert, &QPushButton::clicked, this, &MainWindow::onCancelConversion);
    connect(m_table, &QTableWidget::itemSelectionChanged, this, &MainWindow::onMediaTableSelectionChanged);
    connect(m_table, &QTableWidget::cellDoubleClicked, this, &MainWindow::onMediaTableDoubleClicked);

    return box;
}

QWidget* MainWindow::createInspectorPane() {
    auto* box = new QGroupBox("Denetleyici (Inspector)", m_topSplitter);
    auto* boxLayout = new QVBoxLayout(box);
    boxLayout->setContentsMargins(8, 12, 8, 8);

    m_inspectorTabs = new QTabWidget(box);

    // Tab 1: Dönüştürme & Codec
    auto* tab1Scroll = new QScrollArea(m_inspectorTabs);
    tab1Scroll->setWidgetResizable(true);
    tab1Scroll->setFrameShape(QFrame::NoFrame);
    auto* tab1 = new QWidget(tab1Scroll);
    auto* form1 = new QFormLayout(tab1);
    form1->setContentsMargins(8, 10, 8, 10);
    form1->setSpacing(8);

    auto* qRow = new QHBoxLayout();
    m_sliderQuality = new QSlider(Qt::Horizontal, tab1);
    m_sliderQuality->setRange(10, 100);
    m_sliderQuality->setValue(85);
    m_lblQualityVal = new QLabel("%85", tab1);
    m_lblQualityVal->setMinimumWidth(42);
    connect(m_sliderQuality, &QSlider::valueChanged, this, [this](int val) {
        m_lblQualityVal->setText(QString("%%1").arg(val));
    });
    qRow->addWidget(m_sliderQuality);
    qRow->addWidget(m_lblQualityVal);
    form1->addRow("Kalite:", qRow);

    m_cmbResPreset = new QComboBox(tab1);
    m_cmbResPreset->addItem("Orijinal", "orig");
    m_cmbResPreset->addItem("3840x2160 (4K UHD)", "2160p");
    m_cmbResPreset->addItem("1920x1080 (Full HD)", "1080p");
    m_cmbResPreset->addItem("1280x720 (HD)", "720p");
    m_cmbResPreset->addItem("854x480 (SD)", "480p");
    m_cmbResPreset->addItem("Özel Boyut", "custom");
    connect(m_cmbResPreset, QOverload<int>::of(&QComboBox::currentIndexChanged),
            this, &MainWindow::onResolutionPresetChanged);
    form1->addRow("Çözünürlük:", m_cmbResPreset);

    auto* sizeRow = new QHBoxLayout();
    m_spinWidth = new QSpinBox(tab1);
    m_spinWidth->setRange(0, 7680);
    m_spinWidth->setSpecialValueText("Oto");
    m_spinHeight = new QSpinBox(tab1);
    m_spinHeight->setRange(0, 4320);
    m_spinHeight->setSpecialValueText("Oto");
    m_spinWidth->setEnabled(false);
    m_spinHeight->setEnabled(false);
    sizeRow->addWidget(new QLabel("G:", tab1));
    sizeRow->addWidget(m_spinWidth);
    sizeRow->addWidget(new QLabel("Y:", tab1));
    sizeRow->addWidget(m_spinHeight);
    form1->addRow("Özel Boyut (px):", sizeRow);

    m_cmbVideoCodec = new QComboBox(tab1);
    m_cmbVideoCodec->addItem("Otomatik", "");
    m_cmbVideoCodec->addItem("H.264 (libx264)", "libx264");
    m_cmbVideoCodec->addItem("H.265 / HEVC (libx265)", "libx265");
    m_cmbVideoCodec->addItem("VP9 (libvpx-vp9)", "libvpx-vp9");
    m_cmbVideoCodec->addItem("MPEG-4 (mpeg4)", "mpeg4");
    form1->addRow("Video Codec:", m_cmbVideoCodec);

    m_cmbFps = new QComboBox(tab1);
    m_cmbFps->addItem("Orijinal", 0);
    m_cmbFps->addItem("60 FPS", 60);
    m_cmbFps->addItem("30 FPS", 30);
    m_cmbFps->addItem("24 FPS", 24);
    m_cmbFps->addItem("15 FPS", 15);
    form1->addRow("Video FPS:", m_cmbFps);

    m_cmbAudioBitrate = new QComboBox(tab1);
    m_cmbAudioBitrate->addItems({"320k", "256k", "192k", "128k", "96k"});
    m_cmbAudioBitrate->setCurrentText("192k");
    form1->addRow("Ses Bitrate:", m_cmbAudioBitrate);

    m_cmbSampleRate = new QComboBox(tab1);
    m_cmbSampleRate->addItem("Orijinal", 0);
    m_cmbSampleRate->addItem("48000 Hz", 48000);
    m_cmbSampleRate->addItem("44100 Hz", 44100);
    m_cmbSampleRate->addItem("22050 Hz", 22050);
    form1->addRow("Örnekleme:", m_cmbSampleRate);

    m_cmbAudioStream = new QComboBox(tab1);
    m_cmbAudioStream->addItem("Varsayılan (Otomatik)", -1);
    connect(m_cmbAudioStream, QOverload<int>::of(&QComboBox::currentIndexChanged),
            this, &MainWindow::onAudioStreamComboChanged);
    form1->addRow("Ses Kanalı:", m_cmbAudioStream);

    auto* trimRow = new QHBoxLayout();
    m_spinTrimStart = new QDoubleSpinBox(tab1);
    m_spinTrimStart->setRange(0.0, 86400.0);
    m_spinTrimStart->setDecimals(2);
    m_spinTrimStart->setSingleStep(0.5);
    m_spinTrimStart->setSuffix(" sn");

    m_spinTrimEnd = new QDoubleSpinBox(tab1);
    m_spinTrimEnd->setRange(0.0, 86400.0);
    m_spinTrimEnd->setDecimals(2);
    m_spinTrimEnd->setSingleStep(0.5);
    m_spinTrimEnd->setSpecialValueText("Son");
    m_spinTrimEnd->setSuffix(" sn");

    trimRow->addWidget(new QLabel("Baş:", tab1));
    trimRow->addWidget(m_spinTrimStart);
    trimRow->addWidget(new QLabel("Bit:", tab1));
    trimRow->addWidget(m_spinTrimEnd);
    form1->addRow("Kesme (Trim):", trimRow);

    m_chkNoAudio = new QCheckBox("Sesi Kaldır (Sessiz Video)", tab1);
    m_chkStripMeta = new QCheckBox("Metadata (EXIF) Temizle", tab1);
    form1->addRow(m_chkNoAudio);
    form1->addRow(m_chkStripMeta);

    tab1Scroll->setWidget(tab1);
    m_inspectorTabs->addTab(tab1Scroll, "Dönüştürme && Codec");

    // Tab 2: Efektler & Ses Mikseri
    auto* tab2Scroll = new QScrollArea(m_inspectorTabs);
    tab2Scroll->setWidgetResizable(true);
    tab2Scroll->setFrameShape(QFrame::NoFrame);
    auto* tab2 = new QWidget(tab2Scroll);
    auto* tab2Layout = new QVBoxLayout(tab2);
    tab2Layout->setContentsMargins(8, 10, 8, 10);
    tab2Layout->setSpacing(8);

    auto* targetRow = new QHBoxLayout();
    m_lblEffectsTarget = new QLabel("Hedef: Toplu Dönüştürme Ayarları", tab2);
    m_lblEffectsTarget->setStyleSheet("color: #93c5fd; font-weight: 600; font-size: 12px;");
    m_btnResetEffects = new QPushButton("Sıfırla", tab2);
    m_btnResetEffects->setMaximumWidth(75);
    targetRow->addWidget(m_lblEffectsTarget, 1);
    targetRow->addWidget(m_btnResetEffects);
    tab2Layout->addLayout(targetRow);

    auto* form2 = new QFormLayout();
    form2->setSpacing(8);

    // Brightness (-1.0..1.0)
    auto* brRow = new QHBoxLayout();
    m_sliderBrightness = new QSlider(Qt::Horizontal, tab2);
    m_sliderBrightness->setRange(-100, 100);
    m_sliderBrightness->setValue(0);
    m_lblBrightnessVal = new QLabel("0.00", tab2);
    m_lblBrightnessVal->setMinimumWidth(42);
    brRow->addWidget(m_sliderBrightness);
    brRow->addWidget(m_lblBrightnessVal);
    form2->addRow("Parlaklık:", brRow);

    // Contrast (0.2..2.0)
    auto* ctRow = new QHBoxLayout();
    m_sliderContrast = new QSlider(Qt::Horizontal, tab2);
    m_sliderContrast->setRange(20, 200);
    m_sliderContrast->setValue(100);
    m_lblContrastVal = new QLabel("1.00", tab2);
    m_lblContrastVal->setMinimumWidth(42);
    ctRow->addWidget(m_sliderContrast);
    ctRow->addWidget(m_lblContrastVal);
    form2->addRow("Kontrast:", ctRow);

    // Saturation (0.0..3.0)
    auto* stRow = new QHBoxLayout();
    m_sliderSaturation = new QSlider(Qt::Horizontal, tab2);
    m_sliderSaturation->setRange(0, 300);
    m_sliderSaturation->setValue(100);
    m_lblSaturationVal = new QLabel("1.00", tab2);
    m_lblSaturationVal->setMinimumWidth(42);
    stRow->addWidget(m_sliderSaturation);
    stRow->addWidget(m_lblSaturationVal);
    form2->addRow("Doygunluk:", stRow);

    m_cmbRotate = new QComboBox(tab2);
    m_cmbRotate->addItem("0° (Normal)", 0);
    m_cmbRotate->addItem("90° Saat Yönü", 90);
    m_cmbRotate->addItem("180° Ters", 180);
    m_cmbRotate->addItem("270° Saat Yönü Tersi", 270);
    form2->addRow("Döndürme:", m_cmbRotate);

    auto* flipRow = new QHBoxLayout();
    m_chkFlipH = new QCheckBox("Yatay Çevir", tab2);
    m_chkFlipV = new QCheckBox("Dikey Çevir", tab2);
    flipRow->addWidget(m_chkFlipH);
    flipRow->addWidget(m_chkFlipV);
    form2->addRow("Çevirme:", flipRow);

    auto* colorFxRow = new QHBoxLayout();
    m_chkGrayscale = new QCheckBox("Siyah-Beyaz", tab2);
    m_chkSepia = new QCheckBox("Sepya", tab2);
    colorFxRow->addWidget(m_chkGrayscale);
    colorFxRow->addWidget(m_chkSepia);
    form2->addRow("Renk Efekti:", colorFxRow);

    // Volume (0..200%)
    auto* volRow = new QHBoxLayout();
    m_sliderVolume = new QSlider(Qt::Horizontal, tab2);
    m_sliderVolume->setRange(0, 200);
    m_sliderVolume->setValue(100);
    m_lblVolumeVal = new QLabel("%100", tab2);
    m_lblVolumeVal->setMinimumWidth(42);
    volRow->addWidget(m_sliderVolume);
    volRow->addWidget(m_lblVolumeVal);
    form2->addRow("Ses Seviyesi:", volRow);

    auto* fadeRow = new QHBoxLayout();
    m_spinFadeIn = new QDoubleSpinBox(tab2);
    m_spinFadeIn->setRange(0.0, 30.0);
    m_spinFadeIn->setDecimals(2);
    m_spinFadeIn->setSingleStep(0.25);
    m_spinFadeIn->setSuffix(" sn");

    m_spinFadeOut = new QDoubleSpinBox(tab2);
    m_spinFadeOut->setRange(0.0, 30.0);
    m_spinFadeOut->setDecimals(2);
    m_spinFadeOut->setSingleStep(0.25);
    m_spinFadeOut->setSuffix(" sn");

    fadeRow->addWidget(new QLabel("Giriş:", tab2));
    fadeRow->addWidget(m_spinFadeIn);
    fadeRow->addWidget(new QLabel("Çıkış:", tab2));
    fadeRow->addWidget(m_spinFadeOut);
    form2->addRow("Ses Geçişi:", fadeRow);

    tab2Layout->addLayout(form2);
    tab2Layout->addStretch();

    tab2Scroll->setWidget(tab2);
    m_inspectorTabs->addTab(tab2Scroll, "Efektler && Ses Mikseri");

    boxLayout->addWidget(m_inspectorTabs);

    // Connect all Tab 2 effect controls
    connect(m_btnResetEffects, &QPushButton::clicked, this, &MainWindow::onResetEffects);
    connect(m_sliderBrightness, &QSlider::valueChanged, this, &MainWindow::onEffectsChanged);
    connect(m_sliderContrast, &QSlider::valueChanged, this, &MainWindow::onEffectsChanged);
    connect(m_sliderSaturation, &QSlider::valueChanged, this, &MainWindow::onEffectsChanged);
    connect(m_cmbRotate, QOverload<int>::of(&QComboBox::currentIndexChanged), this, &MainWindow::onEffectsChanged);
    connect(m_chkFlipH, &QCheckBox::toggled, this, &MainWindow::onEffectsChanged);
    connect(m_chkFlipV, &QCheckBox::toggled, this, &MainWindow::onEffectsChanged);
    connect(m_chkGrayscale, &QCheckBox::toggled, this, &MainWindow::onEffectsChanged);
    connect(m_chkSepia, &QCheckBox::toggled, this, &MainWindow::onEffectsChanged);
    connect(m_sliderVolume, &QSlider::valueChanged, this, &MainWindow::onEffectsChanged);
    connect(m_spinFadeIn, QOverload<double>::of(&QDoubleSpinBox::valueChanged), this, &MainWindow::onEffectsChanged);
    connect(m_spinFadeOut, QOverload<double>::of(&QDoubleSpinBox::valueChanged), this, &MainWindow::onEffectsChanged);

    return box;
}

QWidget* MainWindow::createTimelinePane() {
    auto* box = new QGroupBox("Çok Kanallı Zaman Çizelgesi (Timeline)", m_mainSplitter);
    auto* layout = new QVBoxLayout(box);
    layout->setContentsMargins(8, 12, 8, 8);
    layout->setSpacing(6);

    // Timeline Toolbar
    auto* toolbar = new QHBoxLayout();
    toolbar->setSpacing(6);

    m_btnAddVideoTrack = new QPushButton("+ Video Kanalı", box);
    m_btnAddAudioTrack = new QPushButton("+ Ses Kanalı", box);
    m_btnSplitClip = new QPushButton("Playhead'den Böl", box);
    m_btnSplitClip->setToolTip("Seçili klibi kırmızı imleç konumundan ikiye böl (S)");
    m_btnDeleteClip = new QPushButton("Seçili Klibi Sil", box);
    m_btnDeleteClip->setToolTip("Seçili klibi zaman çizelgesinden kaldır (Delete)");

    auto* zoomLbl = new QLabel("Yakınlaştırma:", box);
    m_sliderZoom = new QSlider(Qt::Horizontal, box);
    m_sliderZoom->setRange(15, 200);
    m_sliderZoom->setValue(60);
    m_sliderZoom->setFixedWidth(130);
    m_lblZoomVal = new QLabel("60 px/s", box);
    m_lblZoomVal->setMinimumWidth(52);

    auto* outFmtLbl = new QLabel("Çıktı:", box);
    m_cmbTimelineFormat = new QComboBox(box);
    m_cmbTimelineFormat->addItem("MP4 (H.264 + AAC)", "mp4");
    m_cmbTimelineFormat->addItem("MKV (Matroska)", "mkv");
    m_cmbTimelineFormat->addItem("WebM (VP9 + Opus)", "webm");
    m_cmbTimelineFormat->addItem("MOV (QuickTime)", "mov");
    m_cmbTimelineFormat->addItem("GIF (Animasyon)", "gif");
    m_cmbTimelineFormat->addItem("MP3 (Yalnızca Ses)", "mp3");
    m_cmbTimelineFormat->addItem("WAV (Kayıpsız Ses)", "wav");

    m_btnExportTimeline = new QPushButton("Zaman Çizelgesini Render Et", box);
    m_btnExportTimeline->setObjectName("btnSuccess");

    m_btnCancelExport = new QPushButton("Durdur", box);
    m_btnCancelExport->setObjectName("btnDanger");
    m_btnCancelExport->setEnabled(false);

    toolbar->addWidget(m_btnAddVideoTrack);
    toolbar->addWidget(m_btnAddAudioTrack);
    toolbar->addSpacing(6);
    toolbar->addWidget(m_btnSplitClip);
    toolbar->addWidget(m_btnDeleteClip);
    toolbar->addSpacing(10);
    toolbar->addWidget(zoomLbl);
    toolbar->addWidget(m_sliderZoom);
    toolbar->addWidget(m_lblZoomVal);
    toolbar->addStretch();
    toolbar->addWidget(outFmtLbl);
    toolbar->addWidget(m_cmbTimelineFormat);
    toolbar->addWidget(m_btnCancelExport);
    toolbar->addWidget(m_btnExportTimeline);

    layout->addLayout(toolbar);

    m_timelineScroll = new QScrollArea(box);
    m_timelineScroll->setWidgetResizable(true);
    m_timelineScroll->setHorizontalScrollBarPolicy(Qt::ScrollBarAsNeeded);
    m_timelineScroll->setVerticalScrollBarPolicy(Qt::ScrollBarAsNeeded);
    m_timelineScroll->setFrameShape(QFrame::StyledPanel);

    m_timelineWidget = new TimelineWidget(m_timelineScroll);
    m_timelineScroll->setWidget(m_timelineWidget);
    layout->addWidget(m_timelineScroll, 1);

    connect(m_btnAddVideoTrack, &QPushButton::clicked, this, &MainWindow::onAddVideoTrack);
    connect(m_btnAddAudioTrack, &QPushButton::clicked, this, &MainWindow::onAddAudioTrack);
    connect(m_btnSplitClip, &QPushButton::clicked, this, &MainWindow::onSplitClip);
    connect(m_btnDeleteClip, &QPushButton::clicked, this, &MainWindow::onDeleteSelectedClip);
    connect(m_sliderZoom, &QSlider::valueChanged, this, &MainWindow::onZoomChanged);
    connect(m_btnExportTimeline, &QPushButton::clicked, this, &MainWindow::onStartTimelineExport);
    connect(m_btnCancelExport, &QPushButton::clicked, this, &MainWindow::onCancelTimelineExport);

    return box;
}

void MainWindow::applyDarkTheme() {
    setStyleSheet(R"(
        QMainWindow, QWidget {
            background-color: #0f141f;
            color: #e2e8f0;
            font-family: "Segoe UI", sans-serif;
            font-size: 12px;
        }
        #appTitle {
            font-size: 17px;
            font-weight: 800;
            letter-spacing: 0.6px;
            color: #60a5fa;
        }
        #versionBadge {
            background-color: #1e3a8a;
            color: #93c5fd;
            border: 1px solid #3b82f6;
            border-radius: 4px;
            padding: 2px 7px;
            font-size: 11px;
            font-weight: 700;
        }
        #appSubtitle {
            color: #94a3b8;
            font-size: 12px;
        }
        QGroupBox {
            background-color: #151c2b;
            border: 1px solid #26334d;
            border-radius: 8px;
            margin-top: 9px;
            padding-top: 10px;
            font-weight: 600;
        }
        QGroupBox::title {
            subcontrol-origin: margin;
            left: 10px;
            padding: 0 6px;
            color: #93c5fd;
        }
        QSplitter::handle {
            background-color: #1e293b;
        }
        QSplitter::handle:horizontal {
            width: 4px;
        }
        QSplitter::handle:vertical {
            height: 4px;
        }
        QSplitter::handle:hover {
            background-color: #3b82f6;
        }
        QTabWidget::pane {
            border: 1px solid #28354d;
            border-radius: 6px;
            background-color: #131a27;
        }
        QTabBar::tab {
            background-color: #1b2436;
            color: #94a3b8;
            border: 1px solid #28354d;
            border-bottom: none;
            border-top-left-radius: 5px;
            border-top-right-radius: 5px;
            padding: 6px 12px;
            margin-right: 3px;
            font-weight: 600;
        }
        QTabBar::tab:selected {
            background-color: #131a27;
            color: #f8fafc;
            border-color: #3b82f6;
        }
        QTableWidget {
            background-color: #111724;
            alternate-background-color: #151d2d;
            border: 1px solid #26334d;
            border-radius: 6px;
            gridline-color: #202c42;
            selection-background-color: #1e3a8a;
        }
        QHeaderView::section {
            background-color: #1a2438;
            color: #cbd5e1;
            padding: 5px 6px;
            border: none;
            border-bottom: 1px solid #2b3b57;
            font-weight: 600;
        }
        QPushButton {
            background-color: #1f2a40;
            color: #e2e8f0;
            border: 1px solid #334463;
            border-radius: 5px;
            padding: 5px 11px;
            font-weight: 600;
        }
        QPushButton:hover {
            background-color: #2b3a57;
            border-color: #4f6d9e;
        }
        QPushButton:checked {
            background-color: #1d4ed8;
            border-color: #60a5fa;
            color: #ffffff;
        }
        QPushButton:disabled {
            background-color: #171e2c;
            color: #64748b;
            border-color: #222c40;
        }
        #btnPrimary {
            background-color: #2563eb;
            border-color: #3b82f6;
            color: #ffffff;
            padding: 6px 14px;
        }
        #btnPrimary:hover {
            background-color: #1d4ed8;
        }
        #btnSuccess {
            background-color: #059669;
            border-color: #10b981;
            color: #ffffff;
            padding: 6px 14px;
        }
        #btnSuccess:hover {
            background-color: #047857;
        }
        #btnDanger {
            background-color: #991b1b;
            border-color: #dc2626;
            color: #ffffff;
        }
        #btnDanger:hover {
            background-color: #b91c1c;
        }
        #btnPlayPause[playing="true"] {
            background-color: #d97706;
            border-color: #f59e0b;
            color: #ffffff;
        }
        #timecodeLabel {
            background-color: #0b101b;
            border: 1px solid #26334d;
            border-radius: 4px;
            padding: 3px 8px;
            color: #60a5fa;
        }
        QComboBox, QLineEdit, QSpinBox, QDoubleSpinBox, QPlainTextEdit {
            background-color: #0d121d;
            border: 1px solid #2b3a55;
            border-radius: 5px;
            padding: 4px 7px;
            color: #f1f5f9;
        }
        QComboBox:disabled, QLineEdit:disabled, QSpinBox:disabled, QDoubleSpinBox:disabled {
            color: #64748b;
            background-color: #121826;
        }
        QProgressBar {
            background-color: #0d121d;
            border: 1px solid #26334d;
            border-radius: 5px;
            text-align: center;
            color: #ffffff;
            height: 18px;
        }
        QProgressBar::chunk {
            background-color: #3b82f6;
            border-radius: 4px;
        }
        QScrollArea {
            background-color: #0d121d;
            border: 1px solid #243047;
            border-radius: 6px;
        }
    )");
}

void MainWindow::dragEnterEvent(QDragEnterEvent* event) {
    if (event->mimeData()->hasUrls()) {
        event->acceptProposedAction();
    }
}

void MainWindow::dropEvent(QDropEvent* event) {
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
    addFilePaths(paths);
}

void MainWindow::keyPressEvent(QKeyEvent* event) {
    QWidget* fw = focusWidget();
    const bool textInputFocused = qobject_cast<QLineEdit*>(fw) != nullptr
        || qobject_cast<QAbstractSpinBox*>(fw) != nullptr
        || qobject_cast<QPlainTextEdit*>(fw) != nullptr;

    if (!textInputFocused) {
        if (event->key() == Qt::Key_Space) {
            onPlayPauseToggled(!m_previewWidget->isPlaying());
            event->accept();
            return;
        }
        if (event->key() == Qt::Key_I) {
            onMarkInRequested();
            event->accept();
            return;
        }
        if (event->key() == Qt::Key_O) {
            onMarkOutRequested();
            event->accept();
            return;
        }
        if (event->key() == Qt::Key_S && event->modifiers() == Qt::NoModifier) {
            onSplitClip();
            event->accept();
            return;
        }
        if (event->key() == Qt::Key_Delete && m_timelineWidget->selectedClipId() >= 0) {
            onDeleteSelectedClip();
            event->accept();
            return;
        }
    }

    QMainWindow::keyPressEvent(event);
}

void MainWindow::onAddFiles() {
    const QStringList exts = Formats::allSupportedInputExtensions();
    QStringList patterns;
    for (const QString& e : exts) {
        patterns << "*." + e;
    }
    const QString filter = QString("Medya Dosyaları (%1);;Tüm Dosyalar (*.*)").arg(patterns.join(" "));
    const QStringList files = QFileDialog::getOpenFileNames(this, "Medya Dosyaları Seç", QString(), filter);
    if (!files.isEmpty()) {
        addFilePaths(files);
    }
}

void MainWindow::onAddFolder() {
    const QString dir = QFileDialog::getExistingDirectory(this, "Klasör Seç");
    if (dir.isEmpty()) return;

    QStringList paths;
    QDirIterator it(dir, QDir::Files, QDirIterator::Subdirectories);
    while (it.hasNext()) {
        paths << it.next();
    }
    addFilePaths(paths);
}

int MainWindow::appendMediaToPool(const MediaInfo& info) {
    for (int i = 0; i < m_items.size(); ++i) {
        if (m_items[i].filePath == info.filePath) {
            return i;
        }
    }

    const int row = m_table->rowCount();
    m_table->insertRow(row);
    m_items.append(info);

    m_table->setItem(row, 0, new QTableWidgetItem(info.fileName));
    m_table->setItem(row, 1, new QTableWidgetItem(info.summaryText()));

    auto* cmb = new QComboBox(m_table);
    const auto targets = Formats::validTargetsFor(info.category);
    for (const auto& f : targets) {
        cmb->addItem(f.label, f.ext);
    }
    for (int i = 0; i < cmb->count(); ++i) {
        if (cmb->itemData(i).toString() != info.sourceExt) {
            cmb->setCurrentIndex(i);
            break;
        }
    }
    m_table->setCellWidget(row, 2, cmb);

    auto* bar = new QProgressBar(m_table);
    bar->setRange(0, 100);
    bar->setValue(0);
    m_table->setCellWidget(row, 3, bar);

    m_table->setItem(row, 4, new QTableWidgetItem("Bekliyor"));
    return row;
}

QList<MediaInfo> MainWindow::probeAndRegisterFiles(const QStringList& paths, bool addToPoolTable) {
    QList<MediaInfo> result;
    for (const QString& rawPath : paths) {
        const QString absPath = QFileInfo(rawPath).absoluteFilePath();
        int existingIdx = -1;
        for (int i = 0; i < m_items.size(); ++i) {
            if (m_items[i].filePath == absPath) {
                existingIdx = i;
                break;
            }
        }

        if (existingIdx >= 0) {
            result.append(m_items[existingIdx]);
            continue;
        }

        MediaInfo info = MediaProbe::probe(absPath);
        if (info.category == MediaCategory::Unknown) {
            continue;
        }
        if (addToPoolTable) {
            appendMediaToPool(info);
        }
        result.append(info);
    }
    return result;
}

void MainWindow::addFilePaths(const QStringList& paths) {
    const int before = m_items.size();
    probeAndRegisterFiles(paths, true);
    const int added = m_items.size() - before;

    if (added > 0) {
        m_lblStatus->setText(QString("%1 dosya havuza eklendi. Toplam: %2 dosya.").arg(added).arg(m_items.size()));
        if (m_table->selectedItems().isEmpty() && m_table->rowCount() > 0) {
            m_table->selectRow(before);
        }
    } else if (!paths.isEmpty()) {
        m_lblStatus->setText("Desteklenen yeni medya dosyası bulunamadı.");
    }
}

void MainWindow::onClearList() {
    if (m_worker->isRunning()) return;
    m_table->setRowCount(0);
    m_items.clear();
    m_previewPoolRow = -1;
    if (m_timelineWidget->selectedClipId() < 0) {
        populateAudioStreamsCombo({}, -1);
    }
    m_lblStatus->setText("Medya havuzu listesi temizlendi.");
}

void MainWindow::onBrowseOutputDir() {
    const QString dir = QFileDialog::getExistingDirectory(this, "Çıktı Klasörü Seç", m_editOutputDir->text());
    if (!dir.isEmpty()) {
        m_editOutputDir->setText(dir);
    }
}

void MainWindow::onApplyBatchFormat() {
    const auto targetCat = static_cast<MediaCategory>(m_cmbBatchCategory->currentData().toInt());
    const QString targetExt = m_cmbBatchFormat->currentData().toString();
    int updated = 0;

    for (int row = 0; row < m_items.size(); ++row) {
        if (m_items[row].category == targetCat) {
            if (auto* cmb = qobject_cast<QComboBox*>(m_table->cellWidget(row, 2))) {
                const int idx = cmb->findData(targetExt);
                if (idx >= 0) {
                    cmb->setCurrentIndex(idx);
                    updated++;
                }
            }
        }
    }
    m_lblStatus->setText(QString("%1 dosya için hedef format '%2' olarak güncellendi.")
                             .arg(updated)
                             .arg(targetExt.toUpper()));
}

void MainWindow::onAddSelectedToTimeline() {
    if (m_items.isEmpty()) {
        QMessageBox::information(this, "Bilgi", "Zaman çizelgesine eklemek için önce Medya Havuzuna dosya ekleyin.");
        return;
    }

    QList<int> rows;
    QSet<int> seen;
    for (const auto* item : m_table->selectedItems()) {
        if (!seen.contains(item->row())) {
            seen.insert(item->row());
            rows.append(item->row());
        }
    }
    std::sort(rows.begin(), rows.end());

    if (rows.isEmpty()) {
        for (int r = 0; r < m_items.size(); ++r) {
            rows.append(r);
        }
    }

    int lastAddedId = -1;
    for (int r : rows) {
        if (r < 0 || r >= m_items.size()) continue;
        const MediaInfo& media = m_items[r];
        const int clipId = m_project.addClip(media, -1, -1.0);
        if (clipId >= 0) {
            lastAddedId = clipId;
            if (media.category == MediaCategory::Video || media.category == MediaCategory::Audio) {
                m_previewProvider->requestWaveform(clipId, media.filePath);
            }
        }
    }

    if (lastAddedId >= 0) {
        m_previewPoolMode = false;
        m_timelineWidget->setSelectedClipId(lastAddedId);
        onClipSelected(lastAddedId);
        m_timelineWidget->refreshLayout();
        requestPreviewAtPlayhead();
        m_lblStatus->setText(QString("%1 klip zaman çizelgesine eklendi.").arg(rows.size()));
    }
}

void MainWindow::onMediaTableSelectionChanged() {
    const auto selected = m_table->selectedItems();
    if (selected.isEmpty()) return;

    const int row = selected.first()->row();
    if (row < 0 || row >= m_items.size()) return;

    if (m_timelineWidget->selectedClipId() < 0) {
        populateAudioStreamsCombo(m_items[row].audioStreams, -1);
    }

    if (!m_playTimer->isActive()) {
        requestPreviewForPoolRow(row);
    }
}

void MainWindow::onMediaTableDoubleClicked(int row, int /*column*/) {
    if (row < 0 || row >= m_items.size()) return;
    const MediaInfo& media = m_items[row];
    const int clipId = m_project.addClip(media, -1, -1.0);
    if (clipId >= 0) {
        if (media.category == MediaCategory::Video || media.category == MediaCategory::Audio) {
            m_previewProvider->requestWaveform(clipId, media.filePath);
        }
        m_previewPoolMode = false;
        m_timelineWidget->setSelectedClipId(clipId);
        onClipSelected(clipId);
        m_timelineWidget->refreshLayout();
        requestPreviewAtPlayhead();
        m_lblStatus->setText(QString("'%1' zaman çizelgesine eklendi.").arg(media.fileName));
    }
}

void MainWindow::onResolutionPresetChanged(int index) {
    const QString preset = m_cmbResPreset->itemData(index).toString();
    const bool isCustom = (preset == "custom");
    m_spinWidth->setEnabled(isCustom);
    m_spinHeight->setEnabled(isCustom);

    if (preset == "orig") {
        m_spinWidth->setValue(0);
        m_spinHeight->setValue(0);
    } else if (preset == "2160p") {
        m_spinWidth->setValue(3840);
        m_spinHeight->setValue(2160);
    } else if (preset == "1080p") {
        m_spinWidth->setValue(1920);
        m_spinHeight->setValue(1080);
    } else if (preset == "720p") {
        m_spinWidth->setValue(1280);
        m_spinHeight->setValue(720);
    } else if (preset == "480p") {
        m_spinWidth->setValue(854);
        m_spinHeight->setValue(480);
    }
}

FilterAdjustments MainWindow::currentFiltersFromUi() const {
    FilterAdjustments f;
    f.brightness = static_cast<double>(m_sliderBrightness->value()) / 100.0;
    f.contrast = static_cast<double>(m_sliderContrast->value()) / 100.0;
    f.saturation = static_cast<double>(m_sliderSaturation->value()) / 100.0;
    f.rotateDegrees = m_cmbRotate->currentData().toInt();
    f.flipH = m_chkFlipH->isChecked();
    f.flipV = m_chkFlipV->isChecked();
    f.grayscale = m_chkGrayscale->isChecked();
    f.sepia = m_chkSepia->isChecked();
    return f;
}

ConversionOptions MainWindow::currentOptions() const {
    ConversionOptions opt;
    opt.quality = m_sliderQuality->value();
    opt.width = m_spinWidth->value();
    opt.height = m_spinHeight->value();
    opt.fps = m_cmbFps->currentData().toInt();
    opt.videoCodec = m_cmbVideoCodec->currentData().toString();
    opt.audioBitrate = m_cmbAudioBitrate->currentText();
    opt.sampleRate = m_cmbSampleRate->currentData().toInt();
    opt.selectedAudioStream = m_cmbAudioStream->currentData().toInt();
    opt.noAudio = m_chkNoAudio->isChecked();
    opt.stripMetadata = m_chkStripMeta->isChecked();
    opt.trimStartSec = m_spinTrimStart->value();
    opt.trimEndSec = m_spinTrimEnd->value();
    opt.volume = static_cast<double>(m_sliderVolume->value()) / 100.0;
    opt.fadeInSec = m_spinFadeIn->value();
    opt.fadeOutSec = m_spinFadeOut->value();
    opt.filters = currentFiltersFromUi();
    return opt;
}

QString MainWindow::buildOutputPath(const MediaInfo& info, const QString& targetExt) const {
    const QFileInfo fi(info.filePath);
    QString dir = m_chkSameAsSource->isChecked() ? fi.absolutePath() : m_editOutputDir->text().trimmed();
    if (dir.isEmpty()) dir = fi.absolutePath();
    QDir().mkpath(dir);

    const QString base = fi.completeBaseName();
    QString candidate = QDir(dir).filePath(base + "." + targetExt);
    if (QFileInfo(candidate).absoluteFilePath() == fi.absoluteFilePath() || QFileInfo::exists(candidate)) {
        candidate = QDir(dir).filePath(base + "_donusturuldu." + targetExt);
        int counter = 2;
        while (QFileInfo::exists(candidate)) {
            candidate = QDir(dir).filePath(QString("%1_donusturuldu_%2.%3").arg(base).arg(counter++).arg(targetExt));
        }
    }
    return candidate;
}

void MainWindow::onStartConversion() {
    if (m_items.isEmpty()) {
        QMessageBox::information(this, "Bilgi", "Lütfen önce dönüştürülecek dosya ekleyin.");
        return;
    }
    if (m_worker->isRunning() || m_exporter->isRunning()) return;

    QList<ConversionTask> tasks;
    const ConversionOptions opts = currentOptions();
    m_lastOutputDirs.clear();
    m_batchRowProgress.fill(0, m_items.size());

    for (int row = 0; row < m_items.size(); ++row) {
        auto* cmb = qobject_cast<QComboBox*>(m_table->cellWidget(row, 2));
        const QString targetExt = cmb ? cmb->currentData().toString() : "";
        if (targetExt.isEmpty()) continue;

        ConversionTask t;
        t.row = row;
        t.media = m_items[row];
        t.targetExt = targetExt;
        t.outputPath = buildOutputPath(m_items[row], targetExt);
        t.options = opts;
        tasks.append(t);

        const QString outDir = QFileInfo(t.outputPath).absolutePath();
        if (!m_lastOutputDirs.contains(outDir)) {
            m_lastOutputDirs.append(outDir);
        }

        if (auto* bar = qobject_cast<QProgressBar*>(m_table->cellWidget(row, 3))) {
            bar->setValue(0);
        }
        m_table->item(row, 4)->setText("Sırada");
    }

    if (tasks.isEmpty()) return;
    if (m_previewWidget->isPlaying()) {
        onPlayPauseToggled(false);
    }
    m_batchTotalTasks = tasks.size();
    m_globalProgress->setValue(0);
    setUiBusy(true, false);
    m_lblStatus->setText(QString("%1 dosya dönüştürülüyor...").arg(tasks.size()));
    m_worker->startQueue(tasks);
}

void MainWindow::onCancelConversion() {
    m_worker->cancel();
    m_lblStatus->setText("Toplu dönüştürme durduruluyor...");
}

void MainWindow::onTaskStarted(int row) {
    if (row >= 0 && row < m_table->rowCount()) {
        m_table->item(row, 4)->setText("Dönüştürülüyor...");
    }
}

void MainWindow::onTaskProgress(int row, int percent) {
    if (row >= 0 && row < m_table->rowCount()) {
        if (auto* bar = qobject_cast<QProgressBar*>(m_table->cellWidget(row, 3))) {
            bar->setValue(percent);
        }
        if (row < m_batchRowProgress.size()) {
            m_batchRowProgress[row] = qBound(0, percent, 100);
        }
        if (m_batchTotalTasks > 0) {
            int sum = 0;
            for (int p : m_batchRowProgress) sum += p;
            m_globalProgress->setValue(qBound(0, sum / m_batchTotalTasks, 100));
        }
    }
}

void MainWindow::onTaskFinished(int row, bool success, const QString& message, qint64 outputSizeBytes) {
    if (row < 0 || row >= m_table->rowCount()) return;
    if (row < m_batchRowProgress.size()) {
        m_batchRowProgress[row] = 100;
    }
    if (success) {
        if (auto* bar = qobject_cast<QProgressBar*>(m_table->cellWidget(row, 3))) {
            bar->setValue(100);
        }
        m_table->item(row, 4)->setText(QString("Tamamlandı (%1)").arg(MediaInfo::formatBytes(outputSizeBytes)));
    } else {
        m_table->item(row, 4)->setText("Hata: " + message);
    }
}

void MainWindow::onAllFinished(int successCount, int failCount) {
    setUiBusy(false, false);
    m_globalProgress->setValue(failCount == 0 ? 100 : m_globalProgress->value());
    m_lblStatus->setText(
        QString("Toplu dönüştürme tamamlandı. Başarılı: %1, Hatalı/İptal: %2").arg(successCount).arg(failCount));
}

void MainWindow::onPlayheadChanged(double sec) {
    m_previewPoolMode = false;
    const double total = qMax(m_project.totalDurationSec(), sec);
    m_previewWidget->setTimecode(sec, total);
    requestPreviewAtPlayhead();
}

void MainWindow::onClipSelected(int clipId) {
    const TimelineClip* clip = m_project.findClip(clipId);
    loadClipIntoInspector(clip);
    if (clip) {
        m_previewPoolMode = false;
        requestPreviewAtPlayhead();
    }
}

void MainWindow::onProjectModified() {
    const double cur = m_timelineWidget->playheadSec();
    const double total = qMax(m_project.totalDurationSec(), cur);
    m_previewWidget->setTimecode(cur, total);
    if (const TimelineClip* clip = m_project.findClip(m_timelineWidget->selectedClipId())) {
        loadClipIntoInspector(clip);
    }
    requestPreviewAtPlayhead();
}

void MainWindow::onFilesDroppedOnTrack(const QStringList& paths, int trackIndex, double dropTimeSec) {
    const QList<MediaInfo> probed = probeAndRegisterFiles(paths, true);
    if (probed.isEmpty()) {
        m_lblStatus->setText("Bırakılan dosyalar arasında desteklenen medya bulunamadı.");
        return;
    }

    double cursorSec = qMax(0.0, dropTimeSec);
    int lastId = -1;
    for (const MediaInfo& media : probed) {
        const int clipId = m_project.addClip(media, trackIndex, cursorSec);
        if (clipId >= 0) {
            lastId = clipId;
            if (const TimelineClip* added = m_project.findClip(clipId)) {
                cursorSec = added->timelineStartSec + added->clipDuration();
            }
            if (media.category == MediaCategory::Video || media.category == MediaCategory::Audio) {
                m_previewProvider->requestWaveform(clipId, media.filePath);
            }
        }
    }

    if (lastId >= 0) {
        m_previewPoolMode = false;
        m_timelineWidget->setSelectedClipId(lastId);
        onClipSelected(lastId);
        m_timelineWidget->refreshLayout();
        requestPreviewAtPlayhead();
        m_lblStatus->setText(QString("%1 klip zaman çizelgesine bırakıldı.").arg(probed.size()));
    }
}

void MainWindow::onPlayPauseToggled(bool playing) {
    if (playing) {
        if (m_project.clips.isEmpty()) {
            m_previewWidget->setPlaying(false);
            m_lblStatus->setText("Oynatma için zaman çizelgesine klip ekleyin.");
            return;
        }
        const double total = m_project.totalDurationSec();
        if (m_timelineWidget->playheadSec() >= total - 0.05 && total > 0.0) {
            m_timelineWidget->setPlayheadSec(0.0);
        }
        m_previewPoolMode = false;
        m_previewWidget->setPlaying(true);
        m_playElapsed.restart();
        m_previewRequestTimer.restart();
        m_playTimer->start();
        requestPreviewAtPlayhead();
    } else {
        m_playTimer->stop();
        m_previewWidget->setPlaying(false);
    }
}

void MainWindow::onSeekRequested(double sec) {
    if (m_project.clips.isEmpty() && m_previewPoolMode && m_previewPoolRow >= 0 && m_previewPoolRow < m_items.size()) {
        const double dur = qMax(0.0, m_items[m_previewPoolRow].durationSec);
        requestPreviewForPoolRow(m_previewPoolRow, qBound(0.0, sec, dur));
        return;
    }

    const double total = m_project.totalDurationSec();
    const double target = qBound(0.0, sec, qMax(total, sec));
    m_previewPoolMode = false;
    m_timelineWidget->setPlayheadSec(target);
    m_previewWidget->setTimecode(target, qMax(total, target));
    requestPreviewAtPlayhead();
}

void MainWindow::onStepRequested(double deltaSec) {
    if (m_project.clips.isEmpty() && m_previewPoolMode && m_previewPoolRow >= 0 && m_previewPoolRow < m_items.size()) {
        const double dur = qMax(0.0, m_items[m_previewPoolRow].durationSec);
        requestPreviewForPoolRow(m_previewPoolRow, qBound(0.0, m_previewPoolTimeSec + deltaSec, dur));
        return;
    }
    onSeekRequested(qMax(0.0, m_timelineWidget->playheadSec() + deltaSec));
}

void MainWindow::onMarkInRequested() {
    const double ph = m_timelineWidget->playheadSec();
    TimelineClip* clip = m_project.findClip(m_timelineWidget->selectedClipId());
    if (clip && ph > clip->timelineStartSec && ph < clip->timelineStartSec + clip->clipDuration() - 0.2) {
        const double delta = ph - clip->timelineStartSec;
        clip->timelineStartSec = ph;
        if (clip->media.category == MediaCategory::Image) {
            clip->outPointSec = qMax(0.2, clip->outPointSec - delta);
        } else {
            clip->inPointSec += delta;
        }
        m_timelineWidget->refreshLayout();
        onProjectModified();
        m_lblStatus->setText(QString("Klip girişi [I] %1 konumuna kırpıldı.").arg(MediaInfo::formatTimecode(ph)));
        return;
    }

    m_spinTrimStart->setValue(ph);
    m_lblStatus->setText(QString("Kesme başlangıcı [I]: %1 sn").arg(ph, 0, 'f', 2));
}

void MainWindow::onMarkOutRequested() {
    const double ph = m_timelineWidget->playheadSec();
    TimelineClip* clip = m_project.findClip(m_timelineWidget->selectedClipId());
    if (clip && ph >= clip->timelineStartSec + 0.2 && ph < clip->timelineStartSec + clip->clipDuration()) {
        const double newDur = ph - clip->timelineStartSec;
        clip->outPointSec = clip->inPointSec + newDur;
        m_timelineWidget->refreshLayout();
        onProjectModified();
        m_lblStatus->setText(QString("Klip çıkışı [O] %1 konumuna kırpıldı.").arg(MediaInfo::formatTimecode(ph)));
        return;
    }

    m_spinTrimEnd->setValue(ph);
    m_lblStatus->setText(QString("Kesme bitişi [O]: %1 sn").arg(ph, 0, 'f', 2));
}

void MainWindow::onPlaybackTick() {
    const double dt = static_cast<double>(m_playElapsed.restart()) / 1000.0;
    const double total = m_project.totalDurationSec();
    double nextSec = m_timelineWidget->playheadSec() + dt;

    if (total <= 0.0 || nextSec >= total) {
        nextSec = qMax(0.0, total);
        m_playTimer->stop();
        m_previewWidget->setPlaying(false);
    }

    m_timelineWidget->setPlayheadSec(nextSec);
    m_previewWidget->setTimecode(nextSec, qMax(total, nextSec));

    if (!m_previewInFlight || m_previewRequestTimer.elapsed() >= 320) {
        requestPreviewAtPlayhead();
    }
}

void MainWindow::onPreviewFrameReady(const QString& filePath, double /*timestampSec*/, const QImage& frame) {
    m_previewInFlight = false;
    if (filePath == m_expectedPreviewPath) {
        m_previewWidget->setFrame(frame);
    }
}

void MainWindow::onWaveformReady(int clipId, const QVector<float>& peaks) {
    if (TimelineClip* clip = m_project.findClip(clipId)) {
        clip->waveformPeaks = peaks;
        m_timelineWidget->update();
    }
}

bool MainWindow::isTrackEffectivelyMuted(int trackIndex) const {
    if (trackIndex < 0 || trackIndex >= m_project.tracks.size()) return true;
    const auto& tr = m_project.tracks[trackIndex];
    if (tr.muted) return true;

    bool anySameTypeSolo = false;
    for (const auto& t : m_project.tracks) {
        if (t.type == tr.type && t.solo) {
            anySameTypeSolo = true;
            break;
        }
    }
    return anySameTypeSolo && !tr.solo;
}

const TimelineClip* MainWindow::activeVideoClipAt(double sec) const {
    const TimelineClip* best = nullptr;
    int bestTrack = -1;
    for (const auto& clip : m_project.clips) {
        if (clip.trackType != MediaCategory::Video) continue;
        if (isTrackEffectivelyMuted(clip.trackIndex)) continue;
        const double start = clip.timelineStartSec;
        const double end = start + clip.clipDuration();
        if (sec >= start && sec <= end) {
            // Higher track index (e.g. V2 layer over V1) takes visual priority
            if (clip.trackIndex >= bestTrack) {
                bestTrack = clip.trackIndex;
                best = &clip;
            }
        }
    }
    return best;
}

const TimelineClip* MainWindow::activeAudioClipAt(double sec) const {
    for (const auto& clip : m_project.clips) {
        if (clip.trackType != MediaCategory::Audio) continue;
        if (clip.muted || isTrackEffectivelyMuted(clip.trackIndex)) continue;
        const double start = clip.timelineStartSec;
        const double end = start + clip.clipDuration();
        if (sec >= start && sec <= end) {
            return &clip;
        }
    }
    return nullptr;
}

void MainWindow::requestPreviewAtPlayhead() {
    if (m_previewPoolMode && m_previewPoolRow >= 0 && m_previewPoolRow < m_items.size()) {
        requestPreviewForPoolRow(m_previewPoolRow, m_previewPoolTimeSec);
        return;
    }

    const double ph = m_timelineWidget->playheadSec();
    const double total = qMax(m_project.totalDurationSec(), ph);
    m_previewWidget->setTimecode(ph, total);

    const TimelineClip* vClip = activeVideoClipAt(ph);
    if (vClip) {
        const double mediaTime = (vClip->media.category == MediaCategory::Image)
            ? 0.0
            : vClip->inPointSec + qMax(0.0, ph - vClip->timelineStartSec);

        m_expectedPreviewPath = vClip->media.filePath;
        m_previewWidget->setOverlayTitle(
            QString("%1 • %2").arg(vClip->media.fileName, MediaInfo::formatTimecode(mediaTime)));
        m_previewInFlight = true;
        m_previewRequestTimer.restart();
        m_previewProvider->requestFrame(vClip->media.filePath, mediaTime, vClip->filters);
        return;
    }

    m_expectedPreviewPath.clear();
    m_previewInFlight = false;
    m_previewWidget->setFrame(QImage());
    if (const TimelineClip* aClip = activeAudioClipAt(ph)) {
        m_previewWidget->setOverlayTitle("Ses Çalınıyor: " + aClip->media.fileName);
    } else {
        m_previewWidget->setOverlayTitle(QString());
    }
}

void MainWindow::requestPreviewForPoolRow(int row, double timestampSec) {
    if (row < 0 || row >= m_items.size()) return;
    const MediaInfo& info = m_items[row];
    m_previewPoolMode = true;
    m_previewPoolRow = row;

    if (info.category == MediaCategory::Audio) {
        m_previewPoolTimeSec = 0.0;
        m_expectedPreviewPath.clear();
        m_previewWidget->setFrame(QImage());
        m_previewWidget->setOverlayTitle("Ses Dosyası: " + info.fileName);
        m_previewWidget->setTimecode(0.0, info.durationSec);
        return;
    }

    const double ts = (info.category == MediaCategory::Image)
        ? 0.0
        : (timestampSec >= 0.0 ? timestampSec : m_spinTrimStart->value());
    m_previewPoolTimeSec = ts;
    m_expectedPreviewPath = info.filePath;
    m_previewWidget->setOverlayTitle(QString("%1 (%2x%3)").arg(info.fileName).arg(info.width).arg(info.height));
    m_previewWidget->setTimecode(ts, info.durationSec);
    m_previewInFlight = true;
    m_previewRequestTimer.restart();
    m_previewProvider->requestFrame(info.filePath, ts, currentFiltersFromUi());
}

void MainWindow::onAddVideoTrack() {
    m_project.addTrack(MediaCategory::Video);
    m_timelineWidget->refreshLayout();
    m_lblStatus->setText("Yeni video kanalı eklendi.");
}

void MainWindow::onAddAudioTrack() {
    m_project.addTrack(MediaCategory::Audio);
    m_timelineWidget->refreshLayout();
    m_lblStatus->setText("Yeni ses kanalı eklendi.");
}

void MainWindow::onSplitClip() {
    const double ph = m_timelineWidget->playheadSec();
    int targetClipId = m_timelineWidget->selectedClipId();

    auto spansPlayhead = [ph](const TimelineClip* c) {
        return c && ph >= c->timelineStartSec + 0.1 && ph <= c->timelineStartSec + c->clipDuration() - 0.1;
    };

    if (!spansPlayhead(m_project.findClip(targetClipId))) {
        targetClipId = -1;
        for (const auto& c : m_project.clips) {
            if (spansPlayhead(&c)) {
                targetClipId = c.id;
                break;
            }
        }
    }

    if (targetClipId < 0) {
        m_lblStatus->setText("Bölme için playhead bir klibin içinde olmalıdır.");
        return;
    }

    const int rightId = m_project.splitClip(targetClipId, ph);
    if (rightId >= 0) {
        m_timelineWidget->setSelectedClipId(rightId);
        onClipSelected(rightId);
        m_timelineWidget->refreshLayout();
        m_lblStatus->setText("Klip playhead konumundan ikiye bölündü.");
    } else {
        m_lblStatus->setText("Klip bu konumdan bölünemedi (minimum klip süresi 0.1 sn).");
    }
}

void MainWindow::onDeleteSelectedClip() {
    const int cid = m_timelineWidget->selectedClipId();
    if (cid < 0) {
        m_lblStatus->setText("Silmek için zaman çizelgesinden bir klip seçin.");
        return;
    }

    if (m_project.removeClip(cid)) {
        m_timelineWidget->setSelectedClipId(-1);
        loadClipIntoInspector(nullptr);
        m_timelineWidget->refreshLayout();
        requestPreviewAtPlayhead();
        m_lblStatus->setText("Seçili klip zaman çizelgesinden kaldırıldı.");
    }
}

void MainWindow::onZoomChanged(int value) {
    m_lblZoomVal->setText(QString("%1 px/s").arg(value));
    m_timelineWidget->setPixelsPerSecond(static_cast<double>(value));
}

void MainWindow::onStartTimelineExport() {
    if (m_project.clips.isEmpty()) {
        QMessageBox::information(this, "Bilgi", "Render almadan önce zaman çizelgesine en az bir klip ekleyin.");
        return;
    }
    if (m_worker->isRunning() || m_exporter->isRunning()) return;

    const QString fmtExt = m_cmbTimelineFormat->currentData().toString();
    QString defaultDir = m_chkSameAsSource->isChecked()
        ? QFileInfo(m_project.clips.first().media.filePath).absolutePath()
        : m_editOutputDir->text().trimmed();
    if (defaultDir.isEmpty()) {
        defaultDir = QStandardPaths::writableLocation(QStandardPaths::DesktopLocation);
    }
    QDir().mkpath(defaultDir);

    const QString suggested = QDir(defaultDir).filePath(
        QString("ZamanCizelgesi_%1.%2")
            .arg(QDateTime::currentDateTime().toString("yyyyMMdd_HHmmss"), fmtExt));

    const QString filter = QString("%1 Dosyası (*.%2);;Tüm Dosyalar (*.*)").arg(fmtExt.toUpper(), fmtExt);
    const QString outPath = QFileDialog::getSaveFileName(this, "Zaman Çizelgesini Render Et", suggested, filter);
    if (outPath.isEmpty()) return;

    ExportSettings settings;
    settings.outputPath = outPath;
    settings.formatExt = fmtExt;
    settings.width = (m_spinWidth->value() > 0) ? m_spinWidth->value() : 1920;
    settings.height = (m_spinHeight->value() > 0) ? m_spinHeight->value() : 1080;
    settings.fps = m_cmbFps->currentData().toInt();
    settings.quality = m_sliderQuality->value();
    settings.audioBitrate = m_cmbAudioBitrate->currentText();
    settings.sampleRate = (m_cmbSampleRate->currentData().toInt() > 0) ? m_cmbSampleRate->currentData().toInt() : 48000;

    const QString outDir = QFileInfo(outPath).absolutePath();
    if (!m_lastOutputDirs.contains(outDir)) {
        m_lastOutputDirs.prepend(outDir);
    }

    if (m_previewWidget->isPlaying()) {
        onPlayPauseToggled(false);
    }
    m_globalProgress->setValue(0);
    setUiBusy(false, true);
    m_lblStatus->setText("Zaman çizelgesi render ediliyor...");
    m_exporter->startExport(m_project, settings);
}

void MainWindow::onCancelTimelineExport() {
    m_exporter->cancel();
    m_lblStatus->setText("Zaman çizelgesi render işlemi durduruluyor...");
}

void MainWindow::onExportProgress(int percent, const QString& statusText) {
    m_globalProgress->setValue(qBound(0, percent, 100));
    if (!statusText.isEmpty()) {
        m_lblStatus->setText(statusText);
    }
}

void MainWindow::onExportFinished(bool success, const QString& outputPath, const QString& message) {
    setUiBusy(false, false);
    if (success) {
        m_globalProgress->setValue(100);
        const qint64 bytes = QFileInfo(outputPath).size();
        m_lblStatus->setText(QString("Zaman çizelgesi render edildi: %1 (%2)")
                                 .arg(QFileInfo(outputPath).fileName(), MediaInfo::formatBytes(bytes)));
    } else {
        m_lblStatus->setText("Render hatası/iptal: " + message);
    }
}

void MainWindow::loadClipIntoInspector(const TimelineClip* clip) {
    m_updatingInspector = true;
    const QSignalBlocker b1(m_sliderBrightness);
    const QSignalBlocker b2(m_sliderContrast);
    const QSignalBlocker b3(m_sliderSaturation);
    const QSignalBlocker b4(m_cmbRotate);
    const QSignalBlocker b5(m_chkFlipH);
    const QSignalBlocker b6(m_chkFlipV);
    const QSignalBlocker b7(m_chkGrayscale);
    const QSignalBlocker b8(m_chkSepia);
    const QSignalBlocker b9(m_sliderVolume);
    const QSignalBlocker b10(m_spinFadeIn);
    const QSignalBlocker b11(m_spinFadeOut);

    if (clip) {
        m_lblEffectsTarget->setText(QString("Hedef: Seçili Klip — %1").arg(clip->media.fileName));
        m_sliderBrightness->setValue(qRound(clip->filters.brightness * 100.0));
        m_sliderContrast->setValue(qRound(clip->filters.contrast * 100.0));
        m_sliderSaturation->setValue(qRound(clip->filters.saturation * 100.0));

        const int rotIdx = m_cmbRotate->findData(clip->filters.rotateDegrees);
        m_cmbRotate->setCurrentIndex(rotIdx >= 0 ? rotIdx : 0);

        m_chkFlipH->setChecked(clip->filters.flipH);
        m_chkFlipV->setChecked(clip->filters.flipV);
        m_chkGrayscale->setChecked(clip->filters.grayscale);
        m_chkSepia->setChecked(clip->filters.sepia);
        m_sliderVolume->setValue(qRound(clip->volume * 100.0));
        m_spinFadeIn->setValue(clip->fadeInSec);
        m_spinFadeOut->setValue(clip->fadeOutSec);

        populateAudioStreamsCombo(clip->media.audioStreams, clip->selectedAudioStream);
    } else {
        m_lblEffectsTarget->setText("Hedef: Toplu Dönüştürme Ayarları");
        const auto rows = m_table->selectedItems();
        if (!rows.isEmpty() && rows.first()->row() < m_items.size()) {
            populateAudioStreamsCombo(m_items[rows.first()->row()].audioStreams, -1);
        } else {
            populateAudioStreamsCombo({}, -1);
        }
    }

    m_lblBrightnessVal->setText(QString::number(static_cast<double>(m_sliderBrightness->value()) / 100.0, 'f', 2));
    m_lblContrastVal->setText(QString::number(static_cast<double>(m_sliderContrast->value()) / 100.0, 'f', 2));
    m_lblSaturationVal->setText(QString::number(static_cast<double>(m_sliderSaturation->value()) / 100.0, 'f', 2));
    m_lblVolumeVal->setText(QString("%%1").arg(m_sliderVolume->value()));

    m_updatingInspector = false;
}

void MainWindow::populateAudioStreamsCombo(const QList<AudioStreamInfo>& streams, int selectedIndex) {
    const QSignalBlocker blocker(m_cmbAudioStream);
    m_cmbAudioStream->clear();
    m_cmbAudioStream->addItem("Varsayılan (Otomatik)", -1);

    for (const auto& s : streams) {
        QString label = QString("#%1: %2 | %3 Hz | %4 ch")
                            .arg(s.streamIndex)
                            .arg(s.codec.isEmpty() ? "?" : s.codec.toUpper())
                            .arg(s.sampleRate)
                            .arg(s.channels);
        if (!s.language.isEmpty()) label += QString(" (%1)").arg(s.language);
        if (!s.title.isEmpty()) label += QString(" - %1").arg(s.title);
        m_cmbAudioStream->addItem(label, s.streamIndex);
    }

    const int found = m_cmbAudioStream->findData(selectedIndex);
    m_cmbAudioStream->setCurrentIndex(found >= 0 ? found : 0);
}

void MainWindow::onEffectsChanged() {
    if (m_updatingInspector) return;

    m_lblBrightnessVal->setText(QString::number(static_cast<double>(m_sliderBrightness->value()) / 100.0, 'f', 2));
    m_lblContrastVal->setText(QString::number(static_cast<double>(m_sliderContrast->value()) / 100.0, 'f', 2));
    m_lblSaturationVal->setText(QString::number(static_cast<double>(m_sliderSaturation->value()) / 100.0, 'f', 2));
    m_lblVolumeVal->setText(QString("%%1").arg(m_sliderVolume->value()));

    if (TimelineClip* clip = m_project.findClip(m_timelineWidget->selectedClipId())) {
        clip->filters = currentFiltersFromUi();
        clip->volume = static_cast<double>(m_sliderVolume->value()) / 100.0;
        clip->fadeInSec = m_spinFadeIn->value();
        clip->fadeOutSec = m_spinFadeOut->value();
        m_timelineWidget->update();
    }

    requestPreviewAtPlayhead();
}

void MainWindow::onResetEffects() {
    m_updatingInspector = true;
    m_sliderBrightness->setValue(0);
    m_sliderContrast->setValue(100);
    m_sliderSaturation->setValue(100);
    m_cmbRotate->setCurrentIndex(0);
    m_chkFlipH->setChecked(false);
    m_chkFlipV->setChecked(false);
    m_chkGrayscale->setChecked(false);
    m_chkSepia->setChecked(false);
    m_sliderVolume->setValue(100);
    m_spinFadeIn->setValue(0.0);
    m_spinFadeOut->setValue(0.0);
    m_updatingInspector = false;

    onEffectsChanged();
    m_lblStatus->setText("Efektler ve ses mikseri varsayılana sıfırlandı.");
}

void MainWindow::onAudioStreamComboChanged(int index) {
    if (m_updatingInspector || index < 0) return;
    if (TimelineClip* clip = m_project.findClip(m_timelineWidget->selectedClipId())) {
        const int streamIdx = m_cmbAudioStream->currentData().toInt();
        clip->selectedAudioStream = qMax(0, streamIdx);
    }
}

void MainWindow::onOpenOutputFolder() {
    QString dir;
    if (!m_chkSameAsSource->isChecked() && !m_editOutputDir->text().trimmed().isEmpty()) {
        dir = m_editOutputDir->text().trimmed();
        QDir().mkpath(dir);
    } else if (!m_lastOutputDirs.isEmpty()) {
        dir = m_lastOutputDirs.first();
    } else if (!m_items.isEmpty()) {
        dir = QFileInfo(m_items.first().filePath).absolutePath();
    } else if (!m_project.clips.isEmpty()) {
        dir = QFileInfo(m_project.clips.first().media.filePath).absolutePath();
    }

    if (!dir.isEmpty()) {
        QDesktopServices::openUrl(QUrl::fromLocalFile(dir));
    }
}

void MainWindow::onToggleLog(bool checked) {
    m_logContainer->setVisible(checked);
}

void MainWindow::onAppendFfmpegLog(const QString& cmdLine) {
    if (!m_txtLog) return;
    const QString ts = QDateTime::currentDateTime().toString("HH:mm:ss");
    m_txtLog->appendPlainText(QString("[%1] %2").arg(ts, cmdLine));
}

void MainWindow::setUiBusy(bool batchBusy, bool exportBusy) {
    const bool busy = batchBusy || exportBusy;

    m_btnAddFiles->setEnabled(!busy);
    m_btnAddFolder->setEnabled(!busy);
    m_btnClear->setEnabled(!busy);
    m_btnApplyBatch->setEnabled(!busy);
    m_btnAddToTimeline->setEnabled(!busy);
    m_btnConvert->setEnabled(!busy);
    m_btnCancelConvert->setEnabled(batchBusy);

    m_btnAddVideoTrack->setEnabled(!busy);
    m_btnAddAudioTrack->setEnabled(!busy);
    m_btnSplitClip->setEnabled(!busy);
    m_btnDeleteClip->setEnabled(!busy);
    m_btnExportTimeline->setEnabled(!busy);
    m_btnCancelExport->setEnabled(exportBusy);
}
