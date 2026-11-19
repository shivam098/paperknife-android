# PaperKnife for Android — architecture

PaperKnife for Android follows the design of
[PaperKnife for Windows](https://github.com/shivam098/paperknife-windows/blob/main/docs/ARCHITECTURE.md),
whose engine it runs, ported to Kotlin.

## Modules

| Module | Responsibility |
|---|---|
| `:core` | The engine, ported line by line from PaperKnife for Windows ([porting rules](PORTING.md)): PDF syntax, page content, fonts, the editing helpers, and `DocumentSession`, with its snapshots, undo history and verification. A plain JVM library that calls PDFium through JNA, so its tests run on the JVM. Its test fixtures hold the conformance runner. |
| `:app` | The Jetpack Compose app: `EditorViewModel`, which follows the Windows app's main window, the screen, and `Editor`, a thin adapter over `DocumentSession`. |

## PDF engine

- **PDFium** from [pdfium-binaries](https://github.com/bblanchon/pdfium-binaries)
  (chromium/8152 for arm64-v8a, armeabi-v7a, x86_64 and x86), the build without V8, so JavaScript
  in PDFs never runs. The build downloads it and checks its checksum; binaries aren't committed.
- The engine calls the same subset of PDFium's C API that Windows uses, through JNA's direct
  mapping, so PaperKnife has no native code of its own: the app ships `libpdfium.so` and JNA's
  `libjnidispatch.so` for each ABI.
- PDFium isn't thread-safe, so every call holds one lock, and the app makes its engine calls on
  one thread.
- **Edits are transactions**, as on Windows: change a copy, save it, read it again, verify it
  (character support, text round trip, object counts, pixel comparison of the page with the
  edited text hidden), then keep it or throw it away. Undo and redo restore earlier snapshots,
  which are byte arrays, within the session's history budget.
- **Behavior is the Windows engine's.** Every case of the shared
  [conformance corpus](../conformance/README.md) must give the same observations here.

## Files

- PaperKnife opens PDFs with the system's file picker (`ACTION_OPEN_DOCUMENT`) or when another app
  opens one with it (`ACTION_VIEW`), and saves a copy through the picker (`ACTION_CREATE_DOCUMENT`).
- Saving writes the document to the destination's stream and flushes it; only then does
  PaperKnife count the document as saved, so a failed save leaves it unsaved.
- A document lives as long as the app's process; if Android stops the app in the background,
  unsaved changes are lost (see the [roadmap](ROADMAP.md)).

## Privacy

- The manifest declares no `INTERNET` permission, so the system blocks all network access. CI
  fails if a dependency adds one through manifest merging, for debug and release builds.
- No analytics, crash-reporting or advertising SDKs.

## Testing

- JVM tests for `:core`, among them the whole conformance corpus, on Windows and Linux.
- An instrumented test runs the whole corpus in the app on an emulator in CI, with Android's
  PDFium, ART and JNA.
- CI opens a PDF in the app on an emulator, taps its text, types new text and checks that the
  edit is applied and verified.