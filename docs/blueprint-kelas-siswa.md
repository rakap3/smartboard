# Blueprint: Kelas Interaktif (WebSocket + Web Siswa)

Versi draf 3 · 20 November 2026 · Status: usulan, belum diimplementasikan

Dokumen ini adalah rancangan lengkap untuk menghubungkan **Papan Guru** (aplikasi Android di layar interaktif) dengan **perangkat siswa** lewat server sinkronisasi. Dokumen ini melengkapi *Panduan Teknis Penyiapan Server Kelas Interaktif* (18 November 2026). Panduan itu menjelaskan infrastruktur, sedangkan blueprint ini menjelaskan apa yang dibangun: alur kelas, protokol, data, backend, web siswa, dan perubahan di aplikasi guru.

**Perubahan dari draf 1:**
- Siswa **tidak menulis di papan bersama**. Siswa mengerjakan **lembar tugas** masing-masing, guru memantau semuanya, lalu memanggil siswa **satu per satu** untuk presentasi.
- **Guru menyetujui setiap siswa** yang masuk.
- Server uji coba **memakai alamat IP** (lihat §11).
- Keputusan soal teknologi WebSocket ada di §5.1.

**Perubahan di draf 3:**
- Server pilot di **`http://157.15.165.36:8001`**, tanpa TLS (§11).
- Presentasi **hanya ditampilkan di smartboard**.
- Siswa mendapat **alat lengkap**: pena, stabilo, penghapus, bentuk, teks, pilih/geser, serta urungkan dan ulangi.

---

## 1. Alur kelas

```
 1. Guru: Papan Guru → "Mulai kelas"  → layar guru menampilkan kode + QR
 2. Siswa: pindai QR, isi nama          → status "Menunggu persetujuan guru"
 3. Guru: setujui siswa (satu-satu / semua)  → siswa masuk, melihat papan guru
 4. Guru: pilih halaman → "Bagikan sebagai lembar tugas"
          → setiap siswa menerima SALINAN SENDIRI dari halaman itu
 5. Siswa: bebas menulis di lembarnya (pena, stabilo, penghapus, urungkan)
          → siswa lain TIDAK bisa melihat lembarnya
 6. Guru: layar "Pantau" menampilkan semua lembar siswa secara langsung
          (kisi gambar kecil; ketuk satu untuk melihat besar dan live)
 7. Guru: "Kunci lembar" saat waktu habis → siswa tidak bisa mengubah lagi
 8. Guru: pilih satu siswa → "Presentasi"
          → lembar siswa itu tampil besar di smartboard (perangkat siswa lain tidak ikut menampilkan)
          → hanya siswa itu yang boleh menulis lagi di lembarnya, sambil menjelaskan
          → guru boleh ikut mencoret di lembar tersebut
 9. Guru: "Selesai" → pilih siswa berikutnya (hanya satu presenter dalam satu waktu)
10. Guru: "Simpan hasil" → semua lembar disimpan sebagai PDF di perangkat guru
11. Guru: "Akhiri kelas"
```

**Aturan inti:**
- **Papan guru:** hanya guru yang menulis. Siswa melihatnya secara langsung.
- **Lembar tugas:** setiap siswa menulis di lembarnya sendiri, bersamaan, tanpa saling melihat. Guru bisa melihat dan menulis di semua lembar.
- **Presentasi:** maksimal **satu** siswa dalam satu waktu. Tidak ada siswa yang menulis di papan bersama.
- Siswa yang belum disetujui guru **tidak menerima isi kelas apa pun**.

### Di luar cakupan pilot
Video dan suara, kuis otomatis, penilaian, akun guru atau siswa yang lengkap, mode ujian (mengunci perangkat), dan mode tanpa internet (LAN). Rancangan ini tidak menutup kemungkinan fitur-fitur tersebut ditambahkan nanti (lihat §13).

### Keputusan yang sudah diambil
| Topik | Keputusan |
|---|---|
| Aplikasi siswa | **Web/PWA** (TypeScript, tanpa framework) |
| Aplikasi guru | Tetap **native** (Papan Guru) |
| Server | Sesuai Panduan: **Node.js 24 + TypeScript + `ws` + MySQL 8.4**, di belakang **Caddy** |
| Klien WebSocket di aplikasi guru | **OkHttp** (§5.1) |
| Menulis bersamaan | **Tidak ada** di papan bersama. Siswa menulis di lembar masing-masing, dan presentasi satu per satu |
| Masuk kelas | **Harus disetujui guru** |
| Alamat server pilot | **`http://157.15.165.36:8001`**, HTTP biasa tanpa TLS (§11) |
| Presentasi | **Hanya di smartboard** |
| Alat siswa di lembar | **Lengkap**: pena, stabilo, penghapus, bentuk, teks, pilih/geser, urungkan/ulangi |

---

## 2. Arsitektur

```
 Layar guru (Android, Papan Guru)        Perangkat siswa (browser)
        │  WS + HTTP                              │  WS + HTTP
        └──────────────┬──────────────────────────┘
                       ▼
           Caddy (:8001, tanpa TLS)  —  http://157.15.165.36:8001
           ┌───────────┴────────────┐
           │ /api/*, /ws            │ /  (file statis web siswa)
           ▼                        ▼
  Backend Node.js (127.0.0.1:3000)   web-siswa/dist
     │  state sesi di memori (papan + semua lembar), antrean per sesi
     ▼
  MySQL 8.4 (sesi, anggota, lembar tugas, log operasi, snapshot, aset)
```

**Struktur repositori:**
```
app/         Aplikasi guru Android (sudah ada)
backend/     Server Node.js + TypeScript
web-siswa/   Web siswa (Vite + TypeScript)
protocol/    Definisi pesan bersama: tipe TS, JSON Schema, fixtures
docs/        Dokumen ini dan Panduan
```

---

## 3. Model data

Format item sama dengan `BoardCodec` di aplikasi guru (`app/.../BoardStore.kt`). Dengan begitu, satu definisi item berlaku untuk penyimpanan lokal, jaringan, dan web siswa.

### 3.1 Dokumen dalam satu sesi
Setiap operasi menyebut **dokumen** (`doc`) yang diubahnya:

