import re

BASE = ("/private/tmp/claude-501/-Users-alex-Documents-claude-schecksmp--claude-worktrees-"
        "remove-lives-keep-admin-826c03/8f49df50-466a-40dc-b27d-bfde1188d88c/scratchpad/")
p = BASE + "panelsmoke.js"
s = open(p).read()

s = s.replace("""                   dim: 'overworld', from: Date.now() - 60000, to: Date.now() - 40000,
                   x: 23, y: 37, z: 33, events: 41, weight: 44 },""",
"""                   dim: 'overworld', from: Date.now() - 60000, to: Date.now() - 40000,
                   x: 23, y: 37, z: 33, events: 41, weight: 44, tool: 'pickaxe' },""")
s = s.replace("""                   dim: 'overworld', from: Date.now() - 400000, to: Date.now() - 40000,
                   x: 90, y: 64, z: 12, events: 12, weight: 42 }],""",
"""                   dim: 'overworld', from: Date.now() - 400000, to: Date.now() - 40000,
                   x: 90, y: 64, z: 12, events: 12, weight: 42, tool: 'loop' }],""")

NEW = r"""  // ---- filtering ----
  check('the filter panel lists what is actually there', () => {
    sandbox.filterOpen = true;
    sandbox.paintFilters();
    const host = byId.get('t-filters');
    const all = (host._html || '') +
      host.children.map((c) => c._html || '').join('');
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

  check('a notable stretch is labelled', () => {
    sandbox.paintAll();
    return /class="sqlabel"/.test(byId.get('t-map')._html || '')
      ? true : 'nothing was labelled';
  });

  check("the model's note goes on the label when there is one", () => {
    sandbox.paintAll();
    return /A shaft straight down/.test(byId.get('t-map')._html || '')
      ? true : 'the moment did not reach the map';
  });

  check('badges can be turned off', () => {
    sandbox.mapOpts.sequences = false;
    sandbox.paintAll();
    const off = !/class="tsq"/.test(byId.get('t-map')._html || '');
    sandbox.mapOpts.sequences = true;
    sandbox.paintAll();
    return off ? true : 'they stayed';
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

  // ---- crowded marks ----"""

anchor = "  // ---- crowded marks ----"
assert anchor in s
s = s.replace(anchor, NEW, 1)

s = s.replace("function stub(tag) {", """// The key the filter uses to name one particular thing, which is a NUL so it
// cannot collide with a block name.
const SEP = String.fromCharCode(0);

function headSize() {
  const html = byId.get('t-map')._html || '';
  const m = html.match(/class="thead[^"]*"[^>]*>[\\s\\S]{0,160}?width="([\\d.]+)"/);
  return m ? +m[1] : 0;
}

function stub(tag) {""", 1)

open(p, "w").write(s)
print("ok")
