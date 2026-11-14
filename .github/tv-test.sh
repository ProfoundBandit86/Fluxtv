#!/usr/bin/env bash
# Android TV emulator: a published Home row (preview channel) should show on Flux's Home.
set -x
mkdir -p out
P=com.fluxtv.app
texts() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; echo "== TEXT ($1): $(adb shell cat /sdcard/ui.xml | grep -o 'text="[^"]*"' | grep -v 'text=""' | head -60 | tr '\n' ' ')"; }
adb install -r flux-test.apk
adb shell pm grant $P android.permission.READ_TV_LISTINGS
adb shell dumpsys package $P | grep -E "TV_LISTINGS|EPG"
adb shell am start -W -n $P/.AddonsActivity; sleep 5
adb forward tcp:8090 tcp:8090
curl -s -m 30 -X POST http://127.0.0.1:8090/test-channel | grep -o "<p class='m'>[^<]*"
adb shell am force-stop $P
adb shell am start -W -n $P/.MainActivity
sleep 40
adb shell input keyevent DPAD_DOWN; sleep 1; adb shell input keyevent DPAD_UP; sleep 1
for i in 1 2 3 4 5 6 7 8 9 10; do adb shell input keyevent DPAD_DOWN; sleep 1; texts tv-home-$i | grep -i -E "shell|test movie" ; done
adb exec-out screencap -p > out/tv-01-home-app-rows.png
adb shell am start -W -n $P/.HomeRowsActivity; sleep 3; texts tv-rows; adb exec-out screencap -p > out/tv-02-customize.png
adb logcat -d -b crash | tail -40
adb logcat -d | grep -i -E "TvProvider|SecurityException" | tail -20
exit 0
