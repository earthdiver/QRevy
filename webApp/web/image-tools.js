import {resampleImage} from './image-resampling.js';
import {drawQrGrid} from './grid-overlay.js';
import {t} from './i18n.js';
// Image edits remain drafts until Apply. Crop coordinates use original decoded pixels.
const clamp=(v,a,b)=>Math.max(a,Math.min(b,v));
const make=(html)=>{const d=document.createElement('dialog');d.className='image-dialog';d.innerHTML=html;for(const el of d.querySelectorAll('[data-native-key]'))el.textContent=t(el.dataset.nativeKey);for(const canvas of d.querySelectorAll('canvas')){const surface=document.createElement('div');surface.className='image-gesture-surface';canvas.replaceWith(surface);surface.append(canvas);}
 const scroll={x:window.scrollX,y:window.scrollY},bodyStyle=document.body.style.cssText;
 document.body.style.position='fixed';document.body.style.top=`-${scroll.y}px`;document.body.style.left=`-${scroll.x}px`;document.body.style.width='100%';
 let restored=false;const restore=()=>{if(restored)return;restored=true;d.removeEventListener('close',restore);document.body.style.cssText=bodyStyle;window.scrollTo(scroll.x,scroll.y);};
 d.addEventListener('close',restore,{once:true});
 d.addEventListener('touchmove',e=>{if(e.target instanceof Element&&e.target.closest('.image-gesture-surface'))e.preventDefault();},{passive:false});
 document.body.append(d);return {dialog:d,close:()=>{d.close();restore();d.remove();}};};
