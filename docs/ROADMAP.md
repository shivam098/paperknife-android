# PaperKnife for Android — roadmap

## Done in 0.5.1

- The engine of PaperKnife for Windows, ported line by line, which passes every case of the
  shared conformance corpus on the JVM and on Android.
- The app: open PDFs from the file picker or other apps (also password-protected ones), tap text
  to select it, edit or delete it with verification and a clear reason when an edit is refused,
  rotate, delete and add pages, undo and redo, save a copy.
- Signed release APKs for arm64-v8a, armeabi-v7a, x86_64 and x86, built by CI from a tag.

## Next

- Keep unsaved changes when Android stops the app in the background.
- Pinch to zoom; a thumbnail grid with drag to reorder; extract, merge and split.
- Open PDFs shared from other apps (the share sheet).

## Later

- Highlights, notes, ink drawing with stylus support, form filling.
- Scan documents with the camera, processed on the device; compress, images to PDF and PDF to
  images.
- On-device OCR for scanned pages. Tesseract (Apache-2.0) is preferred over proprietary SDKs so
  the app can be published on F-Droid.
- Google Play and F-Droid. F-Droid requires every native library to be built from source, so a
  reproducible PDFium build is needed before submission.