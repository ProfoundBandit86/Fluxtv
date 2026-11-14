#!/usr/bin/env bash
# Android TV emulator: another app publishes a Home row (preview channel); Flux should show it.
set -x
mkdir -p out
P=com.fluxtv.app
texts() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; echo "== TEXT ($1): $(adb shell cat /sdcard/ui.xml | grep -o 'text="[^"]*"' | grep -v 'text=""' | head -60 | tr '\n' ' ')"; }
adb install -r flux-test.apk
adb shell pm grant $P com.android.providers.tv.permission.READ_EPG_DATA
adb shell "content insert --uri content://android.media.tv/channel --bind type:s:TYPE_PREVIEW --bind 'display_name:s:Shell Picks' --bind input_id:s:test.input --bind browsable:i:1"
adb shell content query --uri content://android.media.tv/channel --projection _id:display_name:type
CH=$(adb shell content query --uri content://android.media.tv/channel --projection _id:display_name | grep "Shell Picks" | grep -o "_id=[0-9]*" | head -1 | cut -d= -f2)
echo "== CHANNEL id: $CH"
for t in One Two Three; do
  adb shell "content insert --uri content://android.media.tv/preview_program --bind channel_id:l:${CH:-1} --bind type:i:0 --bind 'title:s:Shell Movie $t' --bind poster_art_aspect_ratio:i:0 --bind browsable:i:1 --bind 'intent_uri:s:intent:#Intent;action=android.settings.SETTINGS;end'"
done
adb shell content query --uri content://android.media.tv/preview_program --projection _id:title:channel_id
adb shell am start -W -n $P/.MainActivity
sleep 40
adb shell input keyevent DPAD_DOWN; sleep 1; adb shell input keyevent DPAD_UP; sleep 1
for i in 1 2 3 4 5 6 7 8; do adb shell input keyevent DPAD_DOWN; sleep 1; done
texts tv-home; adb exec-out screencap -p > out/tv-01-home-app-rows.png
adb shell am start -W -n $P/.HomeRowsActivity; sleep 3; texts tv-rows; adb exec-out screencap -p > out/tv-02-customize.png
adb logcat -d -b crash | tail -40
exit 0
