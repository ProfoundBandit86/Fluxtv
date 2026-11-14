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

# ---------------- fake TMDB + Gemini
nohup python3 .github/fake_xtream.py > /tmp/fake.log 2>&1 < /dev/null &
FAKE_PID=$!
sleep 2
adb shell am start -W -n $P/.AddonsActivity; sleep 4
adb forward tcp:8090 tcp:8090
curl -s -m 30 -X POST --data-urlencode "tmdb=http://10.0.2.2:8000/3" --data-urlencode "gemini=http://10.0.2.2:8000/gemini/" http://127.0.0.1:8090/test-config | grep -o "<p class='m'>[^<]*"
curl -s -m 30 -X POST --data-urlencode "tmdb=fakeTmdbKey" --data-urlencode "gemini=fakeGeminiKey" http://127.0.0.1:8090/keys | grep -o "<p class='m'>[^<]*"
adb shell am force-stop $P

# Home: Continue watching, apps, services, rows
adb shell am start -W -n $P/.MainActivity
sleep 35
nudge; top home; texts home 50; shot 01-home
for i in 1 2 3; do key DPAD_DOWN 1; done; sleep 2; texts home-scrolled 40; shot 02-home-scrolled
# Sidebar: ← from the first card opens it
key DPAD_LEFT 1; key DPAD_LEFT 1; key DPAD_LEFT 2; texts drawer 20; shot 03-drawer
key DPAD_DOWN 1; texts drawer-down 30; key DPAD_CENTER 20; top movies; texts movies 30; shot 04-movies
key DPAD_DOWN 2; key DPAD_DOWN 2; shot 04b-movies-scrolled
crashes home

# Service page
adb shell am start -W -n $P/.ServiceActivity --es service netflix
sleep 12; nudge; texts service 30; shot 05-service
crashes service

# Title page -> Cast & Crew -> person -> filmography
adb shell am start -W -n $P/.DetailActivity --es type movie --es id tt0111161 --es name Test
sleep 12; nudge; texts detail 30; shot 06-detail
adb shell am start -W -n $P/.CreditsActivity --es id tt0111161 --es type movie --es name "The Shawshank Redemption"
sleep 8; nudge; texts credits 20; shot 07-credits
key DPAD_CENTER 8; top person; texts person 40; shot 08-person
nudge; key DPAD_CENTER 10; top person-click; texts person-click 10; shot 09-person-to-title
crashes credits

# Gemini search (typed), then voice
adb shell am start -W -n $P/.SearchActivity
sleep 3; adb shell input text "mind%sbending%smovies"; key ENTER 20
texts gemini 40; shot 10-gemini
adb shell am force-stop $P
adb shell am start -W -n $P/.SearchActivity --ez voice true
sleep 6; top voice; texts voice 15; shot 11-voice
crashes search

adb logcat -d > out/logcat.txt
kill $FAKE_PID 2>/dev/null
exit 0
