# Geliştirme Günlüğü (`CHANGELOG_DEV`)

## [1.0.0-dev] - AVENLYX Studio (Android Medya Stüdyosu — Kotlin / Compose / Media3)

### Mimari ve Altyapı (`android-foundation`)
- Gradle `8.11.1` + AGP `8.7.2` + Kotlin `2.0.21` + Compose BOM `2024.09.00` + Media3 `1.5.1` sürüm kataloğu (`gradle/libs.versions.toml`) yapılandırıldı (`minSdk = 26`, `compileSdk = 35`, `targetSdk = 35`, JVM `17`).
- Çevrimdışı güvenlik modeli uygulandı (`INTERNET` izni yok; `FOREGROUND_SERVICE_MEDIA_PROCESSING`, `POST_NOTIFICATIONS`, `WAKE_LOCK` izinleri ve `FileProvider` yapılandırması).
- Alan modelleri (`MediaModels.kt`), 14 senaryolu Türkçe hata eşleyici (`StudioErrorMapper`), atomik SAF/MediaStore depolama yardımcısı (`FileStorageHelper`), SQLite kalıcılık katmanı (`StudioDatabase` - yarım kalan iş kurtarma ve `schemaVersion` desteği), DataStore tercih deposu (`UserPreferencesRepository`) ve Undo/Redo destekli `StudioRepository` tanımlandı.

### Gerçek Medya Motorları (`android-media-engine`)
- `EngineCapabilityRegistry`: `MediaCodecList` üzerinden cihazın gerçek H.264/HEVC/VP9/AV1 ve AAC/OPUS/FLAC encoder yeteneklerinin tespiti; `supportsCrossfade = false` dürüst bayrağı.
- `MediaInspector`: `ContentResolver`, `MediaExtractor`, `MediaMetadataRetriever`, `ExifInterface` ve PCM `MediaCodec` dalga formu çıkarımı; uzantı-içerik uyuşmazlığı kontrolü.
- `Media3Engine`: Media3 `Transformer` `1.5.1` ile video dönüştürme, kesme, birleştirme, filtre/hız/metin katmanı, videodan ses çıkarma, videoya ses ekleme (`AudioFitMode`) ve fotoğraflardan MP4 slayt videosu.
- `AndroidPlatformEngine`: `MediaExtractor` + `MediaCodec` + `MediaMuxer` + yerleşik RIFF/WAV header yazıcı ile garantili WAV/M4A ses dönüştürme, kesme, birleştirme, iki sesi miksleme, fade-in/out ve ses seviyesi/kanal kontrolü.
- `ImageEngine`: `ImageDecoder`/`BitmapFactory` (`inSampleSize` korumalı), `Matrix`, `Canvas`, EXIF okuma/silme, şeffaf arka plan doldurma, filigran ve JPG/PNG/WebP/BMP kodlama.
- `MediaProcessingService`: Android 14/15 `mediaProcessing` ön plan servisi, ilerleme bildirimi, `İptal Et` aksiyonu ve `onTimeout` güvenli durdurma.

### Kullanıcı Arayüzü (`android-studio-ui`)
- Material 3 Açık/Koyu stüdyo teması, Basit Mod / Gelişmiş Ayarlar geçişi ve telefon (`NavigationBar`) / tablet (`NavigationRail`) uyarlanabilir yerleşim.
- 12 araç kartlı Ana Sayfa (`HomeScreen`), Photo Picker + SAF medya seçici ve teknik inceleme ekranı (`PickerAndDetailScreen`).
- Gerçek `ExoPlayer` önizlemeli ve zaman çizelgeli Video Stüdyosu (`VideoStudioScreen`), etkileşimli `Canvas` dalga formlu Ses Stüdyosu (`AudioStudioScreen`), toplu Resim/Slayt Videosu Stüdyosu (`ImageAndSlideshowScreen`), İşlem Kuyruğu / Çıktılarım / Ayarlar ekranı (`QueueHistorySettingsScreen`).

### Doğrulama, Dokümantasyon ve Paketleme (`android-verifier`)
- `mobile/README.md`, `mobile/SUPPORTED_FORMATS.md`, `mobile/KNOWN_LIMITATIONS.md` ve `mobile/CHANGELOG_DEV.md` oluşturuldu ve `AVENLYX Studio` adına göre güncellendi.
- `StudioUnitTest.kt` içinde 19 birim test (12 `StudioTool`, `JobStatus`, `TimelineClipItem`/`TimelineProject`, `EngineCapabilities`, 14 `StudioErrorCode` + `StudioErrorMapper.fromThrowable`, `MediaFormatUtils` uzantı/MIME/uyuşmazlık tespiti, `estimateOutputBytes`, `StudioRepository` 30 adımlı Timeline Undo/Redo + `estimateJob` fallback, `UserPreferencesRepository` DataStore akışı, `AudioPcmUtils.StreamingLinearResampler` mono->stereo) yazıldı.
- `.\gradlew.bat testDebugUnitTest assembleDebug` komutu `BUILD SUCCESSFUL` ile tamamlandı (`19 tests, 0 failures, 0 skipped`).
- `aapt2 dump badging` ile APK meta verileri (`com.formatci.mediastudio`, `minSdk=26`, `targetSdk=35`, `application-label='AVENLYX Studio'`, `INTERNET` izni yok) doğrulandı.
- Çıktı APK dosyaları `dist/mobile/AvenlyxStudio-debug.apk` ve `dist/mobile/AvenlyxStudio.apk` (22.839.072 bayt) yoluna paketlendi; kök `README.md` güncellendi.

