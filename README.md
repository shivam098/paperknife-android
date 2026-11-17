# PaperKnife for Android

**An offline, open-source PDF editor for Android.** Part of [PaperKnife](https://github.com/shivam098/paperknife-windows):
your documents never leave your device — no uploads, no account, no telemetry.

> **Status: planning.** No app code yet. Windows is the lead implementation; Android development
> starts once the editing model and shared test corpus are proven there (Windows milestone M1).
> See the [roadmap](docs/ROADMAP.md) and [architecture plan](docs/ARCHITECTURE.md).

## Planned features

The same promises as every PaperKnife app ([product principles](https://github.com/shivam098/paperknife-windows/blob/main/docs/PRODUCT.md)):

- Edit existing text in place, keeping the original font, size, position and color.
- Verify every change before keeping it, and refuse with a clear reason instead of damaging the
  document.
- Organize pages: rotate, delete, reorder, merge and split.
- Later: annotations, form filling, compression, scanning with the camera and on-device text
  recognition.

## Planned technology

| Area | Choice |
|---|---|
| Language and UI | Kotlin, Jetpack Compose (Material 3) |
| PDF engine | [PDFium](https://pdfium.googlesource.com/pdfium/) through a small JNI layer, using the [pdfium-binaries](https://github.com/bblanchon/pdfium-binaries) Android builds (arm64-v8a, armeabi-v7a, x86_64, x86) |
| Files | Storage Access Framework, so no storage permission is needed |
| Privacy | No `INTERNET` permission: Android itself prevents the app from going online |

## Contributing

Ideas and design feedback are welcome in issues. See [CONTRIBUTING.md](CONTRIBUTING.md) and
[SECURITY.md](SECURITY.md).

## License

[Apache License 2.0](LICENSE).
