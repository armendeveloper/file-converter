# File Converter for Android — Design

**Date:** 2026-10-08
**Status:** Draft for review

## 1. Purpose and success criteria

A personal/portfolio Android app, written in Kotlin, that converts files between formats across four families: **images, audio, video and documents**.

The app succeeds when:

1. A user can pick a file (or share one into the app from another app), choose a target format from a list that only shows valid targets, convert it, and open or share the result — in at most 4 taps after picking.
2. Image, audio and video conversions work **fully offline** on the device.
3. Office/e-book document conversions work through **CloudConvert** using the user's own API key. Basic DOCX/XLSX conversions (XLSX↔CSV, DOCX↔TXT, DOCX→PDF text-only) also work offline without a key.
4. Long conversions (e.g. a 10-minute video) survive the user leaving the app, show progress in a notification, and can be cancelled.
5. Every failure shows a plain-language message and a next step; no crash on bad input.

### Decisions already made

| Decision | Choice | Why |
|---|---|---|
| Where conversions run | **Hybrid**: media + simple PDFs on device, office/e-book documents via CloudConvert | Best coverage; Android has no on-device DOCX/XLSX/PPTX→PDF renderer |
| Audience | **Personal / portfolio** | User supplies own CloudConvert key in Settings; no backend proxy |
| On-device media engine | **FFmpeg** via `dev.ffmpegkit-maintained:ffmpeg-kit-full-gpl:8.1.9` | Covers nearly every audio/video format; maintained fork with Android 15 + 16 KB page-size support |

### Out of scope (YAGNI)

- Backend proxy, accounts, payments, ads, analytics.
- Play Store release work (GPL compliance review, privacy policy, store listing).
- Video editing beyond trim range for GIF/clip (no filters, crop UI, subtitles).
- OCR, transcription, archive (ZIP/RAR) conversion.
- Cloud conversion for media (media is always local).

## 2. Platform and stack