| `doc` | Isi | Yang boleh menulis | Yang boleh melihat |
|---|---|---|---|
| `board` | Papan guru: semua halaman | guru | semua anggota yang disetujui |
| `sheet:<assignmentId>:<memberId>` | Lembar tugas milik satu siswa | siswa pemiliknya (kalau tidak dikunci, atau sedang presentasi) dan guru | **hanya** siswa pemiliknya dan guru. Presentasi hanya tampil di smartboard |

### 3.2 Lembar tugas = templat + lapisan siswa
- Saat dibagikan, halaman guru dibekukan menjadi **templat** (`assignment.pages[]`). Isinya item papan plus aset gambar/PDF, dan disimpan sekali saja.
- Setiap lembar siswa hanya menyimpan **lapisan miliknya sendiri** (`items[]` per halaman templat).
- Saat ditampilkan, urutannya: latar, item templat (tidak bisa dihapus atau dipilih siswa), lalu item siswa.
- Dengan begitu, 40 lembar tidak menyalin isi templat 40 kali, dan penghapus atau urungkan milik siswa tidak bisa merusak soal.
- Satu tugas bisa terdiri dari beberapa halaman. Misalnya, beberapa halaman PDF dibagikan sekaligus.

### 3.3 Koordinat dan bingkai acuan
- Semua koordinat memakai **koordinat papan**. Di Papan Guru, saat ini 1 unit papan = 1 piksel layar guru pada zoom 100% (`BoardViewport.homeFrame()`).
- Setiap sesi menyimpan **`frame = {w, h}`**, yaitu ukuran home frame layar guru saat kelas dimulai.
- **Web siswa** menampilkan papan atau lembar dengan skala `s = min(lebarLayar / frame.w, tinggiLayar / frame.h)`. Input siswa diubah dulu ke koordinat papan sebelum dikirim: `bx = (sx − offsetX) / s`.
- Tebal goresan dan ukuran teks juga dalam unit papan, jadi tampilan dan posisi tulisan **sama di semua perangkat**, sesuai Panduan §2.
- **Lembar tugas di HP:** siswa boleh mencubit untuk zoom dan menggeser dua jari di lembarnya sendiri. Ini hanya tampilan lokal. Untuk papan guru, tampilan selalu pas layar.

### 3.4 Item (sama dengan `BoardCodec`)
| `t` | Field | Catatan |
|---|---|---|
| `ink` | `id, c, w, p, h?` | `p` = string `"x,y,…"` dibulatkan 0,1. `h:true` = stabilo |
| `shape` | `id, k, c, w, x1, y1, x2, y2` | `k` ∈ `LINE, ARROW, RECT, ELLIPSE, TRIANGLE` |
| `text` | `id, s, c, z, x, y, mw, mh` | |
| `img` | `id, f, x, y, w, h, l?` | `f` = nama aset (`SAFE_FILE`) |

- **Warna `c`** adalah ARGB 32-bit bertanda. Web mengubahnya dengan `c >>> 0`.
- **Tinta adaptif** sama dengan `BoardRenderer.ink()`: putih ditampilkan hitam di latar terang, dan hitam palet ditampilkan putih di latar gelap.
- **Latar:** `color` ∈ `BLACKBOARD, GREEN, WHITE` dan `pattern` ∈ `NONE, DOTS, GRID, LINES`.

### 3.5 ID item dan ID halaman
- **ID item:** `id = slot × 2^32 + nomorLokal`. Slot 0 untuk guru, sehingga ID lama tetap berlaku. Slot 1 ke atas diberikan server ke setiap siswa yang disetujui. Rumus ini aman di JS selama `slot < 2^21`.
- **ID halaman:** setiap halaman diberi `pageId` string, karena indeks halaman bisa bergeser. Untuk itu, `BoardPage` di aplikasi guru perlu field `id` baru yang disimpan di `board.json`. Berkas lama diberi ID otomatis saat dimuat.

---

## 4. API HTTPS

Semua respons berupa JSON. Error memakai format `{ "error": { "code", "message" } }` dengan pesan dalam bahasa Indonesia.

| Metode & path | Akses | Fungsi |
|---|---|---|
| `GET /healthz`, `GET /readyz` | publik | Sesuai Panduan §2 |
| `POST /api/teacher/sessions` | `Bearer <kunciPerangkat>` | Body `{name, frame}`. Respons: `{sessionId, code, joinUrl, teacherToken, wsUrl}` |
| `POST /api/join` | publik, dibatasi per IP | Body `{code, name}`. Respons: `{sessionId, memberId, token, wsUrl, sessionName}`. Statusnya **`pending`** sampai guru menyetujui |
| `PUT /api/sessions/:id/assets/:name` | token guru | Unggah gambar atau halaman PDF (≤ 5 MB) |
| `GET /api/sessions/:id/assets/:name` | token anggota yang **sudah disetujui** | Unduh aset |
| `POST /api/sessions/:id/end` | token guru | Mengakhiri sesi |

**Identitas dan token:**
- **Guru:** kunci perangkat dibuat dengan skrip CLI di server, lalu dimasukkan sekali di pengaturan Papan Guru.
- **Siswa:** cukup nama tampilan (1–40 karakter).
- **Kode kelas:** 6 karakter dari alfabet tanpa huruf yang mirip (`ABCDEFGHJKMNPQRSTUVWXYZ23456789`).
- **Token:** 32 byte acak. Di database hanya hash SHA-256-nya yang disimpan. Token berlaku selama sesi, maksimal 12 jam, dan tidak pernah dicatat di log.
- **Link gabung:** `http://157.15.165.36:8001/k/ABC123`, ditampilkan sebagai QR.
- **Sambung ulang** memakai token yang sama, jadi **tidak perlu disetujui lagi**. Siswa yang membuka link lagi di perangkat lain akan tampil sebagai permintaan baru.

---

## 5. WebSocket

