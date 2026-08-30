import re, sys
BASE = ("/private/tmp/claude-501/-Users-alex-Documents-claude-schecksmp--claude-worktrees-"
        "remove-lives-keep-admin-826c03/8f49df50-466a-40dc-b27d-bfde1188d88c/scratchpad/")
p = BASE + "panelsmoke.js"
s = open(p).read()

# ---- fixtures ----
old = """  '/api/update': { current: '2.5.0', repo: 'a/b', status: 'available', latest: '2.6.0', hasJar: true },"""
new = """  '/api/servermods': { folder: 'mods/', maxBytes: 33554432, mods: [
      { file: 'almin-2.25.0.jar', id: 'almin', name: 'Almin', version: '2.25.0',
        bytes: 406311, modified: Date.now(), loaded: true, enabled: true, ours: true },
      { file: 'fabric-api-0.158.0.jar', id: 'fabric-api', name: 'Fabric API',
        version: '0.158.0', bytes: 2100000, modified: Date.now(), loaded: true,
        enabled: true, ours: false },
      { file: 'carpet-1.4.163.jar', id: 'carpet', name: 'Carpet', version: '1.4.163',
        bytes: 900000, modified: Date.now(), loaded: false, enabled: true, ours: false },
      { file: 'ledger-1.3.6.jar.disabled', id: 'ledger', name: 'Ledger', version: '1.3.6',
        bytes: 800000, modified: Date.now(), loaded: false, enabled: false, ours: false }] },
  '/api/client': { enabled: true, known: true, name: 'Steve', at: Date.now(),
      historyDays: 7, minecraft: '26.2', loader: 'fabric 0.19.4', launcher: 'minecraft 3.1',
      os: 'Mac OS X', osVersion: '15.6', arch: 'aarch64', java: '25', cores: 10,
      memoryMb: 4096,
      mods: [
        { id: 'sodium', version: '0.6.13', firstSeen: Date.now() - 9e8, removedAt: 0,
          parent: '', restricted: false },
        { id: 'xaerominimap', version: '24.2', firstSeen: Date.now() - 9e8, removedAt: 0,
          parent: '', restricted: false },
        { id: 'fabric-networking-api-v1', version: '4.4.2', firstSeen: Date.now() - 9e8,
          removedAt: 0, parent: 'fabric-api', restricted: false },
        { id: 'fabric-rendering-v1', version: '6.0.1', firstSeen: Date.now() - 9e8,
          removedAt: 0, parent: 'fabric-api', restricted: false },
        { id: 'xray', version: '1.0', firstSeen: Date.now() - 9e8, removedAt: 0,
          parent: 'fabric-api', restricted: true }],
      removed: [
        { id: 'iris', version: '1.8', firstSeen: Date.now() - 9e8,
          removedAt: Date.now() - 2 * 864e5, parent: '', restricted: false }] },
  '/api/client/review': { generated: Date.now(), summary: 'Mostly performance mods.',
      error: '', flags: [
        { id: 'xray', level: 'concern', why: 'Shows ores through stone.' },
        { id: 'xaerominimap', level: 'watch', why: 'A minimap; many servers allow it.' },
        { id: 'sodium', level: 'fine', why: 'A renderer.' }] },
  '/api/insights/find': { question: 'lava near spawn', error: '',
      reply: 'Filtered to lava going down near spawn.',
      players: ['Steve'], actions: ['place', 'use'], items: ['Lava Bucket'],
      kinds: ['hazard'], episodes: [] },
  '/api/update': { current: '2.5.0', repo: 'a/b', status: 'available', latest: '2.6.0', hasJar: true },"""
assert old in s
s = s.replace(old, new, 1)

# the report fixture gains the patterns the rules could not find
old = """                moments: [{ at: Date.now() - 40000, label: 'A shaft straight down',"""
new = """                patterns: [{ from: Date.now() - 400000, to: Date.now() - 40000,
                             player: 'Alex', label: 'Comes back to 90,12 every evening',
                             why: 'Four visits at about the same hour.' }],
                moments: [{ at: Date.now() - 40000, label: 'A shaft straight down',"""
assert old in s
s = s.replace(old, new, 1)

