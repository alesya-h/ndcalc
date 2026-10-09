# Development and deployment

[README](../README.md) · [User and formula reference](reference.md) · [Agent/contributor notes](../AGENTS.md)

## Prerequisites and local development

Use Node.js 20+ and Java 17+. The project uses ClojureScript, Reagent 2, React 19, and shadow-cljs; npm installs the JavaScript dependencies and shadow-cljs resolves the Clojure dependencies.

```sh
npm ci
npm run dev
# http://localhost:8080 — shadow-cljs watches and hot-reloads
```

For a production build:

```sh
npm run build
npm run serve
# http://localhost:8080
```

`PORT` overrides the production server's port. `public/` is the application's static web root. HTML, CSS, fonts, icons, and JavaScript use relative asset URLs, so the app also works at a project subpath such as `/ndcalc/`. Hash-based table URLs don't require server-side route rewrites. Serve over HTTP(S), not `file://`.

## Verification

```sh
npm test                          # ClojureScript unit tests
npm run build                     # Optimized browser release
npx playwright install chromium   # Once, unless using an existing debug Chrome
npm run test:e2e                   # Against a running app
npm run test:firefox               # Native Firefox Alt+wheel/history regression

# Use an already-running Chrome without installing Playwright's browser:
CHROME_CDP_URL=http://127.0.0.1:9222 npm run test:e2e
```

`NDCALC_URL` overrides the test application URL. `FIREFOX_BIN` overrides the system Firefox executable. Firefox testing uses WebDriver BiDi and a disposable profile, without geckodriver. Browser tests use isolated contexts/profiles and **must not touch existing user tables**.

Test builds auto-discover namespaces ending in `-test`. Engine, state, interaction, keyboard, routing, preview, reference, and example tests live in `test/ndcalc/`.

## Screenshots

The README's screenshots are real application captures of fresh built-in examples. Regenerate them against a running application:

```sh
npm run screenshots
# Or:
CHROME_CDP_URL=http://127.0.0.1:9222 npm run screenshots
```

The script uses its own browser context, waits for fonts, disables motion, and writes `docs/screenshots/{plane,stack,hypercube}.png`. It never reads the normal browser profile's IndexedDB. Review the images before committing them.

## GitHub Pages

### Build and test

```sh
npm run build:pages
npm run test:pages
# Or use the debug browser:
CHROME_CDP_URL=http://127.0.0.1:9222 npm run test:pages
```

`build:pages` first runs the optimized shadow-cljs release, then stages **only deployable files** in `target/gh-pages/`: HTML, CSS, favicon, bundled fonts and their licenses, release JavaScript modules, a build-info file, and `.nojekyll`. Development runtimes, source maps, npm dependencies, source code, and user data are excluded. Set `SOURCE_REVISION` to include a source commit ID in `build-info.json`.

`test:pages` serves this exact directory at `/ndcalc/` on a temporary port in an isolated browser context. It checks that assets/fonts load from the project prefix, no app-originated requests go elsewhere, views/editing work, and refresh restores the local document. It does not need the development server. To smoke-test an already deployed site instead, set `NDCALC_URL=https://alesya-h.github.io/ndcalc/`.

### Publish the `gh-pages` bookmark

Keep source on `main` and the staged site **at the root of a separate `gh-pages` branch/bookmark**, not inside `public/`. Only publish after reviewing and testing the release.