### 5.1 Pilihan teknologi
| Bagian | Pilihan | Alasan |
|---|---|---|
| Server | **`ws`** di Node.js (sesuai Panduan) | Ringan, matang, dan memberi kontrol penuh atas urutan dan batasan pesan. **Bukan Socket.IO**: Socket.IO punya protokol dan mekanisme sambung ulang sendiri yang tidak cocok dengan pemulihan berbasis `seq`, dan tidak bisa dipakai dari klien WebSocket biasa |
| Web siswa | **`WebSocket` bawaan browser** | Tidak perlu library. Didukung semua browser modern termasuk Safari di iPad |
| Aplikasi guru | **OkHttp** (`okhttp3.WebSocket`) | Sudah teruji di jutaan aplikasi Android, menangani TLS, ping otomatis, dan header `Authorization`. Ini satu-satunya dependensi runtime yang ditambahkan (sekitar 1 MB). Membuat klien sendiri memang mungkin, tapi koneksi kelas harus andal, dan bug di klien buatan sendiri sulit dilacak di lapangan |
| Format pesan | **JSON teks** | Mudah di-debug. Titik goresan sudah ringkas (`"x,y,…"`). Format biner dipertimbangkan hanya kalau uji beban menunjukkan perlu |
| Kompresi | **Mati** (`perMessageDeflate: false`) | Pesannya kecil dan sering, sehingga kompresi lebih banyak menambah latensi dan beban CPU daripada manfaatnya |

### 5.2 Koneksi dan autentikasi
- Endpoint pilot: `ws://157.15.165.36:8001/ws`. Setelah pindah ke HTTPS menjadi `wss://<host>/ws`.
- **Aplikasi guru:** token dikirim di header `Authorization: Bearer`, sesuai Panduan.
- **Browser** tidak bisa menambah header pada WebSocket, jadi token dikirim di pesan pertama `hello`, paling lambat 5 detik setelah tersambung. Kalau lewat dari itu, koneksi ditutup dengan kode `4408`. Server juga memeriksa header `Origin`.

### 5.3 Amplop pesan
```jsonc
// klien → server
{ "type": "stroke.end", "op": "m7-000123", "doc": "sheet:a1:7", ...data }
// server → klien
{ "type": "stroke.end", "seq": 418, "by": 7, "op": "m7-000123", "doc": "sheet:a1:7", ...data }
```
- **`op`:** ID operasi buatan klien yang unik per sesi. Pesan yang dikirim ulang dengan `op` yang sama tidak diproses dua kali.
- **`seq`:** nomor urut sesi dari server, naik terus, dan hanya untuk operasi yang mengubah keadaan.
  - Guru menerima semua operasi, jadi `seq`-nya berurutan tanpa celah.
  - Siswa hanya menerima operasi dari dokumen yang boleh ia lihat, jadi `seq` yang diterima siswa **boleh berlompatan**. `lastSeq` berarti "sudah diproses sampai nomor ini".
- **`by`:** `memberId` pengirim.

### 5.4 Pesan klien → server

**Umum**
| Tipe | Siapa | Data |
|---|---|---|
| `hello` | semua | `token?, lastSeq?, client:{app, version}` |
| `ping` | semua | `t` |

**Persetujuan masuk (guru)**
| Tipe | Data | Keterangan |
|---|---|---|
| `member.approve` | `members[]` | Menyetujui satu atau beberapa siswa. Siswa itu mendapat `welcome` dan slot |
| `member.reject` | `member` | Siswa ditolak, token dicabut, dan koneksi ditutup dengan kode `4403` |
| `member.kick` | `member` | Mengeluarkan siswa yang sudah masuk |

**Papan guru (`doc: "board"`, hanya guru)**
| Tipe | Data |
|---|---|
| `stroke.begin / stroke.points / stroke.end / stroke.cancel` | Lihat bagian Goresan di bawah |
| `item.add` / `item.update` / `item.remove` | `page, items[]` / `page, items[]` / `page, ids[]` |
| `page.replace` | `page, items[]`. Dipakai untuk urungkan/ulangi, kosongkan halaman, dan penghapus sebagian |
| `board.reset` | `frame, activePage, pages[]`. Seluruh papan, misalnya saat kelas dimulai, ganti papan, atau setelah koneksi pulih |
| `page.add / page.remove / page.move / page.style / page.show` | Seperti di aplikasi guru. `page.show` mengganti halaman yang dilihat siswa |

**Lembar tugas**
| Tipe | Siapa | Data | Keterangan |
|---|---|---|---|
| `assignment.start` | guru | `assignment:{id, title, pages:[{id, color, pattern, items}]}` | Membuat lembar kosong untuk setiap siswa yang sudah disetujui. Siswa yang disetujui belakangan langsung mendapat lembar juga |
| `assignment.lock` | guru | `assignment, locked` | Mengunci atau membuka pengisian untuk semua siswa |
| `assignment.close` | guru | `assignment` | Tugas selesai. Lembar tetap tersimpan dan bisa dilihat, tapi siswa kembali melihat papan guru |
| `sheet.done` | siswa | `doc, done` | Siswa menandai "Sudah selesai". Guru melihat tanda ✓ |
| `stroke.*`, `item.add`, `item.update`, `item.remove`, `layer.replace` | siswa pemilik, guru | `doc, page, …` | Siswa hanya bisa mengubah item **di lapisannya sendiri**. Item templat tidak pernah bisa diubah. `item.add` dipakai untuk bentuk dan teks, `item.update` untuk geser, ubah ukuran, dan ubah teks. `layer.replace` mengganti seluruh lapisan di satu halaman, dipakai untuk urungkan/ulangi dan penghapus sebagian |

**Pemantauan dan presentasi (guru)**
| Tipe | Data | Keterangan |
|---|---|---|
| `watch` | `docs[]` (maks. 2) | Guru berlangganan **goresan live** dari lembar yang sedang dibuka besar. Lembar lain di kisi hanya diperbarui saat goresan selesai (§6.3) |
| `present.start` | `member` | Satu presenter, dan lembarnya hanya tampil di smartboard. Kalau masih ada presenter lain, presentasinya dihentikan otomatis lebih dulu |
| `present.stop` | — | |

**Siswa**
| Tipe | Data |
|---|---|
| `hand.raise` | `up` |

**Goresan (berlaku untuk semua dokumen)**
| Tipe | Data | Keterangan |
|---|---|---|
| `stroke.begin` | `doc, stroke, page, c, w, h?, p` | Titik-titik pertama |
| `stroke.points` | `doc, stroke, p` | Maksimal 64 titik per pesan. Dikirim setiap ±16–33 ms |
| `stroke.end` | `doc, stroke, id` | **Server menyusun item `ink`** dari titik yang ia terima, lalu menyimpannya |
| `stroke.cancel` | `doc, stroke` | |

