# ndcalc contributor / agent guide

## Start here

- `README.md` is the visual introduction and first-use guide. Keep it approachable; move exhaustive semantics to `docs/reference.md` and build/architecture material to `docs/development.md`.
- Read the relevant sections of those docs before changing behavior. They are the public contract, not just implementation notes.
- Stack: ClojureScript, Reagent 2, React 19, shadow-cljs. No application server; committed document data and preferences live in IndexedDB.
- This repository uses **Jujutsu**. Inspect `jj status` and `jj bookmark list -a` before changing revisions; preserve unrelated work and never rewrite published immutable commits.

## Verification

```sh
npm test
npm run build
CHROME_CDP_URL=http://127.0.0.1:9222 npm run test:e2e
npm run test:firefox
```

A running app is needed for end-to-end/Firefox tests. Use Playwright's own installed Chromium if CDP isn't available. Tests and screenshot scripts must use isolated contexts/disposable profiles, never normal user storage. After static-path/deployment changes, also run `npm run build:pages` and `npm run test:pages`.

## Contracts to preserve

- Sparse signed safe-integer coordinates, 0–32 dimensions, omitted coordinates zero. Null axis 0 is one cell deep and cannot move; legacy Plane `(0,0)` remains legal.
- Store source, not computed values. Value can contain a function. Formula is explicitly chosen and synchronous. Text is only presentation: persist a quoted JavaScript string Value, and don't evaluate arbitrary code to detect literal strings.
- Frozen coordinate objects bind to evaluation, not UI state. `$`, `$$`, `_`, aliases, offsets, and hyperplane references must retain dependencies/cycle handling.
- All views share one persisted **full** axis queue. Changing Plane/3D/4D must not drop W or any hidden dimension. Navigation slots are queue positions, not hardcoded dimension IDs.
- Plane, 3D, and 4D are editable. In 4D, W increases left-to-right and outer Z top-to-bottom. Mouse dragging Stack rotates without selecting; touch dragging pans without rotating.
- Ordinary wheel/swipe pans; Alt+wheel/pinch zooms all canvases. Ctrl+wheel adjusts the 3D gap; Shift+wheel changes opacity only in Stack. Captured handled events prevent browser defaults where possible; Ctrl+t/Ctrl+Shift+t remain native.
- Inclusive selections span all dimensions and survive view/mapping/slice/goto changes. Hyperrow/hypercolumn selections belong to one actual dimension. Borders must remain above formatting backgrounds.
- Formatting runs coordinate predicates before value predicates and applies numeric rules only inside populated n-dimensional bounds, including interior holes. Named/header targets format independently. Scoped document CSS excludes out-of-bounds cells.
- Widths are separate per-target metadata, including empty/named/header cells. Natural widths clamp square–400px; manual widths allow 35–2000px. Clear removes content and custom width together; undo restores both. Width-only empty-cell editing must not create a value.
- URLs only identify already stored local documents. Refresh restores the table; Home clears the fragment; Back/Forward work. Missing IDs fall back safely without importing, evaluating arbitrary URL data, or creating/replacing user tables.
- Imports require trust, validate without running expressions, preserve source, and create fresh IDs. Examples create fresh copies. Never migrate by silently deleting populated cells, headers, or unusual legal names such as `__proto__`.
- System theme follows the OS; Light/Dark are fixed choices. Keep fonts/assets local, reduced-motion support, and no application-originated external requests.

## Builds and publication

Source assets must work at both `/` and `/ndcalc/`: use relative HTML/CSS/JS URLs and hash routing. Don't commit generated JS to `main`. `npm run build:pages` stages the deployable release in ignored `target/gh-pages/`; the `gh-pages` bookmark contains those files at its root plus `.nojekyll`, without development runtimes or user data.

Use a separate pages workspace so publishing doesn't overwrite the source checkout. Only push requested bookmarks, confirm remote tracking, and report separately whether the branch is published and whether GitHub Pages is actually enabled. See `docs/development.md#github-pages` for the workflow.
