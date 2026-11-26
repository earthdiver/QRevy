// Reuse only complete local runs with identical browser inputs and host packages.
import {createHash} from 'node:crypto';
import {closeSync,existsSync,lstatSync,mkdirSync,openSync,readFileSync,readSync,readdirSync,readlinkSync,renameSync,rmSync,writeFileSync} from 'node:fs';
import {dirname,join,resolve} from 'node:path';
import {availableParallelism,release} from 'node:os';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';

const ttl=12*60*60*1000;
export function fingerprint(paths,context) {
 const hash=createHash('sha256');
 const buffer=Buffer.alloc(1024*1024);
 const field=value=>hash.update(JSON.stringify(value)+'\n');
 function visit(path,ancestors=new Set()) {
  const stat=lstatSync(path);field([path,stat.mode]);
  if(stat.isSymbolicLink()) {
   if(ancestors.has(path))throw new Error('Cyclic input symlink');
   field(readlinkSync(path));visit(resolve(dirname(path),readlinkSync(path)),new Set([...ancestors,path]));
  } else if(stat.isDirectory()) {
   for(const name of readdirSync(path).sort())visit(join(path,name),ancestors);
  } else if(stat.isFile()) {
   field(stat.size);const fd=openSync(path,'r');
   try {let length;while((length=readSync(fd,buffer,0,buffer.length,null)))hash.update(buffer.subarray(0,length));}
   finally {closeSync(fd);}
  } else throw new Error('Unsupported input file');
 }
 field(context);for(const path of paths)visit(path);return hash.digest('hex');
}

export function cacheAllowed(args,env,platform=process.platform) {
 return platform==='linux' && args.length===0 && !env.CI && env.QREVY_BROWSER_CACHE!=='off' &&
  !['QREVY_QA_FONT','QREVY_WEBKIT_EXECUTABLE','LD_PRELOAD','LD_LIBRARY_PATH','NODE_OPTIONS'].some(key=>env[key]);
}

export function successfulReport(report) {
 const stats=report?.stats;
 return stats?.expected>0 && stats.unexpected===0 && stats.flaky===0 &&
  report.errors?.length===0 && report.config?.projects?.length===5;
}

// The lock covers lookup as well as execution. A concurrent invocation runs fresh
// without publishing a receipt; an interrupted owner leaves a safe cache miss.
export function validate({directory,key,run,reuse=true,now=Date.now,log=console.log}) {
 mkdirSync(directory,{recursive:true});
 const lock=join(directory,'lock'),receipt=join(directory,'success.json');
 try {mkdirSync(lock);} catch(error) {
  if(error.code!=='EEXIST')throw error;
  log('Browser cache busy; running fresh.');return run().status;
 }
 try {
  const before=key();
  let previous;try {previous=JSON.parse(readFileSync(receipt,'utf8'));} catch {}
  const age=now()-previous?.completed;
  if(reuse && previous?.key===before && age>=0 && age<ttl && successfulReport(previous.report)) {
   log(`Browser checks reused: ${previous.report.stats.expected} passed, ${previous.report.stats.skipped} skipped (${new Date(previous.completed).toISOString()}).`);
   return 0;
  }
  rmSync(receipt,{force:true});
  log('Browser inputs changed or no recent success; running all five projects.');
  const result=run();
  if(result.status===0 && successfulReport(result.report) && before===key()) {
   const temporary=join(directory,'success.tmp');
   writeFileSync(temporary,JSON.stringify({key:before,completed:now(),report:{stats:result.report.stats,
    errors:[],config:{projects:result.report.config.projects.map(({name})=>({name}))}}}));
   renameSync(temporary,receipt);
  }
  return result.status;
 } finally {rmSync(lock,{recursive:true,force:true});}
}

function main() {
 const root=resolve(dirname(fileURLToPath(import.meta.url)),'..');
 const directory=join(root,'../build/browser-validation');
 const args=process.argv.slice(2),env=process.env;
 const run=(reportFile)=>{
  if(reportFile)rmSync(reportFile,{force:true});
  const child=spawnSync('npm',['run','test:browser','--',...args,...(reportFile?['--reporter=line,json','--forbid-only']:[])],{
   cwd:root,stdio:'inherit',env:reportFile?{...env,PLAYWRIGHT_JSON_OUTPUT_FILE:reportFile}:env});
  let report;try {if(reportFile)report=JSON.parse(readFileSync(reportFile,'utf8'));} catch {}
  return {status:child.status??1,report};
 };
 if(!cacheAllowed(args,env)) {
  if(args.length===0)rmSync(join(directory,'success.json'),{force:true});
  return run().status;
 }
 // Package-manager state covers installed native libraries/fonts. Unsupported
 // hosts and custom executable/font/library overrides always run fresh.
 const host=['/proc/sys/kernel/random/boot_id','/var/lib/dpkg/status','/etc/os-release'];
 if(!host.every(existsSync))return run().status;
 for(const path of ['/etc/ld.so.cache','/etc/fonts','/usr/share/fonts','/usr/local/share/fonts',
  join(env.HOME,'.fonts'),join(env.HOME,'.local/share/fonts'),join(env.HOME,'.config/fontconfig')])
  if(existsSync(path))host.push(path);
 const browsers=resolve(env.PLAYWRIGHT_BROWSERS_PATH || join(env.XDG_CACHE_HOME || join(env.HOME,'.cache'),'ms-playwright'));
 const paths=['dist','tests','scripts','playwright.config.js','package.json','package-lock.json','node_modules']
  .map(path=>join(root,path));
 paths.push(join(root,'../AGENTS.md'),join(root,'../scripts/check-web.sh'),process.execPath,...host);
 // Include every installed engine, including Chromium's separate headless shell.
 // Exclude Playwright installation bookkeeping, which npm ci refreshes.
 try {for(const name of readdirSync(browsers).sort())if(!name.startsWith('.'))paths.push(join(browsers,name));}
 catch {return run().status;}
 const context={node:process.version,platform:process.platform,arch:process.arch,kernel:release(),cpus:availableParallelism(),
  env:Object.entries(env).filter(([key])=>!['QREVY_BROWSER_CACHE','CODEX_TOOL_CALL_ID','CODEX_SESSION_ID',
   '_','SHLVL','OLDPWD','PS1','PROMPT_COMMAND'].includes(key)).sort(([a],[b])=>a.localeCompare(b))};
 // Resolve fingerprint errors before running: never retry a failed test silently.
 let initial;try {initial=fingerprint(paths,context);} catch(error) {
  console.log(`Browser cache unavailable: ${error.message}`);return run().status;
 }
 let first=true;
 return validate({directory,reuse:env.QREVY_BROWSER_CACHE!=='refresh',key:()=>{if(first){first=false;return initial;}return fingerprint(paths,context);},
  run:()=>run(join(directory,`report-${process.pid}.json`))});
}

if(process.argv[1] && resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
 try {process.exitCode=main();} catch(error) {console.error(error);process.exitCode=1;}
}
