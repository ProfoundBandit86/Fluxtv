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
if [ -f .github/quick.sh ]; then source .github/quick.sh; exit 0; fi
# Quick mode: only the Home button test
if [ -f .github/QUICK_HOME ]; then
  adb shell settings put secure enabled_accessibility_services $P/$P.HomeButtonService
  adb shell settings put secure accessibility_enabled 1
  sleep 5
  adb shell dumpsys accessibility | grep -i -E "flux|bound|enabled services|Filter|keyEvent" | head -30
  adb shell cmd package set-home-activity com.google.android.apps.nexuslauncher/.NexusLauncherActivity
  adb shell am start -W -n $P/.SettingsActivity; sleep 4
  adb shell input keyevent HOME; sleep 7; top sw-home-7s
  adb shell am start -W -n $P/.SettingsActivity; sleep 3
  adb emu event send EV_KEY:KEY_HOMEPAGE:1 EV_SYN:0:0; sleep 0.1; adb emu event send EV_KEY:KEY_HOMEPAGE:0 EV_SYN:0:0
  sleep 4; top hw-home-4s
  adb shell getevent -lp 2>/dev/null | grep -i -B3 HOMEPAGE | head
  adb logcat -d | grep -E "FluxHome|AccessibilityManager|fluxtv" | grep -v NodeInfoDumper | tail -40
  shot quick-home
  exit 0
fi
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
# Trakt (fake) + a subtitles add-on
curl -s -m 30 -X POST --data-urlencode "tmdb=http://10.0.2.2:8000/3" --data-urlencode "gemini=http://10.0.2.2:8000/gemini/" --data-urlencode "trakt=http://10.0.2.2:8000/trakt" http://127.0.0.1:8090/test-config | grep -o "<p class='m'>[^<]*"
curl -s -m 60 -X POST --data-urlencode "url=http://10.0.2.2:8000/subs/manifest.json" http://127.0.0.1:8090/install | grep -o "<p class='m'>[^<]*"
curl -s -m 30 -X POST --data-urlencode "id=fakeClientId" http://127.0.0.1:8090/trakt-app | grep -o "<p class='m'>[^<]*"
curl -s -m 30 -X POST --data-urlencode "name=Default" http://127.0.0.1:8090/trakt-link | grep -o "<p class='m'>[^<]*"
sleep 8
curl -s -m 30 http://127.0.0.1:8090/ > out/settings-page.html
echo "== SETTINGS PAGE: $(grep -o '<summary>[^<]*' out/settings-page.html | tr '\n' ' ')"
echo "== TRAKT STATUS: $(grep -o 'Trakt: [^<]*' out/settings-page.html | head -3 | tr '\n' ' ')"
curl -s -m 30 -X POST --data-urlencode "player_sub_size=2" --data-urlencode "player_sub_style=1" --data-urlencode "player_audio_lang=en" http://127.0.0.1:8090/player | grep -o "<p class='m'>[^<]*"
curl -s -m 30 -X POST --data-urlencode "name=Kids" http://127.0.0.1:8090/profile-add | grep -o "<p class='m'>[^<]*"
# Radarr, Sonarr, SABnzbd (fake)
curl -s -m 30 -X POST --data-urlencode "which=radarr" --data-urlencode "url=http://10.0.2.2:8000/radarr" --data-urlencode "key=rk" http://127.0.0.1:8090/arr | grep -o "<p class='m'>[^<]*"
curl -s -m 30 -X POST --data-urlencode "which=sonarr" --data-urlencode "url=10.0.2.2:8000/sonarr/" --data-urlencode "key=sk" http://127.0.0.1:8090/arr | grep -o "<p class='m'>[^<]*"
curl -s -m 30 -X POST --data-urlencode "which=sab" --data-urlencode "url=http://10.0.2.2:8000/sabnzbd" --data-urlencode "key=bk" http://127.0.0.1:8090/arr | grep -o "<p class='m'>[^<]*"
curl -s -m 30 http://127.0.0.1:8090/ > out/settings-page-arr.html; echo "== ARR SETTINGS: $(grep -o "Connected to [^<]*" out/settings-page-arr.html | tr '\n' ' ')"
adb shell am force-stop $P

