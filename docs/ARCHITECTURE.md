# Architecture (phases 0 and 1)

## Layers
| Layer | Where |
|---|---|
| UI / presentation | `src/builder/js/06_validation.js` (step definitions), `07_rendering.js`, `styles.css` |
| Project data | `src/builder/js/03_model.js`, `04_state.js` |
| Feature engine (pure logic) | `src/builder/js/05_engine.js`, mirrored by `engine/resolver.py` |
| Feature knowledge | `features/registry.json` |
| Conversion / generation | `generate.py`, `engine/migrate.py`, `engine/projectgen.py` |
| Native Android | `android-template/java/` (modules and the capability bridge come in phase 3) |
| Platform versions | `platform.json` |
| Import / export | `08_actions.js` (project JSON, config.json), `tools/apply_update.py` rules in `apply-update.yml` |
| Validation / debugging | `tests/`, `build-report/build-report.json` (uploaded by every build) |

## config.json schema 2 (`hab-build-config-2`)
`{ schema:2, config:{app,src,disp,perms,icon,splash,wv,ads,out}, features:{selected:[ids], settings:{anim}}, analysis:{...} }`
Schema 1 (`hab-build-config-1`, boolean `feat` flags) is still accepted and migrated automatically.

## Feature registry entry
`id, name, cat, impl (web|native|hybrid), status (available|planned), summary, does, apis, perms, deps, conflicts, min_sdk, android,
benefit, cost, privacy, security, battery, storage, if_off, preview{available,note}, test{available,how}, caution, legacy, detect, manifest, gradle, bridge`.
`planned` entries are representable but cannot be built yet (the build stops with a clear message, or skips them with `--lenient`).
Resolver states (computed in the builder): REQUIRED, RECOMMENDED, OPTIONAL, NOT_NEEDED, UNSUPPORTED, with flags CAUTION and CONFLICT, and
tone values positive, attention, warning, info, muted (meaning only; the later UI chooses colours).

## Platform (verified Oct 2026)
Google Play requires new apps and updates to target Android 16 (API 36) from 31 Aug 2026. AGP 8.13 supports API 36 (minimum 8.9.1).
Android 16 changes handled in the template: edge-to-edge is always on (insets handled), `onBackPressed` is no longer called
(OnBackPressedCallback), orientation locks are ignored on large screens.

## Known limits until later phases
* All Java is still compiled into every app; only manifest permissions and receivers follow the selected features (phase 3 adds per-capability modules).
* The builder UI still offers the original switches; the registry holds the full catalogue.
* `project.zip` (multi-file projects) arrives in phase 4; `config.json` remains the hand-off file.
