"""Serves the real panel with fabricated data, so the map can be looked at."""
import http.server, json, time, urllib.parse, os, socketserver

SP = os.path.dirname(os.path.abspath(__file__))
NOW = int(time.time() * 1000)
FROM = NOW - 4 * 3600 * 1000
# A three-hour hole in the middle, so the timeline has quiet time to mark.
QUIET = (FROM + 20 * 60 * 1000, NOW - 40 * 60 * 1000)

def when(i, n):
    """Spread over the period, but never inside the quiet stretch."""
    early = n // 4
    if i < early:
        span = QUIET[0] - FROM
        return FROM + int(span * i / max(1, early))
    span = NOW - QUIET[1]
    return QUIET[1] + int(span * (i - early) / max(1, n - early))

def walk(seed, n, x0, z0):
    pts, x, z = [], x0, z0
    for i in range(n):
        x += int(12 * ((seed * (i + 3)) % 7 - 3))
        z += int(12 * ((seed * (i + 5)) % 7 - 3))
        pts.append({"at": when(i, n), "dim": "overworld",
                    "x": x, "y": 70, "z": z})
    return pts

TRACKS = {"Steve": walk(3, 40, -60, -40), "Alex": walk(5, 36, 40, 60),
          "Mika": walk(7, 30, -10, 90)}
# Two of them are still moving; Alex stopped seven minutes ago, so their head
# on the map should be the grey one.
for who in ("Steve", "Mika"):
    tail = dict(TRACKS[who][-1])
    tail["at"] = NOW
    tail["x"] += 4
    TRACKS[who].append(tail)

ACTIONS = []
kinds = ["place", "break", "attack", "hurt", "death", "chat", "command",
         "container", "join", "leave", "respawn", "item", "interact", "use", "afk"]
SAID = ["anyone seen my pickaxe", "brb", "that creeper came out of nowhere",
        "gg", "who put lava in the nether portal", "back"]
for i, k in enumerate(kinds * 4):
    p = TRACKS[list(TRACKS)[i % 3]][(i * 3) % 30]
    ACTIONS.append({"at": p["at"], "player": list(TRACKS)[i % 3], "mask": "",
                    "action": k,
                    "detail": SAID[i % len(SAID)] if k == "chat" else k + " detail",
                    "dim": "overworld",
                    "x": p["x"] + (i % 5) * 6, "y": 70, "z": p["z"] + (i % 4) * 6,
                    "count": 1 + i % 3})

