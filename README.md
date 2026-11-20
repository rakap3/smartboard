# Papan Guru & Edu Launcher

Papan tulis digital untuk layar interaktif Android. Guru bisa menulis dengan jari atau pena di papan tanpa tepi, memakai banyak halaman, menyisipkan gambar dan PDF, lalu mengekspor hasilnya. Semua papan tersimpan otomatis di perangkat dan aplikasi berjalan tanpa koneksi jaringan.

Repo ini berisi dua aplikasi Android:

- **Papan Guru** (`app/`): papan tulis digital. Dibuka dari ikonnya dan langsung masuk ke papan terakhir.
- **Edu Launcher** (`launcher/`): layar utama (Home) untuk layar interaktif kelas. Isinya jam dan tanggal, tombol besar untuk papan tulis baru, melanjutkan papan terakhir, dan membuka PDF (papan baru diberi nama sesuai berkas). Di bawahnya ada deretan papan terbaru beserta gambar kecilnya, serta akses cepat ke Papan Guru, browser, berkas, pengaturan, dan semua aplikasi. Setelah dipasang, pilih Edu Launcher sebagai aplikasi layar utama di pengaturan Android (atau lewat ikon pengaturan di Edu Launcher).

Edu Launcher membaca daftar papan dan membuka Papan Guru lewat izin bertanda tangan. Karena itu, **kedua APK harus ditandatangani dengan kunci yang sama**. Kalau berbeda, Edu Launcher tetap berjalan, tetapi tidak bisa menampilkan atau membuka papan. Kode bersama (tema UI dan ikon) ada di modul `core/`.

## Fitur Papan Guru

- **Alat tulis.** Pena, stabilo transparan, penghapus, bentuk (garis, panah, persegi, lingkaran, segitiga), teks, alat pilih, dan geser papan. Ada 10 warna dan beberapa pilihan ketebalan. Ketuk lagi alat yang sedang aktif untuk membuka pilihannya.
- **Penghapus.** Bisa menghapus sebagian goresan atau objek utuh. Telapak tangan yang menyentuh layar otomatis menjadi penghapus (bisa dimatikan dari pilihan penghapus). Ujung penghapus pada pena stylus juga langsung menghapus.
- **Pilih dan ubah.** Lingkari objek dengan alat pilih, atau ketuk satu objek. Objek terpilih bisa digeser, diubah ukurannya lewat titik di pojok kanan bawah, diganti warnanya, dibawa ke depan, diduplikat, atau dihapus.
- **Urungkan dan ulangi** untuk setiap perubahan, termasuk hapus, geser, dan ubah ukuran.
- **Papan tanpa batas.** Geser dengan dua jari dan cubit untuk zoom antara 1% dan 10.000%. Tombol di kanan atas mengatur zoom, menampilkan semua isi, dan mengembalikan tampilan awal.
- **Halaman.** Tambah, pindah, duplikat, geser urutan, dan hapus halaman. Ketuk nomor halaman di toolbar untuk melihat semua halaman.
- **Latar papan.** Hitam, hijau, atau putih, dengan pola polos, titik, kotak, atau garis. Latar bisa diterapkan ke satu halaman atau semua halaman.
- **Sisipkan.** Gambar dari perangkat bisa disisipkan, lalu dipindah dan diubah ukurannya. File PDF diimpor menjadi halaman papan (maksimal 100 halaman) yang bisa langsung dicoret.
- **Ekspor.** Semua halaman bisa diekspor ke PDF, atau halaman aktif ke PNG. Tangkapan area bisa disimpan sebagai PNG atau dibagikan.
- **Papan saya.** Buat, buka, ganti nama, dan hapus papan. Papan tersimpan otomatis dan dibuka lagi saat aplikasi dijalankan.
- **Menu cepat.** Ketuk dua kali dengan satu jari untuk membuka menu melingkar tepat di titik ketukan. Isinya pena, stabilo, penghapus, dan pilih, ditambah urungkan, ulangi, tangkap area, dan tampilkan semua isi. Lingkaran tengah menunjukkan warna aktif dan bisa diketuk untuk menggantinya. Ketuk di luar menu untuk menutup.

### Alat kelas

Buka lewat ikon kotak alat di toolbar.

