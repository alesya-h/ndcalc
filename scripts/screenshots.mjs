// Fresh examples in an isolated context; never reads or changes your tables.
import {chromium} from 'playwright';
import {mkdir} from 'node:fs/promises';

const browser=process.env.CHROME_CDP_URL
  ? await chromium.connectOverCDP(process.env.CHROME_CDP_URL)
  : await chromium.launch({headless:true});
const context=await browser.newContext({viewport:{width:1440,height:900},deviceScaleFactor:1,
  colorScheme:'light',reducedMotion:'reduce'});
const page=await context.newPage();
const frame=()=>page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
const button=async name=>{await page.getByRole('button',{name,exact:true}).click();await frame();};
const example=async title=>{
  await button('Home'); await button('Open '+title);
  await page.waitForFunction(title=>document.querySelector('.document-title')?.textContent===title,title);
  await page.getByLabel('Theme',{exact:true}).selectOption('light'); await frame();
};
const capture=async name=>{
  await page.evaluate(()=>document.fonts.ready); await frame();
  await page.locator('.toast').waitFor({state:'hidden'});
  await page.mouse.move(1430,890);
  await page.screenshot({path:`docs/screenshots/${name}.png`,animations:'disabled'});
  console.log(`docs/screenshots/${name}.png`);
};
try {
  await mkdir('docs/screenshots',{recursive:true});
  await page.goto(process.env.NDCALC_URL||'http://localhost:8080'); await page.getByRole('grid').waitFor();
  await example('Product scenario planning');
  await page.getByLabel('X dimension',{exact:true}).selectOption('5');
  await page.getByLabel('Y dimension',{exact:true}).selectOption('1');
  await page.getByLabel('Y dimension',{exact:true}).blur(); await frame();
  await capture('plane');

  await example('OKLCH vs LCH'); await button('3D'); await button('Stack');
  await page.getByRole('checkbox',{name:'3D show labels'}).setChecked(false); await frame();
  await button('Named cells');
  await capture('stack');

  await example('OKLCH vs LCH'); await button('4D');
  await page.getByRole('checkbox',{name:'4D show labels'}).setChecked(false);
  await page.getByRole('spinbutton',{name:'4D Z size'}).fill('2');
  await page.getByLabel('lightness slice coordinate',{exact:true}).fill('3');
  await page.getByLabel('chroma slice coordinate',{exact:true}).fill('3');
  const zoom=page.getByRole('slider',{name:'4D zoom'});
  await zoom.focus(); await page.keyboard.press('Home'); await frame();
  for(let i=0;i<40;i++) await page.keyboard.press('ArrowRight');
  await zoom.blur(); await frame();
  await button('Named cells');
  await capture('hypercube');
} finally {
  await context.close(); await browser.close();
}
