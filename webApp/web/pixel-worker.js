import {resample} from './engine/js/engine.js';
self.onmessage=({data})=>{
  try { const result=resample(data);self.postMessage({result},[result.data.buffer]); }
  catch(error){self.postMessage({error:String(error?.message||error)});}
};
