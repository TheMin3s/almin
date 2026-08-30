# Almin — handoff

Written 2026-08-30. Repo `TheMin3s/almin`, worktree
`.claude/worktrees/remove-lives-keep-admin-826c03`, branch
`claude/remove-lives-keep-admin-826c03`. Last release **v2.27.5** (`1e7d65d`),
pushed to `almin/main`. **Working tree is clean except this file.**

Almin is a Fabric 26.2 server-administration mod: a web panel for a Minecraft
server (activity log, map, players, mods, files, terminal, settings).

---

## 1. What we are trying to accomplish

Six requests, given in one message. The user was explicit about ordering:
**ship 1–5 first as releases, then do 6 on its own, because 6 is huge.**

1. **Redo the AI request system from scratch.** The user's words: "AIs dont
   work at all. I tried OpenAI and my local model and they all fail. Redo the
   entire AI requests system because its not worth continuing to salvage."
   See §5 — this is the one with real unknowns, and it is not yet understood.
2. **Coordinate grid in the 3D views**, so you can see where things happen.
3. **Click a block in the 3D view to see what it is.**
4. **Show the surrounding world in the 3D view.** Right now a scene draws only
   the blocks the events touched, floating in nothing. Also plot **nearby
   players' positions while they build**, with all three axes stored so
   altitude is included — and explicitly **do not** show player positions on
   the 2D map.
5. **On the map, cluster block-place events** that have a 3D view into the
   scene they belong to, with a button somewhere to expand back to the
   individual events.
6. **(Last, large.) Replace the activity map with a BlueMap-style 3D world
   map.** Keep the current 2D activity map as a *legacy* option. The new map
   should be an **optional dependency the admin installs from the Activity
   menu** (so the base mod stays small); once installed it becomes the main
   map, carrying every feature the current map has. The 3D event views should
   then be drawn **in the actual world, with no overlay** — a build appears
   where it is, in context, rather than in a separate isometric box. The user
   sent a screenshot of BlueMap v5.23 rendering a 20 000 × 20 000 world as the
   reference for what it should look like.

---

## 2. What has already changed (this session — all released and pushed)

Every one of these is on `main` and tagged.

| Release | What |
|---|---|
| **v2.27.1** | Save button in the AI settings was wired `onclick=saveAi`, so the click **Event** landed in the `quiet` parameter and the "Saved" branch was never taken — Save looked dead. Also `ai-base-url` was only sent for provider `local`, though the address row is shown for `local`, `custom` and `google`. Both fixed; the show-rule and the save-rule are now one function, `aiHasUrl()`. |
| **v2.27.2** | **The root cause of "Save does nothing".** `/api/config` reads a JSON field called `name`; the AI form and the restricted-mods list posted `key`. The server looked up `""` and answered `Unknown setting: ` with nothing after the colon. v2.27.1's silent-Save had been hiding this error. |
| **v2.27.3** | `jpost` did not catch a failed `fetch`, so an incomplete request left a rejected promise nobody caught and the button kept its last text (reads as "hung, then failed"). Added `why(r)` so a non-200 with no message says so instead of the bare word "failed". Almin now logs every outbound AI call to the Minecraft console: URL + payload size before, status + elapsed after; connect failures and timeouts name the address. |
| **v2.27.4** | Model timeout was 90s; a reverse proxy in front of the panel gives up at 60s, so the proxy always won and returned a bare 504, discarding Almin's diagnosis. New config key **`ai-timeout-seconds`** (default **45**, range 5–600) so Almin answers first. Also: a connection that is *accepted and then silent* is no longer reported as a slow model — it now says "connected, nothing came back, try https:// if that port is TLS". |
| **v2.27.5** | Some newer OpenAI-shaped models reject `max_tokens` and demand `max_completion_tokens`, while everything already deployed understands only `max_tokens`. Almin sends `max_tokens` and, on a 400 whose text names `max_completion_tokens`, renames the field and asks once more. |

Prior to this session, v2.26.0 and v2.27.0 shipped two large batches (server
mods tab, client-mod grouping and first/last-seen, AI scopes/lens/mod-review,
new tracked actions and episode kinds, per-block unfolded logging, separate
build/fight scenes, world-reset detection, Google + custom AI providers). The
git log messages for `e63d8b7` and `244fb22` describe those in full.

---

## 3. Architecture and context decisions worth knowing

**Hard constraints the user has stated. Do not quietly change these.**

