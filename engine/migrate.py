"""Config migration: schema 1 (hab-build-config-1, boolean feat flags) -> schema 2 (feature ids)."""
import copy

DEFAULTS = {
    'app': {'name': 'App', 'pkg': 'com.example.app', 'vc': '1', 'vn': '1.0', 'min': '24', 'target': '36', 'max': '', 'scheme': ''},
    'src': {'type': 'blank', 'file': '', 'html': '<!DOCTYPE html><html><body></body></html>'},
    'disp': {'orient': 'auto', 'status': True, 'action': False, 'keepOn': False, 'cutout': 'default', 'desktop': False, 'wm': False,
             'primary': '#22d3ee', 'loadBg': '#000000', 'statusColor': '#000000', 'navColor': '#000000', 'transp': False, 'tint': 'auto'},
    'perms': [],
    'icon': {'name': '', 'data': ''},
    'splash': {'on': True, 'bg': '#000000', 'style': 'ring', 'loader': '#22d3ee', 'ring': '#333333', 'img': {'name': '', 'data': ''},
               'iconSize': '96', 'radius': '20', 'loaderSize': '40', 'gap': '24', 'dur': '2000', 'thick': '4', 'fade': '300'},
    'wv': {},
    'ads': {'on': False},
    'out': {'format': 'apk'},
}


def fill(d, defaults):
    for k, v in defaults.items():
        if k not in d or d[k] is None:
            d[k] = copy.deepcopy(v)
        elif isinstance(v, dict) and isinstance(d[k], dict):
            fill(d[k], v)
    return d


def legacy_map(reg):
    m = {}
    for f in reg['features']:
        if f.get('legacy'):
            m.setdefault(f['legacy'], []).append(f['id'])
    return m


def normalize(raw, reg):
    """Return {'config','selected','settings','source_schema','notes'} regardless of input schema."""
    notes = []
    if not isinstance(raw, dict) or 'config' not in raw:
        raise ValueError('config.json is not a builder export (missing "config" section).')
    cfg = copy.deepcopy(raw['config'])
    order = [f['id'] for f in reg['features']]
    if raw.get('schema') == 2 or raw.get('format') == 'hab-build-config-2':
        src_schema = 2
        feats = raw.get('features') or {}
        selected = list(feats.get('selected') or [])
        settings = dict(feats.get('settings') or {})
    else:
        src_schema = 1
        ft = cfg.pop('feat', None) or {}
        lm = legacy_map(reg)
        s = set()
        for key, ids in lm.items():
            if ft.get(key) is True:
                s.update(ids)
        anim = ft.get('anim', 'none')
        if anim and anim != 'none':
            s.add('screen_transitions')
        selected = [i for i in order if i in s]
        settings = {'anim': anim if anim and anim != 'none' else 'fade'}
        notes.append('Migrated config from schema 1 to schema 2 (feature flags became feature ids: %s).' % (', '.join(selected) or 'none'))
    cfg.pop('feat', None)
    fill(cfg, DEFAULTS)
    known = [i for i in order if i in set(selected)]
    unknown = [i for i in selected if i not in set(order)]
    return {'config': cfg, 'selected': known + unknown, 'settings': settings, 'source_schema': src_schema, 'notes': notes}


def to_v2(norm):
    return {'format': 'hab-build-config-2', 'schema': 2, 'buildPerformed': False, 'config': norm['config'],
            'features': {'selected': norm['selected'], 'settings': norm['settings']}}
