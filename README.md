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
| Outlive server | `web-supervisor` | keep the panel up after the server stops |
| port / bind / mins | `web-ui-port`, `web-ui-bind`, `web-session-minutes` | edit and press Apply |

Changes take effect immediately — turning Enabled off stops the panel there and
then, and a changed port or address restarts the listener on its own.

`web-start-command` is deliberately **not** editable from the tab or the web
panel. It is the one setting that becomes a command on the host OS, so it stays
in `config/almin/config.json` and `/almin config`.

### What you get

- **Without logging in:** basic metrics only — versions, uptime, a player count,
  TPS. No names, no console, no files, no settings.
- **After logging in:** seven tabs, covering what the in-game admin UI does.

| Tab | What's on it |
|---|---|
| Overview | live metrics, TPS trend, the dashboard rows |
| Console | the server log, tailing, with a command box under it |
| Files | browse, upload, download, rename, delete, fetch a URL, and an editor that opens when you pick a file |
| Activity | what ordinary players have been doing, with a filter |
| Players | who's online, who's been on before, and display-name masks |
| Mods | the mods advertised to joining players, and the jars behind them |
| Settings | every Almin setting, the admin password, and the update check |

The header carries **Stop**, **Restart** and **Start** for the Minecraft server
itself. Stop and Restart always work; Restart hands straight over to
`web-start-command` when supervisor mode is on, and otherwise stops the server
and leaves it to whatever wrapper normally restarts it. **Start** only lights up
in supervisor mode with a start command set — see below.

Two settings are deliberately **not** editable from the web panel:
`web-admin-password-hash`, which has its own field that hashes what you type,
and `web-start-command`, which becomes a command on the host OS.

**What an admin login is worth.** It can write to your `mods/` folder and
restart the server, so it is equivalent to running code on the machine. That is
inherent in what the panel is for, not a gap to be plugged — treat the password
accordingly, and don't put the panel on the internet without TLS.

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
| Activity | `/almin op activity` | what ordinary players have been doing |
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
ordinary players have been doing: joins and leaves, chat, commands, block breaks
and uses, containers opened, PvP hits and deaths.

A masked player joining is announced where only admins see it: the server
console, `almin.log`, and the activity log. Never in chat — a mask exists so
other players see the other name, and "X is really Y" in chat would undo it.
Every admin surface shows the real name first with the mask beside it.

**It never records anyone who can read it.** A player is skipped entirely if
their UUID is on the trusted allowlist, or if they hold moderator permission or
above — which is every vanilla op. So it is a record of the unprivileged, kept
by the privileged, and never a record of the people keeping it.

**Rows expire.** This is data about named people, so it has a deliberate shelf
life rather than accumulating: a day by default, from memory and from
`config/almin/activity.log` alike.

| Setting | Default | Meaning |
|---|---|---|
| `activity-log` | `true` | record at all |
| `activity-retention-minutes` | `1440` | how long a row survives — 5 minutes to 7 days |
| `activity-max-entries` | `20000` | ceiling on the log; oldest rows drop first |
| `activity-blocks` | `true` | block breaks and block use |
| `activity-combat` | `true` | damage taken, hits landed, deaths |
| `activity-items` | `true` | item use, entity interaction, containers |
| `activity-track-seconds` | `5` | position sampling for the map; 0 turns it off |

Recorded: joins and leaves, chat, commands, block breaks and use, containers
opened, item use, entity interaction, hits landed, damage taken and by what,
deaths, and respawns.

### The movement map

The web panel's Activity tab draws one player's path from above — X across, Z
down, the way Minecraft's own maps read — with their actions marked on it.
Hover a marker for what happened and when. Dimensions are drawn separately,
because overworld and nether coordinates share numbers without sharing places.

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

```
/almin mods                          list what's advertised
/almin mods files                    list jars this server holds
/almin mods addfile <id> <file>      advertise a jar the server hosts
/almin mods add <id> <https-url>     advertise one by external link
/almin mods required <id> true       mark it required
/almin mods remove <id>              stop advertising it
/almin mods reload                   re-read mods.json
```

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

**Preferred: host it yourself.** Drop the jar in `config/almin/modfiles/` — or
upload it in the web panel's Mods tab — then advertise it with
`/almin mods addfile`. Players download it straight from your server over the
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
