import {recordHistory,restoreHistoryEntry,historyChangedCells,saveHistoryCheckpoint,checkpointHistoryIndex,readAutomaticTrial} from './workspace-history.js';
import {createRecoveryWorker} from './recovery-worker.js';
import {drawCodewordOverlay,codewordCellLabel} from './codeword-overlay.js';
import {needsLegacyImageMigration,migrateLegacyImages} from './session-migration.js';
import {setupWorkflow} from './workflow.js';
import './preferences.js';
import {renderDiagnostic} from './diagnostic-view.js';
import {cropImage, fineCorners, prepareImage, sourceTransform, imageTransform, mapPoints, settingsForQrr, quadMap, moduleMap} from './image-tools.js';
import {drawQrGrid,drawManualCellMarker,drawClassificationValue} from './grid-overlay.js';
import {getLanguage,t,translate} from './i18n.js';
import {unpackQrr, packQrr} from './qrr.js';
import { listSessions, saveSession, removeSession, removeAllSessions, exportWorkspace, importWorkspace, validateMatrix, validateSession, digest } from './storage.js';
const $=id=>document.getElementById(id), clone=x=>structuredClone(x);
const defaults={gridReference:false,visibleFinderRole:null,photographicSource:true,provisionalBlack:96,provisionalWhite:160,previewQuad:null,lmEnabled:true,confirmedPolarity:'UNKNOWN',finders:[],imageOps:[],toneInvert:false,priorityLimit:4,quietZone:true,version:1,quad:null,assigned:true,orientation:0,black:96,white:160,automatic:true,brightness:0,contrast:0,polarity:'AUTO',ecc:'',mask:-1,lockManual:true,single:false,utf8:false,formatLimitMode:'AUTOMATIC',dataLimitMode:'AUTOMATIC',formatLimit:6,dataLimit:20,partial:12,list:1,known:0,curved:false,neighborhood:false,warp:null,colorUnknown:false,colorClusters:[]};
let state={id:crypto.randomUUID(),name:'新しい作業',matrix:null,source:null,settings:{...defaults},history:[],cursor:-1,image:null,imageHash:null};
let tab='input',mode='matrix',zoom=1,pan=false,selected=null,result=null,candidateIndex=0,imageElement=null,timer=null,busySince=0,workerProgress='',drag=null,dragStart=null,saveChain=Promise.resolve();
let imagePixelsCache=null,imagePreview=null,tailData=null;
let dataDisplayResult=null,dataDisplaySuccess=null;
function syncResultDataDisplay(){
 const candidate=result?.candidates[candidateIndex],target=candidate||result?.failureFormat,success=!!candidate;
 if(target&&(dataDisplayResult!==result||dataDisplaySuccess!==success)){
  const defaults=target.dataDisplay;
  $('dataStage').value=defaults.after?'after':'before';
  $('dataUnmask').checked=defaults.unmasked;
  $('dataPartial').checked=defaults.partial;
 }
 dataDisplayResult=result;dataDisplaySuccess=success;
}
let overlayCells=null,overlayFingerprint='',overlayUpdating=false;
let deletingSessions=false;
const correctionCache=new Map();
// Automatic import trials keep their original input separate from editor changes.
let automaticStarted=false,automaticDetailsRequested=false;
function appPreference(key,fallback){try{const v=localStorage.getItem(key);return v===null?fallback:v==='true';}catch{return fallback;}}
$('automaticRepair').checked=appPreference('qrevy-automatic-repair',true);
$('automaticDiagnostics').checked=appPreference('qrevy-automatic-diagnostics',false);
for(const [id,key] of [['automaticRepair','qrevy-automatic-repair'],['automaticDiagnostics','qrevy-automatic-diagnostics']])$(id).onchange=()=>{try{localStorage.setItem(key,String($(id).checked));}catch{}};
$('openCellEditor').onclick=()=>setTab('edit');
function automaticFailureText(reason){const messages={
 TIME_LIMIT:['制限時間に達した処理があり、修復結果を確定できませんでした。','Some processing reached its time limit before a repair result could be confirmed.'],
 NO_INITIAL_GRID:['QRコードの四隅・バージョンを推定できませんでした。詳細解析で指定できます。','The QR corners and version could not be estimated. You can specify them in detailed analysis.'],
 MULTIPLE_CANDIDATES:['複数の候補が見つかり、結果を1件に絞れませんでした。','Multiple candidates were found; a single result could not be established.'],
 SEARCH_LIMIT:['探索量またはメモリの上限に達し、検証を完了できませんでした。','A search or memory limit prevented verification from finishing.'],
 UNRESOLVED:['試した条件では、候補を選ぶために必要な確認を終えられませんでした。','The checks needed to select a candidate could not be completed under the conditions tried.'],
 NO_CANDIDATE:['今回試した条件では、検証を通る修復候補が見つかりませんでした。','No verified repair candidate was found under the conditions tried.'],
 INTERRUPTED:['解析を中断しました。','Analysis was stopped.'],
 INTERNAL_ERROR:['処理中にエラーが発生し、解析を完了できませんでした。','A processing error prevented analysis from finishing.']};return (messages[reason]||messages.UNRESOLVED)[getLanguage()==='ja'?0:1];}
function automaticStatusText(){const a=result?.automatic||state.automatic?.status;if(!a||resultIsStale()||(a.selectedIndex>=0&&!result?.candidates[candidateIndex]))return '';if(a.restored)return getLanguage()==='ja'?'保存された簡易自動修復の条件で再計算しました。':'Recalculated using the saved quick automatic repair conditions.';if(a.selectedIndex<0)return automaticFailureText(a.failureReason);const c=result?.candidates[candidateIndex],assumption=c?.automaticScanAssumption??a.assumption,ja=getLanguage()==='ja';const note={UTF8:['UTF-8を仮定した候補です。','This candidate assumes UTF-8.'],SINGLE_SEGMENT:['単一セグメントを仮定した候補です。','This candidate assumes a single segment.'],UTF8_SINGLE_SEGMENT:['UTF-8と単一セグメントを仮定した候補です。','This candidate assumes UTF-8 and a single segment.'],EXACT_KNOWN_BITS:['読み取れたデータ・ECC bitに誤りがない条件で検証した候補です。','This candidate was verified assuming the observed data/ECC bits are correct.']}[assumption]?.[ja?0:1];return [(ja?'試した条件の範囲で候補が1件になりました。':'One candidate remains within the conditions tried.'),note,(result?.candidates.length>1?(ja?'別の条件では他の候補も見つかっています。':'Other candidates were found under different conditions.'):'')].filter(Boolean).join('\n');}
function renderAutomaticStatus(){const text=automaticStatusText();$('automaticStatus').hidden=!text;$('automaticStatus').textContent=text;const reports=state.automatic?.reports||[];$('automaticReportPanel').hidden=!reports.some(r=>r.report);$('automaticReport').textContent=reports.map(r=>`=== ${r.mode} ===\n${r.report}`).join('\n\n');}
function observedSource(matrix){const source=clone(matrix);source.text=source.text.replace(/\*/g,'?');const symbols=matrix.text.replace(/\n/g,'');source.sources=[...source.sources].map((v,i)=>symbols[i]==='*'?'0':v).join('');return source;}
function adoptAutomaticCandidate(candidate){if(!candidate)return;const before=state.settings;state.matrix=clone(candidate.input||state.matrix);state.source=observedSource(candidate.source||candidate.input||state.source);state.settings={...before,...clone(candidate.settings||{}),version:candidate.version};state.automaticTrial=readAutomaticTrial(candidate);if(candidate.quad){state.settings.quad=clone(candidate.quad);if(state.image)state.settings.gridReference=true;}if(candidate.grid)state.settings.grid=clone(candidate.grid);state.imageOnly=false;restoreCurveChoices();syncSettings();if(result){result.input=clone(state.matrix);result.settings=clone(state.settings);result.fingerprint=JSON.stringify([state.matrix,state.settings,false]);}}
async function runAutomatic(mode='initial'){
 if(recoveryWorker.pending)await recoveryWorker.pending.promise.catch(()=>{});
 const session=mode==='initial'?{mode,firstRun:!automaticStarted,input:{matrix:clone(state.matrix),settings:clone(state.settings)},reports:[]}:state.automatic;
 if(!session)return;automaticDetailsRequested=false;automaticStarted=true;session.mode=mode;state.automatic=session;state.automaticTrial=null;state.oneTimeSingleSegment=false;
 const settings=clone(session.retrySettings||session.input.settings);if(state.image&&!settings.assigned)settings.quad=null;
 const request={action:state.image?'quickRecoverImage':'quickRecover',...(state.image?imageRequest(settings):{}),matrix:session.input.matrix,settings,firstRun:session.firstRun,diagnostics:$('automaticDiagnostics').checked,timeLimitMillis:mode==='extended'?10000:undefined,unlimited:mode==='unlimited'};
 try{
  if(result)result.needsInspectionRestore=true;
  const res=await call(request,'簡易自動修復');
  if(res.input){state.matrix=clone(res.input);state.source=observedSource(res.source||res.input);}
  if(res.settings){
   const manualQuad=state.settings.quad;
   state.settings={...state.settings,...clone(res.settings)};session.retrySettings={...session.input.settings,...clone(res.settings)};
   if(state.image&&res.settings.quad)state.settings.gridReference=true;
   // A failed estimate still needs editable corners in detailed analysis.
   // Keep the engine's retry settings separate from this manual fallback.
   if(state.image&&!state.settings.quad)state.settings.quad=clone(manualQuad||[request.width*.1,request.height*.1,request.width*.9,request.height*.1,request.width*.9,request.height*.9,request.width*.1,request.height*.9]);
  }
  state.automaticTrial=readAutomaticTrial(res)||readAutomaticTrial(res.automatic);
  if(!state.matrix){await detect({recordHistory:false});}
  if(!session.input.matrix){session.input.matrix=clone(state.matrix);session.input.settings=clone(state.settings);}
  result=res;result.input=clone(state.matrix);result.settings=clone(state.settings);result.fingerprint=JSON.stringify([state.matrix,state.settings,false]);
  session.status=clone(res.automatic||{selectedIndex:-1,failureReason:'UNRESOLVED'});
  if(session.status.report)session.reports.push({mode,report:session.status.report});
  candidateIndex=session.status.selectedIndex>=0?session.status.selectedIndex:res.candidates.length?0:-1;
  adoptAutomaticCandidate(res.candidates[candidateIndex]);
  syncSettings();record('簡易自動修復');selected=null;renderResult();setTab('results');$('fitView').onclick();
  if(automaticDetailsRequested)openDetailedAnalysis();else showAutomaticResult();
 }catch(error){session.status={selectedIndex:-1,failureReason:'INTERRUPTED'};if(!state.matrix)await detect({recordHistory:false});if(!session.input.matrix){session.input.matrix=clone(state.matrix);session.input.settings=clone(state.settings);}record('簡易自動修復');renderAutomaticStatus();report(error);openDetailedAnalysis();}
}
function showAutomaticResult(){const a=result?.automatic;if(!a)return;const ja=getLanguage()==='ja',success=a.selectedIndex>=0;const retry=a.failureReason==='TIME_LIMIT'&&state.automatic?.mode!=='unlimited';$('automaticTitle').textContent=success?(ja?'復元候補が見つかりました':'Recovery candidate found'):(ja?'簡易自動修復の結果':'Quick automatic repair result');$('automaticMessage').textContent=[success?result.candidates[a.selectedIndex]?.text:'',automaticStatusText(),!success&&result.candidates.length?(ja?'候補の内容一覧から、見つかった候補を確認できます。':'Review retained candidates in Candidate contents.'):null,retry?(state.automatic.mode==='initial'?(ja?'同じ入力・格子を使い、最大10秒の時間枠で最初から再試行できます。':'Retry the same input and grid from the beginning with a time allowance of up to 10 seconds.'):(ja?'同じ入力・格子を使い、時間制限なしで最初から再試行できます。探索量・メモリの上限、復号条件、成功判定は維持します。':'Retry the same input and grid without time limits. Search and memory limits, decoding conditions, and success criteria remain unchanged.')):null].filter(Boolean).join('\n\n');$('automaticRetry').hidden=!retry;$('automaticRetry').textContent=state.automatic?.mode==='initial'?(ja?'時間枠を広げて再試行（10秒）':'Retry with more time (10 seconds)'):(ja?'時間制限なしで再試行':'Retry without time limits');$('automaticCopy').hidden=!success;$('automaticCopy').disabled=!success||result.candidates[a.selectedIndex]?.text==null;if(!$('automaticDialog').open)$('automaticDialog').showModal();}
$('automaticClose').onclick=()=>$('automaticDialog').close();
function openDetailedAnalysis(){mode=imageElement?'image':'matrix';setTab('input');$('fitView').onclick();}
$('automaticDetailed').onclick=()=>{$('automaticDialog').close();openDetailedAnalysis();};
$('automaticRetry').onclick=()=>{const next=state.automatic.mode==='initial'?'extended':'unlimited';$('automaticDialog').close();runAutomatic(next).catch(report);};
$('automaticCopy').onclick=()=>{const c=result?.candidates[result.automatic?.selectedIndex];if(c?.text!=null)navigator.clipboard.writeText(c.text).then(()=>toast('全文をコピーしました')).catch(report);};
$('copyAutomaticReport').onclick=()=>navigator.clipboard.writeText($('automaticReport').textContent).then(()=>toast(getLanguage()==='ja'?'診断をコピーしました':'Diagnostics copied')).catch(report);

