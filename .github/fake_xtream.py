#!/usr/bin/env python3
"""Tiny fake Xtream Codes / M3U / XMLTV server for the emulator test (reached at 10.0.2.2:8000)."""
import base64, json, time
from http.server import BaseHTTPRequestHandler, HTTPServer, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

HLS = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
NOW = int(time.time())


def b64(s):
    return base64.b64encode(s.encode()).decode()


CATS = [{"category_id": "1", "category_name": "US| News"}, {"category_id": "2", "category_name": "UK| Sports"}]
LIVE = [
    {"num": 101, "name": "Flux News", "stream_id": 1001, "stream_icon": "", "epg_channel_id": "news.flux", "category_id": "1", "tv_archive": 1, "tv_archive_duration": 3},
    {"num": 102, "name": "World Report", "stream_id": 1002, "stream_icon": "", "epg_channel_id": "world.flux", "category_id": "1"},
    {"num": 201, "name": "Sports One", "stream_id": 2001, "stream_icon": "", "epg_channel_id": "sports.flux", "category_id": "2"},
    {"num": 301, "name": "Cooking HD", "stream_id": 3001, "stream_icon": "", "epg_channel_id": "", "category_id": "2"},
]
VOD_CATS = [{"category_id": "10", "category_name": "New Movies"}]
VOD = [
    {"name": "Test Movie One", "stream_id": 501, "stream_icon": "", "rating": "7.1", "category_id": "10", "container_extension": "mp4"},
    {"name": "Test Movie Two", "stream_id": 502, "stream_icon": "", "rating": "6.4", "category_id": "10", "container_extension": "mkv"},
]
SERIES_CATS = [{"category_id": "20", "category_name": "New Series"}]
SERIES = [{"series_id": 900, "name": "Test Series", "cover": "", "plot": "A test show.", "category_id": "20", "releaseDate": "2024-01-01", "rating": "8"}]


TMDB = {
    "/3/watch/providers/movie": {"results": [{"provider_id": 8, "provider_name": "Netflix", "display_priority": 1, "logo_path": "/x.png"},
                                             {"provider_id": 337, "provider_name": "Disney Plus", "display_priority": 2, "logo_path": "/y.png"},
                                             {"provider_id": 99, "provider_name": "Shudder", "display_priority": 30, "logo_path": "/z.png"}]},
    "/3/watch/providers/tv": {"results": []},
    "/3/network/213": {"name": "Netflix", "logo_path": "/wordmark.png"},
    "/3/movie/603/external_ids": {"imdb_id": "tt0133093"},
    "/3/tv/1396/external_ids": {"imdb_id": "tt0903747"},
    "/3/find/tt0111161": {"movie_results": [{"id": 278}], "tv_results": []},
    "/3/find/tt0133093": {"movie_results": [{"id": 603}], "tv_results": []},
    "/3/find/tt0903747": {"movie_results": [], "tv_results": [{"id": 1396}]},
    "/3/movie/603": {"id": 603, "title": "The Matrix", "adult": False, "release_dates": {"results": [{"iso_3166_1": "US", "release_dates": [{"certification": "R"}]}]}},
    "/3/movie/278": {"id": 278, "title": "The Shawshank Redemption", "adult": False, "release_dates": {"results": [{"iso_3166_1": "US", "release_dates": [{"certification": "PG"}]}]}},
    "/3/tv/1396": {"id": 1396, "name": "Breaking Bad", "adult": False, "content_ratings": {"results": [{"iso_3166_1": "US", "rating": "TV-MA"}]}},
    "/3/movie/278/credits": {"cast": [{"id": 504, "name": "Tim Robbins", "character": "Andy Dufresne", "profile_path": None},
                                      {"id": 192, "name": "Morgan Freeman", "character": "Ellis Boyd 'Red' Redding", "profile_path": None}],
                             "crew": [{"id": 4027, "name": "Frank Darabont", "job": "Director", "profile_path": None},
                                      {"id": 4027, "name": "Frank Darabont", "job": "Screenplay", "profile_path": None}]},
    "/3/movie/603/credits": {"cast": [{"id": 6384, "name": "Keanu Reeves", "character": "Neo", "profile_path": None}], "crew": []},
    "/3/person/504": {"name": "Tim Robbins", "biography": "American actor and filmmaker.", "known_for_department": "Acting",
                      "birthday": "1958-10-16", "place_of_birth": "West Covina, California", "profile_path": None,
                      "combined_credits": {"cast": [
                          {"id": 278, "media_type": "movie", "title": "The Shawshank Redemption", "release_date": "1994-09-23", "character": "Andy Dufresne"},
                          {"id": 603, "media_type": "movie", "title": "The Matrix", "release_date": "1999-03-30", "character": "Cameo"},
                          {"id": 1396, "media_type": "tv", "name": "Breaking Bad", "first_air_date": "2008-01-20", "character": "Guest", "episode_count": 2},
                          {"id": 777, "media_type": "movie", "title": "Upcoming Film", "release_date": "", "character": "Lead"}],
                          "crew": [{"id": 603, "media_type": "movie", "title": "The Matrix", "release_date": "1999-03-30", "job": "Producer"}]}},
}
DISCOVER_MOVIE = {"results": [{"id": 603, "title": "The Matrix", "release_date": "1999-03-30", "overview": "A hacker learns the truth.", "vote_average": 8.2, "poster_path": None, "backdrop_path": None},
                              {"id": 278, "title": "The Shawshank Redemption", "release_date": "1994-09-23", "overview": "Hope.", "vote_average": 8.7, "poster_path": None, "backdrop_path": None}]}
