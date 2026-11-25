#!/usr/bin/env python3
"""A small fake Antresol server for driving the apps by hand (an emulator or a device).

    scripts/fake-server.py [--port 8421] [--host 0.0.0.0] [--trailer <YouTube id>] [--rebuild-after <pages>] [--addons] [--kid] [--requests]

It answers what test-fixtures' FakeServer answers (passwordless profiles, one with a photo, home, grids, a film and
a series page, trailers) with the same JSON, plus live channels and the programme guide. In an
emulator the address is http://10.0.2.2:8421 (type it: the server screen also finds the real
servers of the home network). The port is not 8420, the one the live tests (ANTRESOL_TEST_SERVER=1)
look for a real server on. Nothing plays except the server-played trailer of «Movie 1» (its
«Teaser» has no `play`, so it opens the embedded YouTube player with --trailer's video) and the music.

Music is one album («Golden Hours» by «Northern Static», three tracks) under Music in the top menu.
The tracks are 60 s WAV melodies made here from sine waves (Python's standard library only, rendered on
the first request of each track), served from `/api/tracks/<id>/stream` with Range support, so they
play with sound, seek, and keep playing in the background. Stars are kept for the run; plays are
accepted and forgotten; no track has lyrics.

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

«Отдельная история на этом устройстве»: `GET /api/me/managed`, `POST /api/me/managed`,
`PUT /api/me/devices/current/history` and the `history` of `GET /api/me/devices` / `deviceHistory` of
`/api/me` answer as the server does, kept for the run (one history, «Living Room», to start with; a
history switched off with merge=false comes back on the next switch on without an account). Home's
Continue Watching is another one while the device writes a separate history, so a reload shows. With
--kid the profile is a kid's: the writes answer 403.

With --addons the server has one addon, "alpha", speaking the `x/app` protocol: a way to watch (the first
time it opens a choice of a source and a quality, then it plays at once with what was picked; holding it
chooses again), a way that keeps a copy on the server (a ring shows how far it is and «Cancel» stops it), a
title search (one title of the library, one that is not: its page comes from `GET /api/title`) and the
core's input player for what the way plays (the trailer clip). Without the flag the server has no addons.

With --requests the server has kids' requests open («Попросить»): a kid profile (--kid) searches the catalogue
(`GET /api/requests/search`: a film and a series off the library, a film above its age limit, one album), asks
(`POST /api/requests`), and sees its own requests (`GET /api/requests/mine`) and the state of a title on its page
(`kidRequest` of `GET /api/title`); an administrator (no --kid) gets the list to answer (`GET /api/requests`,
`/count`, `POST /{id}/approve`, `/{id}/decline`). Three requests of the kid «Sam» are there to start with (waiting,
approved, declined with a reason); what is asked or answered is kept for the run.
"""
import argparse
import json
import math
import re
import struct
import sys
import threading
import zlib
from array import array
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

SERVER_ID = "fedcba9876543210fedcba9876543210"
ME = {"id": 1, "name": "Alex", "avatar": "grad-3", "photo": "/api/profiles/1/photo?v=1", "hasPassword": False,
      "isAdmin": True, "isKid": False}
# Alex has a photo (the server's address, versioned by the upload); Sam has none and keeps the letter.
PROFILES = [{"id": 1, "name": "Alex", "avatar": "grad-3", "photo": ME["photo"], "hasPassword": False},
            {"id": 2, "name": "Sam", "avatar": "grad-5", "hasPassword": False}]
SERIES, MOVIE, EPISODES = 500, 100, list(range(601, 607))
SERIES_TMDB, MOVIE_TMDB, OUTSIDE_TMDB = 1399, 603, 9001
# --addons: the protocol of one addon, "alpha"; what its ways remember and do is kept for the run.
addon = {"on": False, "remembered": False, "keep": {"since": None}}
# --requests: kids' requests, by kind|key (see kid_requests_answer).
requests_state = {"on": False, "next": 4, "items": {}}
BLOCKED_TMDB, OUTSIDE_SERIES_TMDB = 9009, 9002
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
big = {"channels": len(LENGTHS), "categories": None, "legacy": False}
# The AI topics (code, name) a big playlist's channels get, as the server's categories (ids ai:<code>).
AI_TOPICS = [("news", "News"), ("sport", "Sport"), ("movies", "Movies"), ("kids", "Kids"), ("music", "Music"), ("documentary", "Documentary")]
# --live-hls: the directory of an HLS stream (index.m3u8 and its segments) every channel plays; None: channels do not play.
live_hls = [None]
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