# Another app's Home row (Android TV preview channel), published from the shell
adb shell pm grant $P com.android.providers.tv.permission.READ_EPG_DATA
adb shell content insert --uri content://android.media.tv/channel --bind type:s:TYPE_PREVIEW --bind display_name:s:"Shell Picks" --bind input_id:s:test.input --bind browsable:i:1
CH=$(adb shell content query --uri content://android.media.tv/channel --projection _id:display_name | grep "Shell Picks" | grep -o "_id=[0-9]*" | head -1 | cut -d= -f2)
echo "== CHANNEL id: $CH"
for t in "Shell Movie One" "Shell Movie Two" "Shell Movie Three"; do
  adb shell content insert --uri content://android.media.tv/preview_program --bind channel_id:l:${CH:-1} --bind type:i:0 --bind "title:s:$t" --bind poster_art_aspect_ratio:i:0 --bind browsable:i:1 --bind "intent_uri:s:intent:#Intent;action=android.settings.SETTINGS;end"
done
adb shell content query --uri content://android.media.tv/preview_program --projection _id:title:channel_id | head -5

# Home: Continue watching, apps, services, rows
adb shell am start -W -n $P/.MainActivity
sleep 35
nudge; top home; texts home 60; shot 01-home
for i in 1 2 3; do key DPAD_DOWN 1; done; sleep 2; texts home-scrolled 40; shot 02-home-scrolled
key DPAD_LEFT 2; texts row-left-drawer 30; shot 02b-row-left-opens-drawer; key DPAD_RIGHT 2
# Sidebar: ← from the first card opens it
shot 03a-closed-edge; key DPAD_LEFT 1; key DPAD_LEFT 1; key DPAD_LEFT 2; texts drawer 20; shot 03-drawer
for i in 1 2 3 4 5 6 7 8 9 10; do key DPAD_DOWN 0; done; sleep 1; shot 03b-drawer-bottom
for i in 1 2 3 4 5 6 7 8 9 10; do key DPAD_UP 0; done; sleep 1; key DPAD_DOWN 1
key DPAD_DOWN 1; texts drawer-down 30; key DPAD_CENTER 20; top movies; texts movies 30; shot 04-movies
texts movies-genres 40; key DPAD_DOWN 2; key DPAD_DOWN 2; shot 04b-movies-scrolled
crashes home

# Rows from other apps + Customize Home lists them
for i in 1 2 3 4 5 6; do key DPAD_DOWN 1; done; sleep 2; texts home-deep 60; shot 04c-home-deep
adb shell am start -W -n $P/.HomeRowsActivity; sleep 3; texts home-rows 60; shot 04d-home-rows
crashes home-rows

# Profiles & Trakt, Settings
adb shell am start -W -n $P/.ProfilesActivity; sleep 4; nudge; texts profiles 40; shot 13-profiles
adb shell am start -W -n $P/.SettingsActivity; sleep 4; texts settings 60; shot 14-settings
key DPAD_DOWN 1; key DPAD_DOWN 1; key DPAD_DOWN 1; key DPAD_DOWN 1; shot 14b-settings-scrolled
crashes settings

# Player options: audio, subtitles (built-in and from an add-on), speed
tap() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; B=$(adb shell cat /sdcard/ui.xml | grep -o "<node[^>]*text=\"$1[^>]*>" | head -1 | grep -o 'bounds="[^"]*"' | grep -o '[0-9]*' | tr '\n' ' '); set -- $B; if [ -n "$4" ]; then adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); else echo "== no element for tap"; fi; }
adb shell am start -W -n $P/.PlayerActivity --es url "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8" --es pi_type movie --es pi_meta tt0068646 --es pi_video tt0068646 --es pi_name PlayerTest
sleep 20; shot 15-player
key MENU 2; texts player-menu 30; shot 16-player-menu
tap "Audio"; sleep 2; texts player-audio 30; shot 17-player-audio
key BACK 1; key MENU 2; tap "Subtitles"; sleep 2; texts player-subs 40; shot 18-player-subs
tap "Find subtitles"; sleep 6; texts player-subs-found 40; shot 19-player-subs-found
tap "English&#10;Flux"; sleep 12; texts player-addon-sub 20; shot 20-player-addon-subtitle
key DPAD_UP 0; shot 21-player-bar
key DPAD_RIGHT 1; key DPAD_RIGHT 1; key DPAD_RIGHT 1; key DPAD_RIGHT 1; key DPAD_CENTER 1; shot 21b-player-popover; key BACK 1
key BACK 2; key DPAD_RIGHT 0; key DPAD_RIGHT 0; key DPAD_RIGHT 0; shot 21c-player-skip
key DPAD_RIGHT 1; key DPAD_RIGHT 1; key DPAD_CENTER 2; texts player-speed 20; shot 22-player-speed
key BACK 1; key BACK 1; key BACK 3
crashes player
echo "== TRAKT LOG"; curl -s http://127.0.0.1:8000/trakt/_log | python3 -c "import json,sys; [print(e['path'], e['body'][:160]) for e in json.load(sys.stdin)]"

