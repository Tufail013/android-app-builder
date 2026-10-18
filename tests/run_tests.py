#!/usr/bin/env python3
"""Self-tests: registry rules, resolver cases, migration, project generation, and the update.zip checker."""
import importlib.util, json, os, re, shutil, stat, subprocess, sys, tempfile, textwrap, zipfile
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
os.chdir(ROOT)
sys.path.insert(0, ROOT)
from engine import migrate, resolver

fails = []


def check(cond, msg):
    print(('ok   ' if cond else 'FAIL ') + msg)
    if not cond:
        fails.append(msg)


reg = resolver.load_registry()
feats = {f['id']: f for f in reg['features']}
# ---- registry rules ----
check(len(feats) == len(reg['features']), 'feature ids are unique')
need = ['summary', 'does', 'benefit', 'cost', 'if_off', 'preview', 'test']
for f in reg['features']:
    ok = f['cat'] in reg['categories'] and f['status'] in ('available', 'planned')
    ok = ok and all(d in feats for d in f.get('deps', [])) and all(c in feats for c in f.get('conflicts', []))
    ok = ok and all(p in reg['permissions'] for p in f.get('perms', []))
    ok = ok and (f['status'] == 'available' or 'phase' in f)
    if f['status'] == 'available':
        ok = ok and all(k in f for k in need)
    for r in f.get('detect', []):
        for pat in r[1:]:
            try:
                re.compile(pat)
            except re.error:
                ok = False
    if not ok:
        check(False, 'registry entry is valid: ' + f['id'])
check(not any('registry entry is valid' in x for x in fails), 'every registry entry is complete and consistent')
# ---- resolver cases ----
for c in json.load(open('tests/fixtures/resolver_cases.json')):
    r = resolver.closure(reg, c['selected'], c['appMin'])
    e = c['expect']
    check(r['enabled'] == e['enabled'] and [p['name'] for p in r['permissions']] == e['permissions'] and sorted(i['code'] for i in r['issues']) == e['issue_codes'], 'resolver: ' + c['name'])
# ---- migration ----
n1 = migrate.normalize(json.load(open('tests/fixtures/config_v1_full.json')), reg)
check(n1['selected'] == ['notifications', 'scheduled_reminders', 'boot_recovery', 'exact_alarms', 'vibration', 'file_picker', 'file_save', 'screen_transitions'] and n1['settings']['anim'] == 'slide', 'migration v1 -> v2 keeps every enabled feature')
n2 = migrate.normalize(migrate.to_v2(n1), reg)
check(n2['selected'] == n1['selected'] and n2['source_schema'] == 2, 'v2 round trip is stable')
check(migrate.normalize(json.load(open('tests/fixtures/config_v1_mcq.json')), reg)['selected'] == [], 'migration of an app with no features selects nothing')
# ---- generation ----


def gen(cfg_path, *extra):
    out = tempfile.mkdtemp()
    p = subprocess.run([sys.executable, 'generate.py', '--config', cfg_path, '--out', out + '/b', '--report', out + '/r.json', *extra], capture_output=True, text=True)
    return p, out


def rd(out, rel):
    return open(out + '/b/app/src/main/' + rel, encoding='utf-8').read()


p, out = gen('tests/fixtures/config_v1_mcq.json')
man = rd(out, 'AndroidManifest.xml') if p.returncode == 0 else ''
check(p.returncode == 0, 'generate: offline quiz app builds a project')
check('ACCESS_NETWORK_STATE' in man and not any(x in man for x in ('CAMERA', 'RECORD_AUDIO', 'LOCATION', 'BLUETOOTH', 'NFC', 'POST_NOTIFICATIONS', 'BOOT_COMPLETED', 'AlarmReceiver')), 'generate: a quiz app gets no camera, mic, location, Bluetooth, NFC or reminder parts')
gr = open(out + '/b/app/build.gradle').read() if p.returncode == 0 else ''
check('targetSdk 36' in gr and 'compileSdk 36' in gr, 'generate: target and compile SDK come from platform.json (36)')
check('enableOnBackInvokedCallback' in man, 'generate: predictive back is declared')
cj = json.loads(rd(out, 'assets/config.json')) if p.returncode == 0 else {'config': {'feat': {'notify': True}}}
check(cj['config']['feat']['notify'] is False and cj['config']['feat']['anim'] == 'none', 'generate: runtime flags are off when features are off')
p, out = gen('tests/fixtures/config_v1_full.json')
man = rd(out, 'AndroidManifest.xml') if p.returncode == 0 else ''
for x in ('POST_NOTIFICATIONS', 'SCHEDULE_EXACT_ALARM', 'RECEIVE_BOOT_COMPLETED', 'VIBRATE', 'INTERNET', '.AlarmReceiver', '.BootReceiver', 'BOOT_COMPLETED'):
    check(x in man, 'generate: feature-driven manifest contains ' + x)
