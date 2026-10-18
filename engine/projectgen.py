"""Writes a complete Android (Gradle) project from a normalized config and a resolved feature set."""
import base64, io, json, os, re, shutil
from xml.sax.saxutils import escape
from PIL import Image, ImageDraw, ImageOps

HEX = re.compile(r'#[0-9a-fA-F]{6}')
PKG = re.compile(r'[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+')
PERM = re.compile(r'[A-Z][A-Z0-9_]*')
NS = 'xmlns:android="http://schemas.android.com/apk/res/android"'


def hx(v, k='#000000'):
    return v if isinstance(v, str) and HEX.fullmatch(v) else k


def sdk_levels(a, plat):
    notes = []
    req_min, req_t = int(a.get('min') or 21), int(a.get('target') or 0)
    mn = max(plat['minSdkFloor'], req_min)
    if mn > req_min:
        notes.append('minSdk raised from %d to %d (minimum supported by the AndroidX libraries in platform.json).' % (req_min, mn))
    if plat.get('allowLowerTarget'):
        tg = max(mn, min(req_t or plat['targetSdk'], plat['targetSdk']))
    else:
        tg = plat['targetSdk']
        if req_t and req_t < tg:
            notes.append('targetSdk raised from %d to %d (Google Play requires the current target level; see platform.json).' % (req_t, tg))
    return mn, tg, max(plat['compileSdk'], tg), notes


def _img(data):
    if isinstance(data, str) and data.startswith('data:image/'):
        try:
            return Image.open(io.BytesIO(base64.b64decode(data.split(',', 1)[1]))).convert('RGBA')
        except Exception as e:
            print('Image problem, using default:', e)
    return None


