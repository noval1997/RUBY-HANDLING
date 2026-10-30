# RUBY HANDLING — Android source v1.2

Nama aplikasi di HP: **RUBY HANDLING**  
Nama proyek Android: **RubyHandling**  
Nama file APK hasil GitHub Actions: **RubyHandling.apk** (build lokal Android Studio menghasilkan `app-debug.apk`)  
Application ID: `com.rubyhandling.app`

## Fitur
- Pemesanan travel terpisah: **Kedatangan** dan **Kepulangan**.
- Nama travel, kontak, maskapai/nomor penerbangan, tanggal, jumlah jamaah, dan jumlah koper.
- Biaya handling diisi manual.
- Mata uang **SAR** atau **IDR**; ringkasan kedua mata uang selalu terpisah.
- Status pembayaran otomatis: Belum bayar, DP/Sebagian, atau Lunas.
- Tambah pembayaran bertahap, riwayat pembayaran, edit dan hapus pemesanan.
- Penyimpanan lokal offline dengan Room.

## Membuat APK
**Build lokal:** buka folder proyek ini di Android Studio dengan JDK 17 dan Android SDK 35, lalu pilih **Build > Build Bundle(s) / APK(s) > Build APK(s)**. Hasil debug ada di `app/build/outputs/apk/debug/app-debug.apk`.

**Build dari HP Android melalui GitHub Actions:**
1. Ekstrak ZIP ini di HP.
2. Buat repository baru di GitHub dan unggah seluruh isi folder proyek ke repository (bukan ZIP-nya).
3. Buka tab **Actions**, izinkan Actions jika diminta, lalu pilih workflow **Build Android APK**.
4. Tekan **Run workflow** dan tunggu sampai selesai.
5. Buka hasil run yang sukses, unduh artifact **RubyHandling-apk**, lalu ekstrak `RubyHandling.apk` dan pasang di HP.

Workflow sudah disertakan pada `.github/workflows/build-apk.yml`. Build APK belum dapat dijalankan atau diverifikasi di lingkungan ini karena Android SDK/Gradle tidak tersedia. File ini adalah source project, bukan APK siap instal.

## Catatan
Aplikasi menyimpan data secara lokal/offline pada perangkat. Belum tersedia login multi-petugas, sinkronisasi cloud, unggah bukti transfer, ekspor PDF/Excel, dan backup otomatis.
