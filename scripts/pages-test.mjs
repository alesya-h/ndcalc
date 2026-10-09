// Smoke-test exactly the staged release at a GitHub Pages-style project path.
import assert from 'node:assert/strict';
import http from 'node:http';
import {readFile,stat} from 'node:fs/promises';
import {resolve,extname,sep} from 'node:path';
import {chromium} from 'playwright';

const root=resolve('target/gh-pages'), prefix='/ndcalc/';
const types={'.html':'text/html','.js':'text/javascript','.css':'text/css',
  '.svg':'image/svg+xml','.json':'application/json','.woff2':'font/woff2'};
await stat(resolve(root,'index.html')); // Fail before starting a server if not staged.
const server=http.createServer(async(req,res)=>{
  try {
    const pathname=new URL(req.url,'http://localhost').pathname;
    if(!pathname.startsWith(prefix)){res.writeHead(404).end();return;}
    const file=resolve(root,decodeURIComponent(pathname.slice(prefix.length))||'index.html');
    if(!file.startsWith(root+sep)){res.writeHead(403).end();return;}
    res.writeHead(200,{'Content-Type':types[extname(file)]||'application/octet-stream'});
    res.end(await readFile(file));
  } catch {res.writeHead(404).end();}
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const url=process.env.NDCALC_URL||`http://127.0.0.1:${server.address().port}${prefix}`;
let browser,context;
try {
  browser=process.env.CHROME_CDP_URL
    ? await chromium.connectOverCDP(process.env.CHROME_CDP_URL)
    : await chromium.launch({headless:true});
  context=await browser.newContext({viewport:{width:1440,height:900}});
  const page=await context.newPage(), errors=[],requests=[],failed=[];
  page.on('pageerror',error=>errors.push(error.message));
  page.on('request',request=>requests.push(request.url()));
  page.on('requestfailed',request=>failed.push(request.url()));
  page.on('response',response=>{if(response.status()>=400)failed.push(response.url());});
  await page.goto(url); await page.getByRole('grid').waitFor();
  const fonts=await page.evaluate(async()=>{
    const loaded=await Promise.all([document.fonts.load('15px "DM Sans"'),document.fonts.load('15px "IBM Plex Mono"')]);
    return loaded.map(faces=>faces.length>0&&faces.every(face=>face.status==='loaded'));
  });
  assert.deepEqual(fonts,[true,true]);
  const tableURL=page.url(); assert.match(new URL(tableURL).hash,/^#\/table\/[^/]+$/);
  await page.getByRole('button',{name:'Edit',exact:true}).click();
  await page.getByRole('button',{name:/^≡ Value/}).click();
  await page.getByRole('textbox',{name:'Cell JavaScript source'}).fill('21 * 2');
  await page.keyboard.press('Control+Enter'); await page.getByRole('dialog').waitFor({state:'hidden'});
  await page.waitForFunction(()=>document.querySelector('[data-coord="[0,0,0,0,0]"] .cell-text')?.textContent==='42');
  await page.waitForFunction(()=>document.querySelector('.save-state')?.textContent==='Saved locally');
  await page.reload(); await page.getByRole('grid').waitFor(); assert.equal(page.url(),tableURL);
  assert.equal(await page.locator('[data-coord="[0,0,0,0,0]"] .cell-text').textContent(),'42');
  await page.getByRole('button',{name:'3D',exact:true}).click(); await page.locator('.cube-stage').waitFor();
  await page.getByRole('button',{name:'4D',exact:true}).click(); await page.locator('.hyper-panel').first().waitFor();
  await page.getByRole('button',{name:'Home',exact:true}).click();
  await page.getByRole('heading',{name:'Tables',exact:true}).waitFor(); assert.equal(new URL(page.url()).hash,'');
  assert.deepEqual(errors,[]); assert.deepEqual(failed,[]);
  for(const request of requests){
    const asset=new URL(request); assert.equal(asset.origin,new URL(url).origin);
    assert.ok(asset.pathname.startsWith(prefix),`Asset escaped project path: ${request}`);
  }
  assert.ok(requests.some(request=>request.endsWith('.woff2')));
  console.log(`✓ Pages release at ${url}: project-path assets/fonts, local-only requests, editing, refresh, and 3D/4D`);
} finally {
  await context?.close(); await browser?.close();
  await new Promise(resolve=>server.close(resolve));
}
