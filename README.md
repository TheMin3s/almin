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
`auto-update` on, downloads the new version and restarts to apply it. When
`auto-update-when-empty` is on (the default), an update found while people are
playing is queued until the last player leaves. A slow download is staged as a
non-jar and the player count is checked again before anything is replaced, so
somebody joining mid-download is not kicked. The running jar is replaced at
the same pathname, so background mod scanners such as BlueMap never retain a
versioned path that disappears during startup. Almin starts the server again
itself afterwards — see
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
website deliberately remains available when Minecraft stops unless you opt out.
On top of that, a restart Almin itself asked for — an auto-update, `/almin op
restart`, or the panel's Restart — arms a watchdog that forces the old process
to exit if it is still running a minute later, because a restart only works if
the old process actually hands over.

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

If a NAS panel, SSH session or process wrapper disconnects stdin instead, Java
can report `Exception handling console input` with `java.io.IOException:
Input/output error`. That exception comes from Minecraft's console-reader
thread, not its main server thread. Almin turns the dead pipe into a clean EOF,
so the server and website continue without the stack trace; console commands
remain available through Almin's website even when the host terminal is gone.

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

### Minecraft's own settings

The **Settings** tab has two halves. *Almin* is everything above; *Minecraft
server* is `server.properties` — the game's file, not Almin's — with a typed
control per row (a dropdown for a true/false, a number box for a number), a
filter, and an Undo on anything you have touched.

Editing it here rather than in the file browser is the point: the browser would
let you do it anyway, so doing it properly is strictly better. Values are
written straight away, **on the line the key is already on**, so the comments
and the order in the file survive — which is more than `Properties.store` would
have left of them.

Almost everything in that file is read when the server boots and kept in memory
after that, so **changes land at the next restart**, and the panel says so
where you press Save. Anything whose name says `password` comes back masked and
is only written if you type a new one: the panel is behind a login, but a
credential that renders into a page is a credential in a screenshot.

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

### What each client is running

Every row in the **Players** list carries a badge saying whether that client
has the Almin mod — the same test the join handler uses to decide whether to
send it anything, so it is the truth rather than a guess.

**Client** on a row that has one opens what it reported: the mod list, the
Minecraft and loader versions, the launcher's own name for itself, and the
shape of the machine — operating system, version, architecture, processors,
and the heap Java was given. That is what a support question needs.

The list is ordered so that *what changed* does not need reading. Present mods
are alphabetical; ones that arrived since the last join are green with a `+`
and stay in that alphabetical order; ones that have gone sit at the bottom,
struck through, with the date, for `client-mod-history-days` (a week by
default) and are then forgotten. A mod that comes back clears its own removal,
so toggling a profile does not fill the list with ghosts, and a version change
is an upgrade rather than an install.

**Everything here is self-reported.** A modified client can put anything it
likes in that packet. It is a support tool and a house rule; nothing that
treats it as proof is right about what it has.

**What is deliberately not collected:** no machine model, no serial number, no
username, no file paths, no addresses. Reading a Mac's model would mean running
`sysctl` on somebody's computer, and a mod that shells out on a player's
machine to report what it found is a different kind of program from this one.
`client-report false` turns the whole thing off, and then the client sends
nothing at all.

| Setting | Default | Meaning |
|---|---|---|
| `client-report` | `true` | let the client mod report its mods and machine |
| `client-mod-history-days` | `7` | how long a removed mod stays listed as recently removed |

### Restricting mods

**Mods → Settings → Restricted mods** takes a list of mod ids players are asked
not to run — `xaerominimap`, as the loader spells it, not the display name.
Every client's report is checked at join: a hit is logged and, with
`mods-restricted-kick` on, disconnects them with the names in the message.

The section is hidden until **Almin required to play** is on, and that is not
tidiness. Without the client mod there is no mod list to check, so the rule
would only ever land on the players honest enough to be visible — which is the
wrong half of them. `mods-show-restricted` puts it back for anyone who wants it
regardless.

Like everything else here it is self-reported, so it is a house rule and not an
anti-cheat.

| Setting | Default | Meaning |
|---|---|---|
| `mods-restricted` | *(empty)* | comma-separated mod ids to restrict |
| `mods-show-restricted` | `false` | show the section without `require-client-mod` |
| `mods-restricted-kick` | `false` | disconnect a player running one, rather than only logging it |