let selectedCells=[],cellGesture=null;const regionCache=new Map(),regionKindsCache=new Map(),regionLimitsCache=new Map();
async function regions(){const v=state.settings.version;if(!regionCache.has(v)){const r=await call({action:"regions",matrix:state.matrix,settings:state.settings});regionCache.set(v,new Set(r.allowed));regionKindsCache.set(v,r.kinds);regionLimitsCache.set(v,r.limits);renderLimitLabels();}return regionCache.get(v);}
async function revalidatePriority(){if(!state.settings.assigned)return;const allowed=await regions(),rows=rowsOf(state.matrix),n=rows.length;let removed=0;for(let y=0;y<n;y++)rows[y]=[...rows[y]].map((c,x)=>{if(c=== "*"&&!allowed.has(y*n+x)){removed++;return "?";}return c;}).join("");state.matrix.text=rows.join("\n");if(removed)toast(`${removed}個の優先指定を解除しました。不明セルとして保持しています。`);}
const canvas=$('canvas'),ctx=canvas.getContext('2d');
// Image geometry always uses source pixels, independently of the display bitmap.
let canvasCoordinates={width:canvas.width,height:canvas.height};
for(let v=1;v<=40;v++)$('version').add(new Option(`V${v} · ${17+4*v}×${17+4*v}`,v));
for(let m=0;m<8;m++)$('mask').add(new Option(String(m),m));
for(const id of ['formatLimitMode','dataLimitMode'])for(const [v,label] of [['AUTOMATIC','初期設定'],['EXACT','完全一致 (0 bit)'],['STRICT','厳しめ'],['RELAXED','緩め'],['MANUAL','手動'],['UNLIMITED','制限なし']])$(id).add(new Option(label,v));
function renderNativeHelp(){for(const el of document.querySelectorAll('[data-native-key]'))el.textContent=t(el.dataset.nativeKey);if(!result){$('diagnostic').textContent=translate('未実行');$('resultCellDetail').textContent=t('inline:セルをタップ：セルの修復理由を表示');}}
renderNativeHelp();
function toast(text){$('toast').textContent=translate(text);$('toast').hidden=false;clearTimeout(toast.timeout);toast.timeout=setTimeout(()=>$('toast').hidden=true,6000);}
function report(error){console.error(error);toast(error?.message||String(error));}
const recoveryWorker=createRecoveryWorker({
  createWorker:()=>new Worker(new URL('./worker.js',import.meta.url),{type:'module'}),
  getLanguage,
  messages:{
    busy:'現在の処理が終わるまでお待ちください',
    loadFailed:'修復エンジンの読み込みに失敗しました。再読み込みしてください。',
    stopped:'処理を中断しました。この実行の未確定候補は保存されません。前回の結果と編集内容は保持しています。',
  },
  onBusy:setBusy,
  onProgress:text=>{workerProgress=text;$('progressText').textContent=text;},
  onError:report,
  onTerminated:()=>{if(result)result.needsInspectionRestore=true;},
});
recoveryWorker.start();
function call(request,label='処理中'){return recoveryWorker.call(request,label);}
function setBusy(busy,label){$('cancel').textContent=translate(busy&&['quickRecover','quickRecoverImage'].includes(recoveryWorker.pending?.action)?'詳細解析':'修復を中断');label=busy&&['recover','recoverImage','quickRecover','quickRecoverImage'].includes(recoveryWorker.pending?.action)?translate('復元を準備中'):label?translate(label):label;
  $('progress').hidden=!busy;$('forceStopHelp').hidden=true;$('progressTitle').hidden=!busy||!['recover','recoverImage','quickRecover','quickRecoverImage'].includes(recoveryWorker.pending?.action);$('recover').disabled=busy||!state.matrix||!state.settings.assigned;
  for(const el of document.querySelectorAll('.viewer-context button,.viewer-context input,.viewer-context select,.panel button,.panel input,.panel select,.import-grid button,#demo,#emptyImport,#sessionsButton,#save,#exportSession,#exportQrr,#exportText,#resultStage,#unmask,#languageButton,#helpButton,#homeButton,#exitButton,#appMenuButton,#workMenuButton'))el.disabled=busy;
  if(busy){workerProgress='';busySince=Date.now();$('progressText').textContent=label;timer=setInterval(()=>{$('progressText').textContent=`${workerProgress||label}\n${getLanguage()==='ja'?'経過時間: ':'Elapsed: '}${Math.floor((Date.now()-busySince)/1000)}${getLanguage()==='ja'?'秒':'s'}`;},1000);}else{clearInterval(timer);timer=null;$('copyPayload').disabled=result?.candidates[candidateIndex]?.text==null||resultIsStale();$('exportPng').disabled=!result?.candidates[candidateIndex]||resultIsStale();$('undo').disabled=state.cursor<=0;$('redo').disabled=state.cursor>=state.history.length-1;$('imageView').disabled=!imageElement;$('candidatePicker').closest('.candidate-picker').hidden=(result?.candidates.length||0)<2;$('browseCandidates').hidden=(result?.candidates.length||0)<2;$('candidatePicker').disabled=!result?.candidates.length||resultIsStale();$('browseCandidates').disabled=!result?.candidates.length||resultIsStale();$('sameCodeword').disabled=!result?.candidates[candidateIndex];renderNextResultAction();applyEligibility();}
}
$('cancel').onclick=()=>{
  if(!recoveryWorker.pending)return;
  if(['quickRecover','quickRecoverImage'].includes(recoveryWorker.pending.action))automaticDetailsRequested=true;
  if(recoveryWorker.stop()==='stopping'){
    $('progressText').textContent=translate('中断処理中…見つかった候補を保持します');
    $('forceStopHelp').textContent=getLanguage()==='ja'?'PWA: もう一度押すと処理を強制終了します。':'PWA: Press again to terminate the operation.';
    $('forceStopHelp').hidden=false;clearInterval(timer);
  }
};
function historyDetail(h){
  if(h.detail)return h.detail;
  const names={black:['黒の上限','Black maximum'],white:['白の下限','White minimum'],lmEnabled:['LM格子補正','LM grid refinement'],warp:['曲面補正の格子','Curved grid'],colorClusters:['色クラスタ','Color clusters'],quad:['四隅','Corners'],finders:['Finder指定','Finder assignments'],imageOps:['画像変換','Image transform'],confirmedPolarity:['Finderの白黒','Finder polarity']},ja=getLanguage()==='ja';
  return (h.changes||[]).map(change=>{
    const el=$(change.key),label=names[change.key]?.[ja?0:1]||[...(el?.labels?.[0]?.childNodes||[])].filter(n=>n.nodeType===Node.TEXT_NODE).map(n=>n.textContent.trim()).join(' ')||change.key;
    const value=v=>{if(typeof v==='boolean')return (change.key==='lockManual'?!v:v)?'ON':'OFF';if(el?.tagName==='SELECT'){const option=[...el.options].find(o=>o.value===String(v));if(option)return option.textContent;}return Array.isArray(v)?JSON.stringify(v):String(v??'—');};
    return `${label}: ${value(change.before)} → ${value(change.after)}`;
  }).join(' / ');
}
function clearAnalysis(){
  inputDataRevision++;inputDataKey='';$('inputDataReport').textContent='';clearTailAnalysis();
}
function clearTailAnalysis(){
  tailInspectionRevision++;tailData=null;$('tailReport').textContent='';$('tailRaw').textContent='';$('tailControls').hidden=true;$('tailRawPanel').hidden=true;
  $('tailSource').replaceChildren();$('tailInterpretation').replaceChildren();
}
function record(label){
  clearAnalysis();
  if(!recordHistory(state,label,defaults,currentSelection))return;
  renderHistory();autoSave();
}
function restoreHistory(index){if(recoveryWorker.pending)return;const h=state.history[index];if(!h)return;if(!state.matrix||rowsOf(state.matrix).length!==rowsOf(h.matrix).length){selected=null;selectedCells=[];}clearAnalysis();overlayFingerprint=0;restoreHistoryEntry(state,index,defaults);restoreCurveChoices();result=null;renderResult();syncSettings();render();renderHistory();autoSave();restoreSelection(h.selection,false).catch(report);}
function renderHistory(){
  $('undo').disabled=state.cursor<=0;$('redo').disabled=state.cursor>=state.history.length-1;
  $('history').replaceChildren(...state.history.map((h,i)=>{const b=document.createElement('button');const time=Number.isFinite(h.timestamp)?new Date(h.timestamp).toLocaleTimeString(getLanguage()==='ja'?'ja-JP':'en-GB',{hour12:false,hour:'2-digit',minute:'2-digit',second:'2-digit'}):'',kinds={IMPORT:['取込','Import'],AUTO_RECOVERY:['修復','Repair'],USER_EDIT:['編集','Edit'],APPLY_RECOVERY:['反映','Apply'],GEOMETRY:['格子','Geometry'],CANDIDATE:['候補','Candidate'],SYSTEM:['操作','Action'],SETTINGS:['設定','Settings']},detail=historyDetail(h);b.textContent=`${i===state.cursor?'● ':' '}${i+1}. ${time} ${kinds[h.kind]?.[getLanguage()==='ja'?0:1]||''} ${translate(['フルオート解析','Automatic scan'].includes(h.label)?'簡易自動修復':h.label)}${` Δ ${historyChangedCells(h,state.history[i-1])}${getLanguage()==='ja'?'セル':' cells'}`}${detail?'\n'+detail:''}`;b.style.whiteSpace='pre-line';b.onclick=()=>restoreHistory(i);return b;}));
}
$('undo').onclick=()=>restoreHistory(state.cursor-1);$('redo').onclick=()=>restoreHistory(state.cursor+1);
function workspaceName(s){return s.nameKey===s.name&&['TXT行列','損傷サンプル','画像からの修復','TXT行列 · 画像調整','損傷サンプル · 画像調整'].includes(s.nameKey)?s.nameKey.split(' · ').map(translate).join(' · '):s.name;}
function serializable(){return {id:state.id,name:state.name,nameKey:state.nameKey,updated:Date.now(),history:clone(state.history),cursor:state.cursor,checkpointCursor:state.checkpointCursor??0,checkpoint:clone(state.checkpoint||null),image:state.image,imageHash:state.imageHash,qrrRaw:state.qrrRaw,qrrVersion:state.qrrVersion};}
function autoSave(){if(!state.matrix||deletingSessions)return;const session=serializable();saveChain=saveChain.catch(()=>{}).then(()=>saveSession(session)).then(()=>{$('saveStatus').textContent=`端末内に保存済み · ${new Date().toLocaleTimeString('ja-JP',{hour:'2-digit',minute:'2-digit'})}`;}).catch(e=>{$('saveStatus').textContent='自動保存できません。作業をファイルに書き出してください。';console.error(e);});}
$('save').onclick=()=>{if(recoveryWorker.pending||!saveHistoryCheckpoint(state))return;autoSave();toast('端末内への保存を開始しました');navigator.storage?.persist?.().catch(()=>{});};
function syncSettings(){$('formatSearchCount').textContent=getLanguage()==='ja'?`探索対象: ${(state.settings.ecc?1:4)*(state.settings.mask<0?8:1)}形式`:`Formats to search: ${(state.settings.ecc?1:4)*(state.settings.mask<0?8:1)}`;$('curveChoice').hidden=!state.settings.curved||!curve.candidates?.length;$('curveStatus').hidden=!state.settings.curved;$('refine').checked=!!state.settings.lmEnabled;for(const [key,value] of Object.entries(state.settings)){const el=$(key);if(!el)continue;if(el.type==='checkbox')el.checked=key==='lockManual'?!value:!!value;else if(value!==null && typeof value!=='object')el.value=String(value);}for(const id of ['formatLimit','dataLimit'])$(id).hidden=state.settings[id+'Mode']!=='MANUAL';$('brightnessValue').textContent=state.settings.brightness;$('contrastValue').textContent=state.settings.contrast;syncThresholdControls();renderThresholdLabels();renderLimitLabels();$('editKind').querySelector('[value="1"]').textContent=t(state.settings.lockManual?'cell_source_fixed':'cell_source_fixed_correctable');}
function readSettings(changedKey=null){const s={...state.settings};for(const key of Object.keys(defaults)){const el=$(key);if(!el)continue;if(el.type==='checkbox')s[key]=key==='lockManual'?!el.checked:el.checked;else if(typeof defaults[key]==='number'){if(el.value.trim()==='')throw new Error(getLanguage()==='ja'?'数値を入力してください。':'Enter a number.');s[key]=['black','white'].includes(key)&&thresholdsInverted()?255-Number(el.value):Number(el.value);}else s[key]=el.value;}if(!Number.isInteger(s.black)||!Number.isInteger(s.white)||s.black<0||s.white>255||s.black>=s.white)throw new Error(getLanguage()==='ja'?`濃度閾値は0〜255の整数とし、QRの黒 ${thresholdsInverted()?'>':'<'} QRの白にしてください。`:`Enter integer density thresholds from 0 to 255, with QR black ${thresholdsInverted()?'>':'<'} QR white.`);for(const key of ['formatLimit','dataLimit']){const maximum=key==='formatLimit'?30:changedKey==='dataLimit'?(regionCache.get(s.version)?.size??29648):29648;if(!Number.isInteger(s[key])||s[key]<0||s[key]>maximum)throw new Error(getLanguage()==='ja'?`誤り上限は0〜${maximum}の整数を入力してください。`:`Enter an integer error limit from 0 to ${maximum}.`);}return s;}
for(const key of Object.keys(defaults)){
 const el=$(key);if(!el||['version','orientation'].includes(key))continue;
 el.onchange=async()=>{
  const before={settings:clone(state.settings),matrix:clone(state.matrix),source:clone(state.source),imageOnly:state.imageOnly};
  try{
   const next=readSettings(key);if(state.matrix&&['ecc','mask'].includes(key))await call({action:'validate',matrix:state.matrix,settings:next});
   state.settings=next;
   if(key==='assigned'&&state.settings.assigned)state.settings.gridReference=true;
   if(key==='assigned'&&!state.settings.assigned){invalidateLm(true);state.settings.grid=null;state.settings.warp=null;state.settings.previewQuad=null;curve.candidates=[];}
   if(['brightness','contrast','toneInvert','polarity','black','white','automatic'].includes(key)){invalidateLm(true);state.settings.warp=null;state.settings.previewQuad=null;curve.candidates=[];}
   if(key==='curved'&&!state.settings.curved){state.settings.warp=null;curve.candidates=[];}
   if(key==='black'||key==='white')state.settings.automatic=false;
   syncSettings();
   // Commit the new settings and all derived samples as one undo step.
   if(state.image&&['curved','assigned'].includes(key)&&state.settings.curved&&state.settings.assigned)await curve({recordHistory:false});
   else if(state.image&&['black','white','automatic','polarity','brightness','contrast','toneInvert','curved','colorUnknown','assigned'].includes(key))await sample(false,{recordHistory:false});
   record('設定を変更');render();
  }catch(e){Object.assign(state,before);imagePixelsCache=null;restoreCurveChoices();syncSettings();render();report(e);}
 };
}
// Keep native range gestures responsive; commit sampling when the thumb is released.
function thresholdsInverted(){return state.settings.confirmedPolarity==='INVERTED'||state.settings.confirmedPolarity!=='NORMAL'&&state.settings.polarity==='INVERTED';}
function syncThresholdControls(black=state.settings.black,white=state.settings.white){
 const inverted=thresholdsInverted();
 for(const [key,value] of [['black',black],['white',white]]){
  $(key+'Range').value=value;$(key).value=inverted?255-value:value;$(key+'Range').setAttribute('aria-valuetext',$(key).value);
  $(key).min=key==='black'?(inverted?1:0):(inverted?0:1);$(key).max=key==='black'?(inverted?255:254):(inverted?254:255);
 }
}
function thresholdDraft(key){
 const number=$(key),value=Number(number.value);
 if(number.value.trim()===''||!Number.isInteger(value)||value<Number(number.min)||value>Number(number.max))return false;
 const logical=thresholdsInverted()?255-value:value;
 let black=key==='black'?logical:Number($('blackRange').value),white=key==='white'?logical:Number($('whiteRange').value);
 if(black>=white){if(key==='black')white=black+1;else black=white-1;}
 $('automatic').checked=false;syncThresholdControls(black,white);renderThresholdLabels(black,white);return true;
}
for(const key of ['black','white']){
 const number=$(key),range=$(key+'Range'),commit=number.onchange;
 number.oninput=()=>{if(number.validity.valid&&number.value!==''){range.value=thresholdsInverted()?255-Number(number.value):number.value;range.setAttribute('aria-valuetext',number.value);}};
 number.onchange=()=>{thresholdDraft(key);return commit();};
 range.oninput=()=>{number.value=thresholdsInverted()?255-Number(range.value):range.value;thresholdDraft(key);};
 range.onchange=()=>number.onchange();
}
$('version').onchange=async()=>{const v=Number($('version').value);if(state.matrix && v!==state.settings.version&&!confirm(translate('バージョンを変更するとセル編集が解除されます。変更しますか？'))){syncSettings();return;}let remapped;try{remapped=rectifiedVersionSettings(v);}catch(e){syncSettings();report(e);return;}selected=null;selectedCells=[];state.settings.grid=null;state.settings.warp=null;state.settings.previewQuad=null;result=null;renderResult();disableLm();if(remapped){Object.assign(state.settings,remapped);imagePixelsCache=null;}state.settings.version=v;if(state.image){state.source=null;state.matrix=null;await sample().catch(report);}else{const n=17+4*v;state.matrix={text:Array(n).fill('?'.repeat(n)).join('\n'),sources:'0'.repeat(n*n)};state.source=clone(state.matrix);await regions();renderLimitLabels();record('バージョン変更');render();}};
$('orientation').onchange=async()=>{const next=Number($('orientation').value),old=state.settings.orientation;for(let k=0;k<(next-old+4)%4;k++)transformMatrix(false);state.settings.orientation=next;syncSettings();await revalidatePriority();record('向きを変更');render();if(state.image&&state.settings.curved)await sample();};
function setTab(next){if(next!=='edit'&&$('inputDataPanel').open)$('inputDataPanel').close();tab=next;document.body.dataset.activeTab=next;document.querySelectorAll('[data-tab]').forEach(b=>b.classList.toggle('active',b.dataset.tab===tab));document.querySelectorAll('[data-panel]').forEach(p=>p.hidden=p.dataset.panel!==tab);document.querySelectorAll('[data-context]').forEach(p=>p.hidden=p.dataset.context!==tab);if(next==='edit'||next==='results')mode='matrix';if(next==='edit'&&state.matrix&&!regionCache.has(state.settings.version))regions().then(render).catch(report);if(next==='results')renderResult();$('viewLabel').textContent=next==='results'?'RECOVERY / 修復結果':next==='edit'?'MODULE EDITOR / セル編集':'SOURCE / 入力';render();}
document.querySelectorAll('[data-tab]').forEach(b=>b.onclick=()=>{setTab(b.dataset.tab);if(state.matrix)document.querySelector('.steps').scrollIntoView({block:'start'});});
function resultInput(){return result?.candidates[candidateIndex]?.input||result?.input||state.matrix;}
function resultIsStale(){return !!result&&result.fingerprint!==JSON.stringify([state.matrix,state.settings,!!state.oneTimeSingleSegment]);}
function matrixNow(){return tab==='results'&&result?.candidates[candidateIndex]?($('resultStage').value==='before'?resultInput():result.candidates[candidateIndex].matrix):tab==='results'&&result?resultInput():state.matrix;}
function rowsOf(m){return m.text.split('\n');}
function imageDisplaySettings(){
 if(tab!=='results'||!result)return state.settings;
 const candidate=result.candidates[candidateIndex],settings={...state.settings,...result.settings,...candidate?.settings};
 if(candidate?.quad){settings.quad=candidate.quad;settings.previewQuad=null;settings.warp=null;}
 // Interpolate the exact trial grid, including any LM or curved correction.
 if(candidate?.grid){settings.grid=candidate.grid;settings.warp=[];}
 return settings;
}
function drawImageLabel(context,text,x,y,width,height,padding){
 const bounds=context.measureText(text),ascent=bounds.actualBoundingBoxAscent||parseFloat(context.font),descent=bounds.actualBoundingBoxDescent||0;
 if(bounds.width>width-2*padding||ascent+descent>height-2*padding)return;
 context.fillText(text,Math.max(padding,Math.min(x,width-padding-bounds.width)),Math.max(padding+ascent,Math.min(y,height-padding-descent)));
}
function drawImageLayer(target,matrix,settings,request,displayWidth){
 const cx=target.getContext('2d');
    cx.fillStyle='#fff';cx.fillRect(0,0,target.width,target.height);cx.setTransform(target.width/request.width,0,0,target.height/request.height,0,0);cx.drawImage(imagePreview,0,0);
    const grid=settings.grid,geometry=settings.quad?moduleMap(settings):null,lineScale=request.width/displayWidth,rasterPixel=Math.max(request.width/target.width,request.height/target.height),manualOnly=tab==='edit'&&$('manualFocus').checked;
    if(grid&&matrix&&drag===null){
      const rows=rowsOf(matrix),n=rows.length,selectedSet=new Set(selectedCells);
      for(let i=0;i<grid.length;i++){
        const x=i%n,y=Math.floor(i/n),point=grid[i],value=rows[y][x],manual=['1','5','6'].includes(matrix.sources[i]),next=geometry(x+1,y),pitch=Math.hypot(next[0]-point[0],next[1]-point[1]);
        if(!manualOnly||manual||selectedSet.has(i))drawClassificationValue(cx,value,point[0],point[1],pitch,lineScale,rasterPixel);
      }
    }
    if(geometry&&tab==='edit'&&$('codewordOverlay').checked&&overlayFingerprint===matrix?.text)drawCodewordOverlay(cx,overlayCells,geometry,lineScale);
    if(geometry)drawQrGrid(cx,{version:settings.version,assigned:settings.assigned,map:geometry,lineScale,manualOnly,curved:!!settings.warp});
    if(grid&&matrix&&drag===null){
      const values=matrix.text.replace(/\n/g,'');
      for(let i=0;i<grid.length;i++){
        const point=grid[i],source=matrix.sources[i];
        if(['1','5','6'].includes(source))drawManualCellMarker(cx,point[0],point[1],5*lineScale,1.5*lineScale,source==='5'||values[i]==='?'||values[i]==='*');
        if(selectedCells.includes(i)){cx.strokeStyle='#ffff00';cx.lineWidth=1.5*lineScale;cx.beginPath();cx.arc(point[0],point[1],4*lineScale,0,Math.PI*2);cx.stroke();}
      }
    }
    const q=tab==='results'?null:settings.quad;if(q){cx.strokeStyle='#df8b32';cx.lineWidth=2*lineScale;cx.beginPath();cx.moveTo(q[0],q[1]);for(let i=1;i<4;i++)cx.lineTo(q[i*2],q[i*2+1]);cx.closePath();cx.stroke();for(let i=0;i<4;i++){cx.beginPath();cx.fillStyle=(settings.finders||[]).some((role,index)=>index%3===0&&role===i)?'#00c853':'#ff8c00';cx.arc(q[i*2],q[i*2+1],10*lineScale,0,Math.PI*2);cx.fill();cx.font=`${Math.max(12*lineScale,10*rasterPixel)}px sans-serif`;cx.fillStyle='#bd6524';if(settings.assigned)drawImageLabel(cx,(getLanguage()==='ja'?['左上','右上','右下','左下']:['TL','TR','BR','BL'])[i],q[i*2]+10*lineScale,q[i*2+1],request.width,request.height,2*lineScale);}}
    for(let i=0;tab!=='results'&&i<(settings.finders||[]).length;i+=3){
      const [role,x,y]=settings.finders.slice(i,i+3);cx.strokeStyle='#ffff00';cx.lineWidth=3*lineScale;cx.beginPath();cx.arc(x,y,18*lineScale,0,Math.PI*2);cx.stroke();
      if(!settings.assigned){cx.fillStyle='#ffff00';cx.font=`${12*lineScale}px sans-serif`;drawImageLabel(cx,{0:'TOP_LEFT',1:'TOP_RIGHT',3:'BOTTOM_LEFT'}[role],x+20*lineScale,y,request.width,request.height,2*lineScale);}
    }
}
let updateWorkflow=()=>{};
function render(){
  renderAutomaticStatus();
  updateWorkflow({state,tab,result,stale:resultIsStale()});
  renderNextResultAction();
  scheduleEligibility();
  if(tab==='edit'&&$('inputDataPanel').open)queueMicrotask(updateInputData);
  canvas.style.touchAction='none';$('panView').classList.toggle('selected',pan);$('panView').setAttribute('aria-pressed',String(pan));
  if(tab==='edit'&&state.matrix&&$('codewordOverlay').checked&&overlayFingerprint!==state.matrix?.text&&!overlayUpdating&&(!recoveryWorker.pending||recoveryWorker.pending.action==='uiState'))queueMicrotask(()=>{if(!overlayUpdating&&(!recoveryWorker.pending||recoveryWorker.pending.action==='uiState')&&$('codewordOverlay').checked&&overlayFingerprint!==state.matrix?.text)$('codewordOverlay').onchange();});const m=matrixNow(),has=!!m||!!imageElement;document.body.classList.toggle('has-work',has);$('imageQuickActions').hidden=!imageElement;$('editorTools').hidden=tab!=='edit'||!m;$('editorActions').hidden=tab!=='edit'||!m;$('resultViewTools').hidden=tab!=='results'||!result;$('resultInspection').hidden=tab!=='results'||!result?.candidates[candidateIndex];$('candidatePicker').closest('.candidate-picker').hidden=(result?.candidates.length||0)<2;$('browseCandidates').hidden=(result?.candidates.length||0)<2;$('candidatePicker').disabled=!result?.candidates.length||resultIsStale();$('browseCandidates').disabled=!result?.candidates.length||resultIsStale();$('sameCodeword').disabled=!result?.candidates[candidateIndex];
  $('cellSummary').hidden=!m||tab==='results';$('reasonLegend').hidden=tab!=='results'||!result;
  $('emptyState').hidden=has;canvas.hidden=!has;$('viewTools').hidden=!has;$('imageSettings').hidden=!imageElement;
  $('matrixImageTools').hidden=!state.matrix||!!state.image||!!state.imageOnly||!!state.qrrRaw;
  const priorityCount=(state.matrix?.text.match(/\*/g)||[]).length,manualCount=(state.matrix?.sources.match(/[156]/g)||[]).length,undoCount=Math.max(0,state.cursor),redoCount=Math.max(0,state.history.length-1-state.cursor);$('priorityCount').textContent=getLanguage()==='ja'?`優先探索 ${priorityCount} / ${state.settings.priorityLimit} · ${2**priorityCount}通り`:`Priority search ${priorityCount} / ${state.settings.priorityLimit} · ${2**priorityCount} combinations`;$('editCounts').textContent=getLanguage()==='ja'?`変更セル: ${manualCount}セル · Undo可能: ${undoCount} · Redo可能: ${redoCount}`:`Changed cells: ${manualCount} · Undo available: ${undoCount} · Redo available: ${redoCount}`;
  $('recover').disabled=!!recoveryWorker.pending||!state.matrix||!!state.imageOnly||!state.settings.assigned;$('attachImage').hidden=!state.qrrRaw||!!state.image;$('imageOnlyNotice').hidden=!state.imageOnly;$('viewTitle').toggleAttribute('data-user-content',has);$('viewTitle').textContent=has?workspaceName(state):'修復するQRを読み込む';
  $('dimensions').textContent=m?`V${(rowsOf(m).length-17)/4} · ${rowsOf(m).length}×${rowsOf(m).length}`:'未読み込み';
  $('imageView').disabled=!imageElement;$('imageView').classList.toggle('selected',mode==='image');$('matrixView').classList.toggle('selected',mode==='matrix');
  $('zoomLabel').textContent=`${Math.round(zoom*100)}%`;
  if(!has)return;
  const comparison=tab==='results'&&$('resultStage').value==='compare',viewport=$('canvasScroll'),viewportStyle=getComputedStyle(viewport);
  const availableWidth=Math.max(1,viewport.clientWidth-parseFloat(viewportStyle.paddingLeft)-parseFloat(viewportStyle.paddingRight)-(comparison?8:0))/(comparison?2:1);
  const availableHeight=Math.max(1,viewport.clientHeight-parseFloat(viewportStyle.paddingTop)-parseFloat(viewportStyle.paddingBottom)-(comparison?20:0));
  const base=Math.min(availableWidth,availableHeight,700);
  if(mode==='image'&&imageElement){
    const displaySettings=imageDisplaySettings(),request=imageRequest(displaySettings),ratio=request.height/request.width;
    const fitted=Math.min(availableWidth,availableHeight/ratio,700),displayWidth=fitted*zoom;
    // Redraw labels at the visible resolution rather than magnifying tiny source-pixel text.
    // Cap the display allocation without changing the original image or sampling request.
    const scale=Math.min(Math.max(1,displayWidth*(devicePixelRatio||1)/request.width),Math.sqrt(8_000_000/(request.width*request.height)));
    canvas.width=Math.max(1,Math.floor(request.width*scale));canvas.height=Math.max(1,Math.floor(request.height*scale));
    canvasCoordinates={width:request.width,height:request.height};canvas.dataset.imageWidth=request.width;canvas.dataset.imageHeight=request.height;
    canvas.style.width=`${displayWidth}px`;canvas.style.height=`${displayWidth*ratio}px`;
    drawImageLayer(canvas,m,displaySettings,request,displayWidth);
  }else if(m){
    const rows=rowsOf(m),n=rows.length,unit=12,quiet=2;canvas.width=canvas.height=(n+quiet*2)*unit;canvasCoordinates={width:canvas.width,height:canvas.height};delete canvas.dataset.imageWidth;delete canvas.dataset.imageHeight;canvas.style.width=canvas.style.height=`${base*zoom}px`;ctx.fillStyle='#fff';ctx.fillRect(0,0,canvas.width,canvas.height);
    const c=result?.candidates[candidateIndex]||result?.failureFormat,original=resultInput()?rowsOf(resultInput()):null;
    for(let y=0;y<n;y++)for(let x=0;x<n;x++){
      let value=rows[y][x];if(tab==='results'&&c&&$('unmask').checked&&!isFunction(c.version,x,y)&&maskBit(c.mask,x,y)&&['0','1'].includes(value))value=value==='1'?'0':'1';
      ctx.fillStyle=value==='1'?(tab==='results'?'#000':'#172d25'):value==='0'?'#ffffff':tab==='results'?'#b9b9b9':value==='*'?'#b7a0d2':'#ccd2c6';

      ctx.fillRect((x+quiet)*unit,(y+quiet)*unit,unit,unit);if(tab==='results'){if(value==='?'||value==='*'){ctx.strokeStyle='#787878';ctx.lineWidth=.7;ctx.beginPath();ctx.moveTo((x+quiet)*unit+3,(y+quiet)*unit+3);ctx.lineTo((x+quiet)*unit+9,(y+quiet)*unit+9);ctx.moveTo((x+quiet)*unit+9,(y+quiet)*unit+3);ctx.lineTo((x+quiet)*unit+3,(y+quiet)*unit+9);ctx.stroke();}if($('resultStage').value!=='before'&&c?.trace?.[`${x},${y}`]){ctx.strokeStyle=reasonColor(c.trace[`${x},${y}`].reason);ctx.lineWidth=1.4;ctx.strokeRect((x+quiet)*unit+1,(y+quiet)*unit+1,unit-2,unit-2);}}if(tab==='edit'&&$('manualFocus').checked&&!['1','5','6'].includes(m.sources[y*n+x])){ctx.fillStyle='#ffffffbb';ctx.fillRect((x+quiet)*unit,(y+quiet)*unit,unit,unit);}
      if(tab!=='results'&&['1','5','6'].includes(m.sources?.[y*n+x]))drawManualCellMarker(ctx,(x+quiet+.5)*unit,(y+quiet+.5)*unit,Math.max(4*canvas.width/(base*zoom),unit*.38),1.5*canvas.width/(base*zoom),m.sources[y*n+x]==='5'||value==='?'||value==='*');
      if(tab==='results'&&$('sameCodeword').checked&&selected&&relatedResultCell(c,x,y)){ctx.strokeStyle=reasonColor(c?.trace?.[`${x},${y}`]?.reason);ctx.lineWidth=3.2;ctx.strokeRect((x+quiet)*unit+1,(y+quiet)*unit+1,unit-2,unit-2);}if(zoom>=1.8){ctx.strokeStyle='#7e907326';ctx.lineWidth=.3;ctx.strokeRect((x+quiet)*unit,(y+quiet)*unit,unit,unit);}
    }
    if(tab==='edit'&&$('codewordOverlay').checked&&overlayFingerprint===state.matrix?.text)drawCodewordOverlay(ctx,overlayCells,(x,y)=>[(x+quiet+.5)*unit,(y+quiet+.5)*unit],canvas.width/(base*zoom));
    for(const i of selectedCells){ctx.strokeStyle='#f18a22';ctx.lineWidth=1;ctx.strokeRect((i%n+quiet)*unit,(Math.floor(i/n)+quiet)*unit,unit,unit);}
    if(selected&&selected.x<n&&selected.y<n){ctx.strokeStyle=tab==='results'?'#00ffff':'#f18a22';ctx.lineWidth=2;ctx.strokeRect((selected.x+quiet)*unit,(selected.y+quiet)*unit,unit,unit);}
  }
  if(m)$('cellSummary').textContent=classificationText(m);renderBeforeComparison();showCell();
}
function isFunction(v,x,y){const n=17+4*v;if(x<9&&y<9||x>=n-8&&y<9||x<9&&y>=n-8||x===6||y===6||v>6&&(x>=n-11&&y<6||y>=n-11&&x<6))return true;if(v===1)return false;const count=Math.floor(v/7)+2,step=v===32?26:Math.floor((v*4+count*2+1)/(count*2-2))*2,a=[6];for(let p=n-7;a.length<count;p-=step)a.splice(1,0,p);for(let i=0;i<a.length;i++)for(let j=0;j<a.length;j++)if(!((i===0&&(j===0||j===a.length-1))||(j===0&&i===a.length-1))&&Math.abs(x-a[i])<=2&&Math.abs(y-a[j])<=2)return true;return false;}
function maskBit(m,x,y){return [(x+y)%2===0,y%2===0,x%3===0,(x+y)%3===0,(Math.floor(y/2)+Math.floor(x/3))%2===0,(x*y)%2+(x*y)%3===0,((x*y)%2+(x*y)%3)%2===0,((x+y)%2+(x*y)%3)%2===0][m];}
$('panView').onclick=()=>{pan=!pan;render();};
$('imageView').onclick=()=>{mode='image';render();};$('matrixView').onclick=()=>{mode='matrix';render();};
$('zoomIn').onclick=()=>{zoom=Math.min(8,zoom*1.5);render();};$('zoomOut').onclick=()=>{zoom=Math.max(Math.min(.01,zoom),zoom/1.5);render();};$('fitView').onclick=()=>{zoom=1;render();const box=viewScroll();box.scrollTop=box.scrollLeft=0;};
let viewportFrame=0;
new ResizeObserver(()=>{cancelAnimationFrame(viewportFrame);viewportFrame=requestAnimationFrame(render);}).observe($('canvasScroll'));
function position(e){const target=e.currentTarget||canvas,r=target.getBoundingClientRect();return {x:(e.clientX-r.left)*canvasCoordinates.width/r.width,y:(e.clientY-r.top)*canvasCoordinates.height/r.height};}
function imageCellAt(p,grid,n){
 if(!grid?.length)return {x:-1,y:-1};
 let best=-1,distance=Infinity;
 for(let i=0;i<grid.length;i++){const d=(grid[i][0]-p.x)**2+(grid[i][1]-p.y)**2;if(d<distance){distance=d;best=i;}}
 const neighbor=grid[best%n===n-1?best-1:best+1],pitch=Math.hypot(neighbor[0]-grid[best][0],neighbor[1]-grid[best][1]);
 return distance>pitch*pitch?{x:-1,y:-1}:{x:best%n,y:Math.floor(best/n)};
}
function cellAt(p){if(mode!=='image')return {x:Math.floor(p.x/12)-2,y:Math.floor(p.y/12)-2};return imageCellAt(p,imageDisplaySettings().grid,rowsOf(matrixNow()).length);}
let cornerSelection=null;canvas.tabIndex=0;
canvas.onpointerdown=e=>{if(e.button!==0||recoveryWorker.pending||pan)return;cornerSelection=null;if(cellGesture){cellGesture=null;selectedCells=[];render();return;}const p=position(e);if(mode==='image'&&tab==='input'&&state.settings.quad){const role=$('finderRole').value;if(role!==''){assignFinder(p,Number(role)).catch(report);return;}const q=state.settings.quad;const locked=new Set((state.settings.finders||[]).filter((_,i)=>i%3===0)),distances=[0,1,2,3].map(i=>locked.has(i)?Infinity:Math.hypot(q[2*i]-p.x,q[2*i+1]-p.y));const i=distances.indexOf(Math.min(...distances));if(distances[i]<canvasCoordinates.width/canvas.getBoundingClientRect().width*32){cornerSelection=i;canvas.focus({preventScroll:true});drag=i;dragStart={point:p,corner:[q[2*i],q[2*i+1]],changed:false,slop:(e.pointerType==='touch'?8:3)*canvasCoordinates.width/canvas.getBoundingClientRect().width};canvas.setPointerCapture(e.pointerId);}}else{const m=matrixNow();if(!m)return;const n=rowsOf(m).length,{x,y}=cellAt(p);if(x>=0&&y>=0&&x<n&&y<n){selected={x,y};selectedCells=[y*n+x];revealCellEvidence();if(tab==='input')setTab('edit');if(tab==='edit'&&$('cellTool').value!=='select'){cellGesture={start:{x,y},tool:$('cellTool').value,cells:new Set()};canvas.setPointerCapture(e.pointerId);updateGesture(x,y);}render();}}};
canvas.onpointermove=e=>{
  if(cellGesture){const p=position(e),{x,y}=cellAt(p);updateGesture(x,y);render();return;}
  if(drag===null||!dragStart)return;
  const p=position(e),dx=p.x-dragStart.point.x,dy=p.y-dragStart.point.y;
  if(!dragStart.changed&&Math.hypot(dx,dy)<=dragStart.slop)return;
  dragStart.changed=true;state.settings.gridReference=true;disableLm();state.settings.warp=null;state.settings.grid=null;
  state.settings.quad[drag*2]=Math.max(0,Math.min(canvasCoordinates.width-1,dragStart.corner[0]+dx));
  state.settings.quad[drag*2+1]=Math.max(0,Math.min(canvasCoordinates.height-1,dragStart.corner[1]+dy));render();
};
function finishCornerDrag(){const changed=dragStart?.changed;drag=null;dragStart=null;if(changed)sample().catch(report);else render();}
canvas.onpointerup=()=>{if(cellGesture){const tool=cellGesture.tool;cellGesture=null;if(tool!=='rect')editCell(tool.startsWith('brush0')?'0':tool.startsWith('brush1')?'1':'?').catch(report);}if(drag!==null)finishCornerDrag();};
canvas.onpointercancel=()=>{cellGesture=null;selectedCells=[];if(drag!==null)finishCornerDrag();else render();};

