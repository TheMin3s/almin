// Drives the panel's own script under a minimal DOM, so a typo'd id or a
// missing function shows up here rather than as a blank page in a browser.
// Not a browser: it checks that the code runs and asks for the right things.
const fs = require('fs');
const vm = require('vm');

// Top-level `let` in a vm script stays in lexical scope, invisible from the
// sandbox object — so the harness could not put the page on a given tab, and
// every render silently drew the dashboard. Only the declaration keyword is
// changed; the code under test is otherwise byte-for-byte the shipped page.
const src = fs.readFileSync(process.argv[2], 'utf8')
  .replace(/^(\s*)let /gm, '$1var ');

let failures = [];
const byId = new Map();
// Registered intervals, so a countdown can be stepped rather than waited on.
const intervals = [];
// Playback advances by real elapsed time, so the harness owns the clock and
// moves it forward exactly as far as the frames it fires.
const RealDate = Date;
let clock = RealDate.now();
function FakeDate(...a) { return a.length ? new RealDate(...a) : new RealDate(clock); }
FakeDate.now = () => clock;
FakeDate.prototype = RealDate.prototype;
Object.setPrototypeOf(FakeDate, RealDate);
function tick(n, stepMs) {
  for (let i = 0; i < n; i++) {
    clock += (stepMs === undefined ? 50 : stepMs);
    for (const iv of intervals) if (iv) iv.fn();
  }
}

// The key the filter uses to name one particular thing, which is a NUL so it
// cannot collide with a block name.
const SEP = String.fromCharCode(0);

// The filter panel is built out of elements rather than markup, so reading it
// back means walking the tree the way a browser would.
function deepText(el) {
  if (!el || typeof el !== 'object') return '';
  let out = (el._html || '') + ' ' + (el.textContent || '');
  for (const kid of el.children || []) out += ' ' + deepText(kid);
  return out;
}

function headSize() {
  const html = byId.get('t-map')._html || '';
  const m = html.match(/class="thead[^"]*"[^>]*>[\s\S]{0,160}?width="([\d.]+)"/);
  return m ? +m[1] : 0;
}

function stub(tag) {
  const el = {
    tagName: tag, id: '', className: '', textContent: '', title: '', value: '',
    type: '', min: '', max: '', disabled: false, checked: false, files: [],
    style: {}, children: [], _html: '',
    alt: '', src: '', loading: '', referrerPolicy: '', parentNode: null,
    append(...k) { for (const x of k) { if (x && typeof x === 'object') x.parentNode = this; }
                   this.children.push(...k); },
    appendChild(k) { if (k && typeof k === 'object') k.parentNode = this;
                     this.children.push(k); return k; },
    replaceChild(neu, old) { const i = this.children.indexOf(old);
                             if (i >= 0) this.children[i] = neu; return old; },
    closest(sel) { return this._closest === undefined ? null : this._closest; },
    insertAdjacentHTML(_pos, html) { register(html); this._html += html; },
    addEventListener() {},
    removeEventListener() {},
    setAttribute(k, v) { if (k === 'id') { this.id = v; byId.set(v, this); } },
    getAttribute() { return 'x'; },
    querySelector() { return stub('div'); },
    querySelectorAll() { return []; },
    getBoundingClientRect() { return { left: 0, top: 0, right: 200, bottom: 32,
                                       width: 200, height: 32 }; },
    focus() {}, remove() {}, select() {}, click() {},
    clientWidth: 1200, clientHeight: 600,
    classList: { add() {}, remove() {}, toggle() {} },
    setPointerCapture() {},
    getBBox() { return { x: 10, y: 10, width: 6, height: 6 }; },
    scrollTop: 0, scrollHeight: 0, clientHeight: 0,
  };
  Object.defineProperty(el, 'innerHTML', {
    get() { return this._html; },
    set(v) { this._html = v; this.children = []; register(v); },
  });
  return el;
}

// Anything the markup declares an id for has to be findable afterwards.
// Listeners bound to the document, so a leaked one can be counted.
const docListeners = [];

function register(html) {
  for (const m of String(html).matchAll(/id="([^"]+)"/g)) {
    if (!byId.has(m[1])) byId.set(m[1], stub('div'));
  }
}

const document = {
  addEventListener: (type, fn) => { docListeners.push({ type, fn }); },
  removeEventListener: (type, fn) => {
    const i = docListeners.findIndex((l) => l.type === type && l.fn === fn);
    if (i >= 0) docListeners.splice(i, 1);
  },
  createElement: (t) => {
    const e = stub(t);
    // Assigning .id later must still register it.
    let id = '';
    Object.defineProperty(e, 'id', {
      get: () => id,
      set: (v) => { id = v; byId.set(v, e); },
    });
    return e;
  },
  createDocumentFragment: () => stub('#fragment'),
  querySelector: () => stub('div'),
  querySelectorAll: () => [],
  getElementById: (id) => {
    if (!byId.has(id)) {
      failures.push('getElementById("' + id + '") found nothing');
      byId.set(id, stub('div'));   // keep going, collect every miss
    }
    return byId.get(id);
  },
};
// Menus and overlays are appended to the body, not to a panel.
document.body = stub('body');

// The page's own fixed elements.
for (const id of ['status', 'statustext', 'age', 'srvstop', 'srvstart', 'srvrestart',
                  'logout', 'nav', 'main']) {
  byId.set(id, stub('div'));
}

