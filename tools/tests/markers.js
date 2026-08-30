// Renders every marker shape at a readable size, so an icon can be identified
// by looking at it rather than by reading the switch that draws it.
const fs = require('fs');
const src = fs.readFileSync(process.argv[2], 'utf8');
const start = src.indexOf('function marker(action,x,y,fill,scale)');
const end = src.indexOf('function stopPlay()');
const code = src.slice(start, end);
const ACTION_COLOR = { chat:'#8fd8ff', command:'#ffc14d', container:'#d6a8ff',
  place:'#7bd88f', break:'#ff9b6b', attack:'#ff6b6b', hurt:'#ffb26b',
  death:'#ff4d4f', join:'#7bd88f', leave:'#9aa3ae', respawn:'#8fd8ff',
  item:'#ffd479', interact:'#9ade7b', use:'#c9d1d9', afk:'#9aa3ae', mask:'#d6a8ff' };
const fn = new Function('ACTION_COLOR', code + '; return {marker, markerShape};')(ACTION_COLOR);
const acts = Object.keys(ACTION_COLOR);
const cell = 90, cols = 4;
let out = '';
acts.forEach((a, i) => {
  const cx = (i % cols) * cell + cell / 2;
  const cy = Math.floor(i / cols) * cell + cell / 2 - 12;
  out += '<g>' + fn.marker(a, cx, cy, ACTION_COLOR[a], 2.6) + '</g>' +
    '<text x="' + cx + '" y="' + (cy + 34) + '" text-anchor="middle" fill="#cbd3dd" ' +
    'font-size="13" font-family="sans-serif">' + a + '</text>';
});
const rows = Math.ceil(acts.length / cols);
fs.writeFileSync(process.argv[3],
  '<svg xmlns="http://www.w3.org/2000/svg" width="' + (cols * cell) + '" height="' +
  (rows * cell) + '" viewBox="0 0 ' + (cols * cell) + ' ' + (rows * cell) + '">' +
  '<rect width="100%" height="100%" fill="#141a12"/>' + out + '</svg>');
console.log('wrote ' + acts.length + ' shapes');