- **The web terminal is Minecraft-console only.** The user was offered a full
  OS shell and explicitly declined it. Never add one.
- **Caddy must not use port 80 by default** and must be localized to the
  Minecraft instance — "the minecraft instance shouldnt interfere with the
  rest of the server outside of minecraft".
- **`TrustedOps` may contain only TheMines' UUID**
  `516e51d9-4e6b-4a2f-a282-e0f51f5a20e7`.
- **The activity log excludes admins by default** (people with UUID/op/Almin
  access), configurable via `activity-include-admins`.
- **The mask login message appears on console and in the activity menu only** —
  never in chat.

**Structural facts.**

- **Zero third-party dependencies** beyond Fabric and Gson. HTTP in is the
  JDK's `com.sun.net.httpserver.HttpServer` (4-thread pool); HTTP out is
  `java.net.http.HttpClient`.
- **The whole panel is one Java string.** `WebPage.HTML = String.join("",
  PART1, PARTFILES, PART2, PARTMAP, PARTSEQ, PARTINSIGHT, PART3)`. A single
  Java string *constant* cannot exceed 64 KB, and `A + B` of two constants
  folds back into one constant — hence `String.join`, which is a runtime call.
  If you add a lot of markup, add a new PART rather than growing one.
- **Almin's records live in `config/almin/`, not the world folder.** This is
  deliberate: the log must survive a server that will not start, which is
  exactly when someone needs to read it. The cost is that deleting the world
  does not delete them, which is what `WorldReset` exists to compensate for
  (it fingerprints the overworld seed + level name into `world.json`).
- **`/api/config` takes `{name, value}`, not `{key, value}`.** This caused a
  whole release. Check any new call site.
- **Activity-log folding.** `ActivityLog.NEVER_FOLDED = {place, break, attack,
  hurt, kill, sign}`. Everything else (item, interact, use, container, craft,
  trade, drop) is still coalesced within `COALESCE_MS` (30 s). Folding
  replaced a row with a count *and moved it to the latest position*, which
  destroyed per-block coordinates — that is why the 3D view used to draw one
  cube in ten. `activityMaxEntries` default is 120 000 (ceiling 400 000) with
  a `CONFIG_VERSION` 2 migration off the old 20 000 default.
- **Licensing matters for item 6.** `build.gradle` declares `"license":
  "ARR"`. **BlueMap is AGPL-3.0.** Almin must not link BlueMap code into
  itself. The user's own suggestion — install it as a *separate optional mod
  jar* from the Activity menu and integrate across a boundary (its HTTP
  endpoints / its own web app) — is also the licence-safe design. Keep it that
  way, and raise it with the user before any approach that bundles or
  statically links AGPL code.

---

## 4. Files currently in play

| File | Notes |
|---|---|
| `src/main/java/com/schecks/almin/AiInsights.java` (1418 ln) | **The file item 1 is about.** `Scope`/`Report`/`Lens`/`ModFlag`/`ModReview` records; `summarise()`, `look()`, `review()`; `provider()`, `addressed()`, `endpointProblem()`, `problem()`; `openaiShaped()`, `anthropic()`, `google()`; the shared `post()` at ~line 831 with all the logging and error translation; `timeout()` reads `ai-timeout-seconds`. |
| `src/main/java/com/schecks/almin/WebPage.java` (6782 ln) | The panel. AI settings form ≈ 5300–5620 (`showAi`, `aiFormChanged`, `aiHasUrl`, `aiMissing`, `saveAi`, `toggleAi`, `testAi`, `saveAiKey`). **3D scenes ≈ 4529–4800**: `SCENE_BUILD` / `SCENE_FIGHT` (4529), `sceneKind()` (4545), `sceneOf()` (4563), `paintScene()` (4755). Items 2–5 all live in that block. `jpost`/`why` ≈ 578. |
| `src/main/java/com/schecks/almin/WebUi.java` (3531 ln) | Routes. `handleConfig` ≈ 1670 (the `name` field). `/api/insights`, `/api/insights/find`, `/api/client/review`, `/api/reset`, `/api/servermods*`. |
| `src/main/java/com/schecks/almin/ActivityLog.java` (593) | Rows, folding, `wipe()` vs `clear()`. |
| `src/main/java/com/schecks/almin/Episodes.java` (643) | Episode classification; weights decide priority (hazard 95 is tested above death 92). |
| `src/main/java/com/schecks/almin/WorldSnapshots.java` (883) / `PlayerTracks.java` (279) | Map pictures and position samples. **Item 4's altitude requirement probably lands in `PlayerTracks`** — check whether it currently stores Y at all. |
| `src/main/java/com/schecks/almin/AlminConfig.java` | Key table + `CONFIG_VERSION` migrations. |
| `src/main/java/com/schecks/almin/events/ActivityHooks.java` | Event wiring; `placedThisTick` suppresses the off-hand duplicate. |
| `src/main/resources/almin.mixins.json` | `"required": true` — a listed-but-missing mixin class is a **startup crash**, not a warning. Always `./gradlew build` (not just `compileJava`) before release; that validates it. |