### What each player has been doing

Every row in the **Players** list carries three more things.

A masked player gets a small second row underneath with the face and name of
the account they appear as. A mask is another player's name as far as everyone
else is concerned, and the useful question — "who does this look like" — is
answered by the face rather than by the string. It is a bare name with no UUID
behind it, so that face is looked up by name; a mask naming no real account
falls back to an initial, which is itself the answer.

Beside it, **what they did**: one icon per kind of action with how many times
in the corner, so a glance says "mostly breaking blocks, one death" without
opening anything. For somebody who is connected that means *this session* —
"what have they been doing" asked about a player who is here is a question
about now, not about last week — and it says which nothing it is when there is
none.

And **where they went**: their path over the ground it was walked on, framed to
fit whatever they actually did. For somebody who is offline that is **their
last visit** rather than their whole week — the week is a scribble, and the
walk they took before logging off is a thing you can read. The visit is found
from the samples themselves, since a gap longer than twenty minutes is where
they were not here; join and leave rows would only work for players who were
being recorded at the time. Their action strip follows the same window — someone who spent the day in one room and someone who walked to
the badlands both get a picture that fills the box, with a scale bar underneath
saying which is which. Without the bar the two would look identical, which
would be worse than no map at all.

**Activity** on any row — and the button on the corner of the little map — opens
that player on the big map with everyone else filtered out.

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

Almin can do the second half itself, and **you do not have to configure how**.
This JVM already knows the command line it was launched with, so running that
again is a faithful restart: same java binary, same heap flags, same jar, same
arguments, same environment, same working directory. The panel's Settings tab
shows the exact command it would run. Self-relaunch is on by default so a
directly launched server actually comes back; hosted or container installs
whose own supervisor handles restarts should switch it off.

This is what **Restart** and **Start** in the panel do, what `/almin op restart`
does, and how an auto-update applies itself. The order matters and is deliberate:
the new server is started **first**, and only once it is genuinely on its way
does the old process give up its port and exit. If the launch fails there is no
handover at all — the panel stays up saying why, which beats exiting into a
server that is down with nothing left to bring it back.

| Setting | Default | Meaning |
|---|---|---|
| `web-supervisor` | `true` | keep the entire website available while stopped |
| `web-restart-relaunch` | `true` | handle Almin restarts here without keeping a stopped-server website permanently |
| `web-start-command` | *(blank)* | run this instead of re-running this server's own command line |

**Turn `web-restart-relaunch` off when a wrapper, container policy, host panel,
or service already restarts this server.** Otherwise both can start one, and
the second to reach the world loses to Minecraft's own `session.lock`.

The two switches are deliberately independent, as they were in the original
restart system. `web-supervisor` only preserves the website. It does not make
Almin take restart ownership away from a host wrapper. All seven authenticated
pages stay reachable after Minecraft stops when it is enabled; actions that
need a live server say that it is stopped, while **Start server** remains
available without a timeout.

Restart and Start use the original launch-first handoff: the replacement is
successfully created before the old website releases its port and exits. The
replacement must now reach Minecraft's `SERVER_STARTED` event too; a Java
process that spawns and then dies during mod or world loading is still a failed
launch. The current website remains untouched, reports the failure, and allows
Start to be tried again. Slow servers are allowed up to ten minutes to finish
startup before the attempted child is stopped and reported as failed.

Turn `web-supervisor` off only when an external wrapper must see the Almin JVM
exit after an ordinary server stop.

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
life rather than accumulating: five days by default, from memory and from
`config/almin/activity.log` alike. Five is long enough that "what happened over
the weekend" is still answerable and short enough that this stays a working
record rather than an archive; `activity-retention-minutes` moves it anywhere
from five minutes to thirty days.

A server that was already running gets the new default too. Every setting is
written to `config.json` on first start, so a changed default would otherwise
only ever reach a fresh install — the file carries a version, and the one
change to five days is applied once, and only where the value is still sitting
on the old one-day default.

| Setting | Default | Meaning |
|---|---|---|
| `activity-log` | `true` | record at all |
| `activity-include-admins` | `false` | record ops and trusted UUIDs too |
| `activity-retention-minutes` | `7200` | how long a row survives — 5 minutes to 30 days |
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

