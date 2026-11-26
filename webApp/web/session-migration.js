import {sourceTransform} from './image-tools.js';

function snapshots(session){
 const entries=[...session.history,...(session.checkpoint?[session.checkpoint]:[])];
 return entries.flatMap(entry=>[
  {entry,settings:entry.settings},
  ...[entry.automatic?.input?.settings,entry.automatic?.retrySettings].filter(Boolean).map(settings=>({entry,settings}))
 ]);
}
function ambiguous(settings){
 const ops=settings?.imageOps||[],source=ops.find(op=>op.type==='source');
 return source?source.legacyAngle!=null&&source.legacyRaster==null:sourceTransform(ops,settings.version).legacyAngle!=null;
}
export function needsLegacyImageMigration(session){return !!session.qrrRaw&&snapshots(session).some(({settings})=>ambiguous(settings));}

function transformKey(settings){
 const op=sourceTransform(settings.imageOps,settings.version);
 return JSON.stringify([op.angle,op.quad,op.quad?op.version:null,op.legacyAngle]);
}
function sameQuad(a,b){return JSON.stringify(a??null)===JSON.stringify(b??null);}
function retainsImportedBasis(entry,settings,original){
 if(!original||transformKey(settings)!==transformKey(original))return false;
 // qrrIndex is inherited by local edits: it identifies a source snapshot, not
 // the coordinate system of the current history entry.
 if(entry.changes?.some(change=>change.key==='imageOps'&&JSON.stringify(change.before)!==JSON.stringify(change.after)))return false;
 // An inactive LM base can survive manual corner edits. Check the current
 // quad first, allowing LM-off histories to restore the original manual quad.
 if(!sameQuad(settings.quad,original.quad)&&
    !(!settings.lmEnabled&&original.lmBaseQuad&&sameQuad(settings.quad,original.lmBaseQuad)))return false;
 if(!settings.lmEnabled)return true;
 // A matching base alone does not establish that an applied LM result still
 // belongs to the imported image coordinates.
 return sameQuad(settings.lmBaseQuad||settings.quad,original.lmBaseQuad||original.quad)&&
        sameQuad(settings.lmAppliedQuad,original.lmAppliedQuad);
}

// Only unchanged imported geometry provides evidence for the Android basis.
// Edited/unknown histories retain the PWA raster they used before migration.
export function migrateLegacyImages(session,originalHistory=[]){
 for(const {entry,settings} of snapshots(session)){
  if(!ambiguous(settings))continue;
  const original=Number.isInteger(entry.qrrIndex)&&entry.qrrIndex>=0?originalHistory[entry.qrrIndex]?.settings:null;
  const legacyRaster=retainsImportedBasis(entry,settings,original)?'android':'pwa';
  settings.imageOps=[{...sourceTransform(settings.imageOps,settings.version),legacyRaster}];
 }
}
