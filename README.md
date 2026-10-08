# File Converter (Android)

Convert images, audio, video and documents on your phone.

- **On the phone, offline:** images (JPG, PNG, WEBP, BMP, TIFF, ICO, GIF, HEIC/AVIF input, images → PDF),
  audio (MP3, M4A, AAC, WAV, FLAC, OGG, OPUS, AIFF, WMA), video (MP4, MKV, WEBM, MOV, AVI, 3GP, video → GIF,
  video → audio), PDF → PNG/JPG pages, TXT → PDF, XLSX ↔ CSV, DOCX ↔ TXT, and a text-only DOCX → PDF.
- **Through CloudConvert (needs your API key and internet):** DOCX, DOC, ODT, RTF, TXT, HTML, MD, XLSX, XLS,
  ODS, CSV, PPTX, PPT, ODP, EPUB, MOBI, AZW3 and PDF, within each document group. DOCX → PDF uses CloudConvert
  when a key is set, so the layout is kept.

Results are saved to `Download/FileConverter/`. You can also share files into the app from any other app.

## Build

Requirements: JDK 21, Android SDK with platform 36. Use a short project path on Windows (long paths break the build).

```bash
./gradlew testDebugUnitTest      # JVM tests
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew connectedDebugAndroidTest   # device/emulator tests (arm64-v8a or x86_64)
```

## CloudConvert key

Create a key at https://cloudconvert.com/dashboard/api/v2/keys (scopes: `task.read`, `task.write`, `user.read`),
then paste it in **Settings → CloudConvert** and tap **Test**. The key is encrypted with an Android Keystore key
and never leaves the phone except in requests to CloudConvert. Free accounts have a daily conversion limit.

## How it works

`FormatRegistry` picks an engine for each (source, target) pair: Android image APIs, FFmpeg
(`dev.ffmpegkit-maintained:ffmpeg-kit-full-gpl`), `PdfDocument`/`PdfRenderer`, small built-in OOXML/CSV
readers and writers, or CloudConvert. Conversions run one at a time in a foreground WorkManager worker,
so long videos keep converting when you leave the app; cancel from the app or the notification.

## Licence note

The FFmpeg build used here is GPL-3.0. That is fine for personal use; check the licence before distributing the app.