canvas.addEventListener('keydown',e=>{
 const delta={ArrowLeft:[-1,0],ArrowRight:[1,0],ArrowUp:[0,-1],ArrowDown:[0,1]}[e.key];
 if(!delta||e.altKey||e.ctrlKey||e.metaKey||cornerSelection==null||mode!=='image'||tab!=='input'||!state.settings.quad)return;
 e.preventDefault();if(recoveryWorker.pending||pan)return;
 if((state.settings.finders||[]).some((v,i)=>i%3===0&&v===cornerSelection))return;
 const q=state.settings.quad,n=17+4*state.settings.version;
 const distance=(a,b)=>Math.hypot(q[a*2]-q[b*2],q[a*2+1]-q[b*2+1]);
 const step=Math.max(.25,(distance(0,1)+distance(3,2)+distance(0,3)+distance(1,2))/(4*(n-1)))/4;
 const i=cornerSelection*2,x=Math.max(0,Math.min(canvasCoordinates.width-1,q[i]+delta[0]*step)),y=Math.max(0,Math.min(canvasCoordinates.height-1,q[i+1]+delta[1]*step));
 if(x===q[i]&&y===q[i+1])return;state.settings.gridReference=true;disableLm();state.settings.warp=null;state.settings.previewQuad=null;state.settings.grid=null;q[i]=x;q[i+1]=y;
 sample().catch(report);
});

