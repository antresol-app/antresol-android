#!/usr/bin/env python3
"""A small fake Antresol server for driving the apps by hand (an emulator or a device).

    scripts/fake-server.py [--port 8421] [--host 0.0.0.0] [--trailer <YouTube id>] [--rebuild-after <pages>]

It answers what test-fixtures' FakeServer answers (one passwordless profile, home, grids, a film and
a series page, trailers) with the same JSON, plus live channels and the programme guide. In an
emulator the address is http://10.0.2.2:8421 (type it: the server screen also finds the real
servers of the home network). The port is not 8420, the one the live tests (ANTRESOL_TEST_SERVER=1)
look for a real server on. Nothing plays except the server-played trailer of «Movie 1»; its
«Teaser» has no `play`, so it opens the embedded YouTube player with --trailer's video.

The guide is one fixed schedule per channel (it depends on the channel and the UTC day the script
was started on: it stays as it is when midnight passes during a run), cut
into pages the way the server's `epgGrid` does: the schedule entries that overlap [from, from+hours),
then the parts of one film (same item, another file, back to back) joined into one programme whose
id is its first entry IN THE PAGE. So a programme across a page edge is sent by both pages, and a
film in parts (channel 1 shows nothing else) comes cut differently by each: with another end, or
with another id and start.

With --rebuild-after N the schedule is rebuilt once, after the Nth guide page was answered, the way
the server does it (a file went missing, an external title failed): the entries that start an hour
or more after that moment are dropped and made again from there, numbered on from the last one
kept. So the pages asked for later have other titles and times under ids the earlier pages used.
"""
import argparse
import json
import re
import struct
import zlib
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

SERVER_ID = "fedcba9876543210fedcba9876543210"
ME = {"id": 1, "name": "Alex", "avatar": "grad-3", "hasPassword": False, "isAdmin": True, "isKid": False}
SERIES, MOVIE, EPISODES = 500, 100, list(range(601, 607))
CAST = [{"name": n, "character": c, "photo": "/api/images/cast/" + n.replace(" ", "%20")}
        for n, c in [("Ada Lovelace", "The Countess"), ("Alan Turing", "The Mathematician"), ("Grace Hopper", "The Admiral")]]
CLIP = Path(__file__).resolve().parent.parent / "test-fixtures/resources/trailer-sample.mp4"
MIN = 60_000
# Names of the categories --categories makes (a playlist's group titles).
GROUPS = ["Movies", "Series", "News", "Sports", "Kids", "Music", "Documentary", "Entertainment", "Comedy", "Science", "Travel", "Cooking"]
# Programme lengths (minutes) a channel cycles through; channel 1 is films in two parts.
LENGTHS = [[50, 50], [45, 25, 120, 30], [50, 95, 10, 45], [70, 145, 25], [15, 30, 15, 60], [120, 90], [105, 45, 30], [25, 25, 50, 20]]
favorites = {2}
last_channel = [3]
# --channels / --categories: a big playlist. The channel count (the 8 programme patterns repeat) and the
# category names, in blocks of consecutive channel numbers like a playlist's groups (None: Films/Series by parity).
big = {"channels": len(LENGTHS), "categories": None}
# The schedule's day: fixed for the run.
DAY = datetime.now(timezone.utc).replace(hour=0, minute=0, second=0, microsecond=0)
# --rebuild-after: guide pages still to answer before the rebuild (None: never), and when it happened (ms).
rebuild = {"pages_left": None, "at": None}


def movie_card(i, **extra):
    return {"id": i, "kind": "movie", "title": f"Movie {i - MOVIE + 1}", "year": 2000 + i % 20, "badges": ["1080p"],
            "poster": f"/api/images/local/{i}/poster", "backdrop": f"/api/images/local/{i}/backdrop", **extra}


def series_card(i=SERIES, title="The Test Show"):
    return {"id": i, "kind": "series", "title": title, "year": 2021, "logo": f"/api/images/local/{i}/logo",
            "poster": f"/api/images/local/{i}/poster", "backdrop": f"/api/images/local/{i}/backdrop"}


