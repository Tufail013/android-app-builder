#!/usr/bin/env python3
"""Prepares config.json for a build: migrates it to schema 2, embeds the newest page, and makes sure required features are on.
Usage: python tools/prepare_config.py [--config config.json] [--ensure file_picker,file_save] [--html path]"""
import argparse, glob, json, os, re, sys
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
from engine import migrate, resolver

ap = argparse.ArgumentParser()
ap.add_argument('--config', default='config.json')
ap.add_argument('--ensure', default='file_picker,file_save')
ap.add_argument('--html', default='')
a = ap.parse_args()
reg = resolver.load_registry()
try:
    norm = migrate.normalize(json.load(open(a.config, encoding='utf-8')), reg)
except FileNotFoundError:
    sys.exit('ERROR: %s was not found. Upload it to the repository first.' % a.config)
page = a.html
if not page and os.path.exists('dist/index.html'):
    page = 'dist/index.html'
if not page:
    cands = glob.glob('index*.html')
    if cands:
        page = max(cands, key=lambda x: int((re.findall(r'\d+', x) or ['-1'])[-1]))
if page:
    norm['config']['src']['html'] = open(page, encoding='utf-8').read()
    print('Embedded page:', page)
else:
    print('No page file found; using the page already inside', a.config)
sel = set(norm['selected']) | {x for x in a.ensure.split(',') if x}
order = [f['id'] for f in reg['features']]
norm['selected'] = [i for i in order if i in sel] + [i for i in sel if i not in order]
json.dump(migrate.to_v2(norm), open(a.config, 'w', encoding='utf-8'))
for n in norm['notes']:
    print(n)
print('Selected features:', ', '.join(norm['selected']))
