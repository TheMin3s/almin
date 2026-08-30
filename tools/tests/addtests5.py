BASE = ("/private/tmp/claude-501/-Users-alex-Documents-claude-schecksmp--claude-worktrees-"
        "remove-lives-keep-admin-826c03/8f49df50-466a-40dc-b27d-bfde1188d88c/scratchpad/")
p = BASE + "panelsmoke.js"
s = open(p).read()

NEW = r"""  // ---- which dimension ----
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

  // ---- crowded marks ----"""

anchor = "  // ---- crowded marks ----"
assert anchor in s
s = s.replace(anchor, NEW, 1)
open(p, "w").write(s)
print("ok")
