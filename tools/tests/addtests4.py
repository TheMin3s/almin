BASE = ("/private/tmp/claude-501/-Users-alex-Documents-claude-schecksmp--claude-worktrees-"
        "remove-lives-keep-admin-826c03/8f49df50-466a-40dc-b27d-bfde1188d88c/scratchpad/")
p = BASE + "panelsmoke.js"
s = open(p).read()

# the players fixture needs the two new flags, and the mods fixture the list
s = s.replace(
    """  '/api/players': { online: [{ name: 'TheMines', uuid: 'u', mask: 'Ghost', sessionMillis: 60000 }],""",
    """  '/api/players': { online: [{ name: 'TheMines', uuid: 'u', mask: 'Ghost', sessionMillis: 60000,
                              hasMod: true, reported: true }],""")

NEW = r"""  // ---- who has the mod ----
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
    if (!/class="cmod fresh"/.test(box.children.map((c) => c.className).join(' ') +
        box.children.map((c) => c.className).join(' '))) {
      // className is on the row itself
    }
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
    return /gone \d/.test(html) ? true : 'it does not say when';
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

  // ---- crowded marks ----"""

anchor = "  // ---- crowded marks ----"
assert anchor in s
s = s.replace(anchor, NEW, 1)
open(p, "w").write(s)
print("ok")