def generate(norm, res, reg, plat, out, template_dir, extra_perms_ok=True):
    c, a, d, sp = norm['config'], norm['config']['app'], norm['config']['disp'], norm['config']['splash']
    pkg = a['pkg']
    if not PKG.fullmatch(pkg):
        raise ValueError('Invalid package name: %r (use lowercase segments like com.example.app).' % pkg)
    mn, tg, comp, sdk_notes = sdk_levels(a, plat)
    vc = int(a['vc'])
    vn = re.sub(r'[\'"\\]', '', str(a['vn']))[:30]
    shutil.rmtree(out, ignore_errors=True)
    M = out + '/app/src/main'
    J = M + '/java/' + pkg.replace('.', '/')
    R = M + '/res'
    for p in [J, R + '/values', R + '/anim', R + '/mipmap-xxxhdpi', R + '/drawable-nodpi', M + '/assets/www']:
        os.makedirs(p)

    def W(p, s):
        with open(p, 'w', encoding='utf-8') as fh:
            fh.write(s)

    # ---- gradle ----
    W(out + '/settings.gradle', "pluginManagement{repositories{google();mavenCentral();gradlePluginPortal()}}\ndependencyResolutionManagement{repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS);repositories{google();mavenCentral()}}\nrootProject.name='App'\ninclude ':app'\n")
    W(out + '/build.gradle', "plugins{id 'com.android.application' version '%s' apply false}\n" % plat['agp'])
    W(out + '/gradle.properties', 'android.useAndroidX=true\norg.gradle.jvmargs=-Xmx2g\n')
    keys = list(plat.get('baseDeps', [])) + [g for g in res['gradle'] if g not in plat.get('baseDeps', [])]
    deps = ''.join(" implementation '%s'\n" % plat['deps'][k] for k in keys)
    W(out + '/app/build.gradle', "plugins{id 'com.android.application'}\nandroid{\n namespace '%s'\n compileSdk %d\n defaultConfig{applicationId '%s';minSdk %d;targetSdk %d;versionCode %d;versionName '%s'}\n compileOptions{sourceCompatibility JavaVersion.VERSION_17;targetCompatibility JavaVersion.VERSION_17}\n}\ndependencies{\n%s}\n" % (pkg, comp, pkg, mn, tg, vc, vn, deps))
    # ---- manifest ----
    manual = [x for x in c.get('perms', []) if isinstance(x, str) and PERM.fullmatch(x)]
    perms = sorted({p['name'] for p in res['permissions']} | set(manual))
    permx = ''.join('  <uses-permission android:name="android.permission.%s"/>\n' % x for x in perms)
    rec = ''
    for r in res['receivers']:
        acts = ''.join('<action android:name="%s"/>' % escape(x) for x in r.get('actions', []))
        inner = '<intent-filter>%s</intent-filter>' % acts if acts else ''
        rec += '    <receiver android:name="%s" android:exported="%s">%s</receiver>\n' % (escape(r['name']), 'true' if r.get('exported') else 'false', inner)
    sch = a.get('scheme') or ''
    flt = '<intent-filter><action android:name="android.intent.action.VIEW"/><category android:name="android.intent.category.DEFAULT"/><category android:name="android.intent.category.BROWSABLE"/><data android:scheme="%s"/></intent-filter>' % sch if re.fullmatch(r'[a-z][a-z0-9+.-]*', sch) else ''
    W(M + '/AndroidManifest.xml', '<?xml version="1.0" encoding="utf-8"?>\n<manifest xmlns:android="http://schemas.android.com/apk/res/android">\n' + permx +
      '  <application android:allowBackup="false" android:enableOnBackInvokedCallback="true" android:icon="@mipmap/ic_launcher" android:label="@string/app_name" android:theme="@style/AppTheme">\n'
      '    <activity android:name=".MainActivity" android:exported="true" android:configChanges="orientation|screenSize|keyboardHidden|smallestScreenSize|screenLayout|density|uiMode">\n'
      '      <intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter>\n      ' + flt + '\n    </activity>\n' + rec + '  </application>\n</manifest>\n')
    # ---- resources ----
    nm = escape(str(a['name'])).replace("'", "\\'").replace('"', '\\"')
    W(R + '/values/strings.xml', '<resources><string name="app_name">%s</string></resources>' % nm)
    W(R + '/values/colors.xml', '<resources><color name="bg">%s</color></resources>' % hx(d.get('loadBg')))
    W(R + '/values/styles.xml', '<resources><style name="AppTheme" parent="Theme.AppCompat.DayNight.NoActionBar"><item name="android:windowBackground">@color/bg</item></style></resources>')
    al = lambda x, y: '<alpha %s android:fromAlpha="%s" android:toAlpha="%s" android:duration="300"/>' % (NS, x, y)
    en = set(res['enabled'])
    an = norm['settings'].get('anim', 'fade') if 'screen_transitions' in en else 'none'
    if an == 'slide':
        E = '<translate %s android:fromXDelta="100%%p" android:toXDelta="0" android:duration="300"/>' % NS
        X = '<translate %s android:fromXDelta="0" android:toXDelta="-30%%p" android:duration="300"/>' % NS
    elif an == 'zoom':
        E = '<set %s><scale android:fromXScale="0.85" android:toXScale="1" android:fromYScale="0.85" android:toYScale="1" android:pivotX="50%%" android:pivotY="50%%" android:duration="300"/><alpha android:fromAlpha="0" android:toAlpha="1" android:duration="300"/></set>' % NS
        X = al(1, 0)
    else:
        E, X = al(0, 1), al(1, 0)
    W(R + '/anim/enter.xml', E)
    W(R + '/anim/exit.xml', X)
    # ---- icons ----
    ic = _img(c['icon'].get('data'))
    if ic is None:
        ic = Image.new('RGBA', (192, 192), hx(d.get('primary'), '#22d3ee'))
        ImageDraw.Draw(ic).ellipse((56, 56, 136, 136), fill='white')
    ic = ImageOps.fit(ic, (192, 192))
    ic.save(R + '/mipmap-xxxhdpi/ic_launcher.png')
    si = _img(sp['img'].get('data'))
    (ImageOps.fit(si, (256, 256)) if si else ic).save(R + '/drawable-nodpi/splash_img.png')
    # ---- assets: page + runtime config (legacy-compatible "feat" map for the current Java template) ----
    html = c['src'].get('html', '')
    W(M + '/assets/www/index.html', html)
    rc = json.loads(json.dumps(c))
    rc['src'].pop('html', None)
    rc['icon']['data'] = ''
    rc['splash']['img']['data'] = ''
    feat = {}
    for f in reg['features']:
        if f.get('legacy'):
            feat[f['legacy']] = feat.get(f['legacy'], False) or f['id'] in en
    feat['anim'] = an
    feat['exactAlarms'] = 'exact_alarms' in en
    rc['feat'] = feat
    W(M + '/assets/config.json', json.dumps({'config': rc, 'enabled': res['enabled'], 'schema': 2}))
    # ---- java from template ----
    for fn in sorted(os.listdir(template_dir + '/java')):
        with open(template_dir + '/java/' + fn, encoding='utf-8') as fh:
            W(J + '/' + fn, fh.read().replace('__PKG__', pkg))
    return {'minSdk': mn, 'targetSdk': tg, 'compileSdk': comp, 'notes': sdk_notes, 'permissions': perms, 'manual_permissions': manual, 'anim': an}