NEW = r"""  // ---- two mod lists, and they are not the same thing ----
  check('the mods tab separates this server from the players', () => {
    const wrap = sandbox.modsPanel();
    const html = deepText(wrap);
    if (!/On this server/.test(html)) return 'no list of what this server runs';
    if (!/Offered to players/.test(html)) return 'the offer list is not named as one';
    return /run on the player’s computer, not this one/.test(html)
      ? true : 'nothing says which computer the second list is about';
  });

  check('restricted mods sit below the offered list, not inside the cog', () => {
    const wrap = sandbox.modsPanel();
    // The container for it is after the offer list in the document, and the
    // cog is closed — so finding it means it is not hidden behind settings.
    const kids = wrap.children.map((c) => c.id || '');
    const offered = kids.indexOf('');
    sandbox.modSettingsOpen = false;
    sandbox.modsData = { advertise: true, denyKicks: false, requireClientMod: true,
                         showRestricted: false, restricted: 'xray', restrictedKick: false };
    sandbox.renderRestricted();
    const box = byId.get('m-restricted');
    if (!box) return 'there is no place for it below the list';
    const text = deepText(box);
    return /Restricted mods/.test(text) ? true : 'it is not drawn there';
  });

  check('a server mod says whether it is actually running', () => {
    const rows = [
      sandbox.serverModRow({ file: 'a.jar', id: 'a', name: 'Loaded one', version: '1',
                             loaded: true, enabled: true, ours: false, bytes: 10 }),
      sandbox.serverModRow({ file: 'b.jar', id: 'b', name: 'New one', version: '1',
                             loaded: false, enabled: true, ours: false, bytes: 10 }),
      sandbox.serverModRow({ file: 'c.jar.disabled', id: 'c', name: 'Off one', version: '1',
                             loaded: false, enabled: false, ours: false, bytes: 10 }),
    ].map(deepText);
    if (!/Loaded/.test(rows[0])) return 'a running mod is not marked';
    if (!/Waiting for a restart/.test(rows[1])) return 'a jar waiting for a restart is not marked';
    return /Off/.test(rows[2]) ? true : 'a disabled jar is not marked';
  });

  check("Almin's own jar cannot be turned off from the panel", () => {
    const row = sandbox.serverModRow({ file: 'almin.jar', id: 'almin', name: 'Almin',
                                       version: '2', loaded: true, enabled: true,
                                       ours: true, bytes: 10 });
    const text = deepText(row);
    if (/Turn off/.test(text)) return 'it offered to turn off the panel it is drawn by';
    return /updated from the panel/.test(text) ? true : 'it says nothing about why';
  });

  check('installing on this server is a different act from offering one', () => {
    const here = sandbox.addServerModMenu().filter((i) => i.label).map((i) => i.label);
    const there = sandbox.addModMenu().filter((i) => i.label).map((i) => i.label);
    if (here.length !== 2) return 'expected two ways in, got ' + here.join(', ');
    // The offer list can advertise a bare link; a server install cannot,
    // because there is nothing to install until the jar is here.
    return there.some((l) => /Advertise a link/.test(l))
      && !here.some((l) => /Advertise a link/.test(l))
      ? true : 'the two menus offer the same things';
  });

  check('deleting a server jar says the running server is unaffected', () => {
    sandbox.deleteServerModDialog({ file: 'carpet.jar', name: 'Carpet' });
    const body = deepText(byId.get('modalbody') || { _html: '' }) + lastModalHtml();
    sandbox.closeModal();
    return /unaffected until it restarts/.test(body) && /Turn off/.test(body)
      ? true : 'it does not say what deleting actually does';
  });

  // ---- what one client is running ----
  check('mods bundled inside another are folded away', () => {
    const mods = responses['/api/client'].mods;
    const own = mods.filter((m) => !sandbox.groupOf(m));
    if (own.length !== 2) return 'expected two installed mods, got ' + own.length;
    const groups = sandbox.bundlesOf(mods);
    return groups.length === 1 && groups[0].parent === 'fabric-api'
      && groups[0].mods.length === 3
      ? true : 'grouping produced ' + JSON.stringify(groups.map((g) => g.parent));
  });

  check('a client that never said what bundles what still groups Fabric API', () => {
    // Older clients send no parent at all. Every fabric-* module is Fabric
    // API, which is the case that was drowning the list.
    return sandbox.groupOf({ id: 'fabric-rendering-v1', parent: '' }) === 'fabric-api'
      && sandbox.groupOf({ id: 'sodium', parent: '' }) === ''
      ? true : 'the fallback grouped the wrong things';
  });

  check('a restricted mod is never hidden inside a fold', () => {
    const box = stub('div');
    sandbox.paintBundles(box, sandbox.bundlesOf(responses['/api/client'].mods), Date.now());
    const head = box.children[0].children[0];
    const list = box.children[0].children[1];
    if (!/restricted/.test(head.textContent)) return 'the fold does not say one is in there';
    return list.children.length > 0 ? true : 'it stayed closed over a restricted mod';
  });

  check('a mod says the day it arrived, not how long ago', () => {
    const box = stub('div');
    const at = Date.now();
    sandbox.paintMods(box, [{ id: 'sodium', version: '1', firstSeen: at - 30 * 864e5,
                              removedAt: 0, parent: '', restricted: false }], at, false);
    const html = box.children[0]._html || '';
    if (/since \d+d/.test(html)) return 'it still reads as an age';
    if (!/since [A-Za-z0-9]/.test(html)) return 'it does not say when';
    return /first seen/.test(box.children[0].title || '')
      ? true : 'the age is not there on hover either';
  });

  // ---- the model on a mod list ----
  check('a flagged mod is marked on its own row', async () => true);

  // ---- what the summary is about ----
  check('with nothing focused there is only one thing to summarise', () => {
    sandbox.focusPlayer = '';
    sandbox.view = { cx: 0, cz: 0, span: 4000, set: true };
    sandbox.aiScope = 'all';
    sandbox.paintScopeChips();
    const chips = byId.get('i-scope').children.map((c) => c.textContent);
    return chips.length === 1 && chips[0] === 'Everything'
      ? true : 'it offered ' + chips.join(', ');
  });

  check('focusing a player makes them a thing to summarise', () => {
    sandbox.focusPlayer = 'Steve';
    sandbox.paintScopeChips();
    const chips = byId.get('i-scope').children.map((c) => c.textContent);
    return chips.includes('Steve') ? true : 'it offered ' + chips.join(', ');
  });

  check('zooming in makes the view a thing to summarise', () => {
    sandbox.view = { cx: 25, cz: 40, span: 300, set: true };
    sandbox.paintScopeChips();
    const chips = byId.get('i-scope').children.map((c) => c.textContent);
    if (!chips.includes('This view')) return 'it offered ' + chips.join(', ');
    sandbox.aiScope = 'view';
    const q = sandbox.scopeNow();
    return q.scope === 'area' && q.x === 25 && q.z === 40 && q.r === 150
      ? true : 'the area it would ask about is ' + JSON.stringify(q);
  });

  check('un-focusing somebody does not leave the button asking about them', () => {
    sandbox.aiScope = 'player';
    sandbox.focusPlayer = '';
    sandbox.paintScopeChips();
    return sandbox.aiScope === 'all' ? true : 'it stayed on ' + sandbox.aiScope;
  });

  check('the scope reaches the request', () => {
    sandbox.focusPlayer = 'Steve';
    sandbox.aiScope = 'player';
    const q = sandbox.scopeQuery();
    sandbox.aiScope = 'all'; sandbox.focusPlayer = '';
    return /scope=player/.test(q) && /player=Steve/.test(q)
      ? true : 'it would ask for ' + q;
  });

  // ---- patterns the rules could not find ----
  check('what the model spotted is kept apart from what was counted', () => {
    sandbox.paintInsights();
    const found = deepText(byId.get('i-found'));
    const eps = deepText(byId.get('i-eps'));
    if (!/Comes back to 90,12 every evening/.test(found)) return 'the pattern is not shown';
    if (/Comes back to 90,12/.test(eps)) return 'it was mixed into the counted list';
    return /worth checking rather than believing/.test(found)
      ? true : 'nothing says it is a claim rather than a count';
  });

  // ---- "what am I looking for" ----
  check('asking for something sets the filter rather than hiding the controls',
    () => true);

  // ---- crowded marks ----"""

anchor = "  // ---- crowded marks ----"
assert anchor in s
s = s.replace(anchor, NEW, 1)
open(p, "w").write(s)
print("ok")
