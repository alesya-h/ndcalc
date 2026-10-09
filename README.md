# ndcalc

An **n-dimensional spreadsheet** built with **ClojureScript, Reagent 2, React 19, and shadow-cljs**. Data is stored in IndexedDB. An empty database opens a 5D example. Home offers fresh copies of the 5D sample, a 4D OKLCH/LCH comparison, and hypertables from physics, math, business, engineering, accounting, and generative art. Fonts are bundled locally (SIL Open Font License files are in `public/fonts/`); the application itself makes no external network requests.

## Run

Requires Node.js 20+ and Java 17+.

```sh
npm install
npm run dev
# http://localhost:8080
```

Production:

```sh
npm run build
npm run serve
# Or serve the public/ directory with any static HTTP server.
```

Tests:

```sh
npm test                       # ClojureScript engine, preview + state tests
npx playwright install chromium # Once, for headless browser tests
npm run test:e2e                # Keep dev/serve running on port 8080
# Alternatively, use an already-running debug Chrome:
CHROME_CDP_URL=http://127.0.0.1:9222 npm run test:e2e
```

`npm run test:firefox` checks **native Firefox Alt+wheel** against history navigation using WebDriver BiDi and a disposable profile. Requires a system Firefox; `FIREFOX_BIN` overrides its executable.

`NDCALC_URL` sets the browser-test URL. `PORT` sets the production server port. Tests use isolated browser contexts and do not touch your tables.

## Cells and coordinates

Tables are sparse, with **0–32 dimensions**, tested through 5D. Numeric coordinates are signed JavaScript safe integers. Omitted coordinates are zero; extra zero coordinates are aliases:

```js
$()             // origin
$(0, 0)         // the same origin, even in a 0D table
$(-2, 3)        // [-2,3,0,…]
$(1, 2, 3, 4, 5)
$("multiplier") // named cell
```

A 0D table exposes exactly one numeric cell, `[]`. A 1D table starts as a row. Missing cells return `undefined`; Delete removes a cell record. Explicitly stored `null`, `undefined`, `""`, and formulas still count as populated cells.

### Explicit value / formula types

In the modal editor, select **Value** or **Formula**. The type is never guessed from source.

- **Value:** any JavaScript expression: `42`, `"text"`, `{answer: 42}`, `123n`, `new Map([["x", 1]])`, or `x => x * 2`.
- **Formula:** a JavaScript function, or `=> expression` shorthand for `() => expression`. Numeric cells receive their full padded coordinates as spread arguments; named cells receive their name; hyperplane cells receive `(dimension, coordinate)`. The original source is preserved.

```js
(a,b,...rest) => $(a,b+1,...rest) + 1
(a,b,...rest) => $(a-1,b,...rest) * $("multiplier")
name => $("input") * 2
```

New numeric formulas are prefilled with one named argument per table dimension (including fixed slice dimensions), followed by `...rest`: `(a,b,c,...rest) => ` for 3D. The cursor starts after the arrow. A 0D template is `(...rest) => `; named cells use `(name,...rest) => `. Existing source is preserved when editing or switching types.

A function stored as **Value** is not called automatically. A formula can explicitly call it: `() => $("as_function")(21)`.

### Text mode and cell widths

**Alt+Enter** opens Text mode, including for an empty cell. Enter/i automatically uses Text for an existing literal string. This is presentation only: applying stores a normal **Value** with `JSON.stringify` source, preserving quotes, backslashes, Unicode, line breaks and empty strings. Text ↔ Value encodes/decodes the literal without executing arbitrary source. Computed-string expressions and Formulas are not automatically converted. Multiline values remain available in the editor/tooltip; grid rows display their first line.

