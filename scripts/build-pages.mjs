// Run after `shadow-cljs release app`; keep deployment separate from source.
import {cp,mkdir,readdir,readFile,rm,writeFile} from 'node:fs/promises';
import {resolve,join} from 'node:path';

const source=resolve('public'), output=resolve('target/gh-pages');
const {version}=JSON.parse(await readFile('package.json','utf8'));
const modules=(await readdir(join(source,'js'))).filter(name=>name.endsWith('.js')).sort();
if(!modules.includes('main.js')) throw new Error('Missing release: run npm run build first.');
await rm(output,{recursive:true,force:true});
await mkdir(join(output,'js'),{recursive:true});
for(const name of ['index.html','style.css','favicon.svg','fonts'])
  await cp(join(source,name),join(output,name),{recursive:true});
for(const name of modules) await cp(join(source,'js',name),join(output,'js',name));
await writeFile(join(output,'.nojekyll'),'');
await writeFile(join(output,'build-info.json'),JSON.stringify({
  app:'ndcalc',version,sourceRevision:process.env.SOURCE_REVISION||null
},null,2)+'\n');
console.log(`Static release: ${output} (${modules.length} JavaScript module(s), no dev runtime)`);