With Jujutsu, the first release can be made in a separate workspace (use a destination that doesn't already exist):

```sh
# From the source workspace, after committing the source changes:
SOURCE_REVISION="$(jj log -r @ --no-graph -T commit_id)" npm run build:pages
CHROME_CDP_URL=http://127.0.0.1:9222 npm run test:pages

jj workspace add --name pages -r 'root()' /tmp/ndcalc-pages
cp -a target/gh-pages/. /tmp/ndcalc-pages/
jj -R /tmp/ndcalc-pages describe -m 'Publish ndcalc static release'
jj -R /tmp/ndcalc-pages bookmark create gh-pages -r @
jj -R /tmp/ndcalc-pages git push --remote origin --bookmark gh-pages
```

An explicit `--bookmark gh-pages` push automatically tracks `gh-pages@origin`. Check with `jj bookmark list -a`. For later releases, create the pages workspace on top of the existing `gh-pages` commit instead of `root()`, replace its site files with the new staging directory, and use `jj bookmark set gh-pages -r @`. Do not rewrite a published release or delete the source workspace to make a deployment.

**One-time GitHub setting:** Repository → **Settings → Pages → Build and deployment → Deploy from a branch → `gh-pages` → `/ (root)`**. Verify these settings after publishing; GitHub may auto-enable Pages for a first `gh-pages` push, but the branch alone isn't proof that deployment has finished. Once enabled, this repository's app is served at `https://alesya-h.github.io/ndcalc/`. GitHub Actions can also deploy the same staged directory if preferred.

There are no application secrets or runtime configuration to upload. IndexedDB is origin-local: localhost data does not automatically move to GitHub Pages; export/import JSON to move a table. Browsers with modern native CSS `@scope` support are recommended.

## Source map

| File | Responsibility |
| --- | --- |
| `src/ndcalc/engine.cljs` | Coordinates, aliases, hyperplanes, immutable coordinate objects, literal strings, evaluation/dependencies, formatting, JSON validation |
| `src/ndcalc/state.cljs` | Editors, modal commands, navigation, selections, clipboard, undo, shared axes, view controls, persistence orchestration |
| `src/ndcalc/routing.cljs` | Local-document URL fragments and browser history listeners |
| `src/ndcalc/storage.cljs` | IndexedDB transactions and preferences |
| `src/ndcalc/layout.cljs` | Text measurement, natural/manual widths, sparse column sizing |
| `src/ndcalc/gestures.cljs` | Captured wheel and native touch pan/pinch/tap |
| `src/ndcalc/preview.cljs` | Bounded 3D/4D windows, permutations, fitting, camera geometry, preference validation |
| `src/ndcalc/ui.cljs` | Reagent UI, paired headers, editor, library, inspector, Plane/3D/4D canvases |
| `src/ndcalc/app.cljs` | React root, theme setup, keyboard capture, startup |
| `src/ndcalc/demo.cljs` | Blank, 5D, and OKLCH/LCH factories |
| `src/ndcalc/examples.cljs` | Domain examples and local animated formatting |
| `public/` | HTML, CSS, icons, bundled fonts and licenses; compiled JS is ignored on source branches |
| `scripts/` | Static server, browser regressions, screenshots, Pages staging and smoke test |

## Runtime and storage model

- **Sparse source-backed data:** cell records store JavaScript source and explicit Value/Formula kinds, not computed values. Text is an editor presentation of a literal string Value, never a third persisted kind.
- **Demand evaluation:** each content revision has a memoized dynamic dependency graph. Evaluation/dependency budgets reset per root; missing cells don't accumulate cache entries. Values may be functions; formulas are synchronous and promises are ordinary values.
- **One axis queue:** `view.axes` persists all real dimensions plus null. Views reveal prefixes of two/three/four entries without losing hidden dimensions. Old mapping/recency fields migrate into it.
- **Global hyperplanes:** source-backed `(dimension, coordinate)` cells are shared across the other dimensions. Numeric active bounds exclude named cells, hyperplanes, and width-only metadata.
- **Independent widths:** per-target width overrides never populate a cell or invalidate computed values. Clearing a target removes its override; undo/clipboard/persistence preserve the appropriate metadata.
- **Versioned import:** the JSON format remains `ndcalc` v1. Optional aliases, hyperplanes, full axes, and widths remain compatible with older documents. Imports validate before trust confirmation and create new document IDs.
- **Trust, not a sandbox:** expressions and predicates can use DOM, storage, and network APIs or block the main thread. Dependency budgets cannot stop an arbitrary infinite JavaScript loop.
- **Local persistence:** transaction completion drives the save indicator. Preferences are separate from document data; simultaneous tabs are last-write-wins, not collaborative merging.

The [reference](reference.md) documents exact coordinate/reference syntax, bounds, formatting order, shortcuts, selection semantics, limits, and JSON fields.
