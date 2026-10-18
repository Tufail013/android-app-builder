# Android App Builder

HTML to Android app builder. The builder page runs on the phone; GitHub Actions builds the APK.

* `src/builder/` - the builder page, split into modules. `tools/bundle.py` joins them into one page.
* `features/registry.json` - every capability: what it does, permissions, dependencies, risks, tests, and whether it is implemented yet.
* `engine/`, `generate.py` - read `config.json` (schema 1 or 2), resolve features, write the Android project.
* `android-template/` - the Java source copied into every generated app.
* `platform.json` - SDK levels, Gradle, AGP and AndroidX versions. Change them here, not in code.
* `tools/` - bundler and config preparation. `tests/` - self-tests.
* `docs/ARCHITECTURE.md` - details. `docs/workflows/` - copies of the two workflow files.

## Updating from a phone
Upload one `update.zip` to the main folder. The **Apply update.zip** workflow checks it, applies it, deletes it, and
commits the result. Updates may only change: `src/ engine/ features/ android-template/ tools/ tests/ docs/`,
`generate.py`, `platform.json`, `README.md`. They can never touch `.github/`, `.git/`, `config.json` or hidden files.
Workflow files are changed by hand (copies are in `docs/workflows/`).