# Twenty marks inside a few blocks: someone mining out a room. Zoomed out
# these become one box; zoomed in they separate.
for i in range(20):
    ACTIONS.append({"at": when(i, 20), "player": "Mika", "mask": "",
                    "action": "break" if i % 4 else "place",
                    "detail": "Deepslate" if i % 4 else "Torch",
                    "dim": "overworld", "x": -12 + (i % 4), "y": 14,
                    "z": 92 + (i // 4), "count": 1 + (i % 6)})

BUILD_AT = NOW - 1800000
for i in range(120):
    # A 10x8 walled box, three high, going up course by course.
    course = i // 36
    k = i % 36
    if k < 10:   bx, bz = -66 + k, -44
    elif k < 18: bx, bz = -57, -44 + (k - 10)
    elif k < 28: bx, bz = -57 - (k - 18), -37
    else:        bx, bz = -66, -37 - (k - 28)
    ACTIONS.append({"at": BUILD_AT + i * 900, "player": "Steve", "mask": "",
                    "action": "place", "detail": "Oak Planks", "dim": "overworld",
                    "x": bx, "y": 71 + course, "z": bz, "count": 1})
for i in range(40):
    ACTIONS.append({"at": BUILD_AT - 300000 + i * 1200, "player": "Steve", "mask": "",
                    "action": "break", "detail": "Grass Block", "dim": "overworld",
                    "x": -66 + (i % 10), "y": 70, "z": -44 + (i // 10), "count": 1})

INSIGHTS = {
  "episodes": [
    {"kind": "shaft", "headline": "Dug a shaft from y 71 down to y 14",
     "player": "Mika", "mask": "", "uuid": "33333333-3333-3333-3333-333333333333",
     "dim": "overworld", "from": FROM, "to": NOW - 600000,
     "x": -12, "y": 42, "z": 92, "events": 57, "weight": 44, "tool": "pickaxe"},
    {"kind": "build", "headline": "Built something 10 across and 3 high, mostly Oak Planks",
     "player": "Steve", "mask": "", "uuid": "11111111-1111-1111-1111-111111111111",
     "dim": "overworld", "from": BUILD_AT - 300000, "to": BUILD_AT + 120 * 900,
     "x": -61, "y": 71, "z": -40, "events": 160, "weight": 60, "tool": "hammer"},
    {"kind": "pace", "headline": "Back and forth around 90,12 \u2014 620 blocks walked "
                                 "without leaving 18 blocks, over 7 minutes",
     "player": "Alex", "mask": "Ghost", "uuid": "22222222-2222-2222-2222-222222222222",
     "dim": "overworld", "from": NOW - 900000, "to": NOW - 60000,
     "x": 90, "y": 70, "z": 12, "events": 24, "weight": 42, "tool": "loop"},
    {"kind": "hazard", "headline": "Placed 6 lava, fire or TNT blocks around -40,-10 at y 71",
     "player": "Alex", "mask": "Ghost", "uuid": "22222222-2222-2222-2222-222222222222",
     "dim": "overworld", "from": NOW - 1500000, "to": NOW - 1400000,
     "x": -40, "y": 71, "z": -10, "events": 9, "weight": 95, "tool": "flame"},
    {"kind": "grind", "headline": "Killed 40 mobs, mostly Zombie, all on one spot",
     "player": "Mika", "mask": "", "uuid": "33333333-3333-3333-3333-333333333333",
     "dim": "overworld", "from": NOW - 1200000, "to": NOW - 900000,
     "x": 30, "y": 30, "z": 70, "events": 40, "weight": 48, "tool": "sword"},
    {"kind": "sign", "headline": "Wrote on a sign \u2014 \u201ckeep out / this means you\u201d",
     "player": "Steve", "mask": "", "uuid": "11111111-1111-1111-1111-111111111111",
     "dim": "overworld", "from": NOW - 800000, "to": NOW - 790000,
     "x": -55, "y": 71, "z": -20, "events": 5, "weight": 44, "tool": "signpost"},
    {"kind": "tree", "headline": "Chopped down about 4 trees",
     "player": "Steve", "mask": "", "uuid": "11111111-1111-1111-1111-111111111111",
     "dim": "overworld", "from": NOW - 2400000, "to": NOW - 2000000,
     "x": 20, "y": 71, "z": -12, "events": 31, "weight": 34, "tool": "axe"}],
  "ai": {"enabled": True, "provider": "local", "model": "qwen2.5:3b",
         "baseUrl": "http://127.0.0.1:11434/v1", "sendChat": True, "autoMinutes": 0,
         "hasKey": False, "problem": ""},
  "report": {"generated": NOW - 90000, "model": "qwen2.5:3b", "provider": "local", "error": "",
             "summary": "A quiet evening with three people on. Steve spent most of it "
                        "building near spawn and clearing the trees behind it, while Mika "
                        "sank a shaft under the eastern hill and has been mining out a room "
                        "at y 14. Alex has been away at the portal for the last few minutes.",
             "moments": [
               {"at": NOW - 600000, "label": "A room being hollowed out at y 14",
                "why": "Mika broke through most of a 4x5 area in one stretch.",
                "player": "Mika", "dim": "overworld", "x": -12, "y": 14, "z": 92,
                "weight": 60},
               {"at": NOW - 1800000, "label": "The build near spawn grew a floor",
                "why": "Steve laid planks across the whole footprint in one go.",
                "player": "Steve", "dim": "overworld", "x": -60, "y": 71, "z": -40,
                "weight": 40}],
             "patterns": [
               {"from": NOW - 3 * 3600 * 1000, "to": NOW - 600000, "player": "Mika",
                "label": "Goes back to the same hill every evening",
                "why": "Three trips to within ten blocks of -12,92 at about the same hour."},
               {"from": NOW - 2400000, "to": NOW - 600000, "player": "",
                "label": "Steve and Mika stop working when Alex logs in",
                "why": "Both go quiet within a minute of Alex joining, twice."}],
             "sequences": [
               {"at": NOW - 600000, "player": "Mika",
                "means": "Getting down to the ore layer under the eastern hill."}]}}


# Alex logged off eight minutes ago and has not moved since, so their face on
# the map should be the small grey one with the left-here tag.
ALEX_LEFT = NOW - 500000
TRACKS["Alex"] = [q for q in TRACKS["Alex"] if q["at"] <= ALEX_LEFT - 60000]
ACTIONS[:] = [a for a in ACTIONS
              if not (a["player"] == "Alex" and a["action"] in ("join", "leave")
                      and a["at"] > ALEX_LEFT - 120000)]
ACTIONS.append({"at": ALEX_LEFT, "player": "Alex", "mask": "Notch", "action": "leave",
                "detail": "", "dim": "overworld",
                "x": TRACKS["Alex"][-1]["x"], "y": 70, "z": TRACKS["Alex"][-1]["z"],
                "count": 1})

SHOTS = [{"at": FROM + int((NOW - FROM) * f), "dim": "overworld",
          "minX": mx, "minZ": mz, "span": 384}
         for f, mx, mz in ((0.05, -200, -200), (0.30, -200, -200),
                           (0.55, 128, -200), (0.80, -200, 128), (0.95, -200, -200))]
# Somewhere with a picture of it but nobody in it, so the switcher has two.
SHOTS.append({"at": NOW - 3600000, "dim": "the_nether",
              "minX": -192, "minZ": -192, "span": 384})

ADMINS = {"ok": True, "includeAdmins": False, "temporary": False, "configured": False}

SHOT_SPAN = 384
UUIDS = {"Steve": "11111111-1111-1111-1111-111111111111",
         "Alex":  "22222222-2222-2222-2222-222222222222",
         "Mika":  "33333333-3333-3333-3333-333333333333",
         "NoSkin": "44444444-4444-4444-4444-444444444444"}
HEADS = {UUIDS["Steve"]: "steve.png", UUIDS["Alex"]: "alex.png",
         UUIDS["Mika"]: "mika.png"}

MODS = [
  {"id": "sodium", "name": "Sodium", "version": "0.6.13", "url": "", "file": "sodium-0.6.13.jar",
   "sha256": "9f2c" * 16, "required": True, "kind": "jar", "source": "modrinth",
   "page": "https://modrinth.com/mod/sodium", "icon": True},
  {"id": "modmenu", "name": "Mod Menu", "version": "13.0.2", "url": "", "file": "modmenu-13.0.2.jar",
   "sha256": "", "required": False, "kind": "jar", "source": "modrinth",
   "page": "https://modrinth.com/mod/modmenu", "icon": True},
  {"id": "customthing", "name": "Custom Thing", "version": "2.1", "file": "",
   "url": "https://files.example.test/customthing-2.1.jar", "sha256": "", "required": False,
   "kind": "link", "source": "link", "page": "", "icon": False},
]

FILES = {
  "": [("config", True, -1, 4), ("logs", True, -1, 22), ("mods", True, -1, 7),
       ("world", True, -1, 9), ("eula.txt", False, 173, -1),
       ("server.jar", False, 52428800, -1), ("server.properties", False, 1381, -1),
       ("start.sh", False, 214, -1), ("whitelist.json", False, 2, -1)],
  "mods": [("almin-2.14.0-server.jar", False, 412000, -1),
           ("sodium-0.6.13.jar", False, 921344, -1),
           ("lithium-0.14.3.jar", False, 388211, -1)],
  "config": [("almin", True, -1, 5), ("fabric", True, -1, 2),
             ("sodium-options.json", False, 2411, -1)],
}
WRITABLE = {"mods", "config", "resourcepacks", "shared"}

class H(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def _json(self, obj):
        b = json.dumps(obj).encode()
        self.send_response(200); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)
    def _png(self, path):
        b = open(path, "rb").read()
        self.send_response(200); self.send_header("Content-Type", "image/png")
        self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)
    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length)
        p = urllib.parse.urlparse(self.path).path
        if p == "/api/insights":
            self._json(INSIGHTS); return
        if p == "/api/insights/find":
            self._json({"question": "lava near spawn", "error": "",
                        "reply": "Filtered to lava and fire going down near spawn.",
                        "players": ["Steve"], "actions": ["place", "use"],
                        "items": ["Lava Bucket"], "kinds": ["hazard"], "episodes": []})
            return
        if p == "/api/client/review":
            self._json({"generated": NOW, "error": "",
                        "summary": "Mostly performance and graphics mods, plus a minimap. "
                                   "One entry I would look at.",
                        "flags": [
                          {"id": "xaerominimap", "level": "watch",
                           "why": "A minimap. Many servers allow it; some do not."},
                          {"id": "lithium", "level": "fine",
                           "why": "Server-logic optimisation."}]})
            return
        self._json({"ok": True})
    def do_GET(self):
        u = urllib.parse.urlparse(self.path)
        q = urllib.parse.parse_qs(u.query)
        p = u.path
        if p == "/":
            b = open(os.path.join(SP, "panel.html"), "rb").read()
            self.send_response(200); self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)
        elif p == "/glyphs":
            b = open(os.path.join(SP, "glyphs.html"), "rb").read()
            self.send_response(200); self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)
        elif p == "/api/session":
            self._json({"authed": True, "secure": True, "encrypted": True, "passwordSet": True,
                        "publicMetrics": True, "serverRunning": True, "supervisor": False,
                        "version": "2.15.0", "canStart": True, "restarting": False,
                        "heads": True,
                        "startCommand": "java -jar server.jar nogui"})
        elif p == "/api/state":
            self._json({"rows": [], "metrics": {"tps": 20, "tpsTarget": 20, "mspt": 3.1,
                        "players": 3, "maxPlayers": 20, "memUsed": "1 GB", "memMax": "4 GB",
                        "memPct": 25, "uptime": "2h", "chunks": 400, "entities": 80},
                        "serverRunning": True, "generated": int(time.time() * 1000)})
        elif p == "/api/activity":
            rows = [dict(a, where="overworld %d,%d,%d" % (a["x"], a["y"], a["z"]),
                         uuid=UUIDS.get(a["player"], ""))
                    for a in ACTIONS[:40]]
            self._json({"rows": rows, "total": len(ACTIONS), "enabled": True, "blocks": True,
                        "retentionMinutes": 1440, "admins": ADMINS})
        elif p == "/api/track":
            if q.get("all"):
                self._json({"players": {k: len(v) for k, v in TRACKS.items()},
                            "trackSeconds": 5, "all": True, "tracks": TRACKS,
                            "ids": {n: UUIDS[n] for n in TRACKS},
                            "actions": ACTIONS, "from": FROM, "to": NOW,
                            "now": int(time.time() * 1000),
                            "afkSeconds": 20, "admins": ADMINS,
                            "online": [
                              {"name": "Steve", "uuid": UUIDS["Steve"], "afk": False,
                               "stillSince": NOW - 4000, "mask": "",
                               "dim": "overworld", "x": 10, "y": 70, "z": 20},
                              {"name": "Alex", "uuid": UUIDS["Alex"], "afk": True,
                               "stillSince": NOW - 420000, "mask": "Notch",
                               "dim": "overworld", "x": 90, "y": 70, "z": 12},
                              {"name": "Mika", "uuid": UUIDS["Mika"], "afk": False,
                               "stillSince": NOW - 1000, "mask": "",
                               "dim": "overworld", "x": -30, "y": 70, "z": 60}]})
            else:
                self._json({"players": {k: len(v) for k, v in TRACKS.items()},
                            "trackSeconds": 5, "player": "", "points": [], "actions": []})
        elif p == "/api/client":
            uid = (q.get("uuid") or [""])[0]
            if uid == UUIDS["Mika"]:
                self._json({"enabled": True, "known": False, "historyDays": 7}); return
            self._json({"enabled": True, "known": True, "historyDays": 7,
                "name": "Steve", "at": NOW - 900000,
                "minecraft": "1.21.9", "loader": "fabric 0.19.4",
                "launcher": "minecraft-launcher 2.3.1",
                "os": "Mac OS X", "osVersion": "15.3.1", "arch": "aarch64",
                "java": "21.0.5", "cores": 10, "memoryMb": 4096,
                "mods": [
                  {"id": "almin", "version": "2.22.0", "firstSeen": NOW - 86400000 * 9,
                   "removedAt": 0, "restricted": False},
                  {"id": "fabric-api", "version": "0.116.0", "firstSeen": NOW - 86400000 * 9,
                   "removedAt": 0, "parent": "", "restricted": False},
                  {"id": "fabric-networking-api-v1", "version": "4.4.2",
                   "firstSeen": NOW - 86400000 * 9, "removedAt": 0,
                   "parent": "fabric-api", "restricted": False},
                  {"id": "fabric-rendering-v1", "version": "6.0.1",
                   "firstSeen": NOW - 86400000 * 9, "removedAt": 0,
                   "parent": "fabric-api", "restricted": False},
                  {"id": "fabric-item-api-v1", "version": "11.2.0",
                   "firstSeen": NOW - 86400000 * 9, "removedAt": 0,
                   "parent": "fabric-api", "restricted": False},
                  {"id": "lithium", "version": "0.14.3", "firstSeen": NOW - 86400000 * 9,
                   "removedAt": 0, "restricted": False},
                  {"id": "sodium", "version": "0.6.13", "firstSeen": NOW - 900000,
                   "removedAt": 0, "restricted": False},
                  {"id": "xaerominimap", "version": "24.2.0", "firstSeen": NOW - 900000,
                   "removedAt": 0, "restricted": True}],
                "removed": [
                  {"id": "iris", "version": "1.8.1", "firstSeen": NOW - 86400000 * 9,
                   "removedAt": NOW - 86400000 * 2, "restricted": False}]})
        elif p == "/api/blocks":
            self._json({"textures": "minecraft-merged.jar", "blocks": {
                "Oak Planks": "#a68a55", "Grass Block": "#7fb238", "Stone": "#7a7a7a",
                "Deepslate": "#4d4d51", "Torch": "#e0c060", "Dirt": "#976d4d",
                "Sand": "#dbcf9a", "Oak Log": "#9a814d", "place detail": "#8a929c"}})
        elif p == "/api/item":
            import zipfile
            name = (q.get("name") or [""])[0]
            jar = "/Users/alex/.gradle/caches/fabric-loom/26.2/minecraft-merged.jar"
            try:
                with zipfile.ZipFile(jar) as z:
                    b = z.read("assets/minecraft/textures/item/" + name + ".png")
            except Exception:
                self.send_response(404); self.send_header("Content-Length", "0")
                self.end_headers(); return
            self.send_response(200); self.send_header("Content-Type", "image/png")
            self.send_header("Content-Length", str(len(b))); self.end_headers()
            self.wfile.write(b)
        elif p == "/api/properties":
            self._json({"file": "server.properties", "rows": [
                {"key": "gamemode", "value": "survival", "type": "TEXT", "secret": False},
                {"key": "difficulty", "value": "normal", "type": "TEXT", "secret": False},
                {"key": "view-distance", "value": "10", "type": "INT", "secret": False},
                {"key": "simulation-distance", "value": "10", "type": "INT", "secret": False},
                {"key": "max-players", "value": "20", "type": "INT", "secret": False},
                {"key": "white-list", "value": "false", "type": "BOOL", "secret": False},
                {"key": "pvp", "value": "true", "type": "BOOL", "secret": False},
                {"key": "motd", "value": "A Minecraft Server", "type": "TEXT", "secret": False},
                {"key": "level-name", "value": "world", "type": "TEXT", "secret": False},
                {"key": "rcon.password", "value": "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022",
                 "type": "TEXT", "secret": True},
                {"key": "enable-command-block", "value": "false", "type": "BOOL", "secret": False}]})
        elif p == "/api/insights":
            self._json(INSIGHTS)
        elif p == "/api/servermods":
            self._json({"folder": "mods/", "maxBytes": 33554432, "mods": [
                {"file": "almin-2.25.0.jar", "id": "almin", "name": "Almin",
                 "version": "2.25.0", "bytes": 406311, "modified": NOW,
                 "loaded": True, "enabled": True, "ours": True},
                {"file": "fabric-api-0.116.0.jar", "id": "fabric-api",
                 "name": "Fabric API", "version": "0.116.0", "bytes": 2100000,
                 "modified": NOW, "loaded": True, "enabled": True, "ours": False},
                {"file": "carpet-1.4.163.jar", "id": "carpet", "name": "Carpet",
                 "version": "1.4.163", "bytes": 900000, "modified": NOW,
                 "loaded": False, "enabled": True, "ours": False},
                {"file": "ledger-1.3.6.jar.disabled", "id": "ledger", "name": "Ledger",
                 "version": "1.3.6", "bytes": 800000, "modified": NOW,
                 "loaded": False, "enabled": False, "ours": False}]})
        elif p == "/api/map":
            if not q.get("at"):
                self._json({"shots": SHOTS, "every": 30})
            elif q.get("height"):
                b = open(os.path.join(SP, "assets", "terrain-heights.png"), "rb").read()
                self.send_response(200); self.send_header("Content-Type", "image/png")
                self.send_header("Content-Length", str(len(b))); self.end_headers()
                self.wfile.write(b); return
            else:
                b = open(os.path.join(SP, "assets", "terrain-textured.png"), "rb").read()
                self.send_response(200); self.send_header("Content-Type", "image/png")
                self.send_header("Content-Length", str(len(b))); self.end_headers()
                self.wfile.write(b)
        elif p == "/api/head":
            uid = (q.get("uuid") or [""])[0]
            name = HEADS.get(uid)
            if not name:
                self.send_response(404); self.send_header("Content-Length", "0")
                self.end_headers(); return
            self._png(os.path.join(SP, "assets", name))
        elif p == "/api/mods/icon":
            mid = (q.get("id") or [""])[0]
            f = os.path.join(SP, "assets", "icon-" + mid + ".png")
            if not os.path.exists(f):
                self.send_response(404); self.send_header("Content-Length", "0")
                self.end_headers(); return
            self._png(f)
        elif p == "/api/mods":
            self._json({"mods": MODS, "unusedFiles": ["oldmod-1.2.jar"], "maxOffers": 64,
                        "advertise": True, "denyKicks": False, "requireClientMod": True,
                        "restricted": "xaerominimap,litematica", "showRestricted": False,
                        "restrictedKick": False})
        elif p == "/api/mods/files":
            self._json({"files": [m["file"] for m in MODS if m["file"]] + ["oldmod-1.2.jar"],
                        "maxBytes": 33554432})
        elif p == "/api/files":
            rel = (q.get("path") or [""])[0]
            rows = FILES.get(rel)
            if rows is None:
                self._json({"error": "No such path: " + rel}); return
            top = rel.split("/")[0] if rel else ""
            writable = top in WRITABLE
            self._json({"path": rel, "isDir": True, "fileSize": -1, "writable": writable,
                        "roots": "mods,config,resourcepacks,shared",
                        "entries": [{"name": n, "directory": d, "size": sz,
                                     "modified": NOW - (i + 1) * 3_600_000,
                                     "items": it, "writable": writable}
                                    for i, (n, d, sz, it) in enumerate(rows)]})
        elif p == "/api/file":
            self._json({"content": "# " + (q.get("path") or [""])[0] + "\nkey=value\n"})
        elif p == "/api/players":
            self._json({"maxPlayers": 20,
                "online": [{"name": n, "uuid": UUIDS[n], "mask": "" if n != "Alex" else "Notch",
                            "sessionMillis": 900000 + i * 60000,
                            "hasMod": n != "Mika", "reported": n != "Mika"}
                           for i, n in enumerate(["Steve", "Alex", "Mika"])],
                "history": [{"uuid": UUIDS[n], "name": n, "firstSeen": 1,
                             "lastSeen": NOW - i * 86400000, "joins": 4 + i,
                             "playtimeMillis": 7200000 * (i + 1),
                             "mask": "" if n != "Alex" else "Notch",
                             "reported": n == "Steve"}
                            for i, n in enumerate(["Steve", "Alex", "Mika", "NoSkin"])]})
        elif p == "/api/console":
            self._json({"lines": ["[12:00:00] [Server thread/INFO]: Done (3.1s)!",
                                  "[12:00:05] [Server thread/WARN]: Can't keep up!"]})
        elif p == "/api/update":
            self._json({"current": "2.15.0", "repo": "TheMin3s/almin", "status": "current"})
        elif p == "/api/config":
            self._json({"writableRoots": "mods,config,resourcepacks,shared", "keys": [
                {"name": "mods-advertise", "description": "Offer the mods listed in mods.json",
                 "type": "BOOL", "min": 0, "max": 0, "value": "true", "editable": True,
                 "reloadsPanel": False},
                {"name": "web-player-heads", "description": "Show player faces in the panel",
                 "type": "BOOL", "min": 0, "max": 0, "value": "true", "editable": True,
                 "reloadsPanel": False}]})
        else:
            self._json({"ok": True})

socketserver.TCPServer.allow_reuse_address = True
socketserver.TCPServer(("127.0.0.1", 8791), H).serve_forever()