### 5.5 Pesan server → klien
| Tipe | Untuk | Isi |
|---|---|---|
| `waiting` | siswa pending | `sessionName` → tampilkan "Menunggu persetujuan guru" |
| `welcome` | anggota disetujui | `member:{id, role, name, slot}`, `session`, `state` |
| `state` | | `seq, frame, board:{activePage, pages[]}, assignment?:{id, title, pages[], locked}, mySheet?:{layers}, presenting?:{member, doc}`. Untuk guru ditambah `members[]` (lengkap dengan status pending), `sheets{}` (semua lapisan siswa), `hands[]`, dan `done[]` |
| `catchup` | | `ops[]` setelah `lastSeq`. Kalau sudah tidak tersedia, server mengirim `state` lengkap |
| `ack` / `reject` | pengirim | `op, seq` / `op, code, message` |
| *(operasi bernomor)* | sesuai hak lihat (§3.1) | `seq, by, op, doc, …` |
| `stroke.*` live | papan → semua; lembar → pemilik dan guru (kalau di-`watch` atau sedang presentasi) | Tanpa `seq`, kecuali `stroke.end` |
| `member.pending` | guru | `member:{id, name}` → muncul di daftar "Minta masuk" |
| `member.joined / member.left` | guru | Kehadiran dan koneksi |
| `pong`, `session.ended` | | |

**Kode penutupan:** `4000` sesi berakhir, `4401` token tidak sah, `4403` ditolak atau dikeluarkan, `4408` tidak ada `hello`, `4413` pesan terlalu besar, `4429` terlalu banyak pesan.

**Kode `reject`:** `NOT_ALLOWED`, `NOT_APPROVED`, `SHEET_LOCKED`, `BAD_MESSAGE`, `UNKNOWN_DOC`, `UNKNOWN_PAGE`, `TOO_LARGE`, `RATE_LIMITED`, `STROKE_REVOKED`.

### 5.6 Hak menulis (ringkas)
```
doc = board                 → hanya guru
doc = sheet:A:M (milik M)   → guru: selalu boleh
                              siswa M: boleh jika (tugas A belum dikunci DAN belum ditutup)
                                       ATAU (M sedang presentasi)
                              siswa lain: tidak pernah
```

### 5.7 Batasan dan validasi
- Ukuran frame maksimal 64 KB, maksimal 64 titik per `stroke.points`, dan 20.000 titik per goresan.
- Batas laju **60 pesan per detik** per koneksi, dengan lonjakan sesaat sampai 120.
- Angka harus terbatas dan dalam rentang ±1e6. Tebal garis 0,1–500. Teks maksimal 2.000 karakter.
- Ukuran tugas maksimal 20 halaman. Lapisan siswa maksimal 5.000 item per halaman.
- Semua bentuk pesan divalidasi dengan JSON Schema dari `protocol/` (ajv). Skema yang sama dipakai untuk tes di Kotlin dan web.

---

## 6. Perilaku server

### 6.1 Urutan dan transaksi
- Setiap sesi punya satu **antrean (actor)**. Setiap operasi yang mengubah keadaan diproses berurutan:
  1. validasi hak dan bentuk pesan,
  2. terapkan ke salinan state,
  3. simpan ke tabel `ops` dalam transaksi (`seq = last_seq + 1`, unik `(session_id, op_id)`),
  4. commit,
  5. ganti state di memori,
  6. kirim `ack`,
  7. siarkan ke anggota yang berhak melihat.
- **`stroke.begin` dan `stroke.points` tidak disimpan per pesan.** Pesan ini divalidasi lalu langsung diteruskan, dan baru disimpan sebagai item utuh pada `stroke.end`. Dengan 40 siswa menulis bersamaan, menyimpan setiap kumpulan titik berarti ribuan transaksi per detik. *Ini interpretasi dari Panduan §2 dan perlu disetujui (§14).*

### 6.2 Persetujuan masuk
- `POST /api/join` membuat anggota dengan status `pending`.
- Koneksi WebSocket siswa yang pending hanya menerima `waiting`. Guru menerima `member.pending`.
- Setelah `member.approve`, server memberi slot, mengubah status menjadi `approved`, lalu mengirim `welcome` ke siswa itu.
- Permintaan yang pending kedaluwarsa setelah 15 menit.
- Batas: maksimal 60 anggota per sesi, ditambah 20 permintaan pending.

### 6.3 Lalu lintas pemantauan (penting untuk performa layar guru)
Kalau 40 siswa menulis bersamaan dan setiap titik dikirim ke layar guru, layar guru bisa menerima lebih dari 1.000 pesan per detik. Untuk mencegahnya:
- **Kisi pantau** (semua lembar sebagai gambar kecil) hanya menerima **operasi bernomor**, yaitu `stroke.end` dan perubahan item. Gambar kecil diperbarui saat goresan selesai, dengan batas maksimal 2× per detik per lembar.
- **Goresan live** hanya dikirim untuk dokumen yang di-`watch` guru, yaitu lembar yang dibuka besar (maksimal 2), dan untuk lembar yang sedang dipresentasikan.

### 6.4 Presentasi
- `present.start` menyimpan presenter di state sebagai operasi bernomor, sehingga ikut pulih saat sambung ulang.
- Lembar presenter selalu boleh ditulis oleh presenter, meskipun tugasnya sudah dikunci. Guru juga boleh mencoret di lembar itu.
- Lembar presenter **tidak dikirim ke siswa lain**. Selama presentasi, siswa lain tetap melihat layar mereka sendiri (lembar masing-masing atau papan guru).
- `present.stop` mengembalikan hak menulis presenter ke aturan kunci tugas. Kalau saat itu presenter sedang menulis, goresannya dibatalkan dengan `stroke.cancel` dan `STROKE_REVOKED`.

### 6.5 Snapshot dan pemulihan
- State sesi (papan, templat tugas, dan semua lapisan siswa) disimpan sebagai snapshot gzip setiap **500 operasi**, saat `assignment.start`, dan saat sesi berakhir.
- **Saat backend restart:** muat snapshot terakhir, lalu putar ulang `ops` setelahnya.
- Log `ops` yang lebih lama dari snapshot terbaru minus 2.000 boleh dipangkas.