def episode_card(i, season, number, **extra):
    return {"id": i, "kind": "episode", "title": f"Episode {number}", "subtitle": f"The Test Show · S{season}:E{number}",
            "poster": f"/api/images/local/{SERIES}/poster", "backdrop": f"/api/images/local/{SERIES}/backdrop",
            "thumb": f"/api/images/frame/{i}", "seriesId": SERIES, "seasonNo": season, "episodeNo": number,
            "season": season, "episode": number, "durationMs": 2700000, **extra}


def home():
    return {"hero": [series_card()], "shelves": [
        {"id": "continue", "title": "Continue Watching", "cards": [episode_card(EPISODES[1], 1, 2, progress=0.4), movie_card(MOVIE, progress=0.3)]},
        {"id": "recent-series", "title": "New Series", "cards": [series_card()]},
        {"id": "recent-movies", "title": "New Movies", "cards": [movie_card(MOVIE + i) for i in range(6)]}]}


def item(i):
    if i == SERIES:
        seasons = [{"id": SERIES + s, "number": s, "title": f"Season {s}",
                    "episodes": [episode_card(e, s, n + 1) for n, e in enumerate(EPISODES[(s - 1) * 4:s * 4])]} for s in (1, 2)]
        return {**series_card(), "overview": "A test series about testing things on a television.", "genres": ["Drama", "Comedy"],
                "rating": "16+", "cast": CAST, "seasons": seasons, "next": episode_card(EPISODES[1], 1, 2, progress=0.4)}
    if i in EPISODES:
        return {"id": i, "kind": "episode", "title": "Episode", "seriesId": SERIES, "files": [{"id": i + 1000, "durationMs": 2700000}]}
    return {**movie_card(i), "overview": "A test movie.", "genres": ["Adventure"], "runtime": 104, "cast": CAST,
            "files": [{"id": i + 1000, "durationMs": 6240000, "audio": [], "subs": []}], "actions": {"play": True}}


def trailers(i, embed_key):
    if i != MOVIE:
        return {"trailers": []}
    base = {"site": "YouTube", "lang": "en", "official": True}
    return {"trailers": [
        {**base, "name": "Official Trailer", "key": "movieTrailer1", "type": "Trailer", "url": "https://www.youtube.com/watch?v=movieTrailer1", "play": "/api/trailers/movieTrailer1/play"},
        {**base, "name": "Teaser", "key": embed_key, "type": "Teaser", "url": "https://www.youtube.com/watch?v=" + embed_key}]}


def png(path, sized=False):
    """A flat PNG in a colour of its own (posters upright). A logo is a white ring on nothing; asked
    for with `?w=` ([sized]) it comes on black, as the server's smaller copies (JPEG) do."""
    w, h = (20, 30) if path.endswith("/poster") or "/cast/" in path else (48, 27)
    seed = zlib.crc32(path.encode())
    pixel = bytes(60 + (seed >> shift) % 120 for shift in (0, 8, 16)) + b"\xff"
    rows = [[pixel] * w for _ in range(h)]
    if "logo" in path:
        w, h = 90, 60
        ring = lambda x, y: 20 <= ((x - 45) ** 2 + (y - 30) ** 2) ** 0.5 <= 28
        rows = [[b"\xff\xff\xff\xff" if ring(x, y) else b"\0\0\0\xff" if sized else b"\0\0\0\0" for x in range(w)] for y in range(h)]
    raw = b"".join(b"\0" + b"".join(row) for row in rows)

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")


