BASE = ("/private/tmp/claude-501/-Users-alex-Documents-claude-schecksmp--claude-worktrees-"
        "remove-lives-keep-admin-826c03/8f49df50-466a-40dc-b27d-bfde1188d88c/scratchpad/")
p = BASE + "panelsmoke.js"
s = open(p).read()

# the report fixture gains the per-sequence meanings
s = s.replace(
    """                moments: [{ at: Date.now() - 40000, label: 'A shaft straight down',""",
    """                sequences: [{ at: Date.now() - 40000,
                              means: 'Getting down to the ore layer under the base.' }],
                moments: [{ at: Date.now() - 40000, label: 'A shaft straight down',""")

NEW = r"""  // ---- turning it on ----
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

  check('the form says which piece is missing', () => {
    byId.get('s-aiprov').value = 'local';
    byId.get('s-aimodel').value = '';
    byId.get('s-aiurl').value = '';
    sandbox.aiFormChanged();
    const missing = sandbox.aiMissing();
    byId.get('s-aimodel').value = 'm';
    byId.get('s-aiurl').value = 'http://x/v1';
    sandbox.aiFormChanged();
    return missing.length === 2 && missing.join(' ').includes('model')
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

  // ---- crowded marks ----"""

anchor = "  // ---- crowded marks ----"
assert anchor in s
s = s.replace(anchor, NEW, 1)
open(p, "w").write(s)
print("ok")
