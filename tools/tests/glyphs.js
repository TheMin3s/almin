// Renders every marker and every sequence badge at a readable size, so an
// icon can be identified by looking at it rather than by reading the switch
// that draws it.
const fs = require('fs');
const src = fs.readFileSync(process.argv[2], 'utf8');

function slice(from, to) {
  const a = src.indexOf(from), b = src.indexOf(to);
  if (a < 0 || b < 0) throw new Error('cannot find ' + from + ' / ' + to);
  return src.slice(a, b);
}

const markerCode = slice('const ACTION_COLOR =', 'function activityPanel()');
const marks = new Function(markerCode.replace(/^\s*const ACTION_COLOR/, 'const ACTION_COLOR') +
  '\n' + slice('function marker(action,x,y,fill,scale)', 'function toolShape(tool,c)') +
  '; return {marker, ACTION_COLOR};')();

const toolCode = slice('function toolShape(tool,c)', 'const TOOL_ITEM=');
const tools = new Function(toolCode + '; return toolShape;')();

const acts = Object.keys(marks.ACTION_COLOR);
const glyphs = ['sword','pickaxe','axe','shovel','hoe','hammer','chest','boots','loop',
                'skull','flame','spark','grid','coin','signpost','bed','star','drop',
                'compass','wing'];

const cell = 96, cols = 6;
function sheet(items, draw) {
  let out = '';
  items.forEach((a, i) => {
    const cx = (i % cols) * cell + cell / 2;
    const cy = Math.floor(i / cols) * cell + cell / 2 - 12;
    out += draw(a, cx, cy) +
      '<text x="' + cx + '" y="' + (cy + 36) + '" text-anchor="middle" fill="#cbd3dd" ' +
      'font-size="12" font-family="sans-serif">' + a + '</text>';
  });
  return { svg: out, rows: Math.ceil(items.length / cols) };
}

const a = sheet(acts, (k, cx, cy) =>
  '<g>' + marks.marker(k, cx, cy, marks.ACTION_COLOR[k] || '#9aa3ae', 2.4) + '</g>');
const t = sheet(glyphs, (k, cx, cy) =>
  '<g transform="translate(' + cx + ' ' + cy + ') scale(2.4)">' +
  '<circle r="9.6" fill="#0a0c10"/><circle r="9.6" fill="none" stroke="#ffc14d" ' +
  'stroke-width="1.6"/>' + tools(k, '#ffc14d') + '</g>');

const h = (a.rows + t.rows) * cell + 40;
fs.writeFileSync(process.argv[3],
  '<svg xmlns="http://www.w3.org/2000/svg" width="' + (cols * cell) + '" height="' + h +
  '" viewBox="0 0 ' + (cols * cell) + ' ' + h + '">' +
  '<rect width="100%" height="100%" fill="#141a12"/>' + a.svg +
  '<g transform="translate(0 ' + (a.rows * cell + 20) + ')">' + t.svg + '</g></svg>');
console.log('wrote ' + acts.length + ' marks and ' + glyphs.length + ' badges');
