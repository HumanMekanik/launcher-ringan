# Launcher Ringan v1.2 (gaya Apple TV) untuk Google TV / Android TV

Tanpa iklan, tanpa rekomendasi konten, tanpa internet, tanpa fitur premium.

Tampilan:
- Wallpaper gradasi (6 pilihan) yang berganti pelan tiap 1 menit dengan efek memudar.
- Jam besar, sapaan (pagi/siang/sore/malam), dan tanggal dalam bahasa Indonesia.
- Jadwal sholat Yogyakarta (kriteria Muhammadiyah) berupa 5 kartu kaca. Waktu berikutnya disorot putih,
  lengkap dengan hitung mundur di kanan atas.
- Baris "Favorit": kartu besar 16:9 memakai gambar banner aplikasi TV (kalau aplikasinya punya).
- Baris "Semua Aplikasi": kotak ikon berwarna sesuai ikonnya, tersusun beberapa baris.
- Kartu yang difokus membesar, berbingkai putih, dan namanya menyala.

Kontrol remote:
- OK: buka aplikasi.
- Tahan OK: menu (Tambahkan/Hapus favorit, Pindahkan ke awal favorit, Info aplikasi, Sembunyikan).
- Back atau Home: kembali ke atas.

Favorit awal: Netflix, YouTube, Disney+, Prime Video, Spotify, YouTube Music, dan Play Store,
hanya yang terpasang di TV-mu. Sisanya kamu atur sendiri lewat tahan OK.

PENTING: kode ini belum pernah dikompilasi oleh pembuatnya (tidak ada Android SDK di tempat pembuatannya).
Versi sebelumnya sudah terbukti jalan di TV-mu, tapi versi ini tampilannya ditulis ulang.
Kalau build error atau tampilan di TV berantakan, kirim teks errornya atau foto layar TV ke Claude.

---

## Memperbarui dari versi sebelumnya

Versi ini memakai tanda tangan (kunci) tetap, sedangkan versi lama ditandatangani kunci acak dari GitHub.
Karena itu pemasangan pertama versi ini bisa ditolak. Caranya:

1. Coba dulu seperti biasa:
   ```
   adb install -r app-debug.apk
   ```
   Kalau muncul `Success`, selesai.

2. Kalau muncul `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (tanda tangan beda), lakukan berurutan:
   ```
   adb shell pm enable com.google.android.apps.tv.launcherx
   adb uninstall id.oprek.tvlauncher
   adb install app-debug.apk
   ```
   Baris pertama mengaktifkan lagi Google TV Home supaya tombol Home tidak kosong selama penggantian.
   Setelah `Success`, tekan Home, pilih "Launcher Ringan", lalu "Selalu".
   Kalau mau Google TV Home dimatikan lagi:
   ```
   adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
   ```
   Favorit dan daftar sembunyi akan kembali ke awal (wajar, karena aplikasinya dicopot).
   Mulai versi ini dan seterusnya, `adb install -r` langsung bisa menimpa tanpa langkah ini.

Kalau perintah `adb` bilang `no devices`, sambungkan dulu:
`adb connect IP_TV:PORT` (port dari layar utama Wireless debugging), lalu cek dengan `adb devices`.

---

## Membangun APK

### Cara 1: GitHub (tanpa memasang apa pun di laptop)

1. Buka repositori yang kemarin. Hapus isi lama atau buat repositori baru.
2. Unggah SEMUA isi folder `tv-launcher` (termasuk `.github`, `app`, `gradle`, dan file `app/launcher.keystore`).
   Kalau folder `.github` tidak ikut terseret, buat manual lewat Add file > Create new file
   dengan nama `.github/workflows/build.yml`, lalu tempel isi file itu.
3. Buka tab Actions > "Build APK" > Run workflow.
4. Tunggu 3-5 menit sampai centang hijau. Buka run itu, unduh `launcher-ringan-apk` dari bagian Artifacts,
   ekstrak ZIP-nya, dan kamu mendapat `app-debug.apk`.

### Cara 2: Android Studio
File > Open folder `tv-launcher`, tunggu sync, lalu Build > Build APK(s).
APK ada di `app/build/outputs/apk/debug/app-debug.apk`.

---

## Soal jadwal sholat

Tidak ada API publik resmi dari Muhammadiyah, jadi jadwal dihitung langsung di TV (`PrayerTimes.java`)
memakai kriteria Majelis Tarjih dan Tajdid PP Muhammadiyah untuk Yogyakarta
(lintang -07 47' 48", bujur 110 21' 50" BT, WIB):

- Subuh dan Isya: tinggi matahari -18 derajat
- Magrib: tinggi matahari sekitar -1,19 derajat
- Asar: bayangan = benda + bayangan tengah hari
- Ihtiyat 1 menit, dibulatkan ke menit berikutnya

Dikalibrasi terhadap tabel imsakiyah 1447 H Yogyakarta terbitan Majelis Tarjih (18 Feb - 19 Mar 2026):
149 dari 150 waktu persis sama, sisanya selisih 1 menit. Di luar rentang itu rumusnya sama,
tapi belum ada tabel resmi lain untuk dibandingkan. Bandingkan lagi dengan tabel imsakiyah Ramadan berikutnya.
Untuk ganti kota, ubah `LATITUDE` dan `LONGITUDE` di `PrayerTimes.java`.

---

## Tentang kunci tanda tangan

`app/launcher.keystore` adalah kunci pribadi untuk aplikasi ini saja (kata sandi: `android`).
Fungsinya hanya supaya update berikutnya bisa menimpa versi lama. Jangan dipakai untuk aplikasi lain,
dan kalau kamu mengunggah proyek ini ke repositori publik, ingat bahwa kunci itu ikut terlihat.
Untuk dipakai di TV sendiri, itu tidak masalah.

---

## Kembali ke semula kapan saja

```
adb shell pm enable com.google.android.apps.tv.launcherx
adb uninstall id.oprek.tvlauncher
```