# The device's separate history: the person's managed accounts, the one this device writes into
# (None: the profile's own), and the one it had before «Оставить» (switched on again without an account).
history = {"accounts": [{"id": 20, "name": "Living Room", "avatar": "grad-2"}], "next": 21, "current": None, "kept": None}


def history_ref():
    found = next((a for a in history["accounts"] if a["id"] == history["current"]), None)
    return {"id": found["id"], "name": found["name"]} if found else None


def managed_list():
    device = {"id": 1, "kind": "native", "name": "Android TV", "model": "Emulator"}
    return {"items": [{**a, "devices": [device] if a["id"] == history["current"] else []} for a in history["accounts"]]}


def switch_history(body):
    """`PUT /api/me/devices/current/history` {separate, accountId} or {separate: false, merge}."""
    if body.get("separate"):
        wanted = body.get("accountId") or history["kept"]
        if wanted and not any(a["id"] == wanted for a in history["accounts"]):
            return 404, {"error": "No such history"}
        if not wanted:
            wanted = new_account("Android TV")["id"]
        history["current"] = wanted
    else:
        was, history["current"] = history["current"], None
        # merged: the account goes (no other device writes into it here); kept: it comes back when switched on
        if body.get("merge") and was:
            history["accounts"] = [a for a in history["accounts"] if a["id"] != was]
            history["kept"] = None
        else:
            history["kept"] = was
    return 200, {"id": 1, "history": history_ref()}


def new_account(name):
    account = {"id": history["next"], "name": name, "avatar": "grad-4"}
    history["next"] += 1
    history["accounts"].append(account)
    return account


def tracked_cards():
    """«Вышло новое» as the server sends it: a followed title in the library is its own card; one off the library is
    the TMDB title with id 0 (the apps cannot open it), so two of them share kind and id."""
    off = [{"id": 0, "kind": "series", "title": title, "subtitle": "S2 · E1", "poster": "", "tmdbKind": "series",
            "tmdbId": tmdb, "season": 2, "episode": 1} for title, tmdb in [("Far Away Show", 9001), ("Another Far Show", 9002)]]
    return [series_card()] + off + [{"id": 0, "kind": "movie", "title": "Far Away Film", "tmdbKind": "movie", "tmdbId": 9003}]


def home():
    # Another history has another Continue Watching: what this device watched on its own.
    resume = [movie_card(MOVIE + 10, progress=0.6)] if history["current"] else [episode_card(EPISODES[1], 1, 2, progress=0.4), movie_card(MOVIE, progress=0.3)]
    return {"hero": [series_card()], "shelves": [
        {"id": "continue", "title": "Continue Watching", "cards": resume},
        {"id": "tracked-new", "title": "New Releases", "cards": tracked_cards()},
        {"id": "recent-series", "title": "New Series", "cards": [series_card()]},
        {"id": "recent-movies", "title": "New Movies", "cards": [movie_card(MOVIE + i) for i in range(6)]}]}


def item(i):
    if i == SERIES:
        seasons = [{"id": SERIES + s, "number": s, "title": f"Season {s}",
                    "episodes": [episode_card(e, s, n + 1) for n, e in enumerate(EPISODES[(s - 1) * 4:s * 4])]} for s in (1, 2)]
        return {**series_card(), "overview": "A test series about testing things on a television.", "genres": ["Drama", "Comedy"],
                "rating": "16+", "cast": CAST, "seasons": seasons, "next": episode_card(EPISODES[1], 1, 2, progress=0.4),
                **({"tmdbId": SERIES_TMDB} if addon["on"] else {})}
    if i in EPISODES:
        return {"id": i, "kind": "episode", "title": "Episode", "seriesId": SERIES, "files": [{"id": i + 1000, "durationMs": 2700000}]}
    return {**movie_card(i), "overview": "A test movie.", "genres": ["Adventure"], "runtime": 104, "cast": CAST,
            "files": [{"id": i + 1000, "durationMs": 6240000, "audio": [], "subs": []}], "actions": {"play": True},
            **({"tmdbId": MOVIE_TMDB + (i - MOVIE)} if addon["on"] else {})}


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
    w, h = (20, 30) if path.endswith("/poster") or "/cast/" in path else (40, 40) if path.endswith("/cover") else (48, 27)
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


