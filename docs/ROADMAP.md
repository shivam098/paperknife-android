# PaperKnife for Android — roadmap

Android development starts after Windows milestone M1, so it can reuse a proven editing model
and the shared test corpus. Each milestone ends with an installable app.

## A0 — Foundation

- Gradle project (Kotlin, Jetpack Compose), Apache-2.0 notices, CI that builds the app and runs
  tests on an emulator.
- PDFium JNI bridge for arm64-v8a, armeabi-v7a, x86_64 and x86, with all engine calls on one
  thread.
- Open PDFs through the system file picker (including password-protected files), page view with
  pinch zoom.
- A CI check that the merged manifest has no `INTERNET` permission.

## A1 — Verified text editing

- Port the Windows editing model: immutable snapshots, transactional edits, verification
  (character support, round trip, object counts, pixel comparison), undo and redo.
- Tap text to select it, edit it, see a clear explanation when an edit is refused.
- Save a copy through the system picker.
- Run the shared corpus on emulators and compare results with Windows.

## A2 — Pages

- Thumbnail grid with drag to reorder, rotate, delete, extract, merge and split.
- Share sheet integration: open PDFs sent from other apps.

## A3 — Annotations and forms

- Highlight, notes, ink drawing with stylus support, form filling.

## A4 — Toolbox

- Scan documents with the camera, processed on the device.
- Compress, images to PDF and PDF to images.

## A5 — Text recognition

- On-device OCR for scanned pages. Tesseract (Apache-2.0) is preferred over proprietary SDKs so
  the app can be published on F-Droid.

## Distribution

- Google Play and F-Droid. F-Droid requires every native library to be built from source, so a
  reproducible PDFium build is needed before submission.
