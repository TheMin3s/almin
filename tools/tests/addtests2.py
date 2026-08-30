BASE = ("/private/tmp/claude-501/-Users-alex-Documents-claude-schecksmp--claude-worktrees-"
        "remove-lives-keep-admin-826c03/8f49df50-466a-40dc-b27d-bfde1188d88c/scratchpad/")
p = BASE + "panelsmoke.js"
s = open(p).read()

NEW = r"""  // ---- forgetting with age ----
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
    sandbox.mapOpts.fade.minutes = 5;
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
    sandbox.mapOpts.fade.minutes = 5;
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

  check('a box of marks goes when everything in it has', () => {
    sandbox.mapOpts.cluster = true;
    sandbox.mapOpts.fade.on = true;
    sandbox.mapOpts.fade.minutes = 5;
    sandbox.mapOpts.fade.cats = ['world', 'fight', 'talk', 'move', 'things'];
    sandbox.view = { cx: 25, cz: 40, span: 4000, set: true };
    sandbox.paintAll();
    const boxes = ((byId.get('t-map')._html || '').match(/class="tcl"/g) || []).length;
    sandbox.mapOpts.fade.on = false;
    sandbox.mapOpts.fade.cats = ['world', 'fight', 'things'];
    sandbox.paintAll();
    const withAll = ((byId.get('t-map')._html || '').match(/class="tcl"/g) || []).length;
    return boxes < withAll ? true : withAll + ' boxes stayed ' + boxes;
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

  check('changing one value arms Save and nothing else', async () => {
    sandbox.settingsTab = 'server';
    sandbox.render();
    await new Promise((r) => setTimeout(r, 10));
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
    const armed = byId.get('sp-save');
    sandbox.propEdits = {};
    sandbox.paintSaveState();
    sandbox.settingsTab = 'almin';
    if (!before) return 'it started out armed';
    return /Save 1 change/.test(armed.textContent) ? true : 'it said "' + armed.textContent + '"';
  });

  // ---- crowded marks ----"""

anchor = "  // ---- crowded marks ----"
assert anchor in s
s = s.replace(anchor, NEW, 1)
open(p, "w").write(s)
print("ok")