function updateGesture(x,y){
 const n=rowsOf(state.matrix).length,g=cellGesture;
 if(x<0||y<0||x>=n||y>=n){g.last=null;return;}
 if(g.tool==='rect'){g.cells.clear();for(let yy=Math.min(y,g.start.y);yy<=Math.max(y,g.start.y);yy++)for(let xx=Math.min(x,g.start.x);xx<=Math.max(x,g.start.x);xx++)g.cells.add(yy*n+xx);}
 else{
  const stamp=g.tool.startsWith('stamp');if(stamp)g.cells.clear();
  const size=+(g.tool.match(/(3|5|7)$/)?.[1]||1),r=(size-1)/2,from=stamp?{x,y}:(g.last||{x,y});
  const steps=Math.max(Math.abs(x-from.x),Math.abs(y-from.y),1);
  for(let step=0;step<=steps;step++){const cx=Math.round(from.x+(x-from.x)*step/steps),cy=Math.round(from.y+(y-from.y)*step/steps);for(let yy=Math.max(0,cy-r);yy<=Math.min(n-1,cy+r);yy++)for(let xx=Math.max(0,cx-r);xx<=Math.min(n-1,cx+r);xx++)g.cells.add(yy*n+xx);}
 }
 g.last={x,y};selectedCells=[...g.cells];
}
$('cellTool').onchange=()=>{cellGesture=null;selectedCells=[];render();};
$('clearVisibleFinder').onclick=async()=>{disableLm();state.settings.visibleFinderRole=null;if(!state.settings.finders?.length)state.settings.assigned=false;syncSettings();if(state.image)await sample(false,{recordHistory:false}).catch(report);record('役割指定を解除');render();};
function finderPositions(settings){const last=13+4*settings.version,map=moduleMap({...settings,warp:null});return [[3,3],[last,3],[last,last],[3,last]].map(([column,row])=>map(column,row));}
async function assignFinder(p,role){
  if(state.settings.assigned&&!(state.settings.finders||[]).some((value,i)=>i%3===0&&value===role)){
    const n=17+4*state.settings.version,map=moduleMap(state.settings),expected=map(role===1?n-4:3,role===3?n-4:3),neighbor=map(role===1?n-3:4,role===3?n-4:3),pitch=Math.hypot(neighbor[0]-expected[0],neighbor[1]-expected[1]),tolerance=Math.max(pitch*4,36*canvasCoordinates.width/canvas.getBoundingClientRect().width);
    if(Math.hypot(p.x-expected[0],p.y-expected[1])>tolerance){$('finderRole').value='';toast(getLanguage()==='ja'?'指定した役割のFinder付近をタップしてください。':'Tap near the Finder for the selected role.');return;}
  }
  const savedSettings=clone(state.settings),savedMatrix=clone(state.matrix),savedSource=clone(state.source);let f;
  const restore=()=>{state.settings=savedSettings;state.matrix=savedMatrix;state.source=savedSource;restoreCurveChoices();syncSettings();render();};
  try{
  invalidateLm(true);state.settings.warp=null;state.settings.previewQuad=null;curve.candidates=[];if(!state.settings.assigned){const spots=finderPositions(state.settings),distances=spots.map(point=>Math.hypot(point[0]-p.x,point[1]-p.y)),nearest=distances.indexOf(Math.min(...distances)),shift=(nearest-role+4)%4;for(let k=0;k<(4-shift)%4;k++)transformMatrix(false,true);}state.settings.assigned=true;$('finderRole').value='';f=await call({action:'finder',settings:state.settings,...imageRequest(),x:p.x,y:p.y,alignGrid:true,role,gridAssigned:!!savedSettings.assigned,physicalGrid:!savedSettings.assigned&&state.settings.gridReference?savedSettings.quad:null},'Finder位置を精密探索');
  }catch(e){restore();throw e;}
  if(f.placementRejected){restore();toast(translate('指定済みFinderから推定される位置と離れすぎています。格子またはFinder役割を確認してください。'));return;}
  if(!f.confirmed){await assignRoleOnly(p,role,savedSettings,restore,f.outcome);return;}
  // A tap outside the Finder is not used as its center; cancelling restores the state before the tap.
  if(f.needsConfirmation&&!confirm(translate('タップ位置はFinderの外側です。近くで検出したFinderの中心を使いますか？'))){restore();return;}
  state.settings.gridReference=true;if(state.settings.visibleFinderRole===role)state.settings.visibleFinderRole=null;if(f.quad)state.settings.quad=f.quad;state.settings.confirmedPolarity=f.confirmedPolarity;const points=state.settings.finders||[];state.settings.finders=points.filter((_,i)=>points[Math.floor(i/3)*3]!==role).concat([role,f.x,f.y]);await revalidatePriority();syncSettings();await sample();toast('Finder位置を確認しました');}
