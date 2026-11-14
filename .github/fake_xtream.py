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


class H(BaseHTTPRequestHandler):
    def send(self, code, body, ctype="application/json"):
        data = body.encode() if isinstance(body, str) else body
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        u = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(u.query).items()}
        p = u.path
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
            return self.send(200, "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\n" + HLS + "\n", "application/vnd.apple.mpegurl")
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