---

## 5. Unresolved — read this before touching the AI code

**The user says OpenAI *and* their local model both fail. That is not yet
explained, and it is the thing to get to the bottom of first.**

What is actually established, with evidence, so you do not re-derive it:

- **Almin does send the request.** `AiWireTests` stands up a real
  `HttpServer` on loopback, points the provider at it, and asserts what
  arrived. Both `local` and `custom` reach `POST /v1/chat/completions`
  carrying the model name as typed. This is not a guess.
- **The user's local endpoint is broken independently of Almin.** Their own
  curl, run on the Minecraft host with no Almin involved:
  `curl -sv --max-time 10 http://192.168.6.255:8000/v1/models` → TCP
  *connects*, request goes out, **0 bytes received in 10 s**. Something is
  listening and not speaking HTTP. Most likely a TLS port addressed as plain
  http, or something else on 8000. Almin cannot beat curl against that.
- **The 504 they saw came from Caddy, not Almin** — proxy 60 s vs Almin's
  then-90 s. Addressed in v2.27.4 by defaulting to 45 s.
- **One model rejected `max_tokens`** and named `max_completion_tokens`.
  Addressed in v2.27.5.

So each individual failure so far had a cause outside the request code, which
is why the code kept getting patched rather than replaced. **But OpenAI
failing too does not fit any of those**, and the user has now asked for a
rewrite rather than another patch. Take that seriously, and start by finding
out what OpenAI actually returns.

Suggested first moves (not yet done):

1. **Get the console lines.** Since v2.27.3 every attempt logs
   `[almin] asking the model at <url> (<n> bytes)` and either
   `[almin] the model answered <status> after <ms> ms` or a named failure.
   Ask for those. They will separate "never left" from "left and was refused".
2. **Add a diagnostic that shows the exact request and the raw response** —
   provider, resolved URL, header names (never values), body, and the
   untouched response bytes — surfaced in the panel. Every round of this has
   been slowed by the panel summarising the failure instead of showing it.
3. **Check the key path end to end** for OpenAI: `saveAiKey` → `/api/ai/key`
   → the key file → `key()` → `hasKey()`. `problem()` short-circuits with
   "No API key set for openai." if `hasKey()` is false, and that failure would
   never reach the network at all — which would look exactly like "it doesn't
   work". This is the single most likely explanation for OpenAI failing and it
   has **not** been checked.
4. Whatever the rewrite's shape, keep `AiWireTests` green and extend it: it is
   the only test that proves bytes leave the machine.

**Inherited and still open from earlier sessions** (each was offered and never
answered by the user):

- v2.0.0 is still published with a broken client jar. Options offered: delete
  that release, or backport a v2.1.2 for the 26.1 line.
- An unintended push to `TheMin3s/lifesmp` `main`. Offered to reset it to
  `414ed89`.
- The Modrinth API calls have never been exercised against the live service.
- A crash log the user mentioned was never provided.
- A reported "mods gui in the client mod" bug was never reproduced.

---

## 6. What I was going to do next

Nothing was in progress — v2.27.5 shipped clean and the tree is empty. The
plan for the six items, in the user's stated order:

1. Diagnose the AI properly (§5) **before** designing the replacement, so the
   rewrite is aimed at the real fault rather than a guess. Then rebuild the
   request layer: one explicit request-builder per provider shape, a raw
   transcript the panel can show, and the wire test extended to every
   provider.
2–5. These are all in the `WebPage.java` scene block (≈4529–4800) plus
   whatever `PlayerTracks` needs for altitude. They are independent of item 1
   and could be done first if the AI diagnosis stalls waiting on the user.
   Ship 2–5 as one or two releases.
6. Only after the rest is released. Expect this to be a multi-session piece of
   work: an optional-dependency installer in the Activity menu, a map
   abstraction so legacy-2D and BlueMap-backed-3D can both satisfy it, and
   re-siting the event scenes into world coordinates. Settle the licence
   boundary (§3) before writing code.

