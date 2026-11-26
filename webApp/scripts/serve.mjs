import http from 'node:http';
import {readFile} from 'node:fs/promises';
import {resolve,extname,relative,isAbsolute,sep} from 'node:path';
const root=resolve('dist'),port=Number(process.env.PORT||4173),host=process.env.HOST||'127.0.0.1';
const types={'.html':'text/html; charset=utf-8','.js':'text/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.png':'image/png','.webmanifest':'application/manifest+json','.md':'text/plain; charset=utf-8'};
http.createServer(async(req,res)=>{try{const path=decodeURIComponent(new URL(req.url,'http://localhost').pathname);const file=resolve(root,'.'+(path.endsWith('/')?path+'index.html':path));const rel=relative(root,file);if(!rel||isAbsolute(rel)||rel==='..'||rel.startsWith('..'+sep)){res.writeHead(403);res.end();return;}const data=await readFile(file);res.writeHead(200,{'Content-Type':types[extname(file)]||'application/octet-stream','Cache-Control':'no-cache','Cross-Origin-Opener-Policy':'same-origin','Cross-Origin-Embedder-Policy':'require-corp','X-Content-Type-Options':'nosniff'});res.end(data);}catch{res.writeHead(404);res.end('Not found');}}).listen(port,host,()=>console.log(`QRevy: http://${host}:${port}`));