const button=(id,label)=>`<button type="button" id="${id}">${label}</button>`;
export function cropImage(image, initial={}) {
 return new Promise(resolve=>{
  const {dialog:d,close}=make(`<div class="card-heading"><h2>画像のクロップ・調整</h2>${button('cropCancel','取消')}</div><p class="hint">枠の四隅や内側をドラッグ。元画像の解像度で切り抜いてから解析します。</p><details id="cropHelp"><summary>${t('image_help')}</summary><p data-native-key="crop_help" data-user-content></p><p data-native-key="image_adjustment_help" data-user-content></p></details><canvas id="cropCanvas" aria-label="切り抜き範囲"></canvas><div class="two-col"><label>左<input id="cropX" type="number"></label><label>上<input id="cropY" type="number"></label><label>幅<input id="cropW" type="number" min="1"></label><label>高さ<input id="cropH" type="number" min="1"></label></div><div class="two-col">${button('cropAll','全体を選択')}${button('cropRotate','90°回転')}</div><label>画像の回転角度<input id="cropAngle" type="number" min="-360" max="360" step="0.1" value="0"></label><label>明るさ<input id="cropBrightness" type="range" min="-100" max="100" value="0"><input id="cropBrightnessNumber" type="number" min="-100" max="100" step="1" aria-label="明るさ"></label>${button('cropResetBrightness',t('image_reset_current'))}<label>コントラスト<input id="cropContrast" type="range" min="-100" max="100" value="0"><input id="cropContrastNumber" type="number" min="-100" max="100" step="1" aria-label="コントラスト"></label>${button('cropResetContrast',t('image_reset_current'))}<p id="cropToneError" role="alert"></p>${button('cropResetAdjustments',t('image_reset_all'))}<label class="check"><input id="cropInvert" type="checkbox">白黒反転</label><label class="check"><input id="cropBefore" type="checkbox">調整前を表示</label><button id="cropApply" class="primary wide">切り抜いて解析</button>`);
  const el=id=>d.querySelector('#'+id),c=el('cropCanvas'),surface=c.parentElement,ctx=c.getContext('2d');const iw=image.naturalWidth||image.width,ih=image.naturalHeight||image.height;let angle=0,w=iw,h=ih,rect=[0,0,w,h],initialRect=[0,0,w,h],locating=0,drag=null;
  for(const [id,key] of [['cropBrightness','brightness'],['cropContrast','contrast']])el(id).value=Number.isFinite(initial[key])?String(clamp(initial[key],-100,100)):'0';
  const finish=v=>{close();resolve(v);};el('cropCancel').onclick=()=>finish(null);d.oncancel=e=>{e.preventDefault();finish(null);};
  function drawSource(cx,scale,ox=0,oy=0){cx.save();cx.scale(scale,scale);cx.translate(w/2-ox,h/2-oy);cx.rotate(angle*Math.PI/180);cx.drawImage(image,-iw/2,-ih/2);cx.restore();}
  function render(){const applyLabel=el('cropBefore').checked?t('image_analyze_after'):t('crop_and_analyze');if(el('cropApply').textContent!==applyLabel)el('cropApply').textContent=applyLabel;const scale=Math.min(1,1200/w,900/h);c.width=Math.max(1,Math.round(w*scale));c.height=Math.max(1,Math.round(h*scale));ctx.fillStyle='#fff';ctx.fillRect(0,0,c.width,c.height);drawSource(ctx,scale);
   if(!el('cropBefore').checked){const data=ctx.getImageData(0,0,c.width,c.height),gain=2**(+el('cropContrast').value/50),b=+el('cropBrightness').value*2.55;for(let i=0;i<data.data.length;i+=4)for(let j=0;j<3;j++){const v=clamp(Math.round(data.data[i+j]*gain+127.5*(1-gain)+b),0,255);data.data[i+j]=el('cropInvert').checked?255-v:v;}ctx.putImageData(data,0,0);}
   const [x,y,rw,rh]=rect.map(v=>v*scale);ctx.fillStyle='#102f2a88';ctx.beginPath();ctx.rect(0,0,c.width,c.height);ctx.rect(x,y,rw,rh);ctx.fill('evenodd');ctx.strokeStyle='#ff9c33';ctx.lineWidth=3;ctx.strokeRect(x,y,rw,rh);for(const [px,py] of [[x,y],[x+rw,y],[x+rw,y+rh],[x,y+rh]]){ctx.fillStyle='#ff9c33';ctx.fillRect(px-6,py-6,12,12);}
   ['cropX','cropY','cropW','cropH'].forEach((id,i)=>el(id).value=Math.round(rect[i]));for(const name of ['Brightness','Contrast'])el('crop'+name+'Number').value=el('crop'+name).value;
  }
  function rotate(){angle=clamp(Number(el('cropAngle').value)||0,-360,360);const layout=rotatedLayout(iw,ih,angle);w=layout.w;h=layout.h;rect=[0,0,w,h];initialRect=[0,0,w,h];render();locateSymbol();}
  // Start around the symbol content, located on a reduced copy; the whole image remains the fallback.
  // A selection the user already changed or is typing while locating is kept.
  async function locateSymbol(){if(typeof initial.locate!=='function')return;const token=++locating,before=[...rect];el('cropApply').disabled=true;let found=null;
   try{const s=Math.min(1,1024/Math.max(w,h)),reduced=document.createElement('canvas');reduced.width=Math.max(1,Math.round(w*s));reduced.height=Math.max(1,Math.round(h*s));const rx=reduced.getContext('2d');rx.fillStyle='#fff';rx.fillRect(0,0,reduced.width,reduced.height);drawSource(rx,s);found=await initial.locate(reduced);}catch(e){console.error(e);}
   if(token!==locating||!d.open)return;el('cropApply').disabled=false;if(!Array.isArray(found)||found.length!==4)return;const [fl,ft,fr,fb]=found.map(Number);initialRect=[fl*w,ft*h,(fr-fl)*w,(fb-ft)*h];
   const edited=rect.some((v,i)=>v!==before[i])||['cropX','cropY','cropW','cropH'].some((id,i)=>el(id).value!==String(Math.round(before[i])));if(!edited){rect=[...initialRect];render();}}
  el('cropAngle').onchange=rotate;el('cropRotate').onclick=()=>{el('cropAngle').value=(angle+90)%360;rotate();};el('cropAll').onclick=()=>{rect=[0,0,w,h];render();};
  ['cropBrightness','cropContrast','cropInvert','cropBefore'].forEach(id=>el(id).oninput=()=>{el('cropToneError').textContent='';render();});
  for(const name of ['Brightness','Contrast']){
    el('crop'+name+'Number').onchange=()=>{const input=el('crop'+name+'Number'),value=Number(input.value);if(input.value.trim()===''||!Number.isInteger(value)||value<-100||value>100){el('cropToneError').textContent=t('image_value_error');input.value=el('crop'+name).value;return;}el('crop'+name).value=value;el('cropToneError').textContent='';render();};
    el('cropReset'+name).onclick=()=>{el('crop'+name).value=0;el('cropToneError').textContent='';render();};
  }
  el('cropResetAdjustments').onclick=()=>{el('cropBrightness').value=el('cropContrast').value=0;el('cropInvert').checked=false;el('cropToneError').textContent='';render();};
  ['cropX','cropY','cropW','cropH'].forEach(id=>el(id).onchange=()=>{const x=clamp(+el('cropX').value,0,w-1),y=clamp(+el('cropY').value,0,h-1);rect=[x,y,clamp(+el('cropW').value,1,w-x),clamp(+el('cropH').value,1,h-y)];render();});
  const point=e=>{const b=c.getBoundingClientRect();return [clamp((e.clientX-b.left)*w/b.width,0,w),clamp((e.clientY-b.top)*h/b.height,0,h)];};
  surface.onpointerdown=e=>{if(drag){drag=null;return;}const p=point(e),[x,y,rw,rh]=rect,points=[[x,y],[x+rw,y],[x+rw,y+rh],[x,y+rh]],dist=points.map(q=>Math.hypot(p[0]-q[0],p[1]-q[1])),i=dist.indexOf(Math.min(...dist));const radius=20*w/c.getBoundingClientRect().width,edge=dist[i]<radius?null:Math.abs(p[0]-x)<radius?'left':Math.abs(p[0]-x-rw)<radius?'right':Math.abs(p[1]-y)<radius?'top':Math.abs(p[1]-y-rh)<radius?'bottom':null;drag={p,rect:[...rect],edge,corner:dist[i]<radius?i:-1};surface.setPointerCapture(e.pointerId);};
  surface.onpointermove=e=>{if(!drag)return;const p=point(e),[x,y,rw,rh]=drag.rect;if(drag.edge){const left=drag.edge==='left'?clamp(p[0],0,x+rw-1):x,right=drag.edge==='right'?clamp(p[0],x+1,w):x+rw,top=drag.edge==='top'?clamp(p[1],0,y+rh-1):y,bottom=drag.edge==='bottom'?clamp(p[1],y+1,h):y+rh;rect=[left,top,right-left,bottom-top];}else if(drag.corner<0)rect=[clamp(x+p[0]-drag.p[0],0,w-rw),clamp(y+p[1]-drag.p[1],0,h-rh),rw,rh];else{const opposite=[[x+rw,y+rh],[x,y+rh],[x,y],[x+rw,y]][drag.corner];rect=[Math.min(p[0],opposite[0]),Math.min(p[1],opposite[1]),Math.max(1,Math.abs(p[0]-opposite[0])),Math.max(1,Math.abs(p[1]-opposite[1]))];}render();};surface.onpointerup=()=>drag=null;surface.onpointercancel=()=>{if(drag)rect=drag.rect;drag=null;render();};
  el('cropApply').onclick=async()=>{el('cropApply').disabled=true;try{// The larger of the selection and the initial crop sets the analysis scale.
  const layout=analysisCropLayout(w,h,rect,initialRect),cropped=document.createElement('canvas');cropped.width=layout.width;cropped.height=layout.height;const cx=cropped.getContext('2d');cx.fillStyle='#fff';cx.fillRect(0,0,cropped.width,cropped.height);drawSource(cx,1,layout.left,layout.top);const out=await resampleImage(cropped,'resize',layout.outputWidth,layout.outputHeight);const blob=await new Promise(r=>out.toBlob(r,'image/png'));finish({blob,brightness:+el('cropBrightness').value,contrast:+el('cropContrast').value,toneInvert:el('cropInvert').checked});}catch(error){el('cropToneError').textContent=String(error?.message||error);el('cropApply').disabled=false;}};
  el('cropInvert').checked=!!initial.toneInvert;d.showModal();render();locateSymbol();
 });
}
export function analysisCropLayout(width,height,rect,basis){
 const left=clamp(Math.trunc(rect[0]),0,width-1),top=clamp(Math.trunc(rect[1]),0,height-1);
 const right=clamp(Math.trunc(rect[0]+rect[2]),left+1,width),bottom=clamp(Math.trunc(rect[1]+rect[3]),top+1,height);
 const cropWidth=right-left,cropHeight=bottom-top,scale=Math.min(1,1800/Math.max(cropWidth,cropHeight,basis[2],basis[3]));
 return {left,top,width:cropWidth,height:cropHeight,outputWidth:Math.max(1,Math.round(cropWidth*scale)),outputHeight:Math.max(1,Math.round(cropHeight*scale))};
}
export function fineCorners(image,settings){
 return new Promise(resolve=>{
  const q=[...settings.quad],original=[...q],n=17+4*settings.version;let index=0,drag=null;
  const {dialog:d,close}=make(`<div class="card-heading"><h2>四隅を拡大して調整</h2>${button('cornerCancel','取消')}</div><label>調整する隅<select id="cornerIndex"><option value="0">左上</option><option value="1">右上</option><option value="2">右下</option><option value="3">左下</option></select></label><canvas id="cornerCanvas" aria-label="四隅の拡大画面"></canvas><p class="hint">中心の十字がセル中心です。拡大画面をドラッグ、または矢印で画像軸方向に微調整します。</p><output id="cornerPosition"></output><label>移動量（セル幅）<select id="cornerStep"><option value="0.05">0.05</option><option value="0.1" selected>0.1</option><option value="0.25">0.25</option><option value="0.5">0.5</option><option value="1">1</option></select></label><div class="direction-pad">${button('cornerUp','↑')}<div>${button('cornerLeft','←')}${button('cornerDown','↓')}${button('cornerRight','→')}</div></div><label>拡大倍率<input id="cornerZoom" type="range" min="2" max="16" value="5"></label><div class="two-col">${button('cornerReset','元に戻す')}<button id="cornerApply" class="primary">四隅を確定</button></div>`);
  const el=id=>d.querySelector('#'+id),c=el('cornerCanvas'),surface=c.parentElement,cx=c.getContext('2d');c.width=c.height=600;const locked=new Set((settings.finders||[]).filter((_,i)=>i%3===0));for(const option of el('cornerIndex').options)option.disabled=locked.has(Number(option.value));index=[0,1,2,3].find(i=>!locked.has(i))??2;el('cornerIndex').value=String(index);
  const pitch=()=>Math.max(.25,([0,1,2,3].reduce((a,i)=>{const j=(i+1)%4;return a+Math.hypot(q[2*i]-q[2*j],q[2*i+1]-q[2*j+1]);},0))/(4*(n-1)));
  const span=()=>Math.max(8,pitch()*30/(+el('cornerZoom').value));
  function draw(){
    const x=q[index*2],y=q[index*2+1],s=span();
    cx.fillStyle='#ddd';cx.fillRect(0,0,600,600);cx.imageSmoothingEnabled=false;
    cx.drawImage(image,x-s/2,y-s/2,s,s,0,0,600,600);
    const mapped=(u,v)=>{const p=quadMap(q,u/(n-1),v/(n-1));return [(p[0]-x+s/2)*600/s,(p[1]-y+s/2)*600/s];};
    drawQrGrid(cx,{version:settings.version,assigned:settings.assigned,map:mapped});
    cx.strokeStyle='#ff8a22';cx.lineWidth=2;cx.beginPath();cx.moveTo(280,300);cx.lineTo(320,300);cx.moveTo(300,280);cx.lineTo(300,320);cx.stroke();
    el('cornerPosition').textContent=`x ${x.toFixed(2)} / y ${y.toFixed(2)}`;
  }
  function move(dx,dy){q[index*2]=clamp(q[index*2]+dx,0,image.width??image.naturalWidth);q[index*2+1]=clamp(q[index*2+1]+dy,0,image.height??image.naturalHeight);draw();}
  for(const [id,dx,dy]of [['cornerUp',0,-1],['cornerLeft',-1,0],['cornerDown',0,1],['cornerRight',1,0]])el(id).onclick=()=>{const p=pitch()*+el('cornerStep').value;move(dx*p,dy*p);};
  el('cornerIndex').onchange=()=>{index=+el('cornerIndex').value;draw();};el('cornerZoom').oninput=draw;el('cornerReset').onclick=()=>{q.splice(0,8,...original);draw();};
  surface.onpointerdown=e=>{drag={x:e.clientX,y:e.clientY,q:[...q],span:span()};surface.setPointerCapture(e.pointerId);};surface.onpointermove=e=>{if(!drag)return;const s=drag.span/c.getBoundingClientRect().width;q.splice(0,8,...drag.q);move((e.clientX-drag.x)*s,(e.clientY-drag.y)*s);};surface.onpointerup=()=>drag=null;surface.onpointercancel=()=>{if(drag)q.splice(0,8,...drag.q);drag=null;draw();};
  const finish=v=>{close();resolve(v);};el('cornerCancel').onclick=()=>finish(null);el('cornerApply').onclick=()=>finish(q);d.oncancel=e=>{e.preventDefault();finish(null);};d.showModal();draw();
 });
}

