const fs=require('fs'),path=require('path'),sharp=require('sharp');
const root=path.resolve(__dirname,'..');
const files=[];
function walk(dir){for(const e of fs.readdirSync(dir,{withFileTypes:true})){const p=path.join(dir,e.name);if(e.isDirectory())walk(p);else files.push(p);}}
function lum(hex){const c=hex.match(/\w\w/g).map(x=>parseInt(x,16)/255).map(x=>x<=.04045?x/12.92:((x+.055)/1.055)**2.4);return .2126*c[0]+.7152*c[1]+.0722*c[2];}
const ratio=(a,b)=>{const x=lum(a),y=lum(b);return +((Math.max(x,y)+.05)/(Math.min(x,y)+.05)).toFixed(2);};
(async()=>{
 walk(root);const checks=[];
 for(const p of files.filter(p=>p.endsWith('.png')&&!p.includes(path.sep+'review'+path.sep))){
  const {data,info}=await sharp(p).ensureAlpha().raw().toBuffer({resolveWithObject:true});
  let min=255,max=0,edge=0;
  for(let y=0;y<info.height;y++)for(let x=0;x<info.width;x++){const a=data[(y*info.width+x)*4+3];min=Math.min(min,a);max=Math.max(max,a);if(x===0||y===0||x===info.width-1||y===info.height-1)edge=Math.max(edge,a);}
  const transparent=p.includes('symbol-')||p.includes('wordmark-')||p.includes('lockup-')||p.includes('adaptive-foreground');
  const pass=max===255&&(!transparent||(min===0&&edge===0));
  checks.push({file:path.relative(root,p).replaceAll('\\','/'),width:info.width,height:info.height,alphaMin:min,alphaMax:max,edgeAlphaMax:edge,pass});
  if(!pass)throw Error('Alpha or crop failure: '+p);
 }
 // All edges are straight: the furthest point from the centre is a vertex.
 const points=[[12,66],[12,14],[24,14],[48,44],[48,26],[40,26],[60,6],[60,58],[48,58],[24,28],[24,46],[32,46]];
 const radius=Math.max(...points.map(([x,y])=>Math.hypot((x-36)*.85,(y-36)*.85)));
 if(radius>=33)throw Error('Adaptive safe circle exceeded');
 const result={pngs:checks,adaptive:{canvas:108,safeCircleDiameter:66,maxRadius:+radius.toFixed(4),clearance:+(33-radius).toFixed(4),pass:radius<33},contrast:{mintOnGraphite:ratio('3DDC97','0E0F12'),mintOnSurface:ratio('3DDC97','17191E'),mintOnRaised:ratio('3DDC97','1C1F25'),greenOnWhite:ratio('1FB36F','FFFFFF'),graphiteOnWhite:ratio('0E0F12','FFFFFF')},limits:['Launcher masks are simulated; no device, APK build or manifest integration performed.','Green on white is a brand accent, not an accessible small functional icon or body text. Use graphite variant for those cases.']};
 fs.writeFileSync(path.join(root,'review/validation.json'),JSON.stringify(result,null,2)+'\n');
 console.log(JSON.stringify({pngCount:checks.length,adaptive:result.adaptive,contrast:result.contrast}));
})().catch(e=>{console.error(e);process.exit(1);});
