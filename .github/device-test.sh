#!/usr/bin/env bash
# Emulator test for Flux TV, in sections.
#   FLUX_TEST_MODE=full  → every section (≈50 min).
#   FLUX_TEST_MODE=quick → the core sections (home, settings, player, title page, themes) plus the sections
#                          that match the files changed versus main (listed in /tmp/changed.txt). ≈10–20 min.
# A change to anything that affects every screen (App, Http, ImageLoader, Store, the manifest, build files…)
# makes a quick run behave like a full one.
set -x
mkdir -p out
P=com.fluxtv.app
shot() { adb exec-out screencap -p > "out/$1.png"; }
top() { echo "== TOP ($1): $(adb shell dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity')"; }
texts() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; echo "== TEXT ($1): $(adb shell cat /sdcard/ui.xml | grep -o 'text="[^"]*"' | grep -v 'text=""' | head -${2:-25} | tr '\n' ' ')"; }
focus() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; echo "== FOCUSED ($1): $(adb shell cat /sdcard/ui.xml | grep -o "<node[^>]*focused=\"true\"[^>]*>" | head -2 | grep -o "text=\"[^\"]\{0,40\}" | tr "\n" " ")"; }
key() { adb shell input keyevent "$1"; sleep "${2:-1}"; }
focused() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; echo "== FOCUSED ($1): $(adb shell cat /sdcard/ui.xml | python3 -c "
import sys, re
x = sys.stdin.read()
i = x.find('focused=\"true\"')
s = x.rfind('<node', 0, i)
print(' '.join(re.findall(r'text=\"([^\"]+)\"', x[s:s+3000])[:4]))")"; }
nudge() { key DPAD_DOWN 1; key DPAD_UP 1; }
crashes() { echo "== CRASHES ($1)"; adb logcat -d -b crash | tail -60; }
tap() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; B=$(adb shell cat /sdcard/ui.xml | grep -o "<node[^>]*text=\"$1[^>]*>" | head -1 | grep -o 'bounds="[^"]*"' | grep -o '[0-9]*' | tr '\n' ' '); set -- $B; if [ -n "$4" ]; then adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); else echo "== no element for tap"; fi; }
longtap() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; B=$(adb shell cat /sdcard/ui.xml | grep -o "<node[^>]*text=\"$1[^>]*>" | head -1 | grep -o 'bounds="[^"]*"' | grep -o '[0-9]*' | tr '\n' ' '); set -- $B; if [ -n "$4" ]; then adb shell input swipe $(( ($1+$3)/2 )) $(( ($2+$4)/2 )) $(( ($1+$3)/2 )) $(( ($2+$4)/2 )) 1300; else echo "== no element for longtap"; fi; }
# Waits until some on-screen text starts with $1, for at most $2 seconds (the old fixed wait), so screens that
# are ready early don't sit idle. Checks every ~3 s (a screen dump itself takes a moment).
# POST to the phone setup page, retrying while it starts up (it restarts each time Settings reopens).
post() { local path=$1; shift; local R; for i in 1 2 3 4 5; do R=$(curl -s -m 90 -X POST "$@" "http://127.0.0.1:8090$path" | grep -o "<p class='m'>[^<]*"); [ -n "$R" ] && { echo "$R"; return 0; }; sleep 3; done; echo "== NO ANSWER from $path"; }
waitfor() { local end=$(( $(date +%s) + $2 )); while [ "$(date +%s)" -lt "$end" ]; do
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  if adb shell cat /sdcard/ui.xml | grep -q "text=\"$1"; then echo "== READY ($1) after $(( $2 - end + $(date +%s) ))s"; return 0; fi
  sleep 2; done; echo "== NOT SEEN ($1) in $2 s"; }

# ---------------- which sections run
MODE=${FLUX_TEST_MODE:-full}
CHANGED=$(cat /tmp/changed.txt 2>/dev/null | tr '\n' ' ')
echo "== MODE: $MODE; changed: $CHANGED"
WANT=" core "
add() { WANT="$WANT $* "; }
for f in $CHANGED; do
  case "$f" in
    *.github/device-test.sh|*.github/workflows/*|*.github/fake_xtream.py|*.md|*.github/tv-test.sh) ;;
    */MainActivity.java|*/Blend.java|*/HomeLayout.java|*/HomeRowsActivity.java|*/WatchNext.java|*/ContinueWatching.java) add home-rows cw home-instant apps-sync ;;
    */Apps.java|*/AppsActivity.java) add apps-sync ;;
    */FluxActivity.java) add sidebar ;;
    */DetailActivity.java|*/CreditsActivity.java|*/PersonActivity.java|*/GenreActivity.java) add credits sidebar arr ;;
    */StreamsActivity.java|*/Sources.java|*/Addon.java|*/Addons.java|*/AddonsActivity.java|*/RealDebrid.java|*/PlayInfo.java) add arr ;;
    */Arr.java|*/ArrSetupActivity.java|*/Sab.java|*/DownloadsActivity.java) add arr ;;
    */ServiceActivity.java|*/ServiceShortcutActivity.java) add service sidebar ;;
    */SearchActivity.java|*/Gemini.java|*/VoiceWave.java) add search gemini ;;
    */QuickSettingsActivity.java) add quick ;;
    */HomeButtonService.java|*/Updater.java|*/UpdateReceiver.java) add home-button smarthome ;;
    */Iptv.java|*/IptvActivity.java|*/LiveActivity.java|*/LivePlayerActivity.java|*/GuideView.java|*/Dvr*.java|*/Smb*.java|*/RecordingsActivity.java) add live epg portal ;;
    */Stalker.java|*/MultiviewActivity.java|*/Sports.java|*/SportsActivity.java) add live portal ;;
    */SmartHome.java|*/HomeAssistant.java|*/GoogleCommands.java|*/SmartPanel*.java) add live smarthome ;;
    */ProfileSync.java|*/ProfilesActivity.java|*/Ratings.java|*/Trakt.java|*/AccountActivity.java) add apps-sync ;;
    */Theme.java|*/Ui.java|*/SettingsActivity.java|*/ConfigServer.java|*/FocusScroll.java) add theme-place sidebar quick ;;
    */PlayerActivity.java|*/Players.java|*/Segments.java) add skip-next gemini ;;
    */Plex.java|*/Jellyfin.java|*/MediaServer.java|*/ServersActivity.java|*/Tmdb.java) ;;   # no fake server for these yet: core only
    *) MODE=full ;;   # App, Http, ImageLoader, Store, manifest, build files, resources… touch everything
  esac
