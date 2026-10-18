"""Feature resolver: dependency closure, conflicts, unsupported features, permissions, manifest parts.
Mirrors src/builder/js/05_engine.js (closure). Recommendation states are computed in the builder page."""
import json


def load_registry(path='features/registry.json'):
    with open(path, encoding='utf-8') as fh:
        return json.load(fh)


def closure(reg, selected, app_min):
    feats = {f['id']: f for f in reg['features']}
    order = [f['id'] for f in reg['features']]
    issues, origin = [], {}
    for f in reg['features']:
        if f.get('core'):
            origin[f['id']] = 'core'
    for i in selected:
        if i not in feats:
            issues.append({'level': 'error', 'code': 'UNKNOWN_FEATURE', 'id': i, 'msg': 'Unknown feature: ' + i})
        elif i not in origin:
            origin[i] = 'selected'
    q = list(origin.keys())
    n = 0
    while n < len(q):
        for d in feats[q[n]].get('deps', []):
            if d not in feats:
                issues.append({'level': 'error', 'code': 'UNKNOWN_FEATURE', 'id': d, 'msg': 'Unknown dependency: ' + d})
                continue
            if d not in origin:
                origin[d] = 'dependency'
                q.append(d)
        n += 1
    enabled = [i for i in order if i in origin]
    seen = set()
    for i in enabled:
        f = feats[i]
        for c in f.get('conflicts', []):
            if c in origin:
                k = '|'.join(sorted([i, c]))
                if k not in seen:
                    seen.add(k)
                    issues.append({'level': 'error', 'code': 'CONFLICT', 'id': i, 'msg': f['name'] + ' conflicts with ' + feats[c]['name']})
        if f.get('status') == 'planned':
            issues.append({'level': 'error', 'code': 'UNSUPPORTED', 'id': i, 'msg': '%s is not implemented yet (planned for phase %s)' % (f['name'], f.get('phase'))})
        if f.get('min_sdk', 21) > app_min:
            issues.append({'level': 'warning', 'code': 'API_LEVEL', 'id': i, 'msg': '%s needs Android API %s or newer' % (f['name'], f.get('min_sdk'))})
    perms = {}
    for i in enabled:
        for p in feats[i].get('perms', []):
            perms.setdefault(p, []).append(i)
    return {'enabled': enabled, 'origin': origin,
            'issues': issues, 'permissions': [{'name': n, 'required_by': perms[n]} for n in sorted(perms)]}


def resolve(reg, selected, app_min, lenient=False):
    feats = {f['id']: f for f in reg['features']}
    dropped = []
    if lenient:
        dropped = [i for i in selected if i in feats and feats[i].get('status') == 'planned']
        selected = [i for i in selected if i not in dropped]
    r = closure(reg, selected, app_min)
    en = r['enabled']
    r['dropped'] = dropped
    r['gradle'] = sorted({g for i in en for g in feats[i].get('gradle', [])})
    r['receivers'] = [x for i in en for x in feats[i].get('manifest', {}).get('receivers', [])]
    return r