// Without a detected Finder the tap only names a corner of the grid; it never moves the grid or adds a Finder.
async function assignRoleOnly(p,role,savedSettings,restore,outcome){
  const ja=getLanguage()==='ja',label=ja?['左上','右上',,'左下'][role]:['top left','top right',,'bottom left'][role],finders=savedSettings.finders||[];
  const failure=translate(outcome==='REJECTED'?'Finder候補は見つかりましたが、位置や模様の条件を満たさないため採用しませんでした。':'Finderを検出できませんでした。');
  let message=null;
  if(finders.some((v,i)=>i%3===0&&v===role))message=ja?`確定済みの${label}Finderは変更していません。`:`The confirmed ${label} Finder is unchanged.`;
  else if(finders.length)message=ja?'格子の向きは確定済みのFinderで決まっているため、変更していません。':'The grid orientation is set by the confirmed Finder and is unchanged.';
  else if(!savedSettings.assigned){
   const q=savedSettings.quad,map=moduleMap({...savedSettings,warp:null}),spots=finderPositions(savedSettings),neighbor=map(4,3),pitch=Math.hypot(neighbor[0]-spots[0][0],neighbor[1]-spots[0][1]),tolerance=Math.max(pitch*4,36*canvasCoordinates.width/canvas.getBoundingClientRect().width);
   if(!savedSettings.gridReference||!q)message=ja?'四隅を調整してから役割を指定してください。':'Adjust the corners, then assign the role.';
   else if(Math.min(...spots.map(s=>Math.hypot(s[0]-p.x,s[1]-p.y)))>tolerance)message=ja?'格子の角にあるFinderの位置をタップしてください。':'Tap the Finder position at a corner of the grid.';
  }
  if(message){restore();toast(`${failure} ${message}`);return;}
  // The orientation chosen before the search stays; the corners keep their positions.
  await revalidatePriority();state.settings.visibleFinderRole=role;syncSettings();await sample();
  toast(`${failure} `+(ja?`格子は動かさずに${label}の役割だけを指定しました。位置は四隅で調整してください。`:`Only the ${label} role was assigned and the grid was not moved. Adjust the corners to place it.`));
}
$('clearFinders').onclick=async()=>{invalidateLm(true);state.settings.warp=null;state.settings.previewQuad=null;curve.candidates=[];state.settings.finders=[];state.settings.visibleFinderRole=null;state.settings.confirmedPolarity='UNKNOWN';state.settings.assigned=false;syncSettings();if(state.image)await sample(false,{recordHistory:false}).catch(report);record('Finder指定を解除');render();};
function revealCellEvidence(){if(tab==='results'&&result?.candidates[candidateIndex]){$('resultEvidencePanel').open=true;$('resultInspection').querySelector('details').open=true;}}
function showCell(){if(!selected||!matrixNow()){ $('selectedCell').textContent='セルを選択してください';$('sourceSummary').textContent='';$('cellDetail').textContent='';$('resultCellDetail').textContent=t('inline:セルをタップ：セルの修復理由を表示');return;}const m=matrixNow(),rows=rowsOf(m),v=rows[selected.y]?.[selected.x];if(tab==='edit'&&!cellGesture){const cells=selectedCells.length?selectedCells:[selected.y*rows.length+selected.x],kinds=cells.map(i=>['1','6'].includes(m.sources[i])?'1':m.sources[i]==='5'?'5':'0'),unique=[...new Set(kinds)];$('editKind').value=unique.length===1?unique[0]:'mixed';$('editKind').querySelector('[value="1"]').disabled=cells.some(i=>['?','*'].includes(rows[Math.floor(i/rows.length)]?.[i%rows.length]));$('sourceSummary').textContent=cells.length===1?t('cell_source_preview',v,rowsOf(state.source)[selected.y]?.[selected.x]):t('cell_source_selection',cells.length,kinds.filter(k=>k==='0').length,kinds.filter(k=>k==='5').length,kinds.filter(k=>k==='1').length);}const overlay=tab==='edit'&&$('codewordOverlay').checked&&overlayFingerprint===state.matrix?.text?overlayCells?.[`${selected.x},${selected.y}`]:null;const kind=overlay?codewordCellLabel(overlay,getLanguage()==='ja'):t(regionKindsCache.get(state.settings.version)?.[selected.y*rows.length+selected.x]||'cell_region_codeword');$('selectedCell').textContent=getLanguage()==='ja'?`選択: (${selected.x},${selected.y}) / ${kind} / 値=${v}`:`Selected: (${selected.x},${selected.y}) / ${kind} / value=${v}`;const trace=tab==='results'?resultTrace(result?.candidates[candidateIndex],selected.x,selected.y):null;$('cellDetail').textContent=trace?`${trace.title}\n${trace.detail}`:'';if(tab==='results'){const role=trace?.formatRole,label=translate(role==='ECC'?'ECCレベルのセルを表示':role==='MASK'?'マスク番号のセルを表示':role==='PARITY'?'形式情報の訂正ビットを表示':'同じコード語を表示');const text=[...$('sameCodeword').parentElement.childNodes].find(n=>n.nodeType===Node.TEXT_NODE);if(text)text.nodeValue=label;}$('resultCellDetail').textContent=tab==='results'&&result?.candidates[candidateIndex]?resultBasicCellText(result.candidates[candidateIndex],selected.x,selected.y,trace)+($('calculationDetails').checked?'\n'+technicalCellText(result.candidates[candidateIndex],selected.x,selected.y,trace):''):t('inline:セルをタップ：セルの修復理由を表示');}
async function editCell(value,restore=false){if(!selected||!state.matrix||recoveryWorker.pending)return;const n=rowsOf(state.matrix).length,cells=selectedCells.length?selectedCells:[selected.y*n+selected.x];const m=await call({action:'edit',matrix:state.matrix,source:state.source,settings:state.settings,cells,value:restore?'source':value,kind:restore?'0':$('editKind').value},'セル編集');if(m.text===state.matrix.text&&m.sources===state.matrix.sources){render();return;}state.matrix=m;record(restore?'セルの読み取りを復元':`${cells.length}セル → ${value}`);render();}
document.querySelectorAll('[data-value]').forEach(b=>b.onclick=()=>editCell(b.dataset.value).catch(report));$('restoreCell').onclick=()=>editCell(null,true).catch(report);
$('editKind').onchange=()=>editCell('source').catch(report);
document.querySelectorAll('[data-move]').forEach(b=>b.onclick=()=>{if(!selected||!state.matrix)return;const [dx,dy]=b.dataset.move.split(',').map(Number),n=rowsOf(state.matrix).length;selected={x:Math.max(0,Math.min(n-1,selected.x+dx)),y:Math.max(0,Math.min(n-1,selected.y+dy))};selectedCells=[selected.y*n+selected.x];render();});
function transformMatrix(mirror,preserveLm=false){if(!state.matrix)return;state.settings.visibleFinderRole=mirror?(state.settings.visibleFinderRole===1?3:state.settings.visibleFinderRole===3?1:state.settings.visibleFinderRole):null;if(preserveLm)invalidateLm(true);else disableLm();if(mirror)state.settings.finders=(state.settings.finders||[]).map((value,i)=>i%3===0?(value===1?3:value===3?1:value):value);else if(state.settings.finders?.length){state.settings.finders=[];state.settings.confirmedPolarity='UNKNOWN';toast(getLanguage()==='ja'?'向きを変更したためFinder指定を解除しました。位置と役割を確認してください。':'Finder assignments were cleared after the orientation change. Check the positions and roles.');}state.settings.warp=null;const n=rowsOf(state.matrix).length;if(state.settings.grid){const old=state.settings.grid,next=Array(n*n);for(let y=0;y<n;y++)for(let x=0;x<n;x++){const nx=mirror?y:n-1-y,ny=x;next[ny*n+nx]=old[y*n+x];}state.settings.grid=next;}for(const key of ['matrix','source']){const m=state[key];if(!m)continue;const rows=rowsOf(m),out=Array.from({length:n},()=>Array(n)),sources=Array(n*n),margins=Array(n*n),confidences=Array(n*n);for(let y=0;y<n;y++)for(let x=0;x<n;x++){const nx=mirror?y:n-1-y,ny=x;out[ny][nx]=rows[y][x];sources[ny*n+nx]=m.sources[y*n+x];margins[ny*n+nx]=m.margins?.[y*n+x]??-1;confidences[ny*n+nx]=m.confidences?.[y*n+x]??(rows[y][x]==='?'?0:1);}state[key]={text:out.map(r=>r.join('')).join('\n'),sources:sources.join(''),margins,confidences};}if(state.settings.quad){const q=state.settings.quad;state.settings.quad=(mirror?[0,3,2,1]:[3,0,1,2]).flatMap(i=>[q[i*2],q[i*2+1]]);}selected=null;selectedCells=[];result=null;renderResult();}
$('rotate').onclick=async()=>{transformMatrix(false);state.settings.orientation=(state.settings.orientation+1)%4;await revalidatePriority();syncSettings();record('90°回転');render();if(state.image&&state.settings.curved)await sample();};$('mirror').onclick=async()=>{transformMatrix(true);await revalidatePriority();record('鏡像');render();if(state.image&&state.settings.curved)await sample();};
async function newMatrix(m,name='TXT行列',automatic=false){
  validateMatrix(m);await saveChain;state={id:crypto.randomUUID(),name,nameKey:name,matrix:m,source:clone(m),settings:{...defaults,photographicSource:false,automatic:false,black:64,white:192,quietZone:false,version:(rowsOf(m).length-17)/4},history:[],cursor:-1,image:null,imageHash:null};imageElement=null;selected=null;selectedCells=[];zoom=1;result=null;mode='matrix';if(m.quarterTurns!=null){for(let i=0;i<(4-m.quarterTurns)%4;i++)transformMatrix(false);}state.settings.assigned=m.assigned??true;state.source.text=state.source.text.replace(/\*/g,'?');const symbols=state.matrix.text.replace(/\n/g,'');state.matrix.sources=state.matrix.sources.split('').map((c,i)=>symbols[i]==='*'?'5':c).join('');await revalidatePriority();record('行列を読み込み');syncSettings();renderResult();setTab('input');$('fitView').onclick();if(automatic)await runAutomatic();
}
$('demo').onclick=async()=>{try{const m=await call({action:'demo'},'サンプルを準備');await newMatrix(m,'損傷サンプル');toast('灰色のセルが損傷箇所です。「修復する」で試せます。');}catch(e){report(e);}};
$('emptyImport').onclick=$('imageImport').onclick=()=>$('imageFile').click();$('cameraImport').onclick=()=>$('cameraFile').click();$('textImport').onclick=()=>$('textFile').click();$('sessionImport').onclick=()=>$('sessionFile').click();
$('pasteImport').onclick=async()=>{try{await newMatrix(await call({action:'parse',text:$('matrixInput').value,enforcePriorityLimit:!$('automaticRepair').checked},'行列を確認'),'TXT行列',$('automaticRepair').checked);$('matrixInput').value='';}catch(e){report(e);}};
$('textFile').onchange=async e=>{const f=e.target.files[0];e.target.value='';if(!f)return;try{if(f.size>65536)throw new Error('テキスト行列は64KB以下にしてください');await newMatrix(await call({action:'parse',text:await f.text(),enforcePriorityLimit:!$('automaticRepair').checked},'行列を確認'),'TXT行列',$('automaticRepair').checked);}catch(e){report(e);}};
async function loadImageElement(blob){const url=URL.createObjectURL(blob);try{const img=new Image();img.src=url;await img.decode();return img;}finally{URL.revokeObjectURL(url);}}
$('matrixToImage').onclick=async()=>{
  if(recoveryWorker.pending||!state.matrix||state.image||state.imageOnly)return;
  const previous=state,previousCursor=state.cursor,source=clone(state.source||state.matrix),matrix=clone(state.matrix);
  try{
    const geometry=await call({action:'textImageGeometry',matrix:source},'行列を画像として準備');
    const rows=rowsOf(source),n=rows.length,c=document.createElement('canvas');c.width=c.height=(n+8)*8;
    const cx=c.getContext('2d');cx.fillStyle='#fff';cx.fillRect(0,0,c.width,c.height);
    for(let y=0;y<n;y++)for(let x=0;x<n;x++){cx.fillStyle=rows[y][x]==='1'?'#000':rows[y][x]==='0'?'#fff':'#808080';cx.fillRect((x+4)*8,(y+4)*8,8,8);}
    const blob=await new Promise(resolve=>c.toBlob(resolve,'image/png'));if(!blob)throw new Error('画像の変換に失敗しました');
    const img=await loadImageElement(blob),hash=await digest(await blob.arrayBuffer());
    if(state!==previous||state.cursor!==previousCursor)return;
    // Images belong to a workspace, not a history entry. A new workspace keeps
    // every original matrix snapshot meaningful and available for resumption.
    await saveChain;await saveSession(serializable());
    if(state!==previous||state.cursor!==previousCursor)return;
    const generatedName=previous.nameKey===previous.name?`${previous.name} · 画像調整`:null;
    state={id:crypto.randomUUID(),name:generatedName||`${previous.name.slice(0,180)} · ${getLanguage()==='ja'?'画像調整':'Image adjustments'}`,nameKey:generatedName,matrix,source,
      settings:{...clone(previous.settings),photographicSource:false,quad:null,finders:[],visibleFinderRole:null,assigned:false,orientation:0,grid:null,warp:null,previewQuad:null,
        imageOps:[],brightness:0,contrast:0,toneInvert:false,automatic:false,black:64,white:192,provisionalBlack:64,provisionalWhite:192,
        polarity:'NORMAL',confirmedPolarity:'UNKNOWN',polarityTone:[0,0,false],quietZone:false,curved:false,neighborhood:false,
        lmEnabled:false,lmBaseQuad:null,lmAppliedQuad:null,lmFailed:false,colorUnknown:false,colorClusters:[]},
      history:[],cursor:-1,image:blob,imageHash:hash};
    imageElement=img;imagePixelsCache=null;overlayFingerprint=0;curve.candidates=[];curve.failed=false;curve.memoryLimited=false;
    result=null;selected=null;selectedCells=[];zoom=1;mode='image';
    for(let i=0;i<(4-geometry.quarterTurns)%4;i++)transformMatrix(false);
    Object.assign(state.settings,{quad:geometry.quad,finders:geometry.finders,assigned:geometry.assigned,confirmedPolarity:geometry.assigned?'NORMAL':'UNKNOWN'});
    await revalidatePriority();syncSettings();renderResult();setTab('input');await sample();$('fitView').onclick();
    toast(getLanguage()==='ja'?'疑似画像の作業を作成しました。元の行列作業は作業一覧に保存されています。':'Created an image workspace. The original matrix workspace remains in Workspaces.');
  }catch(e){
    if(state!==previous){state=previous;imageElement=null;imagePixelsCache=null;mode='matrix';result=null;syncSettings();renderResult();setTab('input');renderHistory();}
    report(e);
  }
};
// Decode like the Android app: halve until the longest side is at most 2600 pixels.
function boundedSource(img){const w=img.naturalWidth,h=img.naturalHeight;let sample=1;while(Math.max(w,h)/sample>2600)sample*=2;if(sample===1)return img;const c=document.createElement('canvas');c.width=Math.max(1,Math.floor(w/sample));c.height=Math.max(1,Math.floor(h/sample));c.getContext('2d').drawImage(img,0,0,c.width,c.height);return c;}
// Wait for a preceding engine task, such as grid detection for the previous image, before locating.
async function locateSymbol(canvas){while(recoveryWorker.pending)await recoveryWorker.pending.promise.catch(()=>{});const rgba=canvas.getContext('2d').getImageData(0,0,canvas.width,canvas.height).data,pixels=new Uint32Array(canvas.width*canvas.height);for(let i=0;i<pixels.length;i++)pixels[i]=(rgba[i*4]<<16)|(rgba[i*4+1]<<8)|rgba[i*4+2];return call({action:'symbolExtent',width:canvas.width,height:canvas.height,pixels});}
async function importImage(file,initial={}){
  if(!file)return;if(file.size>32*1024*1024)throw new Error('画像は32MB以下にしてください');
  const original=await boundedSource(await loadImageElement(file));const cropped=await cropImage(original,{...initial,locate:locateSymbol});if(!cropped)return;const blob=cropped.blob;if(!blob)throw new Error('画像の変換に失敗しました');const img=await loadImageElement(blob),c={width:img.naturalWidth,height:img.naturalHeight};
  await saveChain;overlayFingerprint=0;curve.candidates=[];curve.failed=false;curve.memoryLimited=false;state={id:crypto.randomUUID(),name:'画像からの修復',nameKey:'画像からの修復',matrix:null,source:null,settings:{...defaults,photographicSource:initial.photographicSource??true,brightness:cropped.brightness,contrast:cropped.contrast,toneInvert:cropped.toneInvert,curved:false,assigned:false,quad:[c.width*.1,c.height*.1,c.width*.9,c.height*.1,c.width*.9,c.height*.9,c.width*.1,c.height*.9]},history:[],cursor:-1,image:blob,imageHash:await digest(await blob.arrayBuffer())};
  imageElement=await loadImageElement(blob);result=null;selected=null;selectedCells=[];zoom=1;mode='image';syncSettings();renderResult();setTab('input');
  imagePixelsCache=null;if($('automaticRepair').checked)await runAutomatic();else await detect();$('fitView').onclick();
}
for(const id of ['imageFile','cameraFile'])$(id).onchange=e=>{const f=e.target.files[0];e.target.value='';importImage(f).catch(report);};
function imageRequest(settings=state.settings){if(!imageElement)throw new Error('画像を読み込んでください');const cacheKey=state.id+':'+settings.brightness+':'+settings.contrast+':'+settings.toneInvert+':'+JSON.stringify(settings.imageOps);if(imagePixelsCache?.key===cacheKey)return imagePixelsCache.request;const c=document.createElement('canvas');const prepared=prepareImage(imageElement,settings.imageOps);c.width=prepared.naturalWidth||prepared.width;c.height=prepared.naturalHeight||prepared.height;const cx=c.getContext('2d');cx.drawImage(prepared,0,0);const rgba=cx.getImageData(0,0,c.width,c.height).data,pixels=new Uint32Array(c.width*c.height),originalPixels=new Uint32Array(c.width*c.height),s=settings;for(let i=0;i<pixels.length;i++){originalPixels[i]=(rgba[i*4]<<16)|(rgba[i*4+1]<<8)|rgba[i*4+2];const gain=2**(s.contrast/50),shift=127.5*(1-gain)+s.brightness*2.55,scale=Math.fround(s.toneInvert?-gain:gain),offset=Math.fround(s.toneInvert?255-shift:shift),channel=j=>Math.max(0,Math.min(255,Math.round(Math.fround(Math.fround(rgba[i*4+j]*scale)+offset))));const rgb=(channel(0)<<16)|(channel(1)<<8)|channel(2);pixels[i]=rgb;}for(let i=0;i<pixels.length;i++){rgba[i*4]=pixels[i]>>>16;rgba[i*4+1]=(pixels[i]>>>8)&255;rgba[i*4+2]=pixels[i]&255;rgba[i*4+3]=255;}cx.putImageData(new ImageData(rgba,c.width,c.height),0,0);imagePreview=c;const request={width:c.width,height:c.height,pixels,originalPixels};imagePixelsCache={key:cacheKey,request};return request;}
async function detect({recordHistory=true}={}){
 const previous={settings:clone(state.settings),matrix:clone(state.matrix),source:clone(state.source),imageOnly:state.imageOnly};
 const n=previous.matrix?rowsOf(previous.matrix).length:0,map=previous.settings.quad?moduleMap(previous.settings):null;
 const editGrid=previous.settings.grid||(map?Array.from({length:n*n},(_,i)=>map(i%n,Math.floor(i/n))):[]);
 try{
  const detection=await call({action:'detect',...imageRequest()},'格子を推定');
  state.settings.lmBaseQuad=null;state.settings.lmAppliedQuad=null;state.settings.lmFailed=false;
  state.settings.warp=null;state.settings.previewQuad=null;state.settings.grid=null;state.settings.finders=[];state.settings.visibleFinderRole=null;
  state.settings.colorClusters=detection.colorClusters||[];state.settings.provisionalBlack=detection.black??96;state.settings.provisionalWhite=detection.white??160;
  if(detection.quad)state.settings.gridReference=true;
  if(detection.quad){
   state.settings.quad=detection.quad;state.settings.version=detection.version;state.settings.assigned=detection.assigned;state.settings.orientation=0;
   state.settings.finders=detection.autoAssignedFinders||[];state.settings.polarity=detection.polarity;state.settings.confirmedPolarity=detection.confirmedPolarity;
   state.settings.polarityTone=[state.settings.brightness,state.settings.contrast,state.settings.toneInvert];
  }
  syncSettings();await sample(false,{recordHistory:false,editGrid});
  selected=null;selectedCells=[];restoreCurveChoices();
  if(recordHistory)record('画像からセルを読み取り');
  mode='image';render();
  toast(detection.quad?(detection.assigned?'格子と向きを推定しました。確認してください。':'格子を推定しました。Finderの向きを確認してください。'):'格子を自動推定できませんでした。四隅とバージョンを手動で指定してください。');
 }catch(error){Object.assign(state,previous);restoreCurveChoices();syncSettings();render();throw error;}
}
function preserveManualEdits(previous,matrix,editGrid,grid,allowed){
 if(!previous||(!editGrid&&previous.sources.length!==matrix.sources.length))return;
 const values=matrix.text.replace(/\n/g,'').split(''),old=previous.text.replace(/\n/g,''),sources=matrix.sources.split(''),n=rowsOf(matrix).length,used=new Set();
 for(let i=0;i<previous.sources.length;i++){
  if(!['1','5','6'].includes(previous.sources[i]))continue;
  let to=i;
  if(editGrid){
   const point=editGrid[i];let distance=Infinity;to=-1;
   if(point)for(let j=0;j<grid.length;j++){const d=Math.hypot(point[0]-grid[j][0],point[1]-grid[j][1]);if(d<distance){distance=d;to=j;}}
   const neighbors=to<0?[]:[to%n>0?to-1:to+1,to>=n?to-n:to+n];
   const pitch=neighbors.length?Math.min(...neighbors.map(j=>Math.hypot(grid[to][0]-grid[j][0],grid[to][1]-grid[j][1]))):0;
   // Do not silently move an edit to a different module or merge two edits.
   if(to<0||distance>pitch*.5||used.has(to)||(old[i]==='*'&&(!state.settings.assigned||!allowed.has(to))))throw new Error(getLanguage()==='ja'?'編集セルの位置を新しい格子に対応付けられないため、元の格子と編集を保持しました。':'The edited cells could not be matched to the new grid. The original grid and edits were kept.');
  }
  used.add(to);values[to]=old[i];sources[to]=previous.sources[i];matrix.margins[to]=-1;
  if(matrix.confidences)matrix.confidences[to]=previous.confidences?.[i]??0;
 }
 matrix.text=Array.from({length:n},(_,y)=>values.slice(y*n,(y+1)*n).join('')).join('\n');matrix.sources=sources.join('');
}
$('detect').onclick=()=>detect().catch(report);
async function sample(refine=false,{recordHistory=true,editGrid=null}={}){state.settings=readSettings();if(state.settings.curved&&!state.settings.assigned){state.settings.warp=null;state.settings.previewQuad=null;state.settings.grid=null;curve.candidates=[];curve.failed=false;curve.memoryLimited=false;}refine=refine||(!!state.settings.lmEnabled&&state.settings.assigned&&!state.settings.lmAppliedQuad&&!state.settings.lmFailed&&!state.settings.warp);if(refine){state.settings.lmBaseQuad=clone(quadWithoutLm());state.settings.quad=clone(state.settings.lmBaseQuad);}const res=await call({action:refine?'refine':'sample',settings:state.settings,refreshPolarity:JSON.stringify(state.settings.polarityTone)!==JSON.stringify([state.settings.brightness,state.settings.contrast,state.settings.toneInvert]),...imageRequest()},refine?'LM格子補正':'セルを読み取り');state.settings.grid=res.grid;state.settings.confirmedPolarity=res.confirmedPolarity;if(res.provisionalBlack!=null){state.settings.provisionalBlack=res.provisionalBlack;state.settings.provisionalWhite=res.provisionalWhite;}state.settings.polarityTone=[state.settings.brightness,state.settings.contrast,state.settings.toneInvert];state.imageOnly=false;const previous=state.matrix;state.source=clone(res.matrix);state.matrix=res.matrix;const allowed=await regions();preserveManualEdits(previous,state.matrix,editGrid,res.grid,allowed);if(refine){state.settings.warp=null;state.settings.quad=res.quad;state.settings.lmAppliedQuad=clone(res.quad);state.settings.lmFailed=!res.refined;toast(res.refined?'格子を補正しました':'有効な補正が見つからないため元の格子を維持します');}if(res.black!=null&&state.settings.automatic){state.settings.black=res.black;state.settings.white=res.white;}syncSettings();if(recordHistory)record(refine?'LM格子補正':'画像からセルを読み取り');render();if(state.settings.curved&&state.settings.assigned&&!state.settings.warp&&!curve.inProgress)await curve({recordHistory});}
$('refine').onchange=async()=>{const enabled=$('refine').checked;if(!enabled)invalidateLm(true);state.settings.lmEnabled=enabled;state.settings.lmFailed=false;if(state.settings.lmEnabled&&state.settings.curved&&state.settings.warp){const i=Number($('curveChoice').value)||0;if(curve.candidates[i])curve.candidates[i].refined=false;await $('curveChoice').onchange();return;}if(state.settings.lmEnabled){state.settings.lmAppliedQuad=null;state.settings.lmBaseQuad=clone(state.settings.quad);}else{state.settings.grid=null;state.settings.warp=null;state.settings.previewQuad=null;curve.candidates=[];}await sample().catch(report);};
$('recover').onclick=async()=>{try{if(!state.matrix)return;state.oneTimeSingleSegment=false;state.automaticTrial=null;state.automatic=null;state.settings=readSettings();if(state.imageOnly)throw new Error('QRRの元画像を追加してください');if(!state.settings.assigned)throw new Error('Finderの向きを確認してから修復してください');record('修復を開始');if(result)result.needsInspectionRestore=true;const res=await call({action:state.image?'recoverImage':'recover',...(state.image?imageRequest():{}),matrix:state.matrix,settings:{...state.settings,single:state.settings.single||!!state.oneTimeSingleSegment}},'修復を実行中');result=res;result.input=clone(state.matrix);result.settings=clone(state.settings);result.fingerprint=JSON.stringify([state.matrix,state.settings,!!state.oneTimeSingleSegment]);candidateIndex=0;$('resultStage').value='after';setResultView('content');record('修復結果');selected=null;renderResult();setTab('results');$('fitView').onclick();document.querySelector('.steps').scrollIntoView({block:'start'});offerSingleRetry();}catch(e){report(e);}};
function renderResultStatus(){$('resultStatus').textContent=[resultIsStale()?translate('前回実行した修復結果です。編集中の変更はまだ反映されていません。'):'',result?.summary||''].filter(Boolean).join('\n\n');}
function renderResult(){syncResultDataDisplay();renderAutomaticStatus();renderNextResultAction();clearTailAnalysis();const candidates=result?.candidates||[];$('resultCount').textContent=String(candidates.length);renderResultStatus();if($('candidateDialog').open)renderCandidatePage();$('candidatePicker').replaceChildren(...candidates.map((c,i)=>new Option(`${i+1} / ${candidates.length} · V${c.version} / ${c.ecc} / mask ${c.mask}`,String(i))));$('candidatePicker').value=String(candidateIndex);const c=candidates[candidateIndex];$('resultDetail').hidden=!c;$('explanation').parentElement.hidden=!c;if(!c){$('payload').value='';$('methods').textContent='';$('inputAgreement').replaceChildren();$('hex').textContent='';$('copyPayload').disabled=true;$('exportPng').disabled=true;}$('retrySingle').hidden=!result?.retryAvailable;if(!c&&result){setResultView('diagnostic');$('resultStage').value='before';}for(const option of $('resultStage').options)option.disabled=!c&&option.value!=='before';if(c){$('payload').value=c.text??'';$('copyPayload').disabled=c.text==null||resultIsStale();$('exportPng').disabled=resultIsStale();$('methods').textContent=c.methods;$('hex').textContent='';$('inputAgreement').replaceChildren(...[c.formatAgreement,c.dataAgreement].map(text=>{const p=document.createElement('p');p.dataset.userContent='';p.textContent=text;return p;}));$('metrics').replaceChildren(...[[c.corrected,'訂正コード語'],[c.filled,'元の消失コード語']].map(([v,l])=>{const d=document.createElement('div');d.className='metric';const b=document.createElement('b');b.textContent=String(v);d.append(b,document.createTextNode(l));return d;}));}for(const [id,text] of [['contentBefore',c?.contentPreview?.before],['contentAfter',c?.contentPreview?.after],['explanation',c?.explanation]]){const element=$(id);element.textContent=text||'';element.hidden=!text;}renderComparison();if($('resultDataPanel').open)queueMicrotask(updateResultData);renderDiagnostic($('diagnostic'),result?.diagnosticReport,result?[result.diagnostic,result.failure].filter(Boolean).join('\n\n'):translate('未実行'),{appendix:result?.failure||''});$('tailAnalysis').hidden=!result?.tailAvailable;if($('tailAnalysis').open)queueMicrotask(updateTailAnalysis);}
$('unmask').onchange=render;
$('copyPayload').onclick=async()=>{try{if(resultIsStale()||result?.candidates[candidateIndex]?.text==null)return;await navigator.clipboard.writeText(result.candidates[candidateIndex].text);toast('全文をコピーしました');}catch{ $('payload').focus();$('payload').select();toast('コピーできませんでした。選択した本文を手動でコピーしてください。');}};
async function download(blob,name,share=false){const file=new File([blob],name,{type:blob.type});if(share&&navigator.canShare?.({files:[file]})){try{await navigator.share({files:[file],title:'QRevy'});return;}catch(e){if(e.name==='AbortError')return;}}const url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download=name;document.body.append(a);a.click();a.remove();setTimeout(()=>URL.revokeObjectURL(url),60000);}
$('exportText').onclick=()=>state.matrix&&download(new Blob([state.matrix.text+'\n'],{type:'text/plain'}),'qrevy-matrix.txt').catch(report);
$('exportPng').onclick=async()=>{try{if(resultIsStale())return;const c=result?.candidates[candidateIndex];if(!c)return;const rows=rowsOf(c.matrix),n=rows.length,unit=12,out=document.createElement('canvas');out.width=out.height=(n+8)*unit;const cx=out.getContext('2d');cx.fillStyle='#fff';cx.fillRect(0,0,out.width,out.height);cx.fillStyle='#000';for(let y=0;y<n;y++)for(let x=0;x<n;x++)if(rows[y][x]==='1')cx.fillRect((x+4)*unit,(y+4)*unit,unit,unit);const blob=await new Promise(r=>out.toBlob(r,'image/png'));await download(blob,'qrevy-repaired.png',true);}catch(e){report(e);}};
$('exportSession').onclick=async()=>{try{if(!state.matrix)throw new Error('作業を読み込んでください');await download(await exportWorkspace(serializable()),'qrevy-workspace.qrpwa',true);}catch(e){report(e);}};
async function openSession(s){
 validateSession(s);
 if(needsLegacyImageMigration(s)){
  // Re-read the retained QRR to distinguish unchanged imports from PWA edits.
  // If that metadata is unavailable, preserve the existing PWA interpretation.
  const original=await call({action:'qrrDecode',raw:s.qrrRaw,version:s.qrrVersion},'QRRを検証').catch(()=>null);
  migrateLegacyImages(s,original?.history);
 }
 overlayFingerprint=0;curve.candidates=[];curve.failed=false;curve.memoryLimited=false;if(s.image&&await digest(await s.image.arrayBuffer())!==s.imageHash)throw new Error('保存した画像の整合性チェックに失敗しました');const img=s.image?await loadImageElement(s.image):null;const h=s.history[s.cursor];clearAnalysis();state={...s};restoreHistoryEntry(state,s.cursor,defaults);restoreCurveChoices();imageElement=img;imagePixelsCache=null;result=null;selected=null;selectedCells=[];zoom=1;mode='matrix';await regions();syncSettings();renderResult();renderHistory();setTab('input');$('sessionsDialog').close();toast(h.imageOnly?'画像のみの履歴です。QRRの元画像を追加してください。':'作業を再開しました。');if(state.settings.curved&&imageElement&&!curve.candidates.length)await curve({restore:true});await restoreSelection(h.selection);$('fitView').onclick();}
