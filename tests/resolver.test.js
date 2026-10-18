// Runs the builder's feature engine in Node and compares it with the shared fixtures (parity with engine/resolver.py).
const fs = require('fs'), path = require('path'), assert = require('assert');
const root = path.join(__dirname, '..');
const REGISTRY = JSON.parse(fs.readFileSync(path.join(root, 'features/registry.json'), 'utf8'));
const src = fs.readFileSync(path.join(root, 'src/builder/js/05_engine.js'), 'utf8');
const E = new Function('REGISTRY', 'esc', 'P', src + ';return {closure,detect,resolveProject,toV2,selectedFromProject}')(REGISTRY, s => s, {});
let n = 0;
const ok = m => { n++; console.log('ok   ' + m); };
for (const c of JSON.parse(fs.readFileSync(path.join(root, 'tests/fixtures/resolver_cases.json'), 'utf8'))) {
  const r = E.closure(c.selected, c.appMin);
  assert.deepStrictEqual(r.enabled, c.expect.enabled, c.name);
  assert.deepStrictEqual(r.permissions.map(p => p.name), c.expect.permissions, c.name);
  assert.deepStrictEqual(r.issues.map(i => i.code).sort(), c.expect.issue_codes, c.name);
  ok('parity with Python: ' + c.name);
}
const proj = (html, feat) => ({ app: { min: '24' }, perms: [], src: { html }, feat: Object.assign({ anim: 'none' }, feat || {}) });
const st = (r, id) => r.features.find(x => x.id === id);
let r = E.resolveProject(proj('<h1>Quiz</h1><p>Question 1</p>'));
assert.strictEqual(r.features.filter(x => x.show).length, 0); ok('plain quiz page: nothing recommended, nothing suggested');
r = E.resolveProject(proj('<button onclick="Android.notify(1,2)">x</button>'));
assert.strictEqual(st(r, 'notifications').state, 'REQUIRED'); assert.ok(st(r, 'notifications').missing); ok('bridge call without the feature: Required and flagged as missing');
r = E.resolveProject(proj('<input type="file">'));
assert.strictEqual(st(r, 'file_picker').state, 'RECOMMENDED'); assert.ok(r.suggestions.some(s => s.id === 'file_picker')); ok('file input: file picker recommended');
r = E.resolveProject(proj('<p>Account expiry</p><script>new Date()</script>'));
assert.strictEqual(st(r, 'scheduled_reminders').state, 'RECOMMENDED'); ok('expiry-like logic: reminders recommended (low confidence)');
r = E.resolveProject(proj('<script>navigator.geolocation.getCurrentPosition()</script>'));
assert.strictEqual(st(r, 'location_foreground').state, 'UNSUPPORTED'); ok('location use: reported as not available yet');
r = E.resolveProject(proj('<script>navigator.serviceWorker.register("a")</script>'));
assert.ok(r.unsupported.some(u => u.id === 'service_workers')); ok('service worker: reported as unsupported');
r = E.resolveProject(proj('x', { reminders: true }));
assert.strictEqual(st(r, 'notifications').state, 'REQUIRED'); assert.strictEqual(st(r, 'exact_alarms').flags.includes('CAUTION'), true); ok('reminders pull in notifications; exact alarms carry a caution');
const v2 = E.toV2(proj('x', { notify: true, files: true, anim: 'zoom' }));
assert.deepStrictEqual(v2.features.selected, ['notifications', 'file_picker', 'file_save', 'screen_transitions']);
assert.strictEqual(v2.features.settings.anim, 'zoom'); assert.strictEqual(v2.config.feat, undefined); assert.strictEqual(v2.schema, 2); ok('export to config schema 2');
console.log('\nAll ' + n + ' checks passed');