The web Activity tab has two renderers. With no extra mod it uses Almin's
recorded 2D ground snapshots described below. **Install 3D world map** in the
Activity heading installs BlueMap as a separate optional Fabric mod; after one
restart its full rendered world becomes the main canvas, while **Legacy 2D**
always remains beside it. Timeline/playback, filters, paths, faces, clusters,
fading, sequences, coordinate grid, side list and fullscreen work with either
renderer. In the 3D world, build scenes expand at their real X/Y/Z coordinates
instead of opening over the map. See [the BlueMap integration boundary](docs/BLUEMAP.md)
for installation, security, removal and the files it owns.

Travelled player paths fade segment by segment on the same clock as activity
marks in both renderers; during playback, the faint guide is only the path ahead.

Almin neither compiles against nor redistributes BlueMap. Its integrated web
server is forced onto an instance-local loopback port and reached only through
the authenticated Almin origin, including through the existing Caddy proxy.

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
numbers without sharing places. A labelled switcher under the timeline names
them the way people do — Overworld, Nether, The End — and lists any dimension
there is either activity or a picture of the ground for, so somewhere nobody
has been since the log rolled over is still somewhere you can look at. In game,
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
moves them along their paths. Somebody who has logged off by then is drawn
smaller, greyed, with a small arrow on the corner saying they left there — a
path ends where somebody logs off, and a face left standing at full size says
they are still there, which after an evening of people coming and going is a
map full of players who all went home hours ago. Rejoining undoes it. A face
also goes grey once nobody has moved it. Nobody
is sampled while they stand still, so the gap between the cursor and their last
sample is exactly how long they have not moved — which makes the grey right
when you scrub back, rather than only describing right now. Clicking a face —
or a name in the legend, or anyone in the online strip — **focuses** that
player: the map, the timeline ticks and the side list all drop everyone else.
Click again to get them back.

**A coordinate grid** sits under everything, on round block coordinates chosen
for the zoom and labelled with them, with x=0 and z=0 brighter than the rest.
It replaces four lines at quarters of the screen, which moved when you panned
and stood for no coordinate at all — a world people navigate by numbers should
show the numbers. The cog turns it off.

Marks keep their size **on the screen** rather than in the map's own
coordinates, so making the map bigger shows more map instead of bigger marks.
Without that, fullscreen made a mark that read well in the panel fill a
building.

On a wide enough screen a panel opens beside the map with what happened, chat
included, newest at the cursor first. Clicking a line takes the map to that
moment and that place. Above the map, a strip shows who is online now, with
anyone who has stopped moving greyed out.

**Marks that crowd become one box with a number on it.** Zoom out over an
evening's mining and forty overlapping shapes are four boxes; zoom in and they
separate again, because the grouping is by distance on screen rather than by
distance in the world. Clicking a box lists what is inside it, and identical
things fold with a count — fifty rows of "broke Stone" is one line saying
`break ×50`, which is both shorter and more informative than fifty lines.
Clicking one of those lines takes the timeline to it, and the box stays put
through a repaint, a playback frame or a live refresh, following its marks as
you pan and zoom.

**Fullscreen** hands the whole window to the map, with the timeline, the
controls, the side list and the legend floating over it instead of sitting
around it. They are the same elements in the same order — only positioned
differently — so going in and out cannot lose where you were looking. `Esc`
comes back.

#### Forgetting with age

Marks fade with recency out of the box — what happened just now stands out and
everything else stays legible, with a high floor, because on a long period
almost everything is old and fading those away would empty the map of the marks
it exists to show.

**Fade old marks away** is a different thing and is off until you ask for it:
past its window a mark is not drawn at all. It is set per group — the world,
fighting, talking, coming and going, things, and sequences — because "stop
showing me week-old chat" and "stop showing me week-old block edits" are
separate wishes. A box of grouped marks is as visible as the freshest thing in
it and goes once everything in it has. None of this deletes anything: it is
about what is drawn, and what is *kept* is `activity-retention-minutes`.

#### Showing less of it