def entries(channel):
    """The channel's schedule entries (seq, item, file, start, end in ms) from the day before the run's to three days after."""
    at, end = int((DAY - timedelta(days=1)).timestamp() * 1000), int((DAY + timedelta(days=3)).timestamp() * 1000)
    lengths, out, seq = LENGTHS[(channel - 1) % len(LENGTHS)], [], 0
    # After a rebuild: the entries from `cut` on are other titles (and, but for channel 1, other lengths).
    cut, rebuilt = (rebuild["at"] + 60 * MIN if rebuild["at"] is not None else None), False
    while at < end:
        # Channel 1: a film is two files, back to back; it is cut between two films.
        rebuilt = rebuilt or (cut is not None and at >= cut and (channel != 1 or seq % 2 == 0))
        length = lengths[(seq + rebuilt) % len(lengths)] * MIN
        film, part = (seq // 2, seq % 2) if channel == 1 else (seq, 0)
        film += 500 * rebuilt
        out.append({"seq": seq, "item": channel * 1000 + film, "file": channel * 100000 + film * 10 + part, "start": at, "end": at + length})
        at, seq = at + length, seq + 1
    return out


def iso(ms):
    return datetime.fromtimestamp(ms / 1000, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def programs(channel, from_ms, to_ms):
    """`Store.Range` + `channelsAPI.programs` of the server: the entries overlapping [from, to), a film's parts joined."""
    es = [e for e in entries(channel) if e["end"] > from_ms and e["start"] < to_ms]
    out, i = [], 0
    while i < len(es):
        e, end = es[i], es[i]["end"]
        while i + 1 < len(es) and es[i + 1]["item"] == e["item"] and es[i + 1]["file"] != e["file"] and es[i + 1]["start"] == end:
            i += 1
            end = es[i]["end"]
        i += 1
        out.append({"id": f"{channel}-{e['seq']}", "itemId": e["item"], "kind": "movie", "title": f"Programme {e['item'] % 1000 + 1} of channel {channel}",
                    "overview": "A test programme.", "year": 2020, "start": iso(e["start"]), "end": iso(end),
                    "poster": f"/api/images/local/{e['item']}/poster", "backdrop": f"/api/images/local/{e['item']}/backdrop"})
    return out


def channel(number):
    # Channels 2 and 5 have an uploaded logo (transparent), as the server names it.
    logo = {"logoUrl": f"/api/channels/{number}/logo.png?v=1"} if number in (2, 5) else {}
    names = big["categories"]
    if names:
        # A tenth of the channels has no group, as in playlists that do not set one.
        category = "" if number % 10 == 0 else names[(number - 1) * len(names) // big["channels"]]
    else:
        category = "Films" if number % 2 else "Series"
    return {"id": number, "number": number, "name": f"Channel {number}", "category": category, "favorite": number in favorites, **logo}


def channels(now_ms):
    out = []
    for number in range(1, big["channels"] + 1):
        on_air = programs(number, now_ms, now_ms + 6 * 60 * MIN)
        out.append({**channel(number), "personal": False, "auto": True, "enabled": True, "now": on_air[0], "next": on_air[1]})
    return {"channels": out, "lastChannel": last_channel[0]}


def epg(query, now_ms):
    """`GET /api/channels/epg?from=&hours=`, clamped as the server clamps it."""
    from_ms = (now_ms - 60 * MIN) // (30 * MIN) * (30 * MIN)
    if "from" in query:
        from_ms = int(datetime.fromisoformat(query["from"][0].replace("Z", "+00:00")).timestamp() * 1000)
    from_ms = min(max(from_ms, now_ms - 12 * 60 * MIN), now_ms + 47 * 60 * MIN)
    hours = min(int(query.get("hours", ["0"])[0] or 0) or 6, 24)
    to_ms = from_ms + hours * 60 * MIN
    rows = [{**channel(n), "programs": programs(n, from_ms, to_ms)} for n in range(1, big["channels"] + 1)]
    if rebuild["pages_left"]:
        rebuild["pages_left"] -= 1
        if not rebuild["pages_left"]:
            rebuild["at"] = now_ms
            print(f"schedule rebuilt from {iso(now_ms + 60 * MIN)} on", flush=True)
    return {"from": iso(from_ms), "to": iso(to_ms), "channels": rows}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    embed_key = "aqz-KE-bpKQ"

    def send(self, status, body, content_type="application/json"):
        data = body if isinstance(body, bytes) else json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(data)

    def answer(self):
        url = urlparse(self.path)
        path, query = url.path, parse_qs(url.query)
        now_ms = int(datetime.now(timezone.utc).timestamp() * 1000)
        self.rfile.read(int(self.headers.get("Content-Length") or 0))
        if path.startswith("/api/images/") or path.endswith("/logo.png"):
            return self.send(200, png(path, sized="w" in query), "image/png")
        if path.endswith(".mp4"):
            return self.send(200, CLIP.read_bytes(), "video/mp4")
        if re.fullmatch(r"/api/trailers/movieTrailer1/play", path):
            return self.send(200, {"url": "/api/trailers/movieTrailer1.mp4?mk=test-mk", "durationMs": 30000})
        if match := re.fullmatch(r"/api/channels/(\d+)/favorite", path):
            (favorites.add if self.command == "PUT" else favorites.discard)(int(match[1]))
            return self.send(204, b"")
        if path in ("/api/channels/last", "/api/channels/opened"):
            return self.send(204, b"")
        if match := re.fullmatch(r"/api/items/(\d+)/trailers", path):
            return self.send(200, trailers(int(match[1]), self.embed_key))
        if match := re.fullmatch(r"/api/items/(\d+)", path):
            return self.send(200, item(int(match[1])))
        if path == "/api/items":
            series = query.get("kind") == ["series"]
            cards = [series_card()] + [series_card(SERIES + 100 + i, f"Show {i + 1}") for i in range(1, 30)] if series else [movie_card(MOVIE + i) for i in range(23)]
            return self.send(200, {"items": cards, "total": len(cards)})
        answers = {
            "/api/health": {"ok": True, "serverId": SERVER_ID, "version": "1.5.0"},
            "/api/setup": {"needsSetup": False},
            "/api/profiles": [{"id": 1, "name": "Alex", "avatar": "grad-3", "hasPassword": False}],
            "/api/auth/token": {"token": "test-device-token", "user": ME},
            "/api/me": ME,
            "/api/me/ui-language": {"language": "", "effective": "en"},
            "/api/me/playback-prefs": {"audioLangs": [], "subMode": "off", "subLangs": []},
            "/api/home": home(),
            "/api/music/home": {"shelves": []},
            "/api/books/home": {"shelves": []},
            "/api/music/genres": {"genres": []},
        }
        if path in answers:
            return self.send(200, answers[path])
        if path == "/api/channels":
            return self.send(200, channels(now_ms))
        if path == "/api/channels/epg":
            return self.send(200, epg(query, now_ms))
        if path.startswith("/api/progress/") and self.command == "GET":
            return self.send(200, {"positionMs": 0, "durationMs": 0, "finished": False})
        self.send(404, {"error": "not found"})

    do_GET = do_POST = do_PUT = do_DELETE = do_HEAD = answer


if __name__ == "__main__":
    args = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    args.add_argument("--port", type=int, default=8421)
    args.add_argument("--host", default="127.0.0.1", help="0.0.0.0 to let a device on the network in")
    args.add_argument("--trailer", default=Handler.embed_key, help="the YouTube video the embedded trailer plays")
    args.add_argument("--rebuild-after", type=int, metavar="PAGES", help="rebuild the schedule once, after this many guide pages were answered")
    args.add_argument("--channels", type=int, metavar="N", help="serve N channels (default 8), to try a big playlist")
    args.add_argument("--categories", type=int, metavar="M", help="spread the channels over M categories (named like a playlist's groups)")
    options = args.parse_args()
    if options.channels:
        big["channels"] = options.channels
    if options.categories:
        big["categories"] = [f"{GROUPS[i % len(GROUPS)]}" + (f" {i // len(GROUPS) + 1}" if i >= len(GROUPS) else "") for i in range(options.categories)]
    Handler.embed_key = options.trailer
    rebuild["pages_left"] = options.rebuild_after
    print(f"http://127.0.0.1:{options.port} (in an emulator: http://10.0.2.2:{options.port})", flush=True)
    ThreadingHTTPServer((options.host, options.port), Handler).serve_forever()
