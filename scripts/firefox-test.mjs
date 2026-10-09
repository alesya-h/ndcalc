// Native Firefox shortcut regression; requires system Firefox, no geckodriver.
// Runs in a disposable profile and communicates via WebDriver BiDi.
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdtemp,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {createServer} from 'node:net';
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const server=createServer();
await new Promise(r=>server.listen(0,'127.0.0.1',r));
const port=server.address().port;
await new Promise(r=>server.close(r));
const profile=await mkdtemp(join(tmpdir(),'ndcalc-firefox-'));
const ff=spawn(process.env.FIREFOX_BIN||'firefox',['--headless','--no-remote','--profile',profile,'--remote-debugging-port',String(port)],{stdio:['ignore','pipe','pipe']});
let logs='',launchError;
ff.on('error',e=>launchError=e);
ff.stderr.on('data',d=>logs+=d); ff.stdout.on('data',d=>logs+=d);
const exited=new Promise(r=>ff.once('exit',r));
let ws,id=0;
const pending=new Map();
try {
  for(let i=0;i<150&&!logs.includes('WebDriver BiDi listening')&&!launchError;i++)await sleep(100);
  if(launchError)throw launchError;
  assert.match(logs,/WebDriver BiDi listening/,'Firefox failed to start: '+logs);
  ws=new WebSocket(`ws://127.0.0.1:${port}/session`);
  await new Promise((r,j)=>{ws.onopen=r;ws.onerror=j;});
  ws.onmessage=e=>{
    const m=JSON.parse(e.data),p=pending.get(m.id);
    if(p){pending.delete(m.id);clearTimeout(p.timer);m.type==='error'?p.reject(new Error(JSON.stringify(m))):p.resolve(m.result);}
  };
  const send=(method,params={})=>new Promise((resolve,reject)=>{
    const n=++id,timer=setTimeout(()=>{pending.delete(n);reject(new Error('Timed out: '+method));},15000);
    pending.set(n,{resolve,reject,timer});ws.send(JSON.stringify({id:n,method,params}));
  });
  await send('session.new',{capabilities:{alwaysMatch:{acceptInsecureCerts:true}}});
  const {context}=await send('browsingContext.create',{type:'tab'});
  await send('browsingContext.setViewport',{context,viewport:{width:1440,height:900},devicePixelRatio:1});
  await send('browsingContext.navigate',{context,url:process.env.NDCALC_URL||'http://localhost:8080',wait:'complete'});
  const evaluate=async expression=>{
    const result=await send('script.evaluate',{expression,target:{context},awaitPromise:true});
    if(result.type==='exception')throw new Error(JSON.stringify(result.exceptionDetails));
    return result.result;
  };
  const waitFor=async expression=>{
    for(let i=0;i<150;i++){if((await evaluate(`Boolean(${expression})`)).value)return;await sleep(50);}
    throw new Error('Timed out: '+expression);
  };
  await waitFor('document.querySelector(".sheet")');
  await evaluate(`document.querySelectorAll('button').forEach(b=>{if(b.textContent.trim()==='3D')b.click()})`);
  await waitFor('document.querySelector(".cube-stage")');
  await evaluate(`history.pushState({},'', location.pathname + '?wheel-test' + location.hash); window.__wheelEvents=[];
    document.addEventListener('wheel',e=>setTimeout(()=>__wheelEvents.push({alt:e.altKey,prevented:e.defaultPrevented}),0),{capture:true})`);
  const {value:point}=await evaluate(`JSON.stringify((()=>{const r=document.querySelector('.cube-stage').getBoundingClientRect();
    return {x:Math.round(r.x+r.width/2),y:Math.round(r.y+r.height/2)}})())`);
  const {x,y}=JSON.parse(point);
  const snapshot=async()=>JSON.parse((await evaluate(`JSON.stringify({url:location.href,
    zoom:document.querySelector('input[aria-label="3D zoom"]')?.value,events:window.__wheelEvents})`)).value);
  const before=await snapshot();
  await send('input.performActions',{context,actions:[{type:'key',id:'keys',actions:[{type:'keyDown',value:'\uE00A'}]}]});
  await send('input.performActions',{context,actions:[{type:'wheel',id:'wheel',actions:[{type:'scroll',x,y,deltaX:0,deltaY:-120,duration:50,origin:'viewport'}]}]});
  await send('input.performActions',{context,actions:[{type:'key',id:'keys',actions:[{type:'keyUp',value:'\uE00A'}]}]});
  await sleep(300);
  const after=await snapshot();
  assert.equal(after.url,before.url,'Alt-wheel must not traverse history');
  assert.ok(Number(after.zoom)>Number(before.zoom),'Alt-wheel must zoom the volume');
  assert.ok(after.events.some(e=>e.alt&&e.prevented),'Native wheel default must be prevented');
  console.log('✓ Firefox native Alt-wheel zoom: default prevented; history unchanged');
} finally {
  if(ws)ws.close();for(const p of pending.values())clearTimeout(p.timer);
  if(!launchError){ff.kill('SIGTERM');await Promise.race([exited,sleep(1500)]);
    if(ff.exitCode===null&&ff.signalCode===null){ff.kill('SIGKILL');await exited;}}
  await rm(profile,{recursive:true,force:true});
}
