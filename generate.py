# Reads config.json (exported from the builder) and writes a full Android project into build_app/
import json,os,re,sys,io,base64,shutil
from xml.sax.saxutils import escape
from PIL import Image,ImageDraw,ImageOps
cfg=json.load(open('config.json',encoding='utf-8'))
c=cfg['config'];a=c['app'];d=c['disp'];f=c['feat'];sp=c['splash']
pkg=a['pkg']
if not re.fullmatch(r'[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+',pkg):sys.exit('Invalid package name: '+pkg)
HEX=re.compile(r'#[0-9a-fA-F]{6}')
hx=lambda v,k='#000000':v if isinstance(v,str) and HEX.fullmatch(v) else k
mn=max(21,int(a['min']));tg=max(mn,min(34,int(a['target'])));vc=int(a['vc']);vn=re.sub(r'[\'"\\]','',str(a['vn']))[:30]
B='build_app';shutil.rmtree(B,ignore_errors=True)
M=B+'/app/src/main';J=M+'/java/'+pkg.replace('.','/');R=M+'/res'
for p in [J,R+'/values',R+'/anim',R+'/mipmap-xxxhdpi',R+'/drawable-nodpi',M+'/assets/www']:os.makedirs(p)
def W(p,s):open(p,'w',encoding='utf-8').write(s)
# ---- gradle ----
W(B+'/settings.gradle',"pluginManagement{repositories{google();mavenCentral();gradlePluginPortal()}}\ndependencyResolutionManagement{repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS);repositories{google();mavenCentral()}}\nrootProject.name='App'\ninclude ':app'\n")
W(B+'/build.gradle',"plugins{id 'com.android.application' version '8.5.2' apply false}\n")
W(B+'/gradle.properties','android.useAndroidX=true\norg.gradle.jvmargs=-Xmx2g\n')
W(B+'/app/build.gradle',f"""plugins{{id 'com.android.application'}}
android{{
 namespace '{pkg}'
 compileSdk 34
 defaultConfig{{applicationId '{pkg}';minSdk {mn};targetSdk {tg};versionCode {vc};versionName '{vn}'}}
 compileOptions{{sourceCompatibility JavaVersion.VERSION_17;targetCompatibility JavaVersion.VERSION_17}}
}}
dependencies{{
 implementation 'androidx.appcompat:appcompat:1.6.1'
 implementation 'androidx.biometric:biometric:1.1.0'
 implementation 'androidx.swiperefreshlayout:swiperefreshlayout:1.1.0'
 implementation 'androidx.webkit:webkit:1.8.0'
}}
""")
# ---- manifest ----
P=set(x for x in c.get('perms',[]) if re.fullmatch(r'[A-Z_]+',str(x)));P.add('ACCESS_NETWORK_STATE')
if f.get('notify') or f.get('reminders'):P.add('POST_NOTIFICATIONS')
if f.get('reminders'):P|={'SCHEDULE_EXACT_ALARM','RECEIVE_BOOT_COMPLETED'}
if f.get('vibrate'):P.add('VIBRATE')
if f.get('biometric'):P.add('USE_BIOMETRIC')
perm=''.join('  <uses-permission android:name="android.permission.%s"/>\n'%x for x in sorted(P))
sch=a.get('scheme') or ''
flt='<intent-filter><action android:name="android.intent.action.VIEW"/><category android:name="android.intent.category.DEFAULT"/><category android:name="android.intent.category.BROWSABLE"/><data android:scheme="%s"/></intent-filter>'%sch if re.fullmatch(r'[a-z][a-z0-9+.-]*',sch) else ''
W(M+'/AndroidManifest.xml','<?xml version="1.0" encoding="utf-8"?>\n<manifest xmlns:android="http://schemas.android.com/apk/res/android">\n'+perm+'''  <application android:allowBackup="false" android:icon="@mipmap/ic_launcher" android:label="@string/app_name" android:theme="@style/AppTheme">
    <activity android:name=".MainActivity" android:exported="true" android:configChanges="orientation|screenSize|keyboardHidden|smallestScreenSize|screenLayout|density|uiMode">
      <intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter>
      '''+flt+'''
    </activity>
    <receiver android:name=".AlarmReceiver" android:exported="false"/>
    <receiver android:name=".BootReceiver" android:exported="true"><intent-filter><action android:name="android.intent.action.BOOT_COMPLETED"/></intent-filter></receiver>
  </application>
</manifest>
''')
# ---- resources ----
nm=escape(str(a['name'])).replace("'","\\'").replace('"','\\"')
W(R+'/values/strings.xml','<resources><string name="app_name">%s</string></resources>'%nm)
W(R+'/values/colors.xml','<resources><color name="bg">%s</color></resources>'%hx(d.get('loadBg')))
W(R+'/values/styles.xml','<resources><style name="AppTheme" parent="Theme.AppCompat.DayNight.NoActionBar"><item name="android:windowBackground">@color/bg</item></style></resources>')
NS='xmlns:android="http://schemas.android.com/apk/res/android"'
al=lambda x,y:'<alpha %s android:fromAlpha="%s" android:toAlpha="%s" android:duration="300"/>'%(NS,x,y)
an=f.get('anim','fade')
if an=='slide':E='<translate %s android:fromXDelta="100%%p" android:toXDelta="0" android:duration="300"/>'%NS;X='<translate %s android:fromXDelta="0" android:toXDelta="-30%%p" android:duration="300"/>'%NS
elif an=='zoom':E='<set %s><scale android:fromXScale="0.85" android:toXScale="1" android:fromYScale="0.85" android:toYScale="1" android:pivotX="50%%" android:pivotY="50%%" android:duration="300"/><alpha android:fromAlpha="0" android:toAlpha="1" android:duration="300"/></set>'%NS;X=al(1,0)
else:E=al(0,1);X=al(1,0)
W(R+'/anim/enter.xml',E);W(R+'/anim/exit.xml',X)
# ---- icons ----
def img(data):
    if isinstance(data,str) and data.startswith('data:image/'):
        try:return Image.open(io.BytesIO(base64.b64decode(data.split(',',1)[1]))).convert('RGBA')
        except Exception as e:print('Image problem, using default:',e)
    return None