### 6.6 Klien
- **Goresan ditolak:** dihapus dari layar penulis.
- **Operasi guru ditolak:** aplikasi guru mengirim `board.reset` dari papan lokalnya. **Papan lokal guru adalah sumber kebenaran** untuk `board`.
- **Heartbeat:** `ping` setiap 10 detik. Koneksi ditutup kalau tidak ada pesan selama 30 detik.
- **Sambung ulang** dengan jeda 0,5 s, 1 s, 2 s, 4 s, lalu 8 s seterusnya (±20% jitter), kemudian kirim `hello{lastSeq}`.
- **Siswa yang offline:** input dinonaktifkan dengan banner "Menyambung ulang…" (Panduan §2). Goresan yang sudah selesai tetapi belum di-`ack` disimpan di antrean dan dikirim ulang dengan `op` yang sama, jadi tidak ganda.
- **Guru yang offline:** tetap bisa menulis secara lokal. Setelah tersambung, aplikasi guru mengirim `board.reset`.

---

## 7. Skema database (MySQL 8.4, InnoDB, utf8mb4)

```sql
CREATE TABLE teacher_devices (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(80) NOT NULL,
  key_hash BINARY(32) NOT NULL UNIQUE,
  created_at DATETIME(3) NOT NULL,
  revoked_at DATETIME(3) NULL
);

CREATE TABLE sessions (
  id CHAR(26) PRIMARY KEY,                      -- ULID
  device_id BIGINT UNSIGNED NOT NULL,
  code CHAR(6) NULL UNIQUE,                     -- NULL setelah berakhir
  name VARCHAR(80) NOT NULL,
  frame_w FLOAT NOT NULL,
  frame_h FLOAT NOT NULL,
  last_seq BIGINT UNSIGNED NOT NULL DEFAULT 0,
  next_slot INT UNSIGNED NOT NULL DEFAULT 1,
  status ENUM('active','ended') NOT NULL DEFAULT 'active',
  created_at DATETIME(3) NOT NULL,
  ended_at DATETIME(3) NULL,
  FOREIGN KEY (device_id) REFERENCES teacher_devices(id)
);

CREATE TABLE members (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  session_id CHAR(26) NOT NULL,
  role ENUM('teacher','student') NOT NULL,
  status ENUM('pending','approved','rejected','kicked') NOT NULL,
  name VARCHAR(40) NOT NULL,
  slot INT UNSIGNED NULL,                       -- diisi saat disetujui
  token_hash BINARY(32) NOT NULL UNIQUE,
  created_at DATETIME(3) NOT NULL,
  approved_at DATETIME(3) NULL,
  UNIQUE (session_id, slot),
  FOREIGN KEY (session_id) REFERENCES sessions(id)
);

CREATE TABLE assignments (
  id CHAR(26) PRIMARY KEY,
  session_id CHAR(26) NOT NULL,
  title VARCHAR(80) NOT NULL,
  template MEDIUMBLOB NOT NULL,                 -- JSON halaman templat, gzip
  locked BOOLEAN NOT NULL DEFAULT FALSE,
  created_seq BIGINT UNSIGNED NOT NULL,
  closed_at DATETIME(3) NULL,
  FOREIGN KEY (session_id) REFERENCES sessions(id)
);

CREATE TABLE ops (
  session_id CHAR(26) NOT NULL,
  seq BIGINT UNSIGNED NOT NULL,
  op_id VARCHAR(40) NOT NULL,
  member_id BIGINT UNSIGNED NOT NULL,
  doc VARCHAR(64) NOT NULL,                     -- board | sheet:<a>:<m> | session
  type VARCHAR(24) NOT NULL,
  payload JSON NOT NULL,
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY (session_id, seq),
  UNIQUE (session_id, op_id),
  KEY (session_id, doc, seq)
);

CREATE TABLE snapshots (
  session_id CHAR(26) NOT NULL,
  seq BIGINT UNSIGNED NOT NULL,
  body MEDIUMBLOB NOT NULL,                     -- state sesi, gzip
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY (session_id, seq)
);

CREATE TABLE assets (
  session_id CHAR(26) NOT NULL,
  name VARCHAR(80) NOT NULL,
  mime VARCHAR(32) NOT NULL,
  size INT UNSIGNED NOT NULL,
  sha256 BINARY(32) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY (session_id, name)
);
-- File aset: /var/lib/kelas/assets/<session_id>/<name>
```

**Retensi data:** sesi yang sudah berakhir beserta seluruh datanya dihapus otomatis setelah **30 hari** (`RETENTION_DAYS`). Data siswa hanya berupa nama tampilan dan coretan di lembar tugas.

---

## 8. Backend (`backend/`)

```
backend/src/
  index.ts             HTTP + WS, graceful shutdown
  config.ts            env: DB_*, PUBLIC_ORIGIN, RETENTION_DAYS, …
  http/routes.ts       healthz, readyz, sessions, join, assets
  ws/server.ts         upgrade, hello/auth, heartbeat, rate limit
  session/actor.ts     antrean per sesi, terapkan op, broadcast sesuai hak lihat
  session/state.ts     model murni: board, assignment, sheets, presenting  (+ applyOp)
  session/access.ts    aturan hak lihat & tulis (§3.1, §5.6) — diuji tersendiri
  session/strokes.ts   buffer goresan live, pembatalan
  db/*.ts              mysql2/promise, transaksi, migrasi
  security/tokens.ts   token, kode kelas
backend/migrations/0001_init.sql
backend/scripts/create-device-key.ts
backend/test/          vitest: unit + integrasi (MySQL lokal)
```

- **Dependensi runtime:** `ws`, `mysql2`, `ajv`, `ulid`. HTTP memakai `node:http`.
- **Log:** JSON per baris, tanpa token dan tanpa nama siswa.
- **Metrik** (hanya dari localhost): jumlah sesi dan koneksi, operasi per detik, serta latensi siaran p50/p95.

---

## 9. Web siswa (`web-siswa/`)

