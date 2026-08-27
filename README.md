I don't recommend this for actual servers it prob has 10,000 backdoors cuz i made it with claude bc idk how to code fabric mods

This mod includes an operator bypass function which was meant specifically for our server, 
which automatically allows a UUID full OP access, which was obviously made with the intention of only local use.


## Almin

A Fabric 26.1.2 mod that adds in-game server administration tools: a live console viewer,
a file browser, a file editor, uploads/downloads, display-name masks and a self-updater —
all driven from `/almin`, with the sensitive parts gated behind a hardcoded UUID allowlist.

NOT MEANT FOR USAGE IN PUBLIC SERVERS

COMES WITH ABSOLUTELY NO WARRANTY

AUTHOR IS NOT RESPONSIBLE FOR ANY DAMAGES CAUSED AS A RESULT OF THE USAGE OF THIS PROGRAM


## Web panel

Almin also serves a web panel. It has two tiers:

- **Public (no login):** a small set of basic metrics only — versions, uptime, a
  player count and TPS. No names, no console, no files, no settings.
- **Admin (password login):** the full dashboard, the live console, a command
  terminal (runs server commands as the console, same as `/almin op cmd`), and a
  filesystem browser/editor with the same write rules as `/almin op`.

The panel binds to `127.0.0.1` by default and speaks plain HTTP — it is meant to
sit behind a TLS-terminating reverse proxy. Set an admin password in game before
anyone can log in:

```
/almin op web password <password>
```

The password is stored only as a PBKDF2 hash. Five wrong attempts lock a client
out for 15 minutes. Config keys: `web-ui-enabled`, `web-ui-port` (chosen at first
start), `web-ui-bind`, `web-public-metrics`, `web-session-minutes`.

### HTTPS on a domain (Caddy)

A starter `config/almin/Caddyfile` is written on first start. It is deliberately
scoped to this instance and **never binds port 80 or 443**, so it won't collide
with anything else on the host: the panel is published on its own HTTPS port and
the certificate is obtained with the DNS-01 challenge (which needs no inbound
port). Fill in your domain, HTTPS port, and DNS provider credentials, then:

```
caddy run --config config/almin/Caddyfile
```

Because the admin tier is only served to loopback callers, the panel is reachable
from the internet only through the proxy; a direct connection that bypassed it is
refused the login. There is no TLS without the proxy — do not expose the panel's
own port directly.

**Security note:** an admin login grants remote control of the server (terminal +
file writes). Use a strong password, keep the panel behind the proxy, and treat
the URL as sensitive.

### Start / stop from the panel

Once logged in the header has a **Stop server** button (a graceful stop, same as
`/stop`). What happens next depends on `web-supervisor`:

- **`web-supervisor false` (default)** — stopping lets the JVM exit, which is
  what an external wrapper watches for in order to restart the server. This is
  the behaviour `/almin op restart` has always relied on. The panel goes down
  with the server, so there is no Start button.
- **`web-supervisor true`** — the panel's threads keep the JVM alive after the
  server stops, so the page stays up and shows a **Start server** button. Start
  runs `web-start-command` in the server directory, then hands the port over by
  shutting this JVM down; the new server's own panel takes over a few seconds
  later.

```
/almin config web-supervisor true
/almin config web-start-command ./start.sh
```

**Do not enable supervisor mode if something else already restarts your server**
(systemd, a `while true` wrapper, a panel host). Two supervisors means either a
double start or no start at all. And if `web-start-command` is wrong, Start takes
the panel down with it and nothing comes back — test the command by hand first.

The Minecraft server cannot be restarted *inside* the same JVM: its bootstrap
runs once per process. That is why Start launches a fresh process rather than
rebooting the world in place.

## Which jar do I want?

Each release ships two:

| File | Put it in | Contains |
|---|---|---|
| `almin-<version>-server.jar` | your server's `mods/` | everything: commands, mixins, the web panel |
| `almin-<version>-client.jar` | your client's `mods/` | only the screens the server opens (dashboard, console viewer, file browser, nano editor) |

The client jar declares `environment: "client"` and registers no commands, no
mixins and no web panel — it exists purely to render what the server sends, so
a player running it gains nothing on their own machine. The server jar contains
no client code at all.

You don't need the client jar to use Almin: a vanilla client gets the same
information as chat output. It only buys you the graphical screens.

The self-updaters pick their jar out of a release by the `server` / `client` in
the filename, so those names matter — see `UpdateChecker.SERVER_JAR`.

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