done
want() { [ "$MODE" = full ] || [[ "$WANT" == *" $1 "* ]]; }
# Sections that need Live TV set up first
if want epg || want portal || want smarthome; then add live; fi
echo "== SECTIONS: $([ "$MODE" = full ] && echo all || echo $WANT)"

adb install -r flux-test.apk
if [ -f .github/quick.sh ]; then source .github/quick.sh; exit 0; fi
# Quick mode: only the Home button test
if [ -f .github/QUICK_HOME ]; then
  adb shell settings put secure enabled_accessibility_services $P/$P.HomeButtonService
  adb shell settings put secure accessibility_enabled 1
  sleep 5
  adb shell dumpsys accessibility | grep -i -E "flux|bound|enabled services|Filter|keyEvent" | head -30
  adb shell cmd package set-home-activity com.google.android.apps.nexuslauncher/.NexusLauncherActivity
  adb shell am start -W -f 0x10008000 -n $P/.SettingsActivity; sleep 4
  adb shell input keyevent HOME; sleep 7; top sw-home-7s
  adb shell am start -W -f 0x10008000 -n $P/.SettingsActivity; sleep 3
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
(command -v ffmpeg >/dev/null || sudo apt-get install -y -q ffmpeg > /tmp/ffmpeg-install.log 2>&1; ffmpeg -loglevel error -y -f lavfi -i testsrc=size=640x360:rate=25 -f lavfi -i sine=frequency=440 -t 90 -c:v libx264 -preset veryfast -pix_fmt yuv420p -c:a aac -f mpegts /tmp/flux-test.ts && echo "== TEST TS: $(ls -la /tmp/flux-test.ts)") || echo "== TEST TS: ffmpeg failed"
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
# Weather for the clock badge (fake Open-Meteo)
curl -s -m 30 -X POST --data-urlencode "tmdb=http://10.0.2.2:8000/3" --data-urlencode "gemini=http://10.0.2.2:8000/gemini/" --data-urlencode "trakt=http://10.0.2.2:8000/trakt" --data-urlencode "weather=http://10.0.2.2:8000/weather" http://127.0.0.1:8090/test-config | grep -o "<p class='m'>[^<]*"
curl -s -m 30 -X POST --data-urlencode "place=Chicago" http://127.0.0.1:8090/weather | grep -o "<p class='m'>[^<]*"
# MDBList (fake): key + one list as a Home row
curl -s -m 30 -X POST --data-urlencode "tmdb=http://10.0.2.2:8000/3" --data-urlencode "gemini=http://10.0.2.2:8000/gemini/" --data-urlencode "trakt=http://10.0.2.2:8000/trakt" --data-urlencode "mdblist=http://10.0.2.2:8000/mdblist" http://127.0.0.1:8090/test-config | grep -o "<p class='m'>[^<]*"
post /keys --data-urlencode "mdblist=fakeMdbKey"
post /mdblist-add --data-urlencode "url=https://mdblist.com/lists/linaspurinis/top-watched-movies-of-the-week"
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
waitfor "Your apps" 35; sleep 2
# Android's one-time "Viewing full screen" notice takes the remote's keys until it's dismissed.
T=$(texts first-start 40); case "$T" in *"Got it"*) tap "Got it"; sleep 2;; esac
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
# Rows slide up when browsing below the first row; Settings → Categories (MDBList) and dock items
adb shell am start -W -n $P/.MainActivity; sleep 6; nudge; key DPAD_DOWN 1; key DPAD_DOWN 1; sleep 1; shot 02c-home-rows-up; texts home-rows-up 30
adb shell am start -W -f 0x10008000 -n $P/.SettingsActivity --es category categories; sleep 6; texts settings-categories 60; shot 14c-settings-categories
for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14; do key DPAD_DOWN 0; done; sleep 2; shot 14d-settings-categories-mdblist; texts settings-mdblist 40
adb shell am start -W -f 0x10008000 -n $P/.SettingsActivity --es category home; sleep 5; key DPAD_DOWN 1; key DPAD_RIGHT 1; key DPAD_RIGHT 1; for i in 1 2 3 4 5 6 7 8 9 10 11 12; do key DPAD_DOWN 0; done; sleep 1; shot 14e-settings-dock-items; texts settings-dock-items 40
adb shell am start -W -n $P/.MainActivity --es section movie; sleep 8; nudge; texts movies-genres-top 30; shot 04e-movies-genres-top
crashes categories
if want home-rows; then
# Rows from other apps + Customize Home lists them
for i in 1 2 3 4 5 6; do key DPAD_DOWN 1; done; sleep 2; texts home-deep 60; shot 04c-home-deep
adb shell am start -W -n $P/.HomeRowsActivity; sleep 3; texts home-rows 60; shot 04d-home-rows
crashes home-rows
fi
# Profiles & Trakt, Settings
adb shell am start -W -n $P/.ProfilesActivity; sleep 4; nudge; texts profiles 40; shot 13-profiles
adb shell am start -W -f 0x10008000 -n $P/.SettingsActivity; sleep 4; texts settings 60; shot 14-settings
key DPAD_DOWN 1; key DPAD_DOWN 1; key DPAD_DOWN 1; key DPAD_DOWN 1; shot 14b-settings-scrolled
crashes settings
# Player options: audio, subtitles (built-in and from an add-on), speed
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
if want arr; then
# Radarr: add from the title page
adb shell am start -W -n $P/.DetailActivity --es type movie --es id tt0111161 --es name Test
waitfor Trailer 12; nudge; texts detail-arr 30; shot 23-detail-radarr
tap "+ Radarr"; sleep 4; texts arr-quality 20; shot 24-arr-quality
tap "HD-1080p"; sleep 6; texts arr-added 30; shot 25-arr-added
adb shell am start -W -n $P/.DetailActivity --es type series --es id tt0903747 --es name "Breaking Bad"
waitfor Trailer 12; nudge; texts detail-sonarr 30; shot 26-detail-sonarr
tap "Wait"; sleep 1; nudge; for i in 1 2 3 4; do F=$(focused season-find); echo "$F"; case "$F" in *Season*) break;; esac; key DPAD_DOWN 1; done
key DPAD_CENTER 2; texts seasons-menu 30; shot 26b-seasons-menu
key DPAD_DOWN 1; key DPAD_DOWN 1; key DPAD_CENTER 2; texts seasons-picked 30; shot 26c-seasons-picked
adb shell am start -W -n $P/.StreamsActivity --es pi_type movie --es pi_meta tt0111161 --es pi_video tt0111161 --es pi_name Test; sleep 12; tap "Wait"; sleep 1; texts streams-top10 80; shot 26d-streams-top10
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
fi
# Play from the title page: a starting screen, not the stream list
adb shell am start -W -n $P/.StreamsActivity --ez autoplay true --es pi_type movie --es pi_meta tt0133093 --es pi_video tt0133093 --es pi_name Matrix
sleep 2; shot 40-play-starting; sleep 10; top play-auto; shot 41-play-after
key BACK 2; key BACK 2
if want sidebar; then
# Sidebar on other screens
adb shell am start -W -n $P/.DetailActivity --es type movie --es id tt0111161 --es name Test
waitfor Trailer 12; nudge; key DPAD_LEFT 1; key DPAD_LEFT 1; key DPAD_LEFT 1; key DPAD_LEFT 2; texts detail-sidebar 40; shot 33-detail-sidebar
key DPAD_DOWN 1; key DPAD_DOWN 1; key DPAD_CENTER 6; top sidebar-to-movies; texts sidebar-to-movies 12
adb shell am start -W -f 0x10008000 -n $P/.SettingsActivity; sleep 4; key DPAD_LEFT 2; texts settings-sidebar 20; shot 34-settings-sidebar
key BACK 2; texts settings-after-back 8
# Same dock on Apps: icons stay put, ← glides it open with names; picking Sports swaps the screen behind it
adb shell am start -W -n $P/.AppsActivity; sleep 5; nudge; shot 34b-apps-dock
for i in 1 2 3 4 5 6; do key DPAD_LEFT 0; done; sleep 1; texts apps-dock-open 30; shot 34c-apps-dock-open
key DPAD_UP 1; key DPAD_UP 1; key DPAD_CENTER 6; top dock-to-sports; shot 34d-dock-to-sports
key BACK 3; top dock-back-from-sports
crashes sidebar
# Remote-button link to a service page
adb shell am start -W -a android.intent.action.VIEW -d "fluxtv://service/disney"; sleep 8; top deeplink; texts deeplink 10; shot 29-deeplink-disney
fi
if want service; then
# Service page
adb shell am start -W -n $P/.ServiceActivity --es service netflix
sleep 12; nudge; texts service 30; shot 05-service
crashes service
fi
# Title page (core) -> Cast & Crew -> person -> filmography
adb shell am start -W -n $P/.DetailActivity --es type movie --es id tt0111161 --es name Test
waitfor Trailer 12; nudge; texts detail 30; shot 06-detail
if want credits; then
adb shell am start -W -n $P/.CreditsActivity --es id tt0111161 --es type movie --es name "The Shawshank Redemption"
sleep 8; nudge; texts credits 20; shot 07-credits
key DPAD_CENTER 8; top person; texts person 40; shot 08-person
nudge; key DPAD_CENTER 10; top person-click; texts person-click 10; shot 09-person-to-title
crashes credits
fi
if want search; then
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
fi
# Themes
adb shell am start -W -f 0x10008000 -n $P/.SettingsActivity --es category appearance; sleep 4; shot 38-themes
curl -s -m 20 -X POST --data-urlencode "theme=2" http://127.0.0.1:8090/appearance | grep -o "<p class='m'>[^<]*"
adb shell am start -W -n $P/.MainActivity; sleep 8; nudge; shot 39-home-ocean-theme
# (the setup page only answers reliably while an inner screen such as Settings is open)
adb shell am start -W -f 0x10008000 -n $P/.SettingsActivity; sleep 3
curl -s -m 20 -X POST --data-urlencode "theme=0" http://127.0.0.1:8090/appearance | grep -o "<p class='m'>[^<]*"
# Highlight colour: Mint (accent 3 → highlight 5), then back to white
curl -s -m 20 -X POST --data-urlencode "highlight=5" http://127.0.0.1:8090/appearance | grep -o "<p class='m'>[^<]*"
adb shell am force-stop $P
adb shell am start -W -f 0x10008000 -n $P/.SettingsActivity --es category appearance; sleep 4; key DPAD_RIGHT 1; focused highlight-settings; shot 39b-highlight-mint-settings
adb shell am start -W -n $P/.MainActivity; waitfor "Your apps" 15; nudge; key DPAD_DOWN 1; shot 39c-highlight-mint-home
adb shell am start -W -f 0x10008000 -n $P/.SettingsActivity; sleep 6
for i in 1 2 3; do R=$(curl -s -m 20 -X POST --data-urlencode "highlight=0" http://127.0.0.1:8090/appearance | grep -o "<p class='m'>[^<]*"); echo "== HIGHLIGHT RESET: $R"; [ -n "$R" ] && break; sleep 4; done
crashes themes
if want quick; then
# Quick Settings panel (also its own launcher entry)
adb shell am start -W -n $P/.QuickSettingsActivity
sleep 4; nudge; top quick; texts quick 30; shot 12-quick-settings
key DPAD_RIGHT 1; key DPAD_CENTER 5; top quick-open; key BACK 2
crashes quick
fi
if want cw; then
# Continue watching sources + Gemini request settings
adb shell am start -W -n $P/.HomeRowsActivity; sleep 3; texts cw-sources 20; shot 30-cw-sources
tap "✓ Flux"; sleep 2; texts cw-sources-after 12
curl -s http://127.0.0.1:8000/arr/_log | python3 -c "import json,sys; d=json.load(sys.stdin); [print('GEMINI', e['path'], e['body'][-200:]) for e in d['gemini']]"
crashes cw
fi
if want home-button; then
# Home button takeover via the accessibility service (like Projectivy)
adb shell settings put secure enabled_accessibility_services $P/$P.HomeButtonService
adb shell settings put secure accessibility_enabled 1
sleep 3
adb shell am start -W -f 0x10008000 -n $P/.SettingsActivity --es category home; sleep 4; texts settings-home 30; shot 35-settings-home
adb shell cmd package set-home-activity com.google.android.apps.nexuslauncher/.NexusLauncherActivity
adb logcat -c
adb shell am start -W -f 0x10008000 -n $P/.SettingsActivity; sleep 3
# A hardware Home press (through the emulator's virtual keyboard, like a real remote)
adb emu event send EV_KEY:KEY_HOMEPAGE:1 EV_SYN:0:0; sleep 0.1; adb emu event send EV_KEY:KEY_HOMEPAGE:0 EV_SYN:0:0
sleep 2; top after-hw-home-2s; adb logcat -d | grep FluxHome | tail -6
# Software Home (adb input) for comparison
adb shell am start -W -f 0x10008000 -n $P/.SettingsActivity; sleep 3
adb shell input keyevent HOME; sleep 7; top after-sw-home-7s; adb logcat -d | grep FluxHome | tail -6
# Hold Home
adb shell am start -W -f 0x10008000 -n $P/.SettingsActivity; sleep 3
adb emu event send EV_KEY:KEY_HOMEPAGE:1 EV_SYN:0:0; sleep 1.2; adb emu event send EV_KEY:KEY_HOMEPAGE:0 EV_SYN:0:0
sleep 3; top after-long-home; shot 37-long-home
shot 36-after-home-button
curl -s -m 20 http://127.0.0.1:8090/ > /dev/null
adb shell am start -W -f 0x10008000 -n $P/.SettingsActivity; sleep 3
curl -s -m 20 http://127.0.0.1:8090/ > out/settings-page-final.html; echo "== WEB SECTIONS: $(grep -o "<summary>[^<]*" out/settings-page-final.html | tr '\n' ' ')"
crashes home-button
fi
if want live; then
# ---------------- Live TV guide (EPG), TiviMate-style player, DVR to local storage and to an SMB share
(sudo apt-get install -y -q samba > /tmp/samba.log 2>&1 && sudo mkdir -p /srv/flux/Shows && sudo chmod -R 777 /srv/flux \
  && printf '[global]\n  map to guest = Bad User\n  server min protocol = SMB2\n  guest account = nobody\n[flux]\n  path = /srv/flux\n  read only = no\n  guest ok = yes\n  force user = nobody\n' | sudo tee /etc/samba/smb.conf > /dev/null \
  && (sudo systemctl restart smbd || sudo smbd -D) && echo "== SAMBA: up") || echo "== SAMBA: failed $(tail -3 /tmp/samba.log)"
adb shell am start -W -f 0x10008000 -n $P/.SettingsActivity --es category live; sleep 3
post /iptv-xtream --data-urlencode "url=http://10.0.2.2:8000" --data-urlencode "user=test" --data-urlencode "pass=test"
curl -s -m 60 -X POST http://127.0.0.1:8090/dvr-guide | grep -o "<p class='m'>[^<]*"
adb shell am start -W -n $P/.LiveActivity; sleep 8; shot 60-live-guide; texts live-guide 30
key DPAD_DOWN 1; key DPAD_RIGHT 1; key DPAD_RIGHT 2; shot 61-live-guide-later
key DPAD_CENTER 2; shot 62-live-record-dialog; texts record-dialog 12
key DPAD_CENTER 2; sleep 1; shot 63-live-scheduled
key DPAD_LEFT 1; key DPAD_LEFT 1; key DPAD_LEFT 1; key DPAD_LEFT 2; shot 64-live-groups; texts live-groups 20
key BACK 1
key DPAD_CENTER 8; top live-player; shot 65-live-player; texts live-player 20
key DPAD_DOWN 2; texts live-player-arrow 12; key CHANNEL_DOWN 6; shot 66-live-player-zap; texts live-player-zap 12
key DPAD_CENTER 3; shot 67-live-channel-list; key BACK 1
key MENU 2; shot 68-live-options; texts live-options 12
key DPAD_CENTER 3; echo "== recording locally for 20s"; sleep 20
adb shell am start -W -n $P/.RecordingsActivity; sleep 3; shot 69-recordings; texts recordings 20
adb shell "ls -la /sdcard/Android/data/$P/files/Movies/Recordings/ 2>&1 | tail -3"
# SMB share: sign in, browse, choose a folder, record there
adb shell am start -W -f 0x10008000 -n $P/.SettingsActivity --es category live; sleep 3
curl -s -m 60 -X POST --data-urlencode "host=10.0.2.2" http://127.0.0.1:8090/dvr-smb-login | grep -o "<p class='m'>[^<]*\|📁 [^<]*\|Couldn't open it[^<]*" | head -6
curl -s -m 60 -X POST --data-urlencode "path=flux" http://127.0.0.1:8090/dvr-smb-open | grep -o "📁 [^<]*\|Use this folder" | head -4
post /dvr-smb-use --data-urlencode "path=flux/Shows"
curl -s -m 30 http://127.0.0.1:8090/ > out/settings-page-dvr.html
adb shell am start -W -n $P/.SmbBrowseActivity; sleep 5; shot 70-smb-browser; texts smb-browser 20; key BACK 1
adb shell am start -W -n $P/.LiveActivity; sleep 5; key DPAD_CENTER 8; key MENU 2; key DPAD_CENTER 3
echo "== recording to SMB for 20s"; sleep 20; key MENU 2; key DPAD_CENTER 3; sleep 3
echo "== SMB FILES: $(ls -la /srv/flux/Shows 2>&1 | tail -4 | tr '\n' ' ')"
adb shell am start -W -n $P/.RecordingsActivity; sleep 4; shot 71-recordings-smb; texts recordings-smb 24
key DPAD_CENTER 8; top play-recording; shot 72-play-recording; key BACK 2
adb shell am start -W -a android.intent.action.VIEW -d "fluxtv://live/201"; sleep 8; top deeplink-live-201; texts deeplink-live 12; key BACK 2
adb shell am start -W -n $P/.MainActivity; sleep 8; nudge; key DPAD_DOWN 1; shot 73-home-row-1; key DPAD_DOWN 1; shot 74-home-row-2; key DPAD_DOWN 1; shot 75-home-row-3; texts home-live-row 40
crashes live
# ---------------- Which channels show: countries from group names ("US| News", "UK| Sports"), guide download indicator
adb shell am start -W -f 0x10008000 -n $P/.SettingsActivity --es category live; sleep 6
post /iptv-regions --data-urlencode "regions=US" --data-urlencode "unknown=show"
adb shell am start -W -n $P/.LiveActivity; sleep 6; texts live-us-only 40; shot 76-live-us-only
adb shell am start -W -f 0x10008000 -n $P/.SettingsActivity --es category live; sleep 6
(curl -s -m 60 -X POST http://127.0.0.1:8090/dvr-guide > /dev/null &) ; sleep 1.5; shot 77-settings-guide-downloading
sleep 8; for i in 1 2 3 4 5 6 7 8 9 10 11 12; do key DPAD_DOWN 0; done; sleep 2; texts settings-live-channels 50; shot 78-settings-live-channels
adb shell am start -W -n $P/.IptvFilterActivity; sleep 4; texts iptv-filter 30; shot 79-iptv-filter
key DPAD_CENTER 2; texts iptv-filter-hidden 10; key DPAD_CENTER 2; texts iptv-filter-shown-again 10
adb shell am start -W -f 0x10008000 -n $P/.SettingsActivity --es category live; sleep 6
post /iptv-show-all
crashes channel-filters
fi
if want theme-place; then
# ---------------- Settings keeps your place after choosing a theme
adb shell am start -W -f 0x10008000 -n $P/.SettingsActivity --es category appearance; sleep 3
key DPAD_CENTER 2; key DPAD_RIGHT 1; key DPAD_RIGHT 1; focused theme-before
key DPAD_CENTER 4; focused theme-after; shot 91-theme-after
curl -s -m 20 -X POST --data-urlencode "theme=0" http://127.0.0.1:8090/appearance > /dev/null
adb shell am start -W -n $P/.MainActivity; sleep 6; nudge; key DPAD_DOWN 1; shot 92-home-highlight
fi
if want home-instant; then
# ---------------- Home shows instantly from its saved copy
adb shell am force-stop $P; sleep 1
adb shell am start -n $P/.MainActivity; sleep 1.5; shot 90-home-1500ms; texts home-1500ms 25
sleep 8; texts home-later 25
fi
if want smarthome; then
# ---------------- Smart home panel (Home Assistant)
adb shell am start -W -f 0x10008000 -n $P/.SettingsActivity --es category smarthome; sleep 3
post /sh-connect --data-urlencode "url=http://10.0.2.2:8000/ha" --data-urlencode "token=wrong"
post /sh-connect --data-urlencode "url=http://10.0.2.2:8000/ha" --data-urlencode "token=hatoken"
curl -s -m 30 http://127.0.0.1:8090/ > out/settings-page-sh.html; echo "== WEB DEVICES: $(grep -o "name='d_[^']*'[^>]*>[^<]*" out/settings-page-sh.html | sed "s/name='d_//; s/' value='on'//" | tr '\n' '|')"
adb shell am start -W -f 0x10008000 -n $P/.SettingsActivity --es category smarthome; sleep 5; shot 80-settings-smarthome; texts settings-smarthome 40
adb shell settings put secure enabled_accessibility_services $P/$P.HomeButtonService; adb shell settings put secure accessibility_enabled 1; sleep 3
adb shell am start -W -a android.intent.action.VIEW -d "fluxtv://smarthome"
sleep 5; top smart-panel; shot 81-smart-panel   # (no uiautomator here: it pauses accessibility services, which closes the panel)
key DPAD_RIGHT 1; key DPAD_RIGHT 1; key DPAD_RIGHT 2; sleep 1; shot 82-smart-dimmed
key DPAD_DOWN 1; key DPAD_CENTER 2; key DPAD_DOWN 1; key DPAD_CENTER 2; sleep 2; shot 83-smart-toggled
echo "== HA LOG: $(curl -s http://127.0.0.1:8000/ha/_log | python3 -c "import json,sys; d=json.load(sys.stdin); print([ (e['service'], e['data']) for e in d['log']])")"
adb logcat -d | grep -A4 "FluxPanel" | head -12
key BACK 2; top after-panel-back; shot 83b-after-panel-back
curl -s -m 20 -X POST --data-urlencode "name=Living room lights" --data-urlencode "kind=dimmable" http://127.0.0.1:8090/gc-add | grep -o "<p class='m'>[^<]*"
curl -s -m 20 -X POST --data-urlencode "cmd=turn off living room lights" http://127.0.0.1:8090/gc-test | grep -o "<p class='m'>[^<]*"; sleep 3; top after-gc-test; key BACK 2
# Over a playing video (overlay through the accessibility service)
adb shell am start -W -n $P/.LiveActivity; sleep 5; key DPAD_CENTER 6
adb shell am start -W -a android.intent.action.VIEW -d "fluxtv://smarthome"; sleep 4; top panel-over-video
key DPAD_DOWN 1; key DPAD_DOWN 1; key DPAD_RIGHT 2; shot 84-panel-over-video
echo "== HA LOG 2: $(curl -s http://127.0.0.1:8000/ha/_log | python3 -c "import json,sys; d=json.load(sys.stdin); print([ (e['service'], e['data']) for e in d['log']])")"; key BACK 2; top after-overlay-back; shot 85-after-overlay-back
crashes smarthome
fi
if want apps-sync; then
# ---------------- icons, Apps grid, Settings focus, age limits, TV-to-TV sync
adb shell am start -W -f 0x10008000 -n $P/.SettingsActivity --es category home; sleep 3
sleep 2; focused settings-start; key DPAD_RIGHT 2; focused settings-right
for i in 1 2 3 4 5 6 7 8 9 10; do key DPAD_DOWN 1; F=$(focused settings-down-$i); echo "$F"; case "$F" in *"): Round"*|*"): Square"*) break;; esac; done
key DPAD_CENTER 3; focused settings-after-click; top settings-after-click; shot 50-settings-after-click
texts settings-after-click 40
adb shell am start -W -n $P/.AppsActivity; sleep 4; shot 51-apps-grid
adb shell am start -W -n $P/.MainActivity; sleep 8; nudge
for i in 1 2 3 4 5 6; do key DPAD_DOWN 1; done; shot 52-home-app-tiles
adb forward tcp:8091 tcp:8091
echo "== HELLO: $(curl -s -m 10 http://127.0.0.1:8091/sync/hello)"
adb shell am start -W -f 0x10008000 -n $P/.SettingsActivity --es category profiles; sleep 3
CODE=$(curl -s -m 20 -X POST http://127.0.0.1:8090/sync-code | grep -o "settings page): [0-9]*" | grep -o "[0-9]*$"); echo "== CODE: $CODE"
PAIR=$(curl -s -m 20 -X POST -H 'Content-Type: application/json' -d "{\"code\":\"$CODE\",\"id\":\"faketv\",\"name\":\"Bedroom TV\",\"key\":\"\"}" http://127.0.0.1:8091/sync/pair); echo "== PAIR: $PAIR"
KEY=$(echo "$PAIR" | python3 -c "import json,sys; print(json.load(sys.stdin).get('key',''))")
echo "== STATE BEFORE: $(curl -s -m 20 -H "X-Flux-Key: $KEY" http://127.0.0.1:8091/sync/state | python3 -c "import json,sys; d=json.load(sys.stdin); print(d['profiles'], {k: list(v['library'].keys()) for k,v in d['data'].items()})")"
echo "== BAD KEY: $(curl -s -m 10 -H "X-Flux-Key: nope" http://127.0.0.1:8091/sync/state)"
NOW=$(($(date +%s) * 1000))
python3 - "$NOW" > /tmp/state.json <<'PY'
import json, sys
now = int(sys.argv[1])
fav = {"id": "tt0111161", "type": "movie", "name": "The Shawshank Redemption", "poster": "", "t": now}
print(json.dumps({"id": "faketv", "name": "Bedroom TV", "profiles": ["Default", "Kids", "Grandma"], "deleted": {},
  "data": {"Grandma": {"library": {"tt0111161": fav}, "library_removed": {}, "progress": {}, "settings_t": str(now)},
           "Kids": {"library": {"tt0111161": fav, "tt0133093": dict(fav, id="tt0133093", name="The Matrix")}, "library_removed": {},
                    "progress": {}, "max_rating": "PG", "pin": "", "settings_t": str(now + 5000)}}}))
PY
echo "== PUSH: $(curl -s -m 20 -X POST -H "X-Flux-Key: $KEY" -H 'Content-Type: application/json' --data-binary @/tmp/state.json http://127.0.0.1:8091/sync/state)"
echo "== STATE AFTER: $(curl -s -m 20 -H "X-Flux-Key: $KEY" http://127.0.0.1:8091/sync/state | python3 -c "import json,sys; d=json.load(sys.stdin); print(d['profiles'], {k: (list(v['library'].keys()), v.get('max_rating')) for k,v in d['data'].items()})")"
echo "== SETTINGS COPY: $(curl -s -m 20 -H "X-Flux-Key: $KEY" http://127.0.0.1:8091/sync/settings | python3 -c "import json,sys; d=json.load(sys.stdin); print(len(d), 'keys; rd' , 'rd_token' in d or any('rd' in k for k in d), sorted(d)[:40])")"
adb shell am start -W -n $P/.ProfilesActivity; sleep 4; texts profiles-synced 40; shot 53-profiles-synced
curl -s -m 20 http://127.0.0.1:8090/ > out/settings-page-sync.html; echo "== WEB SYNC: $(grep -o "Sync between TVs[^<]*\|Linked with[^<]*\|Bedroom TV" out/settings-page-sync.html | sort | uniq -c | tr '\n' ' ')"
curl -s -m 20 -X POST --data-urlencode "name=Kids" http://127.0.0.1:8090/profile-use | grep -o "<p class='m'>[^<]*"
adb shell am start -W -n $P/.MainActivity; sleep 10; nudge; texts kids-home 50; shot 54-kids-home
key DPAD_LEFT 1; key DPAD_LEFT 1; shot 55-kids-sidebar; texts kids-sidebar 30; key BACK 1
adb shell am start -W -n $P/.MainActivity --es section favorites; sleep 5; texts kids-favorites 30; shot 56-kids-favorites
adb shell am start -W -f 0x10008000 -n $P/.SettingsActivity --es category profiles; sleep 3
curl -s -m 20 -X POST --data-urlencode "name=Default" http://127.0.0.1:8090/profile-use | grep -o "<p class='m'>[^<]*"
crashes sync
fi
# ---------------- v26: info screen (no phantom stop, Trailer), skip intro / credits / next episode, extra guide sources
curl -s -m 30 -X POST --data-urlencode "tmdb=http://10.0.2.2:8000/3" --data-urlencode "gemini=http://10.0.2.2:8000/gemini/" --data-urlencode "trakt=http://10.0.2.2:8000/trakt" --data-urlencode "introdb=http://10.0.2.2:8000/introdb" http://127.0.0.1:8090/test-config | grep -o "<p class='m'>[^<]*"
adb shell am start -W -n $P/.DetailActivity --es type movie --es id tt0111161 --es name Test
waitfor Trailer 12; focused info-open; texts info-buttons 20; shot 90-info-open
key DPAD_DOWN 1; focused info-one-down; shot 91-info-one-down
key DPAD_UP 1; focused info-back-up
if want skip-next; then
adb shell am start -W -n $P/.PlayerActivity --es url "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8" --es pi_type series --es pi_meta tt0903747 --es pi_video tt0903747:1:1 --es pi_name "Breaking Bad" --es pi_label "S1 E1"
sleep 14; shot 92-skip-intro; texts skip-intro 10
key DPAD_CENTER 3; shot 93-after-skip-intro; texts after-skip 10
sleep 26; shot 94-next-episode; texts next-episode 10
sleep 4; shot 95-next-countdown
sleep 9; top after-countdown; shot 96-next-started
key BACK 2; key BACK 2
crashes skip-next
fi
if want epg; then
post /epg-extra --data-urlencode "urls=http://10.0.2.2:8000/extra-epg.xml" --data-urlencode "refresh=1"
sleep 15
curl -s -m 60 -X POST http://127.0.0.1:8090/dvr-guide | grep -o "<p class='m'>[^<]*"
curl -s -m 20 http://127.0.0.1:8090/ > out/settings-page-epg.html; echo "== WEB EPG: $(grep -o 'Last download: [^<]*\|extra-epg.xml: [^<]*' out/settings-page-epg.html | tr '\n' ' ')"
adb shell am start -W -n $P/.LiveActivity; sleep 8; shot 97-guide-extra; texts guide-extra 40
adb shell am start -W -f 0x10008000 -n $P/.SettingsActivity --es category live; sleep 4; key DPAD_RIGHT 1; key DPAD_DOWN 1; key DPAD_DOWN 1; key DPAD_DOWN 1; key DPAD_DOWN 1; key DPAD_DOWN 1; shot 98-settings-epg; texts settings-epg 40
crashes epg
fi
if want portal; then
# ---------------- v28: MAC portal, catch-up, multiview, sports
XT=ca0e9097; PORTAL=9b2ad21
post /iptv-stalker --data-urlencode "url=http://10.0.2.2:8000/stalker/c/" --data-urlencode "mac=00-1a-79-00-00-01"
sleep 6
adb shell am start -W -n $P/.LivePlayerActivity --es group all --es source $PORTAL --es channel 501; sleep 12; top portal-play; shot 100-portal-playing; texts portal-play 12
key BACK 2
echo "== PORTAL REQS: $(curl -s http://127.0.0.1:8000/_reqs | python3 -c "import json,sys; r=json.load(sys.stdin); print([x[:90] for x in r if 'stalker' in x][-8:])")"
adb shell am start -W -n $P/.LiveActivity; sleep 8; texts live-with-portal 30; shot 101-guide-with-portal
key DPAD_LEFT 2; shot 102-guide-past; texts guide-past 12
key DPAD_CENTER 10; top catchup; shot 103-catchup-playing; texts catchup 12
key BACK 2; key BACK 2
adb shell am start -W -n $P/.LiveActivity --es group all; sleep 6   # Live TV otherwise reopens on the last group
# move down to the Xtream channel that keeps replays ("Flux News", tv_archive 3 days)
for i in 1 2 3 4 5 6 7 8; do T=$(texts guide-find 4); echo "$T"; case "$T" in *"Flux News"*) break;; esac; key DPAD_UP 1; done
key DPAD_LEFT 2; texts guide-past-xtream 12; shot 103b-guide-past-xtream
key DPAD_CENTER 10; top catchup-xtream; shot 103c-catchup-xtream
echo "== CATCHUP REQS: $(curl -s http://127.0.0.1:8000/_reqs | python3 -c "import json,sys; r=json.load(sys.stdin); print([x for x in r if 'timeshift' in x or 'archive' in x][-4:])")"
key BACK 2; key BACK 2
adb shell am start -W -n $P/.MultiviewActivity --es group all --es source $XT --es channel 1001; sleep 15; shot 104-multiview-2; top multiview
key DPAD_RIGHT 2; shot 105-multiview-right
key MENU 2; texts multiview-menu 10; tap "Add a view"; sleep 10; shot 106-multiview-3
key BACK 2
# Live TV buttons on the Xtream channel with replay: OK shows them; Start over / rewind use the provider's replay.
# Each step reopens the player so it starts clean, then drives the buttons with the remote (focus starts on Pause).
liveopen() { adb shell am start -W -n $P/.LivePlayerActivity --es group all --es source $XT --es channel 1001 >/dev/null; sleep 8; key DPAD_UP 1; key DPAD_CENTER 1; }
liveopen; top live-buttons-player; texts live-buttons 40; shot 111-live-buttons
adb logcat -c; liveopen; key DPAD_LEFT 0; key DPAD_LEFT 0; key DPAD_CENTER 8; key DPAD_UP 1; texts live-start-over 30; shot 112-live-start-over
echo "== LIVE LOG: $(adb logcat -d -s FluxLive:V | tail -5 | tr '\n' ' ')"
echo "== START-OVER REQS: $(curl -s http://127.0.0.1:8000/_reqs | python3 -c "import json,sys; r=json.load(sys.stdin); print([x for x in r if 'timeshift' in x][-2:])")"
liveopen; for i in 1 2 3 4; do key DPAD_RIGHT 0; done; key DPAD_CENTER 4; texts live-categories 30; shot 113-live-categories
liveopen; for i in 1 2 3 4 5; do key DPAD_RIGHT 0; done; key DPAD_CENTER 3; texts live-catchup-list 30; shot 114-live-catchup-list
liveopen; for i in 1 2 3; do key DPAD_RIGHT 0; done; key DPAD_CENTER 3; shot 115-live-channels; key BACK 1
# Guide times: ⋮ → "Guide times…" (9th item) moves this channel's guide; +15 min is focused first
# (the item is below the dialog's fold, so arrows: ↑ first leaves touch mode, then ↓×8 reaches it; +15 min is focused)
liveopen; key BACK 1; key MENU 2; key DPAD_UP 1; for i in 1 2 3 4 5 6 7 8; do key DPAD_DOWN 0; done; sleep 1; key DPAD_CENTER 2
texts guide-shift-dialog 20; shot 117-guide-shift; key DPAD_CENTER 2; texts guide-shift-after 20; key BACK 1; key BACK 1
# Lip sync: the flash-and-beep screen; → twice then OK saves +20 ms for this sound output
adb shell am start -W -n $P/.LipSyncActivity; sleep 4; shot 118-lipsync; key DPAD_RIGHT 1; key DPAD_RIGHT 1; texts lipsync 12; key DPAD_CENTER 2
crashes guide-shift-lipsync
# Back on Home after watching: the Live TV row shows the channel just watched first (no full Home rebuild)
adb shell am start -W -n $P/.LivePlayerActivity --es group all --es source $XT --es channel 1002 >/dev/null; sleep 6
adb shell am start -W -n $P/.MainActivity; sleep 5; texts home-live-row-after 60; shot 116-home-live-row-after
key DPAD_UP 2; top live-arrow-no-zap; key CHANNEL_UP 4; texts live-ch-up 12
key BACK 2
crashes portal-catchup-multiview
adb shell am start -W -f 0x10008000 -n $P/.SettingsActivity; sleep 4
curl -s -m 30 -X POST --data-urlencode "tmdb=http://10.0.2.2:8000/3" --data-urlencode "gemini=http://10.0.2.2:8000/gemini/" --data-urlencode "trakt=http://10.0.2.2:8000/trakt" --data-urlencode "sports=http://10.0.2.2:8000/espn" http://127.0.0.1:8090/test-config | grep -o "<p class='m'>[^<]*"
adb shell am start -W -n $P/.SportsActivity; waitfor Table 15; texts sports 40; shot 107-sports
tap "Kansas City Chiefs"; sleep 10; top sports-watch; shot 108-sports-watch; key BACK 2
sleep 2; longtap "Kansas City Chiefs"; sleep 2; texts sports-watch-on 12; shot 109-sports-watch-on
tap "Table"; sleep 5; texts sports-table 30; shot 110-sports-table; key BACK 1
crashes sports
fi
if want gemini; then
# ---------------- v29: simpler player bar (Ask Gemini + ⋮), Ask Gemini about the screen
adb shell am start -W -n $P/.PlayerActivity --es url "http://10.0.2.2:8000/timeshift/test/test/60/x/1001.ts" --es pi_type movie --es pi_meta tt0133093 --es pi_video tt0133093 --es pi_name "The\ Matrix" --es gemini_test_question "Who\ is\ on\ screen\ right\ now"
sleep 10; key DPAD_UP 1; shot 120-player-bar
key DPAD_RIGHT 0; key DPAD_RIGHT 0; key DPAD_RIGHT 0; key DPAD_RIGHT 1; key DPAD_CENTER 2; texts more-menu 20; shot 121-more-menu
key DPAD_CENTER 2; texts more-audio 12; shot 122-more-audio
key BACK 2; texts more-back 12; key BACK 2
# Ask Gemini opens straight into listening (the test build "hears" a fixed question), then shows the answer
key DPAD_UP 1; for i in 1 2 3 4 5 6; do key DPAD_LEFT 0; done; shot 122b-gemini-button
key DPAD_CENTER 0; sleep 0.4; shot 123-gemini-listening; sleep 0.8; shot 123b-gemini-heard
sleep 6; texts gemini-answer 20; shot 124-gemini-answer
key DPAD_CENTER 1; shot 125-gemini-listening-again; sleep 7; texts gemini-followup 20; shot 126-gemini-followup
echo "== SCREEN ASKS: $(curl -s http://127.0.0.1:8000/_screen)"
key BACK 1; key BACK 2; texts gemini-closed 8; top gemini-closed; key BACK 2
crashes gemini
fi
adb logcat -d > out/logcat.txt
kill $FAKE_PID 2>/dev/null
exit 0
