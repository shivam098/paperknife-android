#!/usr/bin/env bash
# Runs the app's instrumented tests on the connected device, among them DeviceConformanceTest, which
# runs the whole conformance corpus there. Keeps what the corpus run logged (logcat tag
# PaperKnifeCorpus) in device-corpus.log, also when a test fails, and prints its summary.
#
# Usage: ci-device-corpus.sh
set -uo pipefail

# Room for the lines of every case, which logcat would otherwise rotate out.
adb logcat -G 16M || true
adb logcat -c || true

status=0
./gradlew --console=plain :app:connectedDebugAndroidTest || status=$?

adb logcat -d -v time -s PaperKnifeCorpus:V AndroidRuntime:E TestRunner:V > device-corpus.log || true
grep -E 'cases ran in|of at most' device-corpus.log || echo "The corpus run logged no summary."
exit "$status"
