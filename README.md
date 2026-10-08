# ndcalc

An **n-dimensional spreadsheet** built with **ClojureScript, Reagent 2, React 19, and shadow-cljs**. Data is stored in IndexedDB. An empty database opens a 5D example. Home always offers fresh copies of the 5D example and an 8×8×8 OKLCH coordinate-color cube. Fonts are bundled locally (SIL Open Font License files are in `public/fonts/`); the application itself makes no external network requests.

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
- **Formula:** an expression evaluating to a JavaScript function. Numeric cells receive their full padded coordinates as spread arguments; named cells receive their name as the only argument.

```js
(a,b,...rest) => $(a,b+1,...rest) + 1
(a,b,...rest) => $(a-1,b,...rest) * $("multiplier")
name => $("input") * 2
```

New numeric formulas are prefilled with one named argument per table dimension (including fixed slice dimensions), followed by `...rest`: `(a,b,c,...rest) => ` for 3D. The cursor starts after the arrow. A 0D template is `(...rest) => `; named cells use `(name,...rest) => `. Existing source is preserved when editing or switching types.

A function stored as **Value** is not called automatically. A formula can explicitly call it: `() => $("as_function")(21)`.

Display uses stringification: text as-is, JSON for objects/arrays, source for functions, and readable fallbacks for BigInt, symbols, maps, sets, or circular objects. Empty cells display blank; an explicitly stored `undefined` displays `undefined`. React renders these as text, not HTML.

Formulas are synchronous and demand-evaluated. Each content revision has a memoized dependency graph; edits invalidate it, including conditional dependencies and named references. Circular references and evaluation errors appear in cells without breaking the table. There are guards for dependency depth and cell-evaluation count per root calculation, rather than per browsing session. Missing coordinates do not accumulate cache entries as you navigate. Prefer pure, deterministic functions. Promises are ordinary values, not awaited spreadsheet calculations.

## Dimensions and views

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

The **active area** is the componentwise minimum/maximum of populated numeric coordinates across the entire hypertable. Named cells do not expand it. Outside this box, cells are dimmed but fully navigable/editable. The grid renders a bounded, responsive window rather than allocating a dense hypertable.

The **3D view** is editable and configurable:

- On first entry, it fits populated bounds along the chosen X/Y/Z axes; an empty table starts with an 8×8×8 window. **Fit active bounds** recalculates extents. Large bounds are explicitly marked as a bounded preview, never presented as the entire volume.
- Set each axis size independently, **1–32**, with a **4,096-cell total limit**. Manually changing a size enables **Follow cell**, which centers the window on the current coordinate. Uncheck it to fit bounds again.
- **Stack** automatically fits the projected volume to the available space. **Drag anywhere in the volume, including over cells, to tilt/rotate**; horizontal movement rotates and vertical movement tilts. Dragging never changes the selected cell. The sliders also adjust tilt, rotation, zoom, and layer gap. **Transparency** runs from 0% (opaque) to 100% (invisible) and applies to each layer. **Reset camera** restores camera defaults, including transparency.
- **Slices** displays individually readable, scrollable grids without overlapping planes. Click to select or double-click to edit without leaving 3D. **Labels** toggles values/coordinates; without labels, formatting fills each cell for a clearer color-volume view.
- Arrows navigate X/Y; **PgUp/PgDn** and **Z − / Z +** move Z. **Enter/i/f**, the Edit button, and the source bar open the cell editor directly in 3D. Visual selection/fill, clear, copy/paste, undo/redo, named cells, formatting rules, and CSS all work without switching to the plane. **Open in plane** is optional. Hover for the full value and source.

3D maintains a distinct, non-null **X/Y/Z dimension queue**. Pressing a dimension key or chip removes that dimension from its existing position and appends it to the end; a new dimension drops the oldest. For example, `[1,2,3]` → `1` → `[2,3,1]` → `4` → `[3,1,4]` → `1` → `[3,4,1]`. Repeating the last dimension does nothing. **0 is ignored in 3D**, so dimension hotkeys never collapse it to a lower-dimensional view. Axis dropdowns explicitly assign an axis, swapping with an existing axis if necessary. The coordinate, visual anchor, and mode are preserved. X/Y stays synchronized with the plane, and fitting bounds refits after remapping or editing. Other dimensions remain fixed.

Entering a higher-dimensional view fills any null axes with available numeric dimensions. Explicitly resizing the document below the view's rank returns to the plane; this is separate from dimension navigation. Computed values, selection highlighting, and active-hypercube formatting are shared by all views. `Ctrl+t` switches 3D between Stack and Slices.

Home → **Open OKLCH color cube** creates 512 coordinate formulas, `(a,b,c) => [a,b,c]`, over `[0,0,0]` through `[7,7,7]`. A formatting rule maps the value to OKLCH lightness, chroma, and hue. Change D3 in the plane to explore hues, or enter 3D to compare all eight layers. Opening an example creates a new saved document without modifying your existing tables.

The **4D view** (4D button or `Ctrl+Shift+t`) is a scrollable matrix of X/Y panels: **W runs horizontally**, **Z vertically**, with higher Z at the top. Sticky row/column headers identify both slice coordinates. The four axes have independent sizes, with the same 1–32 per-axis and 4,096-cell total limits. Fit active bounds, Follow cell, labels, zoom, transparency, cell selection, and editing work as in 3D Slices. Empty tables start with a 4×4×4×4 window. Numeric dimension keys use a four-element queue; `0` remains ignored.