// Projective map from the unit square to the ordered module-center quad.
export function quadMap(q,u,v){
 const [x0,y0,x1,y1,x2,y2,x3,y3]=q,dx1=x1-x2,dx2=x3-x2,dx3=x0-x1+x2-x3,dy1=y1-y2,dy2=y3-y2,dy3=y0-y1+y2-y3,det=dx1*dy2-dx2*dy1;
 const g=Math.abs(det)<1e-12?0:(dx3*dy2-dx2*dy3)/det,h=Math.abs(det)<1e-12?0:(dx1*dy3-dx3*dy1)/det,d=g*u+h*v+1;
 return [((x1-x0+g*x1)*u+(x3-x0+h*x3)*v+x0)/d,((y1-y0+g*y1)*u+(y3-y0+h*y3)*v+y0)/d];
}
// Grid coordinates identify pixel centers (0 is the first pixel), while Canvas
// transforms use pixel boundaries. Account for that half-pixel on both sides.
export function rotatedLayout(width,height,angle){
 const a=angle*Math.PI/180,c=Math.cos(a),s=Math.sin(a),w=Math.ceil(Math.abs(width*c)+Math.abs(height*s)-1e-9),h=Math.ceil(Math.abs(width*s)+Math.abs(height*c)-1e-9);
 const cx=(width-1)/2,cy=(height-1)/2,rx=(w-1)/2,ry=(h-1)/2;
 return {w,h,map:(x,y)=>[(x-cx)*c-(y-cy)*s+rx,(x-cx)*s+(y-cy)*c+ry],
  unmap:(x,y)=>[(x-rx)*c+(y-ry)*s+cx,-(x-rx)*s+(y-ry)*c+cy]};
}
export function prepareImage(image,ops=[]){
 if(ops.length===1&&ops[0].type==='source')return renderSource(image,ops[0]);
 let current=image;
 for(const op of ops){const out=document.createElement('canvas'),w=current.naturalWidth||current.width,h=current.naturalHeight||current.height;
  if(op.type==='rotate'){const l=rotatedLayout(w,h,op.angle);out.width=l.w;out.height=l.h;const cx=out.getContext('2d');cx.fillStyle='#fff';cx.fillRect(0,0,l.w,l.h);cx.translate(l.w/2,l.h/2);cx.rotate(op.angle*Math.PI/180);cx.drawImage(current,-w/2,-h/2);}
  else if(op.type==='rectify'){const q=op.quad,n=17+4*op.version,side=Math.max(2,Math.min(Math.floor(2600/(1+2/(n-1))),Math.round([0,1,2,3].reduce((a,i)=>{const j=(i+1)%4;return a+Math.hypot(q[2*i]-q[2*j],q[2*i+1]-q[2*j+1]);},0)/4))),margin=Math.round(side/(n-1));out.width=out.height=side+2*margin;const src=document.createElement('canvas');src.width=w;src.height=h;const sc=src.getContext('2d');sc.drawImage(current,0,0);const data=sc.getImageData(0,0,w,h).data,cx=out.getContext('2d'),dest=cx.createImageData(out.width,out.height);dest.data.fill(255);for(let y=0;y<out.height;y++)for(let x=0;x<out.width;x++){const [sx,sy]=quadMap(q,(x-margin)/(side-1),(y-margin)/(side-1)).map(Math.round);if(sx<0||sy<0||sx>=w||sy>=h)continue;const a=(sy*w+sx)*4,b=(y*out.width+x)*4;dest.data.set(data.subarray(a,a+4),b);}cx.putImageData(dest,0,0);out.qrQuad=[margin,margin,margin+side-1,margin,margin+side-1,margin+side-1,margin,margin+side-1];if(out.width>1800){const reduced=document.createElement('canvas');reduced.width=reduced.height=1800;reduced.getContext('2d').drawImage(out,0,0,1800,1800);reduced.qrQuad=out.qrQuad.map(v=>(v+.5)*1800/out.width-.5);current=reduced;continue;}}
  else throw new Error('Unknown image transform');current=out;
 }
 return current;
}

