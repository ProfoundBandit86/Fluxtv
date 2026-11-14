#!/usr/bin/env python3
"""Tiny fake Xtream Codes / M3U / XMLTV server for the emulator test (reached at 10.0.2.2:8000)."""
import base64, json, time
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse, parse_qs

HLS = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
NOW = int(time.time())


def b64(s):
    return base64.b64encode(s.encode()).decode()


CATS = [{"category_id": "1", "category_name": "News"}, {"category_id": "2", "category_name": "Sports"}]
LIVE = [
    {"num": 101, "name": "Flux News", "stream_id": 1001, "stream_icon": "", "epg_channel_id": "news.flux", "category_id": "1"},
    {"num": 102, "name": "World Report", "stream_id": 1002, "stream_icon": "", "epg_channel_id": "world.flux", "category_id": "1"},
    {"num": 201, "name": "Sports One", "stream_id": 2001, "stream_icon": "", "epg_channel_id": "sports.flux", "category_id": "2"},
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
TRAKT_POLLS = [0]
SRT = "1\n00:00:00,500 --> 00:10:00,000\nFLUX ADDON SUBTITLE\n"


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
        if u.path.endswith(":generateContent"):
            text = json.dumps([{"title": "The Matrix", "year": 1999, "type": "movie"},
                               {"title": "Breaking Bad", "year": 2008, "type": "series"}])
            return self.send(200, json.dumps({"candidates": [{"content": {"parts": [{"text": text}]}}]}))
        self.send(404, "not found")

    def do_GET(self):
        u = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(u.query).items()}
        p = u.path
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
                                                  "server_info": {"url": "10.0.2.2", "port": "8000"}}))
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


HTTPServer(("0.0.0.0", 8000), H).serve_forever()