**Auto width** measures each cell's content and clamps it from a square cell to **400px**. A column takes its largest cell width in the current slice, including populated cells outside the visible row window and its hyperplane header. With preview labels hidden, ordinary cells use their square minimum; visible headers still size to their text. Override with the editor's **Cell width** field (35–2000px) or mouse/pen-drag a cell's right edge. Double-click the edge or choose **Clear custom width** in the editor and Apply to reset. Width-only empty cells can be resized/reset in the editor without entering a value. Clearing a cell, selection, row, or column removes its custom widths too; undo restores both content and widths. A bottom-right dot marks manual widths. Widths support empty/header/named cells, do not populate numeric bounds, survive undo/export/reload, and follow copied cells. Touch users can set widths in the editor without sacrificing swipe gestures.

Display uses stringification: text as-is, JSON for objects/arrays, source for functions, and readable fallbacks for BigInt, symbols, maps, sets, or circular objects. Empty cells display blank; an explicitly stored `undefined` displays `undefined`. React renders these as text, not HTML.

### Axis aliases, hyperplane values, and coordinate objects

**Dimensions → Axis aliases** assigns optional unique names (up to 80 characters), such as `date` or `department`. Aliases appear in chips, selectors, slice inputs, and preview headers instead of dimension numbers. Numeric dimension IDs still work in formulas; aliases are case-sensitive. Renaming an alias does not rewrite existing JavaScript—update references using its old name.

A **hyperplane cell** belongs to one `(dimension, coordinate)` pair and is shared across all other dimensions. Use it for dates, department names, units, rates, or other axis-associated values. Each grid has **two horizontal header rows and two vertical header columns**: raw coordinates, then their computed hyperplane values, then ordinary cells. 4D also has these paired headers for outer Z/W slices. Click a value header to edit; focused headers support Enter/i, Shift+Enter/I for Formula, and Delete/Backspace to clear. In 3D Stack, headers remain clickable while mouse-dragging ordinary cells rotates the camera. Intersections form a **2×2 corner**: axis names off-diagonal and diagonal lines in the other two cells.

Hyperplane cells have explicit Value/Formula types, accept arbitrary JavaScript values, participate in dependency tracking/cycle detection and undo, and persist with the table. Missing values return `undefined`. They do **not** expand numeric populated bounds; removing a dimension with populated hyperplane cells is refused. The null axis supports only coordinate zero.

`_` is an **immutable coordinate object bound to the cell being evaluated**, independent of the current view. `offset(axis, amount)` returns a new coordinate without moving the UI; aliases or actual dimension numbers identify axes, not navigation slots. `value()` fetches that cell; `value(axis)` fetches its hyperplane value. `coordinate(axis)` returns the raw signed coordinate, and `coords` exposes the immutable coordinate data.

```js
=> $(_.offset("date", -1)) + 1
=> _.offset(3, 15).value() + 1
=> $$(1, 42)
=> $$("department", 42)
=> $$("department", _)
=> _.value("department")
=> $$["department"]
=> $$.department
```

`$$.alias` / `$$["alias"]` implicitly read the hyperplane value at `_`'s coordinate on that axis. Bracket syntax supports names containing spaces. `$(coordinateObject)` reads an ordinary or hyperplane target just like `.value()`. Hyperplane coordinate objects may offset their own axis; they have no fixed position on other axes. Named cells likewise have no numeric axis coordinates. Offset amounts and results must stay within safe-integer bounds; null offsets do not move.

Formatting functions also receive the bound `_`, `$`, and `$$`. `_.kind` is `"cell"`, `"named"`, or `"hyperplane"`; hyperplane objects additionally have `.dimension`. Their coordinate predicate receives this coordinate object as its single argument, rather than masquerading as ordinary X/Y coordinates. For example, `() => _.kind === "hyperplane"` styles all value headers.

Formulas are synchronous and demand-evaluated. Each content revision has a memoized dependency graph; edits invalidate it, including conditional dependencies and named references. Circular references and evaluation errors appear in cells without breaking the table. There are guards for dependency depth and cell-evaluation count per root calculation, rather than per browsing session. Missing coordinates do not accumulate cache entries as you navigate. Prefer pure, deterministic functions. Promises are ordinary values, not awaited spreadsheet calculations.

