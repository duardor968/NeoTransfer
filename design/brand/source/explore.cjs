const fs = require('fs');
const sharp = require('sharp');
const path = require('path');
const root = path.resolve(__dirname, '..');
const paths = [
  'M12 60V12H24L48 42V24H38L60 2V60H48L24 30V48H34Z',
  'M12 60V12H24L60 48V60H48L24 36V60Z M48 12H60V36L48 24Z',
  'M12 22L28 6V16H48L60 28H28V38Z M60 50L44 66V56H24L12 44H44V34Z'
];
let body = '<rect width="1200" height="520" fill="#0E0F12"/>';
paths.forEach((d, i) => {
  body += `<g transform="translate(${80+i*400} 70) scale(3)"><path d="${d}" fill="#3DDC97"/></g>`;
  [16,24,32,48].forEach((s,j)=>body+=`<svg x="${65+i*400+j*65}" y="360" width="${s}" height="${s}" viewBox="0 0 72 72"><path d="${d}" fill="#3DDC97"/></svg>`);
});
sharp(Buffer.from(`<svg xmlns="http://www.w3.org/2000/svg" width="1200" height="520">${body}</svg>`)).png().toFile(path.join(root,'review','exploration.png'));