$('sessionFile').onchange=async e=>{const f=e.target.files[0];e.target.value='';if(!f)return;try{const s=f.name.toLowerCase().endsWith('.qrr')?await call({action:'qrrDecode',...await unpackQrr(f)},'QRRを検証'):await importWorkspace(f);await saveChain;await openSession({...s,id:crypto.randomUUID()});autoSave();}catch(e){report(e);}};
async function showSessions(){try{await saveChain;const sessions=await listSessions();$('sessionList').replaceChildren(...sessions.map(s=>{const div=document.createElement('div');div.className='session-item';const open=document.createElement('button');const name=document.createElement('span');name.dataset.userContent='';name.textContent=workspaceName(s);open.append(name);const small=document.createElement('small');small.textContent=new Date(s.updated).toLocaleString(getLanguage()==='ja'?'ja-JP':'en-GB');open.append(small);open.onclick=()=>openSession(s).catch(report);const del=document.createElement('button');del.textContent='削除';del.onclick=async()=>{try{if(confirm(t('delete_recent_work_message'))){await deleteSavedSessions(s.id);await showSessions();}}catch(e){report(e);}};div.append(open,del);return div;}));if(!sessions.length)$('sessionList').textContent='保存した作業はまだありません。';if(!$('sessionsDialog').open)$('sessionsDialog').showModal();}catch(e){report(e);}}
$('sessionsButton').onclick=showSessions;$('helpButton').onclick=()=>$('helpDialog').showModal();document.querySelectorAll('[data-close]').forEach(b=>b.onclick=()=>$(b.dataset.close).close());
$('dismissInstall').onclick=()=>{$('install').hidden=true;try{localStorage.setItem('qrevy-install-dismissed','1');}catch{}};
try{if(/iPhone|iPad/.test(navigator.userAgent)&&!navigator.standalone&&!localStorage.getItem('qrevy-install-dismissed'))$('install').hidden=false;}catch{}
if('serviceWorker' in navigator)navigator.serviceWorker.register('./sw.js').then(reg=>{
  const ready=()=>{$('offline').textContent='オフライン準備完了';};if(reg.active)ready();reg.addEventListener('updatefound',()=>{reg.installing?.addEventListener('statechange',()=>{if(reg.waiting)toast('新しい版を準備しました。作業を保存してアプリを閉じ、開き直してください。');});});navigator.serviceWorker.ready.then(ready);
}).catch(e=>{$('offline').textContent='オフライン準備未完了';console.error(e);});
window.addEventListener('pagehide',()=>{if(state.matrix)autoSave();});

$('exportQrr').onclick=async()=>{try{if(!state.matrix)throw new Error('作業を読み込んでください');const raw=await call({action:'qrrEncode',id:state.id,cursor:state.cursor,history:JSON.stringify(state.history.map(h=>({...h,settings:settingsForQrr(imageElement,h.settings),detail:historyDetail(h)}))),qrrRaw:state.qrrRaw,qrrVersion:state.qrrVersion},'QRRを書き出し');await download(await packQrr(raw),'qrevy-session.qrr',true);}catch(e){report(e);}};

function updateCurveStatus(){const i=Number($('curveChoice').value)||0,total=curve.candidates?.length||0;const text=!state.settings.assigned?translate('曲面補正: QRの向きを指定してください'):curve.failed?translate(state.settings.warp?'再計算に失敗: 保存した補正を使用':'補正計算に失敗: 直線格子を使用'):total?(getLanguage()==='ja'?`曲面補正: 候補 ${i+1}/${total}`:`Distortion: candidate ${i+1}/${total}`):translate('補正点を検出できません: 直線格子を使用');$('curveStatus').textContent=text+(curve.memoryLimited?t('inline:\nメモリ上限により補正探索の一部を省略しました'):'');$('curveStatus').hidden=!state.settings.curved;$('curveChoice').hidden=!state.settings.curved||!total;$('curveChoice').disabled=!!recoveryWorker.pending||!state.settings.assigned||total<2;}
async function curve({restore=false,recordHistory=true}={}){if(curve.inProgress)return;if(!state.settings.assigned){updateCurveStatus();return;}curve.inProgress=true;try{const retained=state.settings.warp?{quad:clone(state.settings.previewQuad||state.settings.quad),warp:clone(state.settings.warp),grid:clone(state.settings.grid)}:null;const r=await call({action:'curve',settings:state.settings,...imageRequest()},'曲面の補正点を探索');curve.failed=false;curve.memoryLimited=r.memoryLimited;if(retained&&!r.candidates.some(c=>JSON.stringify([c.quad,c.warp])===JSON.stringify([retained.quad,retained.warp])))r.candidates.push(retained);curve.candidates=r.candidates;$('curveChoice').replaceChildren(...r.candidates.map((c,i)=>new Option(getLanguage()==='ja'?`補正候補 ${i+1}`:`Correction candidate ${i+1}`,String(i))));if(!r.candidates.length){state.settings.warp=null;updateCurveStatus();await sample(false,{recordHistory});return;}const index=retained?Math.max(0,r.candidates.findIndex(c=>JSON.stringify([c.quad,c.warp])===JSON.stringify([retained.quad,retained.warp]))):0;$('curveChoice').value=String(index);const c=r.candidates[index];Object.assign(state.settings,{previewQuad:c.quad,warp:c.warp,grid:c.grid});if(!restore)await sample(false,{recordHistory});saveCurveCache();updateCurveStatus();}catch(e){curve.failed=true;updateCurveStatus();if(!recordHistory)throw e;}finally{curve.inProgress=false;}}
$('curveChoice').onchange=async()=>{if(!state.settings.curved)return;const i=Number($('curveChoice').value),c=curve.candidates?.[i];if(!c)return;Object.assign(state.settings,{previewQuad:c.quad,warp:c.warp,grid:c.grid});try{if(state.settings.lmEnabled&&!c.refined){const refined=await call({action:'refineCurve',settings:state.settings,...imageRequest()},'曲面格子を補正');curve.candidates[i]={...c,...refined};Object.assign(state.settings,{previewQuad:refined.quad,warp:refined.warp,grid:refined.grid});}await sample();saveCurveCache();updateCurveStatus();}catch(e){report(e);}};

$('sameCodeword').onchange=render;
let tailInspectionRevision=0;
async function updateTailAnalysis(){
 const revision=++tailInspectionRevision;
 try{
  while(recoveryWorker.pending)await recoveryWorker.pending.promise.catch(()=>{});
  if(revision!==tailInspectionRevision||tailData||tab!=='results'||!result?.tailAvailable||!$('tailAnalysis').open||!$('resultDiagnosticSection').open)return;
  const saved=result,index=candidateIndex,c=saved.candidates[index];
  const data=await inspectCall({action:'tail',matrix:resultInput(),candidate:c,ecc:c?.ecc||saved.settings.ecc,mask:c?.mask??saved.settings.mask},'終端後の追加セグメントを解析');
  if(revision!==tailInspectionRevision||saved!==result||index!==candidateIndex||!$('tailAnalysis').open)return;
  tailData=data;$('tailControls').hidden=false;$('tailSource').replaceChildren(...tailData.views.map((v,i)=>new Option(v.label,String(i))));$('tailSource').value=String(tailData.initial);renderTail(true);
 }catch(e){report(e);}
}
$('tailAnalysis').ontoggle=()=>{if($('tailAnalysis').open)updateTailAnalysis();};
function renderTail(reset=false){if(!tailData)return;const v=tailData.views[Number($('tailSource').value)];if(reset)$('tailInterpretation').replaceChildren(...v.reports.map((r,i)=>new Option(r.label,String(i))));$('tailInterpretation').hidden=v.reports.length<2;const r=v.reports[Number($('tailInterpretation').value)||0];$('tailReport').textContent=tailData.notice+'\n\n'+r.text;$('tailRawPanel').hidden=!r.tail;$('tailOffset').min=r.tailStart;$('tailOffset').max=r.tailStart+r.tail.length;$('tailOffsetMode').value='immediate';renderTailRaw();}
function renderTailRaw(){if(!tailData)return;const r=tailData.views[Number($('tailSource').value)].reports[Number($('tailInterpretation').value)||0],kind=$('tailOffsetMode').value,end=r.tailStart+r.tail.length;let start=kind==='immediate'?r.tailStart:kind==='byte'?Math.min(end,Math.ceil(r.tailStart/8)*8):Number($('tailOffset').value);$('tailOffset').disabled=kind!=='custom';if(!Number.isInteger(start)||start<r.tailStart||start>end){$('tailOffset').setCustomValidity(getLanguage()==='ja'?`${r.tailStart}〜${end} を指定してください`:`Enter a value from ${r.tailStart} to ${end}`);$('tailOffset').reportValidity();return;}$('tailOffset').setCustomValidity('');$('tailOffset').value=start;const omitted=start-r.tailStart,prefix=!omitted?'':getLanguage()==='ja'?`表示を省略した範囲: bit ${r.tailStart}–${start-1} / ${omitted} bit（データは削除していません）\n`:`Before display start: bits ${r.tailStart}–${start-1} / ${omitted} bits (display offset only).\n`;const help=getLanguage()==='ja'?`bit ${start} から8 bitずつ表示します。各行は「位置: ビット列  16進数」です。? は不明ビット、-- は8 bit揃わないか不明ビットがあり16進数で表せない箇所です。\n`:`Groups of eight bits from bit ${start}. Each row shows position: bits  HEX. ? marks unknown bits; -- means fewer than eight bits or unknown bits prevent a HEX value.\n`;$('tailRaw').textContent=prefix+help+Array.from({length:Math.ceil((end-start)/8)},(_,i)=>{const bits=r.tail.slice(start-r.tailStart+i*8,start-r.tailStart+i*8+8);return `${start+i*8}: ${bits}  ${bits.length===8&&!bits.includes('?')?parseInt(bits,2).toString(16).toUpperCase().padStart(2,'0'):'--'}`;}).join('\n');}
$('tailSource').onchange=()=>renderTail(true);$('tailInterpretation').onchange=()=>renderTail();$('tailOffsetMode').onchange=renderTailRaw;$('tailRawShow').onclick=renderTailRaw;

$('retrySingle').onclick=async()=>{try{state.oneTimeSingleSegment=true;state.automaticTrial=null;state.automatic=null;record('今回だけ単一セグメント仮定で再実行');if(result)result.needsInspectionRestore=true;result=await call({action:state.image?'recoverImage':'recover',...(state.image?imageRequest():{}),matrix:state.matrix,settings:{...state.settings,single:true}},'単一セグメント仮定で再実行');candidateIndex=0;$('resultStage').value='after';result.input=clone(state.matrix);result.settings={...clone(state.settings),single:true};result.fingerprint=JSON.stringify([state.matrix,state.settings,!!state.oneTimeSingleSegment]);record('修復結果');setResultView('content');renderResult();render();}catch(e){report(e);}};

function currentSelection(){const c=result?.candidates?.[candidateIndex];if(!c||result.fingerprint!==JSON.stringify([state.matrix,state.settings,!!state.oneTimeSingleSegment]))return null;return {version:c.version,ecc:c.ecc,mask:c.mask,codewords:c.codewords};}
async function restoreSelection(selection,showResults=true){if(state.imageOnly)return;const saved=state.history[state.cursor];if(!state.automaticTrial&&['フルオート解析','Automatic scan','簡易自動修復','Quick automatic repair'].includes(saved?.label)){toast(getLanguage()==='ja'?'この履歴には自動修復の実行条件がありません。入力を保持しました。修復をもう一度実行してください。':'This history has no automatic execution conditions. The input is preserved. Run recovery again.');return;}if(!selection&&!state.automaticTrial)return;const trial=state.automaticTrial,imageSearch=!!imageElement&&!trial;const r=await call({...trial,action:imageSearch?'recoverImage':'recover',...(imageSearch?imageRequest():{}),matrix:state.matrix,settings:{...state.settings,single:state.settings.single||!!state.oneTimeSingleSegment}},'保存した候補を再計算');r.fingerprint=JSON.stringify([state.matrix,state.settings,!!state.oneTimeSingleSegment]);result=r;if(trial)for(const c of r.candidates)Object.assign(c,{...trial,settings:clone(state.settings),input:clone(state.matrix)});result.automatic=state.automatic?.status?{...state.automatic.status}:trial?{selectedIndex:-1,restored:true,assumption:trial.automaticScanAssumption}:null;result.input=clone(state.matrix);result.settings={...clone(state.settings),single:state.settings.single||!!state.oneTimeSingleSegment};candidateIndex=selection?r.candidates.findIndex(c=>c.version===selection.version&&c.ecc===selection.ecc&&c.mask===selection.mask&&JSON.stringify(c.codewords)===JSON.stringify(selection.codewords)):r.candidates.length?0:-1;if(result.automatic&&result.automatic.selectedIndex>=0)result.automatic.selectedIndex=candidateIndex;renderResult();if(showResults)setTab('results');else render();renderHistory();if(selection&&candidateIndex<0)toast('以前の候補が見つかりません。結果は未選択です。');}
$('attachImage').onclick=()=>$('companionImageFile').click();
$('companionImageFile').onchange=async e=>{const f=e.target.files[0];e.target.value='';if(!f)return;try{if(f.size>32*1024*1024)throw new Error('画像は32MB以下にしてください');const img=await loadImageElement(f);if(img.naturalWidth>4096||img.naturalHeight>4096||img.naturalWidth*img.naturalHeight>8000000)throw new Error('QRRの元画像は4096px以内・800万画素以下で追加してください。自動縮小は座標を変えるため行いません。');const c=document.createElement('canvas');c.width=img.naturalWidth;c.height=img.naturalHeight;const cx=c.getContext('2d');cx.fillStyle='#fff';cx.fillRect(0,0,c.width,c.height);cx.drawImage(img,0,0);const blob=await new Promise(r=>c.toBlob(r,'image/png'));state.image=blob;state.imageHash=await digest(await blob.arrayBuffer());imageElement=await loadImageElement(blob);imagePixelsCache=null;state.settings.assigned=false;if(!state.settings.quad)state.settings.quad=[c.width*.1,c.height*.1,c.width*.9,c.height*.1,c.width*.9,c.height*.9,c.width*.1,c.height*.9];syncSettings();mode='image';setTab('input');await sample();toast('元画像を追加しました。QRRだけでは画像の同一性を証明できないため、四隅と向きを確認してください。');}catch(e){report(e);}};

window.addEventListener('qrevy-language',async()=>{try{renderNativeHelp();syncSettings();for(let i=0;i<$('curveChoice').options.length;i++)$('curveChoice').options[i].textContent=getLanguage()==='ja'?`補正候補 ${i+1}`:`Correction candidate ${i+1}`;updateCurveStatus();renderThresholdLabels();renderHistory();render();const hadInput=!!$('inputDataReport').textContent,hadTail=!!$('tailReport').textContent,hadData=!!$('hex').textContent;if(result){const saved=result,selectedCandidate=saved.candidates[candidateIndex];const refreshed=await inspectCall({action:'localizeResult'},'保存した候補を再計算');if(saved.automatic)for(const c of refreshed.candidates){const prior=saved.candidates.find(p=>p.version===c.version&&p.ecc===c.ecc&&p.mask===c.mask&&JSON.stringify(p.codewords)===JSON.stringify(c.codewords));if(prior?.automaticScanAssumption!=null)Object.assign(c,{...readAutomaticTrial(prior),settings:clone(prior.settings),input:clone(prior.input)});}result={...refreshed,input:saved.input,settings:saved.settings,fingerprint:saved.fingerprint,automatic:saved.automatic};if(dataDisplayResult===saved)dataDisplayResult=result;candidateIndex=selectedCandidate?result.candidates.findIndex(c=>c.version===selectedCandidate.version&&c.ecc===selectedCandidate.ecc&&c.mask===selectedCandidate.mask&&JSON.stringify(c.codewords)===JSON.stringify(selectedCandidate.codewords)):-1;comparisonKey='';renderResult();render();await comparisonPromise;await refreshCandidateDiagnostic();if(hadData&&(result.candidates[candidateIndex]||result.failureFormat))await updateResultData();if(hadTail)await updateTailAnalysis();}if(hadInput)await updateInputData();}catch(e){report(e);}});


$('fineCorners').onclick=async()=>{if(!imageElement||recoveryWorker.pending)return;imageRequest();const quad=await fineCorners(imagePreview,state.settings);if(!quad)return;disableLm();state.settings.gridReference=true;state.settings.quad=quad;state.settings.warp=null;state.settings.grid=null;await sample().catch(report);mode='image';render();};

