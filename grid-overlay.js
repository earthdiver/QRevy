// The faint fixed patterns are alignment references, not sampled or repaired values.
// Keep their positions and provisional orientation behavior in sync with
// Android PreprocessOverlayView.drawExpectedStructures, including its faint colors.
export function drawQrGrid(ctx,{version,assigned=true,map,lineScale=1,manualOnly=false,curved=false}) {
  const n=17+4*version;
  const path=(vertices,closed=false)=>{
    ctx.beginPath();
    const points=closed?[...vertices,vertices[0]]:vertices;
    points.forEach(([x,y],i)=>{
      if(!i){ctx.moveTo(...map(x,y));return;}
      const [ax,ay]=points[i-1],steps=curved?Math.max(1,Math.ceil(Math.hypot(x-ax,y-ay)*2)):1;
      for(let j=1;j<=steps;j++)ctx.lineTo(...map(ax+(x-ax)*j/steps,ay+(y-ay)*j/steps));
    });
    if(closed)ctx.closePath();
  };
  ctx.save();
  ctx.strokeStyle='#00ffff';ctx.lineWidth=2*lineScale;
  path([[-.5,-.5],[n-.5,-.5],[n-.5,n-.5],[-.5,n-.5]],true);ctx.stroke();
  ctx.strokeStyle='#00ffff66';ctx.lineWidth=.7*lineScale;
  const step=n<=33?1:n<=65?2:4;
  for(const i of new Set([...Array.from({length:Math.floor(n/step)+1},(_,i)=>i*step),n])){
    const t=i-.5;
    path([[t,-.5],[t,n-.5]]);ctx.stroke();
    path([[-.5,t],[n-.5,t]]);ctx.stroke();
  }
  if(!manualOnly){
    const module=(x,y,black)=>{
      path([[x-.43,y-.43],[x+.43,y-.43],[x+.43,y+.43],[x-.43,y+.43]],true);
      ctx.fillStyle=black?'#39ff8844':'#ffffff22';ctx.fill();
      ctx.strokeStyle='#7cffb288';ctx.lineWidth=.7*lineScale;ctx.stroke();
    };
    const origins=[[0,0],[n-7,0],[0,n-7],...(!assigned?[[n-7,n-7]]:[])];
    for(const [ox,oy] of origins)for(let y=0;y<7;y++)for(let x=0;x<7;x++)module(ox+x,oy+y,x===0||x===6||y===0||y===6||(x>=2&&x<=4&&y>=2&&y<=4));
    for(let k=8;k<=n-9;k++){
      module(k,6,k%2===0);module(6,k,k%2===0);
      if(!assigned){module(k,n-7,k%2===0);module(n-7,k,k%2===0);}
    }
    if(assigned)module(8,n-8,true);
    ctx.strokeStyle='#00ff66aa';ctx.lineWidth=2*lineScale;
    ctx.setLineDash(assigned?[]:[5*lineScale,4*lineScale]);
    for(const offset of assigned?[6]:[6,n-7]){
      path([[0,offset],[n-1,offset]]);ctx.stroke();
      path([[offset,0],[offset,n-1]]);ctx.stroke();
    }
    for(const [x,y] of origins){path([[x,y],[x+6,y],[x+6,y+6],[x,y+6]],true);ctx.stroke();}
  }
  ctx.restore();
}

// Provenance has its own cyan square; selection and priority never replace it.
export function drawManualCellMarker(ctx,x,y,halfSize,lineWidth,tentative=false){
  ctx.save();ctx.setLineDash(tentative?[halfSize*.6,halfSize*.4]:[]);
  ctx.strokeStyle='#000000';ctx.lineWidth=lineWidth*2.4;ctx.strokeRect(x-halfSize,y-halfSize,halfSize*2,halfSize*2);
  ctx.strokeStyle='#00ffff';ctx.lineWidth=lineWidth;ctx.strokeRect(x-halfSize,y-halfSize,halfSize*2,halfSize*2);
  ctx.restore();
}

export function drawClassificationValue(ctx,value,x,y,pitch,lineScale,rasterPixel=0){
  // At the display bitmap cap, retain enough raster pixels to distinguish digits.
  // Limit that floor to the module pitch so dense grids do not gain oversized text.
  const unknown=value==='?'||value==='*',font=Math.max(6*lineScale,Math.min(13*lineScale,pitch*.72),Math.min(10*rasterPixel,pitch*.72));
  ctx.save();ctx.textAlign='center';ctx.textBaseline='middle';ctx.font=`${font}px sans-serif`;
  if(unknown){ctx.fillStyle=value==='*'?'#aa55dddd':'#ffd600dd';ctx.beginPath();ctx.arc(x,y,Math.max(1.4*lineScale,Math.min(6*lineScale,pitch*.31)),0,Math.PI*2);ctx.fill();}
  else{ctx.strokeStyle='#000000';ctx.lineWidth=Math.max(lineScale,font*.2);ctx.lineJoin='round';ctx.strokeText(value,x,y);}
  ctx.fillStyle=unknown?'#000000':'#ffffff';ctx.fillText(value,x,y);ctx.restore();
}
