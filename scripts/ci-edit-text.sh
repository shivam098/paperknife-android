#!/usr/bin/env bash
# Types new text over the text ci-tap-page.sh selected, applies it with the keyboard's Enter key,
# checks that PaperKnife reports what it should, and takes a screenshot.
#
# Usage: ci-edit-text.sh <new text> <expected report> <screenshot.png>
set -euo pipefail

bash "$(dirname "$0")/ci-close-system-dialogs.sh"

dump() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null
  adb exec-out cat /sdcard/ui.xml
}

# input text takes %s for a space.
adb shell input text "${1// /%s}"
adb shell input keyevent KEYCODE_ENTER
sleep 8
adb exec-out screencap -p > "$3"

ui=$(dump)
if ! grep -qF "$2" <<< "$ui"; then
  echo "PaperKnife didn't report \"$2\". The screen shows:"
  grep -o 'text="[^"]*"' <<< "$ui" | sort -u
  adb logcat -d -s AndroidRuntime:E
  exit 1
fi

echo "PaperKnife reported \"$2\"."
