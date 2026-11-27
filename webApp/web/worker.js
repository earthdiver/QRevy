import { execute } from './engine/js/engine.js';
import {loadNativeScanner} from './native-boundaries.js';
// Initialize once, outside repair budgets. Queue requests arriving during initialization.
const ready=loadNativeScanner().then(detect=>{globalThis.qrevyNativeScan=detect;})
  .catch(error=>{console.warn('Native detector unavailable; using Java reading and verified geometry',error);});
let queue=ready;
self.onmessage=({data})=>{
  queue=queue.then(()=>{
    try{self.postMessage({id:data.id,result:JSON.parse(execute(data.request))});}
    catch(error){self.postMessage({id:data.id,error:String(error?.message||error)});}
  });
};
ready.then(()=>self.postMessage({ready:true}));