### 9.1 Teknologi
- Vite + TypeScript tanpa framework. Target bundle **< 60 KB gzip**.
- Canvas 2D (`desynchronized: true`) dengan skala `devicePixelRatio`.
- Pointer Events dan `getCoalescedEvents()`. Pena, jari, dan mouse semuanya didukung.
- **Penolakan telapak tangan:** kalau ada input pena (`pointerType === "pen"`), sentuhan jari diabaikan selama 500 ms.
- **Tanpa HTTPS (§11):** service worker dan pemasangan PWA tidak tersedia. Siswa tetap bisa membuat pintasan "Tambahkan ke layar utama". Manifest tetap disiapkan agar PWA otomatis aktif setelah server pindah ke HTTPS.
- **Hindari API khusus konteks aman** (secure context), karena API ini tidak jalan di `http://`. Contohnya `crypto.randomUUID()` (pakai `crypto.getRandomValues()`), Clipboard API, dan service worker. Canvas, Pointer Events, WebSocket, dan `localStorage` tetap berfungsi.
- Semua teks dalam bahasa Indonesia.

### 9.2 Struktur
```
web-siswa/src/
  main.ts            routing: /k/:code → gabung → menunggu → kelas
  join.ts            kode + nama (nama diingat di localStorage)
  connection.ts      WebSocket, hello/catchup, heartbeat, reconnect, antrean op
  board/state.ts     model + applyOp()   ← dipakai bersama dengan backend
  board/render.ts    port BoardRenderer (latar, pola, ink, shape, text, img)
  board/view.ts      canvas, fit/zoom, layer cache, live strokes
  board/input.ts     pointer → koordinat papan → stroke.*
  sheet/tools.ts     pena, stabilo, penghapus, bentuk, teks, pilih/geser, urungkan/ulangi (lapisan siswa)
  sheet/geometry.ts  port Geometry (hit test, laso, hapus sebagian) dari BoardModel
  ui/*.ts            bar status, banner, angkat tangan, "Sudah selesai"
```

### 9.3 Layar
1. **Gabung:** kode kelas (otomatis terisi dari QR) dan nama, lalu tombol **"Minta masuk"**.
2. **Menunggu:** "Menunggu persetujuan guru…" dengan animasi ringan. Kalau ditolak, muncul "Permintaan ditolak guru".
3. **Papan guru (lihat saja):** papan memenuhi layar, dengan tombol ✋ "Angkat tangan".
4. **Lembar tugas** (otomatis terbuka saat guru membagikan):
   - toolbar: pena dan stabilo (10 warna yang sama dengan guru, 3 ketebalan), penghapus (sebagian atau utuh), bentuk (garis, panah, persegi, lingkaran, segitiga), teks (kotak input bawaan browser), pilih/geser/ubah ukuran untuk item milik sendiri, serta urungkan dan ulangi. Di layar HP sempit, toolbar bisa digeser seperti dock Papan Guru,
   - tombol **"Sudah selesai"**, serta tombol untuk berpindah halaman kalau tugasnya lebih dari satu halaman,
   - tab kecil "Papan guru" untuk melihat papan sebentar,
   - saat dikunci: banner "Lembar dikunci guru" dan toolbar dinonaktifkan.
5. **Saat presentasi:** presenter melihat banner "Giliranmu menjelaskan", dan lembarnya terbuka lagi untuk ditulisi meskipun tugas sudah dikunci. Layar siswa lain tidak berubah.
6. **Koneksi putus:** banner kuning "Menyambung ulang…" dan input nonaktif.
7. **Kelas berakhir:** layar "Kelas sudah selesai".

### 9.4 Rendering sama persis dengan guru
- `smoothPath` (quadTo antar titik tengah), ujung bulat, dan stabilo dengan ujung kotak beralfa 0x66.
- Pola latar dengan langkah 56 unit, minimal 32 px, dikali atau dibagi 2.
- Teks memakai baseline `y + size` dan tinggi baris 1,25.
- **Uji kesamaan:** fixture yang sama dirender di Android dan web, lalu screenshot keduanya dibandingkan dengan toleransi piksel.

---

## 10. Perubahan di aplikasi guru (Papan Guru)

1. **Dependensi:** `com.squareup.okhttp3:okhttp`, satu-satunya dependensi runtime. Catatan ini perlu diperbarui di CLAUDE.md.
2. ✅ *Sudah ada sejak versi 0.10.1.* **Mode tanpa TLS:** sejak Android 9, koneksi `http://` dan `ws://` diblokir secara default. Saat ini `android:usesCleartextTraffic` diatur lewat manifest placeholder `cleartext`: bernilai `true` hanya di **build debug**, dan `false` di build rilis. Izin `INTERNET` juga sudah ditambahkan. Setelah server pindah ke HTTPS, placeholder debug dikembalikan ke `false`.
3. **`SyncClient.kt`:** membungkus OkHttp WebSocket. Tugasnya hello, ack/antrean `op`, sambung ulang, dan mengirim callback ke UI thread.
4. **`ClassSession.kt`:** menyimpan state kelas, yaitu anggota, permintaan masuk, tugas aktif, lapisan semua siswa, presenter, tangan terangkat, tanda selesai, dan `lastSeq`. Kelas ini juga mengubah commit papan menjadi operasi:
   - item baru → `item.add`,
   - item yang hilang → `item.remove`,
   - item yang berubah → `item.update`,
   - kalau perubahannya lebih dari 50 item, atau berasal dari urungkan/ulangi atau penghapus sebagian → `page.replace`.
5. ✅ *Sudah ada sejak versi 0.10.1.* **`BoardModel`:** `BoardPage.id` (format `p_` diikuti 12 karakter alfanumerik, disimpan di `board.json`) dan skema ID slot `ItemIds` (§3.5).
6. **Memulai kelas:**
   - Tombol **"Mulai kelas"** di Papan Guru. Opsional, tile yang sama di Edu Launcher lewat `BoardsContract` dengan extra baru, misalnya `EXTRA_CLASS`.
   - Pengaturan "Server kelas" berisi alamat server dan kunci perangkat, dengan tombol "Uji koneksi" yang memanggil `/readyz`.
7. **Panel kelas** (tombol di header papan, dengan badge jumlah permintaan):
   - Kode dan **QR besar** (port satu berkas generator QR berlisensi MIT).
   - **"Minta masuk"**: daftar nama dengan tombol ✓ setujui dan ✕ tolak, serta tombol **"Setujui semua"**.
   - Daftar siswa dengan status online, ✋, ✓ selesai, dan tombol keluarkan.
8. **Bagikan lembar tugas:**
   - Menu halaman → "Bagikan sebagai lembar tugas". Pilihannya halaman ini saja atau beberapa halaman, plus judul tugas.
   - Aset diunggah lebih dulu, lalu `assignment.start` dikirim.