| Item | Value |
|---|---|
| Language | Kotlin 2.x, coroutines + Flow |
| UI | Jetpack Compose, Material 3, Navigation Compose, single `MainActivity` |
| minSdk / targetSdk / compileSdk | 24 / 36 / 36 (minSdk 24 is FFmpegKit fork's floor) |
| ABIs | arm64-v8a, x86_64 (what the FFmpegKit fork ships) |
| Background work | WorkManager `CoroutineWorker` running as a foreground service (`dataSync` type) |
| Networking | OkHttp + kotlinx.serialization (CloudConvert REST v2) |
| Persistence | Preferences DataStore (settings), Room (conversion history) |
| Secrets | API key encrypted with an AES-256-GCM key held in Android Keystore, ciphertext stored in DataStore |
| Build | Gradle Kotlin DSL + version catalog, single `:app` module, JDK 21 |

FFmpeg dependency: `dev.ffmpegkit-maintained:ffmpeg-kit-full-gpl:8.1.9` — drop-in replacement for `com.arthenica:ffmpeg-kit-full-gpl`; API package `com.arthenica.ffmpegkit`. Includes x264/x265, libmp3lame, Vorbis, Opus, VPX, AOM, libwebp. Licensed GPL-3.0 — acceptable for personal use; would need review before any public distribution.

## 3. Supported formats

`FormatRegistry` is the single source of truth. Each format has: id, family, extensions, MIME types, and whether it is readable and/or writable by each engine.

### 3.1 Images — on device

| | Formats |
|---|---|
| Input | JPG/JPEG, PNG, WEBP, BMP, GIF (first frame), HEIC/HEIF (API 28+), AVIF (API 31+), TIFF, ICO |
| Output | JPG, PNG, WEBP, BMP, TIFF, ICO, GIF (static), PDF |

Engine selection: JPG/PNG/WEBP output via `ImageDecoder`/`BitmapFactory` + `Bitmap.compress` (`ImageConverter`). BMP, TIFF, ICO, GIF output and TIFF/ICO input via FFmpeg. PDF output via `LocalPdfConverter`. Multiple images → one PDF is supported (one page per image).

Options: quality (1–100, lossy formats only), max dimension resize (original / 4096 / 2048 / 1080 / 720 px longest side).

### 3.2 Audio — on device (FFmpeg)

Input and output: **MP3, M4A/AAC, WAV, FLAC, OGG (Vorbis), OPUS, AIFF, WMA**. Any → any (except same → same).

Options: bitrate for lossy (64 / 128 / 192 / 256 / 320 kbps), sample rate (keep / 44.1 kHz / 48 kHz), channels (keep / mono / stereo).

### 3.3 Video — on device (FFmpeg)

Input and output: **MP4, MKV, WEBM, MOV, AVI, 3GP**. Plus:
- Video → **GIF** (fps 10/15, width 320/480/720, start/end time required, max 30 s range)
- Video → any **audio** format from 3.2 (extract audio track)

Default codecs per container: MP4/MOV/MKV → H.264 + AAC; WEBM → VP9 + Opus; AVI → MPEG-4 Part 2 (xvid) + MP3; 3GP → H.263-compatible scaling to 352×288 max + AAC. Where input codecs are already compatible with the target container and no options change, **stream copy** (`-c copy`) is used for speed.

Options: resolution (keep / 1080p / 720p / 480p), quality (CRF presets: High / Medium / Small file), optional trim start/end.

### 3.4 Documents

**On device (`LocalPdfConverter`):**
- Images → PDF (one page per image)
- PDF → PNG / JPG (one image per page; multi-page output saved as individual files in one folder)
- TXT → PDF (monospace, A4, word-wrapped)

**On device (`OfficeLiteConverter`)** — pure Kotlin, no extra libraries; reads/writes the OOXML zip packages directly:

| Pair | Fidelity | Notes |
|---|---|---|
| XLSX → CSV | Full (cell values) | One CSV per sheet; single-sheet files produce one `.csv`, multi-sheet files produce `<name>-<sheet>.csv` each. Formulas output their cached value. Dates output as stored serial numbers converted to ISO-8601 when the cell has a built-in date style. |
| CSV → XLSX | Full (cell values) | One sheet; numeric-looking cells written as numbers, everything else as inline strings. Delimiter auto-detected (`,` `;` tab). |
| DOCX → TXT | Full (text) | Paragraphs → lines, tabs and line breaks kept; tables flattened row by row with tab-separated cells. |
| TXT → DOCX | Full (text) | One paragraph per line, default Calibri 11 pt. |
| DOCX → PDF | **Basic** (text only) | DOCX → text → TXT→PDF renderer. No images, fonts, tables layout or styles. |

Routing rule for documents: "Full" on-device pairs are always done on device. For **DOCX → PDF**, CloudConvert is used when an API key is set and the network is available; otherwise the Basic on-device route runs. The Convert screen shows which route will run: "☁ CloudConvert" or "Offline · basic (text only)".

**Cloud (`CloudConvertConverter`)** — inputs and outputs:

| Group | Formats |
|---|---|
| Word processing | DOCX, DOC, ODT, RTF, TXT, HTML, MD, PDF |
| Spreadsheets | XLSX, XLS, ODS, CSV, PDF |
| Presentations | PPTX, PPT, ODP, PDF |
| E-books | EPUB, MOBI, AZW3, PDF |

Allowed pairs within each group follow CloudConvert's supported conversions; `FormatRegistry` holds a static list of allowed pairs (no runtime discovery call). If a full-fidelity on-device route exists for a pair (e.g. JPG→PDF, TXT→PDF, XLSX→CSV), the on-device route is always preferred. Basic-fidelity on-device routes (DOCX→PDF) are only a fallback when the cloud route is unavailable.

## 4. Architecture

Single `:app` module, packages by responsibility:

```
dev.armen.fileconverter
├── ui/            Compose screens, ViewModels, navigation, theme
├── domain/        FileFormat, FormatFamily, FormatRegistry, ConversionRequest,
│                  ConversionOptions, ConversionResult, ConversionError
├── engine/        Converter interface + ImageConverter, FfmpegConverter,
│                  LocalPdfConverter, OfficeLiteConverter, CloudConvertConverter,
│                  FfmpegCommandBuilder
├── engine/office/ DocxReader, DocxWriter, XlsxReader, XlsxWriter, CsvCodec
├── work/          ConversionWorker, ConversionScheduler, notification helper
├── data/          SettingsRepository, ApiKeyCipher, HistoryDao/HistoryDatabase,
│                  OutputStore (MediaStore writer), InputResolver (Uri → name/size/MIME)
└── di/            AppContainer (manual DI — no Hilt)
```

### 4.1 Units

| Unit | Responsibility | Depends on |
|---|---|---|
| `FormatRegistry` | Detect format from MIME + extension; list valid targets for a source; pick engine for a (source, target) pair | Nothing (pure Kotlin) |
| `Converter` | `suspend fun convert(input: File, target: FileFormat, options: ConversionOptions, output: File, onProgress: (Float) -> Unit)` — throws `ConversionError` | — |
| `FfmpegCommandBuilder` | Pure function: (source, target, options, media info) → FFmpeg argument list | Nothing (pure Kotlin) |
| `FfmpegConverter` | Probe input duration (FFprobeKit), run command async, map statistics time → progress, support cancel | FFmpegKit, `FfmpegCommandBuilder` |
| `ImageConverter` | Decode with `ImageDecoder` (API 28+) / `BitmapFactory`, apply EXIF rotation, resize, compress | Android SDK |
| `LocalPdfConverter` | Images→PDF via `PdfDocument`; PDF→images via `PdfRenderer`; TXT→PDF via `StaticLayout` on `PdfDocument` pages | Android SDK |
| `DocxReader` / `XlsxReader` | Stream the zip, parse `word/document.xml` or `xl/workbook.xml` + `xl/sharedStrings.xml` + `xl/styles.xml` + sheets with `XmlPullParser`; return plain paragraphs / rows of cell strings | `java.util.zip`, `org.xmlpull` (built into Android; kxml2 on JVM for tests) |
| `DocxWriter` / `XlsxWriter` | Write minimal valid OOXML packages (`[Content_Types].xml`, `_rels`, document/workbook/sheet parts) | `java.util.zip` |
| `CsvCodec` | RFC 4180 CSV read/write with quote escaping and delimiter detection | Nothing (pure Kotlin) |
| `OfficeLiteConverter` | Wires readers/writers into the `Converter` interface for the five offline office pairs; DOCX→PDF delegates text rendering to `LocalPdfConverter` | Office readers/writers, `LocalPdfConverter` |
| `CloudConvertClient` | Thin REST client for CloudConvert v2 (create job, upload, get job, cancel, user info) | OkHttp, kotlinx.serialization |
| `CloudConvertConverter` | Orchestrates the job: create job with `import/upload` → `convert` → `export/url` tasks, upload, poll every 2 s until finished, download | `CloudConvertClient` |
| `ConversionWorker` | Copy input `Uri` to cache, resolve engine, run converter, publish via `OutputStore`, write history row, clean temp files, report progress via `setProgress` | All engines, `OutputStore`, history |
| `OutputStore` | Save a file into `Download/FileConverter/` through MediaStore (API 29+) or app-specific external dir fallback on API 24–28; return content `Uri` | Android SDK |
| `SettingsRepository` | Encrypted API key, sandbox toggle, default quality options | DataStore, `ApiKeyCipher` |
| `ApiKeyCipher` | Encrypt/decrypt with an AES-GCM key from Android Keystore | Android Keystore |

### 4.2 CloudConvert integration

- Base URL: `https://api.cloudconvert.com/v2` (or `https://api.sandbox.cloudconvert.com/v2` when the sandbox toggle is on).
- Auth: `Authorization: Bearer <key>`.
- Flow: `POST /jobs` with three tasks (`import/upload`, `convert` with `input_format`/`output_format`, `export/url`) → multipart upload to the import task's `result.form.url` with its `result.form.parameters` → poll `GET /jobs/{id}` every 2 s → download `export/url` file URL → `DELETE /jobs/{id}` best-effort cleanup.
- Progress mapping: upload 0–40 %, conversion 40–80 % (indeterminate within), download 80–100 %.
- "Test key" in Settings calls `GET /users/me` and shows the remaining conversion credits.
- Max input size: 1 GB (CloudConvert free-tier limits are surfaced as errors, not enforced client-side beyond this).

## 5. User experience

### 5.1 Screens

1. **Home** — "Pick files" button (`ActivityResultContracts.OpenMultipleDocuments`), recent conversions list (from Room, newest first, tap to open/share, swipe to remove from history). App is also a target for `ACTION_SEND` / `ACTION_SEND_MULTIPLE` for `*/*`.
2. **Convert** — header with file name(s), size, detected format. Target format chips grouped by family. Options panel specific to the target family. Cloud targets show a "☁ Uploads to CloudConvert" badge; if no key is set, the Convert button routes to Settings. Batch: multiple files must share a family; one target applies to all.
3. **Progress** — per-file progress bar + overall progress for batches, current stage text ("Uploading…", "Converting…"), Cancel button.
4. **Result** — per output: name, size, Open (ACTION_VIEW), Share (ACTION_SEND); "Convert another" returns to Home.
5. **Settings** — CloudConvert API key field (masked) + Test key button, sandbox toggle, default image quality, default audio bitrate, default video quality, "Clear history".

### 5.2 Data flow

```
Picker/Share Intent → InputResolver (name, size, MIME) → FormatRegistry.detect
  → ConvertViewModel (targets, options) → ConversionScheduler.enqueue(request)
  → ConversionWorker: copy Uri→cache → FormatRegistry.engineFor → Converter.convert(→ cache output)
  → OutputStore.save → History insert → cleanup
  ← UI observes WorkInfo (progress, success, error) by work id
```

Each file in a batch is its own `OneTimeWorkRequest`, tagged with a batch id; they run sequentially (unique work chain) to avoid running multiple FFmpeg sessions at once.

Output naming: `<original-base-name>.<target-ext>`; on collision MediaStore's numbering applies.

## 6. Error handling

`ConversionError` is a sealed class; each maps to a user message and action:

| Error | When | Message / action |
|---|---|---|
| `UnsupportedPair` | No engine for (source, target) | "Can't convert X to Y." |
| `UnreadableInput` | Decode/probe fails, empty or corrupt file | "This file looks damaged or isn't really a X." |
| `MissingApiKey` | Cloud route, no key | "Add your CloudConvert API key" → Settings |
| `InvalidApiKey` | HTTP 401/403 | "Your CloudConvert key was rejected" → Settings |
| `QuotaExceeded` | HTTP 402 / 429 or credits exhausted | "CloudConvert limit reached — try later or upgrade your plan." |
| `NoNetwork` | IOException before response on cloud route | "No internet connection." + Retry |
| `InsufficientStorage` | ENOSPC / MediaStore insert fails for space | "Not enough storage space." |
| `EngineFailed(log)` | FFmpeg non-zero return code, CloudConvert task error | "Conversion failed." + expandable "Details" with last 20 log lines / CloudConvert message |
| `Cancelled` | User cancels | Silent return to Convert screen |

Cancellation: `FFmpegKit.cancel(sessionId)` for local media; `DELETE /jobs/{id}` for cloud. Temp files in `cacheDir/conversions/<workId>/` are deleted in a `finally` block on every outcome.

## 7. Testing

**JVM unit tests (fast, run on every build):**
- `FormatRegistry`: detection from MIME/extension (including mismatched/unknown), valid target lists per family, engine choice prefers on-device.
- `FfmpegCommandBuilder`: exact argument lists for representative pairs (MP4→WEBM, MOV→MP4 stream-copy, video→GIF with palette, WAV→MP3 320 kbps, video→M4A extract, trim options).
- `CloudConvertConverter` + client: against OkHttp `MockWebServer` — happy path, 401, 402, task error, cancellation.
- Error mapping: exceptions → `ConversionError` variants.
- Office: `CsvCodec` quoting/escaping/delimiter detection; `XlsxReader` on fixture workbooks (shared strings, inline strings, numbers, booleans, cached formula values, date styles, multiple sheets, sparse cells like `A1, C1`); `DocxReader` on fixtures with paragraphs, tabs, breaks and a table; round-trips CSV→XLSX→CSV and TXT→DOCX→TXT preserve content.
- Routing: DOCX→PDF picks CloudConvert with key + network, Basic offline otherwise.

**Instrumented tests (emulator):**
- Real conversions on small fixtures in `androidTest/assets`: PNG→JPG, JPG→PDF, PDF→PNG, TXT→PDF, WAV→MP3, MP3→OGG, MP4→WEBM, MP4→GIF, MP4→M4A. Assert output exists, is non-empty, and has the expected MIME/magic bytes.
- `ApiKeyCipher` round-trip with real Keystore.

**Build verification in this environment:** `./gradlew testDebugUnitTest assembleDebug` with JDK 21 and Android SDK (platform 36, build-tools 36.0.0). Instrumented tests run on the existing local AVD when available.

## 8. Risks

| Risk | Mitigation |
|---|---|
| FFmpeg fork artifact or ABI issues | Pin exact version 8.1.9; verify `assembleDebug` early in implementation; fall back to 7.1.7 line if 8.1.9 fails |
| APK size (~60–80 MB with full-gpl) | Accepted for personal use; ABI splits produce per-ABI APKs |
| HEIC/AVIF decoding unavailable on old devices | Format only offered as input on supported API levels; otherwise routed through FFmpeg if it can decode, else `UnreadableInput` |
| CloudConvert API changes | Client isolated in one class with MockWebServer tests |
| GPL-3.0 licence | Personal use only; documented in README |