cj = json.loads(rd(out, 'assets/config.json'))['config']['feat'] if p.returncode == 0 else {}
check(cj.get('reminders') and cj.get('exactAlarms') and cj.get('files') and cj.get('anim') == 'slide', 'generate: legacy runtime flags match the enabled features')
v2 = migrate.to_v2(migrate.normalize(json.load(open('tests/fixtures/config_v1_mcq.json')), reg))
v2['features']['selected'] = ['camera_capture']
tmp = tempfile.mkdtemp()
json.dump(v2, open(tmp + '/c.json', 'w'))
p, out = gen(tmp + '/c.json')
check(p.returncode == 1 and 'not implemented' in p.stdout, 'generate: a planned feature stops the build with a clear message')
p, out = gen(tmp + '/c.json', '--lenient')
check(p.returncode == 0 and 'CAMERA' not in rd(out, 'AndroidManifest.xml'), 'generate: --lenient skips planned features without adding their permissions')
# ---- update.zip checker (script is embedded in the workflow so an update cannot change it) ----
wf = '.github/workflows/apply-update.yml'
if os.path.exists(wf):
    m = re.search(r"<<'PYEOF'\n(.*?)\n\s*PYEOF\n", open(wf).read(), re.S)
    d = tempfile.mkdtemp()
    open(d + '/au.py', 'w').write(textwrap.dedent(m.group(1)) + '\n')
    spec = importlib.util.spec_from_file_location('au', d + '/au.py')
    au = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(au)

    def mkzip(entries, symlink=None):
        zp = tempfile.mktemp(suffix='.zip')
        with zipfile.ZipFile(zp, 'w') as z:
            for n, data in entries:
                z.writestr(n, data)
            if symlink:
                zi = zipfile.ZipInfo(symlink)
                zi.external_attr = (stat.S_IFLNK | 0o777) << 16
                z.writestr(zi, 'x')
        return zp

    def rejected(entries, **kw):
        root = tempfile.mkdtemp()
        try:
            au.apply(mkzip(entries, **kw), root)
            return False
        except au.Bad:
            return not any(files for _, _, files in os.walk(root))
    root = tempfile.mkdtemp()
    r = au.apply(mkzip([('update.json', '{"name":"t","build":true}'), ('src/a.txt', 'x'), ('platform.json', '{}')]), root)
    check(os.path.exists(root + '/src/a.txt') and r['build'] is True, 'update: a valid package is applied')
    for label, ents in {'path traversal': [('../evil.txt', 'x')], 'nested traversal': [('src/../../evil.txt', 'x')], 'absolute path': [('/etc/x', 'x')], 'backslash path': [('src\\a.txt', 'x')],
                        'workflow file': [('.github/workflows/x.yml', 'x')], 'hidden file': [('src/.secret', 'x')], 'config.json': [('config.json', '{}')],
                        'unknown folder': [('other/a.txt', 'x')], 'case-duplicate': [('src/a.txt', 'x'), ('SRC/A.txt', 'y')], 'delete traversal': [('update.json', '{"delete":["../x"]}'), ('src/a.txt', 'x')],
                        'delete workflow': [('update.json', '{"delete":[".github/workflows/build.yml"]}'), ('src/a.txt', 'x')]}.items():
        check(rejected(ents), 'update: rejects ' + label + ' and changes nothing')
    check(rejected([('src/a.txt', 'x')], symlink='src/link'), 'update: rejects symbolic links and changes nothing')
    check(rejected([('src/ok.txt', 'x'), ('../bad.txt', 'x')]), 'update: one bad entry blocks the whole package')
else:
    print('skip update checker tests (workflow file not found)')
print('\n%d check(s) failed' % len(fails) if fails else '\nAll checks passed')
sys.exit(1 if fails else 0)