// Remap image observations through the immutable source, preserving logical edits.
function remappedImageSettings(nextOps){
  const w=imageElement.naturalWidth,h=imageElement.naturalHeight;
  const before=imageTransform(w,h,state.settings.imageOps),after=imageTransform(w,h,nextOps);
  const map=(x,y)=>after.fromSource(...before.toSource(x,y));
  const quad=mapPoints(quadWithoutLm(),map);
  const finders=Array.from({length:(state.settings.finders||[]).length/3},(_,i)=>[state.settings.finders[i*3],...map(state.settings.finders[i*3+1],state.settings.finders[i*3+2])]).flat();
  if([...quad,...finders].some(v=>!Number.isFinite(v)||Math.abs(v)>1e7))throw new Error('Invalid transformed image geometry');
  return {imageOps:nextOps,quad,finders};
}
function rectifiedVersionSettings(version){
  const info=sourceTransform(state.settings.imageOps,state.settings.version);
  if(!imageElement||!info.quad||info.version===version)return null;
  return remappedImageSettings([{...info,version}]);
}
async function transformImage(op){
  if(!imageElement||recoveryWorker.pending)return;
  const info=sourceTransform(state.settings.imageOps,state.settings.version),next={...info};
  if(op.type==='rotate'){
    if(!Number.isFinite(op.angle))throw new Error('Invalid rotation angle');
    const rounded=Math.round((info.angle+op.angle)*10000)/10000;
    next.angle=((rounded+180)%360+360)%360-180;
    if(next.angle===info.angle)return;
  }else if(op.type==='rectify'){
    const before=imageTransform(imageElement.naturalWidth,imageElement.naturalHeight,state.settings.imageOps);
    next.quad=mapPoints(quadWithoutLm(),before.toSource).map(v=>Math.round(v*1e6)/1e6);
    // Rectify the physical face without changing mirrored logical orientation.
    const q=next.quad,cross=(q[2]-q[0])*(q[5]-q[3])-(q[3]-q[1])*(q[4]-q[2]);
    if(cross<0)next.quad=[...q.slice(0,2),...q.slice(6,8),...q.slice(4,6),...q.slice(2,4)];
    next.version=state.settings.version;next.legacyAngle=null;delete next.legacyRaster;
  }else if(op.type==='clearPerspective'){
    if(!info.quad)return;
    next.quad=null;next.legacyAngle=null;delete next.legacyRaster;
  }
  const mapped=remappedImageSettings([next]);
  // Render before changing state so invalid transforms leave observations intact.
  prepareImage(imageElement,mapped.imageOps);
  Object.assign(state.settings,mapped);invalidateLm();state.settings.warp=null;state.settings.previewQuad=null;state.settings.grid=null;curve.candidates=[];curve.failed=false;
  imagePixelsCache=null;syncSettings();await sample();mode='image';render();
}
$('rotateImage').onclick=()=>transformImage({type:'rotate',angle:Number($('imageAngle').value)}).catch(report);
$('clearPerspective').onclick=()=>transformImage({type:'clearPerspective'}).catch(report);
$('rectifyImage').onclick=()=>transformImage({type:'rectify',quad:[...state.settings.quad],version:state.settings.version}).catch(report);
$('exportSource').onclick=async()=>{if(!imageElement)return;imageRequest();const blob=await new Promise(r=>imagePreview.toBlob(r,'image/png'));await download(blob,'qrevy-sampling-source.png',true);};

let inputDataRevision=0,inputDataKey='';
function inputDataFingerprint(){return JSON.stringify([state.id,state.matrix,state.settings,$('inputUnmask').checked,$('inputPartial').checked,getLanguage()]);}
async function updateInputData(){
 const revision=++inputDataRevision;
 try{
  while(recoveryWorker.pending)await recoveryWorker.pending.promise.catch(()=>{});
  if(revision!==inputDataRevision||tab!=='edit'||!$('inputDataPanel').open||!state.matrix)return;
  const key=inputDataFingerprint();if(key===inputDataKey)return;
  inputDataKey=key;$('inputDataReport').textContent='';
  const r=await call({action:'inputData',matrix:state.matrix,settings:state.settings,unmasked:$('inputUnmask').checked,partial:$('inputPartial').checked},'修復前データを解析');
  if(revision===inputDataRevision&&key===inputDataFingerprint()&&tab==='edit'&&$('inputDataPanel').open)$('inputDataReport').textContent=r.text;
  else if(inputDataKey===key)inputDataKey='';
 }catch(e){inputDataKey='';report(e);}
}
$('openInputData').onclick=()=>{$('inputDataPanel').showModal();updateInputData();};

$('discardDraft').onclick=()=>{
  if(recoveryWorker.pending||!confirm(translate('最後に保存した状態へ戻しますか？')))return;
  restoreHistory(checkpointHistoryIndex(state));
};

$('candidatePicker').onchange=()=>selectCandidate(Number($('candidatePicker').value));

$('imageSettings').ontoggle=()=>render();

// Keep browser focus/scroll-to-control behavior below the pinned preview.
function updatePreviewInset(){const headerHeight=document.querySelector('body>header').getBoundingClientRect().height;document.documentElement.style.setProperty('--header-height',`${headerHeight}px`);const mobile=getComputedStyle($('previewDock')).position==='sticky'&&document.body.classList.contains('has-work');document.documentElement.style.scrollPaddingTop=mobile?`${headerHeight+$('previewDock').getBoundingClientRect().height+12}px`:'';}
new ResizeObserver(updatePreviewInset).observe($('previewDock'));new ResizeObserver(updatePreviewInset).observe(document.querySelector('body>header'));window.addEventListener('resize',updatePreviewInset);

let candidatePage=0;const candidatePageSize=20;
function selectCandidate(index){if(recoveryWorker.pending||resultIsStale()||!result?.candidates[index])return;candidateIndex=index;if(result?.automatic)adoptAutomaticCandidate(result.candidates[index]);record('候補を選択');renderResult();render();refreshCandidateDiagnostic().then(()=>{if($('resultDataPanel').open)return updateResultData();}).catch(report);}
function renderCandidatePage(){const query=$('candidateSearch').value.toLocaleLowerCase(),all=result?.candidates||[],matches=[];all.forEach((c,i)=>{if(!query||(c.text??'').toLocaleLowerCase().includes(query))matches.push(i);});const pages=Math.max(1,Math.ceil(matches.length/candidatePageSize));candidatePage=Math.min(candidatePage,pages-1);const from=candidatePage*candidatePageSize;
 $('candidates').replaceChildren(...matches.slice(from,from+candidatePageSize).map(i=>{const c=all[i],b=document.createElement('button');b.className='candidate'+(i===candidateIndex?' selected':'');b.setAttribute('aria-pressed',String(i===candidateIndex));const info=document.createElement('small');info.textContent=getLanguage()==='ja'?`候補${i+1}  ―  データ・ECCの不一致: ${c.mismatches}bit`:`Candidate ${i+1}  ―  Data/ECC mismatches: ${c.mismatches}bit`;b.append(info,document.createTextNode(c.content&&c.content.includes(c.text??'')?c.content:c.text??translate('内容の復号には至っていません')));b.onclick=()=>{selectCandidate(i);$('candidateDialog').close();$('candidatePicker').focus({preventScroll:true});};return b;}));
 $('candidatePageStatus').textContent=`${matches.length?from+1:0}–${Math.min(from+candidatePageSize,matches.length)} / ${matches.length}`;$('candidatePrevious').disabled=candidatePage===0;$('candidateNext').disabled=candidatePage>=pages-1;$('candidates').scrollTop=0;
}
$('browseCandidates').onclick=()=>{$('candidateSearch').value='';candidatePage=Math.max(0,Math.floor(candidateIndex/candidatePageSize));renderCandidatePage();$('candidateDialog').showModal();};
$('candidateSearch').oninput=()=>{candidatePage=0;renderCandidatePage();};$('candidatePrevious').onclick=()=>{candidatePage--;renderCandidatePage();};$('candidateNext').onclick=()=>{candidatePage++;renderCandidatePage();};
function setResultView(view,scroll=false){
 document.body.dataset.resultView='content';
 const section=view==='evidence'?$('resultEvidencePanel'):view==='diagnostic'?$('resultDiagnosticSection'):null;
 if(section){section.open=true;if(scroll)section.scrollIntoView({block:'start'});}
 else if(scroll)document.querySelector('[data-panel="results"]').scrollIntoView({block:'start'});
}

$('resultStage').onchange=render;
let comparisonKey='',comparisonA=null,comparisonB=null,comparisonPromise=Promise.resolve();
function renderComparison(){const all=result?.candidates||[],old=$('compareCandidate').value;$('candidateComparison').hidden=all.length<2;$('compareCandidate').replaceChildren(...all.flatMap((c,i)=>i===candidateIndex?[]:[new Option(t('result_tab',i+1),String(i))]));if([...$('compareCandidate').options].some(o=>o.value===old))$('compareCandidate').value=old;const a=all[candidateIndex],b=all[Number($('compareCandidate').value)];const key=JSON.stringify([candidateIndex,$('compareCandidate').value,getLanguage()]);if(!a||!b||all.length<2){$('comparisonText').textContent='';comparisonKey='';comparisonA=comparisonB=null;return;}if(!predictedComparisonOpen()||(key===comparisonKey&&a===comparisonA&&b===comparisonB))return;comparisonKey=key;comparisonA=a;comparisonB=b;comparisonPromise=inspectCall({action:'compare',candidate:a,other:b,firstNumber:candidateIndex+1,secondNumber:Number($('compareCandidate').value)+1},'比較相手を選択').then(r=>{if(key===comparisonKey&&a===comparisonA&&b===comparisonB)$('comparisonText').textContent=r.text;}).catch(report);}
function predictedComparisonOpen(){return $('candidateComparison').open;}
$('candidateComparison').ontoggle=renderComparison;$('compareCandidate').onchange=renderComparison;
let dataInspectionRevision=0;
async function updateResultData(){const revision=++dataInspectionRevision;try{while(recoveryWorker.pending)await recoveryWorker.pending.promise.catch(()=>{});if(revision!==dataInspectionRevision||!$('resultDataPanel').open||tab!=='results')return;const c=result?.candidates[candidateIndex];if(!c&&!result?.failureFormat){$('hex').textContent=getLanguage()==='ja'?'表示できるデータがありません。':'No data is available.';return;}const saved=result,index=candidateIndex;const r=await inspectCall({action:'resultData',matrix:resultInput(),candidate:c,after:$('dataStage').value==='after',unmasked:$('dataUnmask').checked,partial:$('dataPartial').checked},'データを解析');if(revision===dataInspectionRevision&&saved===result&&index===candidateIndex)$('hex').textContent=r.text;}catch(e){report(e);}};

function resultTrace(c,x,y){const trace=c?.trace?.[`${x},${y}`]||c?.formatCells?.[`${x},${y}`];if(trace)return trace;const n=c?.version?17+4*c.version:0,code=c?.codewordMap?.[y*n+x];return code>=0?{codeword:Math.floor(code/8),bit:code%8,title:translate('この候補では値を変更していません。'),detail:`raw ${Math.floor(code/8)} / bit ${code%8}`}:null;}
function relatedResultCell(c,x,y){const a=resultTrace(c,selected.x,selected.y),b=resultTrace(c,x,y);return !!a&&!!b&&(a.codeword!=null&&a.codeword===b.codeword||a.formatRole!=null&&a.formatRole===b.formatRole);}

function renderBeforeComparison(){const before=$('beforeCanvas'),compare=tab==='results'&&$('resultStage').value==='compare'&&!!result?.candidates[candidateIndex];before.hidden=!compare;$('beforePane').hidden=!compare;for(const pane of [$('beforePane'),$('afterPane')])pane.style.height=compare?`${Math.max(80,$('canvasScroll').clientHeight-20)}px`:'';$('canvasScroll').classList.toggle('comparison-view',compare);if(!compare)return;const m=resultInput(),rows=rowsOf(m),n=rows.length,c=result.candidates[candidateIndex],cx=before.getContext('2d');before.width=canvas.width;before.height=canvas.height;before.style.width=canvas.style.width;before.style.height=canvas.style.height;if(mode==='image'&&imageElement){const settings=imageDisplaySettings(),request=imageRequest(settings);drawImageLayer(before,m,settings,request,parseFloat(canvas.style.width));return;}cx.fillStyle='#fff';cx.fillRect(0,0,before.width,before.height);for(let y=0;y<n;y++)for(let x=0;x<n;x++){let value=rows[y][x];if($('unmask').checked&&!isFunction(c.version,x,y)&&maskBit(c.mask,x,y)&&['0','1'].includes(value))value=value==='1'?'0':'1';cx.fillStyle=value==='1'?'#000':value==='0'?'#fff':'#b9b9b9';cx.fillRect((x+2)*12,(y+2)*12,12,12);if(value==='?'||value==='*'){cx.strokeStyle='#787878';cx.lineWidth=.7;cx.beginPath();cx.moveTo((x+2)*12+3,(y+2)*12+3);cx.lineTo((x+2)*12+9,(y+2)*12+9);cx.moveTo((x+2)*12+9,(y+2)*12+3);cx.lineTo((x+2)*12+3,(y+2)*12+9);cx.stroke();}}}
$('beforeCanvas').onpointerdown=e=>{if(e.button!==0||pan||recoveryWorker.pending)return;const p=position(e),n=rowsOf(resultInput()).length,cell=mode==='image'?imageCellAt(p,imageDisplaySettings().grid,n):null,x=cell?.x??(mode==='image'?-1:Math.floor(p.x/12)-2),y=cell?.y??(mode==='image'?-1:Math.floor(p.y/12)-2);if(x>=0&&y>=0&&x<n&&y<n){selected={x,y};selectedCells=[y*n+x];revealCellEvidence();render();}};
$('calculationDetails').onchange=showCell;
function clearWorkspace(){
 state={id:crypto.randomUUID(),name:'新しい作業',matrix:null,source:null,settings:clone(defaults),history:[],cursor:-1,image:null,imageHash:null};
 imageElement=null;imagePixelsCache=null;imagePreview=null;result=null;candidateIndex=0;selected=null;selectedCells=[];zoom=1;mode='matrix';
 overlayFingerprint=0;curve.candidates=[];curve.failed=false;curve.memoryLimited=false;correctionCache.clear();
 clearAnalysis();syncSettings();renderResult();renderHistory();$('saveStatus').textContent='';setTab('input');
}
async function deleteSavedSessions(id=null){
 if(deletingSessions||recoveryWorker.pending)return;
 deletingSessions=true;
 const deletion=saveChain.catch(()=>{}).then(async()=>{
  if(id===null)await removeAllSessions();else await removeSession(id);
  if(id===null||state.id===id)clearWorkspace();
 });
 saveChain=deletion.catch(()=>{});
 try{await deletion;}finally{deletingSessions=false;}
}
$('deleteAllSessions').onclick=async()=>{if(!confirm(translate('保存済み作業をすべて削除しますか？')))return;try{await deleteSavedSessions();await showSessions();}catch(e){report(e);}};

$('manualFocus').onchange=render;
$('codewordOverlay').onchange=async()=>{
 if(!$('codewordOverlay').checked){render();return;}
 if(overlayUpdating)return;
 // Queue behind eligibility checks without losing this matrix's overlay refresh.
 overlayUpdating=true;
 try{
  const text=state.matrix.text,r=await call({action:'overlay',matrix:state.matrix});
  if(state.matrix?.text===text){overlayCells=r.cells;overlayFingerprint=text;}
 }catch(e){report(e);return;}finally{overlayUpdating=false;}
 render();
};
function renderFinderHint(){
 const role=$('finderRole').value,key={0:'top_left',1:'top_right',3:'bottom_left'}[role];
 const hint=key?t('finder_placement_help',t(key)):translate('役割を選び、画像上のFinder付近をタップしてください。役割を選択していない間は、四隅をドラッグできます。');
 $('finderHint').hidden=!imageElement;
 if($('finderHint').textContent!==hint)$('finderHint').textContent=hint;
}
$('finderRole').onchange=()=>{$('removeFinder').disabled=!!recoveryWorker.pending||$('finderRole').value==='';renderFinderHint();};
$('removeFinder').onclick=async()=>{if($('finderRole').value==='')return;const role=Number($('finderRole').value);invalidateLm(true);state.settings.warp=null;state.settings.previewQuad=null;curve.candidates=[];state.settings.finders=(state.settings.finders||[]).filter((_,i,a)=>a[Math.floor(i/3)*3]!==role);if(!state.settings.finders.length){state.settings.confirmedPolarity='UNKNOWN';state.settings.assigned=state.settings.visibleFinderRole!=null;}syncSettings();if(state.image)await sample(false,{recordHistory:false}).catch(report);record('Finder指定を解除');render();};
document.querySelectorAll('[data-reset]').forEach(button=>button.onclick=async()=>{const key=button.dataset.reset;state.settings[key]=0;invalidateLm(true);state.settings.warp=null;state.settings.previewQuad=null;curve.candidates=[];syncSettings();await sample().catch(report);});
$('resetImageAdjustments').onclick=async()=>{Object.assign(state.settings,{brightness:0,contrast:0,toneInvert:false});invalidateLm(true);state.settings.warp=null;state.settings.previewQuad=null;curve.candidates=[];syncSettings();await sample().catch(report);};
$('resetImageRotation').onclick=()=>{const angle=sourceTransform(state.settings.imageOps,state.settings.version).angle;return transformImage({type:'rotate',angle:-angle}).catch(report);};

// Two fingers zoom the work surface; they never commit a brush transaction.
// An already moved corner is finalized before zooming, just as on pointer up/cancel.
for(const surface of [canvas,$('beforeCanvas')]){
const touchPoints=new Map();let pinchGesture=null,panGesture=null;
surface.addEventListener('pointerdown',e=>{if(e.button!==0||recoveryWorker.pending)return;if(e.pointerType==='mouse'&&!pan)return;touchPoints.set(e.pointerId,{x:e.clientX,y:e.clientY});if(touchPoints.size===2){const [a,b]=[...touchPoints.values()],box=viewScroll(),r=surface.getBoundingClientRect();pinchGesture={distance:Math.hypot(a.x-b.x,a.y-b.y),zoom,cx:((a.x+b.x)/2-r.left)/r.width,cy:((a.y+b.y)/2-r.top)/r.height};cellGesture=null;selectedCells=[];if(drag!==null)finishCornerDrag();panGesture=null;surface.setPointerCapture(e.pointerId);e.preventDefault();e.stopImmediatePropagation();}else if(pan){panGesture={x:e.clientX,y:e.clientY,left:viewScroll().scrollLeft,top:viewScroll().scrollTop};surface.setPointerCapture(e.pointerId);e.preventDefault();e.stopImmediatePropagation();}},true);
surface.addEventListener('pointermove',e=>{if(touchPoints.has(e.pointerId))touchPoints.set(e.pointerId,{x:e.clientX,y:e.clientY});if(pinchGesture&&touchPoints.size>=2){const [a,b]=[...touchPoints.values()],g=pinchGesture,box=viewScroll();zoom=Math.max(.25,Math.min(8,g.zoom*Math.hypot(a.x-b.x,a.y-b.y)/Math.max(1,g.distance)));render();const r=surface.getBoundingClientRect();box.scrollLeft+=r.left+g.cx*r.width-(a.x+b.x)/2;box.scrollTop+=r.top+g.cy*r.height-(a.y+b.y)/2;e.preventDefault();e.stopImmediatePropagation();}else if(panGesture){viewScroll().scrollLeft=panGesture.left+panGesture.x-e.clientX;viewScroll().scrollTop=panGesture.top+panGesture.y-e.clientY;e.preventDefault();e.stopImmediatePropagation();}},true);
for(const type of ['pointerup','pointercancel'])surface.addEventListener(type,e=>{touchPoints.delete(e.pointerId);if(pinchGesture||panGesture){if(touchPoints.size===0){pinchGesture=null;panGesture=null;}e.preventDefault();e.stopImmediatePropagation();}},true);
}

