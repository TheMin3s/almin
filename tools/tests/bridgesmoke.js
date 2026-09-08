// Runs the BlueMap bridge the way BlueMap does: as a script in a page, with
// an app object beside it. What it is really checking is what happens when
// that app is not the one the bridge was written against.
//
// The bridge is served into somebody else's web app. That app is an optional
// install, it has versions, and Almin does not get to pick which one. So the
// question this asks is not "does it work against the app we know" but "does
// it still do its job against one it does not" — because the answer used to
// be no, and silently: registering a camera listener on an object that had
// moved threw, and took the stylesheet, the ready message and the first
// render with it. The panel then waited forever for a frame that had already
// given up, and the 3D map showed none of Almin's marks with nothing on
// screen to say why.
//
// Usage: node tools/tests/bridgesmoke.js build/toolstests/bridge.js
const fs = require('fs');
const vm = require('vm');

const src = fs.readFileSync(process.argv[2], 'utf8');
let fails = 0;

function ck(what, ok, saw) {
  console.log((ok ? '  PASS  ' : '  FAIL  ') + what + (ok ? '' : '  -> ' + saw));
  if (!ok) fails++;
}

/** Enough of a page for a script that draws in one. */
function makeElement(tag) {
  return {
    tagName: tag, id: '', className: '', textContent: '', style: {}, dataset: {},
    children: [], _attr: {},
    appendChild(k) { this.children.push(k); return k; },
    setAttribute(k, v) { this._attr[k] = v; if (k === 'id') this.id = v; },
    getAttribute(k) { return this._attr[k]; },
    addEventListener() {}, removeEventListener() {},
    closest() { return null; },
    getBoundingClientRect() { return { x: 0, y: 0, width: 0, height: 0,
                                       top: 0, left: 0, right: 0, bottom: 0 }; },
    classList: { add() {}, remove() {}, toggle() {}, contains() { return false; } },
  };
}

/**
 * One run of the bridge.
 *
 * @param events what this version of BlueMap offers as an event source, if
 *               anything — the whole point of the exercise
 */
function run(events) {
  const posted = [];
  const byId = new Map();
  const head = makeElement('head'), body = makeElement('body');
  const document = {
    head, body,
    documentElement: makeElement('html'),
    createElement: makeElement,
    getElementById: (id) => byId.get(id) || null,
    querySelector: () => null,
    querySelectorAll: () => [],
    addEventListener() {}, removeEventListener() {},
  };
  // The bridge gives its stylesheet an id and looks it up before making a
  // second one, so the shim has to remember what it was handed.
  const realAppend = head.appendChild.bind(head);
  head.appendChild = (k) => { if (k.id) byId.set(k.id, k); return realAppend(k); };

  let sets = null;
  class MarkerSet {
    constructor(id) { this.id = id; this.data = { id }; this.parent = null; this.sets = []; }
    add(s) { s.parent = this; this.sets.push(s); }
    updateMarkerSetsFromData(d) { sets = d; }
  }
  const app = {
    popupMarkerSet: new MarkerSet('popup'),
    maps: [{ data: { id: 'world', name: 'World' } }],
    mapViewer: { map: { data: { id: 'world' } },
                 controlsManager: { position: { x: 0, y: 64, z: 0 }, distance: 300 },
                 renderer: { domElement: makeElement('canvas') },
                 redraw() {} },
    switchMap: async () => {},
  };
  if (events) app.events = events;

  const listeners = [];
  const sandbox = {
    window: null, document, console,
    setTimeout: (fn) => { try { fn(); } catch (e) { posted.push('threw:' + e.message); } },
    clearTimeout: () => {}, setInterval: () => 1, clearInterval: () => {},
    location: { origin: 'http://localhost' },
    parent: { postMessage: (m) => posted.push(m && m.type) },
    Math, JSON, Date, Set, Map, Object, Array, String, Number, Boolean, Error,
  };
  sandbox.window = sandbox;
  sandbox.self = sandbox;
  sandbox.bluemap = app;
  sandbox.BlueMap = { MarkerSet };
  sandbox.addEventListener = (name, fn) => listeners.push({ name, fn });
  sandbox.removeEventListener = () => {};
  sandbox.postMessage = (m) => {
    for (const l of listeners) {
      if (l.name !== 'message') continue;
      // The bridge only listens to its parent, which is the panel. Sending
      // as anything else is a message it is right to ignore.
      l.fn({ source: sandbox.parent, origin: 'http://localhost', data: m });
    }
  };

  let threw = '';
  try { vm.runInNewContext(src, sandbox, { filename: 'bridge.js' }); }
  catch (e) { threw = e.message; }
  return { posted, threw, styled: !!byId.get('almin-bridge-style'),
           send: (state) => sandbox.postMessage(
             { source: 'almin-activity-v1', type: 'state', state }),
           sets: () => sets };
}

