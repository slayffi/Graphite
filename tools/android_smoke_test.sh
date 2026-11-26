#!/usr/bin/env bash
# Installs an APK on the connected emulator/device, starts Graphite and checks that it really comes up:
# the process stays alive, the first screen is on display and nothing crashed. Used by CI on the *release*
# APK, because R8 problems and crashes in library initializers do not show up in JVM tests.
#
#   tools/android_smoke_test.sh <apk> [timeout seconds]
set -euo pipefail

APK="${1:?usage: android_smoke_test.sh <apk> [timeout seconds]}"
TIMEOUT="${2:-180}"
PKG="com.graphite.torrent"
ADB="${ADB:-adb}"
OUT="${SMOKE_OUT:-build/smoke}"
mkdir -p "$OUT"

$ADB wait-for-device
$ADB install -r "$APK" >/dev/null
# Otherwise the system's notification permission dialog covers the first screen on Android 13+.
$ADB shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
$ADB logcat -c || true
$ADB shell am start -W -n "$PKG/.android.MainActivity" >/dev/null

fail() {
  echo "SMOKE TEST FAILED: $1"
  $ADB logcat -d -v threadtime > "$OUT/logcat.txt" || true
  grep -E "AndroidRuntime|FATAL EXCEPTION|ANR in $PKG" "$OUT/logcat.txt" | head -60 || true
  [ -f "$OUT/ui.xml" ] && grep -o 'text="[^"]\{1,2000\}"' "$OUT/ui.xml" | head -40 || true
  exit 1
}

deadline=$(( $(date +%s) + TIMEOUT ))
shown=""
while [ "$(date +%s)" -lt "$deadline" ]; do
  sleep 5
  $ADB logcat -d -v threadtime > "$OUT/logcat.txt" || true
  if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt" && grep -A2 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -q "$PKG"; then
    fail "the app crashed"
  fi
  $ADB shell uiautomator dump /sdcard/graphite-ui.xml >/dev/null 2>&1 || continue
  $ADB shell cat /sdcard/graphite-ui.xml > "$OUT/ui.xml" 2>/dev/null || continue
  # A slow emulator may put "System UI isn't responding" on top: choose "Wait" and look again.
  if grep -q "isn't responding" "$OUT/ui.xml"; then
    tap=$(python3 - "$OUT/ui.xml" <<'PY'
import re, sys
m = re.search(r'text="Wait"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', open(sys.argv[1], encoding="utf-8", errors="ignore").read())
print(f"{(int(m[1]) + int(m[3])) // 2} {(int(m[2]) + int(m[4])) // 2}" if m else "")
PY
)
    [ -n "$tap" ] && $ADB shell input tap $tap || true
    continue
  fi
  if grep -q "Graphite stopped because of an error" "$OUT/ui.xml"; then
    fail "the app shows its error report"
  fi
  if grep -qE "Add your first download|Добавьте первую загрузку" "$OUT/ui.xml"; then
    shown="yes"
    break
  fi
done

[ -n "$shown" ] || fail "the first screen did not appear in ${TIMEOUT}s"
[ -n "$($ADB shell pidof "$PKG" | tr -d '\r')" ] || fail "the process is gone"

# Keep it running a little: background jobs (engine start, tracker list, automation) must not kill it.
sleep 20
$ADB logcat -d -v threadtime > "$OUT/logcat.txt" || true
if grep -A2 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -q "$PKG"; then fail "the app crashed after start"; fi
[ -n "$($ADB shell pidof "$PKG" | tr -d '\r')" ] || fail "the process died after start"
$ADB shell uiautomator dump /sdcard/graphite-ui.xml >/dev/null 2>&1 && $ADB shell cat /sdcard/graphite-ui.xml > "$OUT/ui.xml" || true
if grep -q "Graphite stopped because of an error" "$OUT/ui.xml"; then fail "the app shows its error report"; fi

echo "Start-up OK: the downloads screen is shown"

# --- Adding a download: the magnet intent, the add sheet, the engine and the foreground service. ---------
# The info-hash does not exist, so nothing is downloaded; the torrent just waits for metadata.
dump_ui() {
  $ADB shell uiautomator dump /sdcard/graphite-ui.xml >/dev/null 2>&1 && $ADB shell cat /sdcard/graphite-ui.xml > "$OUT/ui.xml" 2>/dev/null
}
# Taps the first node whose text matches the regular expression; prints nothing, fails when absent.
tap_text() {
  local xy
  xy=$(python3 - "$OUT/ui.xml" "$1" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding="utf-8", errors="ignore").read()
for node in re.findall(r"<node[^>]*>", xml):
    text = re.search(r'text="([^"]*)"', node)
    bounds = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
    if text and bounds and re.search(sys.argv[2], text.group(1)):
        x1, y1, x2, y2 = map(int, bounds.groups())
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
PY
)
  [ -n "$xy" ] || return 1
  $ADB shell input tap $xy
}

$ADB shell am start -a android.intent.action.VIEW \
  -d "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567\&dn=SmokeTestDownload" \
  -n "$PKG/.android.MainActivity" >/dev/null

added=""
deadline=$(( $(date +%s) + TIMEOUT ))
while [ "$(date +%s)" -lt "$deadline" ]; do
  sleep 4
  dump_ui || continue
  if grep -q "Graphite stopped because of an error" "$OUT/ui.xml"; then fail "the app shows its error report after adding"; fi
  if grep -q "SmokeTestDownload" "$OUT/ui.xml" && ! grep -qE "Start without preview|Начать без предпросмотра" "$OUT/ui.xml"; then
    added="yes"
    break
  fi
  tap_text "^(Start without preview|Начать без предпросмотра)$" || true
done
[ -n "$added" ] || fail "the download did not appear in the list"

sleep 10
$ADB logcat -d -v threadtime > "$OUT/logcat.txt" || true
if grep -A2 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -q "$PKG"; then fail "the app crashed after adding a download"; fi
[ -n "$($ADB shell pidof "$PKG" | tr -d '\r')" ] || fail "the process died after adding a download"
$ADB shell dumpsys activity services "$PKG" > "$OUT/services.txt" 2>/dev/null || true
grep -q "TorrentService" "$OUT/services.txt" || fail "the download service is not running"
grep -q "isForeground=true" "$OUT/services.txt" || fail "the download service is not in the foreground"

echo "SMOKE TEST PASSED: Graphite started, a download was added and the foreground service is running"