## Dimensions and views

**Dimensions:** precedes the axis chips; the topbar dimensional badge opens the same configuration dialog. Both the ndcalc logo and Home return to the library; the Home / document-title breadcrumb follows the logo.

The default plane is `(X,Y) = (1,2)` (or `(1,0)` for 1D and `(0,0)` for 0D). Dimensions are **1-based**; **0** is the null axis: one column in X, one row in Y. `T` cycles all axis orders: two in the plane, six in 3D, and 24 in 4D, without changing coordinates or selection.

In the plane, press a dimension key to rotate it into view:

1. If it is X, swap X and Y.
2. If it is Y, move Y into X and make Y null.
3. Otherwise, move Y into X and put the chosen dimension in Y.

Your exact example:

```text
start       (1,2)
3           (2,3)
1           (3,1)
1           (1,0)
1           (0,1)
2           (1,2)
1           (2,1)
2           (1,2)
```

The full current coordinate never changes when remapping. Arrow keys move along the mapped axes; inactive dimensions stay fixed. Dimension chips above the table show X/Y/Z/W or hidden navigation-slot status and let you change any slice coordinate. Axis dropdowns support dimensions beyond the 1–9 shortcuts.

The **active area** is the componentwise minimum/maximum of populated numeric coordinates across the entire hypertable. Named and hyperplane cells do not expand it. Outside this box, cells are dimmed but fully navigable/editable. The grid renders a bounded, responsive window rather than allocating a dense hypertable.

The **3D view** is editable and configurable:

- On first entry, it fits populated bounds along the chosen X/Y/Z axes; an empty table starts with an 8×8×8 window. **Fit active bounds** recalculates extents. Large bounds are explicitly marked as a bounded preview, never presented as the entire volume.
- Set each axis size independently, **1–32**, with a **4,096-cell total limit**. Manually changing a size enables **Follow cell**, which centers the window on the current coordinate. Uncheck it to fit bounds again.
- **Stack** automatically fits the projected volume to the available space. **Drag anywhere in the volume, including over cells, to tilt/rotate**; horizontal movement rotates and vertical movement tilts. Dragging never changes the selected cell. The sliders also adjust tilt, rotation, zoom, and layer gap. **Transparency** runs from 0% (opaque) to 100% (invisible) and applies **only to the 3D Stack**. Slices and 4D panels stay opaque. **Reset camera** restores camera defaults, including transparency.
- **Slices** displays individually readable, scrollable grids without overlapping planes. Click to select or double-click to edit without leaving 3D. **Labels** toggles values/coordinates; without labels, formatting fills each cell for a clearer color-volume view.
- Arrows navigate X/Y; **PgUp/PgDn** and **Z − / Z +** move Z. **Enter/i** (regular edit) or **Shift+Enter/I** (Formula), the Edit button, and the source bar open the cell editor directly in 3D. Visual selection/fill, clear, copy/paste, undo/redo, named cells, formatting rules, and CSS all work without switching to the plane. **Open in plane** is optional. Hover for the full value and source.

All views share a persisted **full axis queue containing every existing dimension and null**. Plane reveals its first two axes, 3D its first three, and 4D its first four; the rest stay available for hidden-axis navigation. Changing view never rebuilds or truncates this queue, so a chosen W survives 4D → 3D → Plane → 4D. 3D uses the queue's **X/Y/Z prefix**. In a volume, pressing a dimension key or chip removes it from the visible prefix and appends it to that prefix; an incoming hidden dimension displaces the oldest visible axis into the hidden tail. No dimension is discarded from the full queue. For example, `[1,2,3]` → `1` → `[2,3,1]` → `4` → `[3,1,4]` → `1` → `[3,4,1]`. Repeating the last dimension does nothing. **0 is allowed in every view**: the null axis stays at coordinate zero, one cell deep, with its size control disabled. It cannot be stepped along. Selecting it does not change view mode. Axis dropdowns swap positions across the full queue, including hidden axes. The coordinate, visual anchor, and mode are preserved. X/Y stays synchronized with the plane, and fitting bounds refits after remapping or editing. Other dimensions remain fixed. The legacy Plane `(0,0)` case retains two null slots; volumes skip its duplicate null slot while still preserving all real dimensions.