// Use sampled warp displacements for display; straight/projective guides use the exact homography.
export function moduleMap(settings){
 const n=17+4*settings.version,q=settings.warp&&settings.previewQuad?settings.previewQuad:settings.quad;
 const project=(x,y)=>quadMap(q,x/(n-1),y/(n-1));
 if(!settings.warp||settings.grid?.length!==n*n)return project;
 const offsets=settings.grid.map(([x,y],i)=>{const p=project(i%n,Math.floor(i/n));return [x-p[0],y-p[1]];});
 return (x,y)=>{
  const ix=clamp(Math.floor(x),0,n-2),iy=clamp(Math.floor(y),0,n-2),fx=x-ix,fy=y-iy,p=project(x,y);
  for(const [dx,dy,weight] of [[0,0,(1-fx)*(1-fy)],[1,0,fx*(1-fy)],[0,1,(1-fx)*fy],[1,1,fx*fy]]){
   const delta=offsets[(iy+dy)*n+ix+dx];p[0]+=delta[0]*weight;p[1]+=delta[1]*weight;
  }
  return p;
 };
}

// Source-based transforms mirror QRevy a6d25df. Old operation lists remain
// readable without changing their raster; new edits retain only source geometry.
export function sourceTransform(ops=[],version=1){
 const source=ops.find(op=>op.type==='source');if(source)return {...source};
 const angle=ops.find(op=>op.type==='rotate')?.angle||0,rect=ops.find(op=>op.type==='rectify');
 return {type:'source',angle,quad:rect?.quad||null,version:rect?.version||version,legacyAngle:rect||angle?angle:null,...(rect||angle?{legacyRaster:'pwa'}:{})};
}
export function mapPoints(points,map){return Array.from({length:points.length/2},(_,i)=>map(points[2*i],points[2*i+1])).flat();}
function inverseQuad(q,x,y){
 // Invert the same unit-square homography used by quadMap.
 const [x0,y0,x1,y1,x2,y2,x3,y3]=q,dx1=x1-x2,dx2=x3-x2,dx3=x0-x1+x2-x3,dy1=y1-y2,dy2=y3-y2,dy3=y0-y1+y2-y3,det=dx1*dy2-dx2*dy1;
 const g=Math.abs(det)<1e-12?0:(dx3*dy2-dx2*dy3)/det,h=Math.abs(det)<1e-12?0:(dx1*dy3-dx3*dy1)/det;
 const a=x1-x0+g*x1-x*g,b=x3-x0+h*x3-x*h,c=y1-y0+g*y1-y*g,d=y3-y0+h*y3-y*h,k=a*d-b*c;
 if(!Number.isFinite(k)||Math.abs(k)<1e-12)throw new Error('Invalid image transform');
 return [((x-x0)*d-b*(y-y0))/k,(a*(y-y0)-(x-x0)*c)/k];
}
function rectificationLayout(q,version){
 const span=16+4*version,requested=Math.round([0,1,2,3].reduce((sum,i)=>{const j=(i+1)%4;return sum+Math.hypot(q[2*i]-q[2*j],q[2*i+1]-q[2*j+1]);},0)/4);
 let side=Math.max(2,Math.min(Math.floor(2600/(1+2/span)),requested)),margin=Math.round(side/span);
 while(side+2*margin>2600&&side>2){side--;margin=Math.round(side/span);}
 const size=side+2*margin,w=Math.min(size,1800),qout=[margin,margin,margin+side-1,margin,margin+side-1,margin+side-1,margin,margin+side-1].map(v=>(v+.5)*w/size-.5);
 return {w,h:w,quad:qout};
}
// Android's historical rotation translates the transformed pixel bounds, then
// scales to 1800px. This differs from the centered, uncapped legacy PWA canvas.
export function androidRotationLayout(width,height,angle){
 if(angle%360===0)return {w:width,h:height,map:(x,y)=>[x,y],unmap:(x,y)=>[x,y]};
 const a=Math.fround(angle)*Math.PI/180,snap=v=>Math.abs(v)<1e-7?0:Math.fround(v),c=snap(Math.cos(a)),s=snap(Math.sin(a));
 const left=Math.min(0,Math.fround(width*c),Math.fround(-height*s),Math.fround(width*c-height*s));
 const right=Math.max(0,Math.fround(width*c),Math.fround(-height*s),Math.fround(width*c-height*s));
 const top=Math.min(0,Math.fround(width*s),Math.fround(height*c),Math.fround(width*s+height*c));
 const bottom=Math.max(0,Math.fround(width*s),Math.fround(height*c),Math.fround(width*s+height*c));
 const rw=Math.max(1,Math.ceil(Math.fround(right-left))),rh=Math.max(1,Math.ceil(Math.fround(bottom-top))),scale=Math.min(1,1800/Math.max(rw,rh));
 const w=Math.max(1,Math.round(rw*scale)),h=Math.max(1,Math.round(rh*scale)),sx=w/rw,sy=h/rh;
 const rawMap=(x,y)=>[(x+.5)*c-(y+.5)*s-left-.5,(x+.5)*s+(y+.5)*c-top-.5];
 const rawUnmap=(x,y)=>{const px=x+.5+left,py=y+.5+top,d=c*c+s*s;return [(px*c+py*s)/d-.5,(-px*s+py*c)/d-.5];};
 return {w,h,rw,rh,rawMap,rawUnmap,map:(x,y)=>{const p=rawMap(x,y);return [(p[0]+.5)*sx-.5,(p[1]+.5)*sy-.5];},unmap:(x,y)=>rawUnmap((x+.5)/sx-.5,(y+.5)/sy-.5)};
}
function legacyRotationLayout(width,height,info){return (info.legacyRaster==='android'?androidRotationLayout:rotatedLayout)(width,height,info.legacyAngle||0);}
export function imageTransform(width,height,ops=[],version=1){
 const info=sourceTransform(ops,version),identity=(x,y)=>[x,y];let w=width,h=height,toBase=identity,fromBase=identity,qrQuad=null;
 const reference=info.legacyAngle;
 // No-op rendering and coordinate mapping must both retain the source size.
 if(!info.quad&&info.angle%360===0)return {w,h,toSource:identity,fromSource:identity,toBase:identity,baseWidth:w,baseHeight:h,qrQuad:null};
 if(info.quad){
  const rect=rectificationLayout(info.quad,info.version),q=rect.quad,span=q[2]-q[0],old=legacyRotationLayout(width,height,info);
  w=rect.w;h=rect.h;
  toBase=(x,y)=>{const p=reference==null?[x,y]:old.map(x,y),uv=inverseQuad(info.quad,...p);return [q[0]+uv[0]*span,q[1]+uv[1]*span];};
  fromBase=(x,y)=>{const p=quadMap(info.quad,(x-q[0])/span,(y-q[1])/span);return reference==null?p:old.unmap(...p);};
  qrQuad=q;
 }
 const degrees=info.angle-(info.quad?(reference||0):0);
 // Legacy operation lists used a centered canvas rotation, without a size cap.
 if(reference!=null&&info.angle===reference){
  if(!info.quad){const old=legacyRotationLayout(width,height,info);return {...old,toSource:old.unmap,fromSource:old.map,toBase:old.unmap,baseWidth:width,baseHeight:height,qrQuad:null};}
  return {w,h,toSource:fromBase,fromSource:toBase,toBase:identity,baseWidth:w,baseHeight:h,qrQuad};
 }
 const a=degrees*Math.PI/180,snap=v=>Math.abs(v)<1e-12?0:v,c=snap(Math.cos(a)),s=snap(Math.sin(a));
 const left=Math.min(0,w*c,-h*s,w*c-h*s),top=Math.min(0,w*s,h*c,w*s+h*c);
 const rw=Math.max(1,Math.ceil(Math.abs(w*c)+Math.abs(h*s))),rh=Math.max(1,Math.ceil(Math.abs(w*s)+Math.abs(h*c))),scale=Math.min(1,1800/Math.max(rw,rh));
 const ow=Math.max(1,Math.round(rw*scale)),oh=Math.max(1,Math.round(rh*scale)),sx=ow/rw,sy=oh/rh;
 const rotate=(x,y)=>[((x+.5)*c-(y+.5)*s-left)*sx-.5,((x+.5)*s+(y+.5)*c-top)*sy-.5];
 const unrotate=(x,y)=>{const px=(x+.5)/sx+left,py=(y+.5)/sy+top;return [px*c+py*s-.5,-px*s+py*c-.5];};
 return {w:ow,h:oh,fromSource:(x,y)=>rotate(...toBase(x,y)),toSource:(x,y)=>fromBase(...unrotate(x,y)),toBase:unrotate,baseWidth:w,baseHeight:h,qrQuad:qrQuad&&mapPoints(qrQuad,rotate)};
}
function renderSource(image,op){
 if(!op.quad&&op.angle%360===0)return image;
 // Keep old saved sessions pixel-for-pixel compatible until a transform changes.
 if(op.legacyAngle!=null&&op.angle===op.legacyAngle){
  if(op.legacyRaster==='android')return renderAndroidLegacy(image,op);
  return prepareImage(image,[...(op.angle?[{type:'rotate',angle:op.angle}]:[]),...(op.quad?[{type:'rectify',quad:op.quad,version:op.version}]:[])]);
 }
 const w=image.naturalWidth||image.width,h=image.naturalHeight||image.height,layout=imageTransform(w,h,[op]);
 const src=document.createElement('canvas');src.width=w;src.height=h;const sc=src.getContext('2d');sc.drawImage(image,0,0);const pixels=sc.getImageData(0,0,w,h).data;
 const out=document.createElement('canvas');out.width=layout.w;out.height=layout.h;const cx=out.getContext('2d'),dest=cx.createImageData(out.width,out.height);dest.data.fill(255);
 const channel=(x,y,c)=>{if(x<0||y<0||x>=w||y>=h)return 255;const i=(y*w+x)*4,a=pixels[i+3];return Math.floor((pixels[i+c]*a+255*(255-a)+127)/255);};
 for(let y=0;y<out.height;y++)for(let x=0;x<out.width;x++){
  const base=layout.toBase(x,y);if(base[0]<-.5||base[1]<-.5||base[0]>=layout.baseWidth-.5||base[1]>=layout.baseHeight-.5)continue;
  const [px,py]=layout.toSource(x,y);if(!Number.isFinite(px)||!Number.isFinite(py)||px< -1||py< -1||px>=w||py>=h)continue;
  const ix=Math.floor(px),iy=Math.floor(py),fx=px-ix,fy=py-iy;
  for(let c=0;c<3;c++)dest.data[(y*out.width+x)*4+c]=Math.round((channel(ix,iy,c)*(1-fx)+channel(ix+1,iy,c)*fx)*(1-fy)+(channel(ix,iy+1,c)*(1-fx)+channel(ix+1,iy+1,c)*fx)*fy);
 }
 cx.putImageData(dest,0,0);out.qrQuad=layout.qrQuad;return out;
}

