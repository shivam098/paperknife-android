#!/usr/bin/env bash
# Taps the page PaperKnife shows on the connected device, checks that the text there is selected
# for editing, and takes a screenshot. The point is given from the page's top-left corner in
# fractions of the page's width, because the device clips the box of a page taller than the screen.
#
# Usage: ci-tap-page.sh <x> <y> <expected text> <screenshot.png>
set -euo pipefail

package=io.github.shivam098.paperknife

bash "$(dirname "$0")/ci-close-system-dialogs.sh"

dump() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null
  adb exec-out cat /sdcard/ui.xml
}

if [ -z "$(adb shell pidof "$package" || true)" ]; then
  echo "PaperKnife isn't running."
  adb logcat -d -s AndroidRuntime:E
  exit 1
fi

bounds=$(dump | grep -o 'content-desc="Page"[^>]*bounds="[^"]*"' | grep -o 'bounds="[^"]*"' | head -n 1 || true)
if [ -z "$bounds" ]; then
  echo "PaperKnife doesn't show a page."
  adb logcat -d -s AndroidRuntime:E
  exit 1
fi

read -r left top right _ <<< "$(echo "$bounds" | grep -o '[0-9]\+' | tr '\n' ' ')"
x=$(awk -v l="$left" -v r="$right" -v f="$1" 'BEGIN { printf "%d", l + f * (r - l) }')
y=$(awk -v t="$top" -v l="$left" -v r="$right" -v f="$2" 'BEGIN { printf "%d", t + f * (r - l) }')
echo "The page is at $bounds; tapping ($x, $y)."
adb shell input tap "$x" "$y"
sleep 3
adb exec-out screencap -p > "$4"

if ! dump | grep -qF "text=\"$3\""; then
  echo "\"$3\" isn't selected for editing."
  exit 1
fi

echo "\"$3\" is selected for editing."