`t` cycles **Plane → 3D → 4D → Plane**, skipping views without enough distinct available dimensions (including null). 3D therefore needs at least two numeric dimensions and 4D at least three. Existing null axes are preserved. Resizing below the minimum returns to the plane. Computed values, selection highlighting, and active-hypercube formatting are shared by all views. Use Stack/Slices buttons for the 3D layout. **Ctrl+t and Ctrl+Shift+t are not intercepted**, leaving browser tab shortcuts intact.

Ordinary scroll/trackpad gestures **pan horizontally and vertically**, including a scrollable 3D Stack canvas. **Alt+scroll zooms** in every canvas, including Plane. In 3D, Ctrl+scroll adjusts layer gap; Shift+scroll adjusts transparency only in Stack (it remains available for native horizontal panning in Slices/4D). Native non-passive **capture-phase** wheel handlers prevent handled gestures from reaching browser defaults. Keyboard handling also runs in capture phase; typing fields and unmapped browser shortcuts remain native. Firefox's native Alt+wheel history behavior is regression-tested. Browser/OS-reserved shortcuts that never reach page JavaScript cannot be overridden; Ctrl+t / Ctrl+Shift+t intentionally remain native. `f` toggles Follow cell, `F` fits active bounds, and `l` toggles Labels in either volume view.

**Touch:** one finger pans and two fingers pan/pinch-zoom in Plane, 3D Stack/Slices and 4D. Touch-panning Stack does not rotate or change selection; mouse dragging still rotates. Tap selects a cell, double tap edits, and tapping a header opens its editor. Plane panning advances the bounded coordinate window beyond the current DOM cells. Native page zoom/history overscroll is prevented within canvases.

Home → **Open OKLCH vs LCH** creates 1,024 coordinate formulas over an 8×8×8×2 table. Aliased axes **lightness/chroma/hue** have editable numeric hyperplane values; **space chooses OKLCH or CIELCH (D50)**. Color formatting reads these values through `$$` / `_.value(...)`. In 4D the spaces appear side by side, with hue slices vertically. Chroma uses each space's own scale (OKLCH 0–0.35, LCH 0–131.25), not equal colorimetric values; out-of-sRGB colors are browser gamut-mapped.

The **4D view** (4D button or the second `t`) is a scrollable matrix of X/Y panels: **W runs horizontally**, **Z vertically**, increasing from top to bottom like the inner Y axis. Sticky row/column headers identify both slice coordinates. The four axes have independent sizes, with the same 1–32 per-axis and 4,096-cell total limits. Fit active bounds, Follow cell, labels, wheel zoom, cell selection, and editing work as in 3D Slices. Empty tables start with a 4×4×4×4 window. Numeric dimension keys operate on the four-element prefix of the same full queue, including `0` as a one-cell null axis.

Higher-axis navigation uses **logical navigation slots**, not hardcoded dimension numbers. Slots are positions in the **same full axis queue** in every view; visible axes are its prefix and hidden axes its tail. The queue initially follows dimension-number order, with null last. In the plane, PgUp/PgDn moves slot 3; leaving 4D does not shuffle W or any hidden axis. `Ctrl+Up/Down` does the same; `Ctrl+Left/Right` moves slot 4, Alt moves slots 5/6, and Ctrl+Alt slots 7/8. Up/PageUp/Right increments; Down/PageDown/Left decrements. Shift starts or extends an n-dimensional selection with every movement. Hidden chips show `nav3`…`nav8` to identify these bindings. Missing slots report a message without moving.