const asked = new Set();
const responses = {
  '/api/session': { authed: true, secure: true, encrypted: true, passwordSet: true,
                    publicMetrics: true, serverRunning: true, supervisor: false, canStart: true,
                    canRelaunch: true, restarting: false, version: '2.12.0', heads: true,
                    startCommand: '/usr/bin/java -jar server.jar nogui' },
  '/api/state': { rows: [{ kind: 0, label: 'Server', value: '', accent: '' },
                          { kind: 1, label: 'Version', value: '26.2', accent: '' }],
                  metrics: { tps: 20, tpsTarget: 20, mspt: 3.1, players: 1, maxPlayers: 20,
                             memUsed: '1 GB', memMax: '4 GB', memPct: 25, uptime: '2h',
                             chunks: 400, entities: 80 },
                  serverRunning: true, generated: Date.now() },
  '/api/public': { rows: [], metrics: null, generated: Date.now() },
  '/api/console': { lines: ['[00:00:00] [Server thread/INFO]: hello'] },
  '/api/files': { writable: true, deletable: true,
                  roots: 'mods,config,resourcepacks,shared',
                  deleteRoots: 'mods,config,resourcepacks,shared,world',
                  entries: [
    { name: 'config', directory: true, size: -1, modified: Date.now() - 90000,
      items: 4, writable: true, deletable: true },
    { name: 'a.json', directory: false, size: 12, modified: Date.now() - 5000,
      items: -1, writable: true, deletable: true },
    { name: 'sodium.jar', directory: false, size: 900000, modified: Date.now() - 800000,
      items: -1, writable: true, deletable: true },
    { name: 'server.jar', directory: false, size: 50 * 1024 * 1024,
      modified: Date.now() - 9000000, items: -1, writable: false, deletable: false }] },
  '/api/file': { content: '{}' },
  '/api/mods': { mods: [
      { id: 'sodium', name: 'Sodium', version: '1.0', url: '', file: 's.jar',
        sha256: 'abc', required: true, kind: 'jar', source: 'modrinth',
        page: 'https://modrinth.com/mod/sodium', icon: true },
      { id: 'handmade', name: '', version: '', url: 'https://example.test/x.jar',
        sha256: '', required: false, kind: 'link', source: 'link', page: '', icon: false }],
    unusedFiles: ['leftover.jar'], maxOffers: 64,
    advertise: true, denyKicks: false, requireClientMod: false },
  '/api/mods/files': { files: ['s.jar'] },
  '/api/mods/modrinth': { hits: [{ slug: 'modmenu', title: 'Mod Menu',
                                   description: 'adds a mod list', downloads: 12345678,
                                   icon: 'https://cdn.modrinth.com/data/x/icon.png',
                                   page: 'https://modrinth.com/mod/modmenu' }],
                          gameVersion: '26.2', ok: true, message: 'Added Mod Menu' },
  '/api/config': { keys: [
      { name: 'auto-update', description: 'd', type: 'BOOL', min: 0, max: 0, value: 'true',
        editable: true, reloadsPanel: false },
      { name: 'web-ui-port', description: 'd', type: 'INT', min: 0, max: 65535, value: '8123',
        editable: true, reloadsPanel: true },
      { name: 'update-repo', description: 'd', type: 'TEXT', min: 0, max: 0, value: 'a/b',
        editable: true, reloadsPanel: false },
      { name: 'web-start-command', description: 'd', type: 'TEXT', min: 0, max: 0, value: '',
        editable: false, reloadsPanel: false }],
    writableRoots: 'mods,config',
    deletableRoots: 'mods,config,resourcepacks,shared,world' },
  '/api/activity': { admins: { ok: true, includeAdmins: false, temporary: false,
                               configured: false },
                     rows: [{ at: Date.now() - 60000, player: 'Steve', action: 'break',
                              detail: 'Stone', where: 'overworld 1,2,3', count: 12,
                              uuid: '00000000-0000-0000-0000-0000000000aa' },
                            { at: Date.now() - 10000, player: 'Alex', action: 'chat',
                              detail: 'hello', where: 'overworld 4,5,6', count: 1,
                              uuid: '516e51d9-4e6b-4a2f-a282-e0f51f5a20e7' }],
                     total: 2, enabled: true, blocks: true, retentionMinutes: 1440 },
  '/api/track': { players: { Steve: 42, Alex: 9 }, trackSeconds: 5, player: 'Steve',
                  points: [{ at: Date.now() - 60000, dim: 'overworld', x: 10, y: 64, z: 20 },
                           { at: Date.now() - 30000, dim: 'overworld', x: 40, y: 70, z: 55 },
                           { at: Date.now(), dim: 'the_nether', x: 5, y: 40, z: 8 }],
                  // The all=1 shape shares this route; the harness strips the query.
                  all: true, from: Date.now() - 4 * 3600e3, to: Date.now(),
                  now: Date.now(), afkSeconds: 20, leftPlayerHours: 24,
                  ids: { Steve: '00000000-0000-0000-0000-0000000000aa',
                         Alex: '00000000-0000-0000-0000-0000000000bb' },
                  online: [
                    { name: 'Steve', uuid: '00000000-0000-0000-0000-0000000000aa',
                      afk: false, stillSince: Date.now() - 3000, mask: '',
                      dim: 'overworld', x: 10, y: 64, z: 20 },
                    { name: 'Alex', uuid: '00000000-0000-0000-0000-0000000000bb',
                      afk: true, stillSince: Date.now() - 300000, mask: 'Ghost',
                      dim: 'overworld', x: 90, y: 64, z: 12 }],
                  admins: { ok: true, includeAdmins: false, temporary: false, configured: false },
                  tracks: {
                    Steve: [{ at: Date.now() - 4 * 3600e3, dim: 'overworld', x: -80, y: 64, z: -60 },
                            { at: Date.now() - 3.99 * 3600e3, dim: 'overworld', x: -40, y: 64, z: -20 },
                            { at: Date.now() - 60000, dim: 'overworld', x: 10, y: 64, z: 20 },
                            { at: Date.now() - 30000, dim: 'overworld', x: 40, y: 70, z: 55 },
                            { at: Date.now(), dim: 'the_nether', x: 5, y: 40, z: 8 }],
                    Alex: [{ at: Date.now() - 50000, dim: 'overworld', x: 90, y: 64, z: 12 },
                           { at: Date.now() - 10000, dim: 'overworld', x: 30, y: 64, z: 70 }] },
                  actions: [{ at: Date.now() - 45000, player: 'Steve', mask: '', action: 'break',
                              detail: 'Stone', dim: 'overworld', x: 22, y: 64, z: 33, count: 4 },
                            { at: Date.now() - 20000, player: 'Steve', mask: '', action: 'chat',
                              detail: 'anyone seen my pickaxe', dim: 'overworld',
                              x: 24, y: 64, z: 35, count: 1 },
                            { at: Date.now() - 15000, player: 'Alex', mask: 'Ghost', action: 'afk',
                              detail: 'stopped moving', dim: 'overworld',
                              x: 90, y: 64, z: 12, count: 1 },
                            { at: Date.now() - 5000, player: 'Alex', mask: 'Ghost', action: 'place',
                              detail: 'Dirt', dim: 'overworld', x: 31, y: 64, z: 69, count: 2 },
                            // Hours old, so a fade window has something to
                            // forget. Alongside the early track points rather
                            // than out in the quiet stretch, which is there to
                            // be found by the gap tests.
                            { at: Date.now() - 3.995 * 3600e3, player: 'Steve', mask: '',
                              action: 'break', detail: 'Stone', dim: 'overworld',
                              x: 26, y: 64, z: 36, count: 2 },
                            { at: Date.now() - 3.993 * 3600e3, player: 'Steve', mask: '',
                              action: 'chat', detail: 'long ago', dim: 'overworld',
                              x: 27, y: 64, z: 37, count: 1 },
                            { at: Date.now() - 3.991 * 3600e3, player: 'Alex', mask: 'Ghost',
                              action: 'place', detail: 'Dirt', dim: 'overworld',
                              x: 60, y: 64, z: 60, count: 1 },
                            // Four marks in one spot, so there is something to
                            // group when the map is zoomed out.
                            { at: Date.now() - 44000, player: 'Steve', mask: '', action: 'break',
                              detail: 'Stone', dim: 'overworld', x: 23, y: 64, z: 33, count: 7 },
                            { at: Date.now() - 43000, player: 'Steve', mask: '', action: 'break',
                              detail: 'Stone', dim: 'overworld', x: 23, y: 63, z: 34, count: 5 },
                            { at: Date.now() - 42000, player: 'Steve', mask: '', action: 'place',
                              detail: 'Torch', dim: 'overworld', x: 24, y: 64, z: 33, count: 1 }] },
  '/api/map': { every: 30, shots: [
      { at: Date.now() - 55000, dim: 'overworld', minX: -200, minZ: -200, span: 384 },
      { at: Date.now() - 15000, dim: 'overworld', minX: -180, minZ: -190, span: 384 }] },
  '/api/bluemap': { installed: false, enabled: false, loaded: false, configured: false,
                    ready: false, restartRequired: false, port: 8100, version: '',
                    message: 'Install BlueMap to use the 3D world map.', path: '/bluemap/' },
  '/api/scene/context': { dim: 'overworld', x: 23, z: 33, radius: 12,
      minY: 55, maxY: 75, truncated: false,
      blocks: [{ x: 20, y: 63, z: 31, what: 'Stone' },
               { x: 21, y: 64, z: 31, what: 'Grass Block' }] },
  '/api/insights': {
      episodes: [{ kind: 'shaft', headline: 'Dug a shaft from y 64 down to y 11',
                   player: 'Steve', mask: '', uuid: '00000000-0000-0000-0000-0000000000aa',
                   dim: 'overworld', from: Date.now() - 60000, to: Date.now() - 40000,
                   x: 23, y: 37, z: 33, events: 41, weight: 44, tool: 'pickaxe' },
                 { kind: 'pace', headline: 'Back and forth around 90,12 for 6 minutes',
                   player: 'Alex', mask: 'Ghost', uuid: '00000000-0000-0000-0000-0000000000bb',
                   dim: 'overworld', from: Date.now() - 400000, to: Date.now() - 40000,
                   x: 90, y: 64, z: 12, events: 12, weight: 42, tool: 'loop' }],
      ai: { enabled: true, provider: 'local', model: 'qwen2.5:3b',
            baseUrl: 'http://127.0.0.1:11434/v1', sendChat: true, autoMinutes: 0,
            timeoutSeconds: 45, hasKey: false, problem: '' },
      report: { generated: Date.now() - 1000, summary: 'Steve spent the evening underground.',
                model: 'qwen2.5:3b', provider: 'local', error: '',
                sequences: [{ at: Date.now() - 40000, player: 'Steve',
                              means: 'Getting down to the ore layer under the base.' }],
                patterns: [{ from: Date.now() - 400000, to: Date.now() - 40000,
                             player: 'Alex', label: 'Comes back to 90,12 every evening',
                             why: 'Four visits at about the same hour.' }],
                moments: [{ at: Date.now() - 40000, label: 'A shaft straight down',
                            why: 'Steve went from the surface to bedrock in one go.',
                            player: 'Steve', dim: 'overworld', x: 23, y: 37, z: 33,
                            weight: 60 }] } },
  '/api/servermods': { folder: 'mods/', maxBytes: 33554432, mods: [
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
  '/api/ai/diagnostics': { rows: [{ at: Date.now(), provider: 'openai', model: 'gpt-test',
      url: 'https://api.openai.com/v1/responses',
      requestHeaders: ['accept', 'authorization', 'content-type'],
      requestBody: '{"model":"gpt-test","input":"hello"}', status: 200,
      responseHeaders: ['content-type'], responseBody: '{"output_text":"hello"}',
      elapsedMs: 120, error: '' }] },
  '/api/update': { current: '2.5.0', repo: 'a/b', status: 'available', latest: '2.6.0', hasJar: true },
  '/api/accounts': { ownerName: 'admin', myRank: 0, owner: true, lastRank: 999,
      myAccess: { activity: 'write', files: 'write' },
      menus: [{ id: 'activity', name: 'Activity' }, { id: 'files', name: 'Files' }],
      accounts: [{ id: 'a1', username: 'moderator', mcName: 'Steve', mcUuid: 'u',
                   auditActivity: true, created: 1, lastLogin: Date.now(), rank: 2,
                   access: { activity: 'read', files: 'none' } }] },
  '/api/players': { online: [{ name: 'TheMines', uuid: 'u', mask: 'Ghost', sessionMillis: 60000,
                              hasMod: true, reported: true, protectedPlayer: true },
                             { name: 'Griefer', uuid: 'g', mask: '', sessionMillis: 1000,
                               hasMod: false, reported: false },
                             { name: 'Repentant', uuid: 'r', mask: '', sessionMillis: 1000,
                               hasMod: false, reported: false, banned: true }],
                    history: [{ uuid: 'u', name: 'TheMines', firstSeen: 1, lastSeen: Date.now(),
                                joins: 4, playtimeMillis: 7200000, mask: 'Ghost' }],
                    maxPlayers: 20 },
};

function bodyFor(url) {
  const path = url.split('?')[0];
  asked.add(path);
  return responses[path] !== undefined ? responses[path] : { ok: true };
}

const sandbox = {
  document,
  fetch: async (url) => ({ status: 200, json: async () => bodyFor(url) }),
  setTimeout: (fn, ms) => {
    // Zero-delay callbacks are the page wiring itself up after a render, and
    // the harness needs those to have happened by the time it looks. A real
    // delay is a real wait (the countdown's retry), and must not fire here.
    if (!ms) { try { fn(); } catch (e) { failures.push('setTimeout callback: ' + e.message); } }
    return 0;
  },
  clearTimeout: () => {},
  requestAnimationFrame: (fn) => { try { fn(); } catch (e) {
    failures.push('animation frame: ' + e.message); } return 0; },
  setInterval: (fn, ms) => { intervals.push({ fn, ms }); return intervals.length; },
  clearInterval: (id) => { if (id) intervals[id - 1] = null; },
  confirm: () => true,
  prompt: () => 'x',
  alert: () => {},
  location: { href: '', reload: () => { sandbox.reloads++; } },
  console,
  Date: FakeDate,
  Math,
  JSON,
  encodeURIComponent,
  String, Number, Boolean, Array, Object, Promise, Error, Set, Map, RegExp,
  navigator: { clipboard: { writeText: async () => {} } },
  isSecureContext: true,
  innerWidth: 1440, innerHeight: 900,
  open: () => null,
};
sandbox.reloads = 0;
sandbox.window = sandbox;
sandbox.addEventListener = () => {};
sandbox.removeEventListener = () => {};
sandbox.globalThis = sandbox;

vm.createContext(sandbox);
try {
  vm.runInContext(src, sandbox, { filename: 'panel.js' });
} catch (e) {
  console.log('  FAIL  the script throws on load  -> ' + e.message);
  process.exit(1);
}

// Every tab must render without throwing, and ask for its own data.
const tabs = ['dash', 'term', 'activity', 'files', 'players', 'mods', 'settings'];
(async () => {
  sandbox.authed = true;
  sandbox.serverRunning = true;
  sandbox.pwSet = true;
  for (const t of tabs) {
    sandbox.tab = t;
    try {
      sandbox.render();
      if (sandbox.tab !== t) throw new Error('tab did not stick — harness cannot reach the page state');
      await new Promise((r) => setTimeout(r, 30));
      console.log('  PASS  ' + t + ' tab renders');
    } catch (e) {
      console.log('  FAIL  ' + t + ' tab renders  -> ' + e.message);
      failures.push(t + ': ' + e.message);
    }
  }

  // The panel must actually call the routes the server exposes.
  for (const p of ['/api/config', '/api/players', '/api/update', '/api/mods', '/api/activity',
                   '/api/console']) {
    const ok = asked.has(p);
    console.log((ok ? '  PASS  ' : '  FAIL  ') + 'panel calls ' + p);
    if (!ok) failures.push('never called ' + p);
  }

  // Handlers reached from a click, checked by calling them directly.
  for (const fn of ['setKey', 'sendMask', 'setPassword', 'applyUpdate', 'clearLog',
                    'reloadConfig', 'loadConfig', 'loadUpdate', 'loadPlayers',
                    'dlFile', 'upFiles', 'fetchDialog', 'uploadDialog', 'mkdirDialog',
                    'renameDialog', 'deleteDialog', 'entryMenu', 'addMenu', 'fileRow',
                    'crumbs', 'kindOf', 'isText', 'fmtBytes', 'fmtWhen', 'avatar',
                    'menu', 'menuUnder', 'menuAt', 'modal', 'closeMenu', 'closeModal',
                    'modRow', 'modMenu', 'modIcon', 'addModMenu', 'editModDialog',
                    'uploadModDialog', 'modrinthDialog', 'renderModSettings',
                    'paintUnusedJars', 'setModRequired', 'removeModDialog', 'loadMods',
                    'cfgToggle',
                    'loadActivity', 'paintActivity', 'clearActivity', 'humanMinutes',
                    'loadTrack', 'loadTrackList', 'paintMap', 'openEditor',
                    'searchModrinth', 'addModrinth', 'showWaiting', 'showRelaunch',
                    'showAiDiagnostics',
                    'refreshOnce', 'poll', 'loadAll', 'paintAll', 'showAdmins', 'setAdmins',
                    'togglePlay', 'stopPlay', 'playerColor', 'marker', 'shotFor',
                    'loadBlueMapStatus', 'usingBlueMap', 'setBlueMapMode', 'paintBlueMap',
                    'resetBlueMapDialog',
                    'blueMapPayload', 'openBlueScene', 'inspectBlueWorld']) {
    if (typeof sandbox[fn] !== 'function') {
      console.log('  FAIL  ' + fn + ' is defined');
      failures.push(fn + ' missing');
    } else {
      console.log('  PASS  ' + fn + ' is defined');
    }
  }

  try {
    await sandbox.loadTrack('Steve');
    sandbox.paintMap();
    console.log('  PASS  the movement map draws');
  } catch (e) {
    console.log('  FAIL  the movement map draws  -> ' + e.message);
    failures.push('map: ' + e.message);
  }

  try {
    sandbox.tab = 'settings'; sandbox.render();
    await sandbox.showAiDiagnostics(true);
    const ok = asked.has('/api/ai/diagnostics')
      && byId.get('s-aidiagbox').style.display !== 'none';
    console.log((ok ? '  PASS  ' : '  FAIL  ') + 'the raw AI transcript opens on demand');
    if (!ok) failures.push('AI transcript did not open');
  } catch (e) {
    console.log('  FAIL  the raw AI transcript opens on demand  -> ' + e.message);
    failures.push('AI transcript: ' + e.message);
  }

  try {
    sandbox.tab = 'mods'; sandbox.render();
    // Searching lives inside the dialog now, so open it the way a person does.
    sandbox.modrinthDialog();
    await sandbox.searchModrinth();
    await sandbox.addModrinth('https://modrinth.com/mod/modmenu');
    sandbox.closeModal();
    console.log('  PASS  the Modrinth search and add run');
  } catch (e) {
    console.log('  FAIL  the Modrinth search and add run  -> ' + e.message);
    failures.push('modrinth: ' + e.message);
  }

  try {
    sandbox.tab = 'files'; sandbox.render();
    sandbox.openEditor('config/almin/config.json', false, true, true);
    sandbox.openEditor('', true);
    console.log('  PASS  the editor opens on demand');
  } catch (e) {
    console.log('  FAIL  the editor opens on demand  -> ' + e.message);
    failures.push('editor: ' + e.message);
  }

  try {
    await sandbox.setKey({ name: 'auto-update', type: 'BOOL', reloadsPanel: false }, 'false');
    console.log('  PASS  setKey runs');
  } catch (e) { console.log('  FAIL  setKey runs  -> ' + e.message); failures.push('setKey: ' + e.message); }

  try {
    await sandbox.sendMask('TheMines', 'Ghost', false);
    console.log('  PASS  sendMask runs');
  } catch (e) { console.log('  FAIL  sendMask runs  -> ' + e.message); failures.push('sendMask: ' + e.message); }

  // The timeline map: everyone on one clock, scrubbable.
  try {
    sandbox.tab = 'activity'; sandbox.render();
    await sandbox.loadAll();
    const a = sandbox.playerColor('Steve'), b = sandbox.playerColor('Steve');
    const c = sandbox.playerColor('Alex');
    console.log((a === b ? '  PASS  ' : '  FAIL  ') + 'a player keeps the same colour');
    if (a !== b) failures.push('playerColor is not stable');
    console.log((a !== c ? '  PASS  ' : '  FAIL  ') + 'two players get different colours');
    if (a === c) failures.push('playerColor collides');

    // Scrubbing to the start must not throw on an empty selection, and
    // scrubbing back to the end must draw everything again.
    const span = { from: responses['/api/track'].from, to: responses['/api/track'].to };
    for (const t of [span.from, (span.from + span.to) / 2, span.to]) {
      sandbox.cursorAt = t; sandbox.cursorSet = true; sandbox.paintAll();
    }
    console.log('  PASS  the timeline scrubs end to end');

    // The ground under the map: the picture that matches the cursor, and a
    // URL that does not change every frame of playback.
    const at = Date.now();
    const pick = sandbox.shotFor('overworld', at);
    console.log((pick ? '  PASS  ' : '  FAIL  ') + 'a picture is chosen for the cursor');
    if (!pick) failures.push('no shot chosen');
    const old = sandbox.shotFor('overworld', at - 40000);
    const choseOlder = old && pick && old.at < pick.at;
    console.log((choseOlder ? '  PASS  ' : '  FAIL  ') + 'scrubbing back picks an older picture');
    if (!choseOlder) failures.push('shotFor ignores the cursor');
    const before = sandbox.shotFor('overworld', 1);
    console.log((before ? '  PASS  ' : '  FAIL  ') +
      'before them all it still shows one rather than nothing');
    if (!before) failures.push('shotFor gives up too early');
    const none = sandbox.shotFor('the_end', at);
    console.log((!none ? '  PASS  ' : '  FAIL  ') + 'a dimension with no pictures has none');
    if (none) failures.push('shotFor crossed dimensions');

    sandbox.cursorAt = span.to; sandbox.paintAll();
    const html = byId.get('t-map')._html || '';
    const drawn = html.includes('/api/map?at=') && !html.includes('at=' + at);
    console.log((drawn ? '  PASS  ' : '  FAIL  ') +
      'the ground is drawn, addressed by the picture not the cursor');
    if (!drawn) failures.push('ground image missing or cursor-addressed');

    // Every action has to be drawable, including one nobody has defined yet.
    let shapes = 0;
    for (const a of ['place', 'break', 'attack', 'hurt', 'death', 'chat', 'command',
                     'container', 'join', 'leave', 'respawn', 'item', 'interact',
                     'use', 'afk', 'something-new']) {
      const svg = sandbox.marker(a, 10, 10, '#fff', 1);
      if (typeof svg === 'string' && svg.length > 10) shapes++;
      else failures.push('no marker for ' + a);
    }
    console.log((shapes === 16 ? '  PASS  ' : '  FAIL  ') + 'every action has a marker');

    // Asking for a bigger marker has to make every shape bigger, not the
    // three that happened to use the scale in their own numbers.
    let scaled = 0;
    for (const a of ['place', 'break', 'attack', 'hurt', 'death', 'chat', 'command',
                     'container', 'join', 'leave', 'respawn', 'item', 'interact',
                     'use', 'afk']) {
      const one = sandbox.marker(a, 10, 10, '#fff', 1);
      const big = sandbox.marker(a, 10, 10, '#fff', 2);
      if (big !== one && big.includes('scale(2')) scaled++;
      else failures.push(a + ' ignores the marker scale');
    }
    console.log((scaled === 15 ? '  PASS  ' : '  FAIL  ') + 'every shape honours the scale');

    sandbox.togglePlay();
    const playing = sandbox.playTimer !== null;
    sandbox.stopPlay();
    console.log((playing ? '  PASS  ' : '  FAIL  ') + 'Play starts and stops');
    if (!playing) failures.push('play did not start');
  } catch (e) {
    console.log('  FAIL  the timeline map draws  -> ' + e.message);
    failures.push('timeline: ' + e.message);
  }

  // Who is recorded, and the run-only override.
  try {
    sandbox.showAdmins({ includeAdmins: false, temporary: false, configured: false });
    sandbox.showAdmins({ includeAdmins: true, temporary: true, configured: false });
    await sandbox.setAdmins(true, true);
    console.log('  PASS  the admin-tracking controls run');
  } catch (e) {
    console.log('  FAIL  the admin-tracking controls run  -> ' + e.message);
    failures.push('admins: ' + e.message);
  }

  // A panel served by a different build than the one this page came from is
  // the old panel. It has to put itself onto the new one.
  try {
    sandbox.reloads = 0;
    sandbox.version = null;
    await sandbox.refreshOnce();                      // learns 2.12.0
    const learned = sandbox.version === '2.12.0';
    console.log((learned ? '  PASS  ' : '  FAIL  ') + 'the page records the version it was served by');
    if (!learned) failures.push('version not recorded: ' + sandbox.version);

    await sandbox.refreshOnce();                      // same version, no reload
    const quiet = sandbox.reloads === 0;
    console.log((quiet ? '  PASS  ' : '  FAIL  ') + 'the same version does not reload the page');
    if (!quiet) failures.push('reloaded on an unchanged version');

    responses['/api/session'].version = '2.13.0';     // the server updated under us
    await sandbox.refreshOnce();
    const reloaded = sandbox.reloads === 1;
    console.log((reloaded ? '  PASS  ' : '  FAIL  ') + 'a new version reloads the page onto it');
    if (!reloaded) failures.push('did not reload on a version change');
    responses['/api/session'].version = '2.12.0';
  } catch (e) {
    console.log('  FAIL  the version check runs  -> ' + e.message);
    failures.push('version check: ' + e.message);
  }

  // A restart takes the panel away with it. The page has to survive that gap
  // and come back, rather than reporting the panel as broken.
  try {
    sandbox.reloads = 0;
    sandbox.awaitingReturn = false;
    responses['/api/session'].restarting = true;
    await sandbox.refreshOnce();
    const armed = sandbox.awaitingReturn === true;
    console.log((armed ? '  PASS  ' : '  FAIL  ') + 'a restart in flight is noticed');
    if (!armed) failures.push('restarting flag ignored');

    const realFetch = sandbox.fetch;
    sandbox.fetch = async () => { throw new Error('connection refused'); };
    await sandbox.poll();                             // server gone
    const survived = sandbox.reloads === 0 && sandbox.wasReachable === false;
    console.log((survived ? '  PASS  ' : '  FAIL  ') + 'the gap while it is down is not an error');
    if (!survived) failures.push('poll mishandled the down server');

    sandbox.fetch = realFetch;
    await sandbox.poll();                             // it answered again
    const back = sandbox.reloads === 1;
    console.log((back ? '  PASS  ' : '  FAIL  ') + 'the page reloads once the server is back');
    if (!back) failures.push('did not reload after the server returned');
    responses['/api/session'].restarting = false;
  } catch (e) {
    console.log('  FAIL  the restart gap is handled  -> ' + e.message);
    failures.push('restart gap: ' + e.message);
  }

  // ---- the file browser ----
  function check(what, fn) {
    try {
      const why = fn();
      if (why === true || why === undefined) { console.log('  PASS  ' + what); return; }
      console.log('  FAIL  ' + what + '  -> ' + why);
      failures.push(what + ': ' + why);
    } catch (e) {
      console.log('  FAIL  ' + what + '  -> ' + e.message);
      failures.push(what + ': ' + e.message);
    }
  }

  sandbox.tab = 'files'; sandbox.render();
  await new Promise((r) => setTimeout(r, 20));

  check('the browser lists what the server sent', () => {
    const rows = byId.get('flist').children;
    // Four entries plus the "up one folder" row is only there below the root.
    return rows.length === 4 ? true : 'drew ' + rows.length + ' rows, expected 4';
  });

  check('file kinds are named, not just extensions', () =>
    sandbox.kindOf('a.json') === 'JSON' && sandbox.kindOf('x.mca') === 'Region data'
      && sandbox.kindOf('run') === 'File' ? true
      : 'got ' + sandbox.kindOf('a.json') + ' / ' + sandbox.kindOf('x.mca'));

  check('binaries are not offered to a textarea', () =>
    !sandbox.isText('sodium.jar') && !sandbox.isText('r.0.0.mca') && sandbox.isText('server.properties')
      ? true : 'wrong call on a binary');

  check('sizes read as sizes', () =>
    sandbox.fmtBytes(900000) === '879 KB' && sandbox.fmtBytes(12) === '12 B'
      ? true : 'got ' + sandbox.fmtBytes(900000));

  check('right-clicking a file offers edit, download, rename, delete', () => {
    const items = sandbox.entryMenu({ name: 'a.json', directory: false, size: 12,
                                      writable: true, deletable: true });
    const labels = items.filter((i) => i.label).map((i) => i.label);
    for (const want of ['Edit', 'Download', 'Rename…', 'Delete…', 'Copy path']) {
      if (!labels.includes(want)) return 'missing ' + want + ' (had ' + labels.join(', ') + ')';
    }
    return true;
  });

  check('a read-only file cannot be renamed or deleted from the menu', () => {
    const items = sandbox.entryMenu({ name: 'server.jar', directory: false,
                                      size: 50 * 1024 * 1024, writable: false,
                                      deletable: false });
    const ren = items.find((i) => i.label === 'Rename…');
    const del = items.find((i) => i.label === 'Delete…');
    if (!ren.disabled || !del.disabled) return 'offered a write it cannot do';
    if (!ren.why) return 'disabled with no reason given';
    return true;
  });

  check('world can be deleted without becoming writable', () => {
    const items = sandbox.entryMenu({ name: 'world', directory: true, size: -1,
                                      items: 12, writable: false, deletable: true });
    const ren = items.find((i) => i.label === 'Rename…');
    const del = items.find((i) => i.label === 'Delete…');
    if (!ren.disabled) return 'world was offered for rename';
    return !del.disabled ? true : 'world deletion was disabled';
  });

  check('a binary file cannot be opened in the editor', () => {
    const items = sandbox.entryMenu({ name: 'sodium.jar', directory: false, size: 900000,
                                      writable: true, deletable: true });
    const edit = items.find((i) => i.label === 'Edit');
    if (!edit.disabled) return 'offered to edit a jar';
    // Download must stay available: it is the thing you actually want.
    if (items.find((i) => i.label === 'Download').disabled) return 'refused to download it';
    return true;
  });

  check('a file too large for the editor says so', () => {
    const items = sandbox.entryMenu({ name: 'huge.log', directory: false,
                                      size: 9 * 1024 * 1024, writable: true,
                                      deletable: true });
    const edit = items.find((i) => i.label === 'Edit');
    return edit.disabled && /2 MB/.test(edit.why) ? true : 'no size reason';
  });

  check('a folder offers Open rather than Edit', () => {
    const items = sandbox.entryMenu({ name: 'config', directory: true, size: -1,
                                      items: 4, writable: true, deletable: true });
    const labels = items.filter((i) => i.label).map((i) => i.label);
    return labels.includes('Open') && !labels.includes('Edit') && !labels.includes('Download')
      ? true : 'wrong menu: ' + labels.join(', ');
  });

  check('the add menu carries all four ways of putting something in', () => {
    const labels = sandbox.addMenu().filter((i) => i.label).map((i) => i.label);
    for (const want of ['Upload files…', 'Download from a link…', 'New file…', 'New folder…']) {
      if (!labels.includes(want)) return 'missing ' + want;
    }
    return true;
  });

  check('a read-only folder disables every way of adding to it', () => {
    const was = sandbox.dirWritable;
    sandbox.dirWritable = false;
    const items = sandbox.addMenu().filter((i) => i.label);
    sandbox.dirWritable = was;
    return items.every((i) => i.disabled) ? true : 'offered a write into a read-only folder';
  });

  check('the folder dialogs open', () => {
    sandbox.mkdirDialog(); sandbox.uploadDialog(); sandbox.fetchDialog();
    sandbox.renameDialog('config/a.json', 'a.json');
    sandbox.deleteDialog('config/a.json', { directory: false });
    sandbox.closeModal();
    return true;
  });

  // ---- faces ----
  check('a face is an image when heads are on', () => {
    sandbox.headsOn = true;
    const el = sandbox.avatar('TheMines', '516e51d9-4e6b-4a2f-a282-e0f51f5a20e7', 'lg');
    if (el.tagName !== 'img') return 'drew a ' + el.tagName;
    if (!/\/api\/head\?uuid=/.test(el.src)) return 'asked the wrong url: ' + el.src;
    return true;
  });
  check('a face falls back to an initial with no uuid', () => {
    const el = sandbox.avatar('Steve', '', 'sm');
    return el.tagName === 'span' && el.textContent === 'S' ? true
      : 'got ' + el.tagName + ' "' + el.textContent + '"';
  });
  check('turning heads off asks the server for nothing', () => {
    sandbox.headsOn = false;
    const el = sandbox.avatar('Alex', '516e51d9-4e6b-4a2f-a282-e0f51f5a20e7', '');
    sandbox.headsOn = true;
    return el.tagName === 'span' ? true : 'still requested a face';
  });
  check('two players get two different colours', () => {
    const a = sandbox.avatar('Steve', '', '').style.background;
    const b = sandbox.avatar('Alex', '', '').style.background;
    return a !== b ? true : 'both got ' + a;
  });

  // ---- mods ----
  sandbox.tab = 'mods'; sandbox.render();
  await new Promise((r) => setTimeout(r, 20));

  check('the mod list is the list, with both kinds on it', () => {
    const rows = byId.get('modlist').children;
    if (rows.length !== 2) return 'drew ' + rows.length + ' rows';
    const html = rows.map((r) => r.children.map((c) => c._html || '').join('')).join('');
    if (!/Jar</.test(html)) return 'no jar chip';
    if (!/Link</.test(html)) return 'no link chip';
    if (!/Required</.test(html)) return 'no required chip';
    if (!/Modrinth</.test(html)) return 'nothing says where it came from';
    return true;
  });

  check('a mod with an icon asks this server for it', () => {
    const el = sandbox.modIcon({ id: 'sodium', name: 'Sodium', icon: true });
    return el.tagName === 'img' && /^\/api\/mods\/icon\?id=sodium$/.test(el.src)
      ? true : 'got ' + el.tagName + ' ' + el.src;
  });
  check('a mod without one gets a letter, not a broken image', () => {
    const el = sandbox.modIcon({ id: 'handmade', name: '', icon: false });
    return el.tagName === 'span' && el.textContent === 'H' ? true
      : 'got ' + el.tagName + ' "' + el.textContent + '"';
  });

  check('the + menu offers exactly the three ways in', () => {
    const labels = sandbox.addModMenu().filter((i) => i.label).map((i) => i.label);
    return labels.length === 3 && labels.includes('Search Modrinth…')
      && labels.includes('Upload a jar…') && labels.includes('Advertise a link…')
      ? true : 'got ' + labels.join(', ');
  });

  check('a Modrinth mod links back to its page; a hand-typed one does not', () => {
    const withPage = sandbox.modMenu({ id: 'sodium', name: 'Sodium', required: true,
                                       page: 'https://modrinth.com/mod/sodium' })
      .filter((i) => i.label).map((i) => i.label);
    const without = sandbox.modMenu({ id: 'handmade', name: '', required: false, page: '' })
      .filter((i) => i.label).map((i) => i.label);
    if (!withPage.includes('Open on Modrinth')) return 'no link for a Modrinth mod';
    if (without.includes('Open on Modrinth')) return 'linked a mod with no page';
    if (!withPage.includes('Make optional')) return 'required mod not offered "Make optional"';
    if (!without.includes('Make required')) return 'optional mod not offered "Make required"';
    return true;
  });

  check('the mod dialogs open', () => {
    sandbox.editModDialog(null);
    sandbox.editModDialog({ id: 'sodium', name: 'Sodium', version: '1.0', url: '',
                            file: 's.jar', sha256: 'abc', required: true, kind: 'jar',
                            page: '', source: 'modrinth' });
    sandbox.uploadModDialog();
    sandbox.modrinthDialog();
    sandbox.removeModDialog({ id: 'sodium', name: 'Sodium', kind: 'jar' });
    sandbox.closeModal();
    return true;
  });

  check('a jar-backed mod will not let its id be retyped', () => {
    sandbox.editModDialog({ id: 'sodium', name: 'Sodium', version: '1.0', url: '',
                            file: 's.jar', sha256: '', required: false, kind: 'jar' });
    const locked = byId.get('e-id').disabled === true;
    sandbox.closeModal();
    return locked ? true : 'let someone retype an id the jar decides';
  });

  check('settings hide behind the cog until asked for', () => {
    sandbox.modSettingsOpen = false;
    sandbox.renderModSettings();
    if (byId.get('m-settings').children.length !== 0) return 'settings shown unasked';
    sandbox.modSettingsOpen = true;
    sandbox.renderModSettings();
    // Two sections now: the switches, and the restricted-mods list.
    const shown = byId.get('m-settings').children.length >= 1;
    sandbox.modSettingsOpen = false;
    return shown ? true : 'the cog did not open them';
  });

  check('an unadvertised jar is surfaced rather than lost', () => {
    sandbox.paintUnusedJars(['leftover.jar']);
    const html = byId.get('m-unused').children.map((c) => c._html || '').join('');
    return /nothing offers \(1\)/.test(html) ? true : 'said nothing about it';
  });

  check('nothing is said when every jar is accounted for', () => {
    sandbox.paintUnusedJars([]);
    return byId.get('m-unused').children.length === 0 ? true : 'drew an empty section';
  });

  // ---- the map viewer ----
  sandbox.tab = 'activity'; sandbox.render();
  await new Promise((r) => setTimeout(r, 20));
  await sandbox.loadAll();

  check('quiet time is found from the record itself', () => {
    const gaps = sandbox.quietGaps();
    if (gaps.length !== 1) return 'found ' + gaps.length + ' gaps, expected 1';
    const hours = (gaps[0].to - gaps[0].from) / 3600e3;
    return hours > 3.9 && hours < 4 ? true : 'gap was ' + hours.toFixed(2) + 'h';
  });

  check('a moment inside quiet time is recognised', () => {
    const g = sandbox.quietGaps()[0];
    const mid = (g.from + g.to) / 2;
    if (!sandbox.gapAt(mid, [g])) return 'the middle of the gap was not in it';
    if (sandbox.gapAt(g.from - 1000, [g])) return 'a busy moment was called quiet';
    return true;
  });

  check('playback steps over quiet time', () => {
    const g = sandbox.quietGaps()[0];
    sandbox.skipGaps = true;
    sandbox.cursorAt = g.from - 500;
    sandbox.cursorSet = true;
    sandbox.playSpeed = 60;
    sandbox.togglePlay();
    tick(2);                       // enough frames to reach the gap
    sandbox.stopPlay();
    return sandbox.cursorAt >= g.to
      ? true : 'still ' + ((g.to - sandbox.cursorAt) / 1000).toFixed(0) + 's inside it';
  });

  check('...and runs through it when told to', () => {
    const g = sandbox.quietGaps()[0];
    sandbox.skipGaps = false;
    sandbox.cursorAt = g.from - 500;
    sandbox.playSpeed = 60;
    sandbox.togglePlay();
    tick(2);
    sandbox.stopPlay();
    const stayed = sandbox.cursorAt < g.to;
    sandbox.skipGaps = true;
    return stayed ? true : 'it skipped anyway';
  });

  check('a speed of n means n seconds of recorded time per second', () => {
    // The number on the button used to describe nothing: 1x meant "the whole
    // visible window in twenty seconds", so on a ten-minute period it ran at
    // thirty times real speed.
    const start = sandbox.allData.from;
    const run = (speed) => {
      sandbox.skipGaps = false;
      sandbox.cursorAt = start; sandbox.cursorSet = true;
      sandbox.playSpeed = speed;
      sandbox.togglePlay(); tick(20, 50); sandbox.stopPlay();   // 20 frames x 50ms = 1s
      return sandbox.cursorAt - start;
    };
    for (const speed of [1, 10, 60, 1800]) {
      const moved = run(speed);
      // One real second must have covered exactly `speed` seconds of
      // recorded time, whatever the frames did.
      if (Math.abs(moved - speed * 1000) > 1) {
        return speed + 'x moved ' + (moved / 1000) + 's in one second';
      }
    }
    sandbox.skipGaps = true; sandbox.playSpeed = 60;
    return true;
  });

  check('a throttled timer does not change the speed', () => {
    // Two frames a second instead of twenty must still cover the same ground:
    // the cursor follows the clock, not the frame count.
    const start = sandbox.allData.from;
    const run = (frames, stepMs) => {
      sandbox.skipGaps = false;
      sandbox.cursorAt = start; sandbox.cursorSet = true;
      sandbox.playSpeed = 60;
      sandbox.togglePlay(); tick(frames, stepMs); sandbox.stopPlay();
      return sandbox.cursorAt - start;
    };
    const smooth = run(20, 50);      // one second, twenty frames
    const janky = run(2, 500);       // one second, two frames
    sandbox.skipGaps = true;
    return Math.abs(smooth - janky) < 2
      ? true : smooth + ' vs ' + janky;
  });

  check('a tab that was asleep resumes rather than leaping', () => {
    const start = sandbox.allData.from;
    sandbox.skipGaps = false;
    sandbox.cursorAt = start; sandbox.cursorSet = true;
    sandbox.playSpeed = 60;
    sandbox.togglePlay();
    tick(1, 600000);                 // ten minutes with the tab in the background
    sandbox.stopPlay();
    sandbox.skipGaps = true;
    const movedS = (sandbox.cursorAt - start) / 1000;
    // One second of catching up at 60x, not ten minutes of it.
    return movedS <= 61
      ? true : 'it jumped ' + Math.round(movedS / 60) + ' minutes ahead';
  });

  check('the timeline loops instead of stopping at the end', () => {
    const d = sandbox.allData;
    sandbox.win = { from: d.from, to: d.to, set: true };
    sandbox.skipGaps = false;
    sandbox.cursorAt = d.to - 100;
    sandbox.cursorSet = true;
    sandbox.playSpeed = 60;
    sandbox.togglePlay();
    tick(1);
    const looped = sandbox.cursorAt < d.from + 60000;
    const running = sandbox.playTimer !== null;
    sandbox.stopPlay();
    sandbox.skipGaps = true;
    sandbox.win = { from: 0, to: 0, set: false };
    if (!looped) return 'it ran past the end to ' + sandbox.cursorAt;
    if (!running) return 'it stopped instead of looping';
    return true;
  });

  check('the loop follows the visible slice, not the whole day', () => {
    const d = sandbox.allData;
    const a = d.from + (d.to - d.from) * 0.6;
    const b = d.from + (d.to - d.from) * 0.7;
    sandbox.win = { from: a, to: b, set: true };
    sandbox.skipGaps = false;
    sandbox.cursorAt = b - 100;
    sandbox.playSpeed = 60;
    sandbox.togglePlay(); tick(1); sandbox.stopPlay();
    const back = sandbox.cursorAt >= a && sandbox.cursorAt < a + 60000;
    sandbox.win = { from: 0, to: 0, set: false };
    sandbox.skipGaps = true;
    return back ? true : 'it went to ' + new Date(sandbox.cursorAt).toISOString();
  });

  check('the speed readout says what a second buys', () => {
    sandbox.playSpeed = 60; sandbox.paintSpeed();
    if (byId.get('t-rate').textContent !== '1s = 1 minute') {
      return 'said "' + byId.get('t-rate').textContent + '"';
    }
    sandbox.playSpeed = 1; sandbox.paintSpeed();
    const one = byId.get('t-rate').textContent;
    sandbox.playSpeed = 1800; sandbox.paintSpeed();
    const lots = byId.get('t-rate').textContent;
    sandbox.playSpeed = 60; sandbox.paintSpeed();
    return one === '1s = 1 second' && lots === '1s = 30 minutes'
      ? true : one + ' / ' + lots;
  });

  check('zooming keeps the block under the pointer where it is', () => {
    // The map is drawn, then a wheel-zoom is applied through the same maths
    // the handler uses: centre moves toward the focus by the zoom factor.
    sandbox.view = { cx: 0, cz: 0, span: 400, set: true };
    const bx = 100, bz = 50;                       // the block under the pointer
    const k = 1 / 1.18;
    const next = sandbox.view.span * k;
    const f = next / sandbox.view.span;
    const cx = bx + (sandbox.view.cx - bx) * f;
    // After zooming, that block must still sit the same fraction across.
    const beforeFrac = (bx - sandbox.view.cx) / sandbox.view.span;
    const afterFrac = (bx - cx) / next;
    return Math.abs(beforeFrac - afterFrac) < 1e-9
      ? true : beforeFrac + ' became ' + afterFrac;
  });

  check('the view survives a repaint', () => {
    sandbox.view = { cx: 1234, cz: -567, span: 250, set: true };
    sandbox.paintAll();
    return sandbox.view.cx === 1234 && sandbox.view.span === 250
      ? true : 'it was reset to ' + sandbox.view.cx + '/' + sandbox.view.span;
  });

  check('the home button puts the framing back', () => {
    sandbox.view.set = false;
    sandbox.paintAll();
    return sandbox.view.set === true && sandbox.view.span > 0
      ? true : 'no framing was worked out';
  });

  check('player faces are drawn where the players were', () => {
    sandbox.headsOn = true;
    sandbox.cursorAt = sandbox.allData.to; sandbox.cursorSet = true;
    sandbox.focusPlayer = '';
    sandbox.paintAll();
    const html = byId.get('t-map')._html || '';
    return /\/api\/head\?uuid=/.test(html) ? true : 'no face on the map';
  });

  check('a face on the map is square, like the head it came from', () => {
    const html = byId.get('t-map')._html || '';
    const head = html.slice(html.indexOf('class="thead"'), html.indexOf('class="thead"') + 320);
    if (/<circle/.test(head)) return 'it is still round';
    return /<rect/.test(head) ? true : 'no frame at all';
  });

  check('every mark sits on a backing so it reads over terrain', () => {
    // Thin bright outlines drawn straight onto pixel art read as noise. Each
    // shape gets a dark disc first, whatever the shape is.
    for (const a of ['place', 'chat', 'attack', 'afk', 'something-new']) {
      const svg = sandbox.marker(a, 10, 10, '#fff', 1);
      if (!/fill-opacity="\.78"/.test(svg)) return a + ' has no backing';
    }
    return true;
  });

  check('the online overlay greys out whoever has stopped moving', () => {
    const bar = byId.get('t-online');
    const kids = bar.children;
    if (kids.length !== 2) return 'drew ' + kids.length + ' players';
    const afk = kids.filter((k) => (k.className || '').includes('afk'));
    return afk.length === 1 ? true : afk.length + ' marked away, expected 1';
  });

  check('focusing a player drops everyone else', () => {
    sandbox.focusPlayer = 'Steve';
    sandbox.paintAll();
    const withFocus = byId.get('t-map')._html || '';
    sandbox.focusPlayer = '';
    sandbox.paintAll();
    const without = byId.get('t-map')._html || '';
    if (!(withFocus.length < without.length)) return 'focus drew as much as everyone';
    if (!/showing only Steve/.test(byId.get('t-legend')._html || '')) {
      sandbox.focusPlayer = 'Steve'; sandbox.paintAll();
      const said = /showing only Steve/.test(byId.get('t-legend')._html || '');
      sandbox.focusPlayer = ''; sandbox.paintAll();
      if (!said) return 'the legend does not say it is filtered';
    }
    return true;
  });

  check('the side list carries chat, with what was said', () => {
    sandbox.cursorAt = sandbox.allData.to; sandbox.paintAll();
    const rows = byId.get('t-side').children;
    const html = rows.map((r) => (r._html || '') +
      (r.children || []).map((c) => c._html || '').join('')).join('');
    if (!/anyone seen my pickaxe/.test(html)) return 'the chat line is not there';
    if (!/afk/.test(html)) return 'afk is not shown as an action';
    return true;
  });

  check('the side list respects the focus', () => {
    sandbox.focusPlayer = 'Alex';
    sandbox.paintAll();
    const html = byId.get('t-side').children.map((r) =>
      (r.children || []).map((c) => c._html || '').join('')).join('');
    sandbox.focusPlayer = '';
    return !/anyone seen my pickaxe/.test(html)
      ? true : "Steve's chat showed while focused on Alex";
  });

  check('the look settings hide the overlays', () => {
    sandbox.mapOpts.overlays = false;
    sandbox.paintAll();
    const html = byId.get('t-map')._html || '';
    const gone = !html.includes('id="t-online"');
    sandbox.mapOpts.overlays = true;
    sandbox.paintAll();
    return gone ? true : 'the overlay stayed';
  });

  check('the cog opens the look settings, not a hidden toggle', () => {
    sandbox.optsOpen = true;
    sandbox.paintAll();
    const html = byId.get('t-map')._html || '';
    sandbox.optsOpen = false;
    sandbox.paintAll();
    const back = byId.get('t-map')._html || '';
    if (!html.includes('id="t-opts"')) return 'no settings panel';
    if (!html.includes('o-dim') || !html.includes('o-path') || !html.includes('o-mark')) {
      return 'the panel is missing the fine adjustments';
    }
    const layers = sandbox.mapOptionsHtml();
    for (const id of ['o-actions', 'o-paths', 'o-players', 'o-seq', 'o-grid']) {
      if (!layers.includes('<button id="' + id + '"')) return id + ' is not a layer button';
    }
    return back.includes('id="t-opts"') ? 'it would not close' : true;
  });

  check('a darker ground is a darker scrim over the terrain', () => {
    sandbox.mapOpts.dim = 0.6;
    sandbox.paintAll();
    const dark = /opacity="0\.60"/.test(byId.get('t-map')._html || '');
    sandbox.mapOpts.dim = 0.38;
    sandbox.paintAll();
    return dark ? true : 'the darkness setting did nothing';
  });

  check('path width follows the setting', () => {
    sandbox.mapOpts.path = 6;
    sandbox.paintAll();
    const wide = /stroke-width="6\.0"/.test(byId.get('t-map')._html || '');
    sandbox.mapOpts.paths = false;
    sandbox.paintAll();
    const none = !/stroke-linejoin="round"/.test(byId.get('t-map')._html || '');
    sandbox.mapOpts.path = 2.6; sandbox.mapOpts.paths = true;
    sandbox.paintAll();
    if (!wide) return 'width was ignored';
    return none ? true : 'paths could not be turned off';
  });

  check('marks can be coloured by who did them', () => {
    sandbox.mapOpts.colour = 'player';
    sandbox.mapOpts.cluster = false;
    sandbox.paintAll();
    const byPlayer = byId.get('t-map')._html || '';
    sandbox.mapOpts.colour = 'action';
    sandbox.paintAll();
    const byAction = byId.get('t-map')._html || '';
    sandbox.mapOpts.cluster = true;
    sandbox.paintAll();
    return byPlayer !== byAction ? true : 'the colouring did not change';
  });

  // ---- filtering ----
  check('the filter panel lists what is actually there', () => {
    sandbox.filterOpen = true;
    sandbox.paintFilters();
    const all = deepText(byId.get('t-filters'));
    sandbox.filterOpen = false;
    sandbox.paintFilters();
    return /break/.test(all) && /chat/.test(all) ? true : 'no kinds were offered';
  });

  check('ticking a kind hides the others', () => {
    sandbox.live = false;
    sandbox.cursorAt = sandbox.allData.to; sandbox.cursorSet = true;
    sandbox.mapOpts.cluster = false;
    sandbox.view = { cx: 25, cz: 40, span: 4000, set: true };
    sandbox.clearFilter();
    sandbox.paintAll();
    const before = (byId.get('t-map')._html || '').match(/class="tmk"/g) || [];
    sandbox.filt.acts.add('break');
    sandbox.paintAll();
    const after = (byId.get('t-map')._html || '').match(/class="tmk"/g) || [];
    sandbox.clearFilter();
    sandbox.mapOpts.cluster = true;
    sandbox.paintAll();
    return after.length > 0 && after.length < before.length
      ? true : before.length + ' marks became ' + after.length;
  });

  check('ticking a particular thing narrows it further', () => {
    sandbox.mapOpts.cluster = false;
    sandbox.clearFilter();
    sandbox.filt.acts.add('break');
    sandbox.paintAll();
    const kind = ((byId.get('t-map')._html || '').match(/class="tmk"/g) || []).length;
    sandbox.filt.items.add('break' + SEP + 'Stone');
    sandbox.paintAll();
    const item = ((byId.get('t-map')._html || '').match(/class="tmk"/g) || []).length;
    sandbox.clearFilter();
    sandbox.mapOpts.cluster = true;
    sandbox.paintAll();
    return item > 0 && item <= kind ? true : kind + ' became ' + item;
  });

  check('a thing that nothing matches shows nothing', () => {
    sandbox.mapOpts.cluster = false;
    sandbox.clearFilter();
    sandbox.filt.acts.add('break');
    sandbox.filt.items.add('break' + SEP + 'Nothing At All');
    sandbox.paintAll();
    const none = ((byId.get('t-map')._html || '').match(/class="tmk"/g) || []).length;
    sandbox.clearFilter();
    sandbox.mapOpts.cluster = true;
    sandbox.paintAll();
    return none === 0 ? true : 'it still drew ' + none;
  });

  check('a sequence filter keeps everything that happened during it', () => {
    sandbox.clearFilter();
    sandbox.filt.kinds.add('shaft');
    const ep = sandbox.episodes[0];
    const inside = { player: ep.player, at: ep.to - 1000, action: 'break', dim: 'overworld' };
    const outside = { player: ep.player, at: ep.to + 9e6, action: 'break', dim: 'overworld' };
    const other = { player: 'Nobody', at: ep.to - 1000, action: 'break', dim: 'overworld' };
    const ok = sandbox.passes(inside) && !sandbox.passes(outside) && !sandbox.passes(other);
    sandbox.clearFilter();
    return ok ? true : 'the window was not respected';
  });

  // ---- sequences on the map ----
  check('a stretch of work gets one badge, not forty marks', () => {
    sandbox.mapOpts.sequences = true;
    sandbox.paintAll();
    return /class="tsq"/.test(byId.get('t-map')._html || '')
      ? true : 'no badge was drawn';
  });

  check('the badge carries the tool the work was done with', () => {
    sandbox.paintAll();
    const html = byId.get('t-map')._html || '';
    return /class="tsq"[\s\S]{0,1200}?M-1 -4\.6q4\.4/.test(html)
      ? true : 'the pickaxe was not drawn';
  });

  check('a stretch says what it was when you point at it, not before', () => {
    sandbox.paintAll();
    const html = byId.get('t-map')._html || '';
    // The sentence used to be painted beside every notable badge, which on a
    // busy evening covered the map it was describing.
    if (/class="sqlabel"/.test(html)) return 'it is still drawn unasked';
    return /class="tsq"/.test(html) ? true : 'no badge to point at';
  });

  check("...and the model's note comes with it", () => {
    // What the hover handler builds, without a pointer to move.
    const e = sandbox.episodes[0];
    const note = sandbox.momentFor(e);
    if (!note) return 'the moment did not reach the episode';
    return /A shaft straight down/.test(note.label)
      ? true : 'the note is not the one the model wrote';
  });

  check('badges can be turned off', () => {
    sandbox.mapOpts.sequences = false;
    sandbox.paintAll();
    const off = !/class="tsq"/.test(byId.get('t-map')._html || '');
    sandbox.mapOpts.sequences = true;
    sandbox.paintAll();
    return off ? true : 'they stayed';
  });

  check('a 3D build badge collapses only the placement rows it contains', () => {
    sandbox.clearFilter();
    sandbox.live = false;
    sandbox.cursorAt = sandbox.allData.to; sandbox.cursorSet = true;
    sandbox.view = { cx: 23, cz: 33, span: 100, set: true };
    sandbox.mapOpts.cluster = false;
    sandbox.mapOpts.sequences = true;
    sandbox.mapOpts.sceneEvents = false;
    sandbox.paintAll();
    const folded = byId.get('t-map')._html || '';
    const foldedMarks = (folded.match(/class="tmk"/g) || []).length;
    if (!/id="t-scene-events">Expand \d+ build event/.test(folded)) {
      return 'there is no way to expand the build';
    }
    sandbox.mapOpts.sceneEvents = true;
    sandbox.paintAll();
    const open = byId.get('t-map')._html || '';
    const openMarks = (open.match(/class="tmk"/g) || []).length;
    sandbox.mapOpts.sceneEvents = false;
    sandbox.mapOpts.cluster = true;
    sandbox.paintAll();
    return openMarks > foldedMarks && /Collapse \d+ build event/.test(open)
      ? true : foldedMarks+' folded became '+openMarks+' open';
  });

  check('turning badges off puts every collapsed build event back', () => {
    sandbox.mapOpts.cluster = false;
    sandbox.mapOpts.sequences = true;
    sandbox.mapOpts.sceneEvents = true;
    sandbox.paintAll();
    const expanded = ((byId.get('t-map')._html || '').match(/class="tmk"/g) || []).length;
    sandbox.mapOpts.sceneEvents = false;
    sandbox.mapOpts.sequences = false;
    sandbox.paintAll();
    const withoutBadge = byId.get('t-map')._html || '';
    const marks = (withoutBadge.match(/class="tmk"/g) || []).length;
    sandbox.mapOpts.sequences = true;
    sandbox.mapOpts.cluster = true;
    sandbox.paintAll();
    return marks === expanded && !withoutBadge.includes('t-scene-events')
      ? true : expanded+' expanded, '+marks+' without badge';
  });

  // ---- faces ----
  check('a face is sized on its own, not with the marks', () => {
    sandbox.mapOpts.head = 2;
    sandbox.paintAll();
    const big = headSize();
    sandbox.mapOpts.head = 1;
    sandbox.paintAll();
    const small = headSize();
    sandbox.mapOpts.head = 1.35;
    sandbox.paintAll();
    return big > small * 1.6 ? true : small + ' became ' + big;
  });

  check('a face goes grey once nobody is moving it', () => {
    sandbox.live = false;
    sandbox.cursorAt = sandbox.allData.to; sandbox.cursorSet = true;
    sandbox.paintAll();
    return /class="thead afk"/.test(byId.get('t-map')._html || '')
      ? true : 'everyone was drawn as active';
  });

  check('...and is not grey while they are still moving', () => {
    const d = sandbox.allData;
    const steve = d.tracks.Steve;
    const was = steve[steve.length - 1].at;
    steve[steve.length - 1].at = d.to;
    sandbox.paintAll();
    const html = byId.get('t-map')._html || '';
    steve[steve.length - 1].at = was;
    sandbox.paintAll();
    const greys = (html.match(/class="thead afk"/g) || []).length;
    const all = (html.match(/class="thead/g) || []).length;
    return greys < all ? true : 'all ' + all + ' were grey';
  });

  // ---- marks keep their size on screen ----
  check('the map being drawn larger does not make the marks larger', () => {
    const rect = (w, h) => ({ querySelector: () => ({
      getBoundingClientRect: () => ({ width: w, height: h }) }) });
    sandbox.measureUnit(rect(1100, 660));
    const windowed = sandbox.unitAdjust;
    sandbox.measureUnit(rect(1900, 1140));
    const full = sandbox.unitAdjust;
    return full < windowed * 0.75
      ? true : 'the adjustment barely moved: ' + windowed + ' to ' + full;
  });

  // ---- the ground that stays drawn ----
  check('every patch anyone has a picture of is drawn, not just one', () => {
    const to = sandbox.allData.to;
    sandbox.shots = [
      { at: to - 60000, dim: 'overworld', minX: -200, minZ: -200, span: 384 },
      { at: to - 30000, dim: 'overworld', minX: 200, minZ: -200, span: 384 },
      { at: to - 10000, dim: 'overworld', minX: -200, minZ: -200, span: 384 },
    ];
    sandbox.view = { cx: 0, cz: -100, span: 1400, set: true };
    sandbox.paintAll();
    const html = byId.get('t-map')._html || '';
    const images = (html.match(/href="\/api\/map\?at=/g) || []).length;
    if (images !== 2) return 'drew ' + images + ' patches, expected 2';
    return html.includes('at=' + (to - 10000))
      ? true : 'the older picture of a patch won';
  });

  check('a patch off the side of the view is not fetched at all', () => {
    sandbox.view = { cx: -20, cz: -20, span: 60, set: true };
    sandbox.paintAll();
    const images = ((byId.get('t-map')._html || '')
      .match(/href="\/api\/map\?at=/g) || []).length;
    sandbox.view = { cx: 25, cz: 40, span: 4000, set: true };
    sandbox.paintAll();
    return images <= 1 ? true : 'it fetched ' + images;
  });

  // ---- what a stretch built ----
  check('a build with shape can be drawn as one', () => {
    const build = { kind: 'build', player: 'Steve', dim: 'overworld', events: 20,
                    from: sandbox.allData.to - 50000, to: sandbox.allData.to,
                    x: 22, y: 64, z: 33, headline: 'Built something' };
    if (!sandbox.hasShape(build)) return 'it was not considered to have a shape';
    const sc = sandbox.sceneOf(build);
    if (!sc) return 'nothing was collected';
    return sc.cubes.length > 0 ? true : 'no blocks in it';
  });

  check('a scene opens finished rather than empty', () => {
    const build = { kind: 'build', player: 'Steve', dim: 'overworld', events: 20,
                    from: sandbox.allData.to - 50000, to: sandbox.allData.to,
                    x: 22, y: 64, z: 33, headline: 'Built something' };
    const sc = sandbox.sceneOf(build);
    return sc.upto === sc.cubes.length ? true : 'it opened at ' + sc.upto;
  });

  check('chat and joins have no shape to draw', () => {
    return !sandbox.hasShape({ kind: 'about', events: 40 })
      ? true : 'it offered to draw a shrug';
  });

  check('a scene is clamped to 64 blocks across', () => {
    const far = { kind: 'build', player: 'Steve', dim: 'overworld', events: 20,
                  from: 0, to: sandbox.allData.to, x: 9000, z: 9000, y: 64,
                  headline: 'Far away' };
    return sandbox.sceneOf(far) === null
      ? true : 'it reached a thousand blocks for its blocks';
  });

  check('turning the scene really turns it', () => {
    const a = sandbox.turned({ x: 3, z: 5 }, 0);
    const b = sandbox.turned({ x: 3, z: 5 }, 1);
    const d = sandbox.turned({ x: 3, z: 5 }, 2);
    return (a.x === 3 && b.x === -5 && d.x === -3) ? true : 'the quarter turns are wrong';
  });

  check('the 3D grid is tied to world X, Y and Z coordinates', () => {
    const build = { kind: 'shaft', player: 'Steve', dim: 'overworld', events: 41,
                    from: sandbox.allData.to - 60000, to: sandbox.allData.to - 40000,
                    x: 23, y: 37, z: 33 };
    const was = sandbox.scene;
    sandbox.scene = sandbox.sceneOf(build);
    const grid = sandbox.sceneGridSvg(10, sandbox.scene.minY, sandbox.scene.maxY + 8);
    sandbox.scene = was;
    return /x -?\d+/.test(grid) && /z -?\d+/.test(grid) && /Y -?\d+/.test(grid)
      ? true : 'one of the three axes has no numbered grid';
  });

  check('changed and live-world blocks carry exact click-to-inspect data', () => {
    const changed = sandbox.cube(0, 0, 0, 12,
      { put: true, what: 'Oak Planks', n: 1, wx: 120, y: 70, wz: -44 });
    const world = sandbox.worldCube(1, 0, 0, 12,
      { what: 'Stone', wx: 121, y: 69, wz: -44 });
    if (!/class="sc-block"/.test(changed) || !/data-sc-state="placed"/.test(changed)) {
      return 'a changed block is not inspectable';
    }
    if (!/data-sc-x="120"/.test(changed) || !/data-sc-y="70"/.test(changed)
        || !/data-sc-z="-44"/.test(changed)) return 'its coordinates were lost';
    return /data-sc-state="world now"/.test(world) && /Stone/.test(world)
      ? true : 'the surrounding block cannot be identified';
  });

  check('nearby players use their recorded altitude in a 3D build scene', () => {
    const savedData = sandbox.allData, savedScene = sandbox.scene;
    const at = Date.now();
    sandbox.allData = { trackSeconds: 5, tracks: {
      Builder: [{ at: at, dim: 'overworld', x: 0, y: 64, z: 0 }],
      Witness: [{ at: at + 1, dim: 'overworld', x: 3, y: 82, z: -2 }]
    }, actions: [
      { at: at, player: 'Builder', dim: 'overworld', action: 'place', detail: 'Stone',
        x: 0, y: 64, z: 0, count: 1 },
      { at: at + 2, player: 'Builder', dim: 'overworld', action: 'place', detail: 'Stone',
        x: 1, y: 64, z: 0, count: 1 }
    ] };
    sandbox.scene = sandbox.sceneOf({ kind: 'build', events: 20, player: 'Builder',
      dim: 'overworld', from: at - 1, to: at + 3, x: 0, y: 64, z: 0 });
    const people = sandbox.scenePeopleAt(at + 2);
    const witness = people.find(p => p.player === 'Witness');
    const svg = witness ? sandbox.scenePlayer(witness.x, witness.y - sandbox.scene.minY,
      witness.z, 10, witness) : '';
    sandbox.scene = savedScene; sandbox.allData = savedData;
    return witness && witness.y === 82 && /Witness · 3,82,-2/.test(svg)
      ? true : 'the altitude-aware witness was not plotted';
  });

  // ---- forgetting with age ----
  check('nothing fades until it is asked to', () => {
    sandbox.live = false;
    sandbox.cursorAt = sandbox.allData.to; sandbox.cursorSet = true;
    sandbox.mapOpts.cluster = false;
    sandbox.view = { cx: 25, cz: 40, span: 4000, set: true };
    sandbox.mapOpts.fade.on = false;
    sandbox.paintAll();
    const marks = ((byId.get('t-map')._html || '').match(/class="tmk"/g) || []).length;
    // Every action in the fixture is well over an hour old.
    return marks > 0 ? true : 'they were dropped with fading off';
  });

  check('old marks go once fading is on', () => {
    sandbox.mapOpts.fade.on = true;
    sandbox.mapOpts.fade.minutes = 60;
    sandbox.mapOpts.fade.cats = ['world', 'fight', 'talk', 'move', 'things'];
    sandbox.paintAll();
    const few = ((byId.get('t-map')._html || '').match(/class="tmk"/g) || []).length;
    sandbox.mapOpts.fade.on = false;
    sandbox.paintAll();
    const many = ((byId.get('t-map')._html || '').match(/class="tmk"/g) || []).length;
    return few < many ? true : many + ' stayed ' + few;
  });

  check('only the categories that were asked for fade', () => {
    sandbox.mapOpts.fade.on = true;
    sandbox.mapOpts.fade.minutes = 60;
    sandbox.mapOpts.fade.cats = ['world'];
    sandbox.paintAll();
    const worldOnly = ((byId.get('t-map')._html || '').match(/class="tmk"/g) || []).length;
    sandbox.mapOpts.fade.cats = ['world', 'fight', 'talk', 'move', 'things'];
    sandbox.paintAll();
    const everything = ((byId.get('t-map')._html || '').match(/class="tmk"/g) || []).length;
    sandbox.mapOpts.fade.on = false;
    sandbox.mapOpts.fade.cats = ['world', 'fight', 'things'];
    sandbox.mapOpts.cluster = true;
    sandbox.paintAll();
    return worldOnly > everything
      ? true : 'narrowing the categories dropped just as much';
  });

  check('the opacity curve runs to nothing and then stops', () => {
    sandbox.mapOpts.fade.on = true;
    sandbox.mapOpts.fade.minutes = 10;
    sandbox.mapOpts.fade.cats = ['world'];
    const fresh = sandbox.ageOpacity('world', 0, 60000);
    const half = sandbox.ageOpacity('world', 5 * 60000, 60000);
    const gone = sandbox.ageOpacity('world', 11 * 60000, 60000);
    const other = sandbox.ageOpacity('talk', 11 * 60000, 60000);
    sandbox.mapOpts.fade.on = false;
    if (!(fresh > half && half > 0)) return 'it does not fade on the way';
    if (gone !== 0) return 'it never reaches gone';
    return other > 0 ? true : 'a category that was not asked for faded anyway';
  });

  check('player-track segments fade on the same clock as movement icons', () => {
    sandbox.mapOpts.fade.on = false;
    const points=[{at:0,x:0,y:64,z:0},{at:500,x:1,y:64,z:0},
                  {at:1000,x:2,y:64,z:0}];
    const runs=sandbox.fadedTrackRuns(points,1000,1000);
    const opacities=runs.map(r=>r.opacity);
    return opacities.length>1 && opacities[0]<opacities[opacities.length-1]
      ? true : 'old and new path segments kept the same opacity';
  });

  check('the movement fade window removes expired track segments', () => {
    sandbox.mapOpts.fade.on = true;
    sandbox.mapOpts.fade.minutes = 10;
    sandbox.mapOpts.fade.cats = ['move'];
    const minute=60000, points=[{at:0},{at:minute},{at:11*minute},{at:12*minute}];
    const runs=sandbox.fadedTrackRuns(points,12*minute,minute);
    sandbox.mapOpts.fade.on = false;
    sandbox.mapOpts.fade.cats = ['world', 'fight', 'things'];
    const drawn=runs.flatMap(r=>r.points);
    return !drawn.includes(points[0]) && !drawn.includes(points[1])
      && drawn.includes(points[2]) && drawn.includes(points[3])
      ? true : 'an expired part of the trail was still drawn';
  });

  check('the playback guide contains only the path still ahead', () => {
    const points=[{at:1},{at:2},{at:3},{at:4}];
    const future=sandbox.futureTrackPoints(points,2);
    return future.length===3 && future[0]===points[1] && future[2]===points[3]
      ? true : 'the faint guide still included travelled history';
  });

  check('a box is as visible as the freshest thing in it', () => {
    sandbox.mapOpts.cluster = true;
    sandbox.mapOpts.fade.on = true;
    sandbox.mapOpts.fade.minutes = 60;
    sandbox.mapOpts.fade.cats = ['world', 'fight', 'talk', 'move', 'things'];
    sandbox.view = { cx: 25, cz: 40, span: 4000, set: true };
    sandbox.paintAll();
    const html = byId.get('t-map')._html || '';
    sandbox.mapOpts.fade.on = false;
    sandbox.mapOpts.fade.cats = ['world', 'fight', 'things'];
    sandbox.paintAll();
    // Every box carries an opacity now, and a box of recent things is at full.
    const wrapped = /<g opacity="[\d.]+"><g class="tcl"/.test(html);
    return wrapped ? true : 'boxes are not faded with what is in them';
  });

  // ---- how often it refreshes ----
  check('the refresh interval is a setting, not a constant', () => {
    const src = fs.readFileSync(process.argv[2], 'utf8');
    const i = src.indexOf('function liveTick');
    const body = src.slice(i, i + 700);
    if (!/mapOpts\.refresh/.test(body)) return 'liveTick still uses a fixed number';
    return /o-refresh/.test(src) ? true : 'there is no control for it';
  });

  check('summaries refresh on the same clock as everything else', () => {
    const src = fs.readFileSync(process.argv[2], 'utf8');
    const i = src.indexOf('function liveTick');
    const body = src.slice(i, i + 700);
    return /loadInsights\(\)/.test(body)
      ? true : 'they only come back on a full page reload';
  });

  // ---- Minecraft's own settings ----
  check('Settings has a tab for the game and one for Almin', () => {
    sandbox.tab = 'settings';
    sandbox.settingsTab = 'almin';
    sandbox.render();
    const alminSide = !!byId.get('s-keys');
    sandbox.settingsTab = 'server';
    sandbox.render();
    const serverSide = !!byId.get('sp-rows');
    sandbox.settingsTab = 'almin';
    sandbox.render();
    if (!alminSide) return "Almin's own settings went missing";
    return serverSide ? true : 'the server tab drew nothing';
  });

  check('changing one value arms Save and nothing else', () => {
    sandbox.settingsTab = 'server';
    sandbox.render();
    sandbox.props = [
      { key: 'view-distance', value: '10', type: 'INT', secret: false },
      { key: 'pvp', value: 'true', type: 'BOOL', secret: false },
      { key: 'rcon.password', value: '••••', type: 'TEXT', secret: true },
    ];
    sandbox.propEdits = {};
    sandbox.paintProperties();
    const before = byId.get('sp-save').disabled;
    sandbox.propEdits['view-distance'] = '16';
    sandbox.paintSaveState();
    // Read now: the button is one node that gets rewritten, not a snapshot.
    const label = byId.get('sp-save').textContent;
    const enabled = byId.get('sp-save').disabled === false;
    sandbox.propEdits = {};
    sandbox.paintSaveState();
    const disarmed = byId.get('sp-save').disabled === true;
    sandbox.settingsTab = 'almin';
    if (!before) return 'it started out armed';
    if (!/Save 1 change/.test(label)) return 'it said "' + label + '"';
    if (!enabled) return 'it stayed disabled';
    return disarmed ? true : 'undoing the change left it armed';
  });

  // ---- sizes that agree with each other ----
  check('a box of marks is the size of the marks it stands for', () => {
    sandbox.live = false;
    sandbox.cursorAt = sandbox.allData.to; sandbox.cursorSet = true;
    sandbox.mapOpts.cluster = true;
    sandbox.mapOpts.mark = 2.2;
    sandbox.view = { cx: 25, cz: 40, span: 4000, set: true };
    sandbox.paintAll();
    const html = byId.get('t-map')._html || '';
    const box = html.match(/class="tcl"[\s\S]{0,220}?height="([\d.]+)"/);
    if (!box) return 'no box was drawn';
    // A mark is a disc of radius 8.4 drawn at the same scale, so the two
    // should be within half of each other however the map is being rendered.
    const h = +box[1];
    const markAcross = 16.8 * sandbox.mapOpts.mark * sandbox.unitAdjust;
    const ratio = h / markAcross;
    return (ratio > 0.6 && ratio < 1.4)
      ? true : 'the box is ' + h.toFixed(1) + ' against a mark of ' + markAcross.toFixed(1);
  });

  check('the box grows with the marker size', () => {
    sandbox.mapOpts.mark = 1;
    sandbox.paintAll();
    const small = +((byId.get('t-map')._html || '')
      .match(/class="tcl"[\s\S]{0,220}?height="([\d.]+)"/) || [])[1];
    sandbox.mapOpts.mark = 3.5;
    sandbox.paintAll();
    const big = +((byId.get('t-map')._html || '')
      .match(/class="tcl"[\s\S]{0,220}?height="([\d.]+)"/) || [])[1];
    sandbox.mapOpts.mark = 2.2;
    sandbox.paintAll();
    return big > small * 2 ? true : small + ' became ' + big;
  });

  check('a face is back to the size it was', () => {
    // A const at the top level of the page, so it is not on the sandbox.
    const src = fs.readFileSync(process.argv[2], 'utf8');
    const m = /MAP_DEFAULTS=\{[^}]*head:([\d.]+)/.exec(src);
    return m && +m[1] === 1 ? true : 'the default is ' + (m ? m[1] : 'missing');
  });

  check('a face size nobody chose is not treated as a preference', () => {
    const src = fs.readFileSync(process.argv[2], 'utf8');
    return /was\.v>=2/.test(src)
      ? true : 'an old saved default would stick forever';
  });

  // ---- the wheel belongs to whatever is under it ----
  check('scrolling a panel over the map does not zoom the map', () => {
    const src = fs.readFileSync(process.argv[2], 'utf8');
    const i = src.indexOf('function wireMapGestures');
    const body = src.slice(i, i + 2600);
    const wheel = body.slice(body.indexOf("addEventListener('wheel'"));
    return /clusterbox/.test(wheel) && /mapopts/.test(wheel)
      ? true : 'the wheel is taken whatever it is over';
  });

  // ---- this session, not all of it ----
  check('an online player is asked about this session', () => {
    sandbox.peopleData = sandbox.allData;
    const now = sandbox.allData.to;
    // Counted, not listed: the same kinds of thing appear in both, and it is
    // how many of them that a session is supposed to narrow.
    const total = (el) => el.children.reduce((n, c) =>
      n + (+((/(\d+)<\/i>/.exec(c._html || '') || [])[1] || 0)), 0);
    const some = total(sandbox.actionStrip('Steve', now - 60000));
    const every = total(sandbox.actionStrip('Steve', 0));
    return some > 0 && some < every ? true : every + ' became ' + some;
  });

  check('and says which nothing it is when there is none', () => {
    sandbox.peopleData = sandbox.allData;
    const strip = sandbox.actionStrip('Steve', sandbox.allData.to + 1);
    return /nothing this session/.test(strip._html || '')
      ? true : 'it said "' + (strip._html || '') + '"';
  });

  // ---- the little maps have ground now ----
  check('a player path is drawn over the world, not over black', () => {
    // The little maps read the players tab's own copy of the period.
    sandbox.peopleData = sandbox.allData;
    // The little map draws whichever dimension that player actually walked
    // in, so give it ground for all of them.
    const dims = [...new Set(sandbox.allData.tracks.Steve.map((q) => q.dim))];
    sandbox.shots = dims.map((dim) => ({ at: sandbox.allData.to, dim: dim,
                                         minX: -400, minZ: -400, span: 1024 }));
    const mini = sandbox.pathMap('Steve', 0);
    const html = mini._html || '';
    if (/no path recorded/.test(html)) return 'it found no path to draw';
    return /href="\/api\/map\?at=/.test(html)
      ? true : 'no ground: ' + html.slice(0, 200);
  });

  // ---- the isometric view ----
  check('two heaps thirty blocks apart are two pictures', () => {
    const near = [], far = [];
    for (let i = 0; i < 20; i++) near.push({ x: i % 5, y: 70 + ((i / 5) | 0), z: i % 4 });
    for (let i = 0; i < 6; i++) far.push({ x: 30 + (i % 3), y: 70, z: 30 });
    const kept = sandbox.largestHeap(near.concat(far));
    if (kept.length !== 20) return 'kept ' + kept.length + ' of 26';
    return kept.every((c) => c.x < 30) ? true : 'it kept the far heap';
  });

  check('...but one building is not cut in half', () => {
    const wall = [], other = [];
    for (let i = 0; i < 12; i++) wall.push({ x: i, y: 70, z: 0 });
    // Six blocks away: the far side of the same thing.
    for (let i = 0; i < 8; i++) other.push({ x: i, y: 70, z: 6 });
    const kept = sandbox.largestHeap(wall.concat(other));
    return kept.length === 20 ? true : 'it split into ' + kept.length;
  });

  check('a block is drawn in the colour it actually is', () => {
    sandbox.blockColour = { 'Oak Planks': '#a68a55' };
    const svg = sandbox.cube(0, 0, 0, 12, { put: true, what: 'Oak Planks', n: 1, y: 70 });
    sandbox.blockColour = null;
    return /#[0-9a-f]{6}/i.test(svg) && !/fill="#ffdd7a"/.test(svg)
      ? true : 'it is still a flat yellow';
  });

  check('placed and broken are still told apart', () => {
    sandbox.blockColour = { Stone: '#7a7a7a' };
    const put = sandbox.cube(0, 0, 0, 12, { put: true, what: 'Stone', n: 1, y: 70 });
    const took = sandbox.cube(0, 0, 0, 12, { put: false, what: 'Stone', n: 1, y: 70 });
    sandbox.blockColour = null;
    if (!/stroke="#ffd34d"/.test(put)) return 'placed lost its yellow';
    if (!/stroke="#ff5a5a"/.test(took)) return 'broken lost its red';
    return /fill-opacity="\.\d+"/.test(took) ? true : 'a broken block is drawn solid';
  });

  check('an unknown block still gets a colour of its own', () => {
    sandbox.blockColour = {};
    const a = sandbox.blockRgb('Some Modded Block');
    const b = sandbox.blockRgb('Another Modded Block');
    sandbox.blockColour = null;
    return a !== b ? true : 'every unknown block is the same colour';
  });

  check('the scene is fitted to its window rather than guessed at', () => {
    const src = fs.readFileSync(process.argv[2], 'utf8');
    const i = src.indexOf('function paintScene');
    const body = src.slice(i, src.indexOf('function sceneGridSvg', i));
    if (!/Math\.min\(W\/\(/.test(body)) return 'the scale ignores the window';
    return /translate\('\+tx/.test(body) ? true : 'it is not centred on what is in it';
  });

  check('a distant outlier and high player do not shrink a small block scene', () => {
    const savedData = sandbox.allData, savedScene = sandbox.scene;
    const at = Date.now(), actions = [];
    for (let x = 100; x < 104; x++) actions.push({ at: at + x, player: 'B',
      dim: 'overworld', action: 'place', detail: 'Oak Planks', x: x, y: 64, z: 100,
      count: 1 });
    actions.push({ at: at + 200, player: 'B', dim: 'overworld', action: 'place',
      detail: 'Oak Planks', x: 145, y: 176, z: 100, count: 1 });
    sandbox.allData = { trackSeconds: 5, actions: actions, tracks: {
      Flyer: [{ at: at + 150, dim: 'overworld', x: 102, y: 176, z: 100 }]
    }};
    sandbox.scene = sandbox.sceneOf({ kind: 'build', events: 20, player: 'B',
      dim: 'overworld', from: at, to: at + 300, x: 122, y: 120, z: 100 });
    const people = sandbox.scenePeopleAt(at + 300);
    const good = sandbox.scene && sandbox.scene.cubes.length === 4
      && sandbox.scene.maxY === 64 && sandbox.scene.radius <= 10 && people.length === 0;
    sandbox.scene = savedScene; sandbox.allData = savedData;
    return good ? true : 'the outlier still controlled the frame';
  });

  check('scene cubes request real block-face textures when a pack exists', () => {
    const saved = sandbox.shotTextures;
    sandbox.shotTextures = 'textures.zip';
    const defs = sandbox.sceneTextureDefs([{ what: 'Oak Planks' }, { what: 'Stone' }]);
    sandbox.shotTextures = saved;
    return /api\/block\?name=Oak%20Planks&amp;face=top|api\/block\?name=Oak%20Planks&face=top/.test(defs)
      ? true : 'the texture route was not used';
  });

  // ---- tools ----
  check('the game’s own tool textures are used when there are any', () => {
    sandbox.toolTextures = true;
    const withItems = sandbox.sequenceIcon('shaft', 0, 0, '#fff', 1);
    sandbox.toolTextures = false;
    const drawn = sandbox.sequenceIcon('shaft', 0, 0, '#fff', 1);
    if (!/api\/item\?name=iron_pickaxe/.test(withItems)) return 'no iron pickaxe';
    return !/api\/item/.test(drawn) ? true : 'it asked for one with no textures';
  });

  check('a fight is a sword and felling trees is an axe', () => {
    sandbox.toolTextures = true;
    const fight = sandbox.sequenceIcon('fight', 0, 0, '#fff', 1);
    const tree = sandbox.sequenceIcon('tree', 0, 0, '#fff', 1);
    sandbox.toolTextures = false;
    return /iron_sword/.test(fight) && /iron_axe/.test(tree)
      ? true : 'the tools are wrong';
  });

  // ---- who has the mod ----
  check('a player row says whether that client has Almin', () => {
    const withMod = sandbox.playerRow(
      { name: 'A', uuid: 'a', mask: '', hasMod: true, reported: true }, 'sub', 0);
    const without = sandbox.playerRow(
      { name: 'B', uuid: 'b', mask: '', hasMod: false, reported: false }, 'sub', 0);
    const text = (el) => deepText(el);
    if (!/Almin/.test(text(withMod))) return 'the modded client is not marked';
    return /vanilla/.test(text(without)) ? true : 'the vanilla client is not marked';
  });

  check('the client button waits for a client that has reported', () => {
    const quiet = sandbox.playerRow(
      { name: 'C', uuid: 'c', mask: '', hasMod: true, reported: false }, 'sub', 0);
    const spoke = sandbox.playerRow(
      { name: 'D', uuid: 'd', mask: '', hasMod: true, reported: true }, 'sub', 0);
    const button = (el) => {
      for (const kid of el.children) {
        for (const g of kid.children || []) if (g.textContent === 'Client') return g;
      }
      return null;
    };
    const a = button(quiet), b = button(spoke);
    if (!a || !b) return 'no client button was drawn';
    return (a.disabled && !b.disabled) ? true : 'it is offered whatever the client said';
  });

  check('a mod list separates what is there, what is new and what has gone', () => {
    const box = stub('div');
    byId.set('cl-test', box);
    const at = Date.now();
    sandbox.paintMods(box, [
      { id: 'almin', version: '2.22.0', firstSeen: at - 9e8, removedAt: 0, restricted: false },
      { id: 'sodium', version: '0.6.13', firstSeen: at, removedAt: 0, restricted: false },
      { id: 'xaerominimap', version: '24.2', firstSeen: at, removedAt: 0, restricted: true },
    ], at, false);
    const html = box.children.map((c) => c._html || '').join(' ');
    const classes = box.children.map((c) => c.className).join(' ');
    if (!/fresh/.test(classes)) return 'nothing was marked new';
    if (!/banned/.test(classes)) return 'the restricted one was not marked';
    return /restricted/.test(html) ? true : 'the restricted label is missing';
  });

  check('a removed mod is struck through and dated', () => {
    const box = stub('div');
    const at = Date.now();
    sandbox.paintMods(box, [
      { id: 'iris', version: '1.8', firstSeen: at - 9e8, removedAt: at - 2 * 864e5,
        restricted: false },
    ], at, true);
    const classes = box.children.map((c) => c.className).join(' ');
    const html = box.children.map((c) => c._html || '').join(' ');
    if (!/gone/.test(classes)) return 'it is not marked as gone';
    // A calendar date, not "2d 0h ago" — the question this answers is
    // "what changed on the day it broke", and an age does not line up
    // with a day.
    if (!/gone [A-Za-z0-9]/.test(html)) return 'it does not say when';
    return /last seen/.test(box.children[0].title || '')
      ? true : 'hovering it does not say how long ago';
  });

  // ---- restricted mods ----
  check('restricting mods waits for the client mod to be required', () => {
    sandbox.modsData = { advertise: true, denyKicks: false, requireClientMod: false,
                         showRestricted: false, restricted: '', restrictedKick: false };
    const gated = deepText(sandbox.restrictedSection());
    sandbox.modsData.requireClientMod = true;
    const open = deepText(sandbox.restrictedSection());
    if (!/Requires/.test(gated)) return 'it offered the list with no way to check it';
    return /mod id/i.test(open) ? true : 'it did not open when the mod is required';
  });

  check('...or being told to show it anyway', () => {
    sandbox.modsData = { advertise: true, denyKicks: false, requireClientMod: false,
                         showRestricted: true, restricted: 'xaerominimap',
                         restrictedKick: false };
    const shown = deepText(sandbox.restrictedSection());
    return !/Requires/.test(shown) ? true : 'the override did nothing';
  });

  // ---- somebody who has gone ----
  check('a face does not stand where somebody logged off', () => {
    const d = sandbox.allData;
    const alex = d.tracks.Alex;
    const leftAt = alex[alex.length - 1].at + 1000;
    d.actions.push({ at: leftAt, player: 'Alex', mask: 'Ghost', action: 'leave',
                     detail: '', dim: 'overworld', x: 30, y: 64, z: 70, count: 1 });
    sandbox.live = false;
    sandbox.cursorAt = d.to; sandbox.cursorSet = true;
    sandbox.view = { cx: 25, cz: 40, span: 2000, set: true };
    sandbox.paintAll();
    const html = byId.get('t-map')._html || '';
    d.actions.pop();
    sandbox.paintAll();
    if (!/class="thead gone"/.test(html)) return 'they were drawn as if still there';
    return /left here/.test(html) ? true : 'nothing said they had gone';
  });

  check('...and is drawn smaller than the people still here', () => {
    const d = sandbox.allData;
    const alex = d.tracks.Alex;
    const leftAt = alex[alex.length - 1].at + 1000;
    d.actions.push({ at: leftAt, player: 'Alex', mask: 'Ghost', action: 'leave',
                     detail: '', dim: 'overworld', x: 30, y: 64, z: 70, count: 1 });
    sandbox.paintAll();
    const html = byId.get('t-map')._html || '';
    d.actions.pop();
    sandbox.paintAll();
    const sizes = [...html.matchAll(/class="thead([^"]*)"[\s\S]{0,170}?width="([\d.]+)"/g)]
      .map((m) => ({ gone: /gone/.test(m[1]), w: +m[2] }));
    const left = sizes.find((x) => x.gone), here = sizes.find((x) => !x.gone);
    if (!left || !here) return 'expected one of each, got ' + JSON.stringify(sizes);
    return left.w < here.w * 0.8 ? true : left.w + ' against ' + here.w;
  });

  check('coming back undoes it', () => {
    const d = sandbox.allData;
    const alex = d.tracks.Alex;
    const last = alex[alex.length - 1].at;
    d.actions.push({ at: last - 5000, player: 'Alex', mask: '', action: 'leave',
                     detail: '', dim: 'overworld', x: 30, y: 64, z: 70, count: 1 });
    d.actions.push({ at: last - 2000, player: 'Alex', mask: '', action: 'join',
                     detail: '', dim: 'overworld', x: 30, y: 64, z: 70, count: 1 });
    sandbox.paintAll();
    const html = byId.get('t-map')._html || '';
    d.actions.pop(); d.actions.pop();
    sandbox.paintAll();
    return !/class="thead gone"/.test(html)
      ? true : 'a player who rejoined was still shown as gone';
  });

  // ---- which dimension ----
  check('there is a way to switch dimension', () => {
    sandbox.live = false;
    sandbox.cursorAt = sandbox.allData.to; sandbox.cursorSet = true;
    sandbox.paintAll();
    const dims = byId.get('t-dims');
    const html = deepText(dims) + (dims._html || '');
    // The fixture has an overworld and a nether point.
    return /Overworld/.test(html) && /Nether/.test(html)
      ? true : 'the switcher offered: ' + html.slice(0, 120);
  });

  check('a dimension with only a picture of it is still offered', () => {
    const was = sandbox.shots;
    sandbox.shots = was.concat([{ at: sandbox.allData.to, dim: 'the_end',
                                  minX: 0, minZ: 0, span: 384 }]);
    sandbox.paintAll();
    const html = byId.get('t-dims')._html || '';
    sandbox.shots = was;
    sandbox.paintAll();
    return /The End/.test(html) ? true : 'a dimension with ground was not listed';
  });

  check('the switcher names dimensions the way people do', () => {
    return sandbox.prettyDim('the_nether') === 'Nether'
      && sandbox.prettyDim('overworld') === 'Overworld'
      && sandbox.prettyDim('the_end') === 'The End'
      ? true : 'it still says the_nether';
  });

  check('a modded dimension still gets a readable name', () => {
    return sandbox.prettyDim('twilight_forest') === 'Twilight forest'
      ? true : 'it came out ' + sandbox.prettyDim('twilight_forest');
  });

  // ---- offline players ----
  check('an offline player is asked about their last visit', () => {
    sandbox.peopleData = sandbox.allData;
    const from = sandbox.lastSessionFrom('Steve');
    const track = sandbox.allData.tracks.Steve;
    if (!from) return 'no session was found';
    // The fixture's Steve has an early pair of points and then a gap of hours.
    return from > track[1].at
      ? true : 'it reached back past the gap to ' + new Date(from).toISOString();
  });

  check('...and a path with no gap in it is all one visit', () => {
    sandbox.peopleData = { tracks: { Solid: [
      { at: 1000, dim: 'overworld', x: 0, y: 64, z: 0 },
      { at: 2000, dim: 'overworld', x: 5, y: 64, z: 0 },
      { at: 3000, dim: 'overworld', x: 9, y: 64, z: 0 },
    ] } };
    const from = sandbox.lastSessionFrom('Solid');
    sandbox.peopleData = sandbox.allData;
    return from === 1000 ? true : 'it started at ' + from;
  });

  check('an offline player says so rather than saying nothing was recorded', () => {
    sandbox.peopleData = { tracks: {}, actions: [] };
    const strip = sandbox.actionStrip('Ghosty', 5000, true);
    const mini = sandbox.pathMap('Ghosty', 5000, true);
    sandbox.peopleData = sandbox.allData;
    if (!/last visit/.test(strip._html || '')) return 'the strip said the wrong nothing';
    return /last visit/.test(mini._html || '')
      ? true : 'the little map said the wrong nothing';
  });

  // ---- pointing at a face ----
  check('a face that has gone says when, both ways', () => {
    const at = Date.now() - 3 * 3600e3;
    const el = stub('g');
    el.getAttribute = (k) => ({ 'data-who': 'Alex', 'data-state': 'gone',
                                'data-at': String(at), 'data-still': '0' })[k];
    const said = sandbox.headStory(el);
    if (!/Alex left here/.test(said)) return 'it did not say who or what: ' + said;
    if (!/ago/.test(said)) return 'it did not say how long ago: ' + said;
    // And the clock, because "three hours ago" is a number you have to do
    // arithmetic on before it can be compared to anything else.
    return /, at /.test(said) ? true : 'it did not say the time: ' + said;
  });

  check('a face that has stopped moving says since when', () => {
    const el = stub('g');
    el.getAttribute = (k) => ({ 'data-who': 'Mika', 'data-state': 'afk',
                                'data-at': String(Date.now() - 240000),
                                'data-still': '240' })[k];
    const said = sandbox.headStory(el);
    return /not moving for/.test(said) && /since \d/.test(said)
      ? true : 'it said: ' + said;
  });

  check('a face that is still going says so', () => {
    const el = stub('g');
    el.getAttribute = (k) => ({ 'data-who': 'Steve', 'data-state': 'here',
                                'data-at': String(Date.now() - 4000),
                                'data-still': '4' })[k];
    const said = sandbox.headStory(el);
    return /here/.test(said) && !/left/.test(said) ? true : 'it said: ' + said;
  });

  check('the mark carries what the hover needs to say it', () => {
    const d = sandbox.allData;
    const alex = d.tracks.Alex;
    const leftAt = alex[alex.length - 1].at + 1000;
    d.actions.push({ at: leftAt, player: 'Alex', mask: '', action: 'leave',
                     detail: '', dim: 'overworld', x: 30, y: 64, z: 70, count: 1 });
    sandbox.live = false;
    sandbox.cursorAt = d.to; sandbox.cursorSet = true;
    sandbox.view = { cx: 25, cz: 40, span: 2000, set: true };
    sandbox.paintAll();
    const html = byId.get('t-map')._html || '';
    d.actions.pop();
    sandbox.paintAll();
    const m = /class="thead gone"[^>]*data-at="(\d+)"/.exec(html);
    if (!m) return 'the departed mark carries no moment';
    return Math.abs(+m[1] - leftAt) < 2000
      ? true : 'it carries the wrong moment';
  });

  // ---- turning it on ----
  check('the switch waits until there is something to talk to', () => {
    sandbox.aiState = { enabled: false, provider: 'local', model: '', baseUrl: '',
                        sendChat: true, autoMinutes: 0, hasKey: false, problem: '' };
    byId.get('s-aiprov').value = 'local';
    byId.get('s-aimodel').value = '';
    byId.get('s-aiurl').value = '';
    sandbox.aiFormChanged();
    const blank = byId.get('s-aion').disabled;
    byId.get('s-aimodel').value = 'qwen2.5:3b';
    byId.get('s-aiurl').value = 'http://127.0.0.1:11434/v1';
    sandbox.aiFormChanged();
    const filled = byId.get('s-aion').disabled;
    if (!blank) return 'it offered to turn on with nothing filled in';
    return !filled ? true : 'it stayed disabled when it was ready';
  });

  check('a missing key is not what holds it back', () => {
    // A local model does not want one, and refusing to switch on until
    // somebody typed a key they do not need is the wrong half of the check.
    sandbox.aiState = { enabled: false, provider: 'local', model: 'm',
                        baseUrl: 'http://x/v1', hasKey: false };
    byId.get('s-aiprov').value = 'local';
    byId.get('s-aimodel').value = 'm';
    byId.get('s-aiurl').value = 'http://x/v1';
    byId.get('s-aikey').value = '';
    sandbox.aiFormChanged();
    return sandbox.aiReady() ? true : 'it wanted a key it does not need';
  });

  check('a hosted provider is not asked for an address', () => {
    sandbox.aiState = { enabled: false, provider: 'anthropic', model: 'claude-haiku-4-5',
                        baseUrl: '', hasKey: true };
    byId.get('s-aiprov').value = 'anthropic';
    byId.get('s-aimodel').value = 'claude-haiku-4-5';
    sandbox.aiFormChanged();
    const hidden = byId.get('s-aiurlrow').style.display === 'none';
    const ready = sandbox.aiReady();
    byId.get('s-aiprov').value = 'local';
    sandbox.aiFormChanged();
    if (!hidden) return 'it still asked for one';
    return ready ? true : 'it would not turn on without one';
  });

  check('a hosted provider waits for a saved or typed key', () => {
    sandbox.aiState = { enabled: false, provider: 'openai', model: 'gpt-test',
                        baseUrl: '', hasKey: false };
    byId.get('s-aiprov').value = 'openai';
    byId.get('s-aimodel').value = 'gpt-test';
    byId.get('s-aikey').value = '';
    sandbox.aiFormChanged();
    const blocked = !sandbox.aiReady();
    byId.get('s-aikey').value = 'typed-now';
    sandbox.aiFormChanged();
    const ready = sandbox.aiReady();
    byId.get('s-aikey').value = '';
    return blocked && ready ? true : 'the hosted key readiness rule was wrong';
  });

  check('the form says which piece is missing', () => {
    byId.get('s-aiprov').value = 'local';
    byId.get('s-aimodel').value = '';
    byId.get('s-aiurl').value = '';
    sandbox.aiFormChanged();
    const missing = sandbox.aiMissing();
    // The address fills itself in with where a local model usually is, so the
    // only thing genuinely missing is the model name.
    const filledUrl = (byId.get('s-aiurl').value || '').startsWith('http');
    byId.get('s-aimodel').value = 'm';
    sandbox.aiFormChanged();
    if (!filledUrl) return 'it did not offer the usual local address';
    return missing.length === 1 && missing[0].includes('model')
      ? true : 'it said: ' + missing.join(', ');
  });

  // ---- what a sequence was for ----
  check("the model's reading of a stretch reaches the list", () => {
    sandbox.paintInsights();
    const html = byId.get('i-eps').children.map((c) =>
      (c._html || '') + (c.children || []).map((k) => k._html || '').join('')).join('');
    return /ore layer under the base/.test(html)
      ? true : 'the meaning did not reach the episode row';
  });

  check('...and is kept apart from what actually happened', () => {
    const e = sandbox.episodes[0];
    const means = sandbox.meaningFor(e);
    if (!means) return 'no meaning was found for the shaft';
    // The headline is the fact; the meaning is the reading of it.
    return means !== e.headline ? true : 'the reading is just the fact again';
  });

  check('a stretch the model said nothing about gets nothing', () => {
    const other = sandbox.episodes[1];
    return sandbox.meaningFor(other) === ''
      ? true : 'it borrowed another episode’s reading';
  });

  // ---- travel ----
  check('the boot glyph is drawn around its own centre', () => {
    const svg = sandbox.toolShape('boots', '#fff');
    const nums = (svg.match(/-?\d+(\.\d+)?/g) || []).map(Number);
    // Nothing should reach further than a badge's radius in any direction.
    return nums.every((n) => Math.abs(n) <= 11)
      ? true : 'it reaches ' + Math.max(...nums.map(Math.abs));
  });

  // ---- the coordinate grid ----
  check('the grid is drawn on round coordinates and says which', () => {
    sandbox.live = false;
    sandbox.cursorAt = sandbox.allData.to; sandbox.cursorSet = true;
    sandbox.mapOpts.grid = true;
    sandbox.view = { cx: 0, cz: 0, span: 700, set: true };
    sandbox.paintAll();
    const html = byId.get('t-map')._html || '';
    if (!/>x 0</.test(html)) return 'no x label';
    if (!/>z 0</.test(html)) return 'no z label';
    // Round numbers, not whatever the quarter of the screen happened to be.
    const labels = (html.match(/>x (-?\d+)</g) || []).map((m) => +m.slice(3, -1));
    return labels.every((v) => v % 8 === 0)
      ? true : 'the lines are not on round coordinates: ' + labels.join(',');
  });

  check('the grid follows the zoom rather than the screen', () => {
    sandbox.view = { cx: 0, cz: 0, span: 200, set: true };
    sandbox.paintAll();
    const near = (byId.get('t-map')._html || '').match(/>x (-?\d+)</g) || [];
    sandbox.view = { cx: 0, cz: 0, span: 6000, set: true };
    sandbox.paintAll();
    const far = (byId.get('t-map')._html || '').match(/>x (-?\d+)</g) || [];
    const step = (a) => (a.length > 1 ? Math.abs(+a[1].slice(3, -1) - +a[0].slice(3, -1)) : 0);
    sandbox.view = { cx: 25, cz: 40, span: 4000, set: true };
    sandbox.paintAll();
    return step(far) > step(near)
      ? true : 'zoomed out it stepped ' + step(far) + ', in ' + step(near);
  });

  check('the grid can be turned off', () => {
    sandbox.mapOpts.grid = false;
    sandbox.paintAll();
    const off = !/>x -?\d+</.test(byId.get('t-map')._html || '');
    sandbox.mapOpts.grid = true;
    sandbox.paintAll();
    return off ? true : 'it stayed';
  });

  // ---- two mod lists, and they are not the same thing ----
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
    const body = byId.get('modal-body')._html || '';
    sandbox.closeModal();
    if (!/unaffected until it restarts/.test(body)) return 'it does not say when it takes effect';
    // Turning it off is reversible and answers the same question, so it is
    // offered here rather than found later by someone who deleted a jar to
    // test a theory.
    return /Turn off/.test(body) ? true : 'it does not offer the reversible way';
  });

  check('installing on this server warns that a bad jar takes the server with it', () => {
    sandbox.uploadServerModDialog();
    const body = byId.get('modal-body')._html || '';
    sandbox.closeModal();
    return /takes the server with it/.test(body) && /until the server restarts/.test(body)
      ? true : 'the dialog does not say what a restart risks';
  });

  check('a Modrinth search says which of the two lists it is filling', () => {
    sandbox.modrinthDialog('server');
    const here = byId.get('modal-body')._html || '';
    sandbox.closeModal();
    sandbox.modrinthDialog('offer');
    const there = byId.get('modal-body')._html || '';
    sandbox.closeModal();
    if (!/mods\/<\/code> folder/.test(here)) return 'installing here does not say where it goes';
    return /offered to players/.test(there)
      ? true : 'offering does not say whose computer it lands on';
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

  check('a mod is never folded inside itself', () => {
    // Fabric API is called fabric-api, so the fallback below would have put
    // the parent into its own fold and taken it off the installed list.
    return sandbox.groupOf({ id: 'fabric-api', parent: '' }) === ''
      ? true : 'fabric-api grouped under ' + sandbox.groupOf({ id: 'fabric-api', parent: '' });
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

  // ---- menus that open when you press them ----
  check('a menu opened after another one closed is not eaten', () => {
    // The dismiss listener used to be added with {once:true} and never taken
    // off. A menu closed any other way left it armed, and the click that
    // opened the next menu spent it — closing that one on the spot.
    sandbox.closeMenu();
    sandbox.menu(10, 10, [{ label: 'One', run() {} }]);
    sandbox.closeMenu();                       // closed some other way
    sandbox.menu(10, 10, [{ label: 'Two', run() {} }]);
    const armed = docListeners.filter((f) => f.type === 'click').length;
    const open = !!sandbox.openMenu;
    sandbox.closeMenu();
    if (!open) return 'the second menu was gone before anyone could use it';
    return armed <= 1 ? true : armed + ' dismiss listeners were left behind';
  });

  check('closing a menu takes its dismiss listener with it', () => {
    sandbox.menu(10, 10, [{ label: 'One', run() {} }]);
    sandbox.closeMenu();
    return docListeners.filter((f) => f.type === 'click').length === 0
      ? true : 'a listener outlived the menu it belonged to';
  });

  // ---- two pictures, not one ----
  check('a fight and a build are different scenes', () => {
    return sandbox.sceneKind({ kind: 'build', events: 40 }) === 'build'
      && sandbox.sceneKind({ kind: 'pvp', events: 9 }) === 'fight'
      && sandbox.sceneKind({ kind: 'chat', events: 40 }) === ''
      ? true : 'it made ' + sandbox.sceneKind({ kind: 'pvp', events: 9 }) + ' of a fight';
  });

  const realData = sandbox.allData;
  check('a build scene leaves the blows out', () => {
    const at = Date.now();
    sandbox.allData = { actions: [
      { at: at, player: 'S', dim: 'overworld', action: 'place', detail: 'Oak Planks',
        x: 0, y: 64, z: 0, count: 1 },
      { at: at + 1, player: 'S', dim: 'overworld', action: 'place', detail: 'Oak Planks',
        x: 1, y: 64, z: 0, count: 1 },
      { at: at + 2, player: 'S', dim: 'overworld', action: 'attack', detail: 'Zombie',
        x: 2, y: 64, z: 0, count: 1 },
    ], to: at + 5 };
    const built = sandbox.sceneOf({ kind: 'build', events: 40, player: 'S',
      dim: 'overworld', from: at - 10, to: at + 10, x: 0, y: 64, z: 0 });
    if (!built) return 'no scene at all';
    return built.look === 'build' && built.cubes.length === 2 && built.marks.length === 0
      ? true : 'it drew ' + built.cubes.length + ' cubes and ' + built.marks.length + ' blows';
  });

  check('...and a fight scene leaves the blocks out', () => {
    const at = sandbox.allData.actions[0].at;
    const fight = sandbox.sceneOf({ kind: 'pvp', events: 9, player: 'S',
      dim: 'overworld', from: at - 10, to: at + 10, x: 0, y: 64, z: 0 });
    if (!fight) return 'no scene at all';
    return fight.look === 'fight' && fight.cubes.length === 0 && fight.marks.length === 1
      ? true : 'it drew ' + fight.cubes.length + ' cubes and ' + fight.marks.length + ' blows';
  });

  check('a fight can be replayed, since the blows are its steps', () => {
    const at = sandbox.allData.actions[0].at;
    sandbox.scene = sandbox.sceneOf({ kind: 'pvp', events: 9, player: 'S',
      dim: 'overworld', from: at - 10, to: at + 10, x: 0, y: 64, z: 0 });
    // Before this, playback counted cubes; a fight has none, so pressing
    // Replay went straight to the end and nothing moved.
    const steps = sandbox.sceneSteps();
    sandbox.scene = null;
    sandbox.allData = realData;          // the map tests below want the real one
    return steps === 1 ? true : 'it thought there were ' + steps;
  });

  // ---- crowded marks ----
  check('marks in one spot become one box with a count on it', () => {
    sandbox.live = false;
    sandbox.mapOpts.cluster = true;
    sandbox.view = { cx: 25, cz: 40, span: 4000, set: true };   // zoomed right out
    sandbox.cursorAt = sandbox.allData.to; sandbox.cursorSet = true;
    sandbox.paintAll();
    const html = byId.get('t-map')._html || '';
    if (!html.includes('class="tcl"')) return 'nothing was grouped';
    return /class="tcl"[\s\S]*?>1?\d+</.test(html) ? true : 'the box had no count';
  });

  check('zooming in separates what was grouped', () => {
    sandbox.view = { cx: 23, cz: 33, span: 12, set: true };
    sandbox.paintAll();
    const close = byId.get('t-map')._html || '';
    sandbox.view = { cx: 25, cz: 40, span: 4000, set: true };
    sandbox.paintAll();
    const far = byId.get('t-map')._html || '';
    const closeGroups = (close.match(/class="tcl"/g) || []).length;
    const farGroups = (far.match(/class="tcl"/g) || []).length;
    return farGroups > closeGroups
      ? true : 'zoomed in had ' + closeGroups + ', out had ' + farGroups;
  });

  check('grouping can be turned off', () => {
    sandbox.mapOpts.cluster = false;
    sandbox.paintAll();
    const none = !(byId.get('t-map')._html || '').includes('class="tcl"');
    sandbox.mapOpts.cluster = true;
    sandbox.paintAll();
    return none ? true : 'it grouped anyway';
  });

  check('identical things in a box are folded with a count', () => {
    const items = [
      { player: 'Steve', action: 'break', detail: 'Stone', at: 100, count: 4 },
      { player: 'Steve', action: 'break', detail: 'Stone', at: 200, count: 7 },
      { player: 'Steve', action: 'place', detail: 'Torch', at: 300, count: 1 },
    ];
    const rows = sandbox.clusterList(items);
    if (rows.length !== 2) return 'folded into ' + rows.length + ' rows, expected 2';
    const stone = rows.find((r) => r.a.detail === 'Stone');
    return stone && stone.n === 11 ? true : 'the count was ' + (stone && stone.n);
  });

  check('a cluster box survives the repaint that follows it', () => {
    sandbox.mapOpts.cluster = true;
    sandbox.view = { cx: 25, cz: 40, span: 4000, set: true };
    sandbox.paintAll();
    // What the click handler does, without a DOM click: name a place and draw.
    sandbox.clusterAt = { items: sandbox.allData.actions.slice(0, 3), x: 23, z: 33 };
    sandbox.drawCluster();
    const first = !!byId.get('t-cluster');
    sandbox.paintAll();                       // a playback frame, or a live refresh
    const after = sandbox.clusterAt !== null;
    sandbox.closeCluster();
    if (!first) return 'it never opened';
    if (!after) return 'a repaint threw it away';
    return sandbox.clusterAt === null ? true : 'it would not close';
  });

  check('a cluster is not a place to start dragging the map', () => {
    const src = fs.readFileSync(process.argv[2], 'utf8');
    const i = src.indexOf('function wireMapGestures');
    const body = src.slice(i, i + 1400);
    return /closest\('\.tcl'\)/.test(body)
      ? true : 'a click on a cluster would begin a pan and lose the click';
  });

  // ---- how far out it goes ----
  check('the period stops at the end of the saved data', () => {
    const d = sandbox.allData;
    const realTo = d.to;
    d.now = realTo + 6 * 3600e3;              // nobody on for six hours
    sandbox.live = true;
    sandbox.win.set = false;
    sandbox.paintAll();
    const stopped = Math.abs(sandbox.tl.to - realTo) < 1000;
    const cursorOnData = Math.abs(sandbox.cursorAt - realTo) < 1000;
    d.now = realTo;
    sandbox.paintAll();
    if (!stopped) return 'it ran ' + ((sandbox.tl.to - realTo) / 3600e3).toFixed(1) + 'h past';
    return cursorOnData ? true : 'the live cursor left the data behind';
  });

  check('live says so when the newest thing is old', () => {
    const d = sandbox.allData;
    const realTo = d.to;
    d.now = realTo + 3 * 3600e3;
    sandbox.live = true;
    sandbox.paintAll();
    const said = byId.get('t-at').textContent;
    d.now = realTo;
    sandbox.paintAll();
    return /nothing since/.test(said) ? true : 'it just said "' + said + '"';
  });

  check('the darkening covers the ground rather than half of it', () => {
    sandbox.paintAll();
    const html = byId.get('t-map')._html || '';
    // The scrim is inside the clip, the hatch is deliberately outside it and
    // far larger, so no strip of the viewport is left bare.
    if (!/clipPath id="mapclip"/.test(html)) return 'nothing is clipped';
    if (!/<g clip-path="url\(#mapclip\)">/.test(html)) return 'the map is not inside the clip';
    const hatch = html.match(/<rect x="(-?\d+)" y="(-?\d+)" width="(\d+)" height="(\d+)" fill="url\(#unknown\)"\/>/);
    if (!hatch) return 'no backing';
    return (+hatch[1] < 0 && +hatch[3] > 1000)
      ? true : 'the backing only covers the viewBox';
  });

  // ---- fullscreen ----
  check('fullscreen hands the window to the map', () => {
    sandbox.setFull(true);
    const cls = byId.get('t-layout').className;
    const html = byId.get('t-map')._html || '';
    sandbox.setFull(false);
    if (!/\bfullmap\b/.test(cls)) return 'the layout did not change';
    return /id="t-full"/.test(html) ? true : 'no way back out was drawn';
  });

  check('leaving fullscreen puts it back', () => {
    sandbox.setFull(true);
    sandbox.setFull(false);
    return !/\bfullmap\b/.test(byId.get('t-layout').className)
      ? true : 'it stayed fullscreen';
  });

  check('the timeline and the side list are the same elements either way', () => {
    sandbox.setFull(true);
    const line = byId.get('t-line'), side = byId.get('t-side');
    sandbox.setFull(false);
    // Same nodes, so nothing is rebuilt and nothing loses its listeners.
    return (byId.get('t-line') === line && byId.get('t-side') === side)
      ? true : 'they were rebuilt';
  });

  // ---- live ----
  check('the map starts live and says so instead of offering Play', () => {
    sandbox.live = true;
    sandbox.paintAll();
    const pill = byId.get('t-livepill'), play = byId.get('t-play');
    if (!pill || pill.style.display === 'none') return 'no LIVE badge';
    if (!play || play.style.display !== 'none') return 'Play was still offered';
    const speed = byId.get('t-speed'), skip = byId.get('t-skip');
    return (speed.style.display === 'none' && skip.style.display === 'none')
      ? true : 'the time controls were still there';
  });

  check('live pins the cursor to the newest saved moment', () => {
    sandbox.live = true;
    sandbox.cursorAt = sandbox.allData.from;      // somewhere in the past
    sandbox.paintAll();
    const drift = Math.abs(sandbox.cursorAt - sandbox.allData.to);
    return drift < 2000 ? true : 'the cursor was ' + drift + 'ms off the end';
  });

  check('scrubbing the timeline leaves live and brings the buttons back', () => {
    sandbox.live = true;
    sandbox.paintAll();
    sandbox.cursorAt = sandbox.allData.from + 60000;
    sandbox.cursorSet = true;
    sandbox.live = false;
    sandbox.paintAll();
    const play = byId.get('t-play'), pill = byId.get('t-livepill');
    if (play.style.display === 'none') return 'Play did not come back';
    if (pill.style.display !== 'none') return 'the LIVE badge stayed';
    return byId.get('t-golive').style.display !== 'none'
      ? true : 'no way back to live was offered';
  });

  check('pressing play leaves live', () => {
    sandbox.live = true;
    sandbox.togglePlay();
    const left = sandbox.live === false;
    sandbox.stopPlay();
    return left ? true : 'it played while claiming to be live';
  });

  check('going back to live re-frames the whole period', () => {
    sandbox.live = false;
    sandbox.win = { from: sandbox.allData.from, to: sandbox.allData.from + 1000, set: true };
    sandbox.goLive();
    return sandbox.live === true && sandbox.win.set === false
      ? true : 'the slice stayed pinned';
  });

  // ---- what it all meant ----
  check('episodes are listed as sentences, not rows', () => {
    sandbox.paintInsights();
    const html = byId.get('i-eps').children.map((c) =>
      (c._html || '') + (c.children || []).map((k) => k._html || '').join('')).join('');
    return /Dug a shaft from y 64 down to y 11/.test(html)
      ? true : 'the shaft episode was not shown';
  });

  check('the summary and its moments are shown', () => {
    sandbox.paintInsights();
    const html = byId.get('i-ai')._html || '';
    if (!/Steve spent the evening underground/.test(html)) return 'no summary';
    // The stub keeps children across repaints where a browser would replace
    // them, so this asks what was drawn rather than how many times.
    const moments = byId.get('i-moments');
    if (!moments || !moments.children.length) return 'the moment was not listed';
    const rows = moments.children.map((c) => c._html || '').join('');
    if (!/A shaft straight down/.test(rows)) return 'the moment had no label';
    return /bedrock in one go/.test(rows) ? true : 'the reason was dropped';
  });

  check('a moment is marked on the timeline', () => {
    sandbox.paintTimeline();
    const html = byId.get('t-line')._html || '';
    return /A shaft straight down/.test(html) ? true : 'nothing marked the moment';
  });

  check('with no model configured it says so rather than failing', () => {
    const was = sandbox.aiStatus;
    sandbox.aiStatus = { enabled: false, provider: 'local', model: '', baseUrl: '',
                         sendChat: true, autoMinutes: 0, hasKey: false, problem: '' };
    sandbox.paintInsights();
    const html = byId.get('i-ai')._html || '';
    const disabled = byId.get('i-run').disabled === true;
    sandbox.aiStatus = was;
    sandbox.paintInsights();
    if (!/Summaries are off/.test(html)) return 'it did not explain why';
    return disabled ? true : 'the button was still live';
  });

  check('a provider failure is shown as itself', () => {
    const was = sandbox.aiReport;
    sandbox.aiReport = { error: 'The service rejected the API key', moments: [], summary: '' };
    sandbox.paintInsights();
    const html = byId.get('i-ai')._html || '';
    sandbox.aiReport = was;
    sandbox.paintInsights();
    return /rejected the API key/.test(html) ? true : 'the failure was swallowed';
  });

  check('the timeline window stays inside the period', () => {
    const d = sandbox.allData;
    sandbox.win = { from: d.from - 9e9, to: d.to + 9e9, set: true };
    sandbox.paintTimeline();
    const ok = sandbox.win.from >= d.from - 1 && sandbox.win.to <= d.to + 1;
    sandbox.win = { from: 0, to: 0, set: false };
    return ok ? true : 'window escaped to ' + sandbox.win.from + '..' + sandbox.win.to;
  });

  check('a window zoomed to nothing is opened back up', () => {
    const d = sandbox.allData;
    const mid = (d.from + d.to) / 2;
    sandbox.win = { from: mid, to: mid, set: true };
    sandbox.paintTimeline();
    const ok = sandbox.win.to - sandbox.win.from >= 2000;
    sandbox.win = { from: 0, to: 0, set: false };
    return ok ? true : 'it stayed at ' + (sandbox.win.to - sandbox.win.from) + 'ms';
  });

  check('the timeline marks the quiet stretch', () => {
    sandbox.paintTimeline();
    const html = byId.get('t-line')._html || '';
    return /nobody on/.test(html) && /url\(#quiet\)/.test(html)
      ? true : 'the gap is not drawn';
  });

  check('gestures are bound to what survives a repaint, not to the SVG', () => {
    // paintAll replaces the map and the timeline wholesale on every frame of
    // playback. A pointer listener on the SVG dies the moment the first drag
    // causes a paint, which is to say straight away — so the listeners have to
    // live on the containers, and be attached once.
    const src = fs.readFileSync(process.argv[2], 'utf8');
    const tl = src.slice(src.indexOf('function wireTimeline()'),
                         src.indexOf('function wireMapGestures()'));
    if (!/const host=\$\('t-line'\)/.test(tl)) return 'the timeline binds elsewhere';
    if (/svg\.addEventListener/.test(tl)) return 'the timeline still binds to the svg';
    if (!/host\.almWired/.test(tl)) return 'the timeline can be bound twice';
    const mp = src.slice(src.indexOf('function wireMapGestures()'),
                         src.indexOf('function wireMapButtons()'));
    if (!/const host=\$\('t-map'\)/.test(mp)) return 'the map binds elsewhere';
    if (/svg\.addEventListener/.test(mp)) return 'the map still binds to the svg';
    if (!/host\.almWired/.test(mp)) return 'the map can be bound twice';
    return true;
  });

  check('wiring twice does not stack listeners', () => {
    const host = byId.get('t-line');
    host.almWired = false;
    let added = 0;
    const real = host.addEventListener;
    host.addEventListener = () => { added++; };
    sandbox.wireTimeline();
    const first = added;
    sandbox.wireTimeline();
    sandbox.wireTimeline();
    host.addEventListener = real;
    return added === first && first > 0
      ? true : 'bound ' + added + ' listeners over three calls';
  });

  // ---- the update countdown ----
  sandbox.tab = 'settings'; sandbox.render();
  await new Promise((r) => setTimeout(r, 20));

  check('the update button opens a dialog rather than a browser confirm', () => {
    sandbox.updateInfo = { current: '2.15.0', latest: '2.16.0', hasJar: true,
                           status: 'available' };
    sandbox.updateDialog();
    const html = byId.get('up-msg') ? 'ok' : 'no dialog';
    if (html !== 'ok') return html;
    if (!byId.has('up-go') || !byId.has('up-no')) return 'no install/cancel buttons';
    return true;
  });

  try {
    intervals.length = 0;
    responses['/api/update'] = { ok: true, restarting: true, relaunch: true,
                                 message: 'Installed 2.16.0.' };
    await sandbox.applyUpdate();
    const shown = byId.get('cd-num').textContent;
    const ok = String(shown) === '20';
    console.log((ok ? '  PASS  ' : '  FAIL  ') + 'the countdown starts at twenty');
    if (!ok) failures.push('countdown started at ' + shown);
  } catch (e) {
    console.log('  FAIL  the countdown starts at twenty  -> ' + e.message);
    failures.push('countdown: ' + e.message);
  }

  check('it counts down and drains the bar', () => {
    tick(5);
    if (byId.get('cd-num').textContent !== 15) return 'showed ' + byId.get('cd-num').textContent;
    const w = byId.get('cd-bar').style.width;
    return w === '75%' ? true : 'bar at ' + w;
  });

  check('closing the dialog stops it reloading the page', () => {
    const before = sandbox.reloads;
    sandbox.closeModal();
    tick(30);                       // well past zero
    return sandbox.reloads === before ? true : 'it reloaded anyway';
  });

  try {
    const before = sandbox.reloads;
    await sandbox.finishCountdown(0);       // a generation that has been retired
    const ok = sandbox.reloads === before;
    console.log((ok ? '  PASS  ' : '  FAIL  ') + 'a retired countdown will not reload later');
    if (!ok) failures.push('retired countdown still reloaded');
  } catch (e) {
    console.log('  FAIL  a retired countdown will not reload later  -> ' + e.message);
    failures.push('retired countdown: ' + e.message);
  }

  check('an update that is not restarting promises no reload', () => {
    intervals.length = 0;
    responses['/api/update'] = { ok: false, restarting: false,
                                 error: 'Already on the newest version.' };
    sandbox.updateDialog();
    return true;
  });
  try {
    await sandbox.applyUpdate();
    const counting = intervals.some((i) => i);
    console.log((counting ? '  FAIL  ' : '  PASS  ') +
      'no countdown when nothing is restarting');
    if (counting) failures.push('counted down over a no-op update');
  } catch (e) {
    console.log('  FAIL  no countdown when nothing is restarting  -> ' + e.message);
    failures.push('no-op update: ' + e.message);
  }

  try {
    // Installed, but nothing here can start the server again: the page must
    // not promise a reload it cannot deliver.
    intervals.length = 0;
    sandbox.closeModal();
    responses['/api/update'] = { ok: true, restarting: true, relaunch: false,
                                 message: 'Installed.' };
    sandbox.updateDialog();
    await sandbox.applyUpdate();
    const counting = intervals.some((i) => i);
    console.log((counting ? '  FAIL  ' : '  PASS  ') +
      'no countdown when nothing will bring the server back');
    if (counting) failures.push('promised a reload with no relaunch');
    sandbox.closeModal();
    responses['/api/update'] = { current: '2.5.0', repo: 'a/b', status: 'available',
                                 latest: '2.6.0', hasJar: true };
  } catch (e) {
    console.log('  FAIL  no countdown without a relaunch  -> ' + e.message);
    failures.push('no relaunch: ' + e.message);
  }

  // ---- asking the model things (all of these go over the wire) ----
  try {
    sandbox.tab = 'activity'; sandbox.render();
    await new Promise((r) => setTimeout(r, 20));
    sandbox.clearFilter(); sandbox.focusPlayer = '';
    byId.get('i-ask').value = 'lava near spawn';
    let askBody = null;
    const askFetch = sandbox.fetch;
    sandbox.fetch = async (url, init) => {
      if (String(url).split('?')[0] === '/api/insights/find' && init && init.body) {
        askBody = JSON.parse(init.body);
      }
      return askFetch(url, init);
    };
    await sandbox.runAsk(false);

    const answerOnly = sandbox.filt.acts.size === 0 && sandbox.filt.items.size === 0
      && sandbox.focusPlayer === ''
      && /Filtered to lava going down near spawn/.test(deepText(byId.get('i-asked')))
      && /Find on map/.test(deepText(byId.get('i-asked')));
    console.log((answerOnly ? '  PASS  ' : '  FAIL  ') +
      'Ask answers from Activity data without changing the map');
    if (!answerOnly) failures.push('Ask changed the Activity filter or did not answer');

    const windowed = askBody && askBody.scope === 'all'
      && Number.isFinite(askBody.from) && Number.isFinite(askBody.to)
      && askBody.to >= askBody.from;
    console.log((windowed ? '  PASS  ' : '  FAIL  ') +
      'Activity questions carry the selected scope and timeline window');
    if (!windowed) failures.push('Ask omitted its Activity scope/window');

    await sandbox.runAsk(true);
    sandbox.fetch = askFetch;

    const filtered = sandbox.filt.acts.has('place') && sandbox.filt.acts.has('use')
      && sandbox.filt.items.has('Lava Bucket') && sandbox.filt.kinds.has('hazard');
    console.log((filtered ? '  PASS  ' : '  FAIL  ') +
      'what you asked for becomes the filter, not a hidden list');
    if (!filtered) failures.push('the lens did not reach the filter: ' +
      [...sandbox.filt.acts].join(',') + ' / ' + [...sandbox.filt.items].join(','));

    // One player named is a focus, which the map already does better than a
    // filter would.
    const focused = sandbox.focusPlayer === 'Steve';
    console.log((focused ? '  PASS  ' : '  FAIL  ') +
      'one player named becomes a focus rather than a filter row');
    if (!focused) failures.push('the lens did not focus Steve');

    const said = deepText(byId.get('i-asked'));
    const explained = /Filtered to lava going down near spawn/.test(said)
      && /Change it in/.test(said);
    console.log((explained ? '  PASS  ' : '  FAIL  ') +
      'it says what it decided and how to change it');
    if (!explained) failures.push('the lens explained nothing: ' + said.slice(0, 120));

    sandbox.filt.acts.add('place');
    byId.get('i-askclear').onclick();
    const cleared = sandbox.filt.acts.size === 0 && !deepText(byId.get('i-asked')).trim();
    console.log((cleared ? '  PASS  ' : '  FAIL  ') + 'Clear puts the map back');
    if (!cleared) failures.push('Clear left the filter behind');
  } catch (e) {
    console.log('  FAIL  asking for something  -> ' + e.message);
    failures.push('ask: ' + e.message);
  }

  try {
    const c = responses['/api/client'];
    byId.set('cl-review', stub('div'));
    byId.set('cl-mods', stub('div'));
    byId.set('cl-bundled', stub('div'));
    byId.set('cl-gone', stub('div'));
    byId.set('cl-ask', stub('button'));
    await sandbox.reviewMods({ name: 'Steve', uuid: 'u' }, c);

    const box = deepText(byId.get('cl-review'));
    const said = /Mostly performance mods/.test(box);
    console.log((said ? '  PASS  ' : '  FAIL  ') + 'the model says what the list is');
    if (!said) failures.push('no mod summary: ' + box.slice(0, 100));

    // Only the two that are actually a question. Calling forty ordinary mods
    // "fine" one at a time is noise, and unfair to the player.
    const rows = (byId.get('cl-flags') || { children: [] }).children.map(deepText).join(' ');
    const pointed = /xray/.test(rows) && /xaerominimap/.test(rows) && !/sodium/.test(rows);
    console.log((pointed ? '  PASS  ' : '  FAIL  ') +
      'only the mods that are a question are listed');
    if (!pointed) failures.push('flag list was: ' + rows.slice(0, 140));

    const hedged = /not evidence of anything/.test(box) && /can be wrong/.test(box);
    console.log((hedged ? '  PASS  ' : '  FAIL  ') +
      'it says plainly that none of this is proof');
    if (!hedged) failures.push('the mod review made a claim it cannot support');

    // And the flag reaches the row itself, where somebody scanning the list
    // will actually see it.
    const marked = (byId.get('cl-mods')._html || '') +
      byId.get('cl-mods').children.map((k) => k._html || '').join(' ');
    const onRow = /concern|watch/.test(marked);
    console.log((onRow ? '  PASS  ' : '  FAIL  ') + 'the flag lands on the mod row too');
    if (!onRow) failures.push('the row does not carry the flag');
  } catch (e) {
    console.log('  FAIL  the model on a mod list  -> ' + e.message);
    failures.push('mod review: ' + e.message);
  }

  // ---- the optional full-world renderer ----
  try {
    responses['/api/bluemap'] = { installed: true, enabled: true, loaded: true,
      configured: true, ready: true, restartRequired: false, port: 8100,
      version: '5.23', message: 'connected', path: '/bluemap/' };
    sandbox.tab = 'activity'; sandbox.render();
    await sandbox.loadBlueMapStatus(true);
    await sandbox.loadAll();
    await sandbox.loadInsights();
    sandbox.focusPlayer = '';
    sandbox.clearFilter();
    sandbox.allDim = 'overworld';
    sandbox.cursorAt = responses['/api/track'].to;
    sandbox.cursorSet = true;
    sandbox.setBlueMapMode('world');
    sandbox.paintAll();

    const world = sandbox.usingBlueMap() && /t-blue-frame/.test(byId.get('t-map')._html || '');
    console.log((world ? '  PASS  ' : '  FAIL  ') +
      'a connected BlueMap becomes the main activity map');
    if (!world) failures.push('BlueMap did not become the main renderer');

    sandbox.paintBlueMapChoice();
    const resetButton=(byId.get('t-map-choice').children||[])
      .find((b)=>b.textContent==='Reset renders…');
    const resetOffered=!!resetButton;
    console.log((resetOffered ? '  PASS  ' : '  FAIL  ') +
      'a connected BlueMap offers a render reset');
    if (!resetOffered) failures.push('BlueMap render reset button is missing');

    let payload = sandbox.bluePendingState || {};
    const coldStartSafe=!(payload.scenes||[]).some(m=>m.kind==='block-change');
    console.log((coldStartSafe ? '  PASS  ' : '  FAIL  ') +
      'BlueMap startup waits for a camera before creating block geometry');
    if (!coldStartSafe) failures.push('BlueMap created world-wide block boxes before its camera');

    sandbox.blueCamera={x:0,y:70,z:0,distance:300,map:'world'};
    sandbox.paintAll();
    payload = sandbox.bluePendingState || {};
    const carried = (payload.markers || []).length > 0 && (payload.lines || []).length > 0
      && (payload.grid || []).some((g) => g.type === 'label');
    console.log((carried ? '  PASS  ' : '  FAIL  ') +
      'the 3D renderer receives actions, player paths and a labelled coordinate grid');
    if (!carried) failures.push('BlueMap payload omitted map features');

    const clusterHtml=sandbox.blueClusterHtml([
      {at:clock-3000,player:'Steve',mask:'',action:'break',detail:'Stone',count:2,x:4,y:63,z:8},
      {at:clock-2000,player:'Alex',mask:'Builder',action:'place',detail:'Oak Planks',count:3,x:6,y:64,z:10}
    ]);
    const clusterLists=/Steve/.test(clusterHtml)&&/Stone/.test(clusterHtml)&&
      /Builder/.test(clusterHtml)&&/Oak Planks/.test(clusterHtml)&&/×3/.test(clusterHtml);
    console.log((clusterLists ? '  PASS  ' : '  FAIL  ') +
      'a BlueMap cluster lists the players, actions, items and counts inside it');
    if (!clusterLists) failures.push('BlueMap cluster still only shows a grouped-action count');

    const leftAt=clock-60*60000;
    const leftData={shownNames:['Alex'],shownActs:[],tracks:{Alex:[{at:leftAt-1000,
      dim:'overworld',x:12,y:64,z:18}]},ids:{Alex:'00000000-0000-0000-0000-0000000000bb'},
      online:[],away:{Alex:{at:leftAt,gone:true}},afkSecs:20,cursor:clock,now:clock,
      leftPlayerHours:24,windowMs:4*3600000};
    const wasLive=sandbox.live; sandbox.live=true;
    const departed=sandbox.blueMapPayload(leftData,[],[],[]).players[0];
    const leftHead=departed&&departed.gone&&departed.text==='Alex'&&
      departed.title.includes('Alex · left at '+sandbox.fmtWhen(leftAt))&&
      !departed.title.includes('afk');
    console.log((leftHead ? '  PASS  ' : '  FAIL  ') +
      'a departed BlueMap player is a timed head, not an AFK player');
    if (!leftHead) failures.push('departed BlueMap head has the wrong state or hover text');
    leftData.now=leftAt+24*3600000+1;
    const expired=sandbox.blueMapPayload(leftData,[],[],[]).players.length===0;
    console.log((expired ? '  PASS  ' : '  FAIL  ') +
      'a departed BlueMap head disappears after its configured retention');
    if (!expired) failures.push('expired departed BlueMap head remained visible');
    sandbox.live=wasLive;

    const pathOpacity=[...new Set((payload.lines||[]).filter(l=>
      String(l.id||'').startsWith('path-')).map(l=>l.opacity))];
    const pathFades=pathOpacity.length>1;
    console.log((pathFades ? '  PASS  ' : '  FAIL  ') +
      'BlueMap player tracks carry age-banded opacity');
    if (!pathFades) failures.push('BlueMap paths did not fade by segment age');

    const blueLayers=sandbox.mapOptionsHtml();
    const blueBlockControls=blueLayers.includes('<button id="o-blocks"') &&
      blueLayers.includes('id="o-blockmins"');
    console.log((blueBlockControls ? '  PASS  ' : '  FAIL  ') +
      'BlueMap gives recent block outlines their own layer button and timer');
    if (!blueBlockControls) failures.push('BlueMap recent block controls are missing');

    const recentBlocks=(payload.scenes||[]).filter(m=>m.kind==='block-change');
    const automaticBlocks=recentBlocks.some(m=>m.color==='#48df6b') &&
      recentBlocks.some(m=>m.color==='#ff565d') &&
      recentBlocks.every(m=>m.opacity>0&&m.opacity<=1) &&
      recentBlocks.every(m=>/^recent-block--?\d+--?\d+--?\d+$/.test(m.id));
    console.log((automaticBlocks ? '  PASS  ' : '  FAIL  ') +
      'recent placed and broken blocks appear automatically and carry fade opacity');
    if (!automaticBlocks) failures.push('automatic BlueMap block outlines are missing or unfaded');

    const savedActions=sandbox.allData.actions;
    sandbox.allData.actions=savedActions.concat(Array.from({length:900},(_,i)=>({
      at:sandbox.cursorAt-i,player:'Load',mask:'',action:i%2?'place':'break',detail:'Stone',
      dim:'overworld',x:(i%30)-15,y:40+Math.floor(i/900),z:Math.floor(i/30)-15,count:1
    })));
    sandbox.paintAll();
    const busyBlocks=(sandbox.bluePendingState.scenes||[]).filter(m=>m.kind==='block-change');
    const bounded=busyBlocks.length===500 && new Set(busyBlocks.map(m=>m.id)).size===500;
    console.log((bounded ? '  PASS  ' : '  FAIL  ') +
      'a busy server caps recent block geometry at 500 stable markers');
    if (!bounded) failures.push('busy BlueMap block geometry was not bounded');
    sandbox.allData.actions=savedActions;
    sandbox.paintAll();
    payload=sandbox.bluePendingState||{};

    const oldBlockMinutes=sandbox.mapOpts.blockMinutes;
    sandbox.mapOpts.blockMinutes=10;
    const blockClock=sandbox.blockChangeOpacity(0)===1 &&
      Math.abs(sandbox.blockChangeOpacity(5*60000)-.5)<.001 &&
      sandbox.blockChangeOpacity(10*60000)===0;
    sandbox.mapOpts.blockMinutes=oldBlockMinutes;
    console.log((blockClock ? '  PASS  ' : '  FAIL  ') +
      'block outlines use their own adjustable fade timer');
    if (!blockClock) failures.push('recent block clock is not independent and linear');

    const oldLayers = { actions: sandbox.mapOpts.actions, paths: sandbox.mapOpts.paths,
      blocks: sandbox.mapOpts.blocks, players: sandbox.mapOpts.players,
      sequences: sandbox.mapOpts.sequences,
      grid: sandbox.mapOpts.grid };
    Object.assign(sandbox.mapOpts, { actions: false, blocks: false, paths: false, players: false,
      sequences: false, grid: false });
    sandbox.paintAll();
    const filtered = sandbox.bluePendingState || {};
    const layersOff = ['markers', 'lines', 'players', 'scenes', 'grid']
      .every((key) => !(filtered[key] || []).length);
    console.log((layersOff ? '  PASS  ' : '  FAIL  ') +
      'BlueMap layer buttons can hide activity, blocks, paths, players, events and the grid');
    if (!layersOff) failures.push('a disabled BlueMap layer remained in the payload');
    Object.assign(sandbox.mapOpts, oldLayers);
    sandbox.paintAll();

    const build = sandbox.episodes.find((e) => sandbox.sceneKind(e) === 'build');
    if (build) {
      sandbox.allData.tracks[build.player].push({ at: build.from + 1000, dim: build.dim,
        x: build.x, y: build.y + 7, z: build.z });
      sandbox.openBlueScene(build);
    }
    const scene = sandbox.bluePendingState || {};
    const inWorld = (scene.scenes || []).some((m) => m.type === 'box')
      && (scene.scenes || []).some((m) => m.kind === 'scene-player');
    console.log((inWorld ? '  PASS  ' : '  FAIL  ') +
      'a selected 3D build is made of world-coordinate blocks and altitude-aware players');
    if (!inWorld) failures.push('the BlueMap build stayed a badge or lost player altitude');

    sandbox.setBlueMapMode('legacy');
    sandbox.paintAll();
    const legacy = !sandbox.usingBlueMap() && /id="t-svg"/.test(byId.get('t-map')._html || '');
    console.log((legacy ? '  PASS  ' : '  FAIL  ') + 'legacy 2D remains available');
    if (!legacy) failures.push('legacy map was replaced rather than retained');

    responses['/api/bluemap'] = { installed: false, enabled: false, loaded: false,
      configured: false, ready: false, restartRequired: false, port: 8100,
      version: '', message: 'not installed', path: '/bluemap/' };
  } catch (e) {
    console.log('  FAIL  the optional full-world renderer  -> ' + e.message);
    failures.push('BlueMap renderer: ' + e.message);
  }

  // ---- saving the model settings ----
  // Both halves of "Save does nothing": the address that was never sent, and
  // the confirmation that was never shown because the click event landed in
  // the parameter that means "say nothing".
  try {
    sandbox.tab = 'settings'; sandbox.settingsTab = 'almin'; sandbox.render();
    await new Promise((r) => setTimeout(r, 20));

    const realFetch = sandbox.fetch;
    let posted = [];
    sandbox.fetch = async (url, init) => {
      let body = null;
      try { body = init && init.body ? JSON.parse(init.body) : null; } catch (e) { body = null; }
      posted.push({ url: String(url).split('?')[0], body });
      return { status: 200, json: async () => bodyFor(url) };
    };

    async function saveAs(provider, address) {
      byId.get('s-aiprov').value = provider;
      byId.get('s-aimodel').value = 'some-model';
      byId.get('s-aiurl').value = address;
      byId.get('s-aitimeout').value = '900';
      sandbox.aiFormChanged();
      posted = [];
      // A real click hands the handler an event. That argument is what broke
      // the confirmation, so the harness has to pass one too.
      await byId.get('s-aisave').onclick({ type: 'click' });
      const sent = new Map(posted.filter((x) => x.url === '/api/config' && x.body)
                                 .map((x) => [x.body.name, x.body.value]));
      return sent;
    }

    const custom = await saveAs('custom', 'https://openrouter.ai/api/v1');
    const keptUrl = custom.get('ai-base-url') === 'https://openrouter.ai/api/v1';
    console.log((keptUrl ? '  PASS  ' : '  FAIL  ') +
      'a custom endpoint address is actually sent to the server');
    if (!keptUrl) failures.push('ai-base-url not saved for custom: ' + custom.get('ai-base-url'));

    const keptTimeout = custom.get('ai-timeout-seconds') === '900';
    console.log((keptTimeout ? '  PASS  ' : '  FAIL  ') +
      'a custom endpoint wait time is actually sent to the server');
    if (!keptTimeout) failures.push('ai-timeout-seconds not saved for custom');

    const said = byId.get('s-aimsg').textContent || '';
    const confirmed = /Saved/.test(said);
    console.log((confirmed ? '  PASS  ' : '  FAIL  ') +
      'pressing Save says so, rather than looking like nothing happened');
    if (!confirmed) failures.push('Save gave no confirmation: "' + said + '"');

    const google = await saveAs('google', 'https://gemini.example/v1beta');
    const geminiUrl = google.get('ai-base-url') === 'https://gemini.example/v1beta';
    console.log((geminiUrl ? '  PASS  ' : '  FAIL  ') +
      'a Gemini address behind a proxy is saved too');
    if (!geminiUrl) failures.push('ai-base-url not saved for google');

    const openai = await saveAs('openai', '');
    const noUrl = !openai.has('ai-base-url');
    console.log((noUrl ? '  PASS  ' : '  FAIL  ') +
      'a provider with one fixed endpoint sends no address');
    if (!noUrl) failures.push('openai sent an address it does not have');

    const noCustomTimeout = !openai.has('ai-timeout-seconds')
      && byId.get('s-aitimeoutrow').style.display === 'none';
    console.log((noCustomTimeout ? '  PASS  ' : '  FAIL  ') +
      'the custom-server timeout stays out of hosted OpenAI setup');
    if (!noCustomTimeout) failures.push('hosted OpenAI showed or saved the custom timeout');

    const named = openai.get('ai-provider') === 'openai' && openai.get('ai-model') === 'some-model';
    console.log((named ? '  PASS  ' : '  FAIL  ') + 'the provider and model go with it');
    if (!named) failures.push('provider or model missing from the save');

    sandbox.fetch = realFetch;
  } catch (e) {
    console.log('  FAIL  saving the model settings  -> ' + e.message);
    failures.push('ai settings save: ' + e.message);
  }

  // ---- kicking and banning ----
  try {
    sandbox.tab = 'players'; sandbox.render();
    await new Promise((r) => setTimeout(r, 20));
    await sandbox.loadPlayers();

    // One card per player; find each by the name printed on it.
    const cards = [];
    (function walk(el) {
      if (!el || typeof el !== 'object') return;
      if (el.className === 'pcard') cards.push(el);
      for (const k of el.children || []) walk(k);
    })(byId.get('p-online') || byId.get('players') || document.body);

    function cardFor(name) {
      return cards.find((c) => deepText(c).includes(name));
    }
    function labels(card) {
      const out = [];
      (function walk(el) {
        if (!el || typeof el !== 'object') return;
        if (el.tagName === 'button') out.push(el.textContent);
        for (const k of el.children || []) walk(k);
      })(card);
      return out;
    }

    const grief = labels(cardFor('Griefer') || {});
    const canAct = grief.includes('Kick') && grief.includes('Ban');
    console.log((canAct ? '  PASS  ' : '  FAIL  ') + 'an online player can be kicked or banned');
    if (!canAct) failures.push('no kick/ban button: ' + grief.join(','));

    const done = labels(cardFor('Repentant') || {});
    const undo = done.includes('Unban') && !done.includes('Ban');
    console.log((undo ? '  PASS  ' : '  FAIL  ') + 'a banned player is offered Unban instead');
    if (!undo) failures.push('banned player offered: ' + done.join(','));

    const owner = labels(cardFor('TheMines') || {});
    const spared = !owner.includes('Kick') && !owner.includes('Ban');
    console.log((spared ? '  PASS  ' : '  FAIL  ') + 'a trusted operator is offered neither');
    if (!spared) failures.push('trusted op could be removed: ' + owner.join(','));
  } catch (e) {
    console.log('  FAIL  kicking and banning  -> ' + e.message);
    failures.push('kick/ban: ' + e.message);
  }

  // ---- accounts and what each of them may reach ----
  try {
    const realMe = sandbox.me;

    function navLabels() {
      sandbox.authed = true;
      sandbox.setChrome();
      return (byId.get('nav').children || []).map((b) => b.textContent || b._html || '');
    }

    sandbox.me = { username: 'admin', owner: true, access: {}, linkedPlayer: '', audited: false };
    const ownerTabs = navLabels();
    const sawAll = ['Overview', 'Console', 'Activity', 'Files', 'Players', 'Mods']
      .every((t) => ownerTabs.some((x) => x.includes(t)));
    console.log((sawAll ? '  PASS  ' : '  FAIL  ') + 'the main account sees every menu');
    if (!sawAll) failures.push('owner missing tabs: ' + ownerTabs.join(','));

    sandbox.me = { username: 'mod', owner: false, access: { activity: 'read', players: 'write' },
                   linkedPlayer: '', audited: false };
    sandbox.tab = 'files';
    const modTabs = navLabels();
    const only = modTabs.length === 2
      && modTabs.some((t) => t.includes('Activity')) && modTabs.some((t) => t.includes('Players'));
    console.log((only ? '  PASS  ' : '  FAIL  ') + 'a limited account sees only its own menus');
    if (!only) failures.push('limited account tabs: ' + modTabs.join(','));

    const noSettings = !modTabs.some((t) => t.includes('Settings'));
    console.log((noSettings ? '  PASS  ' : '  FAIL  ') + 'a menu it cannot open is not drawn at all');
    if (!noSettings) failures.push('settings drawn for an account without it');

    // It was looking at Files, which it may not open; it must not stay there.
    const moved = sandbox.tab !== 'files';
    console.log((moved ? '  PASS  ' : '  FAIL  ') +
      'it is moved off a menu it is no longer allowed to see');
    if (!moved) failures.push('stayed on a forbidden tab');

    const readOnly = sandbox.readOnly('activity') && !sandbox.readOnly('players');
    console.log((readOnly ? '  PASS  ' : '  FAIL  ') + 'read-only is told apart from write');
    if (!readOnly) failures.push('readOnly() wrong');

    // The People section belongs to the main account alone, and is not in
    // the page at all for anyone else — a hidden section still carries every
    // other account's username to somebody who should not have it.
    // Settings as read-only is not permission to manage people, and the
    // section is absent from the page rather than hidden in it.
    sandbox.me = { username: 'mod', owner: false, access: { settings: 'read' },
                   linkedPlayer: '', audited: false };
    sandbox.settingsTab = 'almin';
    const asMod = deepText(sandbox.settingsPanel());
    const absent = !asMod.includes('s-people');
    console.log((absent ? '  PASS  ' : '  FAIL  ') +
      'People is not in the page for an account that cannot manage anyone');
    if (!absent) failures.push('People markup served to a non-manager');

    sandbox.me = { username: 'admin', owner: true, access: {}, linkedPlayer: '', audited: false };
    const asOwner = deepText(sandbox.settingsPanel());
    const there = asOwner.includes('s-people') && asOwner.includes('s-acadd');
    console.log((there ? '  PASS  ' : '  FAIL  ') + '...and is there for it');
    if (!there) failures.push('People missing for the owner');

    // ---- read-only ----
    sandbox.me = { username: 'mod', owner: false, access: { players: 'read' },
                   linkedPlayer: '', audited: false };
    sandbox.tab = 'players'; sandbox.render();
    await new Promise((r) => setTimeout(r, 20));
    const note = byId.get('ro-note');
    const told = note && /read-only/.test(note.textContent || '');
    console.log((told ? '  PASS  ' : '  FAIL  ') + 'a read-only tab says so at the top');
    if (!told) failures.push('no read-only note');

    // The acting buttons are off; the ones that only move around are not.
    const main = byId.get('main');
    const acting = [], plain = [];
    (function walk(el) {
      if (!el || typeof el !== 'object') return;
      if ((el.tagName || '').toLowerCase() === 'button') {
        (/(^|\s)(go|danger)(\s|$)/.test(el.className || '') ? acting : plain).push(el);
      }
      for (const k of el.children || []) walk(k);
    })(main);
    const offed = acting.length === 0 || acting.every((b) => b.disabled);
    console.log((offed ? '  PASS  ' : '  FAIL  ') + 'the controls that act are turned off');
    if (!offed) failures.push('an acting button stayed enabled while read-only');
    const browsing = plain.every((b) => !b.disabled || b.almWasDisabled);
    console.log((browsing ? '  PASS  ' : '  FAIL  ') +
      '...and the ones that only look around are left alone');
    if (!browsing) failures.push('read-only disabled a plain button');

    sandbox.me = { username: 'admin', owner: true, access: {}, linkedPlayer: '', audited: false };
    sandbox.render();
    await new Promise((r) => setTimeout(r, 20));
    // byId keeps a node after main.innerHTML clears it, so ask the tree that
    // is actually on screen rather than the id map.
    const noNote = !(byId.get('main').children || [])
      .some((k) => k.id === 'ro-note');
    console.log((noNote ? '  PASS  ' : '  FAIL  ') + 'a full account is not told it is read-only');
    if (!noNote) failures.push('read-only note shown to a full account');

    // ---- the header's server controls ----
    // They are not in a tab, so filtering the tabs never reached them.
    sandbox.authed = true; sandbox.serverRunning = true; sandbox.canStart = true;
    sandbox.me = { username: 'admin', owner: true, access: {}, linkedPlayer: '', audited: false };
    sandbox.setChrome();
    const ownerSees = byId.get('srvrestart').style.display !== 'none'
      && byId.get('srvstop').style.display !== 'none';
    console.log((ownerSees ? '  PASS  ' : '  FAIL  ') +
      'the main account can still stop and restart the server');
    if (!ownerSees) failures.push('owner lost the server controls');

    sandbox.me = { username: 'watcher', owner: false, access: { activity: 'read' },
                   linkedPlayer: '', audited: false };
    sandbox.setChrome();
    const hidden = byId.get('srvrestart').style.display === 'none'
      && byId.get('srvstop').style.display === 'none';
    console.log((hidden ? '  PASS  ' : '  FAIL  ') +
      'an account without Overview is not offered them');
    if (!hidden) failures.push('server controls shown to an account without dash');

    sandbox.me = { username: 'looker', owner: false, access: { dash: 'read' },
                   linkedPlayer: '', audited: false };
    sandbox.setChrome();
    const readerHidden = byId.get('srvrestart').style.display === 'none';
    console.log((readerHidden ? '  PASS  ' : '  FAIL  ') +
      '...nor one that only reads it');
    if (!readerHidden) failures.push('server controls shown to a dash reader');

    sandbox.serverRunning = false;
    sandbox.me = { username: 'admin', owner: true, access: {}, linkedPlayer: '', audited: false };
    sandbox.setChrome();
    const startBack = byId.get('srvstart').style.display !== 'none';
    console.log((startBack ? '  PASS  ' : '  FAIL  ') +
      'and Start comes back for the main account when the server is down');
    if (!startBack) failures.push('owner cannot start a stopped server');
    sandbox.serverRunning = true;

    // ---- levels ----
    // A manager who is not the owner still gets People, and the levels it
    // offers stop above its own.
    sandbox.me = { username: 'chief', owner: false, access: { settings: 'write' },
                   linkedPlayer: '', audited: false };
    sandbox.settingsTab = 'almin';
    const asChief = deepText(sandbox.settingsPanel());
    const chiefHasPeople = asChief.includes('s-people');
    console.log((chiefHasPeople ? '  PASS  ' : '  FAIL  ') +
      'an account that can change Settings can manage people');
    if (!chiefHasPeople) failures.push('People withheld from a settings manager');

    sandbox.myRank = 2; sandbox.lastRank = 999;
    sandbox.myAccess = { activity: 'read', files: 'none', settings: 'write' };
    sandbox.me = { username: 'chief', owner: false, access: { settings: 'write' },
                   linkedPlayer: '', audited: false };
    const levels = (function () {
      const sel = sandbox.rankPicker({ id: 'x', rank: 4 });
      const opts = [];
      (function walk(el) {
        if (!el || typeof el !== 'object') return;
        if ((el.tagName || '').toLowerCase() === 'option') opts.push(+el.value);
        for (const k of el.children || []) walk(k);
      })(sel);
      return opts;
    })();
    const belowOnly = levels.length > 0 && levels.every((v) => v > 2);
    console.log((belowOnly ? '  PASS  ' : '  FAIL  ') +
      'only levels below your own are offered');
    if (!belowOnly) failures.push('rank picker offered: ' + levels.join(','));

    const offered = (function () {
      const cell = sandbox.accessPicker({ id: 'x', access: {} }, { id: 'activity', name: 'Activity' });
      const opts = [];
      (function walk(el) {
        if (!el || typeof el !== 'object') return;
        if ((el.tagName || '').toLowerCase() === 'option') opts.push(el.value);
        for (const k of el.children || []) walk(k);
      })(cell);
      return opts;
    })();
    const noMoreThanMine = offered.includes('read') && !offered.includes('write');
    console.log((noMoreThanMine ? '  PASS  ' : '  FAIL  ') +
      'you cannot offer a level you do not hold yourself');
    if (!noMoreThanMine) failures.push('access picker offered: ' + offered.join(','));

    const shut = (function () {
      const cell = sandbox.accessPicker({ id: 'x', access: {} }, { id: 'files', name: 'Files' });
      const opts = [];
      (function walk(el) {
        if (!el || typeof el !== 'object') return;
        if ((el.tagName || '').toLowerCase() === 'option') opts.push(el.value);
        for (const k of el.children || []) walk(k);
      })(cell);
      return opts;
    })();
    const onlyNone = shut.length === 1 && shut[0] === 'none';
    console.log((onlyNone ? '  PASS  ' : '  FAIL  ') +
      '...and a menu you have none of can only be given as none');
    if (!onlyNone) failures.push('files picker offered: ' + shut.join(','));
    sandbox.myRank = 0; sandbox.myAccess = {};

    // ---- being told the visit is recorded ----
    sandbox.watchedTold = false;
    sandbox.me = { username: 'watched', owner: false, access: { activity: 'read' },
                   linkedPlayer: '', audited: true };
    sandbox.tab = 'activity'; sandbox.render();
    await new Promise((r) => setTimeout(r, 20));
    const warned = (byId.get('main').children || [])
      .some((k) => k.id === 'watched-note' && /recorded/.test(k.textContent || ''));
    console.log((warned ? '  PASS  ' : '  FAIL  ') +
      'a watched account is told the Activity menu keeps a record');
    if (!warned) failures.push('no watched banner');

    const dialog = deepText(document.body).includes('This menu keeps a record')
      || sandbox.watchedTold === true;
    console.log((dialog ? '  PASS  ' : '  FAIL  ') + '...and it is put in front of them once');
    if (!dialog) failures.push('no watched dialog');

    sandbox.me = { username: 'mod', owner: false, access: { activity: 'write' },
                   linkedPlayer: '', audited: false };
    sandbox.render();
    await new Promise((r) => setTimeout(r, 20));
    const quiet = !(byId.get('main').children || []).some((k) => k.id === 'watched-note');
    console.log((quiet ? '  PASS  ' : '  FAIL  ') +
      'an account nobody is recording is not warned');
    if (!quiet) failures.push('unwatched account warned');

    sandbox.me = realMe;
  } catch (e) {
    console.log('  FAIL  accounts  -> ' + e.message);
    failures.push('accounts: ' + e.message);
  }

  const missing = failures.filter((f) => f.startsWith('getElementById'));
  for (const m of new Set(missing)) console.log('  NOTE  ' + m);

  const real = failures.filter((f) => !f.startsWith('getElementById'));
  console.log(real.length === 0 ? '\nPANEL SMOKE PASSED' : '\n' + real.length + ' FAILED');
  process.exit(real.length === 0 ? 0 : 1);
})();
