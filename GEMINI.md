# AVENLYX Studio — Proje Kuralları ve Mimarisi

Kullanıcı kuralı gereğince bu ve sonraki tüm projelerde aşağıdaki düzen esastır:

1. **`desktop/`**: Masaüstü uygulaması kaynak kodları, build yapılandırmaları ve masaüstüne özel bağımlılıklar.
2. **`mobile/`**: Mobil uygulama kaynak kodları ve projeleri.
3. **`web/`**: Web uygulaması bileşenleri (oluşturulduğunda).
4. **`dist/`**: Dağıtım ve nihai derleme çıktıları:
   - `dist/desktop/`: Masaüstü uygulamasının çalıştırılabilir, bağımlılıkları tamamlanmış nihai paketi (`AvenlyxStudio.exe`, DLL'ler, `baslat.bat`).
   - `dist/mobile/`: Mobil uygulamanın derlenmiş APK / paket çıktıları.
5. **`README.md`**: Proje kök dizininde tüm detayları açıklayan merkezi rehber.

