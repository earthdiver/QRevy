// Browser view of upstream RecoveryDiagnosticView; wording stays in native resources.
import {t} from './i18n.js';
const views=new WeakMap();
const node=(tag,className,text='')=>{const el=document.createElement(tag);el.className=className;el.textContent=text;return el;};
function text(el,value){const next=value??'';if(el.textContent!==next)el.textContent=next;el.hidden=!next;}
function visibility(body,show,toggle){if(!show&&body.contains(document.activeElement))toggle.focus({preventScroll:true});body.hidden=!show;}
function disclosure(button,open,label){button.textContent=label;button.setAttribute('aria-expanded',String(open));button.setAttribute('aria-description',t(open?'diagnostic_expanded':'diagnostic_collapsed'));}
let serial=0;
function groupView(){
 const root=node('div','diagnostic-group'),heading=node('h4',''),summary=node('p','diagnostic-summary'),button=node('button','diagnostic-toggle'),body=node('pre','diagnostic-body');
 button.type='button';body.id=`diagnostic-group-${++serial}`;button.setAttribute('aria-controls',body.id);root.append(heading,summary,button,body);
 const view={root,heading,summary,button,body,open:false,value:null,update(){const g=this.value,canToggle=g.collapsible&&!!g.text,calculations=g.id==='CALCULATIONS'||g.id==='SEARCH';const record=t(this.open?'diagnostic_close_record':'diagnostic_open_record',g.title??'');disclosure(button,this.open,calculations?t(this.open?'diagnostic_close_calculations':'diagnostic_open_calculations'):record);if(calculations)button.setAttribute('aria-label',record);else button.removeAttribute('aria-label');button.hidden=!canToggle;visibility(body,!!g.text&&(!g.collapsible||this.open),button.hidden?this.parentToggle:button);},bind(g,reset,parentToggle){this.value=g;this.parentToggle=parentToggle;text(heading,g.title);text(summary,g.summary);text(body,g.text);if(reset||!g.collapsible||!g.text)this.open=false;root.dataset.group=g.id;this.update();}};
 button.onclick=()=>{view.open=!view.open;view.update();};return view;
}
function sectionView(){
 const root=node('section','diagnostic-section'),heading=node('h3',''),summary=node('p','diagnostic-summary'),button=node('button','diagnostic-toggle'),body=node('div','diagnostic-groups');button.type='button';body.id=`diagnostic-section-${++serial}`;button.setAttribute('aria-controls',body.id);root.append(heading,summary,button,body);
 const view={root,heading,summary,button,body,groups:new Map(),open:false,value:null,update(){const s=this.value;disclosure(button,this.open,t(this.open?'diagnostic_close_section':'diagnostic_open_section',s.label??s.title));button.hidden=!s.groups?.length;visibility(body,this.open&&!!s.groups?.length,button);},bind(s,reset){this.value=s;root.dataset.stage=s.id;text(heading,s.title);text(summary,s.summary);const present=new Set((s.groups||[]).map(g=>g.id));for(const [id,v]of this.groups)if(!present.has(id)){if(v.root.contains(document.activeElement))button.focus({preventScroll:true});v.root.remove();this.groups.delete(id);}for(const g of s.groups||[]){let v=this.groups.get(g.id);if(!v){v=groupView();this.groups.set(g.id,v);body.append(v.root);}v.bind(g,reset,button);}if(reset||!s.groups?.length)this.open=false;this.update();}};
 button.onclick=()=>{view.open=!view.open;view.update();};return view;
}
export function renderDiagnostic(container,report,fallbackText='',{resetExpanded=false,appendix=''}={}){
 container.classList.add('diagnostic-report');let view=views.get(container);
 if(!report){container.replaceChildren(node('pre','diagnostic-body',fallbackText));views.delete(container);return;}
 if(!view){const scope=node('h3','diagnostic-scope'),overview=node('p','diagnostic-overview'),stages=node('div','diagnostic-stages'),extra=node('pre','diagnostic-appendix');container.replaceChildren(scope,overview,stages,extra);view={scope,overview,stages,extra,sections:new Map()};views.set(container,view);}
 text(view.scope,report.scope);text(view.overview,report.overview);text(view.extra,appendix);
 const present=new Set((report.sections||[]).map(s=>s.id));for(const [id,v]of view.sections)if(!present.has(id)){v.root.remove();view.sections.delete(id);}
 for(const s of report.sections||[]){let v=view.sections.get(s.id);if(!v){v=sectionView();view.sections.set(s.id,v);view.stages.append(v.root);}v.bind(s,resetExpanded);}
}