# Radarr: add from the title page
adb shell am start -W -n $P/.DetailActivity --es type movie --es id tt0111161 --es name Test
sleep 12; nudge; texts detail-arr 30; shot 23-detail-radarr
tap "+ Radarr"; sleep 4; texts arr-quality 20; shot 24-arr-quality
tap "HD-1080p"; sleep 6; texts arr-added 30; shot 25-arr-added
adb shell am start -W -n $P/.DetailActivity --es type series --es id tt0903747 --es name "Breaking Bad"
sleep 12; nudge; texts detail-sonarr 30; shot 26-detail-sonarr
adb shell am start -W -n $P/.DownloadsActivity; sleep 6; texts downloads 60; shot 27-downloads
adb shell am start -W -n $P/.ArrSetupActivity; sleep 5; texts arr-setup 60; shot 28-arr-setup
crashes arr
echo "== ARR LOG"; curl -s http://127.0.0.1:8000/arr/_log | python3 -c "import json,sys; d=json.load(sys.stdin); [print(e['path'], e.get('body','')[:200]) for e in d['arr'] if 'body' in e]; [print('GEMINI', e['path'], e['body'][-260:]) for e in d['gemini']]"
# Fallback: an episode with no streams anywhere goes to Sonarr
adb shell am start -W -n $P/.StreamsActivity --es pi_type series --es pi_meta tt9999997 --es pi_video tt9999997:2:3 --es pi_name FallbackShow --es pi_label S2E3
sleep 15; texts fallback 30; shot 31-fallback-sonarr
# Genres in Movies
adb shell am start -W -n $P/.GenreActivity --es type movie --es genre Action
sleep 12; nudge; texts genre 30; shot 32-genre-action
crashes fallback
echo "== ARR LOG 2"; curl -s http://127.0.0.1:8000/arr/_log | python3 -c "import json,sys; d=json.load(sys.stdin); [print(e['path'], e.get('body','')[:160]) for e in d['arr'] if 'body' in e]"
# Play from the title page: a starting screen, not the stream list
adb shell am start -W -n $P/.StreamsActivity --ez autoplay true --es pi_type movie --es pi_meta tt0133093 --es pi_video tt0133093 --es pi_name Matrix
sleep 2; shot 40-play-starting; sleep 10; top play-auto; shot 41-play-after
key BACK 2; key BACK 2

# Sidebar on other screens
adb shell am start -W -n $P/.DetailActivity --es type movie --es id tt0111161 --es name Test
sleep 12; nudge; key DPAD_LEFT 1; key DPAD_LEFT 1; key DPAD_LEFT 1; key DPAD_LEFT 2; texts detail-sidebar 40; shot 33-detail-sidebar
key DPAD_DOWN 1; key DPAD_DOWN 1; key DPAD_CENTER 6; top sidebar-to-movies; texts sidebar-to-movies 12
adb shell am start -W -n $P/.SettingsActivity; sleep 4; key DPAD_LEFT 2; texts settings-sidebar 20; shot 34-settings-sidebar
key BACK 2; texts settings-after-back 8
crashes sidebar
# Remote-button link to a service page
adb shell am start -W -a android.intent.action.VIEW -d "fluxtv://service/disney"; sleep 8; top deeplink; texts deeplink 10; shot 29-deeplink-disney

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
adb shell am start -W -n $P/.SearchActivity --es query "mind bending movies"
sleep 20
texts gemini 40; shot 10-gemini
adb shell am force-stop $P
adb shell pm grant $P android.permission.RECORD_AUDIO
adb shell am start -W -n $P/.SearchActivity
sleep 4; shot 11a-search-start; texts search-start 30
adb shell am start -W -n $P/.SearchActivity --ez voice true
sleep 3; top voice; shot 11-voice; sleep 5; texts voice 15; shot 11b-voice-after
adb shell am start -W -n $P/.SearchActivity --ez demo_listen true; sleep 3; shot 11c-listening-demo
crashes search