async function inspectCall(request,label){
  while(recoveryWorker.pending)await recoveryWorker.pending.promise.catch(()=>{});
  if(result?.needsInspectionRestore){
    const saved=result,oneTime=JSON.parse(saved.fingerprint)[2],trial=readAutomaticTrial(saved.candidates[candidateIndex])||state.automaticTrial,imageSearch=!!imageElement&&!trial,pixels=imageSearch?imageRequest(saved.settings):{};
    if(imageSearch)imageRequest();
    const restored=await call({...trial,action:imageSearch?'recoverImage':'recover',...pixels,matrix:saved.input,settings:{...saved.settings,single:saved.settings.single||oneTime}},'保存した候補を再計算');
    for(const candidate of saved.candidates){
      const fresh=restored.candidates.find(c=>c.version===candidate.version&&c.ecc===candidate.ecc&&c.mask===candidate.mask&&JSON.stringify(c.codewords)===JSON.stringify(candidate.codewords));
      // Worker IDs can be reassigned to another candidate. Never retain an old
      // ID when the saved candidate is absent from the recalculated result.
      candidate.inspectionId=fresh?.inspectionId??null;
      candidate.inspectionUnavailable=!fresh;
    }
    saved.inspectionRestoreMissing=saved.candidates.some(c=>c.inspectionUnavailable);
    saved.needsInspectionRestore=false;
  }
  if(request.candidate?.inspectionUnavailable||request.other?.inspectionUnavailable||(request.action==='localizeResult'&&result?.inspectionRestoreMissing))
    throw new Error(getLanguage()==='ja'?'以前の候補を再計算で再現できないため、根拠を表示できません。修復をもう一度実行してください。':'The previous candidate could not be reproduced, so its evidence is unavailable. Run recovery again.');
  return call(request,label);
}

function renderThresholdLabels(black=state.settings.black,white=state.settings.white){const inv=thresholdsInverted(),ja=getLanguage()==='ja';$('blackLabel').textContent=inv?(ja?`画像の明部（QRの黒(1)）: ${255-black}以上`:`Light pixels (QR black (1)): ≥ ${255-black}`):(ja?`画像の暗部（QRの黒(1)）: ${black}以下`:`Dark pixels (QR black (1)): ≤ ${black}`);$('whiteLabel').textContent=inv?(ja?`画像の暗部（QRの白(0)）: ${255-white}以下`:`Dark pixels (QR white (0)): ≤ ${255-white}`):(ja?`画像の明部（QRの白(0)）: ${white}以上`:`Light pixels (QR white (0)): ≥ ${white}`);}

function classificationText(m){const count=value=>m.text.split(value).length-1,ja=getLanguage()==='ja';return (ja?`判定: 1=${count('1')}  0=${count('0')}  ?=${count('?')}  *=${count('*')}`:`Classification: 1=${count('1')}  0=${count('0')}  ?=${count('?')}  *=${count('*')}`)+(state.settings.assigned?'':ja?'\n向き未確定・位置合わせ用の仮ガイド':'\nOrientation unassigned · provisional alignment guides');}
function renderLimitLabels(){$('formatLimitNote').textContent=t('format_mismatch_limit_note');$('dataLimitNote').textContent=t('data_ecc_mismatch_limit_note');$('dataLimit').max=regionCache.get(state.settings.version)?.size??29648;const limits=regionLimitsCache.get(state.settings.version);if(!limits)return;for(const kind of ['format','data']){const key=kind==='format'?'formatLimit':'dataLimit';for(const option of $(key+'Mode').options){const mode=option.value,value=mode==='MANUAL'?state.settings[key]:limits[kind][mode],bits=value==null?t('format_mismatch_limit_unlimited'):`${value}${getLanguage()==='ja'?'bit':' bits'}`;option.textContent=['AUTOMATIC','STRICT','RELAXED','MANUAL'].includes(mode)?t('error_limit_'+mode.toLowerCase()+'_value',bits):mode==='EXACT'?t('error_limit_exact'):t('format_mismatch_limit_unlimited');}}}
function renderNextResultAction(){
  const action=result?.nextAction,visible=!!action&&!result?.candidates.length&&!resultIsStale();
  $('nextResultGuide').hidden=$('nextResultAction').hidden=!visible;
  $('nextResultDetails').hidden=!visible||!action.detail;
  if(visible){$('nextResultGuide').textContent=action.text;$('nextResultAction').textContent=action.label;$('nextResultAction').disabled=!!recoveryWorker.pending;$('nextResultDetail').textContent=action.detail||'';}
}
$('nextResultAction').onclick=()=>{
  if(recoveryWorker.pending||!result?.nextAction||resultIsStale())return;
  const settings=result.nextAction.target==='settings';setTab(settings?'input':'edit');
  const target=settings?$('ecc'):$('canvas');if(settings)for(let parent=target.parentElement;parent;parent=parent.parentElement)if(parent.tagName==='DETAILS')parent.open=true;target.scrollIntoView({block:'center'});if(settings)target.focus();
};
// Inspection IDs restart with the worker; candidate objects identify the actual result.
const technicalTextCache=new WeakMap(),technicalTextRequests=new WeakMap();
function candidateCache(store,c,create){let value=store.get(c);if(!value){value=create();store.set(c,value);}return value;}
function technicalCellText(c,x,y,trace){
  if(trace?.formatRole)return trace.formatDetail||trace.detail;
  if(!trace)return getLanguage()==='ja'?'このセルの追加の計算記録はありません。':'No additional calculation record is available for this cell.';
  const key=`${getLanguage()}:${x},${y}`,cache=candidateCache(technicalTextCache,c,()=>new Map()),requests=candidateCache(technicalTextRequests,c,()=>new Set());
  if(cache.has(key))return cache.get(key);
  if(!requests.has(key)){
    requests.add(key);
    queueMicrotask(async()=>{try{while(recoveryWorker.pending)await recoveryWorker.pending.promise.catch(()=>{});const response=await inspectCall({action:'cellTechnical',candidate:c,x,y},'計算の補足');cache.set(key,response.text);showCell();}catch(e){report(e);}finally{requests.delete(key);}});
  }
  return trace.detail;
}
const codewordTextCache=new WeakMap(),codewordTextRequests=new WeakMap();
function codewordText(c,x,y){
  const value=c.codewordMap?.[y*(17+4*c.version)+x];if(value==null||value<0)return '';
  const key=`${getLanguage()}:${value}`,cache=candidateCache(codewordTextCache,c,()=>new Map()),requests=candidateCache(codewordTextRequests,c,()=>new Set());
  if(cache.has(key))return '\n'+cache.get(key);
  if(!requests.has(key)){
    requests.add(key);
    queueMicrotask(async()=>{try{while(recoveryWorker.pending)await recoveryWorker.pending.promise.catch(()=>{});const response=await inspectCall({action:'codewordData',candidate:c,raw:Math.floor(value/8),bit:value%8},'コード語の詳細を表示');cache.set(key,response.text);showCell();}catch(e){report(e);}finally{requests.delete(key);}});
  }
  return '';
}
function resultBasicCellText(c,x,y,trace){const ja=getLanguage()==='ja',n=17+4*c.version,original=resultInput(),before=rowsOf(original)[y][x],after=rowsOf(c.matrix)[y][x],source=c.matrix.sources[y*n+x],inputSource=original.sources[y*n+x],mask=$('unmask').checked&&!isFunction(c.version,x,y)&&maskBit(c.mask,x,y),display=v=>mask&&['0','1'].includes(v)?(v==='1'?'0':'1'):v,value=v=>translate(v==='0'?'白（0）':v==='1'?'黒（1）':'不明（?）');const a=display(before),b=display(after);let reason=source==='6'?'固定指定した値です。修正可の設定により復元時の訂正を許可しています。':source==='5'?'仮指定した値です。復元時に訂正されることがあります。':source==='1'?'手動で指定した値です。':before===after?'この候補では値を変更していません。':trace?.bitConstraintSolved&&['RS_ERASURE_RECOVERY','RS_ERROR_CORRECTION'].includes(trace.reason)?'部分bit制約で補いました。':trace?.reason==='RS_ERASURE_RECOVERY'?'RS誤り訂正で不明な値を補いました。':trace?.reason==='RS_ERROR_CORRECTION'?'RS誤り訂正で白黒を変更しました。':trace?.title||'この候補の値を表示しています。';let text=(ja?`セル (${x},${y})（座標は0から）`:`Cell (${x},${y}) (zero-based coordinates)`)+`\n${a===b?(ja?`${value(b)}のまま`:`Unchanged: ${value(b)}`):`${value(a)} → ${value(b)}`}`;if($('unmask').checked&&!isFunction(c.version,x,y))text+=t('inline:\nマスク解除後の表示値です。');if(c.formatCells?.[`${x},${y}`])text+='\n'+c.formatCells[`${x},${y}`].title;text+='\n'+translate(reason);const input={0:'入力：画像から判別した結果',6:'入力：手動で指定した値（固定あり、訂正可）',5:'入力：手動で仮指定した値（固定なし、訂正可）',1:'入力：手動で指定した値（固定あり、訂正不可）'}[inputSource];if(input)text+=t('inline:\n'+input);const agreement=candidateAgreement(c,x,y);if(agreement)text+=ja?`\n比較した${agreement.total}候補中${agreement.same}候補で、このセルの白黒が一致しています。`:`\nThis cell agrees in ${agreement.same} of ${agreement.total} compared candidates.`;if(after==='?'||after==='*')text+=t('inline:\nこのセルの白黒はまだ決まっていません。');if($('sameCodeword').checked)text+=codewordText(c,x,y);return text;}

(async()=>{if(state.matrix||recoveryWorker.pending)return;try{const n=21,r=await call({action:'regions',matrix:{text:Array(n).fill('?'.repeat(n)).join('\n'),sources:'0'.repeat(n*n)},settings:{...defaults}});regionCache.set(1,new Set(r.allowed));regionKindsCache.set(1,r.kinds);regionLimitsCache.set(1,r.limits);renderLimitLabels();}catch(e){report(e);}})();

let eligibilityFingerprint='',singleEligible=false;
function applyEligibility(){$('clearVisibleFinder').hidden=state.settings.visibleFinderRole==null;$('clearVisibleFinder').disabled=!!recoveryWorker.pending;renderFinderHint();$('removeFinder').disabled=!!recoveryWorker.pending||$('finderRole').value==='';updateCurveStatus();$('refine').disabled=!!recoveryWorker.pending||!imageElement||!state.settings.assigned;$('lmStatus').textContent=!state.settings.assigned?translate('四隅とFinderの役割を指定してください'):state.settings.lmFailed?translate('補正を適用できませんでした'):state.settings.lmEnabled&&state.settings.lmAppliedQuad?translate('補正済み'):state.settings.lmBaseQuad?translate('補正はOFFです'):'';$('single').disabled=!!recoveryWorker.pending||!state.matrix||(!singleEligible&&!$('single').checked);$('colorUnknown').disabled=!!recoveryWorker.pending||!(state.settings.colorClusters||[]).length;}
function scheduleEligibility(){if(!state.matrix){$('single').disabled=true;$('colorUnknown').disabled=true;return;}const key=JSON.stringify([state.matrix,state.settings]);if(key===eligibilityFingerprint){applyEligibility();return;}queueMicrotask(async()=>{if(recoveryWorker.pending||!state.matrix)return;const current=JSON.stringify([state.matrix,state.settings]);if(current===eligibilityFingerprint)return;eligibilityFingerprint=current;try{const status=await call({action:'uiState',matrix:state.matrix,settings:state.settings});if(current===JSON.stringify([state.matrix,state.settings]))singleEligible=status.single;else eligibilityFingerprint='';applyEligibility();}catch(e){eligibilityFingerprint='';report(e);}});}

function reasonColor(reason){return reason==='FORMAT_INFORMATION'?'#be8700':reason==='USER_OVERRIDE'?'#0091c8':['RS_ERASURE_RECOVERY','RS_ERROR_CORRECTION'].includes(reason)?'#e67300':reason?.startsWith('PAYLOAD_')?'#8b5cf6':reason==='PARTIAL_BIT_SEARCH'?'#d21469':['FINDER_PATTERN','SEPARATOR','TIMING_PATTERN','ALIGNMENT_PATTERN','VERSION_INFORMATION','FIXED_DARK_MODULE','REMAINDER_BITS'].includes(reason)?'#009b50':'#505050';}
function candidateAgreement(c,x,y){if(c.formatCells?.[`${x},${y}`])return null;const seen=new Set(),all=(result?.candidates||[]).filter(candidate=>{if(candidate.text==null)return false;const key=JSON.stringify([candidate.version,candidate.ecc,candidate.mask,candidate.codewords]);if(seen.has(key))return false;seen.add(key);return true;}),sample=all.slice(0,16);if(!sample.includes(c)&&all.includes(c)&&sample.length)sample[sample.length-1]=c;const final=rowsOf(c.matrix)[y]?.[x],values=sample.map(candidate=>rowsOf(candidate.matrix)[y]?.[x]).filter(value=>value==='0'||value==='1');return values.length>=2?{same:values.filter(value=>value===final).length,total:values.length}:null;}

function viewScroll(){return $('canvasScroll').classList.contains('comparison-view')?$('afterPane'):$('canvasScroll');}
for(const id of ['afterPane','beforePane'])$(id).addEventListener('scroll',()=>{if($('resultStage').value!=='compare')return;const from=$(id),to=$(id==='afterPane'?'beforePane':'afterPane');if(to.scrollLeft!==from.scrollLeft)to.scrollLeft=from.scrollLeft;if(to.scrollTop!==from.scrollTop)to.scrollTop=from.scrollTop;});

for(const id of ['dataStage','dataUnmask','dataPartial'])$(id).onchange=()=>updateResultData();
for(const id of ['inputUnmask','inputPartial'])$(id).onchange=()=>{if(state.matrix)updateInputData();};

async function refreshCandidateDiagnostic(){await comparisonPromise;const saved=result,index=candidateIndex,c=saved?.candidates[index];if(!c)return;const r=await inspectCall({action:'diagnostic',candidate:c},'探索の診断情報');if(saved!==result||index!==candidateIndex)return;Object.assign(result,r);renderResultStatus();renderDiagnostic($('diagnostic'),r.diagnosticReport,r.diagnostic,{appendix:result.failure||''});}

$('resultDataPanel').ontoggle=()=>{if($('resultDataPanel').open)updateResultData();};

// Old histories can retain LM coordinates after refinement has been disabled.
// Restore a base only while its successful LM result is the current grid.
function quadWithoutLm(s=state.settings){return s.lmEnabled&&!s.lmFailed&&s.lmBaseQuad&&s.lmAppliedQuad&&JSON.stringify(s.quad)===JSON.stringify(s.lmAppliedQuad)?s.lmBaseQuad:s.quad;}

function invalidateLm(restore=false){if(restore)state.settings.quad=clone(quadWithoutLm());state.settings.lmAppliedQuad=null;state.settings.lmBaseQuad=null;state.settings.lmFailed=false;}

function disableLm(restore=false){const enabled=state.settings.lmEnabled;invalidateLm(restore);state.settings.lmEnabled=false;$('refine').checked=false;if(enabled)toast('LM補正を解除しました');}

function curveCacheKey(){const s=state.settings;return JSON.stringify([state.id,quadWithoutLm(s),s.version,s.lmEnabled,s.brightness,s.contrast,s.toneInvert,s.imageOps,s.polarity,s.black,s.white,s.automatic,s.finders]);}
function saveCurveCache(){if(!curve.candidates?.length)return;correctionCache.set(curveCacheKey(),{candidates:clone(curve.candidates),selected:Number($('curveChoice').value)||0,memoryLimited:curve.memoryLimited});if(correctionCache.size>40)correctionCache.delete(correctionCache.keys().next().value);}
function restoreCurveChoices(){const cached=correctionCache.get(curveCacheKey());curve.failed=false;curve.memoryLimited=false;curve.candidates=state.settings.curved&&cached?clone(cached.candidates):state.settings.curved&&state.settings.warp?[{quad:clone(state.settings.previewQuad||state.settings.quad),warp:clone(state.settings.warp),grid:clone(state.settings.grid)}]:[];$('curveChoice').replaceChildren(...curve.candidates.map((_,i)=>new Option(getLanguage()==='ja'?`補正候補 ${i+1}`:`Correction candidate ${i+1}`,String(i))));if(cached){$('curveChoice').value=String(cached.selected);curve.memoryLimited=cached.memoryLimited;}updateCurveStatus();}

const retryOffers=new Map();
function offerSingleRetry(){if(!result?.retryPromptEligible||state.oneTimeSingleSegment||state.settings.single)return;const key=JSON.stringify([state.matrix,state.settings]);let offered=retryOffers.get(state.id);if(!offered){retryOffers.clear();retryOffers.set(state.id,offered=new Set());}if(offered.has(key))return;offered.add(key);$('singleRetryDialog').showModal();}
$('declineSingleRetry').onclick=()=>$('singleRetryDialog').close();
$('acceptSingleRetry').onclick=()=>{$('singleRetryDialog').close();$('retrySingle').onclick();};

async function finishWorkspace(){
 await saveChain;
 const saved=!!state.matrix;
 if(saved)await saveSession(serializable());
 if(result)result.needsInspectionRestore=true;
 recoveryWorker.suspend();
 return saved;
}
updateWorkflow=setupWorkflow({navigate:setTab,showResult:setResultView,defaults,finish:finishWorkspace,resume:recoveryWorker.start});
$('resultDiagnosticSection').ontoggle=()=>{if($('resultDiagnosticSection').open)updateTailAnalysis();};
// Progress belongs to the running operation, independent of the selected pane or viewport.
document.body.append($('progress'));
updateWorkflow({state,tab,result,stale:resultIsStale()});
document.body.dataset.resultView='content';
// Render only after all state and event handlers have been initialized.
syncSettings();render();renderHistory();