DISCOVER_TV = {"results": [{"id": 1396, "name": "Breaking Bad", "first_air_date": "2008-01-20", "overview": "Chemistry.", "vote_average": 8.9, "poster_path": None, "backdrop_path": None}]}


TRAKT_LOG = []
ARR_LOG = []
ADDED = {"radarr": False}
GEMINI_LOG = []
HA_LOG = []
HA = {
    "light.living_room": {"state": "on", "attributes": {"friendly_name": "Living room lamp", "brightness": 128, "supported_color_modes": ["brightness"]}},
    "light.kitchen": {"state": "off", "attributes": {"friendly_name": "Kitchen lights", "supported_color_modes": ["color_temp"]}},
    "light.porch": {"state": "off", "attributes": {"friendly_name": "Porch light", "supported_color_modes": ["onoff"]}},
    "switch.tv_backlight": {"state": "on", "attributes": {"friendly_name": "TV backlight"}},
    "fan.bedroom": {"state": "off", "attributes": {"friendly_name": "Bedroom fan", "percentage": 0, "percentage_step": 33.3}},
    "climate.thermostat": {"state": "heat", "attributes": {"friendly_name": "Thermostat", "current_temperature": 69, "temperature": 70, "temperature_unit": "°F"}},
    "sensor.outside": {"state": "41", "attributes": {"friendly_name": "Outside temperature"}},
}
HA_AREAS = {"light.living_room": "Living Room", "switch.tv_backlight": "Living Room", "light.kitchen": "Kitchen", "fan.bedroom": "Bedroom"}
SOON = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(time.time() + 3 * 86400))


