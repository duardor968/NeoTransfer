// NODE_PATH must point to a Node package directory containing sharp.
const fs = require('fs');
const path = require('path');
const sharp = require('sharp');
const root = path.resolve(__dirname, '..');
const word = require('./wordmark-paths.json');
const D = 'M12 66V14H24L48 44V26H40L60 6V58H48L24 28V46H32Z';
const colors = {graphite:'#0E0F12',surface:'#17191E',raised:'#1C1F25',mint:'#3DDC97',green:'#1FB36F',white:'#FFFFFF'};
const svg = (w,h,body) => `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}">${body}</svg>\n`;
const rect = (w,h,c) => `<rect width="${w}" height="${h}" fill="${c}"/>`;
const mark = (c,x=0,y=0,s=1) => `<path fill="${c}" d="${D}" transform="translate(${x} ${y}) scale(${s})"/>`;
const text = (c,x,y,size) => `<g fill="${c}" transform="translate(${x} ${y}) scale(${size/word.units} ${-size/word.units})">${word.glyphs.map(g=>`<path d="${g.d}" transform="translate(${g.x} 0)"/>`).join('')}</g>`;
function write(p,s){const f=path.join(root,p);fs.mkdirSync(path.dirname(f),{recursive:true});fs.writeFileSync(f,s);}
const xml = body => `<?xml version="1.0" encoding="utf-8"?>\n${body}\n`;
function vector(c,adaptive=false){return xml(`<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="${adaptive?108:72}dp" android:height="${adaptive?108:72}dp" android:viewportWidth="${adaptive?108:72}" android:viewportHeight="${adaptive?108:72}">\n${adaptive?'<group android:scaleX="0.85" android:scaleY="0.85" android:translateX="23.4" android:translateY="23.4">':''}\n<path android:fillColor="${c}" android:pathData="${D}"/>\n${adaptive?'</group>':''}\n</vector>`);}
async function png(p,src,width){await sharp(Buffer.from(src)).resize({width}).png().toFile(path.join(root,p));}
const variants = {light:[colors.green,colors.graphite],dark:[colors.mint,colors.white],graphite:[colors.graphite,colors.graphite],white:[colors.white,colors.white]};
async function main(){
  for(const [name,[mc,tc]] of Object.entries(variants)){
    const symbol=svg(72,72,mark(mc));
    const wordmark=svg(384,64,text(tc,2,48,60));
    const lockup=svg(466,80,mark(mc,0,4)+text(tc,82,58,60));
    for(const [type,src] of Object.entries({symbol,wordmark,lockup})){
      write(`svg/neotransfer-${type}-${name}.svg`,src);
      await png(`png/neotransfer-${type}-${name}.png`,src,type==='symbol'?512:type==='wordmark'?1152:1398);
    }
  }
  const adaptiveMark=mark(colors.graphite,23.4,23.4,.85);
  const foreground=svg(108,108,adaptiveMark);
  write('svg/neotransfer-adaptive-foreground.svg',foreground);
  write('svg/neotransfer-adaptive-background.svg',svg(108,108,rect(108,108,colors.mint)));
  write('svg/neotransfer-adaptive-monochrome.svg',svg(108,108,mark('#000000',23.4,23.4,.85)));
  await png('png/neotransfer-adaptive-foreground-432.png',foreground,432);
  const store=svg(512,512,rect(512,512,colors.mint)+mark(colors.graphite,64,64,384/72));
  write('svg/neotransfer-store-icon.svg',store);
  await png('png/neotransfer-store-icon-512.png',store,512);
  write('android/res/drawable/ic_neotransfer_foreground.xml',vector(colors.graphite,true));
  write('android/res/drawable/ic_neotransfer_monochrome.xml',vector('#000000',true));
  write('android/res/drawable/ic_neotransfer_symbol.xml',vector(colors.graphite));
  write('android/res/drawable-night/ic_neotransfer_symbol.xml',vector(colors.mint));
  write('android/res/drawable/ic_neotransfer_background.xml',xml(`<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle"><solid android:color="${colors.mint}"/></shape>`));
  for(const version of [26,33])for(const suffix of ['', '_round'])write(`android/res/mipmap-anydpi-v${version}/ic_neotransfer${suffix}.xml`,xml(`<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n<background android:drawable="@drawable/ic_neotransfer_background"/>\n<foreground android:drawable="@drawable/ic_neotransfer_foreground"/>\n${version===33?'<monochrome android:drawable="@drawable/ic_neotransfer_monochrome"/>\n':''}</adaptive-icon>`));
  for(const [density,size] of Object.entries({mdpi:48,hdpi:72,xhdpi:96,xxhdpi:144,xxxhdpi:192})){
    fs.mkdirSync(path.join(root,`android/res/mipmap-${density}`),{recursive:true});
    for(const round of [false,true]){
      const bg=round?`<circle cx="36" cy="36" r="36" fill="${colors.mint}"/>`:`<rect width="72" height="72" rx="16" fill="${colors.mint}"/>`;
      const source=svg(72,72,bg+mark(colors.graphite,5.4,5.4,.85));
      await png(`android/res/mipmap-${density}/ic_neotransfer${round?'_round':''}.png`,source,size);
    }
  }
  let board=rect(1400,1060,colors.graphite);
  board+=`<rect x="700" width="700" height="430" fill="white"/>`;
  board+=mark(colors.mint,56,60,2)+text(colors.white,232,154,65);
  board+=mark(colors.green,756,60,2)+text(colors.graphite,932,154,65);
  board+=mark(colors.white,110,268,.75)+text(colors.white,184,310,34);
  board+=mark(colors.graphite,810,268,.75)+text(colors.graphite,884,310,34);
  const label=(s,x,y,c='#A6ADB8',size=16)=>`<text x="${x}" y="${y}" font-family="sans-serif" font-size="${size}" fill="${c}">${s}</text>`;
  board+=label('NEOTRANSFER / N DE DOBLE DIRECCION',64,36);
  board+=label('SIMBOLO + INTER SEMIBOLD',764,36,'#5D646D');
  board+=label('MONOCROMO',64,260)+label('MONOCROMO',764,260,'#5D646D');
  board+=label('ANDROID / MASCARAS SIMULADAS',64,474);
  const masks=[`<circle cx="90" cy="90" r="90"/>`,`<rect width="180" height="180" rx="40"/>`,`<path d="M90 0C162 0 180 18 180 90S162 180 90 180S0 162 0 90S18 0 90 0Z"/>`];
  masks.forEach((mask,i)=>{
    const x=64+i*242,y=505;
    board+=`<defs><clipPath id="mask${i}">${mask}</clipPath></defs><g transform="translate(${x} ${y})"><g clip-path="url(#mask${i})">${rect(180,180,colors.mint)}${mark(colors.graphite,13.5,13.5,2.125)}</g></g>`;
  });
  board+=`<g transform="translate(828 505)"><rect width="180" height="180" rx="40" fill="white"/>${mark(colors.graphite,13.5,13.5,2.125)}</g>`;
  board+=label('CAPA MONOCROMA',828,718);
  board+=label('TAMANO REAL / 16, 24, 32, 48 PX',64,776);
  [16,24,32,48].forEach((size,i)=>{board+=mark(colors.mint,64+i*95,810,size/72)+label(String(size),64+i*95,897);});
  board+=`<rect x="560" y="766" width="442" height="160" fill="white"/>`;
  [16,24,32,48].forEach((size,i)=>{board+=mark(colors.graphite,590+i*95,810,size/72)+label(String(size),590+i*95,897,'#5D646D');});
  board+=label('Minimo recomendado: simbolo 24 px / lockup 156 px. 16 px: solo identificacion.',64,979);
  board+=label('Vectores propios, sin sombras. Lettering trazado: no requiere instalar Inter.',64,1014);
  write('review/brand-board.svg',svg(1400,1060,board));
  await png('review/brand-board.png',svg(1400,1060,board),1400);
  let sizes=rect(1100,440,colors.surface);
  for(let i=0;i<4;i++){
    const s=[16,24,32,48][i],src=svg(72,72,mark(colors.mint));
    const buf=await sharp(Buffer.from(src)).resize(s,s).png().toBuffer();
    const up=await sharp(buf).resize(s*6,s*6,{kernel:'nearest'}).png().toBuffer();
    sizes+=`<image x="${32+i*265}" y="40" width="${s*6}" height="${s*6}" href="data:image/png;base64,${up.toString('base64')}"/>`+label(`${s} px / 6x`,32+i*265,392);
  }
  await png('review/pixel-review.png',svg(1100,440,sizes),1100);
  const parts=[];
  for(const [i,name] of ['dark','light'].entries()){
    const buf=await sharp(path.join(root,`svg/neotransfer-lockup-${name}.svg`)).resize(156).png().toBuffer();
    parts.push({input:buf,left:24+i*240,top:24});
    const solid=await sharp(path.join(root,`svg/neotransfer-lockup-${name==='light'?'graphite':'white'}.svg`)).resize(156).png().toBuffer();
    parts.push({input:solid,left:24+i*240,top:78});
  }
  await sharp(Buffer.from(svg(480,128,rect(480,128,colors.graphite)+'<rect x="240" width="240" height="128" fill="white"/>'))).composite(parts).png().toFile(path.join(root,'review/minimum-lockup.png'));
  fs.copyFileSync(path.resolve(root,'../../app/src/main/assets/licenses/Inter-OFL.txt'),path.join(root,'source/Inter-OFL.txt'));
  console.log('Brand assets generated in '+root);
}
main().catch(e=>{console.error(e);process.exit(1);});
