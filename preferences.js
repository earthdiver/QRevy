import {t,getLanguage} from './i18n.js';
const $=id=>document.getElementById(id);
const storedStyle=()=>{try{return localStorage.getItem('qrevy-appearance')||'classic';}catch{return 'classic';}};
document.body.dataset.appearance=storedStyle();
$('appearanceButton').onclick=()=>{$('appearanceChoice').value=storedStyle();$('appearanceDialog').showModal();};
$('saveAppearance').onclick=()=>{try{localStorage.setItem('qrevy-appearance',$('appearanceChoice').value);}catch{$('appearanceDialog').close();$('appearanceNoticeText').textContent=getLanguage()==='ja'?'外観設定を保存できませんでした。':'Could not save appearance settings.';$('appearanceNotice').showModal();return;}$('appearanceDialog').close();$('appearanceNoticeText').textContent=t('style_next_launch');$('appearanceNotice').showModal();};

$('versionButton').onclick=()=>{$('versionText').textContent=getLanguage()==='ja'?'QRevy PWA\nバージョン 0.2.0\n修復エンジン: QRevy 0.21.1':'QRevy PWA\nVersion 0.2.0\nRecovery engine: QRevy 0.21.1';$('versionDialog').showModal();};
let licenseRequest=0;
async function showLicense(){const request=++licenseRequest,choice=$('licenseChoice').value,file=choice==='privacy'?(getLanguage()==='ja'?'PRIVACY.ja.md':'PRIVACY.md'):choice;$('licenseText').textContent='';try{const response=await fetch('./licenses/'+file);if(!response.ok)throw new Error(String(response.status));const content=await response.text();if(request===licenseRequest)$('licenseText').textContent=content;}catch{$('licenseText').textContent=getLanguage()==='ja'?'ファイルを読み込めませんでした。':'Could not read the file.';}}
$('licensesButton').onclick=()=>{$('licensesDialog').showModal();showLicense();};$('licenseChoice').onchange=showLicense;
