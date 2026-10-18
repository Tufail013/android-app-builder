#!/usr/bin/env python3
"""Builds an Android project from config.json (schema 1 or 2). Usage: python generate.py [--config config.json] [--out build_app] [--lenient]"""
import argparse, json, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from engine import migrate, resolver, projectgen

ap = argparse.ArgumentParser()
ap.add_argument('--config', default='config.json')
ap.add_argument('--out', default='build_app')
ap.add_argument('--registry', default='features/registry.json')
ap.add_argument('--platform', default='platform.json')
ap.add_argument('--template', default='android-template')
ap.add_argument('--report', default='build-report/build-report.json')
ap.add_argument('--lenient', action='store_true', help='skip planned (not yet implemented) features instead of failing')
a = ap.parse_args()

reg = resolver.load_registry(a.registry)
plat = json.load(open(a.platform, encoding='utf-8'))
try:
    raw = json.load(open(a.config, encoding='utf-8'))
    norm = migrate.normalize(raw, reg)
except FileNotFoundError:
    sys.exit('ERROR: %s was not found. Export it from the builder (last step, "Download config.json") and upload it.' % a.config)
except (ValueError, KeyError) as e:
    sys.exit('ERROR: %s could not be read: %s' % (a.config, e))
min_sdk = int(norm['config']['app'].get('min') or 21)
res = resolver.resolve(reg, norm['selected'], min_sdk, lenient=a.lenient)
errors = [i for i in res['issues'] if i['level'] == 'error']
report = {'schema': 1, 'source_schema': norm['source_schema'], 'migrations': norm['notes'], 'enabled': [{'id': i, 'origin': res['origin'][i]} for i in res['enabled']],
          'dropped_planned': res['dropped'], 'issues': res['issues'], 'permission_details': []}
for p in res['permissions']:
    m = reg['permissions'].get(p['name'], {})
    report['permission_details'].append({'name': p['name'], 'title': m.get('title'), 'why': m.get('why'), 'required_by': p['required_by'], 'if_denied': m.get('if_denied')})
os.makedirs(os.path.dirname(a.report), exist_ok=True)
if errors:
    json.dump(report, open(a.report, 'w'), indent=1)
    print('BUILD STOPPED - fix these problems in your config:')
    for e in errors:
        print(' -', e['msg'])
    sys.exit(1)
info = projectgen.generate(norm, res, reg, plat, a.out, a.template)
report['sdk'] = {k: info[k] for k in ('minSdk', 'targetSdk', 'compileSdk')}
report['warnings'] = info['notes'] + [i['msg'] for i in res['issues']]
report['manifest_permissions'] = info['permissions']
report['manual_permissions'] = info['manual_permissions']
json.dump(report, open(a.report, 'w'), indent=1)
print('Generated', norm['config']['app']['pkg'], '| minSdk', info['minSdk'], 'targetSdk', info['targetSdk'], 'compileSdk', info['compileSdk'])
print('Features:', ', '.join(res['enabled']))
for w in report['warnings']:
    print('NOTE:', w)
