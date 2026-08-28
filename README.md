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

If the port it wants is already taken it moves to the next free one and saves
that, so a second Minecraft instance on the same machine no longer knocks the
first one's panel out.

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
- **After logging in:** the full dashboard, live console, a command terminal,
  a file browser/editor, and mod management.

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
