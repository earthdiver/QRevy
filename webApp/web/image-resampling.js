/** Geometry and decoding stay local; pixel arithmetic runs in the shared Kotlin engine. */
export async function resampleImage(image,method='import',outWidth=0,outHeight=0){
  const width=image.naturalWidth||image.width,height=image.naturalHeight||image.height;
  const canvas=document.createElement('canvas');canvas.width=width;canvas.height=height;
  const ctx=canvas.getContext('2d');ctx.drawImage(image,0,0);
  const {data}=ctx.getImageData(0,0,width,height);
  // Release the full-size backing surface before allocating the bounded result.
  canvas.width=canvas.height=1;
  const worker=new Worker(new URL('./pixel-worker.js',import.meta.url),{type:'module'});
  try{
    const result=await new Promise((resolve,reject)=>{
      worker.onmessage=({data})=>data.error?reject(new Error(data.error)):resolve(data.result);
      worker.onerror=event=>{event.preventDefault();reject(new Error(event.message||'Image preprocessing failed'));};
      worker.postMessage({width,height,data,method,outWidth,outHeight},[data.buffer]);
    });
    canvas.width=result.width;canvas.height=result.height;
    ctx.putImageData(new ImageData(result.data,result.width,result.height),0,0);
    return canvas;
  }finally{worker.terminate();}
}