**Filter** opens the tree of what is actually in the period: kinds of thing
grouped into the world, fighting, talking, coming and going, and things — with
a count beside each — and under `place`, `break` and the rest, the particular
blocks and items, also with counts. Ticking a kind shows that kind; expanding
it and ticking `Oak Log` narrows to that. Unticking the kind lets go of the
things under it, so the map never hides rows for a reason that is no longer on
screen.

Sequences are in the same tree. Ticking `fight` shows everything that player
did *while the fight was going on* rather than only the swings, which is the
honest reading of the question. The filter reaches the map, the timeline ticks
and the side list together, so all three agree about what is being shown.

When a model is enabled, the question box above the map has two separate
actions. **Ask** answers from the selected Everything/player/area scope and the
visible timeline window without moving the map. **Find on map** also applies
the supporting player, action, item and episode filters so the answer can be
inspected. Both are grounded in the server's own Activity rows; chat text is
withheld when `ai-send-chat` is off.

#### One badge per stretch of work

A stretch of work gets a badge with the tool on it — a pickaxe for a shaft, an
axe for felling trees, a hammer for a build, a sword for a fight, boots for a
journey — worked out from what was actually broken, so digging through sand
gets a shovel and digging through stone gets a pickaxe. Where the server has
textures they are the game's own iron tools; where it has none they are drawn,
which is the same fallback the ground has. Point at one and it says what it was — and what the model said about it, once
one is connected. It waits for the pointer on purpose: a dozen sentences drawn
across the map cover the map they are describing, and the badge already says
what kind of work it was, which is what a glance needs. Clicking one opens it.

#### What a stretch of work actually built

The map answers *where*; a mark from above cannot answer *what shape*. The log
knows every block that went down and every one that came up, with its height,
so the shape is recoverable — and **3D** on a build, a shaft, a tunnel or a
clearing draws it in isometric: placed blocks solid and yellow, broken blocks
as red outlines, because what is being shown there is an absence. Anything hit
during it is a red burst where it happened.

Blocks are drawn in the colour they actually are — the server knows, from its
own registry and from the texture where there is one — with the yellow or red
on the outline. Two questions, two channels: a wall of solid yellow only said
"somebody put blocks here", which the sentence above the picture had already
said.

The ground around it is the world itself, in three dimensions: snapshots carry
a height for every column beside its colour, so the land is built out of the
same blocks the build is — a top face per column at its own height and a side
face wherever the ground next to it is lower. A hillside is a hillside rather
than a picture of one. **Ground** turns it off.

Heights are stored beside each whole picture, not each difference: ground moves
slowly, and the shape from the nearest keyframe is the same shape. A snapshot
taken before this existed has no heights, and the scene is then the blocks
alone rather than an error.

Turn it a quarter at a time to see round the back, and drag the slider to watch
it go up in the order it actually went up. The block size and the framing come
from what is in the scene, so it fits its window rather than running off the
edge of it.

An episode is cut by time and by distance from a running centre, so a player
who dug a hole, walked thirty blocks and dug another lands both in one run —
and two heaps thirty blocks apart drawn in one picture are two pictures. Blocks
within a dozen of each other are one piece of work; the rest is left out, and
the count under the picture says how much. It is clamped to 64 blocks across,
and only what changed is in it.

#### How it looks

The cog in the top corner opens the adjustments that are about your eyes rather
than about the server, and they are there rather than in Settings because they
are the things you change *while looking at the thing they change*: ground
darkness, path width, marker size, face size, whether marks are coloured by
what they were or by who did them, whether faces, paths, grouping, sequence
badges and the side panels are drawn at all, **how often the live map
refreshes**, and **whether old marks fade away**. Faces have their own size
because they are the thing you look for: tying them to the marker size meant
making them readable made everything else shout. They are remembered in the browser, so two admins looking at the
same map are allowed to disagree about how dark the ground should be.

#### The timeline

Two strips. The thin one on top is the whole period with the visible slice
marked; the one below is that slice, drawn large. Scroll it to zoom about the
pointer, drag it to scrub, and drag the top strip to move the window. **Back to
live** puts the whole period back in view.

The period is exactly what is saved, end to end, so zooming out stops at the
oldest and newest rows there are. It used to run to the clock instead, which on
a server nobody had touched since yesterday meant most of the timeline was
empty and there was nothing to zoom out to but blank. Live still means the
newest thing there is, and says `live · nothing since 3h ago` when that is
older than a couple of minutes, rather than implying the map is showing now.