9. **Layar "Pantau":**
   - Kisi lembar siswa dengan gambar kecil yang di-render `BoardRenderer` di thread kerja, tanpa cache path.
   - Lembar diurutkan berdasarkan nama atau status.
   - Ketuk satu lembar untuk tampilan besar yang live (`watch`), dengan tombol sebelumnya dan berikutnya.
   - Tombol **"Kunci lembar"** dan **"Buka kunci"**.
10. **Presentasi:**
    - Dari tampilan besar, tekan **"Presentasi"**. Lembar hanya tampil di smartboard.
    - Lembar siswa tampil penuh di smartboard, dan guru bisa mencoret dengan alat yang sama seperti di papan.
    - Tombol **"Selesai"** dan **"Siswa berikutnya"**. Urutan berikutnya bisa acak dengan memakai ulang logika pengundi nama.
11. **Simpan hasil:** semua lembar disimpan sebagai satu PDF, satu siswa per bagian dan ditandai nama siswa, memakai `BoardFiles.exportPdf` dari templat dan lapisan siswa.
12. **Indikator koneksi** di header: titik hijau, kuning, atau merah, ditambah jumlah siswa online.

---

## 11. Server uji coba: `http://157.15.165.36:8001`

### 11.1 Konfigurasi
- **Caddy** mendengarkan di port 8001 tanpa TLS dan melayani web siswa, `/api`, dan `/ws`. Backend tetap berjalan di `127.0.0.1:3000`, dan MySQL di `127.0.0.1:3306` (Panduan §3).
- **Firewall:** buka TCP **8001** saja, plus SSH yang dibatasi untuk admin. Port 3000 dan 3306 tidak boleh terbuka ke luar.
- **Backend env:** `PUBLIC_ORIGIN=http://157.15.165.36:8001`. Nilai ini dipakai untuk link QR dan pemeriksaan header `Origin`.

```
{
    auto_https off
}

http://157.15.165.36:8001, :8001 {
    encode gzip
    handle /api/* { reverse_proxy 127.0.0.1:3000 }
    handle /ws    { reverse_proxy 127.0.0.1:3000 }
    handle {
        root * /opt/kelas/web-siswa
        try_files {path} /index.html
        file_server
    }
}
```

### 11.2 Konsekuensi tanpa HTTPS (perlu diketahui sekolah)
| Hal | Dampak | Mitigasi saat pilot |
|---|---|---|
| Lalu lintas tidak terenkripsi | Token, nama siswa, dan isi lembar bisa disadap oleh perangkat lain di Wi-Fi yang sama | Siswa hanya memakai nama panggilan. Token hanya berlaku selama sesi (maks. 12 jam). Persetujuan guru mencegah orang asing masuk kelas. Kunci perangkat guru hanya dikirim saat membuat sesi |
| PWA tidak bisa dipasang | Tidak ada mode offline untuk file aplikasi, dan tidak ada ikon PWA | Siswa memakai pintasan "Tambahkan ke layar utama". File web kecil (< 60 KB) jadi tetap cepat dimuat |
| Android memblokir cleartext | Papan Guru tidak bisa tersambung | Izinkan cleartext hanya di build pilot (§10 butir 2) |
| Beberapa jaringan sekolah atau operator membatasi port tidak standar | Siswa mungkin tidak bisa membuka `:8001` | Cek dulu dari Wi-Fi sekolah dan dari data seluler. Kalau terblokir, pindah ke port 80 |
| Panduan mensyaratkan HTTPS/WSS | Pilot menyimpang dari Panduan | Catat sebagai pengecualian pilot. Sebelum dipakai lebih luas, pindah ke HTTPS (§11.3) |

### 11.3 Pindah ke HTTPS nanti
Kalau port 80 dan 443 bisa dibuka, **`https://157-15-165-36.sslip.io`** memberi sertifikat Let's Encrypt otomatis tanpa membeli domain. Bisa juga memakai domain sendiri. Langkahnya:
1. Ubah Caddyfile, lalu ubah `PUBLIC_ORIGIN`.
2. Aktifkan service worker.
3. Hapus izin cleartext di build guru.
4. Buat QR baru.

Data di server tidak perlu dipindah.

## 12. Pengujian dan penerimaan

| Tingkat | Isi |
|---|---|
| Unit | `applyOp`, aturan hak lihat dan tulis (`access.ts`), pengubahan commit menjadi operasi (Kotlin), skema, token, dan kode kelas |
| Integrasi | Buat sesi → minta masuk → setujui → bagikan tugas → 3 siswa menulis bersamaan → kunci → presentasi → sambung ulang → restart backend → `catchup` |
| Kontrak | Fixture di `protocol/fixtures/` dibaca oleh tes Kotlin, backend, dan web. Ketiganya harus menghasilkan state yang sama |
| Beban | 40 siswa simulasi **menulis bersamaan di lembar masing-masing**, guru membuka kisi pantau dan satu lembar live. Ukur p50/p95 latensi, CPU dan RAM server, **FPS layar guru**, dan bandwidth |
| Lapangan | Panduan §4, disesuaikan (lewat `http://157.15.165.36:8001` dari Wi-Fi sekolah):<br>• Satu siswa presentasi, tulisannya tampil di smartboard sebelum pena diangkat.<br>• `present.stop` di tengah goresan menolak goresan berikutnya.<br>• Siswa lain tidak bisa melihat atau mengubah lembar temannya.<br>• Wi-Fi tablet diputus lalu disambung lagi.<br>• Siswa bergabung terlambat.<br>• Backend di-restart. |

**Latensi:**
- `stroke.begin` membawa `t` (jam pengirim), dan server menambahkan `ts`.
- Layar guru mencatat `waktu_terima − ts`, ditambah setengah RTT dari ping.
- Target: **p95 ≤ 200 ms** untuk lembar yang dipresentasikan atau yang sedang dibuka besar.

---

## 13. Tahapan pengerjaan