def profile_photo():
    """Alex's photo: a smiling face on a sky gradient, nothing a letter avatar could be taken for."""
    n = 96
    rows = []
    for y in range(n):
        row = []
        for x in range(n):
            face = (x - 48) ** 2 + (y - 52) ** 2 <= 30 ** 2
            eye = (x - 37) ** 2 + (y - 44) ** 2 <= 16 or (x - 59) ** 2 + (y - 44) ** 2 <= 16
            mouth = 15 ** 2 <= (x - 48) ** 2 + (y - 52) ** 2 <= 18 ** 2 and y > 58
            row.append(b"\x30\x20\x20\xff" if face and (eye or mouth) else b"\xf4\xc8\x9a\xff" if face
                       else bytes((70 + y, 130 + y // 2, 220, 255)))
        rows.append(row)
    raw = b"".join(b"\0" + b"".join(row) for row in rows)

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", n, n, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")


# --- Music: one album of three generated tracks ---
ARTIST, ALBUM, TRACKS = 910, 900, [901, 902, 903]
ARTIST_NAME, ALBUM_TITLE, ALBUM_YEAR = "Northern Static", "Golden Hours", 2024
RATE, TRACK_SECONDS = 22050, 60
# Per track: title, step between arpeggio notes (s), arpeggio order, and four 4-second chords (MIDI notes).
SONGS = {
    901: ("Morning Light", 0.5, [0, 1, 2, 3, 2, 1, 3, 2],
          [[48, 55, 59, 64], [45, 52, 55, 60], [41, 48, 52, 57], [43, 50, 55, 59]]),
    902: ("Slow Tide", 0.4, [0, 2, 1, 3, 0, 2, 3, 1],
          [[45, 52, 55, 60], [41, 48, 52, 57], [48, 55, 59, 64], [40, 47, 50, 55]]),
    903: ("Paper Lanterns", 0.5, [0, 1, 2, 1, 3, 2, 1, 2],
          [[50, 57, 62, 66], [47, 54, 57, 62], [43, 50, 55, 59], [45, 52, 57, 61]]),
}
starred = set()
wavs, wav_lock = {}, threading.Lock()


def cover(i):
    return f"/api/images/album/{i}/cover"


def album_card():
    return {"id": ALBUM, "kind": "album", "title": ALBUM_TITLE, "subtitle": ARTIST_NAME, "year": ALBUM_YEAR, "poster": cover(ALBUM)}


def artist_card():
    return {"id": ARTIST, "kind": "artist", "title": ARTIST_NAME, "poster": f"/api/images/artist/{ARTIST}/cover"}


def track(i):
    return {"id": i, "title": SONGS[i][0], "artist": ARTIST_NAME, "artistId": ARTIST, "albumId": ALBUM, "album": ALBUM_TITLE,
            "discNo": 1, "trackNo": TRACKS.index(i) + 1, "durationMs": TRACK_SECONDS * 1000, "fileId": i + 8100,
            "lossless": True, "hasLyrics": False, "cover": cover(ALBUM), "starred": i in starred, "cue": False}


def music_home():
    return {"shelves": [{"id": "recent-albums", "title": "Recently Added", "cards": [album_card()]},
                        {"id": "artists", "title": "Artists", "cards": [artist_card()]}]}


def music_page(items, query):
    """A `Page` of the server: the window [offset, offset+limit) of items and their total."""
    offset, limit = int(query.get("offset", ["0"])[0] or 0), int(query.get("limit", ["100"])[0] or 100)
    return {"items": items[offset:offset + limit], "total": len(items)}


def render_chord(notes, step, order, length=4.0, tail=0.8):
    """A chord's pad (two slightly detuned sines a note) with a plucked arpeggio over it, [length]+[tail] seconds long."""
    n, hz = int((length + tail) * RATE), lambda note: 440.0 * 2 ** ((note - 69) / 12) * 2 * math.pi / RATE
    buf = [0.0] * n
    for note in notes:
        w = hz(note)
        for detune in (1.0, 1.004):
            buf = [b + 0.06 * math.sin(w * detune * i) for i, b in enumerate(buf)]
    for k in range(int(length / step)):
        start, w = int(k * step * RATE), hz(notes[order[k % len(order)]] + 12)
        m = min(int(1.8 * RATE), n - start)
        tone = [(math.sin(w * i) + 0.25 * math.sin(2 * w * i)) * math.exp(-3.2 * i / RATE) * min(1.0, i / 80) * 0.22 for i in range(m)]
        buf[start:start + m] = [b + t for b, t in zip(buf[start:start + m], tone)]
    # Swell in over half a second; fade out over the tail, which the next chord's start overlaps.
    return [b * min(1.0, i / (0.5 * RATE)) * min(1.0, (n - i) / (tail * RATE)) for i, b in enumerate(buf)]


def wav(i):
    """The track's WAV (22050 Hz, mono, 16 bit, 60 s), rendered once."""
    with wav_lock:
        if i not in wavs:
            _, step, order, chords = SONGS[i]
            parts, total, length = [render_chord(c, step, order) for c in chords], TRACK_SECONDS * RATE, 4 * RATE
            mix = [0.0] * (total + 2 * length)
            for k in range(TRACK_SECONDS // 4):
                part, at = parts[k % len(parts)], k * length
                mix[at:at + len(part)] = [a + b for a, b in zip(mix[at:at + len(part)], part)]
            mix = mix[:total]
            peak = max(max(mix), -min(mix)) or 1.0
            # Peak at 85 %, a 20 ms fade-in and a 2 s fade-out.
            pcm = array("h", [int(v / peak * 0.85 * 32767 * min(1.0, n / (0.02 * RATE), (total - n) / (2 * RATE))) for n, v in enumerate(mix)])
            if sys.byteorder == "big":
                pcm.byteswap()
            data = pcm.tobytes()
            header = struct.pack("<4sI4s4sIHHIIHH4sI", b"RIFF", 36 + len(data), b"WAVE", b"fmt ", 16, 1, 1, RATE, RATE * 2, 2, 16, b"data", len(data))
            wavs[i] = header + data
        return wavs[i]


def music(path, query):
    """The answer (status, body) of a music route, or None when the path is not one."""
    missing = (404, {"error": "not found"})
    if path == "/api/music/albums":
        return 200, music_page([album_card()], query)
    if path == "/api/music/artists":
        return 200, music_page([artist_card()], query)
    if path == "/api/music/songs":
        return 200, music_page([track(i) for i in TRACKS], query)
    if path == "/api/music/shuffle":
        return 200, {"tracks": [track(i) for i in TRACKS]}
    if path == "/api/music/playlists":
        return 200, {"items": []}
    if path == "/api/music/plays":
        return 204, b""
    if match := re.fullmatch(r"/api/music/albums/(\d+)", path):
        if int(match[1]) != ALBUM:
            return missing
        return 200, {"card": album_card(), "artist": {"id": ARTIST, "name": ARTIST_NAME}, "year": ALBUM_YEAR, "genres": ["Ambient"],
                     "durationMs": TRACK_SECONDS * 1000 * len(TRACKS), "trackCount": len(TRACKS), "quality": "WAV 16-bit",
                     "tracks": [track(i) for i in TRACKS], "starred": ALBUM in starred}
    if match := re.fullmatch(r"/api/music/artists/(\d+)", path):
        if int(match[1]) != ARTIST:
            return missing
        return 200, {"card": artist_card(), "albums": [album_card()], "topTracks": [track(i) for i in TRACKS], "appearsOn": []}
    if match := re.fullmatch(r"/api/music/tracks/(\d+)(/lyrics)?", path):
        if int(match[1]) not in TRACKS:
            return missing
        return 200, ({"lyrics": "", "synced": False, "source": "", "lines": []} if match[2] else track(int(match[1])))
    return None


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


def category_ids(number, category):
    """The ids of a channel's categories as the server sends them (`categories`): AI topics, a source, its group."""
    ids = []
    if big["categories"]:
        # A big playlist: one or two AI topics, and one of two sources (first and second half of the numbers).
        ids.append("ai:" + AI_TOPICS[number % len(AI_TOPICS)][0])
        if number % 4 == 0:
            ids.append("ai:" + AI_TOPICS[(number + 1) % len(AI_TOPICS)][0])
        ids.append("source:1" if number <= big["channels"] // 2 else "source:2")
    if category:
        ids.append("group:" + category)
    return ids


def channel(number):
    # Channels 2 and 5 have an uploaded logo (transparent), as the server names it.
    logo = {"logoUrl": f"/api/channels/{number}/logo.png?v=1"} if number in (2, 5) else {}
    names = big["categories"]
    if names:
        # A tenth of the channels has no group, as in playlists that do not set one.
        category = "" if number % 10 == 0 else names[(number - 1) * len(names) // big["channels"]]
    else:
        category = "Films" if number % 2 else "Series"
    # Without --old-categories the server's `categories` (left out for a channel in none, as the server does).
    ids = [] if big["legacy"] else category_ids(number, category)
    return {"id": number, "number": number, "name": f"Channel {number}", "category": category, "favorite": number in favorites,
            **({"categories": ids} if ids else {}), **logo}


def category_list():
    """The top-level `categories` of `GET /api/channels`: every category once, topics by count, sources, groups by count then name."""
    counts, names, kinds = {}, {}, {}
    for number in range(1, big["channels"] + 1):
        for cid in channel(number).get("categories", []):
            counts[cid] = counts.get(cid, 0) + 1
            kind, _, key = cid.partition(":")
            kinds[cid] = kind
            names[cid] = dict(AI_TOPICS)[key] if kind == "ai" else "Playlist " + "AB"[int(key) - 1] if kind == "source" else key
    rank = {"ai": 0, "source": 1, "group": 2}
    ids = sorted(counts, key=lambda c: (rank[kinds[c]], (-counts[c], names[c]) if kinds[c] != "source" else (0, c)))
    return [{"id": c, "name": names[c], "kind": kinds[c], "count": counts[c]} for c in ids]


def channels(now_ms):
    out = []
    for number in range(1, big["channels"] + 1):
        on_air = programs(number, now_ms, now_ms + 6 * 60 * MIN)
        out.append({**channel(number), "personal": False, "auto": True, "enabled": True, "now": on_air[0], "next": on_air[1]})
    return {"channels": out, "lastChannel": last_channel[0], **({} if big["legacy"] else {"categories": category_list()})}


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


def addon_answer(command, path, query, body):
    """`x/app` of the addon "alpha", `GET /api/title` and the input player: (status, body) or None for another path."""
    if path == "/api/addons/web":
        return 200, [{"id": "alpha", "entry": "/api/addons/alpha/web/index.js"}]
    if path == "/api/addons/alpha/x/app":
        return 200, {"v": 1, "ways": [
            {"id": "watch", "label": "Watch from a source", "icon": "globe", "role": "watch", "kinds": ["movie", "series"], "scope": "any", "episodes": True},
            {"id": "keep", "label": "Keep on the server", "icon": "download", "role": "keep", "kinds": ["movie", "series"], "scope": "outside", "episodes": False}],
            "search": {"label": "Find and watch"}}
    if path == "/api/addons/alpha/x/app/search":
        return 200, {"titles": [
            {"kind": "movie", "tmdbId": MOVIE_TMDB, "title": "Movie 1", "year": 2019, "poster": f"/api/images/local/{MOVIE}/poster", "inLibrary": True, "libraryItemId": MOVIE},
            {"kind": "movie", "tmdbId": OUTSIDE_TMDB, "title": "Far Away Film", "year": 2020, "poster": "/api/images/local/outside/poster", "inLibrary": False}]}
    if path == "/api/addons/alpha/x/app/step":
        ref = body.get("ref")
        if ref is None:
            return 200, {"title": "Source", "note": "Pick a source", "rows": [
                {"ref": "src-1", "label": "Source One", "detail": "Fast", "badges": ["HD", "Audio RU EN", "Subtitles"], "current": True, "final": False},
                {"ref": "src-2", "label": "Source Two", "final": False}]}
        return 200, {"title": "Quality", "rows": [
            {"ref": "q-1080", "label": "1080p", "detail": "12.3 GB", "badges": ["4K"], "final": True},
            {"ref": "q-720", "label": "720p", "final": True}]}
    if path == "/api/addons/alpha/x/app/run":
        if body.get("way") == "keep":
            addon["keep"]["since"] = datetime.now(timezone.utc).timestamp()
            return 200, {"done": "Started"}
        if body.get("quick") and not addon["remembered"]:
            return 200, {"pick": True}
        addon["remembered"] = True
        return 200, {"input": "in-1"}
    if path == "/api/addons/alpha/x/app/state":
        since = addon["keep"]["since"]
        if since is None:
            return 200, {"ways": {}}
        fraction = min((datetime.now(timezone.utc).timestamp() - since) / 60, 1)
        if fraction >= 1:
            return 200, {"ways": {"keep": {"active": False}}}
        return 200, {"ways": {"keep": {"active": True, "fraction": fraction, "text": f"Saved {int(fraction * 100)}%", "cancel": True}}}
    if path == "/api/addons/alpha/x/app/cancel":
        addon["keep"]["since"] = None
        return 200, {"done": "Cancelled"}
    if match := re.fullmatch(r"/api/title/(movie|series)/(\d+)", path):
        tmdb = int(match[2])
        in_library = tmdb == MOVIE_TMDB
        return 200, {"tmdbId": tmdb, "extId": "", "kind": match[1], "title": "Far Away " + ("Film" if match[1] == "movie" else "Show"), "year": 2020,
                     "poster": "/api/images/local/outside/poster", "overview": "A title the library does not have.", "inLibrary": in_library,
                     "libraryItemId": MOVIE if in_library else None, "backdrop": "/api/images/local/outside/backdrop",
                     "genres": ["Drama"], "runtime": 95, "cast": [], "seasons": [{"number": 1, "episodes": 3}]}
    if path == "/api/play/input/in-1" and command == "GET":
        return 200, {"id": "in-1", "addonId": "alpha", "name": "Movie.mkv", "itemId": 0, "slow": False, "startSec": 0}
    if path == "/api/play/input/in-1" and command == "POST":
        return 200, {"mode": "direct", "url": "/api/play/input/in-1/direct", "startSec": 0, "durationMs": 30000, "audio": [], "audioIndex": -1,
                     "subtitles": [], "quality": {"selected": "auto", "applied": "auto"}}
    if path == "/api/play/input/in-1/progress":
        return 204, b""
    if path == "/api/play/input/in-1/direct":
        return 200, CLIP.read_bytes()
    if path == "/api/auth/media-key":
        return 200, {"key": "fake-media-key", "expiresAt": "2099-01-01T00:00:00Z"}
    if path == "/api/search":
        return 200, {"library": [movie_card(MOVIE)], "albums": [], "artists": [], "songs": []}
    return None


def request_json(r, admin=False):
    video = r["kind"] in ("movie", "series")
    return {"id": r["id"], "key": r["key"], "kind": r["kind"], "tmdbId": r.get("tmdbId", 0), "extId": r.get("extId", ""), "title": r["title"],
            "subtitle": r.get("subtitle", ""), "year": 2020, "poster": "/api/images/local/outside/poster" if video else "", "aliases": [],
            "rating": "6+" if video else "", "status": r["status"], "reason": r.get("reason", ""), "libraryItemId": None,
            "createdAt": r["createdAt"], **({"user": {"id": 2, "name": "Sam", "avatar": "grad-5"}} if admin else {})}


def sample_requests():
    now = datetime.now(timezone.utc)
    for i, (kind, tmdb, title, status, reason, ago) in enumerate([
            ("movie", 9001, "Far Away Film", "pending", "", 12),
            ("series", 9002, "Far Away Show", "approved", "", 300),
            ("movie", 9003, "Another Far Film", "declined", "Too scary for now", 1500)], start=1):
        requests_state["items"][f"{kind}|tmdb:{tmdb}"] = {
            "id": i, "kind": kind, "key": f"tmdb:{tmdb}", "tmdbId": tmdb, "title": title, "status": status, "reason": reason,
            "createdAt": (now - timedelta(minutes=ago)).strftime("%Y-%m-%dT%H:%M:%SZ")}


def candidate_json(kind, tmdb, title, subtitle="", ext_id=""):
    key = f"tmdb:{tmdb}" if kind in ("movie", "series") else f"ext:{ext_id}"
    made = requests_state["items"].get(f"{kind}|{key}")
    return {"kind": kind, "tmdbId": tmdb, "extId": ext_id, "title": title, "subtitle": subtitle, "year": 2020,
            "poster": "/api/images/local/outside/poster" if kind in ("movie", "series") else "", "explicit": False, "aliases": [],
            "kidRequest": {"key": key, "allowed": True, "request": request_json(made) if made else None}}


def kid_requests_answer(command, path, query, body, kid):
    """The `/api/requests/*` endpoints and the kid's title page (`GET /api/title`): (status, body) or None for another path."""
    if path.startswith("/api/title/") and kid:
        match = re.fullmatch(r"/api/title/(movie|series)/(\d+)", path)
        if not match:
            return None
        tmdb, blocked = int(match[2]), int(match[2]) == BLOCKED_TMDB
        made = requests_state["items"].get(f"{match[1]}|tmdb:{tmdb}")
        return 200, {"tmdbId": tmdb, "extId": "", "kind": match[1], "title": "Far Away " + ("Film" if match[1] == "movie" else "Show"), "year": 2020,
                     "poster": "/api/images/local/outside/poster", "overview": "A title the library does not have.", "inLibrary": False,
                     "libraryItemId": None, "backdrop": "/api/images/local/outside/backdrop", "genres": ["Drama"], "runtime": 95, "cast": [],
                     "seasons": [{"number": 1, "episodes": 3}],
                     "kidRequest": {"key": f"tmdb:{tmdb}", "allowed": not blocked, "blocked": "This is 16+, and this profile allows up to 6+" if blocked else None,
                                    "request": request_json(made) if made else None}}
    if not path.startswith("/api/requests"):
        return None
    items = requests_state["items"]
    if path == "/api/requests/search" and command == "GET":
        if not kid:
            return 403, {"error": "Only kid profiles can ask"}
        kind = query.get("kind", [""])[0]
        if kind == "video":
            return 200, [candidate_json("movie", 9001, "Far Away Film"), candidate_json("series", OUTSIDE_SERIES_TMDB, "Far Away Show"),
                         candidate_json("movie", 9003, "Another Far Film"), candidate_json("movie", BLOCKED_TMDB, "Grown-up Film")]
        if kind == "music":
            return 200, [candidate_json("music", 0, "Test Album", "Test Artist", "mb-1")]
        return 200, []
    if path == "/api/requests/mine" and command == "GET":
        return 200, [request_json(r) for r in sorted(items.values(), key=lambda r: -r["id"])]
    if path == "/api/requests" and command == "POST":
        if not kid:
            return 403, {"error": "Only kid profiles can ask"}
        kind, tmdb = body.get("kind", ""), int(body.get("tmdbId") or 0)
        if tmdb == BLOCKED_TMDB:
            return 403, {"error": "This is 16+, and this profile allows up to 6+", "code": "rating"}
        key = f"tmdb:{tmdb}" if kind in ("movie", "series") else f"ext:{body.get('extId', '')}"
        if f"{kind}|{key}" not in items:
            items[f"{kind}|{key}"] = {"id": requests_state["next"], "kind": kind, "key": key, "tmdbId": tmdb, "extId": body.get("extId", ""),
                                      "title": body.get("title", ""), "subtitle": body.get("subtitle", ""), "status": "pending",
                                      "createdAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")}
            requests_state["next"] += 1
        return 200, request_json(items[f"{kind}|{key}"])
    if kid:
        return 403, {"error": "Only an administrator can do this"}
    if path == "/api/requests" and command == "GET":
        return 200, [request_json(r, admin=True) for r in sorted(items.values(), key=lambda r: (r["status"] != "pending", -r["id"]))]
    if path == "/api/requests/count":
        return 200, {"pending": sum(r["status"] == "pending" for r in items.values())}
    if match := re.fullmatch(r"/api/requests/(\d+)/(approve|decline)", path):
        found = next((r for r in items.values() if r["id"] == int(match[1])), None)
        if found is None:
            return 404, {"error": "Request not found"}
        if found["status"] != "pending":
            return 409, {"error": "Already answered"}
        found["status"] = "approved" if match[2] == "approve" else "declined"
        found["reason"] = str(body.get("reason", "")) if match[2] == "decline" else ""
        return 200, request_json(found, admin=True)
    return None


IMAGE_CACHE = [("Cache-Control", "private, max-age=604800")]


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    embed_key = "aqz-KE-bpKQ"

    def send(self, status, body, content_type="application/json", headers=()):
        data = body if isinstance(body, bytes) else json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        for name, value in headers:
            self.send_header(name, value)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(data)

    def send_track(self, track_id):
        """The track's WAV with Range support (`bytes=a-b`, `a-`, `-n`), as the player seeks."""
        data = wav(track_id)
        size, headers, status, start, end = len(data), [("Accept-Ranges", "bytes")], 200, 0, len(data) - 1
        asked = re.fullmatch(r"bytes=(\d*)-(\d*)", (self.headers.get("Range") or "").strip())
        if asked and (asked[1] or asked[2]):
            start = int(asked[1]) if asked[1] else max(size - int(asked[2]), 0)
            end = min(int(asked[2]), size - 1) if asked[1] and asked[2] else size - 1
            if start >= size:
                return self.send(416, b"", "audio/wav", [("Content-Range", f"bytes */{size}")])
            if start <= end:
                status = 206
                headers.append(("Content-Range", f"bytes {start}-{end}/{size}"))
            else:
                start, end = 0, size - 1
        try:
            self.send(status, data[start:end + 1], "audio/wav", headers)
        except (BrokenPipeError, ConnectionResetError):
            self.close_connection = True

    def answer(self):
        url = urlparse(self.path)
        path, query = url.path, parse_qs(url.query)
        now_ms = int(datetime.now(timezone.utc).timestamp() * 1000)
        raw = self.rfile.read(int(self.headers.get("Content-Length") or 0))
        body = json.loads(raw) if raw.strip() else {}
        kid = ME["isKid"]
        if requests_state["on"] and (reply := kid_requests_answer(self.command, path, query, body, kid)) is not None:
            return self.send(*reply)
        if addon["on"] and not kid and (reply := addon_answer(self.command, path, query, body)) is not None:
            status, answer = reply
            return self.send(status, answer, "video/mp4" if isinstance(answer, bytes) and status == 200 else "application/json")
        if path == "/api/me/devices/current/history" and self.command == "PUT":
            return self.send(403, {"error": "Only an administrator can change this"}) if kid else self.send(*switch_history(body))
        if path == "/api/me/managed" and self.command == "POST":
            if kid:
                return self.send(403, {"error": "Only an administrator can change this"})
            name = str(body.get("name", "")).strip()
            if not name:
                return self.send(400, {"error": "Enter a name"})
            return self.send(201, {**new_account(name), "devices": []})
        if path == "/api/me/managed":
            return self.send(200, managed_list())
        if path == "/api/me/devices":
            return self.send(200, {"items": [{"id": 1, "kind": "native", "name": "Android TV", "model": "Emulator", "version": "1.0",
                                              "createdAt": "2026-11-01T10:00:00Z", "lastSeen": "2026-11-19T10:00:00Z", "current": True,
                                              "userId": ME["id"], "userName": ME["name"], "userAvatar": ME["avatar"], "history": history_ref()}]})
        # Artwork is cached for a week, as the server sends it (the apps' image loader honours it).
        if path == "/api/profiles/1/photo":
            return self.send(200, profile_photo(), "image/png", headers=IMAGE_CACHE)
        if path.startswith("/api/images/") or path.endswith("/logo.png"):
            return self.send(200, png(path, sized="w" in query), "image/png", headers=IMAGE_CACHE)
        if path.endswith(".mp4"):
            return self.send(200, CLIP.read_bytes(), "video/mp4")
        if re.fullmatch(r"/api/trailers/movieTrailer1/play", path):
            return self.send(200, {"url": "/api/trailers/movieTrailer1.mp4?mk=test-mk", "durationMs": 30000})
        if match := re.fullmatch(r"/api/tracks/(\d+)/stream", path):
            return self.send_track(int(match[1])) if int(match[1]) in TRACKS else self.send(404, {"error": "not found"})
        if match := re.fullmatch(r"/api/music/star/(\d+)", path):
            (starred.add if self.command == "POST" else starred.discard)(int(match[1]))
            return self.send(204, b"")
        if (reply := music(path, query)) is not None:
            return self.send(*reply)
        if match := re.fullmatch(r"/api/channels/(\d+)/favorite", path):
            (favorites.add if self.command == "PUT" else favorites.discard)(int(match[1]))
            return self.send(204, b"")
        if path in ("/api/channels/last", "/api/channels/opened"):
            return self.send(204, b"")
        # --live-hls: every channel plays that HLS directory (its media key is any key).
        if path == "/api/auth/media-key" and live_hls[0]:
            return self.send(200, {"key": "fake-media-key", "expiresAt": "2099-01-01T00:00:00Z"})
        if (match := re.fullmatch(r"/api/channels/live/\d+/([\w.-]+)", path)) and live_hls[0]:
            file = live_hls[0] / match[1]
            if not file.is_file():
                return self.send(404, {"error": "not found"})
            kind = "application/vnd.apple.mpegurl" if file.suffix == ".m3u8" else "video/mp2t"
            return self.send(200, file.read_bytes(), kind)
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
            "/api/profiles": PROFILES,
            "/api/auth/token": {"token": "test-device-token", "user": {**ME, **({"canRequest": kid, "requests": not kid} if requests_state["on"] else {})}},
            "/api/me": {**ME, "deviceHistory": history_ref(), **({"canRequest": kid, "requests": not kid} if requests_state["on"] else {})},
            "/api/me/ui-language": {"language": "", "effective": "en"},
            "/api/me/playback-prefs": {"audioLangs": [], "subMode": "off", "subLangs": []},
            "/api/home": home(),
            "/api/music/home": music_home(),
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
    args.add_argument("--old-categories", action="store_true", help="answer as a server without `categories` (the apps use each channel's `category` text)")
    args.add_argument("--live-hls", type=Path, metavar="DIR", help="an HLS stream (index.m3u8 and segments) every channel plays, e.g. made with "
                      "ffmpeg -i test-fixtures/resources/trailer-sample.mp4 -c copy -hls_list_size 0 -f hls DIR/index.m3u8")
    args.add_argument("--addons", action="store_true", help="the server has one addon, \"alpha\", speaking the `x/app` protocol (ways to watch and to keep, a title search)")
    args.add_argument("--kid", action="store_true", help="the profile is a kid's (no «Отдельная история»; its writes answer 403)")
    args.add_argument("--requests", action="store_true", help="kids' requests are open (see above): with --kid the profile asks, without it answers")
    options = args.parse_args()
    ME["isKid"] = options.kid
    requests_state["on"] = options.requests
    if options.requests:
        sample_requests()
    addon["on"] = options.addons
    live_hls[0] = options.live_hls
    big["legacy"] = options.old_categories
    if options.channels:
        big["channels"] = options.channels
    if options.categories:
        big["categories"] = [f"{GROUPS[i % len(GROUPS)]}" + (f" {i // len(GROUPS) + 1}" if i >= len(GROUPS) else "") for i in range(options.categories)]
    Handler.embed_key = options.trailer
    rebuild["pages_left"] = options.rebuild_after
    print(f"http://127.0.0.1:{options.port} (in an emulator: http://10.0.2.2:{options.port})", flush=True)
    ThreadingHTTPServer((options.host, options.port), Handler).serve_forever()
