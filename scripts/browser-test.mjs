// Run against `npm run dev` or `npm run serve`.
// Set CHROME_CDP_URL=http://127.0.0.1:9222 to use an existing Chrome;
// otherwise run `npx playwright install chromium` once for a headless browser.
import assert from 'node:assert/strict';
import {chromium} from 'playwright';
import {mkdtemp, rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';

const browser = process.env.CHROME_CDP_URL
  ? await chromium.connectOverCDP(process.env.CHROME_CDP_URL)
  : await chromium.launch({headless:true});
const context = await browser.newContext({viewport:{width:1440,height:900}, acceptDownloads:true});
const page = await context.newPage();
const errors = [];
page.on('pageerror', error => errors.push(error.message));
const directory = await mkdtemp(join(tmpdir(), 'ndcalc-test-'));
const check = async (description, run) => {await run(); console.log('✓ ' + description);};
const coord = () => page.locator('.coordinate-label').textContent();
const text = c => page.locator(`[data-coord='${JSON.stringify(c)}'] .cell-text`).first().textContent();
const press = async (...keys) => {
  for (const key of keys) await page.keyboard.press(key);
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
};
const clickText = label => page.getByRole('button', {name:label, exact:true}).click();
const setChecked = async (locator, checked) => {
  // Reagent's controlled checkbox state is committed on the next render frame.
  if (await locator.isChecked() !== checked) await locator.click();
  await press();
  assert.equal(await locator.isChecked(), checked);
};
const cellCursor = async () => {
  for (let i=0; i<3 && (await page.locator('.cursor-mode').textContent())!=='cell'; i++) await press('H');
};
const edit = async (source, formula = false) => {
  await cellCursor();
  await press(formula ? 'I' : 'Enter');
  if (!formula) await page.getByRole('button',{name:/^≡ Value/}).click();
  await page.getByRole('textbox', {name:'Cell JavaScript source'}).fill(source);
  await press('Control+Enter');
  await page.getByRole('dialog').waitFor({state:'hidden'});
};
const go = async (x,y) => {
  await cellCursor();
  await page.locator('body').click({position:{x:2,y:2}}); // leave input focus
  await press('g', ...String(x).split(''), 'Enter', 'G', ...String(y).split(''), 'Enter');
};
const create = async (title,n) => {
  await clickText('Home');
  await clickText('New table');
  await page.getByRole('textbox', {name:'Table title'}).fill(title);
  await page.getByRole('spinbutton', {name:'Dimension count'}).fill(String(n));
  await clickText('Create table');
  await page.locator('.coordinate-label').waitFor();
};
const addNamed = async (name,source,formula=false) => {
  await page.locator('body').click({position:{x:2,y:2}});
  await press('N');
  await page.getByRole('textbox', {name:'Cell name'}).fill(name);
  if (formula) await page.getByRole('button', {name:/^ƒ Formula/}).click();
  await page.getByRole('textbox', {name:'Cell JavaScript source'}).fill(source);
  await press('Control+Enter');
};
const waitText = async (c,expected) => {
  await page.waitForFunction(({c,expected}) => document.querySelector(`[data-coord='${JSON.stringify(c)}'] .cell-text`)?.textContent === expected, {c,expected});
};
try {
  await page.goto(process.env.NDCALC_URL || 'http://localhost:8080');
  await page.getByRole('grid').waitFor();
  await check('compact layout, larger text, and retained contextual guidance', async () => {
    const layout = await page.evaluate(() => ({
      gridTop: document.querySelector('.grid-container').getBoundingClientRect().top,
      rowHeight: document.querySelector('.sheet td').getBoundingClientRect().height,
      cellFont: parseFloat(getComputedStyle(document.querySelector('.cell-text')).fontSize),
      buttonHeight: document.querySelector('.topbar .button').getBoundingClientRect().height
    }));
    assert.ok(layout.gridTop <= 165);
    assert.ok(Math.abs(layout.rowHeight - 35) < 0.1);
    assert.ok(layout.cellFont >= 15);
    assert.ok(layout.buttonHeight <= 34);
    await clickText('Rules');
    assert.match(await page.locator('.panel-tip').innerText(), /Coordinate predicates receive spread coordinates/);
    await page.getByRole('button',{name:/^Named cells/}).click();
    const body = await page.locator('body').innerText();
    for (const slogan of ['Think beyond the plane','A small language for a bigger canvas.','Every slice, one table.'])
      assert.ok(!body.includes(slogan));
  });
  await check('new formulas are prefilled and the caret follows the arrow', async () => {
    await go(8,8); await press('Shift+Enter');
    const source = page.getByRole('textbox',{name:'Cell JavaScript source'});
    assert.equal(await source.inputValue(), '(a,b,c,d,e,...rest) => ');
    assert.equal(await source.evaluate(el => document.activeElement === el && el.selectionStart === el.value.length && el.selectionEnd === el.value.length), true);
    await page.keyboard.type('a+b'); await press('Control+Enter');
    await waitText([8,8,0,0,0], '16');
    await press('u');
    await press('Enter');
    await page.getByRole('button',{name:/^ƒ Formula/}).click();
    await page.waitForFunction(() => {
      const el = document.querySelector('textarea[aria-label="Cell JavaScript source"]');
      return document.activeElement === el && el.selectionStart === el.value.length;
    });
    assert.equal(await source.inputValue(), '(a,b,c,d,e,...rest) => ');
    await press('Escape'); await go(0,0);
  });
  await check('the exact requested dimension rotation sequence', async () => {
    for (const [key,expected] of [['3',[2,3]],['1',[3,1]],['1',[1,0]],['1',[0,1]],['2',[1,2]],['1',[2,1]],['2',[1,2]]]) {
      await press(key);
      await page.waitForFunction(expected => [...document.querySelectorAll('.plane-toolbar select')].map(el=>+el.value).join() === expected.join(), expected);
    }
  });
  await check('named edits propagate to computed cells', async () => {
    await clickText('Edit named cell multiplier');
    await page.getByRole('textbox', {name:'Cell JavaScript source'}).fill('2');
    await press('Control+Enter');
    await waitText([3,1,0,0,0], '269');
  });
  await check('negative goto, block filling, copy/paste, and undo', async () => {
    await go(-3,-2);
    assert.equal(await coord(), '[-3,-2,0,0,0]');
    await press('v','ArrowRight','ArrowDown');
    assert.equal(await page.locator('.selection-count').textContent(), '4 selected');
    await edit('7');
    for (const [x,y] of [[-3,-2],[-2,-2],[-3,-1],[-2,-1]]) assert.equal(await text([x,y,0,0,0]), '7');
    await press('v','ArrowLeft','ArrowUp','y');
    await go(6,2); await press('p');
    await waitText([6,2,0,0,0], '7');
    await press('u'); await waitText([6,2,0,0,0], '');
    await press('Control+Shift+z'); await waitText([6,2,0,0,0], '7');
  });
  await check('values may be functions; formulas may call them', async () => {
    await go(0,9); await edit('x => x * 3');
    await go(1,9); await edit('(a,b,...rest) => $(0,b,...rest)(14)', true);
    await waitText([1,9,0,0,0], '42');
  });
  await check('named formulas receive their string coordinate', async () => {
    await addNamed('input','21');
    await addNamed('double', 'name => name === "double" ? $("input") * 2 : 0', true);
    await addNamed('__proto__', '42');
    await addNamed('constructor', '99');
    await go(2,9); await edit('(a,b,...rest) => $("double") + 1', true);
    await waitText([2,9,0,0,0], '43');
  });
  await check('cycles show errors without breaking the editor', async () => {
    await go(3,9); await edit('(a,b,...rest) => $(a,b,...rest)', true);
    assert.match(await text([3,9,0,0,0]), /Circular reference/);
    await press('u'); await waitText([3,9,0,0,0], '');
  });
  await check('formatting predicates, named matches, reordering, CSS cascade', async () => {
    await clickText('Rules');
    await clickText('Add formatting rule');
    await page.getByRole('textbox', {name:'Rule name'}).fill('named highlight');
    await page.getByRole('textbox', {name:'Coordinate predicate'}).fill('name => name === "double"');
    await page.getByRole('textbox', {name:'Value predicate'}).fill('v => ["heading"]');
    await clickText('Apply rule');
    await clickText('Edit named highlight');
    assert.equal(await page.getByRole('textbox',{name:'Rule name'}).inputValue(),'named highlight');
    await page.getByRole('textbox',{name:'Rule name'}).fill('named highlight edited');
    await page.getByRole('textbox',{name:'Coordinate predicate'}).fill('name => typeof name === "string" && name === "double"');
    await page.getByRole('textbox',{name:'Value predicate'}).fill('v => v === 42 ? ["heading"] : []');
    await setChecked(page.getByRole('checkbox',{name:'Rule enabled'}),false);
    await page.getByRole('textbox',{name:'Coordinate predicate'}).focus();
    await press('Control+Enter');
    assert.equal(await page.locator('.rule-card').count(),4);
    assert.equal(await page.getByRole('checkbox',{name:'Enable named highlight edited'}).isChecked(),false);
    await setChecked(page.getByRole('checkbox',{name:'Enable named highlight edited'}),true);
    await clickText('Move named highlight edited up');
    const names = await page.locator('.rule-name').allTextContents();
    assert.equal(names[2], 'named highlight edited');
    await page.getByRole('button',{name:/^Named cells/}).click();
    const named = page.locator('.named-card').filter({has:page.getByRole('button',{name:'$ double',exact:true})});
    assert.equal(await named.locator('.heading').count(), 1);
    await clickText('CSS');
    await page.getByRole('textbox',{name:'Table CSS'}).fill('.heading { color: rgb(255, 100, 50); }\n.cell-content { outline: 1px solid rgb(200, 70, 0); }');
    await clickText('Apply CSS');
    const heading = page.locator('[data-coord="[0,0,0,0,0]"] .heading');
    // Current viewport can be below origin; return to it to inspect the stylesheet.
    await go(0,0);
    assert.equal(await heading.evaluate(el => getComputedStyle(el).color), 'rgb(255, 100, 50)');
    await go(8,0);
    const outside = page.locator('[data-coord="[8,0,0,0,0]"] .cell-content');
    const insideHole = page.locator('[data-coord="[7,0,0,0,0]"] .cell-content');
    assert.equal(await outside.evaluate(el=>el.classList.contains('heading')),false);
    assert.equal(await outside.evaluate(el=>getComputedStyle(el).outlineStyle),'none');
    assert.equal(await insideHole.evaluate(el=>el.classList.contains('heading')),true);
    assert.equal(await insideHole.evaluate(el=>getComputedStyle(el).outlineStyle),'solid');
    await go(0,0);
  });
  await check('help and themes are toggleable', async () => {
    await press('?'); assert.equal(await page.locator('.help-sidebar').count(), 1);
    await press('?'); assert.equal(await page.locator('.help-sidebar').count(), 0);
    assert.equal(await page.getByLabel('Theme',{exact:true}).inputValue(),'system');
    await page.emulateMedia({colorScheme:'dark'});
    await page.waitForFunction(()=>document.querySelector('.app').dataset.theme==='dark');
    await page.emulateMedia({colorScheme:'light'});
    await page.waitForFunction(()=>document.querySelector('.app').dataset.theme==='light');
    await page.getByLabel('Theme',{exact:true}).selectOption('light');
    await page.emulateMedia({colorScheme:'dark'});
    assert.equal(await page.locator('.app').getAttribute('data-theme'), 'light');
  });
  await check('3D fits the active volume, allows editing, and synchronizes axes', async () => {
    await page.locator('body').click({position:{x:2,y:2}});
    await press('t');
    const shape = await Promise.all(['X','Y','Z'].map(axis=>page.getByRole('spinbutton',{name:`3D ${axis} size`}).inputValue().then(Number)));
    assert.ok(shape.some(n=>n>3));
    assert.equal(await page.locator('.cube-cell').count(),shape.reduce((a,b)=>a*b));
    await press('Enter'); assert.equal(await page.getByRole('dialog').count(), 1);
    await press('Escape');
    assert.equal(await page.getByRole('button', {name:'Edit',exact:true}).isDisabled(), false);
    await press('Control+z','Control+Shift+z');
    assert.equal(await page.locator('.cube-view').count(),1);
    await page.getByLabel('3D X dimension').selectOption('3');
    await page.getByLabel('3D X dimension').blur();
    await press('ArrowRight');
    assert.equal(await coord(), '[0,0,1,0,0]');
    await press('t'); assert.equal(await page.locator('.hyper-view').count(),1);
    await press('t');
    await page.getByLabel('X dimension', {exact:true}).selectOption('1');
    await page.getByLabel('scenario slice coordinate').fill('0');
    await page.getByLabel('scenario slice coordinate').blur();
  });
  await check('JSON download/upload preserves sources and creates a trusted copy', async () => {
    const downloadPromise = page.waitForEvent('download');
    await clickText('Export');
    const download = await downloadPromise;
    const path = join(directory, 'roundtrip.json'); await download.saveAs(path);
    await page.getByLabel('Import JSON document').setInputFiles(path);
    await page.getByRole('dialog',{name:'Trust this document?'}).waitFor();
    assert.equal(await page.getByRole('dialog',{name:'Trust this document?'}).count(), 1);
    await clickText('Trust & open');
    await go(1,9); await waitText([1,9,0,0,0], '42');
    await clickText('Home'); await page.locator('.document-card').nth(1).waitFor();
    assert.equal(await page.locator('.document-card').count(), 2);
  });
  await check('IndexedDB documents and theme survive reload', async () => {
    await page.reload(); await page.locator('.document-card').first().waitFor();
    assert.equal(await page.locator('.document-card').count(), 2);
    assert.equal(await page.locator('.app').getAttribute('data-theme'), 'light');
    await page.locator('.document-open').first().click();
    await go(2,9); await waitText([2,9,0,0,0], '43');
    if (!await page.locator('.named-panel').count()) await clickText('Named cells');
    for (const [name,value] of [['__proto__','42'],['constructor','99']]) {
      const card = page.locator('.named-card').filter({has:page.getByRole('button',{name:'$ '+name,exact:true})});
      assert.equal(await card.locator('.cell-text').textContent(),value);
    }
  });
  await check('0D has exactly one accessible numeric cell', async () => {
    await create('zero',0);
    assert.equal(await page.locator('td[role=gridcell]').count(), 1);
    await press('ArrowRight','ArrowDown','1'); assert.equal(await coord(), '[]');
    await edit('({answer:42})'); assert.equal(await text([]), '{"answer":42}');
    await addNamed('zero_name', '() => $(0,0,0).answer', true);
    await clickText('Home');
    await page.locator('.document-open').filter({hasText:'zero'}).click();
    assert.equal(await text([]), '{"answer":42}');
  });
  await check('1D through 5D support editing and fixed inactive dimensions', async () => {
    for (let n=1;n<=5;n++) {
      await create('rank-'+n,n);
      assert.equal(await page.getByLabel('X dimension').inputValue(),'1');
      assert.equal(await page.getByLabel('Y dimension').inputValue(),n===1?'0':'2');
      if (n>=3) {
        const input = page.getByLabel(`Dimension ${n} slice coordinate`);
        await input.focus(); await page.keyboard.press('Control+A');
        await page.keyboard.type('-2', {delay:25});
        await input.blur();
      }
      await edit('123n');
      const at = Array(n).fill(0); if (n>=3) at[n-1]=-2;
      assert.equal(await text(at),'123n');
      await press('ArrowRight'); at[0]=1; assert.equal(await coord(),JSON.stringify(at));
      await press('ArrowUp'); if(n>1) at[1]=-1; assert.equal(await coord(),JSON.stringify(at));
    }
  });
  await check('visual mode spans planes and slices for 5D fill, copy, clear, and undo', async () => {
    await create('hyperfill',5);
    await page.locator('body').click({position:{x:2,y:2}});
    await press('v','ArrowRight','ArrowDown','3','ArrowDown','4','ArrowDown','5','ArrowDown','1');
    assert.equal(await page.locator('.mode-badge').textContent(),'VISUAL');
    assert.equal(await page.locator('.selection-count').textContent(),'32 selected');
    assert.equal(await page.locator('.sheet td.selected').count(),4);
    await edit('(a,b,c,d,e) => a+b+c+d+e',true);
    assert.equal(await page.locator('.status-right').innerText().then(t=>t.trim().endsWith('32 cells')),true);
    await page.getByLabel('X dimension',{exact:true}).selectOption('1');
    await page.getByLabel('Y dimension',{exact:true}).selectOption('2');
    for (const d of [3,4,5]) {
      await page.getByLabel(`Dimension ${d} slice coordinate`).fill('0');
      await page.getByLabel(`Dimension ${d} slice coordinate`).blur();
    }
    await waitText([0,0,0,0,0],'0');
    await waitText([1,1,0,0,0],'2');
    await go(0,0); await press('v','ArrowRight','ArrowDown');
    await page.getByLabel('X dimension',{exact:true}).selectOption('3');
    await page.getByLabel('X dimension',{exact:true}).blur();
    for (const d of [3,4,5]) {
      await page.getByLabel(`Dimension ${d} slice coordinate`).fill('1');
      await page.getByLabel(`Dimension ${d} slice coordinate`).blur();
    }
    assert.equal(await page.locator('.selection-count').textContent(),'32 selected');
    await press('y');
    await page.getByLabel('X dimension',{exact:true}).selectOption('1');
    const start = [-4,-3,-2,-1,-5];
    for (const [index,value] of start.entries()) {
      await page.getByLabel(`Dimension ${index+1} slice coordinate`).fill(String(value));
      await page.getByLabel(`Dimension ${index+1} slice coordinate`).blur();
    }
    await press('p'); await waitText(start,'-15');
    assert.match(await page.locator('.status-right').innerText(),/64 cells$/);
    await press('v','ArrowRight','ArrowDown','3','ArrowDown','4','ArrowDown','5','ArrowDown');
    assert.equal(await page.locator('.selection-count').textContent(),'32 selected');
    await press('Delete');
    assert.match(await page.locator('.status-right').innerText(),/32 cells$/);
    assert.equal(await page.locator('.mode-badge').textContent(),'NORMAL');
    await press('u'); assert.match(await page.locator('.status-right').innerText(),/64 cells$/);
  });
  await check('OKLCH vs LCH, stack-only transparency, wheel controls, and persistence', async () => {
    await clickText('Home'); await clickText('Open OKLCH vs LCH');
    await waitText([5,4,3,0],'[5,4,3,0]');
    assert.match(await page.locator('[data-coord="[5,4,3,0]"] .cell-content').evaluate(el=>getComputedStyle(el).backgroundColor),/oklch/);
    assert.match(await page.locator('.status-right').innerText(),/1024 cells$/);
    await page.getByLabel('space slice coordinate').fill('1');
    await page.getByLabel('space slice coordinate').blur();
    assert.match(await page.locator('[data-coord="[5,4,3,1]"] .cell-content').evaluate(el=>getComputedStyle(el).backgroundColor),/^lch/);
    await page.getByLabel('space slice coordinate').fill('0');
    await page.getByLabel('space slice coordinate').blur();
    await page.locator('body').click({position:{x:2,y:2}}); await press('t'); await clickText('Stack');
    assert.equal(await page.locator('.cube-cell').count(),512);
    assert.equal(await page.locator('.cube-layer').count(),8);
    await setChecked(page.getByRole('checkbox',{name:'3D show labels'}),false);
    const selectedBeforeDrag = await coord();
    const rotationBefore = Number(await page.getByRole('slider',{name:'3D rotation'}).inputValue());
    const start = await page.locator('.cube-stage').evaluate(el=>{
      const r=el.getBoundingClientRect();
      for (const [a,b] of [[.5,.5],[.1,.5],[.9,.5],[.3,.7]]) {
        const x=r.x+r.width*a,y=r.y+r.height*b,hit=document.elementFromPoint(x,y);
        if(el.contains(hit) && !hit.closest('.hyperplane-edit,.cell-resize')) return {x,y};
      }
      throw new Error('No unobstructed stack drag point');
    });
    await page.mouse.move(start.x,start.y); await page.mouse.down();
    await page.mouse.move(start.x+90,start.y-45,{steps:10}); await page.mouse.up(); await press();
    assert.notEqual(Number(await page.getByRole('slider',{name:'3D rotation'}).inputValue()),rotationBefore);
    assert.equal(await coord(),selectedBeforeDrag);
    const zoom = page.getByRole('slider',{name:'3D zoom'});
    const beforeWheel = Number(await zoom.inputValue());
    const beforePan = await page.locator('.cube-stage').evaluate(el=>[el.scrollLeft,el.scrollTop]);
    await page.mouse.wheel(80,120); await press();
    assert.equal(Number(await zoom.inputValue()),beforeWheel);
    assert.notDeepEqual(await page.locator('.cube-stage').evaluate(el=>[el.scrollLeft,el.scrollTop]),beforePan);
    await page.keyboard.down('Alt'); await page.mouse.wheel(0,-120); await page.keyboard.up('Alt'); await press();
    assert.ok(Number(await zoom.inputValue()) > beforeWheel);
    const gap = page.getByRole('slider',{name:'3D layer gap'});
    const beforeGap = Number(await gap.inputValue());
    await page.keyboard.down('Control'); await page.mouse.wheel(0,-120); await page.keyboard.up('Control'); await press();
    assert.ok(Number(await gap.inputValue()) > beforeGap);
    const transparency = page.getByRole('slider',{name:'3D transparency'});
    await page.keyboard.down('Shift'); await page.mouse.wheel(0,120); await page.keyboard.up('Shift'); await press();
    assert.ok(Number(await transparency.inputValue()) > 0);
    await transparency.focus(); await press('End');
    assert.equal(await page.locator('.cube-layer').first().evaluate(el=>getComputedStyle(el).opacity),'0');
    await press('Home','ArrowRight');
    assert.equal(await page.locator('.cube-layer').first().evaluate(el=>getComputedStyle(el).opacity),'0.99');
    await page.locator('body').click({position:{x:2,y:2}});
    await edit('() => 9001',true); await waitText([0,0,3,0],'9001');
    await press('u'); await waitText([0,0,3,0],'[0,0,3,0]');
    await press('v','ArrowRight','ArrowDown','PageUp');
    assert.equal(await page.locator('.selection-count').textContent(),'8 selected');
    await edit('(a,b,c) => a+b+c',true); await waitText([1,1,4,0],'6');
    await press('v','ArrowLeft','ArrowUp','PageDown','Delete');
    assert.match(await page.locator('.status-right').innerText(),/1016 cells$/);
    await press('u','u'); await waitText([1,1,4,0],'[1,1,4,0]');
    await page.getByRole('spinbutton',{name:'3D X size'}).fill('4'); await press();
    assert.equal(await page.locator('.cube-cell').count(),256);
    await page.locator('body').click({position:{x:2,y:2}}); await press('F');
    assert.equal(await page.locator('.cube-cell').count(),512);
    await press('f'); assert.equal(await page.getByRole('checkbox',{name:'3D follow current cell'}).isChecked(),true);
    await press('F'); assert.equal(await page.getByRole('checkbox',{name:'3D follow current cell'}).isChecked(),false);
    await clickText('Slices');
    assert.equal(await page.getByRole('slider',{name:'3D transparency'}).count(),0);
    assert.equal(await page.locator('.cube-slice').first().evaluate(el=>getComputedStyle(el).opacity),'1');
    await page.locator('body').click({position:{x:2,y:2}}); await press('l');
    assert.equal(await page.getByRole('checkbox',{name:'3D show labels'}).isChecked(),true);
    await page.locator('.cube-cell[data-coord="[2,3,4,0]"]').click();
    assert.equal(await coord(),'[2,3,4,0]');
    await page.locator('.cube-cell[data-coord="[2,3,4,0]"]').dblclick();
    assert.equal(await page.getByRole('dialog').count(),1);
    await press('Escape'); await clickText('Open in plane');
    await page.getByRole('grid').waitFor(); assert.equal(await coord(),'[2,3,4,0]');
    await page.locator('body').click({position:{x:2,y:2}}); await press('t');
    const before = Number(await zoom.inputValue()); await zoom.focus(); await press('ArrowRight');
    await page.getByRole('spinbutton',{name:'3D X size'}).fill('16');
    await page.getByRole('spinbutton',{name:'3D Y size'}).fill('16');
    await page.getByRole('spinbutton',{name:'3D Z size'}).fill('16'); await press();
    assert.equal(await page.locator('.cube-cell').count(),4096);
    await page.getByRole('spinbutton',{name:'3D Z size'}).fill('17');
    assert.equal(await page.getByRole('spinbutton',{name:'3D Z size'}).inputValue(),'16');
    await clickText('Fit active bounds');
    await page.waitForTimeout(150); await page.reload();
    await page.locator('.document-card').filter({has:page.getByRole('heading',{name:'OKLCH vs LCH',exact:true})}).locator('.document-open').click();
    await page.locator('body').click({position:{x:2,y:2}}); await press('t');
    assert.equal(await page.locator('.cube-cell').count(),512);
    assert.equal(await page.getByRole('checkbox',{name:'3D show labels'}).isChecked(),true);
    assert.equal(Number(await zoom.inputValue()),before+1);
    await clickText('Stack'); assert.equal(await transparency.inputValue(),'1');
    await page.screenshot({path:'/tmp/ndcalc-color-stack.png'});
    await page.locator('body').click({position:{x:2,y:2}}); await press('t');
    assert.equal(await page.locator('.hyper-panel').count(),16);
    assert.equal(await page.locator('.cube-cell').count(),1024);
    assert.equal(await page.getByRole('slider',{name:'4D transparency'}).count(),0);
    assert.equal(await page.locator('.cube-slice').first().evaluate(el=>getComputedStyle(el).opacity),'1');
    const hyperZoom = page.getByRole('slider',{name:'4D zoom'});
    const hBefore = Number(await hyperZoom.inputValue());
    await page.locator('.hyper-stage').hover();
    await page.mouse.wheel(120,180); await press();
    assert.equal(Number(await hyperZoom.inputValue()),hBefore);
    assert.ok(await page.locator('.hyper-stage').evaluate(el=>el.scrollLeft>0 && el.scrollTop>0));
    await page.keyboard.down('Alt'); await page.mouse.wheel(0,-120); await page.keyboard.up('Alt'); await press();
    assert.ok(Number(await hyperZoom.inputValue()) > hBefore);
    await page.screenshot({path:'/tmp/ndcalc-lch-comparison.png'});
  });
  await check('3D dimension keys enqueue existing/new dimensions without collapsing the view', async () => {
    await create('3D queue',5);
    for (const [index,value] of [-1,2,3,4,5].entries()) {
      await page.getByLabel(`Dimension ${index+1} slice coordinate`).fill(String(value));
      await page.getByLabel(`Dimension ${index+1} slice coordinate`).blur();
    }
    await page.locator('body').click({position:{x:2,y:2}}); await press('t','v','PageUp');
    const selected = await coord();
    for (const [key,expected] of [['1',[2,3,1]],['1',[2,3,1]],['2',[3,1,2]],['4',[1,2,4]],['3',[2,4,3]],['0',[4,3,0]],['5',[3,0,5]]]) {
      await press(key);
      const actual = await Promise.all(['X','Y','Z'].map(axis=>page.getByLabel(`3D ${axis} dimension`).inputValue().then(Number)));
      assert.deepEqual(actual,expected);
      assert.equal(await page.locator('.cube-view').count(),1);
      assert.equal(await coord(),selected);
      assert.equal(await page.locator('.mode-badge').textContent(),'VISUAL');
      assert.equal(await page.locator('.selection-count').textContent(),'2 selected');
    }
  });
  await check('new shortcuts, row edges, permutations, and eight-dimensional navigation', async () => {
    await create('shortcut axes',8);
    for (const [x,y] of [[-2,2],[5,2],[-9,3]]) {await go(x,y); await edit('42');}
    await go(0,2); await press('Home'); assert.equal(await coord(),'[-2,2,0,0,0,0,0,0]');
    await press('End'); assert.equal(await coord(),'[5,2,0,0,0,0,0,0]');
    await press('Shift+Home'); assert.equal(await page.locator('.selection-count').textContent(),'8 selected');
    await press('Escape','T');
    assert.equal(await page.getByLabel('X dimension',{exact:true}).inputValue(),'2');
    await press('T'); assert.equal(await page.getByLabel('X dimension',{exact:true}).inputValue(),'1');
    await press('c'); assert.equal(await page.getByRole('textbox',{name:'Table CSS'}).count(),1);
    await press('c'); assert.equal(await page.getByRole('textbox',{name:'Table CSS'}).count(),0);
    assert.equal(await page.locator('.inspector.collapsed').count(),1);
    await clickText('CSS'); assert.equal(await page.getByRole('textbox',{name:'Table CSS'}).count(),1);
    await clickText('CSS'); assert.equal(await page.getByRole('textbox',{name:'Table CSS'}).count(),0);
    await press('r'); assert.equal(await page.getByRole('button',{name:'Add formatting rule'}).count(),1);
    await press('n'); assert.equal(await page.locator('.named-panel').count(),1);
    assert.equal(await page.getByRole('dialog').count(),0);
    await press('N'); assert.equal(await page.getByRole('dialog',{name:'New named cell'}).count(),1);
    await press('Escape','h'); assert.equal(await page.locator('.help-sidebar').count(),1);
    const before = await coord(); await press('j','k','l'); assert.equal(await coord(),before);
    await press('h'); assert.equal(await page.locator('.help-sidebar').count(),0);
    await press('PageUp','Shift+PageUp'); assert.equal(await page.locator('.selection-count').textContent(),'2 selected');
    await press('Escape');
    for (let d=1;d<=8;d++) {await page.getByLabel(`Dimension ${d} slice coordinate`).fill('0'); await page.getByLabel(`Dimension ${d} slice coordinate`).blur();}
    await page.locator('body').click({position:{x:2,y:2}});
    await press('Control+Shift+ArrowUp','Control+Shift+ArrowRight','Alt+Shift+ArrowUp','Alt+Shift+ArrowRight',
                'Control+Alt+Shift+ArrowUp','Control+Alt+Shift+ArrowRight','Shift+ArrowRight','Shift+ArrowDown');
    assert.equal(await coord(),'[1,1,1,1,1,1,1,1]');
    assert.equal(await page.locator('.selection-count').textContent(),'256 selected');
    await press('Escape');
    for (let d=1;d<=8;d++) {await page.getByLabel(`Dimension ${d} slice coordinate`).fill('0'); await page.getByLabel(`Dimension ${d} slice coordinate`).blur();}
    await page.locator('body').click({position:{x:2,y:2}}); await press('3','PageUp');
    assert.equal(await coord(),'[1,0,0,0,0,0,0,0]');
    await press('t');
    const tabShortcutsAllowed = await page.evaluate(()=>[false,true].every(shiftKey=>
      document.documentElement.dispatchEvent(new KeyboardEvent('keydown',{key:'t',ctrlKey:true,shiftKey,bubbles:true,cancelable:true}))));
    assert.equal(tabShortcutsAllowed,true);
    const orders = new Set(); const c = await coord();
    for (let i=0;i<6;i++) {await press('T'); orders.add(JSON.stringify(await Promise.all(['X','Y','Z'].map(a=>page.getByLabel(`3D ${a} dimension`).inputValue()))));}
    assert.equal(orders.size,6); assert.equal(await coord(),c);
  });
  await check('4D shows a two-direction slice matrix, edits, navigation, permutations, and persistence', async () => {
    await create('4D colors',4);
    await page.locator('body').click({position:{x:2,y:2}});
    await press('v','ArrowRight','ArrowDown','PageUp','Control+ArrowRight');
    assert.equal(await page.locator('.selection-count').textContent(),'16 selected');
    await edit('(a,b,c,d) => [a,b,c,d]',true);
    await press('r'); await clickText('Add formatting rule');
    await page.getByRole('textbox',{name:'Rule name'}).fill('4D coordinates');
    await page.getByRole('textbox',{name:'Coordinate predicate'}).fill('(...coord) => true');
    await page.getByRole('textbox',{name:'Value predicate'}).fill("v => Array.isArray(v) ? `background: oklch(${25+v[0]*45}% ${0.07+v[1]*0.14} ${v[2]*180+v[3]*90}); color: white;` : ''");
    await clickText('Apply rule');
    await page.locator('body').click({position:{x:2,y:2}}); await press('t','t');
    assert.equal(await page.locator('.hyper-panel').count(),4);
    assert.equal(await page.locator('.hyper-column-label').count(),2);
    assert.equal(await page.locator('.hyper-row-label').count(),2);
    assert.equal(await page.locator('.cube-cell').count(),16);
    assert.equal((await page.locator('.cube-cell').evaluateAll(els=>new Set(els.map(el=>el.dataset.coord)).size)),16);
    assert.equal(await page.locator('.cube-current').count(),1);
    await setChecked(page.getByRole('checkbox',{name:'4D show labels'}),true);
    await page.locator('.toast').waitFor({state:'hidden'});
    await page.screenshot({path:'/tmp/ndcalc-4d.png'});
    const orders = new Set(); const selected = await coord();
    await page.locator('body').click({position:{x:2,y:2}});
    for (let i=0;i<24;i++) {await press('T'); orders.add(JSON.stringify(await Promise.all(['X','Y','Z','W'].map(a=>page.getByLabel(`4D ${a} dimension`).inputValue()))));}
    assert.equal(orders.size,24); assert.equal(await coord(),selected);
    await page.getByRole('spinbutton',{name:'4D W size'}).fill('1'); await press();
    assert.equal(await page.locator('.cube-cell').count(),8);
    await clickText('Fit active bounds'); assert.equal(await page.locator('.cube-cell').count(),16);
    await page.locator('.cube-cell[data-coord="[0,0,0,0]"]').click();
    await press('Shift+PageUp','Control+Shift+ArrowRight');
    assert.equal(await coord(),'[0,0,1,1]');
    assert.equal(await page.locator('.selection-count').textContent(),'4 selected');
    await edit('(a,b,c,d) => a+b+c+d',true); await waitText([0,0,1,1],'2');
    await press('u'); await waitText([0,0,1,1],'[0,0,1,1]');
    assert.equal(await page.locator('.hyper-view').count(),1);
    await setChecked(page.getByRole('checkbox',{name:'4D show labels'}),false);
    await page.waitForTimeout(150); await page.reload();
    await page.locator('.document-card').filter({has:page.getByRole('heading',{name:'4D colors',exact:true})}).locator('.document-open').click();
    await clickText('4D');
    assert.equal(await page.locator('.cube-cell').count(),16);
    assert.equal(await page.getByRole('checkbox',{name:'4D show labels'}).isChecked(),false);
  });
  await check('null axes stay one cell deep in 3D and 4D', async () => {
    await page.locator('body').click({position:{x:2,y:2}}); await press('0');
    assert.equal(await page.getByLabel('4D W dimension').inputValue(),'0');
    assert.equal(await page.getByRole('spinbutton',{name:'4D W size'}).inputValue(),'1');
    assert.equal(await page.getByRole('spinbutton',{name:'4D W size'}).isDisabled(),true);
    assert.equal(await page.locator('.hyper-column-label').count(),1);
    const point = await coord(); await press('Control+Shift+ArrowRight'); assert.equal(await coord(),point);
    await clickText('3D');
    await page.getByLabel('3D Z dimension').selectOption('0'); await page.getByLabel('3D Z dimension').blur(); await press();
    assert.equal(await page.getByRole('spinbutton',{name:'3D Z size'}).inputValue(),'1');
    assert.equal(await page.locator('.cube-layer,.cube-slice').count(),1);
    await press('PageUp'); assert.equal(await coord(),point);
  });
  await check('shared full queue preserves W across 4D, 3D, plane, and reload', async () => {
    await create('shared queue',8); await clickText('4D');
    await page.getByLabel('4D W dimension').selectOption('8'); await page.getByLabel('4D W dimension').blur(); await press();
    for (const name of ['3D','Plane','4D','Plane','3D','4D']) {
      await clickText(name);
      if (name==='4D') assert.equal(await page.getByLabel('4D W dimension').inputValue(),'8');
    }
    await page.waitForTimeout(200); await page.reload(); await page.locator('.document-card').first().waitFor();
    await page.locator('.document-card').filter({has:page.getByRole('heading',{name:'shared queue',exact:true})}).locator('.document-open').click();
    await clickText('4D'); assert.equal(await page.getByLabel('4D W dimension').inputValue(),'8');
  });
  await check('aliases, editable hyperplane headers, coordinate objects, dependencies, and persistence', async () => {
    await create('header references',4); await clickText('Dimensions');
    for (const [index,alias] of ['date','department','scenario','currency'].entries())
      await page.getByLabel(`Axis ${index+1} alias`,{exact:true}).fill(alias);
    await clickText('Apply');
    assert.match(await page.locator('.dimension-chips').innerText(),/date/);
    assert.equal(await page.getByLabel('X dimension',{exact:true}).locator('option:checked').innerText(),'date');
    assert.equal(await page.locator('.sheet thead tr').count(),2);
    assert.equal(await page.locator('.sheet tbody tr').first().locator('th').count(),2);
    await go(41,0); await edit('10');
    await page.getByRole('button',{name:'Edit hyperplane cell date at 41',exact:true}).click();
    await page.getByRole('textbox',{name:'Cell JavaScript source'}).fill("'Earlier'"); await press('Control+Enter');
    await go(42,0);
    await page.getByRole('button',{name:'Edit hyperplane cell date at 42',exact:true}).click();
    await page.getByRole('textbox',{name:'Cell JavaScript source'}).fill("'Today'"); await press('Control+Enter');
    await page.getByRole('button',{name:'Edit hyperplane cell department at 0',exact:true}).click();
    await page.getByRole('textbox',{name:'Cell JavaScript source'}).fill("'Design'"); await press('Control+Enter');
    for (const [source,expected] of [["=> $(_.offset('date',-1))+1",'11'],["=> _.offset('date',-1).value()+1",'11'],
                                    ["=> $$('department',_)",'Design'],["=> _.value('department')",'Design'],
                                    ["=> $$['department']",'Design'],["=> $$.department",'Design'],["=> $$(1,42)",'Today']]) {
      await page.locator('body').click({position:{x:2,y:2}}); await edit(source,true); await waitText([42,0,0,0],expected);
    }
    await page.getByRole('button',{name:'Edit hyperplane cell date at 42',exact:true}).focus(); await press('I');
    await page.getByRole('textbox',{name:'Cell JavaScript source'}).fill("=> _.offset('date',-1).value() + ' +1'");
    await press('Control+Enter'); await waitText([42,0,0,0],'Earlier +1');
    await page.locator('body').click({position:{x:2,y:2}}); await edit('=> $$.department',true);
    await page.getByRole('button',{name:'Edit hyperplane cell department at 0',exact:true}).click();
    await page.getByRole('textbox',{name:'Cell text'}).fill('QA'); await press('Control+Enter');
    await waitText([42,0,0,0],'QA'); await press('u'); await waitText([42,0,0,0],'Design');
    await cellCursor();
    await clickText('4D');
    assert.ok(await page.locator('.hyper-column-value').count());
    assert.ok(await page.locator('.hyper-row-value').count());
    assert.ok(await page.getByRole('button',{name:'Edit hyperplane cell date at 41',exact:true}).count());
    await page.screenshot({path:'/tmp/ndcalc-hyperplane-headers.png'});
    await clickText('Plane'); await page.waitForTimeout(200); await page.reload();
    await page.locator('.document-card').first().waitFor();
    await page.locator('.document-card').filter({has:page.getByRole('heading',{name:'header references',exact:true})}).locator('.document-open').click();
    await waitText([42,0,0,0],'Design');
    assert.equal(await page.getByLabel('X dimension',{exact:true}).locator('option:checked').innerText(),'date');
    assert.equal(await page.getByRole('button',{name:'Edit hyperplane cell date at 42',exact:true}).locator('.cell-text').innerText(),'Earlier +1');
  });
  await check('document-path navigation, 2×2 corners, Text mode, natural widths, and width persistence', async () => {
    await create('presentation test',4);
    assert.equal(await page.locator('.home-button').evaluate(el=>el.previousElementSibling.classList.contains('brand')),true);
    assert.equal(await page.locator('.dimension-bar').evaluate(el=>el.firstElementChild.textContent.trim()),'Dimensions:');
    await page.getByRole('button',{name:'Configure dimensions',exact:true}).click();
    assert.equal(await page.getByRole('spinbutton',{name:'Dimension count'}).inputValue(),'4'); await press('Escape');
    await page.locator('body').click({position:{x:2,y:2}});
    assert.equal(await page.locator('.sheet thead .corner-diagonal').count(),2);
    assert.equal(await page.locator('.sheet thead [colspan]').count(),0);
    const verbatim='He said "hello".\nQuotes, `ticks`, ${notExecuted}, and \\slashes.';
    await press('Alt+Enter'); await page.getByRole('textbox',{name:'Cell text'}).fill(verbatim); await press('Control+Enter');
    await waitText([0,0,0,0],verbatim);
    await press('Enter'); assert.equal(await page.getByRole('textbox',{name:'Cell text'}).inputValue(),verbatim);
    await page.getByRole('button',{name:/^≡ Value/}).click();
    assert.equal(await page.getByRole('textbox',{name:'Cell JavaScript source'}).inputValue(),JSON.stringify(verbatim));
    await page.getByRole('button',{name:/^≡ Text/}).click();
    await page.getByRole('spinbutton',{name:'Cell width',exact:true}).fill('220'); await press('Control+Enter');
    assert.equal(await page.locator('[data-coord="[0,0,0,0]"] .width-dot').count(),1);
    assert.ok(Math.abs(await page.locator('[data-coord="[0,0,0,0]"]').evaluate(el=>el.getBoundingClientRect().width)-220)<1);
    await go(0,1); await press('Alt+Enter'); await page.getByRole('textbox',{name:'Cell text'}).fill('W'.repeat(100)); await press('Control+Enter');
    assert.ok(Math.abs(await page.locator('[data-coord="[0,0,0,0]"]').evaluate(el=>el.getBoundingClientRect().width)-400)<1);
    await press('Enter'); await page.getByRole('spinbutton',{name:'Cell width',exact:true}).fill('155'); await press('Control+Enter');
    assert.ok(Math.abs(await page.locator('[data-coord="[0,0,0,0]"]').evaluate(el=>el.getBoundingClientRect().width)-220)<1);
    await go(3,0);
    const handle=page.locator('[data-coord="[3,0,0,0]"] .cell-resize'), r=await handle.boundingBox();
    await page.mouse.move(r.x+r.width/2,r.y+r.height/2); await page.mouse.down();
    await page.mouse.move(r.x+r.width/2+100,r.y+r.height/2,{steps:5}); await page.mouse.up(); await press();
    assert.equal(await page.locator('[data-coord="[3,0,0,0]"] .width-dot').count(),1);
    assert.equal(await page.locator('[data-coord="[3,0,0,0]"] .cell-text').innerText(),'');
    await page.waitForTimeout(200); await page.reload(); await page.locator('.document-card').first().waitFor();
    await page.locator('.document-card').filter({has:page.getByRole('heading',{name:'presentation test',exact:true})}).locator('.document-open').click();
    assert.equal(await page.locator('[data-coord="[3,0,0,0]"] .width-dot').count(),1);
    await page.getByRole('button',{name:'ndcalc home',exact:true}).click(); await page.getByRole('heading',{name:'Tables',exact:true}).waitFor();
  });
  await check('H focuses hyperrows/hypercolumns, constrains motion, fills/copies/pastes, and keeps borders visible over rules', async () => {
    await create('hyper selection',4); await edit('7'); await clickText('Rules'); await page.getByRole('button',{name:'Add formatting rule'}).click();
    await page.getByRole('textbox',{name:'Rule name'}).fill('solid background');
    await page.getByRole('textbox',{name:'Coordinate predicate'}).fill('()=>true');
    await page.getByRole('textbox',{name:'Value predicate'}).fill("()=> 'background: magenta'"); await press('Control+Enter');
    await page.locator('body').click({position:{x:2,y:2}}); await press('v','ArrowRight','ArrowDown');
    const border=await page.locator('.sheet td.selected').first().evaluate(el=>getComputedStyle(el,'::before').borderTopStyle);
    assert.equal(border,'solid'); await press('Escape'); await go(0,0);
    await press('H'); assert.equal(await page.locator('.cursor-mode').textContent(),'hyperrow');
    assert.equal(await page.locator('.sheet .cursor-secondary').count(),3);
    assert.equal(await page.locator('.sheet .cursor-primary').count(),1);
    await press('ArrowDown'); assert.equal(await coord(),'D1(0)');
    await press('v','ArrowRight','ArrowRight'); assert.equal(await page.locator('.selection-count').textContent(),'3 selected');
    await press('Alt+Enter'); await page.getByRole('textbox',{name:'Cell text'}).fill('Hello'); await press('Control+Enter');
    const h=(d,c)=>page.locator(`[data-hyperplane='[${d},${c}]'] .cell-text`).first();
    for (let c=0;c<3;c++) assert.equal(await h(1,c).textContent(),'Hello');
    await press('v','ArrowLeft','ArrowLeft','y','H','p');
    assert.equal(await page.locator('.cursor-mode').textContent(),'hypercolumn');
    for (let c=0;c<3;c++) assert.equal(await h(2,c).textContent(),'Hello');
    await press('v','ArrowDown','ArrowDown','Delete');
    for (let c=0;c<3;c++) assert.equal(await h(2,c).textContent(),'');
    await press('u'); assert.equal(await h(2,0).textContent(),'Hello');
    await press('H','p');
    for (let c=0;c<3;c++) await waitText([c,2,0,0],'Hello');
    await page.screenshot({path:'/tmp/ndcalc-cell-hypercursor.png'});
  });
  await check('native touchscreen pan/pinch in plane, 3D Stack, and 4D; captured Alt-wheel prevents browser defaults', async () => {
    await create('touch gestures',4);
    const client=await context.newCDPSession(page);
    const touch=async(type,points)=>{await client.send('Input.dispatchTouchEvent',{type,touchPoints:points.map(([x,y],id)=>({x,y,id}))}); await press();};
    const swipe=async locator=>{
      const r=await locator.boundingBox(),x=r.x+r.width*.6,y=r.y+r.height*.6;
      await touch('touchStart',[[x,y]]); await touch('touchMove',[[x-120,y-120]]); await touch('touchEnd',[]);
    };
    const pinch=async locator=>{
      const r=await locator.boundingBox(),x=r.x+r.width*.5,y=r.y+r.height*.5;
      await touch('touchStart',[[x-50,y],[x+50,y]]); await touch('touchMove',[[x-75,y],[x+75,y]]); await touch('touchEnd',[]);
    };
    const firstX=await page.locator('.sheet thead tr').first().locator('th').nth(2).textContent();
    await swipe(page.locator('.grid-container'));
    assert.notEqual(await page.locator('.sheet thead tr').first().locator('th').nth(2).textContent(),firstX);
    await pinch(page.locator('.grid-container'));
    assert.ok(Number(await page.locator('.sheet').evaluate(el=>getComputedStyle(el).zoom))>1);
    await clickText('3D'); await clickText('Stack');
    const rotation=await page.getByRole('slider',{name:'3D rotation'}).inputValue();
    const before=await page.locator('.cube-stage').evaluate(el=>[el.scrollLeft,el.scrollTop]);
    await swipe(page.locator('.cube-stage'));
    assert.notDeepEqual(await page.locator('.cube-stage').evaluate(el=>[el.scrollLeft,el.scrollTop]),before);
    assert.equal(await page.getByRole('slider',{name:'3D rotation'}).inputValue(),rotation);
    const zoom=Number(await page.getByRole('slider',{name:'3D zoom'}).inputValue()); await pinch(page.locator('.cube-stage'));
    assert.ok(Number(await page.getByRole('slider',{name:'3D zoom'}).inputValue())>zoom);
    await clickText('4D');
    for (const [axis,size] of [['X','8'],['Y','4'],['Z','4'],['W','4']]) {
      await page.getByRole('spinbutton',{name:`4D ${axis} size`}).fill(size); await press();
    }
    await swipe(page.locator('.hyper-stage'));
    assert.ok(await page.locator('.hyper-stage').evaluate(el=>el.scrollLeft>0 && el.scrollTop>0));
    const z=Number(await page.getByRole('slider',{name:'4D zoom'}).inputValue()); await pinch(page.locator('.hyper-stage'));
    assert.ok(Number(await page.getByRole('slider',{name:'4D zoom'}).inputValue())>z);
    assert.equal(await page.locator('.hyper-stage').evaluate(el=>{const e=new WheelEvent('wheel',{deltaY:-120,altKey:true,bubbles:true,cancelable:true});el.dispatchEvent(e);return e.defaultPrevented;}),true);
    await client.detach();
  });
  await check('realistic hypertables, animated formatting, and a live ledger control', async () => {
    await page.emulateMedia({reducedMotion:'no-preference'});
    for (const [title,count] of [['Heat diffusion',768],['Membrane eigenmodes',576],['Product scenario planning',1296],
                                 ['Beam design envelope',1440],['Double-entry ledger',216],['Interference atelier',1728]]) {
      await clickText('Home'); await clickText(`Open ${title}`);
      assert.equal(await page.locator('.document-title').innerText(),title);
      assert.match(await page.locator('.status-right').innerText(),new RegExp(`${count} cells$`));
      assert.equal(await page.locator('.cell-error,.format-warning').count(),0);
      assert.ok(await page.getByRole('button',{name:'$ axes',exact:true}).count());
      if (title==='Heat diffusion') {
        assert.equal(await page.locator('[data-coord="[0,0,0,0]"] .cell-content').evaluate(el=>getComputedStyle(el).animationName),'nd-thermal');
      }
      if (title==='Double-entry ledger') {
        await go(0,5); await waitText([0,5,0,0],'0');
        await go(0,0); const cash=Number(await text([0,0,0,0])); await edit(`() => ${cash+100}`,true);
        await waitText([0,5,0,0],'100');
        assert.equal(await page.locator('[data-coord="[0,5,0,0]"] .cell-content').evaluate(el=>getComputedStyle(el).animationName),'nd-audit');
        await press('u'); await waitText([0,5,0,0],'0');
      }
      if (title==='Interference atelier') {
        const cell=page.locator('[data-coord="[0,0,0,0]"] .cell-content');
        assert.match(await cell.evaluate(el=>getComputedStyle(el).backgroundImage),/conic-gradient/);
        assert.equal(await cell.evaluate(el=>getComputedStyle(el).animationName),'nd-art-flow');
        assert.ok(await cell.evaluate(el=>el.getAnimations().length > 0));
        await page.emulateMedia({reducedMotion:'reduce'});
        assert.equal(await cell.evaluate(el=>getComputedStyle(el).animationName),'none');
        await page.emulateMedia({reducedMotion:'no-preference'});
        await clickText('4D'); await page.screenshot({path:'/tmp/ndcalc-art-4d.png'});
      }
    }
    await page.getByLabel('Theme',{exact:true}).selectOption('system'); await press();
    await page.waitForFunction(()=>document.querySelector('.app').dataset.themeChoice==='system');
    await page.waitForFunction(()=>new Promise(resolve=>{
      const open=indexedDB.open('ndcalc');
      open.onsuccess=()=>{const db=open.result, request=db.transaction('settings').objectStore('settings').get('preferences');
        request.onsuccess=()=>{resolve(request.result?.theme==='system'); db.close();};};
    }));
    await page.reload(); await page.locator('.document-card').first().waitFor();
    assert.equal(await page.getByLabel('Theme',{exact:true}).inputValue(),'system');
    await page.emulateMedia({colorScheme:'light'});
    await page.waitForFunction(()=>document.querySelector('.app').dataset.theme==='light');
  });
  assert.deepEqual(errors, []);
  console.log('\nAll browser workflows passed.');
} catch (error) {
  console.error('Browser errors:', errors);
  console.error('Page:', (await page.locator('body').innerText()).slice(-2000));
  await page.screenshot({path:'/tmp/ndcalc-e2e-failure.png'});
  throw error;
} finally {
  await context.close();
  await browser.close(); // For CDP, disconnect the client without closing the user's browser.
  await rm(directory,{recursive:true,force:true});
}