**It opens live.** "What is happening" is the question you arrive with;
"what happened at four o'clock" is the one you come to second. So the cursor
follows the clock, the bar under the timeline says `LIVE` where Play usually is,
and the playback controls are not there — live, there is no speed, no direction
and no position to set. Touch the timeline and they all come back, along with
**Back to live**. The map refreshes itself every ten seconds while live, keeping
wherever you have panned and zoomed to.

The period runs to the clock rather than to the last thing anyone did. On a
quiet server those are an hour apart, and a timeline that stopped at the last
row would say the map was showing an hour ago.

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
around whoever is playing. They are kept with a timestamp, and the map shows
the newest one taken at or before wherever the cursor is.

It is the same idea as a vanilla map but shaded the way the web world maps are.
Vanilla gives every block one flat colour out of a small palette and picks
between three brightnesses, which is why a vanilla map reads as blotches: a
beach and a desert are the same yellow, and a hillside only shows at all if it
happens to face north. Here relief comes from the slope in both directions, so
a hill is a hill whichever way it faces; and water is darkened and blued by how
deep it is, so a coastline and a shelf are visible.

**And where it can, it uses the game's own textures.** At one pixel per block
there is exactly enough room for one texel, so a block takes its texture's
colour nudged by the texel that falls at that position — which means a sand
field comes out grainy the way sand actually is, a floor of oak planks comes
out striped the way planks actually are, and sandstone stops being the same
yellow as sand. It tiles the real 16×16 file across the world, so it is the
game's grain rather than a rule somebody wrote about sand.

Textures do not come from the server: a dedicated server jar ships none,
because nothing on a server ever draws a block. So Almin looks for them, in
order, in `config/almin/textures.zip`, `config/almin/textures/`, any
`resourcepacks/*.zip` or unpacked pack folder, and finally its own classpath
(which has them in a development run). **Any resource pack will do** — drop one
into `resourcepacks/` and the next snapshot is textured. The legend under the
map says which it is using, or `map palette` if it found none, and finding none
is not an error: the map falls back to the palette with an invented grain, and
looks the way it did before.

A texture is only used where it is a surface: a cross-shaped plant, a pane of
glass or a torch is mostly empty, and averaging one into a single pixel gives a
colour nothing in the world is. Those keep the map palette. Greyscale textures
— grass, leaves, water — are tint masks the game colours by biome, and nothing
here knows the biome; the texture supplies the pattern and the palette supplies
the green.

Reading a texture never happens on a game tick: a block Almin has not seen
before takes its palette colour for that picture and is loaded on a daemon
thread for the next one.

Where the top of a column has no colour of its own — glass, a barrier, a light
block, scaffolding — the sampler looks down past it for a block that does,
rather than reporting a hole. A glass roof used to punch one straight through
the map, and the ground was there all along; it just was not the block being
asked.

Only chunks the server already has loaded are drawn; anything else stays
transparent. Generating terrain in order to photograph it would be an enormous
cost for a picture nobody asked for, so the picture is of where people are —
which is what the activity log is about anyway.

Sampling has to happen on the server thread, because block states belong to it,
so it is deliberately bounded; encoding the PNG and writing it happen on a
daemon thread afterwards, where they cost nothing. One snapshot is taken at a
time, so a slow disk delays the next rather than queueing up.

**Pictures thin with age rather than stopping.** Keeping every picture for a
month is impossible and keeping none past a day loses the thing the map is
for, so the further back a picture is, the fewer of its neighbours are kept:

| How far back | One picture every |
|---|---|
| the last half hour | every one taken |
| up to 2 hours | 1 minute |
| up to 6 hours | 5 minutes |
| up to a day | 15 minutes |
| up to 3 days | 30 minutes |
| up to a week | 1 hour |
| up to `map-snapshot-days` | 4 hours |

What survives a slot is the newest picture in it — what the world ended up
looking like — and two areas being watched at once are thinned separately, so
one does not erase the other. A month of pictures taken every thirty seconds is
86,400 of them; the curve keeps about 600. `map-snapshot-thin` turns it off and
goes back to keeping the newest N.

