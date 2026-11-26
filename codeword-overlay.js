// Match Android's CodewordOverlayModel: boundaries enclose complete codewords.
export function codewordCellLabel(cell,ja){
 const head=ja?`コード語 #${cell.raw}`:`Codeword #${cell.raw}`;
 return cell.kind==='UNCLASSIFIED'?`${head} / ${ja?'分類未確定':'unclassified'} / bit ${cell.bit}`:
 `${head} / Block ${cell.block} / ${cell.kind==='DATA'?'Data':'ECC'} #${cell.kindIndex} / bit ${cell.bit}`;
}
export function codewordEdges(cells,x,y){
 const raw=cells[`${x},${y}`]?.raw;
 return [[x,y-1],[x+1,y],[x,y+1],[x-1,y]].map(([nx,ny])=>cells[`${nx},${ny}`]?.raw!==raw);
}
export function drawCodewordOverlay(ctx,cells,map,unitsPerPixel){
 if(!cells)return;
 ctx.save();const numbers=[];
 for(const [key,cell] of Object.entries(cells)){
  const [x,y]=key.split(',').map(Number),center=map(x,y),right=map(x+1,y),pitch=Math.hypot(right[0]-center[0],right[1]-center[1]);
  if(pitch/unitsPerPixel<6)continue;
  const corners=[map(x-.5,y-.5),map(x+.5,y-.5),map(x+.5,y+.5),map(x-.5,y+.5)];
  const palette=cell.kind==='DATA'?['rgba(0,145,255,.259)','#1eb4ff']:cell.kind==='ECC'?['rgba(255,118,20,.282)','#ff9b2d']:['rgba(170,130,210,.243)','#c8afe1'];
  ctx.fillStyle=palette[0];ctx.beginPath();corners.forEach((p,i)=>i?ctx.lineTo(...p):ctx.moveTo(...p));ctx.closePath();ctx.fill();
  ctx.strokeStyle=palette[1];ctx.lineWidth=1.8*unitsPerPixel;
  codewordEdges(cells,x,y).forEach((edge,i)=>{if(edge){ctx.beginPath();ctx.moveTo(...corners[i]);ctx.lineTo(...corners[(i+1)%4]);ctx.stroke();}});
  if(cell.bit===0&&pitch/unitsPerPixel>=24)numbers.push({cell,pitch,center});
 }
 for(const {cell,pitch,center} of numbers){
   const size=Math.max(pitch*.43,7*unitsPerPixel);ctx.font=`bold ${size}px monospace`;ctx.textAlign='center';ctx.textBaseline='middle';ctx.lineWidth=Math.max(1.2*unitsPerPixel,size*.18);ctx.strokeStyle='white';ctx.fillStyle='#111';ctx.strokeText(`#${cell.raw}`,...center);ctx.fillText(`#${cell.raw}`,...center);
 }
 ctx.restore();
}
