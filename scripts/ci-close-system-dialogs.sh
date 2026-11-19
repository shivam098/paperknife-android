#!/usr/bin/env bash
# Closes "isn't responding" dialogs of other apps, which a freshly booted emulator can show over
# PaperKnife, by choosing Wait. Nothing else on the screen is touched.
#
# Usage: ci-close-system-dialogs.sh
set -euo pipefail

for _ in 1 2 3; do
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null
  ui=$(adb exec-out cat /sdcard/ui.xml)
  button=$(grep -o '<node[^>]*resource-id="android:id/aerr_wait"[^>]*>' <<< "$ui" | head -n 1 || true)
  if [ -z "$button" ]; then
    exit 0
  fi

  read -r left top right bottom <<< "$(grep -o 'bounds="[^"]*"' <<< "$button" | grep -o '[0-9]\+' | tr '\n' ' ')"
  echo "Closing a system dialog: $(grep -o 'text="[^"]*responding[^"]*"' <<< "$ui" | head -n 1 || true)"
  adb shell input tap $(( (left + right) / 2 )) $(( (top + bottom) / 2 ))
  sleep 2
done