**Everywhere anyone has been stays drawn.** Pictures are taken of wherever
people are and their windows are aligned to a grid, so a server played on for a
week has pictures of a dozen different places rather than a dozen pictures of
one. The map draws one per place — the newest taken at or before the cursor —
rather than only the one nearest the cursor, so walking away from your base no
longer blanks the map behind you. Nobody is touching that ground, so the last
picture of it is still the right one.

**Only what changed is stored.** The ground barely changes, so writing all of it
again every half minute was writing the same picture over and over. Capture
windows are aligned to a 64-block grid, so a player wandering around produces
pictures of exactly the same square — and each one is then filed as the
difference from the last whole picture, which on a world standing still is a few
hundred changed pixels out of a hundred and fifty thousand. Differences are
always against the whole picture rather than against the one before them, so
reading any snapshot costs two files and never a chain of forty; a fresh whole
one is taken when the window moves, when half the map has changed, or every half
hour. A whole picture something depends on is never deleted before the things
that depend on it. What leaves all this is an ordinary PNG — nothing downstream
knows differences exist.

That is what makes keeping two hours of them affordable, which matters now that
the log itself keeps five days: without it, `map-snapshot-keep` at 40 was twenty
minutes of ground under a five-day timeline.

| Setting | Default | Meaning |
|---|---|---|
| `map-snapshot-seconds` | `30` | how often a picture is taken; `0` leaves the map a grid |
| `map-snapshot-keep` | `1500` | hard ceiling on how many are kept, behind the curve |
| `map-snapshot-days` | `30` | how far back ground pictures go; `0` follows the activity log |
| `map-snapshot-thin` | `true` | keep fewer the older they get, rather than deleting outright |
| `map-blocks-per-pixel` | `1` | detail; `1` is a pixel per block, `2` is four times cheaper |
| `map-radius` | `192` | blocks either side of the players each picture covers |

Ground pictures have their own window — `map-snapshot-days`, a month by default
— rather than the activity log's. That is defensible rather than an oversight:
a thinned month-old snapshot is a picture of the world, what was built and what
was cleared, and not a record of who was standing in it. The paths and the rows
that say who did it still expire on the log's clock. They all go when the log is
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
nothing. Points expire on the same clock as the log, which is the limit on how
long a record of someone's movements exists.

They are written to `config/almin/tracks.json` and picked up again at the next
start. Holding them in memory only sounded like a privacy measure and was
really an inconsistency — every row in the activity log carries the coordinates
it happened at and has always been written to disk — and it meant a player who
was offline had no path at all, which is exactly when you want one. Clearing
the log deletes the file with everything else.

Block edits would otherwise drown everything else, so consecutive edits of the
same block by the same player fold into one row with a count — `break ×47
Stone`. Turn `activity-blocks` off if you only care about chat, commands,
containers and deaths.

`/almin op activity clear` (or **Clear log** in either UI) deletes the whole
thing immediately, from memory and disk. There is no export.

### What it all meant

The log records events: `break Oak Log at 214,71,-88`, four hundred times.
Nobody reads that, and reading it is not how anyone finds out what happened.
What happened is "someone cleared the trees behind spawn" — and that sentence
is not in the log. It is in the *shape* of the log: which blocks, over what
ground, in what order, how tall, how long it took.

Under the map, **What happened** shows that shape. Rows are cut into runs — one
player, one place, no long pause — and each run is classified by its geometry
and its materials:

| Looks like | Comes out as |
|---|---|
| logs in a narrow column, three or more high | *Chopped down about 4 trees* |
| one column of breaks going down | *Dug a shaft from y 64 down to y 11* |
| a long line two blocks high | *Tunnelled 120 blocks east-west at y 11* |
| placements inside a box with height | *Built something 14 across and 6 high, mostly Oak Planks* |
| placements all at one level | *Laid 60 blocks flat of Stone Bricks — a floor or a path* |
| swings and hits traded in one spot | *Traded blows with Steve* |
| twenty breaks over flat ground | *Cleared 18×14 of ground at y 71* |
| a death anywhere in the run | the game's own death message |

