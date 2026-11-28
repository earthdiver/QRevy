/** Decode bounded tiles; shared Kotlin owns all averaging and interpolation. */
export async function resampleImage(image,method='import',outWidth=0,outHeight=0){
  const width=image.naturalWidth||image.width,height=image.naturalHeight||image.height;
  if(!Number.isInteger(width)||!Number.isInteger(height)||width<1||height<1||width>65536||height>65536)throw new Error('Invalid image dimensions');
  let sample=1;
  while(Math.max(width,height)/sample>2600)sample*=2;
  const bounded=method==='import';
  const canvas=document.createElement('canvas');
  canvas.width=bounded?Math.ceil(width/sample):outWidth;
  canvas.height=bounded?Math.ceil(height/sample):outHeight;
  const ctx=canvas.getContext('2d');
  if(!ctx)throw new Error('Image allocation failed');
  const tile=document.createElement('canvas');
  const worker=new Worker(new URL('./pixel-worker.js',import.meta.url),{type:'module'});
  const process=request=>new Promise((resolve,reject)=>{
    worker.onmessage=({data})=>data.error?reject(new Error(data.error)):resolve(data.result);
    worker.onerror=event=>{event.preventDefault();reject(new Error(event.message||'Image preprocessing failed'));};
    worker.postMessage(request,[request.data.buffer]);
  });
  try{
    // Tile edges align with sample blocks, including the partial blocks at image edges.
    const step=bounded?Math.max(sample,Math.floor(1024/sample)*sample):Math.max(width,height);
    for(let y=0;y<height;y+=step)for(let x=0;x<width;x+=step){
      const w=Math.min(step,width-x),h=Math.min(step,height-y);
      tile.width=w;tile.height=h;
      const tc=tile.getContext('2d');
      if(!tc)throw new Error('Image tile allocation failed');
      tc.drawImage(image,x,y,w,h,0,0,w,h);
      const {data}=tc.getImageData(0,0,w,h);
      const result=await process({width:w,height:h,data,method,sample,outWidth,outHeight});
      ctx.putImageData(new ImageData(result.data,result.width,result.height),bounded?x/sample:0,bounded?y/sample:0);
    }
    return canvas;
  }finally{tile.width=tile.height=1;worker.terminate();}
}
