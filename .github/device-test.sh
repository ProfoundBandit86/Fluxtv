#!/usr/bin/env bash
# Emulator smoke test for Flux TV. Prints what's on screen after each step and any crash.
set -x
mkdir -p out
P=com.fluxtv.app

shot() { adb exec-out screencap -p > "out/$1.png"; }
top() { echo "== TOP ($1): $(adb shell dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity')"; }
texts() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml | grep -o 'text="[^"]*"' | head -${2:-40} | tr '\n' ' '; echo; }
crashes() { echo "== CRASHES after $1"; adb logcat -d -b crash | tail -80; adb logcat -d | grep -E "AndroidRuntime|FATAL EXCEPTION|ExoPlayer|PlayerActivity|fluxtv" | tail -60; }

adb install -r flux-test.apk
adb logcat -c

# 1. Home screen with Cinemeta catalogs
adb shell am start -W -n $P/.MainActivity
sleep 30
top home; texts home 60; shot 1-home

# 2. D-pad: move into the first poster row and press OK
for k in DPAD_RIGHT DPAD_RIGHT; do adb shell input keyevent $k; sleep 1; done
shot 2-focused
adb shell input keyevent DPAD_CENTER
sleep 12
top after-dpad-click; texts detail-from-dpad; shot 3-after-dpad-click
crashes dpad-click

# 3. Touch/mouse click on a poster (some remotes and mice send clicks, not D-pad)
adb shell input keyevent BACK; sleep 3
adb shell input tap 700 500
sleep 12
top after-tap; texts after-tap; shot 4-after-tap
crashes tap

# 4. Details page opened directly
adb shell am start -W -n $P/.DetailActivity --es type movie --es id tt0111161 --es name "Shawshank" --es poster ""
sleep 12
top detail; texts detail; shot 5-detail
adb shell input keyevent DPAD_CENTER
sleep 10
top after-play-button; texts after-play-button; shot 6-after-play
crashes play-button

# 5. Install Torrentio through the phone setup page, then list streams
adb shell am start -W -n $P/.AddonsActivity
sleep 5
adb forward tcp:8090 tcp:8090
curl -s -m 60 -X POST --data-urlencode "url=https://torrentio.strem.fun/manifest.json" http://127.0.0.1:8090/install | grep -o "<p class='m'>[^<]*" || echo "install page failed"
adb shell am start -W -n $P/.StreamsActivity --es pi_type movie --es pi_meta tt0111161 --es pi_video tt0111161 --es pi_name Shawshank
sleep 30
top streams; texts streams 30; shot 7-streams
adb shell input keyevent DPAD_CENTER
sleep 10
top after-stream-click; texts after-stream-click; shot 8-after-stream-click
crashes stream-click

# 6. Built-in player with a public test video
adb logcat -c
adb shell am start -W -n $P/.PlayerActivity --es url "https://storage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4" --es pi_video test1 --es pi_name "Test video" --es pi_type movie --es pi_meta test1
sleep 25
top player; texts player; shot 9-player
crashes player

# 7. Player with an HLS stream
adb shell am start -W -n $P/.PlayerActivity --es url "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8" --es pi_video test2 --es pi_name "HLS test" --es pi_type movie --es pi_meta test2
sleep 20
top player-hls; shot 10-player-hls
crashes player-hls

adb logcat -d > out/logcat.txt
exit 0
