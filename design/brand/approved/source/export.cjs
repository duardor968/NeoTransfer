const fs=require('fs'),path=require('path'),sharp=require('sharp');
const root=path.resolve(__dirname,'..');
const source=fs.readFileSync(path.join(__dirname,'approved-concept.png'));
const shapes={
 'neotransfer-textured':{view:'163 92 584 672',d:'M188 737V198H340L582 498V321H497L720 118V705H584L316 373V534H417Z'},
 'payment-success-textured':{view:'830 226 584 528',d:'M856 504L958 403L1049 494L1289 254L1387 353V375L1069 727Z'}
};
async function main(){
 const evidence=[];
 for(const [name,{view,d}] of Object.entries(shapes)){
  const v=view.split(' ').map(Number),width=v[2],height=v[3];
  const svg=`<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="${view}"><defs><clipPath id="silhouette"><path d="${d}"/></clipPath></defs><image x="0" y="0" width="1536" height="1024" href="data:image/png;base64,${source.toString('base64')}" clip-path="url(#silhouette)"/></svg>`;
  fs.writeFileSync(path.join(root,'svg',name+'.svg'),svg);
  for(const size of [64,128,256,512,1024]){
   const file=path.join(root,'png',`${name}-${size}.png`);
   await sharp(Buffer.from(svg)).resize(size,size,{fit:'contain',background:{r:0,g:0,b:0,alpha:0}}).png().toFile(file);
   const {data,info}=await sharp(file).raw().toBuffer({resolveWithObject:true});
   let amin=255,amax=0,edge=0;
   for(let y=0;y<size;y++)for(let x=0;x<size;x++){const a=data[(y*size+x)*4+3];amin=Math.min(amin,a);amax=Math.max(amax,a);if(!x||!y||x===size-1||y===size-1)edge=Math.max(edge,a);}
   if(amin!==0||amax!==255||edge!==0||info.channels!==4)throw Error('Transparency failed '+file);
   evidence.push({file:path.basename(file),size,alpha:[amin,amax],edgeAlpha:edge});
  }
 }
 const layers=[];
 for(const [i,name] of Object.keys(shapes).entries())for(const [j,bg] of ['#0E0F12','#FFFFFF'].entries()){
  const input=await sharp(path.join(root,'png',name+'-512.png')).resize(256).flatten({background:bg}).png().toBuffer();
  layers.push({input,left:i*320+32,top:j*320+32});
  layers.push({input:await sharp(path.join(root,'png',name+'-64.png')).flatten({background:bg}).png().toBuffer(),left:i*320+272,top:j*320+240});
 }
 await sharp({create:{width:660,height:640,channels:3,background:'#0E0F12'}}).composite([{input:Buffer.from('<svg width="660" height="320"><rect width="660" height="320" fill="white"/></svg>'),left:0,top:320},...layers]).png().toFile(path.join(root,'review','backgrounds.png'));
 fs.writeFileSync(path.join(root,'review','validation.json'),JSON.stringify({exports:evidence,method:'Original approved pixels embedded in SVG under measured polygon contours; no generated cutouts used.',limitation:'Raster texture inside SVG; not resolution-independent vector texture. Android app integration and real-device validation not performed.'},null,2));
 console.log('10 transparent PNG exports; alpha and outer edges pass.');
}
main().catch(e=>{console.error(e);process.exit(1)});
