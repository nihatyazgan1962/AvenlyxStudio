# Desteklenen ve Desteklenmeyen Formatlar (`SUPPORTED_FORMATS`)

**AVENLYX Studio (Android Medya Stüdyosu)**, yalnızca cihazdaki gerçek Android SDK ve AndroidX Media3 `1.5.1` bileşenleriyle çalışır. Desteklenen çıktı formatları, `EngineCapabilityRegistry` tarafından `MediaCodecList(MediaCodecList.ALL_CODECS)` taranarak dinamik biçimde doğrulanır.

---

## 1. Video Formatları (`Media3Engine` — Media3 Transformer + MediaCodec)

### Giriş (Okuma / Decode)
Cihazdaki platform decoder'larına bağlı olarak aşağıdaki kapsayıcılar ve akışlar okunabilir:
- **Kapsayıcılar:** `MP4`, `M4V`, `MOV`, `MKV`, `WebM`, `3GP`, `MPEG-TS`
- **Video Kodekleri:** `H.264 (AVC)`, `H.265 (HEVC)`, `VP8`, `VP9`, `AV1` (cihaz donanım/yazılım decoder desteğine bağlı), `MPEG-4`

### Çıkış (Yazma / Encode)
| Format / Kodek | Durum | Açıklama |
| --- | --- | --- |
| **MP4 (H.264 / AVC + AAC)** | ✅ **Her Cihazda Desteklenir** | Tüm telefon, TV ve tarayıcılarla en yüksek uyumluluk. Varsayılan çıktı formatıdır. |
| **MP4 (H.265 / HEVC + AAC)** | ⚠️ **Cihaza Bağlı** | Yalnızca cihazda `video/hevc` encoder bulunduğunda etkinleşir. Daha küçük dosya boyutu sunar. |
| **WebM (VP9 + Opus)** | ⚠️ **Cihaza Bağlı** | Yalnızca Android 10+ (API 29+) cihazlarda `video/x-vnd.on2.vp9` ve `audio/opus` kodlayıcıları birlikte tespit edilirse iki aşamalı `MediaCodec` + `MediaMuxer` hattıyla açılır. |
| **MP4 (AV1)** | ⚠️ **Cihaza Bağlı** | Yalnızca Android 10+ / AV1 encoder (`video/av01`) donanım/yazılım desteği olan cihazlarda açılır. |
| **AVI / FLV / WMV / ProRes Çıktısı** | ❌ **Desteklenmez** | Media3 `Transformer` ve `MediaMuxer` bu kapsayıcıları muxing için desteklemez. |
| **Animasyonlu GIF Çıktısı** | ❌ **Desteklenmez** | Harici native FFmpeg NDK kütüphanesi bulunmadığı için doğrudan videodan animasyonlu GIF üretilmez. |

---

## 2. Ses Formatları (`AndroidPlatformEngine` & `Media3Engine`)

### Giriş (Okuma / Decode)
- `M4A / AAC`, `MP3`, `WAV (PCM)`, `FLAC`, `OGG / OPUS / Vorbis`, `AMR`, `3GP` ve video kapsayıcılarının ses kanalları (`MP4`, `MKV`, `MOV`, `WebM`).

### Çıkış (Yazma / Encode)
| Format / Kodek | Durum | Motor ve Açıklama |
| --- | --- | --- |
| **WAV (PCM 16-bit)** | ✅ **Her Cihazda Garantili** | `AndroidPlatformEngine` içindeki `MediaCodec` PCM decode + yerleşik RIFF/WAV header yazıcısı ile kayıpsız çıktı. Ses kesme, birleştirme, miksleme, fade-in/out ve video ses çıkarma işlemlerini destekler. |
| **M4A (AAC-LC)** | ✅ **Her Cihazda Desteklenir** | `MediaCodec` (`audio/mp4a-latm`) + `MediaMuxer` veya `Media3Engine` üzerinden yüksek uyumlu sıkıştırılmış çıktı. |
| **OPUS** | ⚠️ **Cihaza Bağlı** | Yalnızca Android 10+ (API 29+) cihazlarda `MediaCodecList` üzerinde `audio/opus` encoder tespit edildiğinde açılır. |
| **FLAC** | ⚠️ **Cihaza Bağlı** | Yalnızca `MediaCodecList` üzerinde `audio/flac` encoder tespit edildiğinde açılır (birçok cihaz yalnızca FLAC decoder içerir). |
| **MP3 Çıktısı** | ❌ **Desteklenmez** | Android SDK yerel olarak MP3 encoder (`LAME`) sağlamaz. MP3 dosyaları okunup **M4A** veya **WAV** formatına dönüştürülebilir. |
| **OGG Vorbis / AC3 / WMA / AIFF Çıktısı** | ❌ **Desteklenmez** | Android yerel `MediaMuxer` / `MediaCodec` bu formatlar için evrensel encoder sunmaz. |

---

## 3. Resim Formatları (`ImageEngine` — `ImageDecoder` / `BitmapFactory` / `Canvas`)

### Giriş (Okuma)
- `JPG / JPEG`, `PNG` (alfa kanalı dahil), `WebP`, `BMP`, `HEIC / HEIF` (Android 9+ / cihaz desteğine bağlı), `GIF` (ilk kare statik görsel olarak okunur).

### Çıkış (Yazma)
| Format | Durum | Açıklama |
| --- | --- | --- |
| **JPG / JPEG** | ✅ **Desteklenir** | Kalite slider (`%1-100`), çözünürlük ölçekleme, döndürme/çevirme, filigran ve şeffaf PNG'ler için arka plan rengi doldurma + EXIF temizleme desteği. |
| **PNG** | ✅ **Desteklenir** | Kayıpsız çıktı ve alfa şeffaflığı koruma. |
| **WebP** | ✅ **Desteklenir** | Kalite %100'de kayıpsız (`WEBP_LOSSLESS`), diğer değerlerde kayıplı (`WEBP_LOSSY`) sıkıştırma. |
| **BMP** | ✅ **Desteklenir** | Yerleşik 24-bit/32-bit Windows BMP piksel/header kodlayıcı ile doğrudan çıktı. |
| **Fotoğraflardan MP4 Slayt Videosu** | ✅ **Desteklenir** | Çoklu görselden kare süresi, en-boy oranı (`16:9`, `9:16`, `1:1`) ve opsiyonel arka plan müziği ile MP4 video dışa aktarımı (`Media3Engine`). |
| **AVIF / TIFF / PDF / SVG Çıktısı** | ❌ **Desteklenmez** | Bu sürümde `ImageEngine` yalnızca JPG, PNG, WebP ve BMP çıktısı üretir. |

---

## 4. Uzantı ve Gerçek İçerik Kontrolü

`MediaInspector`, dosya uzantısı ile gerçek konteyner/MIME yapısını (`ContentResolver`, `MediaExtractor`, `BitmapFactory`) karşılaştırır. Örneğin `.mp4` uzantılı gerçekte bir `.png` veya bozuk dosya tespit edilirse `isExtensionMismatch = true` olarak işaretlenir ve dönüştürme öncesi kullanıcıya anlaşılır Türkçe uyarı gösterilir.

