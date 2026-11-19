#!/usr/bin/env bash
# Builds the release APK of the version in VERSION (such as v0.5.1), signed with the release key in
# KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS and KEY_PASSWORD (the repository's secrets), checks
# its signature, version and permissions, and writes it with its checksum to release/.
#
# Usage: VERSION=v0.5.1 KEYSTORE_BASE64=... build-release-apk.sh
set -euo pipefail

# The certificate every release is signed with: Android installs an update only if it is signed
# with the same key (see CONTRIBUTING.md).
expected_certificate=346dc83854cda13db3c038f29466294126682045a21397df50f4184fa0b349bb

version="${VERSION#v}"
for name in KEYSTORE_BASE64 KEYSTORE_PASSWORD KEY_ALIAS KEY_PASSWORD; do
  if [ -z "${!name:-}" ]; then
    echo "The release key's secret for $name isn't set; see CONTRIBUTING.md." >&2
    exit 1
  fi
done

# Secrets set from a shell may end with a line break, which isn't part of them.
keystore="${RUNNER_TEMP:-/tmp}/paperknife-release.jks"
printf '%s' "$KEYSTORE_BASE64" | tr -d '\r\n ' | base64 --decode > "$keystore"
export PAPERKNIFE_KEYSTORE="$keystore"
PAPERKNIFE_KEYSTORE_PASSWORD="$(printf '%s' "$KEYSTORE_PASSWORD" | tr -d '\r\n')"
PAPERKNIFE_KEY_ALIAS="$(printf '%s' "$KEY_ALIAS" | tr -d '\r\n')"
PAPERKNIFE_KEY_PASSWORD="$(printf '%s' "$KEY_PASSWORD" | tr -d '\r\n')"
export PAPERKNIFE_KEYSTORE_PASSWORD PAPERKNIFE_KEY_ALIAS PAPERKNIFE_KEY_PASSWORD

# A daemon the earlier steps started would keep its environment, without the key.
status=0
./gradlew --console=plain --no-daemon :app:assembleRelease -PpaperknifeVersion="$version" || status=$?
rm -f "$keystore"
if [ "$status" -ne 0 ]; then
  exit "$status"
fi

apk=app/build/outputs/apk/release/app-release.apk
tools="$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -n 1)"
"$tools/apksigner" verify --print-certs "$apk" | tee signature.txt
if ! grep -q "Signer #1 certificate SHA-256 digest: $expected_certificate" signature.txt; then
  echo "The APK isn't signed with PaperKnife's release key." >&2
  exit 1
fi

"$tools/aapt" dump badging "$apk" | tee badging.txt
if ! grep -q "versionName='$version'" badging.txt; then
  echo "The APK's version isn't $version." >&2
  exit 1
fi

if "$tools/aapt" dump permissions "$apk" | grep -q 'android.permission.INTERNET'; then
  echo "The app asks for the INTERNET permission; PaperKnife must never go online." >&2
  exit 1
fi

mkdir -p release
cp "$apk" "release/PaperKnife-$version-android.apk"
(cd release && sha256sum "PaperKnife-$version-android.apk" > SHA256SUMS.txt)
cat release/SHA256SUMS.txt