Two more come from the paths rather than the rows, because walking is not an
event and a player who spent twenty minutes going somewhere leaves no trace in
the log at all: *Travelled 640 blocks* — and, when someone walks a long way
without getting anywhere, *Back and forth around 90,12 — 620 blocks walked
without leaving 18 blocks, over 7 minutes*.

All of this is worked out on the server from the log itself. No model, no
network, no key, nothing configured — click any line and the map goes to that
moment and place.

Block names arrive from the game already translated, so the material rules read
English. On a server running in another language they simply miss and
classification falls back to geometry: a shaft is still a shaft, but "chopped
down a tree" becomes "broke 40 blocks". Nothing here knows intent either — a
hole is a hole whether it was a mine or a grief.

#### Handing that to a model

**Off by default.** Settings → *Reading the log with a model* has the whole
thing in one place: where (a local model, Anthropic, OpenAI), the address for a
local one, the model name, the API key, how often to summarise on its own, and
the switch. **Turn on** stays disabled until there is enough to talk to — a
model name, and an address if it is a local one. The key is not part of that
test: a local model does not want one, and refusing to start until somebody
typed a key they do not need would be the wrong half of the check. **Test it**
saves, asks for a summary, and tells you what came back, so "is it working" has
an answer that is not a guess.

Once on, it summarises **on its own** every `ai-auto-minutes` (half an hour by
default) as well as when **Summarise** is pressed. A summary you have to ask
for is one nobody reads; the value is walking up to the panel and finding out
what happened. It skips when nothing has been recorded since the last one.

You get a paragraph saying what the session was about, up to five moments worth
looking at — marked on the timeline, clickable — and, for up to a dozen
stretches, **what that stretch was probably for**. That last one is the thing
the episode's own sentence cannot know: "dug a shaft from y 64 down to y 11" is
what happened, and "getting down to the ore layer under the base" is what it
was in aid of, read from what came before and after it. It shows under the
episode in the list and on the badge's hover, kept visually apart from the
fact, because the fact is certain and the reading is not. A stretch the model
has nothing to say about gets nothing rather than an invented reason.

The model is given the episodes, not the log — forty sentences rather than four
thousand rows. That is deliberate twice over. Counting coordinates is the thing
a loop is best at and a model is worst at, so it is done first; and a prompt of
forty sentences fits in a 3B model running on the same machine, which is the
difference between this being a feature every server can use and a feature with
a bill attached.

A moment's coordinates come from the episode it names, never from the model —
asking it to copy numbers back is asking it to invent them. If it answers with
prose instead of JSON, the prose becomes the summary rather than an error.

| Setting | Default | Meaning |
|---|---|---|
| `ai-enabled` | `false` | let a model summarise at all |
| `ai-provider` | `local` | `local`, `anthropic`, or `openai` |
| `ai-model` | `qwen2.5:3b` | model name, as that service spells it |
| `ai-base-url` | `http://127.0.0.1:11434/v1` | where `local` lives |
| `ai-send-chat` | `true` | include what players said |
| `ai-auto-minutes` | `30` | summarise unattended every N minutes; `0` is only when asked |

`local` means anything speaking the OpenAI chat API at `ai-base-url` — Ollama,
llama.cpp's server, LM Studio — and it is the default because it is the only
option where nothing leaves the machine. Almin does not ship an inference
engine; running a small model is `ollama pull qwen2.5:3b` and pointing this at
it.

**What leaves the machine, if you pick a hosted provider.** Player names, what
they did, where they did it, and — unless `ai-send-chat` is off — what they
said in chat. That is a decision about other people's data, which is why this
is off until someone turns it on, and why the Settings tab says it in those
words next to the switch. `ai-send-chat` is separate from the rest because it
is different in kind: coordinates are a record of a game, chat is a record of a
conversation.

The API key is set in the panel or by writing `config/almin/ai-key`. It is
deliberately **not** in `config.json`: that file is served by the panel's own
file browser and rewritten whenever a setting changes, and a credential in it
would end up in every copy anyone pasted into a bug report. The browser refuses
to open or list `ai-key` at all, and on a POSIX host the file is written
`0600`.

Nothing is sent until somebody presses **Summarise**, or sets
`ai-auto-minutes`. Even then an unattended run is skipped when nothing has been
recorded since the last one — re-summarising an unchanged log is spending money
to be told the same thing.

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
