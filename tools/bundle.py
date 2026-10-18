#!/usr/bin/env python3
"""Builds one self-contained builder page (dist/index.html) from src/builder/ and features/registry.json."""
import argparse,glob,json,os,sys
ap=argparse.ArgumentParser()
ap.add_argument('--out',default='dist/index.html')
ap.add_argument('--no-registry',action='store_true')
a=ap.parse_args()
B='src/builder'
tpl=open(B+'/index.template.html',encoding='utf-8').read()
css=open(B+'/styles.css',encoding='utf-8').read()
parts=[]
if not a.no_registry:
    reg=json.dumps(json.load(open('features/registry.json',encoding='utf-8')),separators=(',',':')).replace('<','\\u003c')
    parts.append('const REGISTRY='+reg+';\n')
for p in sorted(glob.glob(B+'/js/*.js')):parts.append(open(p,encoding='utf-8').read())
html=tpl.replace('/*__STYLES__*/\n',css,1).replace('/*__SCRIPT__*/\n',''.join(parts),1)
os.makedirs(os.path.dirname(a.out) or '.',exist_ok=True)
open(a.out,'w',encoding='utf-8').write(html)
print('Bundled',a.out,len(html),'bytes from',len(parts),'parts')
