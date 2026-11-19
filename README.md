# PaperKnife for Android

**An offline, open-source PDF editor for Android.** Part of [PaperKnife](https://github.com/shivam098/paperknife-windows):
your documents never leave your device — no uploads, no account, no telemetry.

## Download

On your phone or tablet, download `PaperKnife-<version>-android.apk` from the
**[Releases page](https://github.com/shivam098/paperknife-android/releases/latest)** and open it.
Android asks you to allow installing apps from your browser or Files app the first time; allow
it, then select **Install**. If Play Protect says it doesn't recognize the app, select
**More details**, then **Install anyway**. PaperKnife needs Android 8.0 or later.

Every release is signed with the same key, so a new version installs over the old one. To check
a download, compare its SHA-256 with `SHA256SUMS.txt` on the release.

## What it does

The same promises as every PaperKnife app ([product principles](https://github.com/shivam098/paperknife-windows/blob/main/docs/PRODUCT.md)):

- **Edit text in place.** Tap any text to select its line or table cell, type, and tap Apply.
  PaperKnife keeps the original font, size, position and color, and changes only the characters
  you changed.
- **Verified edits.** After every edit PaperKnife reads the result again and compares the whole
  page; if anything else would change, it refuses the edit and says why, instead of damaging the
  document.
- **Hard PDFs too:** PDFs that draw every letter on its own, PDFs saved from browsers, and LaTeX
  PDFs, where it can even add letters the PDF's fonts didn't contain.
- **Pages:** rotate, delete and add pages from another PDF. Undo and redo, and save a copy.

It runs the engine of PaperKnife for Windows, ported line by line, and passes the same
[conformance tests](conformance/README.md), also on Android itself, so it edits the same PDFs the
same way. Later: reordering, extracting and merging pages, annotations, forms, scanning and text
recognition (see the [roadmap](docs/ROADMAP.md)).

## Technology

| Area | Choice |
|---|---|
| Language and UI | Kotlin, Jetpack Compose (Material 3) |
| PDF engine | PaperKnife's engine, ported from PaperKnife for Windows ([porting rules](docs/PORTING.md)), on [PDFium](https://pdfium.googlesource.com/pdfium/) through [JNA](https://github.com/java-native-access/jna), from the [pdfium-binaries](https://github.com/bblanchon/pdfium-binaries) Android builds (arm64-v8a, armeabi-v7a, x86_64, x86) |
| Files | The system's file picker (Storage Access Framework), so no storage permission is needed |
| Privacy | No `INTERNET` permission: Android itself prevents the app from going online |

See the [architecture](docs/ARCHITECTURE.md).

## Contributing

Ideas and design feedback are welcome in issues. See [CONTRIBUTING.md](CONTRIBUTING.md) and
[SECURITY.md](SECURITY.md).

## License

[Apache License 2.0](LICENSE).