Higher-axis navigation uses **logical navigation slots**, not hardcoded dimension numbers. The visible X/Y/Z/W axes come first, followed by the **most recently expelled** hidden axes, then other dimensions in ascending order. In the plane, PgUp/PgDn therefore moves the last expelled axis (or D3 before any expulsion). `Ctrl+Up/Down` does the same; `Ctrl+Left/Right` moves slot 4, Alt moves slots 5/6, and Ctrl+Alt slots 7/8. Up/PageUp/Right increments; Down/PageDown/Left decrements. Shift starts or extends an n-dimensional selection with every movement. Hidden chips show `nav3`…`nav8` to identify these bindings. Missing slots report a message without moving.

`Home`/`End` in the plane go to the first/last **populated cell in the current row**, respecting mapped axes and every fixed coordinate. Other rows and slices do not affect the endpoints; an empty row does not move. Shift+Home/End extends selection. These keys do not change coordinates in 3D or 4D.

Changing the dimension count is supported. Removing a dimension is refused if any populated cell has a non-zero coordinate there; data is never silently discarded.

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
| `f` | Open editor with Formula selected |
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
| `t` | Toggle plane / 3D |
| Ctrl+T | Toggle 3D Stack / Slices |
| Ctrl+Shift+T | Toggle plane / 4D |

Axis operations use lowercase for **X**, uppercase for **Y**. Visual selection keeps an n-dimensional anchor and selects the inclusive box between it and the current cell, across **all** dimensions. Switching planes, using the axis dropdowns, changing fixed slice coordinates, or jumping with `g`/`G` preserves the selection. The plane highlights its intersection with that box; the selected-cell count includes hidden slices. Enter/fill, Delete, and `y` operate on the entire box, not just the visible plane. Escape or `v` cancels selection; filling, clearing, or copying finishes it.

For a 2×2×2 cube, start at `[0,0,0]`, press `v`, Right, Down, `3`, Down, then Enter to fill all 8 cells. Continue rotating into dimensions 4 and 5 to select higher-dimensional hyperboxes. Null axes collapse the view, not the selection.

Goto accepts signed integers. Copy/paste preserves formula source and every selected slice, including empty cells. The copied extents follow the destination's ordered view axes (X/Y in the plane, X/Y/Z in 3D, X/Y/Z/W in 4D); remaining extents map to inactive dimensions in ascending dimension order. Paste is refused if a varying extent cannot fit a target dimension (including null axes and named cells), rather than dropping depth. Coordinate-parameter formulas compute at their new locations. Clipboard and undo history are session-local. The 10,000-cell limit applies to the **total hyperbox volume**; undo retains 100 content edits per open document.

## Conditional formatting and CSS

The right inspector has **Named cells**, **Rules**, and **CSS** tabs.

Rules are an ordered list of two JavaScript functions:

```js
// Coordinate predicate: spread numeric coordinates, or a single named string.
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

All committed table data—including source, named cells, rules, CSS, dimension count, current coordinate, plane mapping, and expelled-axis history—automatically saves to **IndexedDB**. Theme and 3D/4D size/layout/labels/camera preferences, including transparency and mouse-adjusted angles, are also stored there. Fit/follow state is session-local and resets for a newly opened document. Home lists all documents without evaluating their JavaScript. The save indicator reflects transaction completion; storage failures are visible.

Export downloads an `.ndcalc.json` document. Import validates the schema and JavaScript syntax **without executing expressions**, asks for trust, then creates a new document ID. It never overwrites an existing table. Imports are limited to 10 MB.

JSON stores **source**, not computed values. This preserves function-valued cells and expressions producing non-JSON values. On reopen, values are reconstructed from their source; mutated runtime object identity, external closures, nondeterministic results, and async work are not serialized snapshots.

The v1 schema contains `format: "ndcalc"`, `version: 1`, `title`, `dimensions`, a `cells` dictionary keyed by canonical coordinate JSON, a `named` dictionary, `rules`, `css`, and `view`.

### Trust boundary

**JavaScript is trusted code, not a sandbox.** Values, formulas, and predicates run on the page's main thread and can access the DOM, IndexedDB, and the network. Infinite loops can block the page; dependency guards do not interrupt arbitrary JavaScript. Import only documents you trust. Export backups before clearing browser storage. Concurrent tabs use last-write-wins rather than collaborative merging.

## Layout

- `src/ndcalc/engine.cljs` — coordinates, sparse cells, evaluation, formatting, JSON validation.
- `src/ndcalc/state.cljs` — modal commands, selection, undo, clipboard, persistence orchestration.
- `src/ndcalc/storage.cljs` — IndexedDB transactions and preferences.
- `src/ndcalc/ui.cljs` — Reagent components, editor, document library, inspector, 3D preview and 4D slice matrix.
- `src/ndcalc/app.cljs` — React 19 root and startup.
- `src/ndcalc/demo.cljs` — working 5D sample and 8×8×8 OKLCH color cube.
- `src/ndcalc/preview.cljs` — bounded 3D/4D windows, axis permutations, active-bound fitting, camera geometry and validated preferences.
- `test/ndcalc/` — ClojureScript unit tests.
- `scripts/browser-test.mjs` — isolated Playwright end-to-end workflows.
