# PaperKnife for Android — architecture plan

This is a plan, not a description of existing code. It follows the design proven in
[PaperKnife for Windows](https://github.com/shivam098/paperknife-windows/blob/main/docs/ARCHITECTURE.md).

## Modules

| Module | Responsibility |
|---|---|
| `:app` | Jetpack Compose UI, navigation, file pickers. |
| `:core` | Pure Kotlin: snapshots, the document session, verification rules, undo history. Testable on the JVM. |
| `:pdfium` | JNI bindings (C++, NDK, CMake) and the prebuilt `libpdfium.so` for each ABI. |

## PDF engine

- **PDFium** from [pdfium-binaries](https://github.com/bblanchon/pdfium-binaries)
  (`pdfium-android-{arm64,arm,x64,x86}`), the build without V8, so JavaScript in PDFs never runs.
- A thin C++ JNI layer exposes the same subset of the PDFium API that Windows uses. Pixels are
  written straight into Android `Bitmap`s with `AndroidBitmap_lockPixels`.
- PDFium isn't thread-safe, so all engine calls run on one dedicated thread, exposed to Kotlin as
  suspend functions.

### Why not only the platform PDF APIs?

`PdfRenderer` (with Android 15's additions) and the Jetpack `androidx.pdf` library can view,
search, select text, add annotations and fill forms, but they can't change the text drawn on a
page. PaperKnife needs PDFium for editing, and uses it for rendering too, so the pixels the user
sees are the pixels the verifier checks.

## Editing model

The same transaction as on Windows: apply the change to a scratch copy, save it, reload it,
verify it (character support, text round trip, object counts per type, pixel comparison of the page with
the edited text hidden), then commit or discard. Android-specific points:

- **Memory.** The Java heap is small, so snapshots live in native memory or in files in the app's
  cache folder, not in Java byte arrays. The undo history gets a smaller budget than on desktop.
- **Process death.** Android can stop the app in the background. The current snapshot and the
  undo history are written to the cache folder, so an unsaved document survives a restart and
  is deleted when the user discards it.

## Files

- Open with `ACTION_OPEN_DOCUMENT` and keep persistable URI permissions for recent files.
- Save a copy with `ACTION_CREATE_DOCUMENT`. Overwriting a document through a content provider
  isn't atomic, so the app writes and verifies a complete copy first and keeps it until the write
  to the destination succeeds.

## Privacy

- The manifest declares no `INTERNET` permission, so the system blocks all network access. CI
  fails if a dependency adds one through manifest merging.
- No analytics, crash-reporting or advertising SDKs.

## Testing

- JVM unit tests for `:core`.
- Instrumented tests that run the shared corpus on x86_64 emulators in CI and compare the results
  with the Windows app's results.

## Open questions

- Minimum Android version (proposal: Android 8.0, API 26).
- Building PDFium from source reproducibly for F-Droid.
- Whether to share the corpus and its expected results through a dedicated repository once two
  apps use them.
