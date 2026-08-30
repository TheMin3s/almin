# Optional BlueMap world renderer

Almin can use a separately installed BlueMap server mod as the terrain renderer
for the web panel's Activity map. BlueMap is optional: it is not a Gradle
dependency, none of its classes or web assets are inside Almin, and removing it
puts the Activity tab back on Almin's legacy 2D map.

## Install

Open **Activity** and press **Install 3D world map**. Almin resolves the Fabric
build for this server's exact Minecraft version through Modrinth, places the jar
in `mods/`, and prepares the web boundary. Restart the Minecraft server once so
Fabric loads BlueMap and BlueMap starts rendering the world.

When it is ready, **3D world** becomes the default map. **Legacy 2D** beside it
always switches back to Almin's recorded snapshot map; the choice is local to
that browser.

BlueMap needs time and disk space to render a large world. Its own commands and
configuration control that work. Almin does not generate or copy its tiles.

## What Almin configures

Almin makes three narrow, instance-local changes:

- `config/bluemap/webserver.conf` binds BlueMap to `127.0.0.1` on a free port
  from 8100–8199, never Almin's own panel port.
- `config/bluemap/webapp.conf` registers `js/almin-bridge.js` through BlueMap's
  custom-script setting. Other BlueMap settings and scripts are preserved.
- `bluemap/web/js/almin-bridge.js` is an Almin-owned browser bridge. It receives
  filtered activity state from the parent panel and creates markers through the
  web app's `window.BlueMap` surface.

The public browser never reaches that loopback port. Requests to `/bluemap/`
first pass Almin's admin session and are then streamed to BlueMap by a separate
worker pool, so tile and SSE streams cannot consume the panel's four control
workers. The existing Caddy file needs no second public listener: its normal
reverse proxy sends `/bluemap/` to Almin with everything else.

A configured BlueMap that was already running needs one restart before Almin
will proxy it. This ensures the loopback bind is actually in force, rather than
merely written for a future start.

## Activity features in 3D

The terrain and camera are BlueMap's. The activity data and controls remain
Almin's: dimensions, timeline and playback, filters, focus, paths and faces,
action/player colours, clustering, fading, sequence markers, coordinate grid,
side list, online strip, fullscreen, and refresh interval.

Travelled player paths fade segment by segment on the same clock as movement
icons. During playback, the faint guide shows only the path still ahead, so an
old segment cannot remain visible underneath after its activity has faded.

Selecting a build/fight scene while **3D world** is active does not open the
isometric dialog. Recorded blocks, blows, and nearby player samples are placed
at their actual X/Y/Z coordinates in the rendered world. **Expand build events**
does the same for every visible build scene. The legacy renderer keeps its
isometric scene, including live surrounding-world context.

Clicking an activity mark shows its recorded detail and exact coordinates.
Clicking BlueMap terrain asks the live server for the exposed block at that
coordinate without loading the chunk; a distant rendered-but-unloaded chunk is
reported as unavailable rather than being generated for the web panel.

## Removal and troubleshooting

Turn off or remove BlueMap from the server's **Mods** tab and restart. Almin
does not delete BlueMap's rendered tiles or configuration. The legacy map works
throughout and requires no BlueMap files.

If Activity says BlueMap is loaded but not answering, check BlueMap's own server
log and confirm it has produced `bluemap/web/settings.json`. If another local
service occupied its configured port, press **Connect** again before restarting;
Almin will choose a free instance-local port without changing any external
service.
