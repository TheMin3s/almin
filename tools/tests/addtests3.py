BASE = ("/private/tmp/claude-501/-Users-alex-Documents-claude-schecksmp--claude-worktrees-"
        "remove-lives-keep-admin-826c03/8f49df50-466a-40dc-b27d-bfde1188d88c/scratchpad/")
p = BASE + "panelsmoke.js"
s = open(p).read()

NEW = r"""  // ---- sizes that agree with each other ----
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
    // A mark is a disc of radius 8.4 at the same scale, so about 37 across.
    const h = +box[1];
    return (h > 24 && h < 45) ? true : 'the box came out ' + h + ' tall';
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
    return sandbox.MAP_DEFAULTS.head === 1
      ? true : 'the default is ' + sandbox.MAP_DEFAULTS.head;
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
    const now = sandbox.allData.to;
    const strip = sandbox.actionStrip('Steve', now - 60000);
    const all = sandbox.actionStrip('Steve', 0);
    const some = strip.children.length, every = all.children.length;
    // The fixture's Steve did things hours ago and a couple a minute ago.
    return some < every ? true : every + ' became ' + some;
  });

  check('and says which nothing it is when there is none', () => {
    const strip = sandbox.actionStrip('Steve', sandbox.allData.to + 1);
    return /nothing this session/.test(strip._html || '')
      ? true : 'it said "' + (strip._html || '') + '"';
  });

  // ---- the little maps have ground now ----
  check('a player path is drawn over the world, not over black', () => {
    sandbox.shots = [{ at: sandbox.allData.to, dim: 'overworld',
                       minX: -200, minZ: -200, span: 512 }];
    const mini = sandbox.pathMap('Steve', 0);
    return /href="\/api\/map\?at=/.test(mini._html || '')
      ? true : 'no ground was drawn under it';
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
    const body = src.slice(i, i + 2200);
    if (!/Math\.min\(W\/\(/.test(body)) return 'the scale ignores the window';
    return /translate\('\+tx/.test(body) ? true : 'it is not centred on what is in it';
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

  // ---- crowded marks ----"""

anchor = "  // ---- crowded marks ----"
assert anchor in s
s = s.replace(anchor, NEW, 1)
open(p, "w").write(s)
print("ok")