def arr_get(svc, p):
    if p == "/system/status":
        return {"version": "5.9.0" if svc == "radarr" else "4.0.10"}
    if p == "/qualityprofile":
        return [{"id": 1, "name": "HD-1080p"}, {"id": 4, "name": "Ultra-HD"}]
    if p == "/rootfolder":
        return [{"path": "/movies" if svc == "radarr" else "/tv"}]
    if svc == "radarr":
        if p.startswith("/movie/lookup"):
            return {"title": "The Shawshank Redemption", "year": 1994, "tmdbId": 278, "imdbId": "tt0111161", "images": [], "id": 5 if ADDED["radarr"] else 0}
        if p == "/movie/5":
            return {"id": 5, "title": "The Shawshank Redemption", "monitored": True, "hasFile": False}
        if p == "/queue":
            return {"records": [{"title": "Shawshank.1994.1080p", "movie": {"title": "The Shawshank Redemption"}, "size": 8e9, "sizeleft": 2e9,
                                 "timeleft": "00:12:00", "trackedDownloadState": "downloading"}]}
        if p == "/calendar":
            return [{"title": "Upcoming Movie", "digitalRelease": SOON, "imdbId": "tt0133093", "hasFile": False, "images": []}]
    else:
        if p.startswith("/series/lookup"):
            return [{"title": "Breaking Bad", "year": 2008, "tvdbId": 81189, "id": 7, "monitored": True}]
        if p == "/episode":
            return [{"id": 701, "seasonNumber": 2, "episodeNumber": 3}, {"id": 702, "seasonNumber": 2, "episodeNumber": 4}]
        if p == "/series/7":
            return {"id": 7, "title": "Breaking Bad", "monitored": True, "statistics": {"episodeFileCount": 40, "episodeCount": 62}}
        if p == "/queue":
            return {"records": []}
        if p == "/calendar":
            return [{"seasonNumber": 2, "episodeNumber": 3, "airDateUtc": SOON, "hasFile": False,
                     "series": {"title": "Test Show", "imdbId": "tt0903747", "images": []}}]
    return None


def sab(mode):
    if mode == "version":
        return {"version": "4.3.3"}
    if mode == "queue":
        return {"queue": {"paused": False, "speed": "12.5 M", "sizeleft": "3.2 GB", "timeleft": "0:04:10",
                          "slots": [{"nzo_id": "SABnzbd_nzo_1", "filename": "Some.Show.S02E03.1080p", "percentage": "45", "status": "Downloading",
                                     "sizeleft": "1.1 GB", "size": "2.0 GB", "timeleft": "0:01:30"}]}}
    if mode == "history":
        return {"history": {"slots": [{"name": "Old.Movie.2020.1080p", "status": "Completed", "size": "4.4 GB"}]}}
    return {"status": True}
TRAKT_POLLS = [0]
SRT = "1\n00:00:00,500 --> 00:10:00,000\nFLUX ADDON SUBTITLE\n"



REQS = []
SCREEN_LOG = []
TS_FILE = "/tmp/flux-test.ts"
STALKER_TOKEN = "TOK123"


def stalker_programmes(cid):
    start = NOW - NOW % 1800 - 6 * 3600
    out = []
    for i in range(20):
        t = start + i * 1800
        out.append({"id": "%s%02d" % (cid, i), "name": "Portal Show %d" % (i + 1), "descr": "From the portal guide.",
                    "start_timestamp": t, "stop_timestamp": t + 1800})
    return out


def espn_scoreboard(path):
    if not path.startswith("football/nfl"):
        return {"events": []}
    def team(name, nick, loc, abbr, score, home):
        return {"homeAway": "home" if home else "away", "score": score, "winner": False,
                "team": {"displayName": name, "shortDisplayName": nick, "name": nick, "location": loc, "abbreviation": abbr, "logo": ""},
                "records": [{"summary": "9-2"}]}
    iso = lambda t: time.strftime("%Y-%m-%dT%H:%MZ", time.gmtime(t))
    return {"events": [
        {"id": "401", "name": "Buffalo Bills at Kansas City Chiefs", "date": iso(NOW - 1800),
         "status": {"type": {"state": "in", "shortDetail": "2nd 4:12", "completed": False}},
         "competitions": [{"competitors": [team("Kansas City Chiefs", "Chiefs", "Kansas City", "KC", "17", True),
                                           team("Buffalo Bills", "Bills", "Buffalo", "BUF", "14", False)],
                           "broadcasts": [{"names": ["CBS"]}]}]},
        {"id": "402", "name": "Dallas Cowboys at Philadelphia Eagles", "date": iso(NOW + 4 * 3600),
         "status": {"type": {"state": "pre", "shortDetail": "8:20 PM", "completed": False}},
         "competitions": [{"competitors": [team("Philadelphia Eagles", "Eagles", "Philadelphia", "PHI", "0", True),
                                           team("Dallas Cowboys", "Cowboys", "Dallas", "DAL", "0", False)],
                           "broadcasts": [{"names": ["Sports One"]}]}]}]}

