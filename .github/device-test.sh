#!/usr/bin/env bash
# Emulator smoke test for Flux TV: D-pad through home -> details -> streams -> Real-Debrid -> player.
set -x
mkdir -p out
P=com.fluxtv.app
shot() { adb exec-out screencap -p > "out/$1.png"; }
top() { echo "== TOP ($1): $(adb shell dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity')"; }
texts() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; echo "== TEXT ($1): $(adb shell cat /sdcard/ui.xml | grep -o 'text="[^"]*"' | grep -v 'text=""' | head -${2:-25} | tr '\n' ' ')"; }
focus() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; echo "== FOCUSED ($1): $(adb shell cat /sdcard/ui.xml | grep -o '<node[^>]*focused="true"[^>]*>' | head -3 | grep -o 'class="[^"]*"\|text="[^"]\{0,60\}\|bounds="[^"]*"' | tr '\n' ' ')"; }
key() { adb shell input keyevent "$1"; sleep "${2:-1}"; }
crashes() { echo "== CRASHES ($1)"; adb logcat -d -b crash | tail -60; adb logcat -d | grep -E "FATAL EXCEPTION|E AndroidRuntime|ExoPlayerImplInternal|fluxtv" | tail -40; }

adb install -r flux-test.apk
# Pretend Real-Debrid is signed in (dummy token) and install Torrentio via the phone setup page.
adb shell am start -W -n $P/.AddonsActivity; sleep 5
adb forward tcp:8090 tcp:8090
curl -s -m 90 -X POST --data-urlencode "url=https://torrentio.strem.fun/manifest.json" http://127.0.0.1:8090/install | grep -o "<p class='m'>[^<]*"
curl -s -m 30 -X POST --data-urlencode "key=DUMMYTOKEN123" http://127.0.0.1:8090/rdkey | grep -o "<p class='m'>[^<]*"
key BACK 2
adb shell am force-stop $P
adb logcat -c

# Home, D-pad only (real TV behaviour; no taps so we never enter touch mode)
adb shell am start -W -n $P/.MainActivity
sleep 30
top home; focus home; texts home; shot 1-home
for i in 1 2 3 4; do key DPAD_RIGHT 2; focus right-$i; done
for i in 1 2; do key DPAD_DOWN 2; focus down-$i; done
shot 2-after-keys
key DPAD_CENTER 10
top after-ok; texts after-ok 12; shot 3-after-ok
adb shell uiautomator dump /sdcard/ui.xml; adb shell cat /sdcard/ui.xml > out/detail-ui.xml; head -c 1500 out/detail-ui.xml; echo
sleep 10; shot 3b-detail-later
crashes ok
# Direct click on the first poster's title
adb shell am start -W -n $P/.MainActivity; sleep 5
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
B=$(adb shell cat /sdcard/ui.xml | grep -o 'text="Heart of the Beast"[^>]*bounds="[^"]*"' | grep -o 'bounds="[^"]*"' | head -1)
echo "== poster title bounds: $B"
XY=$(echo "$B" | sed -E 's/bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\1 \2 \3 \4/' | awk '{print int(($1+$3)/2), $2-150}')
echo "== tapping $XY"; adb shell input tap $XY; sleep 10
top after-tap; texts after-tap 12; shot 4-after-tap
crashes tap

# Player check with HLS + MP4 test streams (opened directly)
adb logcat -c
adb shell am start -W -n $P/.PlayerActivity --es url "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8" --es pi_video t1 --es pi_name HLS --es pi_type movie --es pi_meta t1
sleep 20; top player-hls; texts player-hls; shot 7-player-hls; crashes player-hls
key BACK 2
adb shell am start -W -n $P/.PlayerActivity --es url "https://download.blender.org/peach/bigbuckbunny_movies/BigBuckBunny_320x180.mp4" --es pi_video t2 --es pi_name MP4 --es pi_type movie --es pi_meta t2
sleep 20; top player-mp4; texts player-mp4; shot 8-player-mp4; crashes player-mp4

adb logcat -d > out/logcat.txt
exit 0