ic=img(c['icon'].get('data'))
if ic is None:
    ic=Image.new('RGBA',(192,192),hx(d.get('primary'),'#22d3ee'));ImageDraw.Draw(ic).ellipse((56,56,136,136),fill='white')
ic=ImageOps.fit(ic,(192,192));ic.save(R+'/mipmap-xxxhdpi/ic_launcher.png')
si=img(sp['img'].get('data'));si=ImageOps.fit(si,(256,256)) if si else ic;si.save(R+'/drawable-nodpi/splash_img.png')
# ---- assets (config without big data) ----
t=json.loads(json.dumps(cfg));tc=t['config']
html=tc['src'].pop('html');tc['icon']['data']='';tc['splash']['img']['data']=''
W(M+'/assets/www/index.html',html);W(M+'/assets/config.json',json.dumps(t))
# ---- java ----
W(J+'/AlarmReceiver.java','package __PKG__;\nimport android.content.*;\npublic class AlarmReceiver extends BroadcastReceiver{\n  @Override public void onReceive(Context c,Intent i){\n    MainActivity.show(c,i.getStringExtra("t"),i.getStringExtra("m"));\n    c.getSharedPreferences("alarms",0).edit().remove(i.getStringExtra("id")).apply();\n  }\n}\n'.replace('__PKG__',pkg))
W(J+'/BootReceiver.java','package __PKG__;\nimport android.content.*;\npublic class BootReceiver extends BroadcastReceiver{\n  @Override public void onReceive(Context c,Intent i){\n    SharedPreferences p=c.getSharedPreferences("alarms",0);\n    for(java.util.Map.Entry<String,?> e:p.getAll().entrySet()){\n      try{org.json.JSONObject o=new org.json.JSONObject((String)e.getValue());long ts=o.getLong("ts");\n        if(ts>System.currentTimeMillis())MainActivity.setAlarm(c,e.getKey(),ts,o.getString("t"),o.getString("m"),false);\n      }catch(Exception x){}\n    }\n  }\n}\n'.replace('__PKG__',pkg))
JAVA=r'''package __PKG__;

import android.app.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.graphics.*;
import android.net.*;
import android.os.*;
import android.provider.MediaStore;
import android.speech.tts.TextToSpeech;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.webkit.WebViewAssetLoader;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;

public class MainActivity extends AppCompatActivity {
  static final String HOST = "appassets.androidplatform.net";
  static final String CH = "app_channel";
  JSONObject root, d, w, f, sp;
  WebView web;
  SwipeRefreshLayout swipe;
  ProgressBar bar;
  FrameLayout main;
  LinearLayout lay;
  TextToSpeech tts;
  String pending;
  ValueCallback<Uri[]> chooser;
  boolean anim;

  static String read(InputStream in) throws IOException {
    ByteArrayOutputStream o = new ByteArrayOutputStream();
    byte[] b = new byte[8192];
    int n;
    while ((n = in.read(b)) > 0) o.write(b, 0, n);
    in.close();
    return o.toString("UTF-8");
  }
  int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
  static int col(String s, int def) { try { return Color.parseColor(s); } catch (Exception e) { return def; } }
  static float lum(int c) { return (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f; }

  @Override protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    try {
      root = new JSONObject(read(getAssets().open("config.json"))).getJSONObject("config");
      d = root.getJSONObject("disp"); w = root.getJSONObject("wv"); f = root.getJSONObject("feat"); sp = root.getJSONObject("splash");
    } catch (Exception e) { throw new RuntimeException(e); }
    anim = !f.optString("anim", "fade").equals("none");
    if (anim) overridePendingTransition(R.anim.enter, R.anim.exit);
    Window win = getWindow();
    String o = d.optString("orient", "auto");
    if (o.equals("portrait")) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    else if (o.equals("landscape")) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
    if (d.optBoolean("keepOn")) win.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    if (f.optBoolean("secure")) win.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    if (Build.VERSION.SDK_INT >= 28) {
      String cut = d.optString("cutout", "default");
      WindowManager.LayoutParams lp = win.getAttributes();
      lp.layoutInDisplayCutoutMode = cut.equals("shortEdges") ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        : cut.equals("never") ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
        : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
      win.setAttributes(lp);
    }
    boolean tr = d.optBoolean("transp");
    int sc = col(d.optString("statusColor"), 0xFF000000), nc = col(d.optString("navColor"), 0xFF000000), pc = col(d.optString("primary"), 0xFF22D3EE);
    win.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
    win.setStatusBarColor(tr ? Color.TRANSPARENT : sc);
    win.setNavigationBarColor(tr ? Color.TRANSPARENT : nc);
    String tint = d.optString("tint", "auto");
    boolean lightIcons = tint.equals("light") || (tint.equals("auto") && lum(sc) < 0.5f);
    int fl = win.getDecorView().getSystemUiVisibility();
    if (Build.VERSION.SDK_INT >= 23) fl = lightIcons ? (fl & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR) : (fl | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    if (Build.VERSION.SDK_INT >= 26) fl = lightIcons ? (fl & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR) : (fl | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    if (!d.optBoolean("status", true)) fl |= View.SYSTEM_UI_FLAG_FULLSCREEN;
    if (f.optBoolean("fullscreen")) fl |= View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
    win.getDecorView().setSystemUiVisibility(fl);

    main = new FrameLayout(this);
    main.setBackgroundColor(col(d.optString("loadBg"), 0xFF000000));
    lay = new LinearLayout(this);
    lay.setOrientation(LinearLayout.VERTICAL);
    if (d.optBoolean("action")) {
      TextView tb = new TextView(this);
      tb.setText(getString(R.string.app_name));
      tb.setTextSize(18);
      tb.setTextColor(lum(pc) > 0.5f ? Color.BLACK : Color.WHITE);
      tb.setBackgroundColor(pc);
      tb.setPadding(dp(16), dp(14), dp(16), dp(14));
      lay.addView(tb);
    }
    web = new WebView(this);
    swipe = new SwipeRefreshLayout(this);
    swipe.addView(web);
    swipe.setEnabled(w.optBoolean("refresh", true));
    swipe.setOnRefreshListener(() -> web.reload());
    swipe.setOnChildScrollUpCallback((p, c) -> web.getScrollY() > 0);
    lay.addView(swipe, new LinearLayout.LayoutParams(-1, 0, 1f));
    main.addView(lay, new FrameLayout.LayoutParams(-1, -1));
    bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    bar.setMax(100);
    bar.setVisibility(View.GONE);
    bar.getProgressDrawable().setColorFilter(pc, PorterDuff.Mode.SRC_IN);
    main.addView(bar, new FrameLayout.LayoutParams(-1, dp(3)));
    setContentView(main);

    WebSettings s = web.getSettings();
    s.setJavaScriptEnabled(w.optBoolean("js", true));
    s.setDomStorageEnabled(w.optBoolean("dom", true));
    s.setAllowFileAccess(w.optBoolean("file"));
    s.setAllowContentAccess(w.optBoolean("content"));
    s.setAllowUniversalAccessFromFileURLs(w.optBoolean("uFile"));
    s.setAllowFileAccessFromFileURLs(w.optBoolean("fileUrl"));
    s.setSupportZoom(w.optBoolean("zoom"));
    s.setBuiltInZoomControls(w.optBoolean("zoom"));
    s.setDisplayZoomControls(false);
    s.setUseWideViewPort(w.optBoolean("wide", true));
    s.setLoadWithOverviewMode(w.optBoolean("overview", true));
    s.setGeolocationEnabled(false);
    String cm = w.optString("cache", "default");
    s.setCacheMode(cm.equals("no-cache") ? WebSettings.LOAD_NO_CACHE : cm.equals("cache-else-network") ? WebSettings.LOAD_CACHE_ELSE_NETWORK : cm.equals("cache-only") ? WebSettings.LOAD_CACHE_ONLY : WebSettings.LOAD_DEFAULT);
    String mx = w.optString("mixed", "never");
    s.setMixedContentMode(mx.equals("always") ? WebSettings.MIXED_CONTENT_ALWAYS_ALLOW : mx.equals("compat") ? WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE : WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    if (d.optBoolean("desktop")) s.setUserAgentString(s.getUserAgentString().replace("Mobile", "X11"));

    final WebViewAssetLoader loader = new WebViewAssetLoader.Builder().addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this)).build();
    web.setWebViewClient(new WebViewClient() {
      @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) { return loader.shouldInterceptRequest(r.getUrl()); }
      @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
        Uri u = r.getUrl();
        if (HOST.equals(u.getHost())) return false;
        try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception e) { }
        return true;
      }
      @Override public void onPageStarted(WebView v, String u, Bitmap fav) { if (anim) v.setAlpha(0.2f); }
      @Override public void onPageFinished(WebView v, String u) { swipe.setRefreshing(false); v.animate().alpha(1f).setDuration(250).start(); }
      @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
        if (r.isForMainFrame() && w.optBoolean("noNet", true) && !online()) showNoNet();
      }
    });
    web.setWebChromeClient(new WebChromeClient() {
      @Override public void onProgressChanged(WebView v, int p) {
        if (w.optBoolean("indicator", true)) { bar.setProgress(p); bar.setVisibility(p < 100 ? View.VISIBLE : View.GONE); }
      }
      @Override public boolean onJsAlert(WebView v, String u, String m, final JsResult r) {
        new AlertDialog.Builder(MainActivity.this).setMessage(m).setPositiveButton(android.R.string.ok, (x, y) -> r.confirm()).setOnCancelListener(x -> r.cancel()).show();
        return true;
      }
      @Override public boolean onJsConfirm(WebView v, String u, String m, final JsResult r) {
        new AlertDialog.Builder(MainActivity.this).setMessage(m).setPositiveButton(android.R.string.ok, (x, y) -> r.confirm()).setNegativeButton(android.R.string.cancel, (x, y) -> r.cancel()).setOnCancelListener(x -> r.cancel()).show();
        return true;
      }
      @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams fp) {
        if (!f.optBoolean("files")) return false;
        if (chooser != null) chooser.onReceiveValue(null);
        chooser = cb;
        try { startActivityForResult(fp.createIntent(), 77); } catch (Exception e) { chooser = null; return false; }
        return true;
      }
    });
    web.addJavascriptInterface(new Bridge(), "Android");
    web.loadUrl("https://" + HOST + "/assets/www/index.html");

    if (sp.optBoolean("on", true)) addSplash();
    if (Build.VERSION.SDK_INT >= 33 && (f.optBoolean("notify") || f.optBoolean("reminders")))
      requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 5);
    if (f.optBoolean("biometric")) { lay.setVisibility(View.INVISIBLE); lock(); }
  }

  void addSplash() {
    final FrameLayout o = new FrameLayout(this);
    o.setBackgroundColor(col(sp.optString("bg"), 0xFF000000));
    o.setClickable(true);
    LinearLayout c = new LinearLayout(this);
    c.setOrientation(LinearLayout.VERTICAL);
    c.setGravity(Gravity.CENTER);
    ImageView iv = new ImageView(this);
    iv.setImageResource(R.drawable.splash_img);
    iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
    final float rad = dp(sp.optInt("radius", 20));
    iv.setOutlineProvider(new ViewOutlineProvider() {
      @Override public void getOutline(View v, Outline ol) { ol.setRoundRect(0, 0, v.getWidth(), v.getHeight(), rad); }
    });
    iv.setClipToOutline(true);
    int isz = dp(sp.optInt("iconSize", 96));
    c.addView(iv, new LinearLayout.LayoutParams(isz, isz));
    c.addView(new View(this), new LinearLayout.LayoutParams(1, dp(sp.optInt("gap", 24))));
    int lc = col(sp.optString("loader"), 0xFF22D3EE), lsz = dp(sp.optInt("loaderSize", 40));
    ProgressBar pb;
    if (sp.optString("style", "ring").equals("bar")) {
      pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
      pb.setIndeterminate(true);
      c.addView(pb, new LinearLayout.LayoutParams(lsz * 2, LinearLayout.LayoutParams.WRAP_CONTENT));
    } else {
      pb = new ProgressBar(this);
      c.addView(pb, new LinearLayout.LayoutParams(lsz, lsz));
    }
    pb.getIndeterminateDrawable().setColorFilter(lc, PorterDuff.Mode.SRC_IN);
    o.addView(c, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
    main.addView(o, new FrameLayout.LayoutParams(-1, -1));
    final int fade = sp.optInt("fade", 300);
    new Handler(Looper.getMainLooper()).postDelayed(() -> o.animate().alpha(0f).setDuration(fade).withEndAction(() -> main.removeView(o)).start(), sp.optInt("dur", 2000));
  }

  void lock() {
    int auth = Build.VERSION.SDK_INT >= 30 ? (BiometricManager.Authenticators.BIOMETRIC_WEAK | BiometricManager.Authenticators.DEVICE_CREDENTIAL) : BiometricManager.Authenticators.BIOMETRIC_WEAK;
    if (BiometricManager.from(this).canAuthenticate(auth) != BiometricManager.BIOMETRIC_SUCCESS) { lay.setVisibility(View.VISIBLE); return; }
    BiometricPrompt.PromptInfo.Builder pi = new BiometricPrompt.PromptInfo.Builder().setTitle(getString(R.string.app_name)).setAllowedAuthenticators(auth);
    if (Build.VERSION.SDK_INT < 30) pi.setNegativeButtonText("Cancel");
    BiometricPrompt bp = new BiometricPrompt(this, ContextCompat.getMainExecutor(this), new BiometricPrompt.AuthenticationCallback() {
      @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) { lay.setVisibility(View.VISIBLE); }
      @Override public void onAuthenticationError(int code, CharSequence msg) { finish(); }
    });
    bp.authenticate(pi.build());
  }

  boolean online() {
    try {
      NetworkInfo n = ((ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)).getActiveNetworkInfo();
      return n != null && n.isConnected();
    } catch (Exception e) { return true; }
  }
  void showNoNet() {
    new AlertDialog.Builder(this).setTitle("No internet").setMessage("Check your connection and try again.").setPositiveButton("Retry", (x, y) -> web.reload()).setNegativeButton("Close", null).show();
  }

  @Override public void onBackPressed() {
    if (w.optBoolean("backNav", true) && web.canGoBack()) { web.goBack(); return; }
    if (w.optBoolean("exitConfirm", true)) {
      new AlertDialog.Builder(this).setMessage("Exit app?").setPositiveButton("Exit", (x, y) -> finish()).setNegativeButton("Stay", null).show();
      return;
    }
    finish();
  }
  @Override public void finish() { super.finish(); if (anim) overridePendingTransition(R.anim.enter, R.anim.exit); }
  @Override protected void onActivityResult(int rq, int rs, Intent it) {
    super.onActivityResult(rq, rs, it);
    if (rq == 77 && chooser != null) { chooser.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(rs, it)); chooser = null; }
  }
  @Override protected void onDestroy() { if (tts != null) tts.shutdown(); super.onDestroy(); }

  static void show(Context c, String t, String m) {
    NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
    if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(new NotificationChannel(CH, "Notifications", NotificationManager.IMPORTANCE_DEFAULT));
    PendingIntent pi = PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    Notification.Builder nb = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CH) : new Notification.Builder(c);
    nb.setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(t).setContentText(m).setContentIntent(pi).setAutoCancel(true);
    nm.notify((int) (System.currentTimeMillis() % 100000000L), nb.build());
  }
  static PendingIntent alarmIntent(Context c, String id, String t, String m) {
    Intent in = new Intent(c, AlarmReceiver.class).putExtra("id", id).putExtra("t", t).putExtra("m", m);
    return PendingIntent.getBroadcast(c, id.hashCode(), in, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
  }
  static void setAlarm(Context c, String id, long ts, String t, String m, boolean save) {
    PendingIntent pi = alarmIntent(c, id, t, m);
    AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
    try {
      if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ts, pi);
      else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ts, pi);
    } catch (Exception e) { am.set(AlarmManager.RTC_WAKEUP, ts, pi); }
    if (save) {
      try {
        JSONObject o = new JSONObject();
        o.put("ts", ts); o.put("t", t); o.put("m", m);
        c.getSharedPreferences("alarms", 0).edit().putString(id, o.toString()).apply();
      } catch (Exception e) { }
    }
  }

  class Bridge {
    @JavascriptInterface public void toast(final String m) {
      if (!f.optBoolean("toast")) return;
      runOnUiThread(() -> Toast.makeText(MainActivity.this, m, Toast.LENGTH_SHORT).show());
    }
    @JavascriptInterface public void vibrate(int ms) {
      if (!f.optBoolean("vibrate")) return;
      Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
      if (v != null) v.vibrate(Math.min(Math.max(ms, 10), 2000));
    }
    @JavascriptInterface public void notify(String t, String m) {
      if (f.optBoolean("notify") || f.optBoolean("reminders")) show(MainActivity.this, t, m);
    }
    @JavascriptInterface public void schedule(String id, long ts, String t, String m) {
      if (f.optBoolean("reminders")) setAlarm(MainActivity.this, id, ts, t, m, true);
    }
    @JavascriptInterface public void cancel(String id) {
      if (!f.optBoolean("reminders")) return;
      ((AlarmManager) getSystemService(ALARM_SERVICE)).cancel(alarmIntent(MainActivity.this, id, "", ""));
      getSharedPreferences("alarms", 0).edit().remove(id).apply();
    }
    @JavascriptInterface public void share(final String text) {
      if (!f.optBoolean("share")) return;
      runOnUiThread(() -> {
        Intent s = new Intent(Intent.ACTION_SEND);
        s.setType("text/plain");
        s.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(s, null));
      });
    }
    @JavascriptInterface public void copy(final String text) {
      if (!f.optBoolean("share")) return;
      runOnUiThread(() -> ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("text", text)));
    }
    @JavascriptInterface public void speak(final String text) {
      if (!f.optBoolean("tts")) return;
      runOnUiThread(() -> {
        if (tts == null) {
          pending = text;
          tts = new TextToSpeech(MainActivity.this, st -> { if (st == TextToSpeech.SUCCESS && pending != null) tts.speak(pending, TextToSpeech.QUEUE_FLUSH, null, "u"); });
        } else tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u");
      });
    }
    @JavascriptInterface public String saveFile(String name, String mime, String text) {
      if (!f.optBoolean("files")) return "disabled";
      try {
        name = new File(name).getName();
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        if (Build.VERSION.SDK_INT >= 29) {
          ContentValues v = new ContentValues();
          v.put(MediaStore.Downloads.DISPLAY_NAME, name);
          v.put(MediaStore.Downloads.MIME_TYPE, mime);
          Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
          OutputStream os = getContentResolver().openOutputStream(u);
          os.write(data); os.close();
          return "Downloads/" + name;
        }
        File fl = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), name);
        FileOutputStream os = new FileOutputStream(fl);
        os.write(data); os.close();
        return fl.getPath();
      } catch (Exception e) { return "error: " + e.getMessage(); }
    }
    @JavascriptInterface public boolean isOnline() { return f.optBoolean("info") && online(); }
    @JavascriptInterface public String appVersion() { return f.optBoolean("info") ? root.optJSONObject("app").optString("vn") : ""; }
  }
}
'''
W(J+'/MainActivity.java',JAVA.replace('__PKG__',pkg))
print('Generated project for',pkg,'| minSdk',mn,'targetSdk',tg,'| features:',[k for k,v in f.items() if v is True])
