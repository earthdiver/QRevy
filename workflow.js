import {getLanguage,translate,t} from './i18n.js';
const $=id=>document.getElementById(id);
// Move the original controls, preserving their handlers, values and accessible labels.
export function setupWorkflow({navigate,showResult,defaults,finish,resume}){
 const input=document.querySelector('[data-panel="input"]'),quick=$('imageQuickTools');
 const make=(tag,id,ja,en)=>{const e=document.createElement(tag);if(id)e.id=id;if(ja){e.dataset.userContent='';e.dataset.ja=ja;e.dataset.en=en;e.textContent=getLanguage()==='ja'?ja:en;}return e;};
 const section=(id,ja,en)=>{const e=make('details',id);e.append(make('summary','',ja,en));return e;};
 const imports=section('inputChoices','別の画像・作業を開く','Open another image or workspace');
 const start=input.querySelector('.import-grid');let n=start;
 while(n&&n.tagName!=='HR'){const next=n.nextElementSibling;imports.append(n);n=next;}
 input.querySelector('.card-heading').after(imports);imports.open=true;
 const saved=make('button','openSavedWork','保存した作業を開く','Open a saved workspace');saved.className='wide';saved.onclick=()=>$('sessionsButton').click();start.after(saved);
 let homeTab='input';
 function leaveHome(){document.body.classList.remove('home-open');}
 function home(){if(!document.body.classList.contains('home-open'))homeTab=document.body.dataset.activeTab||'input';navigate('input');document.body.classList.add('home-open');imports.open=true;requestAnimationFrame(()=>{(homeReturn.hidden?imports:homeReturn).scrollIntoView({block:'start'});$('imageImport').focus({preventScroll:true});});}
 const homeReturn=make('button','homeReturn','現在の作業に戻る','Return to current workspace');homeReturn.className='wide';imports.before(homeReturn);homeReturn.onclick=()=>{leaveHome();imports.open=false;navigate(homeTab);};
 for(const button of document.querySelectorAll('.steps [data-tab]'))button.addEventListener('click',leaveHome,{capture:true});
 const homeButton=make('button','homeButton','ホーム','Home');homeButton.onclick=home;
 const exitButton=make('button','exitButton','終了','Exit');
 const exitDialog=make('dialog','exitDialog'),exitTitle=make('h2','exitTitle','終了','Exit'),exitMessage=make('p','exitMessage');
 exitDialog.setAttribute('aria-labelledby','exitTitle');exitDialog.setAttribute('aria-describedby','exitMessage');
 const back=make('button','exitBack','アプリに戻る','Return to app');back.onclick=()=>exitDialog.close();exitDialog.append(exitTitle,exitMessage,back);document.body.append(exitDialog);
 let finishing=false,suspended=false;
 const message=(ja,en)=>{Object.assign(exitMessage.dataset,{ja,en,userContent:''});exitMessage.textContent=getLanguage()==='ja'?ja:en;};
 exitDialog.addEventListener('cancel',e=>{if(finishing)e.preventDefault();});
 exitDialog.addEventListener('close',()=>{if(suspended){suspended=false;resume();}});
 exitButton.onclick=async()=>{
  finishing=true;back.disabled=true;message('作業を保存しています…','Saving your workspace…');exitDialog.showModal();
  try{
   const saved=await finish();suspended=true;
   const installed=matchMedia('(display-mode: standalone)').matches||navigator.standalone;
   message((saved?'作業を保存しました。':'')+(installed?'端末のホーム画面に戻って終了してください。':'このタブを閉じて終了してください。'),
    (saved?'Your workspace has been saved. ':'')+(installed?'Return to your device’s home screen to exit.':'Close this tab to exit.'));
   window.close();
  }catch(error){console.error(error);message('作業を保存できませんでした。アプリに戻り、メニューの「現在の作業」からファイルに書き出してください。','Your workspace could not be saved. Return to the app and export it from Menu → Current workspace.');}
  finally{finishing=false;back.disabled=false;back.focus();}
 };
 $('automaticRepair').closest('label').nextElementSibling.after($('automaticDiagnostics').closest('label'));
 const geometry=make('section','basicGeometry');geometry.append(make('h3','','格子と向き','Grid and orientation'));
 n=$('version').closest('.two-col');while(n&&n.id!=='openCellEditor'){const next=n.nextElementSibling;geometry.append(n);n=next;}
 quick.prepend(geometry);geometry.append($('imageQuickActions'));$('imageQuickActions').append($('removeFinder'));
 const finderHint=make('p','finderHint');finderHint.className='hint';finderHint.dataset.userContent='';$('imageQuickActions').after(finderHint);
 const finder=make('section','finderSettings');geometry.append($('fineCorners'));finder.append(make('h3','','Finderの指定','Finder assignment'),$('imageQuickActions'),finderHint,$('clearFinders'));geometry.after(finder);
 const correction=make('section','gridCorrection');correction.append(make('h3','','格子の補正','Grid correction'));
 const lm=$('refine').closest('.two-col');geometry.querySelector('h3').after($('detect'));correction.append(lm,$('lmStatus'),$('curved').closest('label'),$('curveStatus'),$('curveChoice'));finder.after(correction);

 const orientationNotice=make('p','orientationNotice','Finderの役割を指定してください。','Assign a Finder role.');orientationNotice.className='notice';orientationNotice.setAttribute('role','status');geometry.after(orientationNotice,$('matrixImageTools'));
 const reading=make('section','basicReading');reading.append(make('h3','','白黒の読み取り','Module sampling'));
 reading.append($('automatic').closest('label'),document.querySelector('.threshold-controls'),$('colorUnknown').closest('label'));correction.after(reading);reading.after($('openCellEditor'));
 $('imageSettings').querySelector('summary').dataset.userContent='';Object.assign($('imageSettings').querySelector('summary').dataset,{ja:'画像の追加調整',en:'Additional image adjustments'});
 const transforms=$('rotateImage').closest('details');$('fineCorners').after(transforms.querySelector('p.hint'));transforms.querySelector('summary').dataset.userContent='';Object.assign(transforms.querySelector('summary').dataset,{ja:'画像回転・射影変換',en:'Image rotation and perspective'});
 const sampling=section('samplingOptions','読み取りの詳細','Sampling options');sampling.append($('neighborhood').closest('label'),$('geometryHelp'));reading.append(sampling);
 const output=$('exportSource');$('imageSettings').querySelector('.image-adjustment-body').append(output);
 const repair=$('ecc').closest('details');repair.id='repairDetails';repair.open=false;
 const heading=repair.querySelector('summary');heading.dataset.userContent='';Object.assign(heading.dataset,{ja:'修復の詳細設定',en:'Detailed recovery settings'});
 $('imageSettings').after(repair);
 const singleHelp=$('single').closest('label').nextElementSibling;
 const note=section('singleHelp','使用条件','When to use this option');singleHelp.before(note);note.append(singleHelp);
 // Empty headings and separators belonged to the old interleaved layout.
 for(const e of [...input.children])if(e.tagName==='HR'||e.tagName==='H3')e.remove();
 const work=make('dialog','workMenu'),workHeading=make('div');workHeading.className='card-heading';workHeading.append(make('h2','','作業','Workspace'));
 const close=make('button','closeWorkMenu','閉じる','Close');close.onclick=()=>work.close();workHeading.append(close);work.append(workHeading,document.querySelector('.save-row'),$('history').closest('details'),$('exportSession'),$('exportQrr'),$('exportText'));document.body.append(work);for(const id of ['exportSession','exportQrr','exportText'])$(id).classList.add('wide');
 const workButton=make('button','workMenuButton','現在の作業','Current workspace');workButton.onclick=()=>work.showModal();
 const menuWrap=make('div','appMenu'),menuButton=make('button','appMenuButton','メニュー ▾','Menu ▾'),menu=make('div','appMenuItems');
 menuButton.setAttribute('aria-haspopup','menu');menuButton.setAttribute('aria-controls',menu.id);menuButton.setAttribute('aria-expanded','false');
 menu.setAttribute('role','menu');menu.setAttribute('aria-labelledby',menuButton.id);menu.hidden=true;
 const recent=$('sessionsButton'),help=$('helpButton');
 Object.assign(recent.dataset,{userContent:'',ja:'保存した作業を開く',en:'Open a saved workspace'});
 Object.assign(help.dataset,{userContent:'',ja:'ヘルプ',en:'Help'});help.removeAttribute('aria-label');
 const separator=make('hr');separator.setAttribute('role','separator');menu.append(homeButton,recent,workButton,help,separator,exitButton);
 const menuItems=[homeButton,recent,workButton,help,exitButton];for(const item of menuItems){item.setAttribute('role','menuitem');item.tabIndex=-1;}
 menuWrap.append(menuButton,menu);document.querySelector('.header-right').append(menuWrap);$('helpDialog').append($('offline'));
 function closeMenu(restoreFocus=false){menu.hidden=true;menuButton.setAttribute('aria-expanded','false');if(restoreFocus)menuButton.focus({preventScroll:true});}
 function openMenu(last=false){menu.hidden=false;menuButton.setAttribute('aria-expanded','true');const available=menuItems.filter(b=>!b.disabled);(last?available.at(-1):available[0])?.focus({preventScroll:true});}
 menuButton.onclick=()=>menu.hidden?openMenu():closeMenu(true);
 menuButton.addEventListener('keydown',e=>{if(['ArrowDown','ArrowUp'].includes(e.key)){e.preventDefault();e.stopPropagation();openMenu(e.key==='ArrowUp');}});
 menu.addEventListener('click',e=>{if(e.target.closest('[role="menuitem"]'))closeMenu();});
 menuWrap.addEventListener('keydown',e=>{
  if(menu.hidden)return;
  if(e.key==='Escape'){e.preventDefault();e.stopPropagation();closeMenu(true);return;}
  if(e.key==='Tab'){closeMenu(true);return;}
  const available=menuItems.filter(b=>!b.disabled),index=available.indexOf(document.activeElement);
  const target={ArrowDown:(index+1)%available.length,ArrowUp:(index-1+available.length)%available.length,Home:0,End:available.length-1}[e.key];
  if(target!==undefined){e.preventDefault();available[target]?.focus();}
 });
 document.addEventListener('pointerdown',e=>{if(!menuWrap.contains(e.target))closeMenu();});
 menuWrap.addEventListener('focusout',e=>{if(e.relatedTarget&&!menuWrap.contains(e.relatedTarget))closeMenu();});
 work.addEventListener('click',e=>{if(e.target.closest('#save,#discardDraft,#exportSession,#exportQrr,#exportText'))work.close();});
 const priority=make('button','prioritySettings','優先探索の上限を変更','Change priority limit');
 const priorityDialog=make('dialog','priorityDialog'),priorityHeading=make('h2','priorityTitle','優先探索の上限','Priority search limit');priorityDialog.setAttribute('aria-labelledby','priorityTitle');
 const priorityField=$('priorityLimit').closest('label'),priorityHome=document.createComment('priority setting');priorityField.before(priorityHome);
 const priorityClose=make('button','closePriority','閉じる','Close');priorityClose.onclick=()=>priorityDialog.close();priorityDialog.append(priorityHeading,priorityClose);document.body.append(priorityDialog);
 priority.onclick=()=>{priorityClose.before(priorityField);priorityDialog.showModal();};priorityDialog.onclose=()=>priorityHome.after(priorityField);
 const selection=$('editorActions').querySelector('.selection-line');$('editorActions').prepend($('selectedCell'));$('editorActions').append(selection);selection.append(priority);
 $('previewDock').append($('editorActions'));document.querySelector('.viewer-controls').prepend($('editorTools'));selection.dataset.context='edit';selection.hidden=true;$('editorTools').after(selection);
 $('editKind').closest('.two-col').after($('cellSourceHelp'));$('editKind').setAttribute('aria-describedby','cellSourceHelp');
 $('editorTools').prepend($('cellEditHelp'));
 $('neighborhood').setAttribute('aria-describedby','geometryHelp');

 // Results use independent disclosures while retaining the decoded content.
 $('resultNavigation').remove();
 const resultPanel=document.querySelector('[data-panel="results"]');resultPanel.querySelector('.card-heading').after($('resultViewTools'));
 const previewHome=document.createComment('workspace preview');$('previewDock').before(previewHome);
 const evidence=$('methods').closest('details');evidence.id='resultEvidencePanel';evidence.open=false;
 const evidenceTitle=evidence.querySelector('summary');evidenceTitle.id='resultEvidenceTab';Object.assign(evidenceTitle.dataset,{userContent:'',ja:'修復の根拠',en:'Recovery evidence'});
 evidenceTitle.after($('resultInspection'));
 $('cellSummary').after($('reasonLegend'));
 const reasonText=$('reasonLegend').querySelector('p');reasonText.textContent=t(reasonText.dataset.nativeKey);
 const diagnostic=$('diagnostic').closest('details');diagnostic.id='resultDiagnosticSection';diagnostic.open=false;
 const diagnosticTitle=diagnostic.querySelector('summary');diagnosticTitle.id='resultDiagnosticTab';Object.assign(diagnosticTitle.dataset,{userContent:'',ja:'探索の診断情報',en:'Search diagnostics'});diagnostic.append($('tailAnalysis'));
 $('resultDetail').after(evidence,$('resultDataPanel'),diagnostic);
 $('resultEvidencePane').remove();$('resultDiagnosticPane').remove();
 for(const key of ['role','aria-labelledby','data-result-pane'])$('resultContentPane').removeAttribute(key);
 const resultTools=$('resultViewTools').querySelector('.display-toggles');$('previewDock').insertBefore(resultTools,$('canvasScroll'));resultTools.id='evidenceTools';
 const legend=make('p','codewordLegend');legend.className='hint';legend.dataset.nativeKey='codeword_overlay_legend';legend.dataset.userContent='';legend.textContent=t('codeword_overlay_legend');$('editorTools').append(legend);
 const actions=make('div','resultReturn');actions.className='two-col';
 for(const [ja,en,id] of [['画像・格子を調整','Adjust image and grid','version'],['セルを編集','Edit modules','cellTool'],['詳細設定で再試行','Review recovery settings','ecc']]){const b=make('button','',ja,en);b.onclick=()=>focus(id);actions.append(b);}resultPanel.append(actions);
 const resultHome=make('button','resultHome','ホーム','Home');resultHome.onclick=home;actions.append(resultHome);
 const report=section('executionReport','修復の条件・実行状況','Recovery conditions and status');resultPanel.insertBefore(report,actions);report.append($('resultStatus'));
 const overview=make('p','resultOverview');overview.dataset.userContent='';overview.className='hint';$('resultViewTools').after(overview);
 for(const [id,ja,en] of [['browseCandidates','候補一覧','Candidates']]){const el=$(id);el.dataset.userContent='';Object.assign(el.dataset,{ja,en});}
 const executed=make('p','executedSettings');executed.dataset.userContent='';executed.className='hint';report.append(executed);
 const jump=make('button','diagnosticSettings','関連する修復設定を開く','Open recovery settings');jump.onclick=()=>focus('formatLimitMode');$('diagnostic').after(jump);
 function focus(id){work.close();navigate(id==='cellTool'?'edit':'input');const el=$(id);for(let p=el.parentElement;p;p=p.parentElement)if(p.tagName==='DETAILS')p.open=true;requestAnimationFrame(()=>{el.scrollIntoView({block:'center'});el.focus({preventScroll:true});});}
 let scrolls={};window.addEventListener('scroll',()=>{if(lastTab)scrolls[lastTab]=window.scrollY;},{passive:true});
 const details=[...document.querySelectorAll('details')],views=new Map();let activeId=null,lastTab=null,activeState=null;
 const fields=['ecc','mask','lockManual','single','utf8','formatLimitMode','formatLimit','dataLimitMode','dataLimit','partial','known','list','priorityLimit','neighborhood'];
 function conditions(s){return fields.filter(k=>JSON.stringify(s[k])!==JSON.stringify(defaults[k])&&!(['formatLimit','dataLimit'].includes(k)&&s[k+'Mode']!=='MANUAL')).map(k=>{const el=$(k),label=el?.labels?.[0];let name=label?[...label.childNodes].filter(n=>n.nodeType===3).map(n=>translate(n.textContent.trim())).join(' '):'';name=name||({formatLimit:'Format bits',dataLimit:'Data/ECC bits'}[k]||k);const value=el?.tagName==='SELECT'?[...el.options].find(o=>o.value===String(s[k]))?.textContent:typeof s[k]==='boolean'?((k==='lockManual'?!s[k]:s[k])?'ON':'OFF'):s[k];return `${name}: ${value??s[k]}`;});}
 function localize(){for(const el of document.querySelectorAll('[data-ja][data-en]'))el.textContent=el.dataset[getLanguage()==='ja'?'ja':'en'];}
 localize();window.addEventListener('qrevy-language',localize);
 return function update({state,tab,result,stale}){
  const has=!!state.matrix||!!state.image;
  if(activeState!==state){leaveHome();activeState=state;}
  homeReturn.hidden=!has;
  if(activeId!==state.id){if(activeId)views.set(activeId,{open:details.map(d=>d.open),scroll:{...scrolls}});const saved=views.get(state.id);details.forEach((d,i)=>d.open=saved?saved.open[i]:d.id==='reasonLegend');imports.open=!has;scrolls=saved?.scroll||{};activeId=state.id;lastTab=null;}
  if(lastTab!==tab){const y=scrolls[tab]||0;lastTab=tab;requestAnimationFrame(()=>window.scrollTo(0,y));}
  const previewAnchor=tab==='results'?$('resultDetail'):previewHome;
  if(previewAnchor.nextSibling!==$('previewDock'))previewAnchor.after($('previewDock'));
  geometry.hidden=!has;finder.hidden=!state.image;correction.hidden=!state.image;output.hidden=!state.image;reading.hidden=!state.image;repair.hidden=!has;$('openCellEditor').hidden=!has;
  $('colorUnknown').closest('label').hidden=!(state.settings.colorClusters||[]).length;
  $('detect').hidden=!state.image;$('fineCorners').hidden=!state.image;lm.hidden=!state.image;$('lmStatus').hidden=!state.image;orientationNotice.hidden=!has||state.settings.assigned;
  legend.hidden=tab!=='edit'||!$('codewordOverlay').checked;
  priority.hidden=!state.matrix;
  const ja=getLanguage()==='ja';
  executed.textContent=result?.settings?(ja?'実行時の条件: ':'Conditions used: ')+(conditions(result.settings).join(' / ')||(ja?'初期設定':'Default settings')):'';
  document.body.classList.toggle('result-stale',!!stale);
  const count=result?.candidates?.length||0;overview.textContent=stale?(ja?'設定の変更は未反映です。再修復してください。':'Settings have changed. Run recovery again.'):result?(ja?`候補 ${count}件。元の内容かどうか確認してください。`:`${count} candidates. Verify the original content.`):'';if(result&&!count)report.open=true;
  if(!has)imports.open=true;
 };
}