`Home`/`End` in the plane go to the first/last **populated cell in the current row**, respecting mapped axes and every fixed coordinate. Other rows and slices do not affect the endpoints; an empty row does not move. Shift+Home/End extends selection. These keys do not change coordinates in 3D or 4D.

Changing the dimension count is supported. Removing a dimension is refused if any populated cell has a non-zero coordinate there; data is never silently discarded.

## Example hypertables

Opening an example creates a **fresh saved copy**, leaving existing tables untouched. The Named cells panel supplies units, assumptions, and editable inputs; aliased hyperplane headers display physical coordinates, dates, department/product names, and metrics. Heat diffusion, membrane modes, beam design, and art formulas use header values through `$$` / `_.value(...)`, so changing a header recalculates results. All data is synthetic and models are deliberately simplified.

| Example | Shape / subject | Formatting |
|---|---|---|
| Heat diffusion | 8×8×4×3 · X/Y, time, diffusivity; analytical Gaussian solution | Thermal color map with gentle CSS breathing |
| Membrane eigenmodes | 8×8×3×3 · X/Y and two mode numbers | Diverging signed amplitudes and nodal-line outlines |
| Product scenario planning | 12×3×3×3×4 · month, product, region, demand scenario, metric | Magnitude bars, profit sign, margin meters |
| Beam design envelope | 6×5×4×3×4 · span, load, section, material, metric | Utilization colors, animated overload hatching |
| Double-entry ledger | 6×6×3×2 · month, account, department, actual/budget | Debit/credit colors; live balance controls pulse on imbalance |
| Interference atelier | 12×12×4×3 · X/Y, phase, motif | Object-valued cells, conic gradients, animated hue and shape morphing |

Business profit/margin and ledger controls reference other cells, so manual edits propagate. Beam results cover deflection, stress, and stress/serviceability utilization; they are screening examples, not design certification. Animations are entirely local CSS and respect `prefers-reduced-motion`.

## Modal keyboard editing

Press **h** to toggle the left cheatsheet (`?` remains an alias). There are no hjkl movement bindings. Shortcuts do not intercept typing in inputs/editors.

| Keys | Action |
| --- | --- |
| Arrows | Navigate X/Y |
| Home / End | First / last populated cell in this row (plane only) |
| PgUp / PgDn, Ctrl+Up / Down | Increment / decrement navigation slot 3 |
| Ctrl+Left / Right | Decrement / increment navigation slot 4 |
| Alt+Up / Down, Alt+Left / Right | Move slots 5 / 6 |
| Ctrl+Alt+Up / Down, Ctrl+Alt+Left / Right | Move slots 7 / 8 |
| Shift+movement | Start / extend selection across dimensions |
| `T` | Next axis permutation (2 / 6 / 24) |
| Tab / Shift+Tab | Next / previous X coordinate |
| `1`–`9`, `0` | Rotate a dimension / null dimension |
| `g15` Enter | Go to X coordinate 15 |
| `G-15` Enter | Go to Y coordinate −15 |
| `b` / `B` | First active X / Y coordinate |
| `e` / `E` | Last active X / Y coordinate |
| Enter / `i` | Edit current cell or fill selection |
| Shift+Enter / `I` | Edit as a formula |
| `f` / `F` | Toggle Follow cell / fit active bounds (3D/4D) |
| `l` | Toggle Labels (3D/4D) |
| Scroll / one-finger swipe | Pan horizontally / vertically (all canvases) |
| Alt+Scroll / two-finger pinch | Zoom (all canvases) |
| Alt+Enter | Edit verbatim text |
| `H` | Cycle cell → hyperrow → hypercolumn → cell |
| Ctrl+Scroll / Shift+Scroll | Layer gap / stack transparency (3D) |
| `v` / Ctrl+V | Toggle n-dimensional visual selection |
| Shift+arrows / Shift+click | Extend selection |
| `y` / `p` | Copy / paste cells and their source |
| Delete / Backspace | Clear current cell or block |
| `d` / `D` | Clear current column / row in this slice |
| `u` / Ctrl+Z | Undo |
| Ctrl+Shift+Z / Ctrl+Y | Redo |
| Ctrl+Enter | Apply the current editor |
| Escape | Cancel editor / selection / goto |
| `n` | Named cells panel |
| `N` | Create a named cell |
| `c` | CSS panel |
| `r` | Formatting rules panel |
| `h` | Toggle help |
| `t` | Cycle plane → 3D → 4D → plane |

