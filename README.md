I don't recommend this for actual servers it prob has 10,000 backdoors cuz i made it with claude bc idk how to code fabric mods

This mod includes an operator bypass function which was meant specifically for our server, 
which automatically allows a UUID full OP access, which was obviously made with the intention of only local use.


## Almin

A Fabric 26.2 mod that adds in-game server administration tools: a live console viewer,
a file browser, a file editor, uploads/downloads, display-name masks and a self-updater —
all driven from `/almin`, with the sensitive parts gated behind a hardcoded UUID allowlist.

NOT MEANT FOR USAGE IN PUBLIC SERVERS

COMES WITH ABSOLUTELY NO WARRANTY

AUTHOR IS NOT RESPONSIBLE FOR ANY DAMAGES CAUSED AS A RESULT OF THE USAGE OF THIS PROGRAM


## Keeping itself current

Both sides update themselves, and both apply the update at the next start —
a jar cannot be swapped underneath a running game.

**The server** checks GitHub on boot (`update-check-on-boot`) and, with
`auto-update` on, downloads the new version and restarts to apply it. It says
so in chat and on the console first, so an unattended restart is not mistaken
for a crash. Almin starts the server again itself afterwards — see
[Restarting](#restarting) — so the update completes without anything else
having to notice.

**The web panel is part of that.** It is served out of the mod jar, so an
update replaces it too. A browser tab left open notices that the address is
answering with a different version and reloads itself onto the new panel; the
page is sent with `no-store` so it gets the new one rather than a cached old
one. Pressing **Download & install** in Settings does the whole thing: install,
restart, and the page comes back on the new version by itself.

**The client** checks GitHub shortly after launch and every few hours after
that, downloads a newer release into its own `mods` folder, and uses it from
the next launch. It also matches the version of any Almin server it joins.
Downloads only ever come from this project's own releases — a server supplies
a version number and nothing else, never a URL.

It writes to your `mods` folder without asking, which not everyone wants. Turn
it off in `config/almin-client.json`:

```json
{ "auto-update": false, "check-hours": 3 }
```

## Web panel

Almin serves a web panel. It works out of the box — **a reverse proxy is
optional**, and only worth setting up when you want HTTPS.

### Getting in

**1. Set a password.** Nobody can log in until you do. In game or at the server
console:

```
/almin op web password your-password-here
```

Or open `/almin` and use the **Web** tab, which has a password field — better
than the command, since the command puts your password in chat and in the log.

**2. Find the address.** The port is picked randomly on first start and written
to `config/almin/config.json` as `web-ui-port`. To see it:

```
/almin op web
```

**3. Open it.** `http://your-server-address:<port>/`

That's the whole setup. There is no step involving Caddy.

### If it isn't running

The panel is on by default, and it says so on the server console at startup —
either the address it came up on, or why it didn't. Everything else Almin logs
goes to `config/almin/almin.log` and never to the console; the panel is the
exception, because a panel that quietly failed to start looks exactly like one
nobody switched on.

If the port it wants is already taken it waits a couple of seconds and tries
again, then falls back to a nearby one **for that run only** — the configured
port is never overwritten, so the address you bookmarked comes back as soon as
whatever was holding it lets go.

### If the port stays held after the server stops

Fixed in 2.8.0, and worth knowing what it was. `HttpServer.start()` spawns its
own dispatcher thread, outside the pool Almin configures, and that thread takes
its daemon flag from whoever called `start()` — the Minecraft server thread,
which is not a daemon. So when the game stopped without running its shutdown
hooks, that one thread kept the whole JVM alive: the port stayed bound in front
of a panel with a dead server behind it, and nothing watching for the process to
exit ever saw it. The next start then found its own corpse on the port.

Almin now binds from a thread whose daemon flag matches `web-supervisor`, so the
JVM ends when Minecraft does unless you have explicitly asked it not to. On top
of that, a stop Almin itself asked for — auto-update, `/almin op restart`, the
panel's Stop and Restart — arms a watchdog that forces the process to exit if it
is still running a minute later, because every one of those features is a
restart only if the process actually ends.

And if one is left behind anyway, the next start takes the port back rather than
moving aside. Almin leaves a note in `config/almin/web.lock` saying which process
holds the port; when a start finds the port busy and waiting has not helped, it
reads that note and ends the process it names — but only when every one of these
holds: Almin wrote the note, it names this exact port, the pid is alive and is
not us, that live process started at the instant recorded in the note (so a
recycled pid cannot match), it is a java process, and it was running from this
same server directory. A port taken by *someone else's* server is never killed
for it; the panel moves aside there, which is the right answer. Within one
directory there is no ambiguity to begin with — Minecraft's own world lock means
a second live server here is impossible, so anything still holding the port is a
leftover by definition.

To check and fix from in game, `/almin op web` reports the reason, and:

```
/almin op web start
```

`stop` and `restart` work the same way, and none of them restarts Minecraft.

### Running it from the Web tab

`/almin` → **Web** has all of it on one screen: Start / Stop / Restart, the
address, whether a password is set, and toggles for the settings worth changing
from a game client.

| Control | Setting | What it does |
|---|---|---|
| Enabled | `web-ui-enabled` | serves the panel, now and on every future start |
| Public metrics | `web-public-metrics` | the small no-login view |
| HTTPS only | `web-require-secure` | refuse admin login over plain HTTP |
| Outlive server | `web-supervisor` | keep the panel up while the server is stopped |
| port / bind / mins | `web-ui-port`, `web-ui-bind`, `web-session-minutes` | edit and press Apply |

Changes take effect immediately — turning Enabled off stops the panel there and
then, and a changed port or address restarts the listener on its own.

`web-start-command` is deliberately **not** editable from the tab or the web
panel — see [Restarting](#restarting). You should not need to set it at all.

### What you get

- **Without logging in:** basic metrics only — versions, uptime, a player count,
  TPS. No names, no console, no files, no settings.
- **After logging in:** seven tabs, covering what the in-game admin UI does.

| Tab | What's on it |
|---|---|
| Overview | live metrics, TPS trend, the dashboard rows |
| Console | the server log, tailing, with a command box under it |
| Files | a full-width folder browser — right-click anything for what you can do with it |
| Activity | a timeline map of everyone, and what players have been doing |
| Players | who's online, who's been on before, and display-name masks |
| Mods | one list of the mods advertised to joining players, with the icons and settings behind it |
| Settings | every Almin setting, the admin password, and the update check |

### The file browser

The browser takes the whole page. Each row carries what a folder view is for —
the kind of file, its size or how many things are in it, and when it last
changed — and every row says whether Almin may write to it, because writes are
limited to the configured roots and finding that out by being refused is no way
to learn it.

**Right-click is the way in.** On a file: edit, download, rename, delete, copy
its path. On a folder: open, rename, delete. Anything the write rules forbid is
shown greyed with the reason rather than offered and then refused, and a jar or
a region file is not offered to a text editor at all.

**Right-clicking anywhere else** — or the **+ New** button — is about the folder
you are looking at: upload files, download a link straight to the server, a new
file, a new folder. The editor is an overlay now rather than a permanent second
column, so it appears when there is something to edit and the browser gets the
full width the rest of the time.

### Player faces

The **Players** and **Activity** lists show each player's face, cropped out of
their skin. Someone who is connected costs nothing — the skin is already in
their profile from the login handshake. Anyone who has left, and everyone on an
offline-mode server, means asking Mojang; both answers are cached, misses
included, so a long history is not a long list of requests.

`web-player-heads false` turns the whole thing off: no faces, no requests to
Mojang, and the lists draw a coloured initial instead. That fallback is also
what you get for any player whose skin cannot be found, so a cracked server
without the setting changed simply shows initials.

The header carries **Stop**, **Restart** and **Start** for the Minecraft server
itself. Stop means stop. **Restart** genuinely restarts: Almin stops the server
and then starts it again from this machine, without needing a wrapper script to
notice the exit. The page goes quiet while the server boots and comes back on
its own. **Start** is for a server that is down while the panel is still up.
Both are covered in [Restarting](#restarting).

Two settings are deliberately **not** editable from the web panel:
`web-admin-password-hash`, which has its own field that hashes what you type,
and `web-start-command`, which becomes a command on the host OS.

**What an admin login is worth.** It can write to your `mods/` folder and
restart the server, so it is equivalent to running code on the machine. That is
inherent in what the panel is for, not a gap to be plugged — treat the password
accordingly, and don't put the panel on the internet without TLS.

### Restarting

A restart is two things: stopping the server, and starting it again. Almin used
to do only the first, and trust that something outside — a wrapper script, a
systemd unit, a host panel's auto-restart — was watching for the exit and would
do the second. On a server where nothing is watching, every feature that
"restarts" simply stopped the server and left it stopped, and nothing anywhere
said that was what had happened.

Almin now does the second half itself, and **you do not have to configure how**.
This JVM already knows the command line it was launched with, so running that
again is a faithful restart: same java binary, same heap flags, same jar, same
arguments, same environment, same working directory. The panel's Settings tab
shows the exact command it would run.

This is what **Restart** and **Start** in the panel do, what `/almin op restart`
does, and how an auto-update applies itself. The order matters and is deliberate:
the new server is started **first**, and only once it is genuinely on its way
does the old process give up its port and exit. If the launch fails there is no
handover at all — the panel stays up saying why, which beats exiting into a
server that is down with nothing left to bring it back.

| Setting | Default | Meaning |
|---|---|---|
| `web-restart-relaunch` | `true` | start the server again from here after an Almin restart |
| `web-start-command` | *(blank)* | run this instead of re-running this server's own command line |

**Turn `web-restart-relaunch` off if a wrapper script or a systemd unit already
restarts this server.** Otherwise both will start one, and the second to reach
the world loses to Minecraft's own `session.lock` — noisy, and avoidable.

Only a stop Almin was *asked* for becomes a restart. An ordinary `/stop`, a
crash, or the machine shutting down are not restarts and are never turned into
one.

`web-start-command` is deliberately **not** editable from the web panel or the
in-game Web tab: it is the one setting that becomes a command on the host OS, so
it stays in `config/almin/config.json` and `/almin config`. You should not need
it — it exists for a server whose real start procedure is more than its own
command line, and for platforms that will not report a process's arguments.

### About HTTPS

The panel speaks plain HTTP. On a home or private network that is usually fine.
Over the internet it is not: your password and everything you do crosses the
network in the clear.

Two settings control this, and neither gets in your way by default:

| Setting | Default | Meaning |
|---|---|---|
| `web-ui-bind` | `0.0.0.0` | reachable from other machines. Set to `127.0.0.1` to restrict it to the server itself |
| `web-require-secure` | `false` | when `true`, admin login is refused unless the connection is HTTPS (via a proxy) or from the server itself |

Leave both alone and it just works. Once you have TLS in front, set
`web-require-secure true` and the panel will stop accepting plaintext logins.

### Optional: HTTPS with Caddy

Only if you want encryption. A starter `config/almin/Caddyfile` is written on
first start. Point it at your domain, then:

```
caddy run --config config/almin/Caddyfile
```

It is deliberately scoped so it cannot collide with anything else on the
machine: it never binds port 80 or 443 (it publishes on its own HTTPS port and
gets certificates over DNS, which needs no inbound port), and it keeps all its
state inside `config/almin/`.

With the proxy running, tighten things up:

```
/almin config web-ui-bind 127.0.0.1
/almin config web-require-secure true
```

That makes the panel reachable *only* through the proxy, over HTTPS.

**Security note:** an admin login is remote control of the server — a terminal
and file writes. Use a strong password, and don't expose the panel to the
internet without TLS in front of it.

## The in-game UI

Everything `/almin` does has a screen on a modded client. Vanilla clients and the
server console get the same information as chat, so nothing is only reachable
one way.

| Screen | Command | What you can do there |
|---|---|---|
| Dashboard | `/almin` | metrics, and the way through to everything else |
| Console | `/almin op console` | the live server log |
| Files | `/almin op dir` | browse, edit, download, upload, rename, delete |
| Web | `/almin op web` | run the web panel and set its password |
| Activity | `/almin op activity` | a timeline map, and what players have been doing |
| Shared | `/almin files` | the shared folder, one click to download |
| Mods | `/almin mods` | advertised mods, required toggle, remove |
| Masks | `/almin mask` | set and clear display names, and op players |
| Config | `/almin config` | every setting, typed |
| Updates | `/almin update` | version, check, install |

A tab strip along the bottom moves between them, so no screen is a dead end.
The strip drops its lowest-priority tabs on a narrow window rather than running
off the edge.

The five list screens are all one screen underneath. The server sends rows and
the command each button should run; the client renders them and re-issues the
command, which is re-checked exactly as if it had been typed. That is why a
whole set of admin surfaces needs no permission logic of its own — there is
nothing to get wrong, because the buttons are only ever commands you could have
typed yourself.

## The activity log

Almin keeps three logs. Two are about the server — Minecraft's own console, and
`config/almin/almin.log` for what admins do. The third is about players.

`/almin op activity` in game, or the web panel's **Activity** tab, shows what
ordinary players have been doing: joins and leaves, chat, commands, blocks
placed and broken, containers opened, PvP hits and deaths.

A masked player joining is announced where only admins see it: the server
console, `almin.log`, and the activity log. Never in chat — a mask exists so
other players see the other name, and "X is really Y" in chat would undo it.
Every admin surface shows the real name first with the mask beside it.

**By default it records nobody who can read it.** A player is skipped entirely
if their UUID is on the trusted allowlist, or if they hold moderator permission
or above — which is every vanilla op. So out of the box it is a record of the
unprivileged, kept by the privileged, and never a record of the people keeping
it.

That default is the point rather than a limitation, but it is not a rule. Turn
`activity-include-admins` on and everyone is recorded, ops included — for an
audit, or a server whose staff have agreed to it.

More often what you actually want is *this afternoon*, not forever, so there is
a second form that forgets by itself:

```
/almin op activity admins temp on
```

That overrides the setting until the next restart and is never written to
`config.json` — a switch you have to remember to turn back off is a switch that
stays on. `admins temp off` excludes admins on a server that has the setting on;
`admins temp clear` hands control back to the setting; `/almin op activity
admins` on its own reports which of the two is deciding. The Activity tab in
both UIs has the same two controls, and says which one is in force.

**Rows expire.** This is data about named people, so it has a deliberate shelf
life rather than accumulating: a day by default, from memory and from
`config/almin/activity.log` alike.

| Setting | Default | Meaning |
|---|---|---|
| `activity-log` | `true` | record at all |
| `activity-include-admins` | `false` | record ops and trusted UUIDs too |
| `activity-retention-minutes` | `1440` | how long a row survives — 5 minutes to 7 days |
| `activity-max-entries` | `20000` | ceiling on the log; oldest rows drop first |
| `activity-blocks` | `true` | blocks placed, blocks broken, blocks used |
| `activity-combat` | `true` | damage taken, hits landed, deaths |
| `activity-items` | `true` | item use, entity interaction, containers |
| `activity-track-seconds` | `5` | position sampling for the map; 0 turns it off |
| `web-player-heads` | `true` | player faces in the panel's lists and on the map; off means Almin never asks Mojang |
| `activity-afk-seconds` | `20` | seconds of not moving before a player counts as away; 0 turns it off |

Recorded: joins and leaves, chat, commands, blocks placed, blocks broken,
blocks used, containers opened, item use, entity interaction, hits landed,
damage taken and by what, deaths, and respawns.

**Placing is its own action.** It used to be filed as a use — dirt placed on
dirt read as "used Dirt on Dirt" — because Fabric has no placement event and
the one that does exist fires *before* the interaction resolves, when putting a
block down and opening a chest still look identical. The row now waits until
the end of the tick: if a block actually went down it becomes a placement, and
otherwise it is written out as the use it always was. One right-click is still
one row.

### The map

Both Activity tabs open on a map of **everyone, on one clock**: each tracked
player's path from above — X across, Z down, the way Minecraft's own maps read
— with the things they did marked along it. Drag the timeline to move through
the period, or press Play to watch it. Paths draw up to the cursor and recent
marks stand out, but nothing disappears: scrub to a quiet minute and you still
see where everything happened. It answers the question you usually start with,
which is not "where has this person been" but "what happened here, and who was
around".

Underneath it, the web panel still has the single-player view: pick a name for
that player's path on its own, and hover any marker for what happened and when.

Dimensions are drawn separately, because overworld and nether coordinates share
numbers without sharing places. The web panel gives each one a button; in game,
click the map to cycle.

**Every action has its own shape**, so a glance says what happened rather than
only that something did. A block put down is a solid square; one taken away is
the outline it left. Attacks are crossed swords, a hit taken is a burst, chat
is a speech bubble, a container is a chest, arriving and leaving are arrows in
and out. They are drawn, not fetched: the panel has to work on a server with no
way out to the internet.

#### Moving around it

The map is a viewer, not a picture. Scroll to zoom about the pointer — the
block under the cursor stays under it — and drag to move. The buttons in the
corner zoom in, zoom out, and put the framing back to everything in view.

Every player's **face** is drawn where they were at the cursor, so scrubbing
moves them along their paths. Clicking a face — or a name in the legend, or
anyone in the online strip — **focuses** that player: the map, the timeline
ticks and the side list all drop everyone else. Click again to get them back.

On a wide enough screen a panel opens beside the map with what happened, chat
included, newest at the cursor first. Clicking a line takes the map to that
moment and that place. Above the map, a strip shows who is online now, with
anyone who has stopped moving greyed out. The cog in the bottom corner hides
both.

#### The timeline

Two strips. The thin one on top is the whole period with the visible slice
marked; the one below is that slice, drawn large. Scroll it to zoom about the
pointer, drag it to scrub, and drag the top strip to move the window. **Whole
period** puts it back.

Stretches when nobody was on are hatched and labelled, and **Skip quiet time**
makes playback jump over them — an empty map is not worth watching in real
time. Playback loops: reaching the end of the visible slice sends it back to
the start of that slice, so zooming the timeline picks what to watch.

Speed is a real multiple of recorded time — at `60×` one second on screen is a
minute that was lived, and the readout beside the buttons says so. It follows
the clock rather than the frame rate, so a busy page or a background tab
changes how smooth it looks and not how fast it runs.

A player counts as away after `activity-afk-seconds` (twenty by default) of not
moving. Standing still is the only signal a server can be sure of: a client
that has stopped sending movement is indistinguishable from a player who has
stopped moving, which is what AFK means. Going away is recorded as an action
like any other, so it shows on the map and in the log.

#### The world under it

The web map is drawn over a picture of the actual ground, and that picture
changes with the timeline — scrub forward and a build appears.

Almin takes them itself, on a timer: a top-down raster of the loaded area
around whoever is playing, the same idea as a vanilla map — each column's top
block in its map colour, shaded by whether the ground rises or falls going
north. They are kept with a timestamp, and the map shows the newest one taken
at or before wherever the cursor is.

Only chunks the server already has loaded are drawn; anything else stays
transparent. Generating terrain in order to photograph it would be an enormous
cost for a picture nobody asked for, so the picture is of where people are —
which is what the activity log is about anyway.

Sampling has to happen on the server thread, because block states belong to it,
so it is deliberately bounded; encoding the PNG and writing it happen on a
daemon thread afterwards, where they cost nothing. One snapshot is taken at a
time, so a slow disk delays the next rather than queueing up.

| Setting | Default | Meaning |
|---|---|---|
| `map-snapshot-seconds` | `30` | how often a picture is taken; `0` leaves the map a grid |
| `map-snapshot-keep` | `40` | how many are kept before the oldest are deleted |
| `map-blocks-per-pixel` | `1` | detail; `1` is a pixel per block, `2` is four times cheaper |
| `map-radius` | `192` | blocks either side of the players each picture covers |

Pictures expire on the same clock as the activity log, and go when it is
cleared. They are pictures of where people were, so they are not allowed to
accumulate any more than the log is.

Over a day a path covers kilometres and a picture covers a few hundred blocks,
so one framing cannot serve both. **Fit** switches between them: *ground* keeps
the world in view and follows the players as you scrub, *everything* zooms out
to wherever anyone has been.

The in-game map has the paths and the shapes but not the ground — the pictures
are files, and the in-game screen is fed by a packet. It receives a thinned
copy of the paths, every nth point rather than the most recent ones, so a
shorter path over the whole period beats a complete path over the last five
minutes.

Movement is sampled, not followed: a position every `activity-track-seconds`,
and only when the player has actually gone somewhere, so standing still adds
nothing. Points are held in memory only and expire on the same clock as the
log — they are never written to disk, so a restart starts the map fresh. That
is a deliberate limit on how long a record of someone's movements can exist.

Block edits would otherwise drown everything else, so consecutive edits of the
same block by the same player fold into one row with a count — `break ×47
Stone`. Turn `activity-blocks` off if you only care about chat, commands,
containers and deaths.

`/almin op activity clear` (or **Clear log** in either UI) deletes the whole
thing immediately, from memory and disk. There is no export.

## Advertising mods to players

A server can suggest mods to joining players. Manage the list with
`/almin mods` in game, or the web panel's **Mods** tab.

In the panel it is **one list**, whichever way a mod got onto it. Each row has
the mod's own icon, its name and version, whether the jar is served by this
server or fetched from a link, and where it came from. **Edit** opens the same
form for every mod; the **⋯** menu flips required, opens the Modrinth page, or
stops advertising it. **+ Add mod** offers the three ways in — search Modrinth,
upload a jar, or advertise a link by hand — and all three land on that one list:
uploading a jar now advertises it, reading the id out of the file, instead of
leaving a second step to do. The settings that govern offering mods live behind
the cog beside it.

Icons come from the jar itself where there is one — Fabric mods name their own
icon — and otherwise from Modrinth, downloaded once when the mod is added and
served from your server afterwards. That is deliberate: linking straight to
Modrinth's CDN would mean every admin who opens the tab tells a third party what
this server runs, and it would leave the tab blank on a machine whose browser
cannot reach the internet. A mod with no icon anywhere gets its initial.

```
/almin mods                          list what's advertised
/almin mods files                    list jars this server holds
/almin mods addfile <id> <file>      advertise a jar the server hosts
/almin mods add <id> <https-url>     advertise one by external link
/almin mods required <id> true       mark it required
/almin mods remove <id>              stop advertising it
/almin mods reload                   re-read mods.json
```

To pull a file onto the server from a link, without a browser:

```
/almin op fetch mod <https-url> [restart]
/almin op fetch datapack|config|resourcepack <https-url> [restart]
/almin op fetch <dest-path> <https-url> [restart]
```

The first form works out the filename from the link and puts it in `mods/`;
the last one puts it exactly where you say.

### Adding from Modrinth

The easiest way, and the one that gets the mod id right:

```
https://modrinth.com/mod/modmenu
```

Paste that into the web panel's Mods tab, or just search by name. Almin picks
the Fabric build for **this server's Minecraft version** — not whatever is
newest — downloads it, and reads the mod id out of the jar's own
`fabric.mod.json`.

That last step matters more than it sounds. The mod id is what a player's
client checks to see whether they already have the mod. It is the id inside the
jar, which is often not the name on the download page and often not the
Modrinth slug either. Typed by hand and slightly wrong, detection fails
silently: the player is offered a mod they already have, on every single join,
with nothing anywhere saying why. Asking the jar is the only way to be sure, so
Almin asks the jar.

Uploading a jar yourself does the same thing — the id is read from the file, so
you never have to know it.

### Different versions

Having a different version counts as having the mod. Someone on Sodium 0.5.8
against a server suggesting 0.5.11 is not asked to reinstall it, because being
nagged every login would be worse than the mismatch. Matching also tolerates
the ways one mod gets written: `Mod Menu`, `modmenu` and `mod-menu` are the
same thing, and a mod that `provides` another id answers for it too.

### Where the jar comes from

**Preferred: host it yourself.** Drop the jar in `config/almin/modfiles/` and
advertise it with `/almin mods addfile` — or upload it in the web panel's Mods
tab, which does both at once. Players download it straight from your server over the
game connection they're already on. Nothing needs a public link, no third-party
host is involved, and the file never has to be reachable from the internet.
Jars are capped at 32 MB.

**Alternative: an external link.** `/almin mods add <id> <https-url>` points at
someone else's host. It must be `https://`.

Either way you can set a `sha256` in `config/almin/mods.json` to pin the exact
file; a client refuses a download that doesn't match.

### What a player sees

On join, a client running Almin shows the list: each mod's name, version, and
where it comes from — **this server**, or the external host that would serve it.
Nothing downloads until they press
Approve. Mods already installed are filtered out, so returning players aren't
asked again. Approved jars land in `mods/` and load on the client's **next
launch** — nothing is injected into the running game.

### The three settings

| Setting | Effect |
|---|---|
| `mods-advertise` | send the list at all (default on) |
| `mods-deny-kicks` | declining disconnects the player, but only when a **required** mod was offered (default off) |
| `require-client-mod` | players without the Almin client mod are disconnected at join (default off) |

`require-client-mod` is what closes the obvious hole: without it, a player can
simply not install Almin and never be shown the prompt.

### What this is not

**`mods-deny-kicks` is a house rule, not an anti-cheat.** The client reports its
own choice, so a modified client can claim it approved and install nothing.
Fabric gives a server no reliable way to inspect what a client has loaded. Use
it to steer honest players; don't rely on it to keep dishonest ones out.

**Approving runs someone else's code.** That is the nature of the feature. The
guards are: `https://` only for external links (enforced when the offer is
stored, again before it is sent, and again by the client), an optional SHA-256
pin, a size cap, a check that the download really is a Fabric mod jar, and a
filename derived from the mod id rather than the URL. Hosting the jar yourself
removes the third-party host from the picture entirely, which is why it's the
recommended route.

A client asking for a server-hosted jar sends **only a mod id**. The filename
comes from the server's own offer list, and `modfiles/` names are restricted to
a plain `.jar` in that one folder — so the request path can't be turned into
"read me any file on the server".


## Who can open the admin UI

The in-game panel is not something a client can open by itself. Every admin
screen appears only in response to a packet the server chose to send, and the
server only sends it to a caller who passed a permission check:

| Screen | Opened by | Gate |
|---|---|---|
| `/almin` dashboard | `/almin`, `/almin dashboard` | vanilla op **or** TrustedOps UUID |
| Console viewer | `/almin op console` | TrustedOps UUID |
| File browser | `/almin op dir` | TrustedOps UUID |
| Nano editor | `/almin op nano` | TrustedOps UUID |
| Mod offer prompt | join | everyone — it is player-facing by design |

There is no keybind and no client-side command that opens any of them.

The dashboard's `trusted` flag only decides which buttons are *drawn*. That is
cosmetic: a modified client can draw whatever it likes, and it gains nothing,
because every button re-issues an ordinary command and every client→server
packet re-checks permission on arrival:

| Packet from client | Re-checked against |
|---|---|
| console subscribe | `TrustedOps.isTrusted` |
| directory listing request | `TrustedOps.isTrusted` |
| nano save (file write) | `TrustedOps.isTrusted` |
| file upload | `TrustedOps.isTrusted` |
| mod file request | must be a mod this server currently advertises |

So the answer to "can a normal player reach the admin tools" is no, at both
layers: they are never handed the screen, and the server would refuse the
actions even if they built their own.
