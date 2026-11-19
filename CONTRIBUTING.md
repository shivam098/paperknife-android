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

## Releasing

1. Write the release notes as `docs/releases/v<version>.md`.
2. Tag the commit and push the tag, for example `git tag v0.5.1` and `git push origin v0.5.1`.
3. The [Release workflow](.github/workflows/release.yml) runs CI with that version, including the
   conformance corpus on an emulator, builds the release APK, signs it, checks that it is signed
   with PaperKnife's release key and can't go online, and publishes it with its checksum as a
   GitHub release. Tags with a suffix, such as `v0.5.1-beta.1`, become pre-releases.

The release key is in the repository's secrets `PAPERKNIFE_KEYSTORE_BASE64` (the PKCS12 keystore,
base64-encoded), `PAPERKNIFE_KEYSTORE_PASSWORD`, `PAPERKNIFE_KEY_ALIAS` and
`PAPERKNIFE_KEY_PASSWORD`; [scripts/build-release-apk.sh](scripts/build-release-apk.sh) checks the
certificate's SHA-256. Android installs an update only over an app signed with the same key, so
never replace it: a release signed with another key can't update PaperKnife on anyone's phone. To
sign on a computer instead, set `PAPERKNIFE_KEYSTORE` to the keystore's path and the three other
variables, then run `./gradlew --no-daemon :app:assembleRelease -PpaperknifeVersion=0.5.1`
(`--no-daemon` because a Gradle daemon started earlier keeps its old environment). Without the
key, release builds come out unsigned.

By contributing, you agree that your contributions are licensed under the Apache License 2.0.
