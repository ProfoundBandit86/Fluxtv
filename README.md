# Flux TV

An Android TV home-screen launcher and media browser in the style of Stremio/WuPlay, with
built-in **Real-Debrid** support.

## Features

- **Launcher**: can replace the TV's home screen. Has a clock, an *Apps* screen with every
  installed app, and a shortcut to TV settings.
- **Stremio add-on protocol**: install any add-on by its `manifest.json` URL. Catalogs, search,
  details and streams all come from your add-ons. Cinemeta (movie/series info) is pre-installed.
- **Plex, Jellyfin and Emby**: add your own media servers under *Media servers*.
  - Plex: sign in with a code at plex.tv/link. All servers on your account are found automatically,
    trying local addresses first, then remote, then Plex Relay.
  - Jellyfin / Emby: server address + username/password (easiest to type from the phone setup page).
  - Each library's newest titles plus *On deck* / *Continue watching* appear as home rows. Server
    titles show up in search and open on the same details page with seasons, episodes and resume points.
  - Every server title offers **Direct play** (original file) and **Transcoded** (the server converts
    it to H.264/AAC), for formats the TV can't play.
  - **"Also on your server"**: when you open a movie or episode from an add-on, any matching copy in
    your Plex/Jellyfin/Emby library is listed at the top of the streams, matched by IMDb ID.
  - Watch progress syncs both ways: resume points and watched marks come from the server, and the
    position you stop at is sent back to it.
- **Real-Debrid**: sign in with a code (real-debrid.com/device) or an API token. Torrent results
  (`infoHash`) from stream add-ons are sent to Real-Debrid and played as direct HTTPS streams.
  Results that aren't already cached on Real-Debrid are cleaned up automatically, and you're told
  to pick another stream.
- **Home rows**: Continue watching, My watchlist, then every add-on catalog.
- **Details page**: backdrop, rating, genres, cast and director, trailer, watchlist, seasons and
  episodes with watched and percent markers, and a "next episode" button.
- **Profiles**: each profile has its own watchlist and watch progress.
- **Built-in player** (Media3/ExoPlayer): resumes where you stopped and saves progress every 10 s.
  Long-press any stream, or use the button on a playback error, to open it in VLC, Just Player
  or any other installed player.
- **Phone setup page**: while the *Add-ons* or *Media servers* screen is open, visit
  `http://<tv-ip>:8090` from a phone on the same Wi-Fi to paste add-on links, your Real-Debrid
  token, Jellyfin/Emby logins or a Plex address + token, with no remote typing.

## Get the APK

Every push to `main` builds the app on GitHub Actions and publishes it as a release.
Download **flux-tv.apk** from the newest release on the repo's Releases page:

`https://github.com/ProfoundBandit86/fluxtv/releases/latest/download/flux-tv.apk`

(That link always points at the newest version, so it's handy for the Downloader app.)

## Automatic updates

- Whenever the Flux home screen reloads (boot, pressing Home, coming back from another screen),
  Flux checks the latest GitHub release, at most once every 15 minutes.
- If the release is newer, Flux downloads it and starts the install right away. On the first update
  Android asks you to let Flux "install unknown apps"; turn that on once. On Android 12+ later updates
  can install silently; older versions show a quick Install confirmation.
- *Updates · v1.0.N* in the side menu shows the installed version and checks immediately.
- To ship an update, push to `main`. The release is tagged `v<run number>`, and that number is the app's
  versionCode, so every build is newer than the last.

### Signing key (important)

Android only accepts an update signed with the same key as the installed app. Because the session that
set this up couldn't create GitHub secrets, the repo includes `signing/flux-release.jks`. This repo is
public, so anyone can see that key. For a personal app that's a small risk, because updates only come from
your own Releases page. To use a private key instead, add these repo secrets (Settings → Secrets and
variables → Actions) and the workflow switches to it automatically:
`FLUX_KEYSTORE_BASE64` (output of `base64 -w0 your.jks`), `FLUX_KEYSTORE_PASSWORD`, `FLUX_KEY_ALIAS`,
`FLUX_KEY_PASSWORD`. Then delete `signing/flux-release.jks`. Changing the key means uninstalling and
reinstalling Flux once.

## Install and set up

1. Sideload `app-release.apk`, for example with the Downloader app, a USB stick, or `adb install`.
2. Open **Flux TV → Real-Debrid → Sign in with a code** and approve the code on real-debrid.com/device.
3. Open **Add-ons** and install a stream add-on, either by typing its URL or from your phone using
   the address shown on screen. Any Stremio-compatible stream add-on works. Add-ons that return
   torrent hashes are resolved through your Real-Debrid account. Add-ons you've already set up with
   your own Real-Debrid key return direct links, which also work.

## Make it the home screen

- **Most Android TV boxes**: press Home and pick *Flux TV → Always*, or go to Settings → Apps →
  Default apps → Home app.
- **Google TV / Android TV 12+**: there is no picker, so you have to disable the stock launcher over ADB:
  ```sh
  adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
  adb shell pm disable-user --user 0 com.google.android.tungsten.setupwraith
  ```
  To undo: `adb shell pm enable com.google.android.apps.tv.launcherx` (and the same for `setupwraith`).
  Disabling the stock launcher can break voice search and some system shortcuts.
- **Fire TV**: Fire OS doesn't allow replacing its home screen. Open Flux from the app list instead.

## Notes

- Data is stored only on the TV. There's no Flux account and no cloud sync between devices. Progress
  for titles played from Plex/Jellyfin/Emby is synced through your server.
- Jellyfin: uses the current `Authorization: MediaBrowser` sign-in, so it works with Jellyfin 12, where
  the old sign-in methods are switched off. Stream links use the `ApiKey` parameter.
- The first time you open an add-on title, Flux reads your server's whole library index (IMDb IDs) to
  find matches. With a very large library this can take a few seconds. The index is cached for 15 minutes.
- The phone setup page only runs while the Add-ons screen is open, and anyone on your local
  network can reach it during that time.
- Some audio formats (e.g. DTS, TrueHD) may not play in the built-in player on every TV. Use
  long-press → external player for those.
- Only use add-ons and content you have the right to access.

## Code map

| File | What it does |
|---|---|
| `Addon.java`, `Addons.java` | Stremio add-on protocol: manifests, catalogs, search, meta, streams |
| `RealDebrid.java` | Device sign-in, token refresh, magnet → selected file → unrestricted link |
| `Sources.java`, `Plex.java`, `Jellyfin.java`, `MediaServer.java` | Plex / Jellyfin / Emby: sign-in, browsing, streams, IMDb matching, progress sync |
| `Updater.java`, `UpdateReceiver.java` | GitHub Releases update check, download and install |
| `ServersActivity.java` | Add/remove media servers |
| `Store.java` | Add-ons, profiles, watchlist, progress, credentials |
| `MainActivity.java` | Home and launcher screen |
| `DetailActivity.java`, `StreamsActivity.java`, `PlayerActivity.java` | Title page → stream picker → player |
| `AddonsActivity.java`, `ConfigServer.java` | Add-on manager and phone setup page |
| `AccountActivity.java`, `AppsActivity.java`, `SearchActivity.java` | Real-Debrid account, all apps, search |
