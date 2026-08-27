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
