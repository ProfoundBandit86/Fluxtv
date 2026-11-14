#!/usr/bin/env bash
# Emulator smoke test for Flux TV: home -> title page -> streams -> Real-Debrid, series page, Settings colours.
set -x
mkdir -p out
P=com.fluxtv.app
shot() { adb exec-out screencap -p > "out/$1.png"; }
top() { echo "== TOP ($1): $(adb shell dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity')"; }
texts() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; echo "== TEXT ($1): $(adb shell cat /sdcard/ui.xml | grep -o 'text="[^"]*"' | grep -v 'text=""' | head -${2:-25} | tr '\n' ' ')"; }
key() { adb shell input keyevent "$1"; sleep "${2:-1}"; }
nudge() { key DPAD_DOWN 1; key DPAD_UP 1; }
crashes() { echo "== CRASHES ($1)"; adb logcat -d -b crash | tail -60; }

adb install -r flux-test.apk
adb shell am start -W -n $P/.AddonsActivity; sleep 5
adb forward tcp:8090 tcp:8090
curl -s -m 90 -X POST --data-urlencode "url=https://torrentio.strem.fun/manifest.json" http://127.0.0.1:8090/install | grep -o "<p class='m'>[^<]*"
curl -s -m 30 -X POST --data-urlencode "key=DUMMYTOKEN123" http://127.0.0.1:8090/rdkey | grep -o "<p class='m'>[^<]*"
adb shell am force-stop $P
adb logcat -c

# Home
adb shell am start -W -n $P/.MainActivity
sleep 35
top home; texts home 30; shot 01-home
key DPAD_RIGHT 2; key DPAD_RIGHT 2; texts hero 12; shot 02-poster-focused
key DPAD_DOWN 2; key DPAD_RIGHT 2; shot 03-second-row
key DPAD_CENTER 12
top details; nudge; texts details 20; shot 04-details
crashes details
key DPAD_CENTER 35
top streams; nudge; texts streams 20; shot 05-streams
key DPAD_CENTER 12
texts stream-error 25; shot 06-stream-error
crashes streams

# Series page
adb shell am start -W -n $P/.DetailActivity --es type series --es id tt0903747 --es name "Series test"
sleep 15; nudge; top series; texts series 30; shot 07-series
crashes series

# Settings: pick the second accent colour, then see home recoloured
adb shell am start -W -n $P/.SettingsActivity
sleep 4; nudge; key DPAD_RIGHT 1; key DPAD_CENTER 3
texts settings 30; shot 08-settings
crashes settings
adb shell am start -W -n $P/.MainActivity
sleep 30; shot 09-home-recoloured
crashes home-recoloured

adb logcat -d > out/logcat.txt
exit 0
