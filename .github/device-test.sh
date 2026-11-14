#!/usr/bin/env bash
# Emulator smoke test for Flux TV: home -> title page -> streams -> Real-Debrid, series page, Settings colours.
set -x
mkdir -p out
P=com.fluxtv.app
shot() { adb exec-out screencap -p > "out/$1.png"; }
top() { echo "== TOP ($1): $(adb shell dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity')"; }
texts() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; echo "== TEXT ($1): $(adb shell cat /sdcard/ui.xml | grep -o 'text="[^"]*"' | grep -v 'text=""' | head -${2:-25} | tr '\n' ' ')"; }
focus() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; echo "== FOCUSED ($1): $(adb shell cat /sdcard/ui.xml | grep -o "<node[^>]*focused=\"true\"[^>]*>" | head -2 | grep -o "text=\"[^\"]\{0,40\}" | tr "\n" " ")"; }
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

# ---------------- IPTV with a fake Xtream server + M3U/EPG
nohup python3 .github/fake_xtream.py > /tmp/fake_xtream.log 2>&1 < /dev/null &
FAKE_PID=$!
sleep 2
curl -s "http://127.0.0.1:8000/player_api.php?username=test&password=test" | head -c 200; echo
adb shell am start -W -n $P/.IptvActivity; sleep 4
adb forward tcp:8090 tcp:8090
curl -s -m 60 -X POST --data-urlencode "url=http://10.0.2.2:8000" --data-urlencode "user=test" --data-urlencode "pass=wrong" http://127.0.0.1:8090/iptv-xtream | grep -o "<p class='m'>[^<]*"
curl -s -m 60 -X POST --data-urlencode "url=http://10.0.2.2:8000/player_api.php?username=x" --data-urlencode "user=test" --data-urlencode "pass=test" http://127.0.0.1:8090/iptv-xtream | grep -o "<p class='m'>[^<]*"
curl -s -m 60 -X POST --data-urlencode "m3u=http://10.0.2.2:8000/list.m3u" --data-urlencode "epg=http://10.0.2.2:8000/epg.xml" http://127.0.0.1:8090/iptv-m3u | grep -o "<p class='m'>[^<]*"
sleep 2; texts iptv-settings 30; shot 10-iptv-settings
adb shell am force-stop $P

adb shell am start -W -n $P/.LiveActivity
sleep 12; nudge; top live; texts live 40; shot 11-live
crashes live
key DPAD_RIGHT 2; focus live-focus 2>/dev/null; key DPAD_CENTER 3; key BACK 2
adb logcat -c
adb shell am start -W -n $P/.PlayerActivity --ez live true --es live_source ca0e9097 --es live_category 1 --ei live_index 0 --es url "http://10.0.2.2:8000/live/test/test/1001.ts" --es pi_name "Flux News"
sleep 20
top live-player; texts live-player 10; shot 12-live-player
key DPAD_DOWN 4; texts channel-down 10; shot 13-channel-down
crashes live-player
adb logcat -d | grep -E "ExoPlayerImplInternal|fluxtv" | tail -15
key BACK 2; key BACK 2

# Movies section shows the provider's categories
adb shell am start -W -n $P/.MainActivity
sleep 35
nudge; key DPAD_DOWN 1; key DPAD_DOWN 1; key DPAD_CENTER 30
key DPAD_RIGHT 2; key DPAD_DOWN 2; key DPAD_DOWN 2; sleep 2
texts movies-with-iptv 80; shot 14-movies-iptv
crashes movies-iptv

# Search blends provider movies & series
adb shell am start -W -n $P/.SearchActivity
sleep 3; adb shell input text "Test"; key ENTER 25
texts search-iptv 40; shot 15-search-iptv
crashes search

adb logcat -d > out/logcat.txt
kill $FAKE_PID 2>/dev/null
exit 0
