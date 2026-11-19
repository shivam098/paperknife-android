# Contributing to PaperKnife for Android

PaperKnife for Android runs the engine of [PaperKnife for Windows](https://github.com/shivam098/paperknife-windows),
ported to Kotlin, in a Jetpack Compose app. Every PaperKnife app follows the same rules
([product principles](https://github.com/shivam098/paperknife-windows/blob/main/docs/PRODUCT.md)):

1. **Nothing leaves the device.** No network calls, telemetry, analytics or advertising SDKs, and
   no `INTERNET` permission (CI checks the app).
2. **Edits are verified** before they are kept, and refused with a clear reason otherwise.
3. **No faked edits**, such as shapes drawn over text.
4. **No documents we don't own** in the repository: tests generate their PDFs, and the
   conformance corpus stores only PDFs PaperKnife's tests generated.

## Getting started

You need JDK 17 and the Android SDK (platform 35), for example from Android Studio. Then:

```bash
./gradlew :core:test          # the engine's tests, on this computer's JVM
./gradlew :app:assembleDebug  # the app, in app/build/outputs/apk/debug/
./gradlew :app:connectedDebugAndroidTest  # the conformance corpus on a connected device or emulator
```

The build downloads PDFium (checked against its SHA-256) for this computer, for the tests, and
for the app's Android ABIs.

The engine follows PaperKnife for Windows' engine line by line; [docs/PORTING.md](docs/PORTING.md)
lists the rules that keep its behavior the same. The tests run the
[conformance corpus](conformance/README.md): the cases PaperKnife for Windows records, which this
app must pass too. Don't change it here. Change the cases in paperknife-windows, then copy the
corpus with `pwsh scripts/sync-conformance.ps1 -Windows ../paperknife-windows`.

Never change the `applicationId` in `app/build.gradle.kts`: Android identifies the app, and its
updates, by it. When a shipped dependency changes, update its license texts in `third_party/` and
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

By contributing, you agree that your contributions are licensed under the Apache License 2.0.
