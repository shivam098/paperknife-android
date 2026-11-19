# PDFium

PaperKnife for Android uses [PDFium](https://pdfium.googlesource.com/pdfium/) chromium/8152, the
build without V8 or XFA from [pdfium-binaries](https://github.com/bblanchon/pdfium-binaries), so
JavaScript in PDFs never runs. PaperKnife for Windows and Mac use the same version.

- The root `build.gradle.kts` downloads `pdfium-android-{arm64,arm,x64,x86}.tgz`, checks each
  SHA-256 and puts `libpdfium.so` for each ABI into the app (`lib/<abi>/`). For the engine's
  tests it downloads the build for the machine running them (Windows, Linux or macOS). Nothing is
  committed.
- The engine calls PDFium's C API through [JNA](https://github.com/java-native-access/jna).
- [`licenses/`](licenses/) holds the license texts of PDFium and the libraries compiled into it,
  as the build ships them, and the MIT license of pdfium-binaries
  (`pdfium-binaries-LICENSE.txt`).