// Android legacyRotate first draws with nearest-neighbor sampling, then prepare()
// filters any size reduction. Rectification follows that prepared coordinate basis.
function renderAndroidLegacy(image,op){
 let current=image;
 if(op.angle%360!==0){
  const w=image.naturalWidth||image.width,h=image.naturalHeight||image.height,l=androidRotationLayout(w,h,op.angle);
  const out=document.createElement('canvas');out.width=l.rw;out.height=l.rh;
  const cx=out.getContext('2d'),p=l.rawMap(-.5,-.5),x=l.rawMap(.5,-.5),y=l.rawMap(-.5,.5);
  cx.fillStyle='#fff';cx.fillRect(0,0,out.width,out.height);cx.imageSmoothingEnabled=false;
  cx.setTransform(x[0]-p[0],x[1]-p[1],y[0]-p[0],y[1]-p[1],p[0]+.5,p[1]+.5);cx.drawImage(image,0,0);
  current=out;
  if(l.w!==l.rw||l.h!==l.rh){const reduced=document.createElement('canvas');reduced.width=l.w;reduced.height=l.h;reduced.getContext('2d').drawImage(out,0,0,l.w,l.h);current=reduced;}
 }
 return op.quad?prepareImage(current,[{type:'rectify',quad:op.quad,version:op.version}]):current;
}