Panel shortcuts (`n`, `c`, `r`) or clicking an inspector tab **hide an already active panel**. A narrow tab rail remains for mouse reopening. Browser Ctrl+t / Ctrl+Shift+t keep their native behavior.

**`H` hypercell cursors:** the ordinary current cell and its X/Y hyperplane values always have borders. `H` cycles the stronger highlight: **cell → hyperrow (top headers, X) → hypercolumn (left headers, Y)**. Hyper-mode movement is restricted to its single actual dimension; unrelated arrows/slots do nothing. `v`/Shift selects an inclusive hyperline; Enter/Alt+Enter/I fills it, `y`/`p` copies/pastes, and Delete clears it without altering ordinary cells. Hyperlines can cross orientations or paste into an ordinary row; multidimensional blocks cannot silently collapse into a line. Ordinary clicks restore the cell cursor; Shift+click on headers extends their selection. 4D's outer Z/W headers also support their own axis cursor. Selection borders overlay formatting, remaining visible when rules override backgrounds.

Axis operations use lowercase for **X**, uppercase for **Y**. Visual selection keeps an n-dimensional anchor and selects the inclusive box between it and the current cell, across **all** dimensions. Switching planes, using the axis dropdowns, changing fixed slice coordinates, or jumping with `g`/`G` preserves the selection. The plane highlights its intersection with that box; the selected-cell count includes hidden slices. Enter/fill, Delete, and `y` operate on the entire box, not just the visible plane. Escape or `v` cancels selection; filling, clearing, or copying finishes it.

For a 2×2×2 cube, start at `[0,0,0]`, press `v`, Right, Down, `3`, Down, then Enter to fill all 8 cells. Continue rotating into dimensions 4 and 5 to select higher-dimensional hyperboxes. Null axes collapse the view, not the selection.

Goto accepts signed integers. Copy/paste preserves formula source and every selected slice, including empty cells. The copied extents follow the destination's ordered view axes (X/Y in the plane, X/Y/Z in 3D, X/Y/Z/W in 4D); remaining extents map to inactive dimensions in ascending dimension order. Paste is refused if a varying extent cannot fit a target dimension (including null axes and named cells), rather than dropping depth. Coordinate-parameter formulas compute at their new locations. Clipboard and undo history are session-local. The 10,000-cell limit applies to the **total hyperbox volume**; undo retains 100 content edits per open document.

## Conditional formatting and CSS

The right inspector has **Named cells**, **Rules**, and **CSS** tabs.

Rules are an ordered list of two JavaScript functions:

```js
// Coordinate predicate: numeric coordinates, a named string, or a hyperplane object.
(x,y,...rest) => typeof x === "number" && y === 0
name => name === "my_named_cell"

// Value predicate: receives the computed JavaScript value.
v => v > 0 ? ["positive", "bold"] : []
v => `color: ${v < 0 ? "coral" : "seagreen"}; font-weight: 600;`
```

Conditional formatting is evaluated only for numeric cells inside the active hypercube, across every dimension. This includes empty cells between the populated corners, but excludes all cells outside those bounds; an empty table has no active numeric cells. Named-cell rules remain eligible independently of numeric bounds. Bounds and formatting update when cells are added or removed.

At render time the coordinate predicate runs first. Only a match invokes the value predicate. Arrays accumulate CSS classes; strings accumulate CSS declarations in rule order, with later declarations winning. Invalid rules are isolated and surfaced in cell tooltips / warning markers. Click a rule's name or its pencil button to edit its name, coordinate predicate, value predicate, and enabled state. Apply (or Ctrl+Enter) replaces that rule in place, preserving its ID and order; Escape cancels. Disable, delete, drag, or use ↑/↓ buttons to reorder rules.

