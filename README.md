# Metro İstanbul

İstanbul'un raylı sistem hatları ve metrobüsüyle oynanan, Mini Metro tarzı hat çizme oyunu.

**Oyna:** https://maelstoorm.github.io/metro-game/

iPhone: Safari'de aç, Paylaş → "Ana Ekrana Ekle". Bir kez açıldıktan sonra internetsiz de çalışır.

Android: [MetroIstanbul.apk](https://maelstoorm.github.io/metro-game/apk/MetroIstanbul.apk) dosyasını indir ve kur (sürüm 3.9). Telefon "bilinmeyen kaynak" uyarısı verirse bu kaynağa izin ver.

## Android (Google Play) derlemesi

`android/` klasörü oyunu tam ekran bir WebView içinde açan Android uygulamasıdır (paket `io.github.maelstoorm.metroistanbul`, sürüm 3.9, versionCode 40). Derleme sırasında kökteki `index.html` uygulamaya kopyalanır; yazı tipleri uygulamanın içindedir, oyun internetsiz de açılır. Durak anonsları telefonun metin okuma motoruyla okunur, "Reklam izle, kaldığın yerden devam et" Google AdMob ödüllü reklamıyla çalışır (gerekirse önce Google'ın izin penceresi çıkar).

- `main` dalına her gönderimde GitHub Actions imzasız bir AAB derler ve `builds` dalına `metro-istanbul-unsigned.aab` olarak koyar (`commit.txt` hangi commit'ten derlendiğini yazar). Derleme bozulursa günlük aynı dala `build-log.txt` olarak düşer.
- AAB imzasızdır: Play Console'a yüklemeden önce kendi yükleme anahtarınla imzala (`jarsigner` ya da Android Studio). Anahtar ve parolalar depoya konmaz.
- AdMob kimlikleri kökteki `admob.properties` dosyasındadır (`appId=`, `rewardedId=`). Boşken Google'ın test reklamları görünür, gelir getirmez.
- Bilgisayarda derlemek için: `cd android && ./gradlew bundleRelease` (JDK 17 ve Android SDK gerekir).

Harita verisi © OpenStreetMap katkıcıları (ODbL). Yazı tipleri: Barlow ve Barlow Condensed (SIL Open Font License 1.1).

## Sahiplik

Bu oyun **Maelstrom Studio'ya (Egemen)** aittir. © 2026, tüm hakları saklıdır.
Kod, tasarım, görseller ve sesler izinsiz kopyalanamaz, değiştirilemez ya da dağıtılamaz. Ayrıntılar için [LICENSE](LICENSE) dosyasına bak.