# Themes
adb shell am start -W -n $P/.SettingsActivity --es category appearance; sleep 4; shot 38-themes
curl -s -m 20 -X POST --data-urlencode "theme=2" http://127.0.0.1:8090/appearance | grep -o "<p class='m'>[^<]*"
adb shell am start -W -n $P/.MainActivity; sleep 8; nudge; shot 39-home-ocean-theme
curl -s -m 20 -X POST --data-urlencode "theme=0" http://127.0.0.1:8090/appearance > /dev/null
crashes themes

# Quick Settings panel (also its own launcher entry)
adb shell am start -W -n $P/.QuickSettingsActivity
sleep 4; nudge; top quick; texts quick 30; shot 12-quick-settings
key DPAD_RIGHT 1; key DPAD_CENTER 5; top quick-open; key BACK 2
crashes quick

# Continue watching sources + Gemini request settings
adb shell am start -W -n $P/.HomeRowsActivity; sleep 3; texts cw-sources 20; shot 30-cw-sources
tap "✓ Flux"; sleep 2; texts cw-sources-after 12
curl -s http://127.0.0.1:8000/arr/_log | python3 -c "import json,sys; d=json.load(sys.stdin); [print('GEMINI', e['path'], e['body'][-200:]) for e in d['gemini']]"
crashes cw
# Home button takeover via the accessibility service (like Projectivy)
adb shell settings put secure enabled_accessibility_services $P/$P.HomeButtonService
adb shell settings put secure accessibility_enabled 1
sleep 3
adb shell am start -W -n $P/.SettingsActivity --es category home; sleep 4; texts settings-home 30; shot 35-settings-home
adb shell cmd package set-home-activity com.google.android.apps.nexuslauncher/.NexusLauncherActivity
adb logcat -c
adb shell am start -W -n $P/.SettingsActivity; sleep 3
# A hardware Home press (through the emulator's virtual keyboard, like a real remote)
adb emu event send EV_KEY:KEY_HOMEPAGE:1 EV_SYN:0:0; sleep 0.1; adb emu event send EV_KEY:KEY_HOMEPAGE:0 EV_SYN:0:0
sleep 2; top after-hw-home-2s; adb logcat -d | grep FluxHome | tail -6
# Software Home (adb input) for comparison
adb shell am start -W -n $P/.SettingsActivity; sleep 3
adb shell input keyevent HOME; sleep 7; top after-sw-home-7s; adb logcat -d | grep FluxHome | tail -6
# Hold Home
adb shell am start -W -n $P/.SettingsActivity; sleep 3
adb emu event send EV_KEY:KEY_HOMEPAGE:1 EV_SYN:0:0; sleep 1.2; adb emu event send EV_KEY:KEY_HOMEPAGE:0 EV_SYN:0:0
sleep 3; top after-long-home; shot 37-long-home
shot 36-after-home-button
curl -s -m 20 http://127.0.0.1:8090/ > /dev/null
adb shell am start -W -n $P/.SettingsActivity; sleep 3
curl -s -m 20 http://127.0.0.1:8090/ > out/settings-page-final.html; echo "== WEB SECTIONS: $(grep -o "<summary>[^<]*" out/settings-page-final.html | tr '\n' ' ')"
crashes home-button
adb logcat -d > out/logcat.txt
kill $FAKE_PID 2>/dev/null
exit 0