**Static formatting** is a rule with `() => true` (or a predicate matching a fixed coordinate). CSS is applied using **Apply CSS** or Ctrl+Enter. The stylesheet uses native `@scope`, excluding out-of-bounds cell subtrees, to affect only table/inspector cells; theme variables such as `var(--accent)`, `var(--green)`, and `var(--red)` are available. Modern Chrome/Firefox with CSS `@scope` support is recommended.

## Persistence and JSON

All committed table data—including source, named cells, rules, CSS, dimension count, current coordinate, plane mapping, and full shared axis queue, aliases, hyperplane cells and per-cell widths—automatically saves to **IndexedDB**. Theme choice (**System**, Light, Dark) and plane zoom / 3D/4D size/layout/labels/camera preferences, including stack transparency and mouse-adjusted angles, are also stored there. **System is the default**, tracks `prefers-color-scheme` live, and follows OS changes only while selected. Light/Dark overrides stay fixed. Fit/follow state is session-local and resets for a newly opened document. Home lists all documents without evaluating their JavaScript. The save indicator reflects transaction completion; storage failures are visible.

Export downloads an `.ndcalc.json` document. Import validates the schema and JavaScript syntax **without executing expressions**, asks for trust, then creates a new document ID. It never overwrites an existing table. Imports are limited to 10 MB.

JSON stores **source**, not computed values. This preserves function-valued cells and expressions producing non-JSON values. On reopen, values are reconstructed from their source; mutated runtime object identity, external closures, nondeterministic results, and async work are not serialized snapshots.

The v1 schema contains `format: "ndcalc"`, `version: 1`, `title`, `dimensions`, `cells` keyed by canonical numeric coordinate JSON, `named`, `rules`, `css`, and `view`. Optional `aliases` maps numeric dimension keys to names; `hyperplanes` maps `[dimension, coordinate]` JSON keys to source-backed cell records; `view.axes` holds the full queue. Optional `cell-widths` maps canonical targets (numeric/name arrays or hyperplane objects) to per-cell pixel overrides. Older documents without these fields remain supported and migrate their saved mapping/recency into the queue.

### Trust boundary

**JavaScript is trusted code, not a sandbox.** Values, formulas, and predicates run on the page's main thread and can access the DOM, IndexedDB, and the network. Infinite loops can block the page; dependency guards do not interrupt arbitrary JavaScript. Import only documents you trust. Export backups before clearing browser storage. Concurrent tabs use last-write-wins rather than collaborative merging.

## Layout

- `src/ndcalc/engine.cljs` — coordinates, aliases, hyperplanes, coordinate objects, literal strings, evaluation, formatting, JSON validation.
- `src/ndcalc/layout.cljs` — natural/per-cell widths and sparse column sizing.
- `src/ndcalc/gestures.cljs` — native captured wheel and touch pan/pinch/tap.
- `src/ndcalc/state.cljs` — modal commands, selection, undo, clipboard, persistence orchestration.
- `src/ndcalc/storage.cljs` — IndexedDB transactions and preferences.
- `src/ndcalc/ui.cljs` — Reagent components, editor, document library, inspector, 3D preview and 4D slice matrix.
- `src/ndcalc/app.cljs` — React 19 root and startup.
- `src/ndcalc/demo.cljs` — blank, 5D and OKLCH/LCH document factories.
- `src/ndcalc/examples.cljs` — domain-specific hypertables and animated formatting.
- `src/ndcalc/preview.cljs` — bounded 3D/4D windows, axis permutations, active-bound fitting, camera geometry and validated preferences.
- `test/ndcalc/` — ClojureScript unit tests.
- `scripts/browser-test.mjs` — isolated Playwright end-to-end workflows.
