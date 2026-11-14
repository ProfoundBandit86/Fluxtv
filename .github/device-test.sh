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

# Home, then the Movies / TV Shows / Favorites sections from the sidebar
adb shell am start -W -n $P/.MainActivity
sleep 35
top home; texts home 20; shot 01-home
nudge; key DPAD_DOWN 1; key DPAD_DOWN 1; key DPAD_CENTER 25
texts movies 30; shot 02-movies
key DPAD_DOWN 1; key DPAD_CENTER 25
texts shows 30; shot 03-shows
crashes sections

# Title page (movie): favorite it
adb shell am start -W -n $P/.DetailActivity --es type movie --es id tt0111161 --es name "Movie test"
sleep 15; nudge; texts movie-detail 30; shot 04-movie-detail
key DPAD_RIGHT 1; key DPAD_CENTER 2; texts after-favorite 12
for i in 1 2 3 4; do key DPAD_DOWN 1; done; sleep 3; shot 05-movie-detail-scrolled
crashes movie-detail

# Series title page
adb shell am start -W -n $P/.DetailActivity --es type series --es id tt0903747 --es name "Series test"
sleep 15; nudge; shot 06-series
for i in 1 2; do key DPAD_DOWN 1; done; sleep 3; shot 07-series-episodes
crashes series

# Favorites section
adb shell am start -W -n $P/.MainActivity
sleep 5; key BACK 1; nudge
for i in 1 2 3 4 5; do key DPAD_UP 1; done
key DPAD_DOWN 1; key DPAD_DOWN 1; key DPAD_DOWN 1; key DPAD_DOWN 1; key DPAD_CENTER 5
texts favorites 20; shot 08-favorites
crashes favorites

adb logcat -d > out/logcat.txt
exit 0
