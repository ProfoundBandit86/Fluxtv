# Flux TV — handoff

Last updated: 2026-11-17 · latest release **v30** · repo `ProfoundBandit86/Fluxtv` (old name `fluxtv` redirects)

This file is for whoever picks the project up next (a person or a new AI session). It says what Flux is,
how it is built, tested and released, what was done recently, and the rules and gotchas learned the hard way.

---

## 1. What Flux is

An Android TV launcher + media app (package `com.fluxtv.app`) for an **onn 4K Pro (Google TV 14)**, used by
the owner and family. It combines:

- Stremio add-ons (Cinemeta, Torrentio…) + **Real-Debrid**, Plex / Jellyfin / Emby, Trakt, TMDB
- Live TV / IPTV: Xtream Codes, M3U, **MAC (Stalker) portals**, TV guide (EPG), DVR to local/USB/SMB,
  catch-up, Multiview, Sports hub
- Home screen takeover (Home button opens Flux), app rows, Quick Settings, profiles with age limits,
  TV-to-TV sync, a phone/web setup page, Gemini search + "Ask Gemini" in the player, smart-home panel
  (Home Assistant + Google Assistant commands), Radarr/Sonarr/SAB

## 2. Build, test, release

| What | How |
|---|---|
| Language | Java only (framework Views, no Compose, no Kotlin). minSdk 24, target/compile 35 |
| Libraries | Media3 1.5.0 (+ HLS, UI, datasource), `org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1`, `eu.agno3.jcifs:jcifs-ng:2.1.10` |
| Build | GitHub Actions only (Gradle 8.14.3, AGP 8.7.3). No local Android SDK in the AI workspace |
| Release | **Every push to `main`** builds a signed APK and publishes GitHub release `v<run number>`. TVs update themselves (Updater.java) when the home screen reloads |
| Signing | `signing/flux-release.jks`, passwords `fluxtv-release`, alias `flux` (owner agreed to a public keystore). SHA-1 `24:A0:36:38:69:B5:09:3A:8F:BA:F0:63:C7:CE:6E:64:20:5A:ED:C0` |
| Shrinking | R8 on (`minifyEnabled true`, `shrinkResources true`, **`-dontobfuscate`** so on-screen crash traces stay readable). Rules in `app/proguard-rules.pro` |
| Emulator tests | Push to branch **`device-test`** → `.github/workflows/device-test.yml` runs `.github/device-test.sh` on an Android TV emulator (≈50 min, timeout 70). Screenshots + `logcat.txt` are pushed to branch **`device-test-output`** |
| Fake servers for tests | `.github/fake_xtream.py` (port 8000): Xtream + XMLTV + timeshift, fake TMDB, Gemini (incl. "Ask Gemini" with `/_screen` log), Trakt, Radarr/Sonarr, Home Assistant (`/ha`, token `hatoken`), subtitles add-on, IntroDB (`/introdb`), Stalker portal (`/stalker/portal.php`, MAC `00:1A:79:00:00:01`), ESPN (`/espn`), request log `/_reqs`. Test-only URLs are set through the web app's `/test-config` (TEST_BUILD only) |
| Test helpers in device-test.sh | `key`, `shot`, `texts`, `focused`, `top`, `crashes`, `tap "<text prefix>"`, `longtap` |
| Local compile check | `javac -sourcepath /home/claude/stubs:app/src/main/java …` against hand-made Android stubs. The stubs have many gaps (Canvas, ProgressBar, R ids…), so filter out "cannot find symbol"; **CI is the real compiler** |

Usual loop: edit → push `device-test` → read `== …` lines in the run log + screenshots → push `device-test:main` to release.

Gotchas for tests:
- `uiautomator dump` (used by `texts`/`focused`) takes seconds and pauses accessibility services; the player's
  controls auto-hide after 6 s, so don't put dumps between key presses that navigate the player bar.
- adb splits `--es` values on spaces: write `"The\ Matrix"`.
- The web/config server (port 8090) answers reliably once an inner screen (e.g. Settings) has been opened.
- Dialogs left open by one test pollute the next test's `texts`.
- The runner needs `ffmpeg` (installed by the script) to make `/tmp/flux-test.ts` for portal/catch-up playback.

## 3. Code map (app/src/main/java/com/fluxtv/app)