| Fase | Hasil | Selesai jika |
|---|---|---|
| **A. Protokol & backend lokal** | `protocol/`, `backend/`: migrasi, API, WS, persetujuan masuk, actor, aturan akses, snapshot, tes | Tes integrasi lulus dengan klien tiruan, termasuk setelah restart |
| **B. Web siswa: gabung & lihat** | Gabung, menunggu persetujuan, papan guru live, sambung ulang | Siswa melihat papan guru sama dengan fixture, dan sambung ulang memulihkan state |
| **C. Mode kelas di Papan Guru** | OkHttp SyncClient, "Mulai kelas", QR, panel persetujuan, siaran papan dan goresan live | Guru menyetujui 3 siswa dan tulisan guru tampil di perangkat siswa sebelum pena diangkat |
| **D. Lembar tugas & pantau** | Bagikan tugas, alat tulis siswa, kisi pantau, tampilan besar live, kunci | 40 siswa simulasi mengisi bersamaan dan layar guru tetap lancar |
| **E. Presentasi & simpan hasil** | Presentasi satu per satu di smartboard, coretan guru di lembar siswa, PDF hasil | Skenario uji lapangan §12 lulus |
| **F. Uji coba** | Server `http://157.15.165.36:8001` sesuai §11, uji beban, catatan latensi | Satu jam pelajaran nyata tanpa kehilangan data, p95 ≤ 200 ms. Port 8001 bisa dibuka dari Wi-Fi sekolah |

**Setelah pilot (usulan):**
1. Akun guru dan riwayat kelas.
2. Mengembalikan lembar yang sudah dikoreksi ke siswa.
3. Kuis pilihan ganda.
4. Mode LAN.
5. Aplikasi Android siswa (membungkus web).
6. Mode ujian.

---

## 14. Keputusan

**Sudah diputuskan (draf 3):**
- Siswa tidak pernah menulis bersamaan di papan bersama.
- Lembar tugas dikerjakan masing-masing dan dipantau guru.
- Presentasi satu per satu, hanya di smartboard.
- Guru menyetujui setiap siswa yang masuk.
- Siswa mendapat alat lengkap di lembarnya.
- Server pilot di `http://157.15.165.36:8001`.
- WebSocket memakai `ws` di server, WebSocket bawaan browser di web siswa, dan OkHttp di aplikasi guru.

- HTTP tanpa TLS diterima selama fase pengembangan.
- Titik goresan disimpan per goresan utuh (§6.1).
- Identitas guru memakai kunci perangkat.
- Data dihapus otomatis setelah 30 hari.
- Satu VPS (`157.15.165.36`) menjalankan Caddy, backend, dan MySQL, dikelola sendiri oleh pemilik proyek.

---

## 15. Serah terima ke agent server

Bagian ini untuk agent yang mengerjakan backend dan web siswa langsung di server `157.15.165.36`.

### 15.1 Pembagian kerja
| Dikerjakan di server (agent server) | Dikerjakan di repo aplikasi guru |
|---|---|
| Fase A: `protocol/` (tipe TS, JSON Schema, fixtures) dan `backend/` | Fase C–E sisi Android: OkHttp, mode kelas, panel persetujuan, Pantau, presentasi, simpan hasil |
| Fase B, D, dan E sisi web: seluruh `web-siswa/` | Perubahan `BoardModel` (`BoardPage.id`, ID slot) |
| Fase F: Caddy di port 8001, layanan systemd, MySQL, backup (Panduan §3) | Build pilot dengan izin cleartext |

**Kontrak bersama adalah folder `protocol/`.** Folder ini dibuat lebih dulu oleh agent server dan berisi tipe pesan, JSON Schema, serta fixture JSON (contoh `state`, operasi, dan hasil `applyOp`). Aplikasi guru nanti memakai fixture yang sama untuk tesnya. Setiap perubahan protokol harus menaikkan `protocol/VERSION` dan dicatat di `protocol/CHANGELOG.md`.

### 15.2 Urutan kerja yang disarankan
1. `protocol/`: skema semua pesan di §5, ditambah fixture.
2. `backend/`: migrasi §7, API §4, WebSocket §5, actor dan aturan akses §6, snapshot, dan tes (§12 baris Unit dan Integrasi).
3. **Klien tiruan** (`backend/scripts/fake-teacher.ts` dan `fake-student.ts`). Klien ini menggantikan aplikasi guru selama Papan Guru belum mendukung mode kelas: membuat sesi, menyetujui siswa, membagikan tugas, presentasi, dan uji beban 40 siswa.
4. `web-siswa/`: §9.
5. Pemasangan di VPS: §11, plus Panduan §3 (systemd dan backup), tetapi memakai HTTP di port 8001.

### 15.3 Acuan dari aplikasi guru (kalau repo ini tersedia di server)
Web siswa harus menggambar **persis sama** dengan Papan Guru. Kalau repo aplikasi tidak tersedia di server, salin berkas-berkas ini:
- `app/src/main/java/id/kelasku/papanguru/BoardModel.kt`: item, `ShapeType`, `BoardColor` (nilai ARGB), `BoardPattern`, dan `Geometry` (hit test, laso, `eraseInk`, `shapeOutline`).
- `app/src/main/java/id/kelasku/papanguru/BoardRenderer.kt`: `drawBackground` (warna dan jarak pola), `smoothPath`, stabilo, tinta adaptif, teks, dan konstanta `LINE_HEIGHT`/`HIGHLIGHT_ALPHA`.
- `app/src/main/java/id/kelasku/papanguru/BoardStore.kt`: `BoardCodec` (format item JSON, `encodePoints`, dan `SAFE_FILE`).
- Palet warna pena: `PALETTE` di `MainActivity.kt`.

### 15.4 Hal yang mudah terlewat
- **Konteks tidak aman (`http://`):** jangan memakai `crypto.randomUUID()`, service worker, atau Clipboard API (§9.1).
- **Token di browser** dikirim lewat pesan `hello`, bukan header (§5.2). Server tetap harus menerima header `Authorization` untuk aplikasi guru.
- **Warna** adalah ARGB bertanda (*signed*). Ubah dengan `c >>> 0` sebelum memakainya di CSS atau canvas.
- **`seq` yang diterima siswa boleh berlompatan** (§5.3). Jangan anggap ada data yang hilang.
- **Lalu lintas ke guru:** goresan live hanya dikirim untuk dokumen yang di-`watch` atau yang sedang dipresentasikan (§6.3).
- **Teks dan pesan error** semuanya dalam bahasa Indonesia.
- **Log tidak boleh berisi token atau nama siswa.**
