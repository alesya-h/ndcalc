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
const edit = async (source, formula = false) => {
  await press(formula ? 'f' : 'Enter');
  await page.getByRole('textbox', {name:'Cell JavaScript source'}).fill(source);
  await press('Control+Enter');
  await page.getByRole('dialog').waitFor({state:'hidden'});
};
const go = async (x,y) => {
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
    await go(8,8); await press('f');
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
    await page.getByRole('checkbox',{name:'Rule enabled'}).uncheck();
    await page.getByRole('textbox',{name:'Coordinate predicate'}).focus();
    await press('Control+Enter');
    assert.equal(await page.locator('.rule-card').count(),4);
    assert.equal(await page.getByRole('checkbox',{name:'Enable named highlight edited'}).isChecked(),false);
    await page.getByRole('checkbox',{name:'Enable named highlight edited'}).check();
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
    await clickText('Light theme'); assert.equal(await page.locator('.app').getAttribute('data-theme'), 'light');
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
    await press('t');
    await page.getByLabel('X dimension', {exact:true}).selectOption('1');
    await page.getByLabel('Dimension 3 slice coordinate').fill('0');
    await page.getByLabel('Dimension 3 slice coordinate').blur();
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
    await page.getByRole('button',{name:/^Named cells/}).click();
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
  await check('OKLCH example, configurable volume, slices, picking, camera, and persistence', async () => {
    await clickText('Home'); await clickText('Open OKLCH color cube');
    await waitText([5,4,3],'[5,4,3]');
    const palette = page.locator('[data-coord="[5,4,3]"] .cell-content');
    assert.match(await palette.evaluate(el=>getComputedStyle(el).backgroundColor),/oklch/);
    assert.match(await page.locator('.status-right').innerText(),/512 cells$/);
    await page.locator('body').click({position:{x:2,y:2}}); await press('t');
    assert.equal(await page.locator('.cube-cell').count(),512);
    assert.equal(await page.locator('.cube-layer').count(),8);
    assert.match(await page.locator('.cube-ranges').innerText(),/8 × 8 × 8/);
    await page.getByRole('checkbox',{name:'3D show labels'}).uncheck();
    await page.locator('.toast').waitFor({state:'hidden'});
    await page.screenshot({path:'/tmp/ndcalc-color-stack.png'});
    const selectedBeforeDrag = await coord();
    const rotationBefore = Number(await page.getByRole('slider',{name:'3D rotation'}).inputValue());
    const tiltBefore = Number(await page.getByRole('slider',{name:'3D tilt'}).inputValue());
    const stage = await page.locator('.cube-stage').boundingBox();
    const start = {x:stage.x+stage.width/2,y:stage.y+stage.height/2};
    await page.mouse.move(start.x,start.y); await page.mouse.down();
    await page.mouse.move(start.x+90,start.y-45,{steps:10}); await page.mouse.up(); await press();
    assert.notEqual(Number(await page.getByRole('slider',{name:'3D rotation'}).inputValue()),rotationBefore);
    assert.notEqual(Number(await page.getByRole('slider',{name:'3D tilt'}).inputValue()),tiltBefore);
    assert.equal(await coord(),selectedBeforeDrag);
    const transparency = page.getByRole('slider',{name:'3D transparency'});
    await transparency.focus(); await press('End');
    assert.equal(await page.locator('.cube-layer').first().evaluate(el=>getComputedStyle(el).opacity),'0');
    await press('Home','ArrowRight');
    assert.equal(await page.locator('.cube-layer').first().evaluate(el=>getComputedStyle(el).opacity),'0.99');
    await page.locator('body').click({position:{x:2,y:2}});
    await edit('() => 9001',true); await waitText([0,0,3],'9001');
    assert.equal(await page.locator('.cube-view').count(),1);
    await press('u'); await waitText([0,0,3],'[0,0,3]');
    await press('Control+Shift+z'); await waitText([0,0,3],'9001');
    await press('u','v','ArrowRight','ArrowDown','PageUp');
    assert.equal(await page.locator('.selection-count').textContent(),'8 selected');
    await edit('(a,b,c) => a+b+c',true); await waitText([1,1,4],'6');
    await press('v','ArrowLeft','ArrowUp','PageDown','Delete');
    assert.match(await page.locator('.status-right').innerText(),/504 cells$/);
    await press('u','u'); await waitText([1,1,4],'[1,1,4]');
    await page.getByRole('spinbutton',{name:'3D X size'}).fill('4');
    assert.equal(await page.locator('.cube-cell').count(),256);
    assert.equal(await page.getByRole('checkbox',{name:'3D follow current cell'}).isChecked(),true);
    await clickText('Fit active bounds'); assert.equal(await page.locator('.cube-cell').count(),512);
    await page.getByLabel('Dimension 3 slice coordinate').fill('3');
    await page.getByLabel('Dimension 3 slice coordinate').blur();
    await clickText('Next Z slice'); assert.equal(await coord(),'[1,1,4]');
    await press('PageDown'); assert.equal(await coord(),'[1,1,3]');
    await press('PageUp'); assert.equal(await coord(),'[1,1,4]');
    await clickText('Slices'); await page.getByRole('checkbox',{name:'3D show labels'}).check();
    assert.equal(await page.locator('.cube-slice').count(),8);
    assert.equal(await page.locator('.cube-cell').count(),512);
    await page.locator('.cube-cell[data-coord="[2,3,4]"]').click();
    assert.equal(await coord(),'[2,3,4]');
    await page.screenshot({path:'/tmp/ndcalc-color-slices.png'});
    await page.locator('.cube-cell[data-coord="[2,3,4]"]').dblclick();
    assert.equal(await page.getByRole('dialog').count(),1);
    await press('Escape'); assert.equal(await page.locator('.cube-view').count(),1);
    await clickText('Open in plane');
    await page.getByRole('grid').waitFor(); assert.equal(await coord(),'[2,3,4]');
    await page.locator('body').click({position:{x:2,y:2}}); await press('t');
    assert.equal(await page.getByRole('button',{name:'Slices',exact:true}).getAttribute('aria-pressed'),'true');
    const zoom = page.getByRole('slider',{name:'3D zoom'});
    const before = Number(await zoom.inputValue()); await zoom.focus(); await press('ArrowRight');
    assert.equal(Number(await zoom.inputValue()),before+1);
    await page.getByRole('spinbutton',{name:'3D X size'}).fill('16');
    await page.getByRole('spinbutton',{name:'3D Y size'}).fill('16');
    await page.getByRole('spinbutton',{name:'3D Z size'}).fill('16');
    assert.equal(await page.locator('.cube-cell').count(),4096);
    await page.getByRole('spinbutton',{name:'3D Z size'}).fill('17');
    assert.equal(await page.getByRole('spinbutton',{name:'3D Z size'}).inputValue(),'16');
    assert.match(await page.locator('.toast').innerText(),/4096/);
    await clickText('Fit active bounds');
    await page.waitForTimeout(150); await page.reload();
    await page.locator('.document-card').filter({has:page.getByRole('heading',{name:'OKLCH color cube',exact:true})}).locator('.document-open').click();
    await page.locator('body').click({position:{x:2,y:2}}); await press('t');
    assert.equal(await page.locator('.cube-cell').count(),512);
    assert.equal(await page.locator('.cube-slice').count(),8);
    assert.equal(await page.getByRole('checkbox',{name:'3D show labels'}).isChecked(),true);
    assert.equal(Number(await page.getByRole('slider',{name:'3D zoom'}).inputValue()),before+1);
    assert.equal(await page.getByRole('slider',{name:'3D transparency'}).inputValue(),'1');
  });
  await check('3D dimension keys enqueue existing/new dimensions without collapsing the view', async () => {
    await create('3D queue',5);
    for (const [index,value] of [-1,2,3,4,5].entries()) {
      await page.getByLabel(`Dimension ${index+1} slice coordinate`).fill(String(value));
      await page.getByLabel(`Dimension ${index+1} slice coordinate`).blur();
    }
    await page.locator('body').click({position:{x:2,y:2}}); await press('t','v','PageUp');
    const selected = await coord();
    for (const [key,expected] of [['1',[2,3,1]],['1',[2,3,1]],['2',[3,1,2]],['4',[1,2,4]],['3',[2,4,3]],['0',[2,4,3]],['5',[4,3,5]]]) {
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
    const layout = await page.getByRole('button',{name:'Slices',exact:true}).getAttribute('aria-pressed');
    await press('Control+t'); assert.notEqual(await page.getByRole('button',{name:'Slices',exact:true}).getAttribute('aria-pressed'),layout);
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
    await page.locator('body').click({position:{x:2,y:2}}); await press('Control+Shift+t');
    assert.equal(await page.locator('.hyper-panel').count(),4);
    assert.equal(await page.locator('.hyper-column-label').count(),2);
    assert.equal(await page.locator('.hyper-row-label').count(),2);
    assert.equal(await page.locator('.cube-cell').count(),16);
    assert.equal((await page.locator('.cube-cell').evaluateAll(els=>new Set(els.map(el=>el.dataset.coord)).size)),16);
    assert.equal(await page.locator('.cube-current').count(),1);
    await page.getByRole('checkbox',{name:'4D show labels'}).check();
    await page.locator('.toast').waitFor({state:'hidden'});
    await page.screenshot({path:'/tmp/ndcalc-4d.png'});
    const orders = new Set(); const selected = await coord();
    await page.locator('body').click({position:{x:2,y:2}});
    for (let i=0;i<24;i++) {await press('T'); orders.add(JSON.stringify(await Promise.all(['X','Y','Z','W'].map(a=>page.getByLabel(`4D ${a} dimension`).inputValue()))));}
    assert.equal(orders.size,24); assert.equal(await coord(),selected);
    await page.getByRole('spinbutton',{name:'4D W size'}).fill('1');
    assert.equal(await page.locator('.cube-cell').count(),8);
    await clickText('Fit active bounds'); assert.equal(await page.locator('.cube-cell').count(),16);
    await page.locator('.cube-cell[data-coord="[0,0,0,0]"]').click();
    await press('Shift+PageUp','Control+Shift+ArrowRight');
    assert.equal(await coord(),'[0,0,1,1]');
    assert.equal(await page.locator('.selection-count').textContent(),'4 selected');
    await edit('(a,b,c,d) => a+b+c+d',true); await waitText([0,0,1,1],'2');
    await press('u'); await waitText([0,0,1,1],'[0,0,1,1]');
    assert.equal(await page.locator('.hyper-view').count(),1);
    await page.getByRole('checkbox',{name:'4D show labels'}).uncheck();
    await page.waitForTimeout(150); await page.reload();
    await page.locator('.document-card').filter({has:page.getByRole('heading',{name:'4D colors',exact:true})}).locator('.document-open').click();
    await clickText('4D');
    assert.equal(await page.locator('.cube-cell').count(),16);
    assert.equal(await page.getByRole('checkbox',{name:'4D show labels'}).isChecked(),false);
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