- **Core**: `App` (thread pools IO/MAIN, lifecycle → edge-to-edge `App.fullScreen`, HTTP response cache, IPTV prefetch hourly),
  `Store` (prefs, profiles, progress, library, settings export/import), `Http` (pooled connections — **don't add `disconnect()` back**),
  `ImageLoader` (LIFO pool, in-flight de-dupe, disk cache 150 MB), `Ui`, `Theme`, `FocusScroll` (rows never focusable themselves)
- **Home/browse**: `MainActivity`, `Blend` (home rows, saved copy per profile), `FluxActivity` (sidebar), `DetailActivity`
  (info page: Play / Sources / Trailer / Save / Cast / service / Arr), `StreamsActivity` (autoplay "starting" screen), `SearchActivity`, `Gemini`
- **Player**: `PlayerActivity` (Flux's own controls: ⏯ −10 +30, **Ask Gemini**, **⋮** menu; skip intro/credits via `Segments`
  (IntroDB / TheIntroDB); next-episode countdown; network-drop auto-reconnect ×5; codec fallback), `Players.create()` (renderers with
  FFmpeg audio, `ResolvingDataSource` for `fluxiptv://`, SMB data source, LoadControl 1.5 s start)
- **IPTV**: `Iptv` (sources, channels, disk caches in `files/iptv/`, guide download + extra XMLTV sources + name matching, catch-up URLs,
  `resolve()`), `Stalker` (MAC portal client), `LiveActivity` + `GuideView` (TiviMate-style guide, past programmes ↺ on catch-up channels),
  `LivePlayerActivity`, `MultiviewActivity` (2–4 players, sound follows highlight, 720p cap), `Sports` + `SportsActivity`
  (ESPN public scoreboard/standings, matched to channels by guide titles + network names), `Dvr`/`DvrService`/`DvrAlarm`, `Smb*`, `RecordingsActivity`
- **System**: `HomeButtonService` (accessibility service: Home key + launcher-window detection, overlay "blip" + PendingIntent so
  background launches are allowed; also hosts the smart panel overlay), `Updater` (session installs with **`PACKAGE_SOURCE_STORE`**,
  `reinstall()`), `ProfileSync` (TV-to-TV, port 8091, NSD `_fluxtv._tcp`, 6-digit pairing), `Ratings`, `ConfigServer` (web app, port 8090)
- **Smart home**: `SmartHome`, `HomeAssistant`, `GoogleCommands` (Assistant intents), `SmartPanel`, `SmartPanelActivity`

## 4. Recent work (this round, v18 → v30)

- v18–v25: faster Play, profile picker in sidebar, codec fallback, square app icons, settings keep position, age-limited profiles + PIN,
  TV-to-TV sync + copy settings, EPG/DVR/SMB, edge-to-edge, smart-home panel, home pre-cache, highlight clipping fix (clipToPadding)
- v26: skip intro/credits + play next; extra XMLTV guide sources (EPG downloader); Trailer button on every title; phantom focus stop removed
- v27: **Home button on onn 4K Pro** — Android 13+ "restricted settings" blocked the accessibility switch for sideloaded installs.
  Fix: updates install as store-source; Settings → Home screen → "Unlock the Home button (reinstall Flux)". Owner confirmed it works.
- v28: MAC portals, catch-up/replays, Multiview, Sports hub; speed work (R8, pooled HTTP, response cache, image loader, quicker start)
- v29: player bar simplified to Ask Gemini + ⋮; **Ask Gemini** panel (pauses, sends a still of the frame + title/time context,
  Google Search grounding, voice or typed follow-ups, spoiler-safe)
- v30: player reconnects on dropped connections (ERROR_CODE_IO_*) instead of the misleading "format not supported" dialog

## 5. Rules and decisions to keep

- Owner is non-technical; explain in plain words. Releases go straight to his TV, so test on the emulator first.
- **Don't help bypass Real-Debrid "infringing_file" blocks.**
- The IPTV app at github.com/akus0/iptv-app-public (VesperTV fork) is **GPL-3.0**; owner chose to **rebuild features, not copy code**. Don't port its code.
- Google Home SDK must never be committed (license); it was tried (v22) and removed. The `HOME_SDK_KEY` repo secret is unused.
- The AI session can't set GitHub Actions secrets.
- Commits end with the Claude attribution lines the session provides.
- Gemini uses the owner's own key (Settings → Search). Sports scores use ESPN's unofficial public JSON — may change without notice.

## 6. Known gaps / ideas

- Xtream catch-up URL format is standard but wasn't exercised end-to-end on the emulator (portal catch-up was).
- MAC portals that hand out HLS (`.m3u8`) links won't play through `fluxiptv://` (media type is guessed before resolving); most give TS.
- "Ask Gemini" has no frame for DRM video and isn't in the Live TV player yet.
- Not yet tested on the real TV: Multiview, Sports layout, Ask Gemini, auto-reconnect.