/** A state with one of everything the panel sends. */
const STATE = {
  dimension: 'overworld', darkness: 0.3, livePlayers: false, hideCoords: false,
  markers: [{ id: 'a0', kind: 'action', x: 10, y: 64, z: 10, color: '#ffab33',
              size: 2, opacity: 1, text: '+', title: 'Steve placed stone' }],
  lines: [{ id: 'path-Steve-0', label: 'Steve travelled path', width: 2.6,
            color: 'hsl(147 68% 66%)', opacity: 0.9,
            points: [{ x: 1, y: 64, z: 1 }, { x: 4, y: 64, z: 6 }] }],
  players: [{ id: 'p-Steve', x: 4.5, y: 66, z: 6.5, color: '#6d7682', size: 1,
              state: 'here', fallback: 'S', text: 'Steve', cap: '', capColor: '',
              icon: '/api/head?uuid=x&name=Steve', title: 'Steve — here' }],
  places: [{ id: 'q0', kind: 'place', shape: 'place', x: 20.5, y: 66, z: 40.5,
             color: '#ffcf6b', size: 1, text: 'Steve’s base', title: 'Steve’s base' },
           { id: 'q0r', type: 'ring', label: 'Steve’s base', color: '#ffcf6b',
             points: [{ x: 18, y: 65, z: 38 }, { x: 22, y: 65, z: 42 }] }],
  scenes: [{ id: 's0', type: 'marker', kind: 'episode', shape: 'scene', x: 3, y: 64,
             z: 3, color: '#ffab33', text: 'a shaft', title: 'a shaft' }],
  grid: [{ id: 'gx0', color: '#dfe6ef', width: 1.6, opacity: 0.55,
           points: [{ x: 0, y: 64, z: -80 }, { x: 0, y: 64, z: 80 }] }],
};

/** render() is async, so the sets land a microtask after the state does. */
const settle = () => new Promise((r) => setImmediate(r));

function counts(sets) {
  if (!sets) return null;
  const out = {};
  for (const k of Object.keys(sets)) out[k] = Object.keys(sets[k].markers || {}).length;
  return out;
}

async function main() {
// ---- the app it was written against ----
{
  const target = { addEventListener() {}, removeEventListener() {} };
  const r = run(target);
  ck('the bridge loads into the app it knows', !r.threw, r.threw);
  ck('...says it is ready, which is what makes the panel start sending',
    r.posted.indexOf('ready') >= 0, r.posted.join(',') || 'nothing posted');
  r.send(STATE);
  await settle();
  const c = counts(r.sets());
  ck('...and one state draws every layer',
    c && c['almin-places'] === 2 && c['almin-actions'] === 1
      && c['almin-tracks'] === 2 && c['almin-scenes'] === 1 && c['almin-grid'] === 1,
    JSON.stringify(c));
}

// ---- an app that keeps its events somewhere else ----
// This is the version that was broken, and the symptom was not "the camera
// stopped following": it was no places, no faces, no coloured paths, and no
// error anybody could see.
{
  const r = run(null);
  ck('an app with no events object does not take the bridge down with it',
    !r.threw, r.threw);
  ck('...the stylesheet still goes in, so a mark that is drawn is visible',
    r.styled, 'no stylesheet — every mark would be an unstyled button');
  ck('...it still says it is ready, so the panel still sends it anything',
    r.posted.indexOf('ready') >= 0, r.posted.join(',') || 'nothing posted');
  r.send(STATE);
  await settle();
  const c = counts(r.sets());
  ck('...and it still draws places, faces and paths',
    c && c['almin-places'] === 2 && c['almin-tracks'] === 2,
    JSON.stringify(c));
}

// ---- an app whose events throw when listened to ----
{
  const angry = { addEventListener() { throw new Error('not supported'); } };
  const r = run(angry);
  ck('an app that refuses a listener is not a bridge that gives up',
    !r.threw && r.posted.indexOf('ready') >= 0, r.threw || r.posted.join(','));
  r.send(STATE);
  await settle();
  ck('...and it draws the same map', counts(r.sets())['almin-places'] === 2,
    JSON.stringify(counts(r.sets())));
}

}

main().then(() => {
console.log(fails === 0 ? '\nBRIDGE SMOKE PASSED' : '\n' + fails + ' FAILED');
process.exit(fails === 0 ? 0 : 1);
});
