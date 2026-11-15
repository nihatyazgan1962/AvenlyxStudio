# Bilinen Sınırlamalar (`KNOWN_LIMITATIONS`)

Bu belge, **AVENLYX Studio (Android Medya Stüdyosu)** uygulamasının Android platform mimarisi ve AndroidX Media3 `1.5.1` kısıtlamalarından kaynaklanan sınırlarını şeffaf biçimde açıklar. Desteklenmeyen özellikler kullanıcıya "çalışıyormuş gibi" sunulmaz.

---

## 1. Media3 Composition Crossfade (Geçiş Efekti) Sınırlaması
- **Durum:** Desteklenmiyor (`EngineCapabilities.supportsCrossfade = false`).
- **Neden:** AndroidX Media3 `1.5.1` `Composition` ve `EditedMediaItemSequence` altyapısı, ardışık video klipleri arasında yerleşik bir **video crossfade / dissolve geçişi** sağlamamaktadır.
- **Uygulama Davranışı:** Klipler ardışık (`cut`) olarak birleştirilir; UI üzerinde crossfade geçişi sahte olarak açık gösterilmez. (Ses kliplerinde ise `AndroidPlatformEngine` içinde PCM örnekleri üzerinde **fade-in** ve **fade-out** desteklenir.)

## 2. Cihaz Bazlı Video ve Ses Codec Farklılıkları (HEVC / VP9 / AV1 / OPUS / FLAC)
- Android cihazlarda donanım ve yazılım `MediaCodec` sağlayıcıları üreticiye ve işlemciye (Snapdragon, Exynos, MediaTek, Tensor vb.) göre değişir.
- **HEVC (H.265), VP9, AV1:** Bazı cihazlarda yalnızca decoder bulunur, encoder bulunmaz; ya da belirli çözünürlük/kare hızı kombinasyonları donanım encoder'ı tarafından reddedilebilir.
- **OPUS / FLAC:** Çoğu cihazda decoder mevcuttur fakat encoder eksik olabilir.
- **Çözüm:** `EngineCapabilityRegistry` uygulama açılışında cihazın `MediaCodecList` listesini tarayarak yalnızca gerçekte var olan encoder'ları seçenek olarak sunar. Yine de bir cihaz encoder'ı çalışma anında belirli profil/çözünürlüğü reddederse `StudioErrorMapper` kullanıcıyı **H.264 / AAC / WAV** gibi evrensel profillere yönlendirir.

## 3. MP3, OGG Vorbis ve Animasyonlu GIF Çıktısı Bulunmaması
- Uygulama tamamen Android yerel API'leri (Media3 + MediaCodec + Bitmap/ImageDecoder) üzerine kuruludur; paket boyutunu düşük tutmak ve native GPL/LGPL C/C++ kütüphanelerine bağımlılık yaratmamak için FFmpeg NDK derlemesi eklenmemiştir.
- Bu nedenle:
  - **MP3, OGG Vorbis, AC3, WMA** dosyaları **okunup dönüştürülebilir**, ancak bu formatlara **doğrudan çıktı (encode)** üretilmez. Ses çıktısı için **M4A (AAC)** ve kayıpsız **WAV (PCM)** her cihazda aktiftir.
  - Videodan doğrudan **animasyonlu GIF** dışa aktarma bu sürümde desteklenmez.

## 4. Ters Video Oynatma (Reverse) ve Çoklu Serbest Video Katmanı (PiP)
- Donanım kod çözücüler ileri yönlü GOP (keyframe) zinciri ile çalıştığından kareleri bellek şişirmeden ters sıraya çevirip yeniden kodlamak mobil cihazlarda yüksek RAM/OOM riski taşır; ters oynatma efekti bu sürümde devre dışıdır.
- `VIDEO_ADD_AUDIO` ve `SLIDESHOW_MAKER` akışları güvenilir biçimde desteklenirken, karmaşık çoklu şeffaf video PiP katmanları yerine kararlı sıralı klip montajı ve metin/filigran katmanı sunulmaktadır.

## 5. Tek Eşzamanlı Media3 Transformer İşlemi
- Donanım video encoder oturumları cihazlarda sınırlı sayıda (genellikle 1-2 aktif oturum) ayrılabilir. Eşzamanlı çoklu video kodlaması termal daralma (`thermal throttling`) ve `CODEC_RESOURCE_EXHAUSTED` hatalarına yol açabildiği için `Media3Engine` işleri kuyrukta tek tek (`Mutex` ile sıralı) yürütür.

## 6. Android 14 / 15 `mediaProcessing` Ön Plan Servis Süre Sınırı
- Android 14 (API 34) ve Android 15 (API 35) üzerinde `foregroundServiceType="mediaProcessing"` kullanan servisler sistem tarafından **6 saatlik** çalışma sınırına tabidir.
- Çok uzun işlemlerde sistem `Service.onTimeout()` çağırabilir; `MediaProcessingService` bu durumda aktif işi güvenli biçimde durdurup geçici dosyaları temizler ve kullanıcıya `SERVICE_STOPPED` uyarısı gösterir.

## 7. Dosya Boyutu Tahminlerinin Yaklaşıklığı
- İşlem öncesi gösterilen tahmini çıktı boyutu ve tahmini süre (`JobEstimate`), seçili video/ses bitrate değeri, çözünürlük çarpanı ve kalite profili üzerinden hesaplanır. Değişken bitrate (VBR) kullanan donanım encoder'ları sahne hareketine bağlı olarak tahminden daha büyük veya daha küçük dosya üretebilir; bu nedenle arayüzde *"Bu bir tahmindir"* açıklaması gösterilir.

## 8. Depolama ve RAM Sınırları
- Çıktılar atomik güvenlik nedeniyle önce uygulama önbelleğinde oluşturulup ardından `MediaStore` / SAF hedefine taşınır. Bu nedenle işlem sırasında hedef çıktı boyutu kadar ek geçici boş alana ihtiyaç duyulur (`StatFs` ile ön kontrol yapılır).
- Çok yüksek megapikselli resimlerde `ImageEngine`, `BitmapFactory.Options.inSampleSize` ile güvenli ölçekleme yaparak `OutOfMemoryError` oluşmasını engeller.

