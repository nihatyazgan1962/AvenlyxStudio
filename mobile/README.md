# AVENLYX Studio — Android Medya Stüdyosu

**AVENLYX Studio**, tamamen çevrimdışı çalışan, Kotlin ve Jetpack Compose (Material 3) ile geliştirilmiş; video, ses ve resim dosyalarını dönüştürme, kesme, birleştirme, filtreleme ve dışa aktarma işlemlerini doğrudan cihaz üzerinde gerçekleştiren modern bir mobil medya stüdyosudur.

---

## Temel İlkeler

- **%100 Çevrimdışı ve Gizlilik Odaklı:** `AndroidManifest.xml` içinde `INTERNET` izni yer almaz. Tüm medya işleme ve inceleme işlemleri cihaz donanımı üzerinde yerel olarak çalışır.
- **Gerçek Donanım & Platform Motorları:** Sahte/simüle çıktı üretilmez.
  - **Video & Slayt Gösterisi:** AndroidX Media3 (`ExoPlayer` + `Transformer` `1.5.1`)
  - **Ses İşleme:** Android Platform `MediaExtractor` + `MediaCodec` + `MediaMuxer` + yerleşik RIFF/WAV PCM yazıcı (`AndroidPlatformEngine`)
  - **Resim İşleme:** `ImageDecoder` / `BitmapFactory` + `Matrix` + `Canvas` + `ExifInterface` (`ImageEngine`)
- **Dürüst Yetenek Raporlama:** `EngineCapabilityRegistry` cihazdaki gerçek `MediaCodecList` encoder/decoder desteğini tarar. Cihazın veya motorun desteklemediği format/efektler açıkça belirtilir.
- **Uyarlanabilir Tasarım (Telefon & Tablet):** Telefonlarda tek elle erişilebilir alt gezinme çubuğu (`NavigationBar`), tablet ve geniş ekranlarda (`>= 840.dp`) `NavigationRail` ve yan yana önizleme/kontrol yerleşimi sunar.
- **Basit Mod & Gelişmiş Ayarlar:** Günlük kullanıcılar için sade profiller, ileri seviye kullanıcılar için çözünürlük, FPS, bitrate, örnekleme hızı ve renk/ses ayarları.

---

## Teknoloji Yığını

| Bileşen | Sürüm / Detay |
| --- | --- |
| Dil | Kotlin `2.0.21` |
| UI Çatısı | Jetpack Compose BOM `2024.09.00` (Material 3 + Adaptive Layout) |
| Medya Motoru | AndroidX Media3 `1.5.1` (`exoplayer`, `ui`, `transformer`, `effect`, `common`) |
| Kalıcılık | SQLite (`StudioDatabase` - `SQLiteOpenHelper`) & DataStore Preferences `1.1.1` |
| Arka Plan / Servis | Ön Plan Servisi (`foregroundServiceType="mediaProcessing"`) & WorkManager `2.9.0` |
| Hedef / Minimum SDK | `minSdk = 26` (Android 8.0) · `compileSdk = 35` · `targetSdk = 35` (Android 15) |
| Derleme Sistemi | Gradle `8.11.1` · Android Gradle Plugin `8.7.2` · JDK `17` |

---

## Proje Modül ve Paket Yapısı

```text
mobile/
├── app/src/main/java/com/formatci/mediastudio/
│   ├── MediaStudioApp.kt                   # Uygulama konteyneri ve bildirim kanalı
│   ├── MainActivity.kt                     # Edge-to-edge UI, SAF/PhotoPicker ve gezinme
│   ├── domain/model/MediaModels.kt         # Çekirdek enum ve veri modelleri (12 StudioTool, Timeline, Job)
│   ├── core/
│   │   ├── designsystem/Theme.kt           # Material 3 Açık/Koyu stüdyo teması
│   │   ├── error/StudioError.kt            # 14 senaryolu Türkçe hata eşleyici (StudioErrorMapper)
│   │   └── files/FileStorageHelper.kt      # SAF URI yönetimi, StatFs alan kontrolü, atomik MediaStore kaydı
│   ├── data/
│   │   ├── database/StudioDatabase.kt      # Kalıcı SQLite tabloları ve yarım kalan iş kurtarma
│   │   ├── preferences/UserPreferencesRepository.kt
│   │   └── repository/StudioRepository.kt  # Kuyruk, geçmiş, timeline Undo/Redo ve durum akışları
│   ├── media/
│   │   ├── capability/EngineCapabilityRegistry.kt
│   │   ├── inspector/MediaInspector.kt     # Gerçek codec/FPS/EXIF tespiti ve ses dalga formu çıkarımı
│   │   └── engine/
│   │       ├── MediaEngine.kt
│   │       ├── Media3Engine.kt
│   │       ├── AndroidPlatformEngine.kt
│   │       ├── ImageEngine.kt
│   │       └── CompositeMediaEngine.kt
│   ├── service/MediaProcessingService.kt   # İlerleme ve İptal destekli ön plan servisi
│   ├── ui/StudioViewModel.kt
│   └── feature/
│       ├── home/HomeScreen.kt
│       ├── picker/PickerAndDetailScreen.kt
│       ├── video/VideoStudioScreen.kt
│       ├── audio/AudioStudioScreen.kt
│       ├── image/ImageAndSlideshowScreen.kt
│       └── queue/QueueHistorySettingsScreen.kt
├── app/src/test/java/com/formatci/mediastudio/
│   └── StudioUnitTest.kt                   # JUnit 4 + Coroutines Test birim testleri
├── SUPPORTED_FORMATS.md                    # Gerçek motor ve cihaz bazlı format matrisi
├── KNOWN_LIMITATIONS.md                    # Motor ve Android platform sınırlamaları
└── CHANGELOG_DEV.md                        # Geliştirme günlüğü
```

---

## İzinler ve Depolama Güvenliği

- `POST_NOTIFICATIONS`: Uzun süren medya işlemlerinin ilerlemesini bildirim çubuğunda göstermek için.
- `FOREGROUND_SERVICE` ve `FOREGROUND_SERVICE_MEDIA_PROCESSING`: Uygulama arka plandayken dönüştürme/dışa aktarma işleminin kesilmemesi için.
- `WAKE_LOCK`: Uzun video kodlama sırasında cihazın uyku moduna geçip işlemi durdurmasını önlemek için.
- **SAF / Photo Picker:** Tüm medya okuma işlemleri kullanıcının seçtiği URI'lar üzerinden yapılır; çıktılar atomik olarak önbellekte üretildikten sonra `MediaStore` (`Movies`, `Music`, `Pictures`, `Downloads` / `Documents/FormatciStudio`) veya seçilen SAF hedefine taşınır.

---

## Derleme ve Test

`mobile/` dizininde JDK 17 ve Android SDK 35 yapılandırılmış ortamda:

```powershell
cd mobile
.\gradlew.bat testDebugUnitTest assembleDebug
```

Derlenen debug APK dosyası kök dizindeki dağıtım klasörüne paketlenir:
- `../dist/mobile/AvenlyxStudio-debug.apk`
- `../dist/mobile/AvenlyxStudio.apk`

Ayrıntılı desteklenen format tablosu için [SUPPORTED_FORMATS.md](./SUPPORTED_FORMATS.md), bilinen platform kısıtları için [KNOWN_LIMITATIONS.md](./KNOWN_LIMITATIONS.md) dosyalarına bakınız.

