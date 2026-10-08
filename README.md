# ndcalc

A local-first, modal **n-dimensional spreadsheet** built with **ClojureScript, Reagent 2, React 19, and shadow-cljs**. No backend or account. A fresh database opens a working 5D example. Fonts are bundled locally (SIL Open Font License files are in `public/fonts/`); the application itself makes no external network requests.

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
npm test                       # ClojureScript engine + state tests
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

A function stored as **Value** is not called automatically. A formula can explicitly call it: `() => $("as_function")(21)`.

Display uses stringification: text as-is, JSON for objects/arrays, source for functions, and readable fallbacks for BigInt, symbols, maps, sets, or circular objects. Empty cells display blank; an explicitly stored `undefined` displays `undefined`. React renders these as text, not HTML.

Formulas are synchronous and demand-evaluated. Each content revision has a memoized dependency graph; edits invalidate it, including conditional dependencies and named references. Circular references and evaluation errors appear in cells without breaking the table. There are guards for dependency depth and cell-evaluation count. Prefer pure, deterministic functions. Promises are ordinary values, not awaited spreadsheet calculations.

## Dimensions and views

The default plane is `(X,Y) = (1,2)` (or `(1,0)` for 1D and `(0,0)` for 0D). Dimensions are **1-based**; **0** is the null axis: one column in X, one row in Y.

Press a dimension key to rotate it into view:

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

The full current coordinate never changes when remapping. Arrow keys move along the mapped axes; inactive dimensions stay fixed. Dimension chips above the table show X/Y or fixed status and let you change any slice coordinate. Axis dropdowns support dimensions beyond the 1–9 shortcuts.

The **active area** is the componentwise minimum/maximum of populated numeric coordinates across the entire hypertable. Named cells do not expand it. Outside this box, cells are dimmed but fully navigable/editable. The grid renders a bounded, responsive window rather than allocating a dense hypertable.

The **3D view** is read-only: three stacked, tilted slices form a 3×3×3 neighborhood around the numeric current cell. Choose distinct X/Y/Z dimensions and a tilt angle. X/Y mapping and navigation stay synchronized with the plane. A null X/Y axis returns to the plane; two non-null axes are needed to enter 3D. Other dimensions remain fixed. It shares computed values and formatting with the plane. Switch back to Plane to edit.

Changing the dimension count is supported. Removing a dimension is refused if any populated cell has a non-zero coordinate there; data is never silently discarded.

## Modal keyboard editing

Press **?** to toggle the left cheatsheet. Shortcuts do not intercept typing in inputs/editors.

| Keys | Action |
| --- | --- |
| Arrows / `h j k l` | Navigate the plane |
| Tab / Shift+Tab | Next / previous X coordinate |
| `1`–`9`, `0` | Rotate a dimension / null dimension |
| `g15` Enter | Go to X coordinate 15 |
| `G-15` Enter | Go to Y coordinate −15 |
| `b` / `B` | First active X / Y coordinate |
| `e` / `E` | Last active X / Y coordinate |
| Enter / `i` | Edit current cell or fill selection |
| `f` | Open editor with Formula selected |
| `v` / Ctrl+V | Toggle visual-block selection |
| Shift+arrows / Shift+click | Extend selection |
| `y` / `p` | Copy / paste cells and their source |
| Delete / Backspace | Clear current cell or block |
| `d` / `D` | Clear current column / row in this slice |
| `u` / Ctrl+Z | Undo |
| Ctrl+Shift+Z / Ctrl+Y | Redo |
| Ctrl+Enter | Apply the current editor |
| Escape | Cancel editor / selection / goto |
| `n` | Create a named cell |
| `c` | Show conditional formatting |
| `t` | Toggle plane / 3D |

Axis operations use lowercase for **X**, uppercase for **Y**. Block operations (`y`, `p`, Delete, Enter) act on the visible selection. Goto accepts signed integers. Copy/paste preserves formula source; coordinate-parameter formulas naturally compute at their new locations. Clipboard and undo history are session-local. Block writes are limited to 10,000 cells; undo retains 100 content edits per open document.

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

At render time the coordinate predicate runs first. Only a match invokes the value predicate. Arrays accumulate CSS classes; strings accumulate CSS declarations in rule order, with later declarations winning. Invalid rules are isolated and surfaced in cell tooltips / warning markers. Disable, edit, delete, drag, or use ↑/↓ buttons to reorder rules.

**Static formatting** is a rule with `() => true` (or a predicate matching a fixed coordinate). CSS is applied using **Apply CSS** or Ctrl+Enter. The stylesheet uses native `@scope` to affect only table/inspector cells; theme variables such as `var(--accent)`, `var(--green)`, and `var(--red)` are available. Modern Chrome/Firefox with CSS `@scope` support is recommended.

## Persistence and JSON

All committed table data—including source, named cells, rules, CSS, dimension count, current coordinate, and plane mapping—automatically saves to **IndexedDB**. The theme preference is also stored there. Home lists all documents without evaluating their JavaScript. The save indicator reflects transaction completion; storage failures are visible.

Export downloads an `.ndcalc.json` document. Import validates the schema and JavaScript syntax **without executing expressions**, asks for trust, then creates a new document ID. It never overwrites an existing table. Imports are limited to 10 MB.

JSON stores **source**, not computed values. This preserves function-valued cells and expressions producing non-JSON values. On reopen, values are reconstructed from their source; mutated runtime object identity, external closures, nondeterministic results, and async work are not serialized snapshots.

The v1 schema contains `format: "ndcalc"`, `version: 1`, `title`, `dimensions`, a `cells` dictionary keyed by canonical coordinate JSON, a `named` dictionary, `rules`, `css`, and `view`.

### Trust boundary

**JavaScript is trusted code, not a sandbox.** Values, formulas, and predicates run on the page's main thread and can access the DOM, IndexedDB, and the network. Infinite loops can block the page; dependency guards do not interrupt arbitrary JavaScript. Import only documents you trust. Export backups before clearing browser storage. Concurrent tabs use last-write-wins rather than collaborative merging.

## Layout

- `src/ndcalc/engine.cljs` — coordinates, sparse cells, evaluation, formatting, JSON validation.
- `src/ndcalc/state.cljs` — modal commands, selection, undo, clipboard, persistence orchestration.
- `src/ndcalc/storage.cljs` — IndexedDB transactions and preferences.
- `src/ndcalc/ui.cljs` — Reagent components, editor, document library, inspector, 3D preview.
- `src/ndcalc/app.cljs` — React 19 root and startup.
- `src/ndcalc/demo.cljs` — working 5D sample.
- `test/ndcalc/` — ClojureScript unit tests.
- `scripts/browser-test.mjs` — isolated Playwright end-to-end workflows.