class H(BaseHTTPRequestHandler):
    def send(self, code, body, ctype="application/json"):
        data = body.encode() if isinstance(body, str) else body
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        u = urlparse(self.path)
        n = int(self.headers.get("Content-Length", 0) or 0)
        raw = self.rfile.read(n).decode("utf-8", "replace")
        if u.path.startswith("/trakt/"):
            tp = u.path[len("/trakt"):]
            TRAKT_LOG.append({"path": tp, "body": raw, "key": self.headers.get("trakt-api-key"), "auth": self.headers.get("Authorization")})
            if tp == "/oauth/device/code":
                return self.send(200, json.dumps({"device_code": "dev123", "user_code": "FLUX1234", "verification_url": "https://trakt.tv/activate",
                                                  "expires_in": 600, "interval": 1}))
            if tp == "/oauth/device/token":
                TRAKT_POLLS[0] += 1
                if TRAKT_POLLS[0] < 3:
                    return self.send(400, "{}")
                return self.send(200, json.dumps({"access_token": "acc", "refresh_token": "ref", "expires_in": 86400, "created_at": int(time.time())}))
            return self.send(201, json.dumps({"added": {}, "deleted": {}}))
        if u.path.startswith("/radarr/") or u.path.startswith("/sonarr/"):
            ARR_LOG.append({"path": u.path, "body": raw[:300]})
            if u.path == "/radarr/api/v3/movie":
                ADDED["radarr"] = True
            return self.send(201, "{}")
        if u.path.startswith("/ha/"):
            if self.headers.get("Authorization") != "Bearer hatoken":
                return self.send(401, '{"message":"Unauthorized"}')
            if u.path == "/ha/api/template":
                ids = list(HA.keys())
                return self.send(200, json.dumps({"ids": ids, "areas": [HA_AREAS.get(i) for i in ids]}), "text/plain")
            if u.path.startswith("/ha/api/services/"):
                dom, svc = u.path.split("/")[-2:]
                data = json.loads(raw or "{}")
                HA_LOG.append({"service": dom + "." + svc, "data": data})
                e = HA.get(data.get("entity_id"))
                if e:
                    if svc == "turn_on":
                        e["state"] = "on"
                        if "brightness_pct" in data: e["attributes"]["brightness"] = round(data["brightness_pct"] * 2.55)
                    elif svc == "turn_off":
                        e["state"] = "off"
                    elif svc == "set_percentage":
                        e["state"] = "on" if data["percentage"] > 0 else "off"; e["attributes"]["percentage"] = data["percentage"]
                    elif svc == "set_temperature":
                        e["attributes"]["temperature"] = data["temperature"]
                return self.send(200, "[]")
            return self.send(404, "{}")
        if u.path.endswith(":generateContent"):
            GEMINI_LOG.append({"path": u.path, "body": raw[:600]})
            if '"systemInstruction"' in raw:
                b = json.loads(raw)
                has_img = any("inlineData" in pt for c in b.get("contents", []) for pt in c.get("parts", []))
                turns = len(b.get("contents", []))
                SCREEN_LOG.append({"image": has_img, "turns": turns, "tools": "tools" in b, "q": b["contents"][-1]["parts"][-1]["text"]})
                return self.send(200, json.dumps({"candidates": [{"content": {"parts": [{"text":
                    "That's **the presenter** in front of a test pattern%s. (turn %d)" % (" I can see the frame" if has_img else "", turns)}]}}]}))
            text = json.dumps([{"title": "The Matrix", "year": 1999, "type": "movie"},
                               {"title": "Breaking Bad", "year": 2008, "type": "series"}])
            return self.send(200, json.dumps({"candidates": [{"content": {"parts": [{"text": text}]}}]}))
        self.send(404, "not found")

    def do_PUT(self):
        u = urlparse(self.path)
        n = int(self.headers.get("Content-Length", 0) or 0)
        raw = self.rfile.read(n).decode("utf-8", "replace")
        ARR_LOG.append({"path": "PUT " + u.path, "body": raw[:300]})
        self.send(202, "{}")

    def do_GET(self):
        u = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(u.query).items()}
        p = u.path
        REQS.append(self.path)
        if p == "/_screen":
            return self.send(200, json.dumps(SCREEN_LOG))
        if p == "/_reqs":
            return self.send(200, json.dumps(REQS[-400:]))
        if p.startswith("/timeshift/") or p.startswith("/stalker-play/"):
            try:
                data = open(TS_FILE, "rb").read()
            except Exception:
                return self.send(404, "no test stream")
            return self.send(200, data, "video/mp2t")
        if p == "/stalker/portal.php":
            t, a = q.get("type", ""), q.get("action", "")
            if t == "stb" and a == "handshake":
                return self.send(200, json.dumps({"js": {"token": STALKER_TOKEN, "random": "x"}}))
            if self.headers.get("Authorization", "") != "Bearer " + STALKER_TOKEN or "00%3A1A%3A79%3A00%3A00%3A01" not in self.headers.get("Cookie", "").upper().replace("%3a", "%3A"):
                return self.send(200, "Authorization failed.", "text/plain")
            if t == "stb" and a == "get_profile":
                return self.send(200, json.dumps({"js": {"id": "1", "status": 0}}))
            if t == "itv" and a == "get_genres":
                return self.send(200, json.dumps({"js": [{"id": "*", "title": "All"}, {"id": "10", "title": "Portal News"}, {"id": "11", "title": "Portal Sports"}]}))
            if t == "itv" and a == "get_all_channels":
                return self.send(200, json.dumps({"js": {"data": [
                    {"id": "501", "name": "Portal One", "number": "1", "logo": "", "cmd": "ffmpeg http://localhost/ch/501", "tv_genre_id": "10", "xmltv_id": "", "tv_archive": 1, "tv_archive_duration": 72},
                    {"id": "502", "name": "Portal Sports", "number": "2", "logo": "", "cmd": "ffmpeg http://localhost/ch/502", "tv_genre_id": "11", "xmltv_id": "", "tv_archive": 0}]}}))
            if t == "itv" and a == "create_link":
                return self.send(200, json.dumps({"js": {"cmd": "ffmpeg http://10.0.2.2:8000/stalker-play/live.ts?play_token=abc"}}))
            if t == "itv" and a == "get_epg_info":
                return self.send(200, json.dumps({"js": {"data": {"501": stalker_programmes("501"), "502": stalker_programmes("502")}}}))
            if t == "itv" and a == "get_short_epg":
                return self.send(200, json.dumps({"js": stalker_programmes(q.get("ch_id", "501"))}))
            if t == "tv_archive" and a == "create_link":
                return self.send(200, json.dumps({"js": {"cmd": "auto http://10.0.2.2:8000/stalker-play/archive.ts"}}))
            return self.send(200, json.dumps({"js": []}))
        if p.startswith("/espn/apis/site/v2/sports/") and p.endswith("/scoreboard"):
            return self.send(200, json.dumps(espn_scoreboard(p[len("/espn/apis/site/v2/sports/"):])))
        if p.startswith("/espn/apis/v2/sports/") and p.endswith("/standings"):
            entry = lambda n, w, l: {"team": {"displayName": n}, "stats": [{"name": "wins", "displayValue": str(w)}, {"name": "losses", "displayValue": str(l)}, {"name": "winPercent", "displayValue": ".%03d" % (1000 * w // (w + l))}]}
            return self.send(200, json.dumps({"children": [
                {"name": "AFC", "standings": {"entries": [entry("Kansas City Chiefs", 9, 2), entry("Buffalo Bills", 8, 3)]}},
                {"name": "NFC", "standings": {"entries": [entry("Philadelphia Eagles", 9, 2), entry("Dallas Cowboys", 5, 6)]}}]}))
        if p.startswith("/mdblist/lists/top") or p.startswith("/mdblist/lists/user"):
            return self.send(200, json.dumps([{"id": 1, "name": "Top Watched Movies of the Week", "slug": "top-watched-movies-of-the-week", "user_name": "linaspurinis", "mediatype": "movie", "items": 3},
                                              {"id": 2, "name": "Latest TV Shows", "slug": "latest-tv-shows", "user_name": "garycrawfordgc", "mediatype": "show", "items": 2}]))
        if p.startswith("/mdblist/lists/") and p.endswith("/items"):
            return self.send(200, json.dumps({"movies": [
                {"id": 1, "rank": 1, "title": "The Shawshank Redemption", "imdb_id": "tt0111161", "mediatype": "movie", "release_year": 1994},
                {"id": 2, "rank": 2, "title": "The Matrix", "imdb_id": "tt0133093", "mediatype": "movie", "release_year": 1999},
                {"id": 3, "rank": 3, "title": "The Godfather", "imdb_id": "tt0068646", "mediatype": "movie", "release_year": 1972}], "shows": []}))
        if p.startswith("/weather/v1/search"):
            return self.send(200, json.dumps({"results": [{"name": "Chicago", "admin1": "Illinois", "latitude": 41.85, "longitude": -87.65}]}))
        if p.startswith("/weather/v1/forecast"):
            return self.send(200, json.dumps({"current": {"temperature_2m": 47.6, "weather_code": 2, "is_day": 1}}))
        if p.startswith("/introdb/segments"):
            q = parse_qs(urlparse(self.path).query)
            if q.get("imdb_id", [""])[0] == "tt0903747":
                return self.send(200, json.dumps({"imdb_id": "tt0903747", "segments": [
                    {"segment_type": "intro", "start_ms": 2000, "end_ms": 40000},
                    {"segment_type": "outro", "start_ms": 62000, "end_ms": None}]}))
            return self.send(404, json.dumps({"error": "not found"}))
        if p.startswith("/ha/"):
            if p == "/ha/_log":
                return self.send(200, json.dumps({"log": HA_LOG, "states": HA}, indent=1))
            if self.headers.get("Authorization") != "Bearer hatoken":
                return self.send(401, '{"message":"Unauthorized"}')
            if p == "/ha/api/config":
                return self.send(200, json.dumps({"location_name": "Test Home", "version": "2026.11.1"}))
            if p == "/ha/api/states":
                return self.send(200, json.dumps([{"entity_id": k, "state": v["state"], "attributes": v["attributes"]} for k, v in HA.items()]))
            return self.send(404, "{}")
        if p == "/arr/_log":
            return self.send(200, json.dumps({"arr": ARR_LOG, "gemini": GEMINI_LOG}, indent=1))
        for svc in ("radarr", "sonarr"):
            if p.startswith("/" + svc + "/api/v3"):
                r = arr_get(svc, p[len("/" + svc + "/api/v3"):])
                ARR_LOG.append({"path": self.path})
                return self.send(200, json.dumps(r)) if r is not None else self.send(404, "{}")
        if p == "/sabnzbd/api":
            ARR_LOG.append({"path": self.path.split("apikey")[0]})
            return self.send(200, json.dumps(sab(q.get("mode", ""))))
        if p.startswith("/trakt/"):
            tp = p[len("/trakt"):]
            if tp == "/_log":
                return self.send(200, json.dumps(TRAKT_LOG, indent=1))
            if tp == "/users/settings":
                return self.send(200, json.dumps({"user": {"username": "fluxtester"}}))
            if tp == "/sync/watchlist/movies":
                return self.send(200, json.dumps([{"movie": {"title": "The Matrix", "year": 1999, "ids": {"imdb": "tt0133093", "tmdb": 603}}}]))
            if tp == "/sync/watchlist/shows":
                return self.send(200, json.dumps([{"show": {"title": "Breaking Bad", "year": 2008, "ids": {"imdb": "tt0903747", "tmdb": 1396}}}]))
            if tp == "/sync/playback":
                return self.send(200, json.dumps([{"progress": 42.5, "paused_at": time.strftime("%Y-%m-%dT%H:%M:%S.000Z", time.gmtime()),
                                                   "type": "movie", "movie": {"title": "The Shawshank Redemption", "year": 1994, "runtime": 142,
                                                                             "ids": {"imdb": "tt0111161"}}}]))
            return self.send(404, "not found")
        # A subtitles add-on
        if p == "/subs/manifest.json":
            return self.send(200, json.dumps({"id": "test.subs", "version": "1.0.0", "name": "Flux Subs", "resources": ["subtitles"],
                                              "types": ["movie", "series"], "idPrefixes": ["tt"], "catalogs": []}))
        if p.startswith("/subs/subtitles/"):
            return self.send(200, json.dumps({"subtitles": [{"id": "1", "url": "http://10.0.2.2:8000/subs/test.srt", "lang": "eng"},
                                                            {"id": "2", "url": "http://10.0.2.2:8000/subs/test.srt", "lang": "spa"}]}))
        if p == "/subs/test.srt":
            return self.send(200, SRT, "application/x-subrip")
        if p == "/gemini/models":
            return self.send(200, json.dumps({"models": [{"name": "models/gemini-9.9-flash", "supportedGenerationMethods": ["generateContent"]},
                                                         {"name": "models/gemini-9.9-flash-lite", "supportedGenerationMethods": ["generateContent"]}]}))
        if p in TMDB:
            return self.send(200, json.dumps(TMDB[p]))
        if p == "/3/discover/movie" or p == "/3/search/movie":
            return self.send(200, json.dumps(DISCOVER_MOVIE))
        if p == "/3/discover/tv" or p == "/3/search/tv":
            return self.send(200, json.dumps(DISCOVER_TV))
        if p == "/player_api.php":
            if q.get("username") != "test" or q.get("password") != "test":
                return self.send(200, json.dumps({"user_info": {"auth": 0}}))
            a = q.get("action", "")
            if a == "":
                return self.send(200, json.dumps({"user_info": {"auth": 1, "status": "Active", "allowed_output_formats": ["ts", "m3u8"]},
                                                  "server_info": {"url": "10.0.2.2", "port": "8000", "timezone": "UTC"}}))
            if a == "get_live_categories":
                return self.send(200, json.dumps(CATS))
            if a == "get_live_streams":
                return self.send(200, json.dumps(LIVE))
            if a == "get_short_epg":
                sid = q.get("stream_id")
                return self.send(200, json.dumps({"epg_listings": [
                    {"title": b64("Morning Show " + sid), "description": b64("Live"), "start_timestamp": str(NOW - 1800), "stop_timestamp": str(NOW + 1800)},
                    {"title": b64("Evening Show " + sid), "description": b64("Next"), "start_timestamp": str(NOW + 1800), "stop_timestamp": str(NOW + 5400)}]}))
            if a == "get_vod_categories":
                return self.send(200, json.dumps(VOD_CATS))
            if a == "get_vod_streams":
                return self.send(200, json.dumps(VOD))
            if a == "get_series_categories":
                return self.send(200, json.dumps(SERIES_CATS))
            if a == "get_series":
                return self.send(200, json.dumps(SERIES))
            if a == "get_vod_info":
                return self.send(200, json.dumps({"info": {"name": "Test Movie One", "plot": "A movie from the fake provider.",
                                                           "genre": "Drama, Comedy", "duration": "01:42:00", "releasedate": "2023-05-01", "rating": "7.1"},
                                                  "movie_data": {"stream_id": 501, "container_extension": "mp4"}}))
            if a == "get_series_info":
                return self.send(200, json.dumps({"info": {"name": "Test Series", "plot": "A test show.", "genre": "Drama"},
                                                  "episodes": {"1": [{"id": "7001", "episode_num": 1, "title": "Pilot", "container_extension": "mp4", "season": 1, "info": {}},
                                                                     {"id": "7002", "episode_num": 2, "title": "Second", "container_extension": "mp4", "season": 1, "info": {}}]}}))
            return self.send(200, "[]")
        # Live: .ts is "not available" so the player must fall back to .m3u8
        if p.startswith("/live/") and p.endswith(".ts"):
            return self.send(404, "no ts")
        if p.startswith("/live/") and p.endswith(".m3u8"):
            # Real Xtream servers usually redirect streams to a load balancer.
            self.send_response(302)
            self.send_header("Location", HLS)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if p == "/list.m3u":
            body = "#EXTM3U\n"
            for c in LIVE:
                body += '#EXTINF:-1 tvg-id="%s" tvg-chno="%d" group-title="Playlist %s",%s\n%s\n' % (
                    c["epg_channel_id"], c["num"], "News" if c["category_id"] == "1" else "Sports", c["name"], HLS)
            return self.send(200, body, "audio/x-mpegurl")
        if p == "/xmltv.php":
            fmt = lambda t: time.strftime("%Y%m%d%H%M%S +0000", time.gmtime(t))
            start = NOW - NOW % 1800 - 3600
            body = '<?xml version="1.0" encoding="UTF-8"?><tv>'
            for c in [c for c in LIVE if c["epg_channel_id"]]:
                body += '<channel id="%s"><display-name>%s</display-name></channel>' % (c["epg_channel_id"], c["name"])
            shows = ["Morning Brief", "Market Watch", "The Big Match", "Nature Hour", "Late Night Live", "Documentary: Oceans", "Evening News", "Comedy Club"]
            for ci, c in enumerate([c for c in LIVE if c["epg_channel_id"]]):
                t = start
                k = ci
                while t < NOW + 8 * 3600:
                    dur = 1800 if k % 3 else 3600
                    title = shows[k % len(shows)]
                    if c["epg_channel_id"] == "sports.flux" and t <= NOW < t + dur:
                        title = "NFL Football: Buffalo Bills at Kansas City Chiefs"
                    body += '<programme start="%s" stop="%s" channel="%s"><title>%s</title><desc>%s on %s. A description of this programme for the guide.</desc></programme>' % (
                        fmt(t), fmt(t + dur), c["epg_channel_id"], title, title, c["name"])
                    t += dur
                    k += 1
            body += '</tv>'
            return self.send(200, body, "application/xml")
        if p == "/extra-epg.xml":
            fmt = lambda t: time.strftime("%Y%m%d%H%M%S +0000", time.gmtime(t))
            start = NOW - NOW % 1800 - 3600
            body = '<?xml version="1.0"?><tv><channel id="cook.other.us"><display-name>Cooking</display-name></channel>'
            body += '<channel id="unrelated.us"><display-name>Unrelated</display-name></channel>'
            for i in range(10):
                body += '<programme start="%s" stop="%s" channel="cook.other.us"><title>Pasta Masterclass %d</title><desc>From the extra guide.</desc></programme>' % (
                    fmt(start + i * 3600), fmt(start + (i + 1) * 3600), i + 1)
            body += '</tv>'
            return self.send(200, body, "application/xml")
        if p == "/epg.xml":
            fmt = lambda t: time.strftime("%Y%m%d%H%M%S +0000", time.gmtime(t))
            body = '<?xml version="1.0"?><tv>'
            for c in LIVE:
                body += '<programme start="%s" stop="%s" channel="%s"><title>Guide Now %s</title><desc>x</desc></programme>' % (fmt(NOW - 600), fmt(NOW + 1200), c["epg_channel_id"], c["name"])
                body += '<programme start="%s" stop="%s" channel="%s"><title>Guide Next %s</title></programme>' % (fmt(NOW + 1200), fmt(NOW + 4800), c["epg_channel_id"], c["name"])
            body += "</tv>"
            return self.send(200, body, "application/xml")
        self.send(404, "not found")

    def log_message(self, *a):
        pass


ThreadingHTTPServer(("0.0.0.0", 8000), H).serve_forever()