---

## 7. Build and test commands

**`java` is not on `PATH` in this environment.** Every command below needs
this first, or Gradle fails with "Unable to locate a Java Runtime":

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25 && export PATH="$JAVA_HOME/bin:$PATH"
```

Compile, and validate the mixin JSON:

```bash
./gradlew build
```

**The test harness lives in a scratchpad from an earlier session**, not in the
repo. Path:

```
/private/tmp/claude-501/-Users-alex-Documents-claude-schecksmp--claude-worktrees-remove-lives-keep-admin-826c03/8f49df50-466a-40dc-b27d-bfde1188d88c/scratchpad
```

Your session's scratchpad will have a different id — **copy that directory
into yours rather than recreating it.** It holds 28 reflection-driven Java
suites (`*Tests.java`), the Node DOM-stub panel harness (`panelsmoke.js`),
`PageDump.java`, `stub.py`, `cp.txt` (the compiled classpath) and fixtures.

Dump the panel and run the browser-code smoke suite (≈300 assertions):

```bash
java -cp "$(cat $SP/cp.txt):build/classes/java/main:build/resources/main:$SP/out" PageDump "$SP/panel.html" "$SP/panel.js" && node "$SP/panelsmoke.js" "$SP/panel.js"
```

Run every Java suite (`ImageTests` and `RelaunchTests` are skipped — they need
`-Dfixtures=$SP/fixtures` and `-Dharness=$SP/harness` respectively, and are
*not* regressions when they appear to fail without them):

```bash
for f in "$SP"/*Tests.java; do t=$(basename "$f" .java); case $t in ImageTests|RelaunchTests) continue;; esac; javac -cp "$CP" -d "$SP/out" "$f" 2>/dev/null && java -cp "$CP:$SP/out" $t >/dev/null 2>&1 || echo "FAILED $t"; done
```

Regenerate `cp.txt` if it goes stale (do **not** hand-roll it with `find` —
that was done once and broke the suites):

```bash
./gradlew -I $SP/cp.gradle printCp -q
```

Release. `release.sh` requires a clean tree, bumps `mod_version`, builds,
commits, tags, and publishes to GitHub — but it pushes to the **current
branch**, so `main` must be pushed by hand afterwards:

```bash
./release.sh 2.28.0
```

```bash
git push almin HEAD:main
```

---

## 8. Things learned that the code does not tell you

- **Verify every Minecraft API before using it.** `javap` against
  `~/.gradle/caches/fabric-loom/26.2/minecraft-merged.jar` and the fabric-api
  jars. 26.2 renamed things: it is `level.dimension().identifier().getPath()`,
  **not** `.location()`. Guessing has cost several compile cycles.
- **Register world-lifecycle work on `SERVER_STARTED`, not `SERVER_STARTING`** —
  the overworld does not exist yet during *starting*, so seed reads fail.
- **The browser catches what the unit tests cannot.** `stub.py` on port 8791
  serves the panel for real browsing. It has caught a self-referencing mod
  group (`fabric-api` folding into itself), a heading being overwritten after
  render, and misread glyphs. Run it before calling panel work done.
- **Watch for weak assertions.** One test read
  `contains("nothing here \nis proof") || contains("is proof")` — the `||`
  made it trivially true. Prefer `&&` of two specific phrases.
- **A fixture can be the thing that is wrong.** A new `bridge` classification
  "failed" a floor test by correctly calling a 20×1 line a path; the fixture
  was a bad floor, not the rule a bug.
- **`almin.mixins.json` is `required: true`.** A class listed there and never
  written is a startup crash. This nearly shipped once —
  `AdvancementActivityMixin` was lost to a `cd` that failed inside a Bash
  heredoc batch, swallowing the first heredoc. `git status --short` before
  every release, and read the file list.
- **The user's memory note on workflow:** commit → run `release.sh` → verify on
  the live server. They ship confirmed changes without local Minecraft testing,
  because there is no runnable server in this environment.
- **`git stash` is shared across worktrees** and other sessions may pop it.
  Use a WIP commit instead; if you must stash, `git stash push -u -m "<tag>"`,
  capture the SHA, and `git stash apply <sha>`.
- The panel harness rewrites top-level `let ` to `var ` so the sandbox can see
  the page's state; if you add a top-level binding the harness needs to poke,
  that is why it works.
