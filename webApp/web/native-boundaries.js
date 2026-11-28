

export function nativeScanner(native){
  return (pixels,width,height)=>{
    if(!Number.isInteger(width)||!Number.isInteger(height)||width<1||height<1||width>4096||height>4096||width*height>8_000_000||pixels.length!==width*height)throw new Error('Invalid native image');
    let input=0,output=0;
    try{
      input=native._malloc(width*height*4);output=native._malloc(16*8*4);
      if(!input||!output)throw new Error('Native image allocation failed');
      for(let i=0;i<pixels.length;i++){
        const c=pixels[i],j=input+i*4;
        native.HEAPU8[j]=c>>>16&255;native.HEAPU8[j+1]=c>>>8&255;native.HEAPU8[j+2]=c&255;native.HEAPU8[j+3]=255;
      }
      const count=native._detectBoundaries(input,width,height,output);
      if(count<0||count>16)throw new Error('Invalid native boundary count');
      const boundaries=Array.from(native.HEAP32.subarray(output/4,output/4+count*8));
      const textStart=native._textPointer(),bytesStart=native._bytesPointer();
      const decoded=native._hasDecoded()?{
        text:new TextDecoder('utf-8',{ignoreBOM:true}).decode(native.HEAPU8.subarray(textStart,textStart+native._textLength())),
        bytes:Array.from(native.HEAPU8.subarray(bytesStart,bytesStart+native._bytesLength())),reader:'ZXing-C++'
      }:null;
      return {boundaries,decoded};
    }finally{if(input)native._free(input);if(output)native._free(output);}
  };
}
export async function loadNativeScanner(options){
  const {default:createNative}=await import('./native/boundary.js');
  const scan=nativeScanner(await createNative(options));
  return (...args)=>{
    try{return scan(...args);}
    catch(error){console.warn('Native reading failed; using Java reading and verified geometry',error);return {boundaries:[],decoded:null};}
  };
}

/** Keep one exact input; settings and incoming cache hints cannot forge a native result. */
export function cacheNativeScanner(scan){
  let previous=null;
  return (pixels,width,height)=>{
    if(previous&&previous.width===width&&previous.height===height&&pixels.length===previous.pixels.length&&
       previous.pixels.every((value,i)=>value===pixels[i]))return structuredClone(previous.result);
    const result=scan(pixels,width,height);
    previous={pixels:Uint32Array.from(pixels),width,height,result:structuredClone(result)};
    return result;
  };
}