- **Penggaris, segitiga siku, dan busur derajat.** Alat ini melayang di atas papan dengan skala cm sungguhan. Tarik pena di sepanjang tepinya untuk menggambar garis lurus atau busur yang rapi. Geser alat dengan satu jari. Putar dengan dua jari atau dengan tombol putar, dan sudutnya otomatis menempel di kelipatan 15°. Tutup lewat tombol ×.
- **Jangka.** Tekan titik pusat, lalu tarik ke luar untuk menentukan jari-jari. Lepas untuk mendapat lingkaran penuh, atau lanjutkan memutar untuk menggambar busur.
- **Timer dan stopwatch** dengan pilihan 1 sampai 15 menit. Bunyi dan tampilan berkedip saat waktu habis.
- **Pengundi nama** dari daftar siswa. Ada pilihan tanpa ulang sampai semua nama terpilih.
- **Dadu** (1 sampai 4 buah) dan **kalkulator** dengan koma desimal.
- **Tirai** untuk menutup sebagian papan dari atas atau kiri, lalu membukanya sedikit demi sedikit.
- **Lampu sorot** yang menggelapkan papan kecuali satu lingkaran. Area di dalam lingkaran tetap bisa ditulisi.
- **Tulis bersama.** Setiap jari menulis goresannya sendiri, jadi beberapa siswa bisa menulis bersamaan. Selama mode ini aktif, zoom dan geser memakai alat geser.

Kartu timer, pengundi nama, dadu, dan kalkulator bisa dipindah dengan menyeret judulnya.

## Membuat APK

Gunakan JDK 17, lalu pasang Android SDK Platform 35 dan Build Tools 35.0.0. Jika SDK tidak ada di lokasi bawaan, buat `local.properties` berisi `sdk.dir=/path/ke/sdk`. Jalankan `./gradlew :app:assembleDebug :launcher:assembleDebug` dari root proyek. Hasilnya ada di `app/build/outputs/apk/debug/app-debug.apk` (Papan Guru) dan `launcher/build/outputs/apk/debug/launcher-debug.apk` (Edu Launcher). Tambahkan `-PdevInstall` untuk versi uji (`.dev`) yang bisa dipasang berdampingan dengan versi biasa.

Unit test untuk model papan dan format simpan dijalankan dengan `./gradlew :app:testDebugUnitTest`.

## Uji di layar interaktif

1. Pasang APK debug. Jika ADB sudah terhubung, jalankan `adb install -r artifacts/papan-guru-debug.apk` dan `adb install -r artifacts/edu-launcher-debug.apk`.
2. Pasang juga Edu Launcher dan pilih sebagai aplikasi layar utama. Tekan tombol Home dan pastikan Edu Launcher tampil rapi. Buat papan baru dari Edu Launcher, tekan Home, lalu buka lagi papan itu lewat "Lanjutkan" dan deretan papan terbaru. Coba "Buka PDF" dan "Semua aplikasi".
3. Tulis dengan jari dan pena memakai beberapa warna dan ketebalan. Pastikan garis mengikuti posisi input dan tidak terasa tertunda walaupun papan sudah penuh.
4. Coba stabilo, lalu kedua mode penghapus. Tempelkan telapak tangan untuk menghapus.
5. Gambar setiap bentuk dan tambahkan teks. Ketuk teks dengan alat teks untuk mengubahnya.
6. Dengan alat pilih, lingkari beberapa objek. Geser, ubah ukuran, ganti warna, duplikat, lalu hapus. Urungkan dan ulangi setiap langkah.
7. Tambah halaman, ganti latar, sisipkan gambar, dan impor PDF. Coret di atas halaman PDF.
8. Ekspor ke PDF dan PNG, lalu buka hasilnya di aplikasi lain.
9. Dari menu alat kelas, tampilkan penggaris. Tarik garis di tepinya, putar dengan dua jari, lalu ulangi dengan busur derajat dan jangka.
10. Jalankan timer 1 menit sampai bunyi. Isi daftar nama lalu acak beberapa kali. Coba dadu, kalkulator, tirai, lampu sorot, dan tulis bersama dengan dua orang.
11. Tutup aplikasi sepenuhnya, buka lagi, dan pastikan papan beserta semua halamannya kembali utuh.