// Export PWA legacy geometry in Android's coordinate basis. This is an affine
// change in the prepared image, including any stored LM/curved-grid displacement.
export function settingsForQrr(image,settings){
 const info=sourceTransform(settings.imageOps,settings.version);
 if(info.legacyAngle==null||info.legacyRaster==='android')return settings;
 if(!image)throw new Error('Attach the source image before exporting legacy PWA transforms to QRR.');
 const w=image.naturalWidth||image.width,h=image.naturalHeight||image.height;
 const android={...info,legacyRaster:'android'};
 if(info.quad){
  const old=rotatedLayout(w,h,info.legacyAngle),next=androidRotationLayout(w,h,info.legacyAngle);
  android.quad=mapPoints(info.quad,(x,y)=>next.map(...old.unmap(x,y)));
 }
 const before=imageTransform(w,h,[info]),after=imageTransform(w,h,[android]),map=(x,y)=>after.fromSource(...before.toSource(x,y));
 const out={...settings,imageOps:[android]};
 for(const key of ['quad','lmBaseQuad','lmAppliedQuad','previewQuad'])if(settings[key])out[key]=mapPoints(settings[key],map);
 if(settings.finders)out.finders=Array.from({length:settings.finders.length/3},(_,i)=>[settings.finders[i*3],...map(settings.finders[i*3+1],settings.finders[i*3+2])]).flat();
 if(settings.grid)out.grid=settings.grid.map(p=>map(...p));
 if(settings.warp){const origin=map(0,0);out.warp=[...settings.warp];for(let i=0;i<out.warp.length;i+=5){const v=map(settings.warp[i+2],settings.warp[i+3]);out.warp[i+2]=v[0]-origin[0];out.warp[i+3]=v[1]-origin[1];}}
 return out;
}
