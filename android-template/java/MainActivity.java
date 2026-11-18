package __PKG__;

import android.app.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.graphics.*;
import android.net.*;
import android.os.*;
import android.provider.MediaStore;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.webkit.WebViewAssetLoader;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;

public class MainActivity extends AppCompatActivity {
  static final String HOST = "appassets.androidplatform.net";
  JSONObject root, d, w, f, sp;
  WebView web;
  WebView popup;
  SwipeRefreshLayout swipe;
  ProgressBar bar;
  FrameLayout main;
  LinearLayout lay;
  ValueCallback<Uri[]> chooser;
  boolean anim;
  String failedUrl;

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
    WindowCompat.setDecorFitsSystemWindows(win, false);
    String tint = d.optString("tint", "auto");
    boolean lightIcons = tint.equals("light") || (tint.equals("auto") && lum(sc) < 0.5f);
    WindowInsetsControllerCompat wc = WindowCompat.getInsetsController(win, win.getDecorView());
    wc.setAppearanceLightStatusBars(!lightIcons);
    wc.setAppearanceLightNavigationBars(!lightIcons);
    if (!d.optBoolean("status", true)) wc.hide(WindowInsetsCompat.Type.statusBars());
    if (f.optBoolean("fullscreen")) {
      wc.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
      wc.hide(WindowInsetsCompat.Type.systemBars());
    }

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
    final View topStrip = new View(this), botStrip = new View(this);
    topStrip.setBackgroundColor(tr ? Color.TRANSPARENT : sc);
    botStrip.setBackgroundColor(tr ? Color.TRANSPARENT : nc);
    main.addView(topStrip, new FrameLayout.LayoutParams(-1, 0, Gravity.TOP));
    main.addView(botStrip, new FrameLayout.LayoutParams(-1, 0, Gravity.BOTTOM));
    ViewCompat.setOnApplyWindowInsetsListener(main, (v, wi) -> {
      Insets b = wi.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
      Insets k = wi.getInsets(WindowInsetsCompat.Type.ime());
      lay.setPadding(b.left, b.top, b.right, Math.max(b.bottom, k.bottom));
      topStrip.getLayoutParams().height = b.top;
      botStrip.getLayoutParams().height = k.bottom > b.bottom ? 0 : b.bottom;
      ((FrameLayout.LayoutParams) bar.getLayoutParams()).topMargin = b.top;
      v.requestLayout();
      return WindowInsetsCompat.CONSUMED;
    });
    getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
      @Override public void handleOnBackPressed() { handleBack(); }
    });

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
    s.setSupportMultipleWindows(true);
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
        String h = u.getHost() == null ? "" : u.getHost();
        // TA SERIES: a Drive file link (drive.google.com/file/d/<ID>/...) opens
        // Google's viewer. Convert it to a direct download so the student gets
        // the file through the app's DownloadListener instead of the viewer.
        String dl = driveDownloadUrl(u);
        if (dl != null) { web.loadUrl(dl); return true; }
        // TA SERIES: keep the site and Google Drive inside the app so files open
        // in-app and downloads go through the app's DownloadListener. Everything
        // else (WhatsApp, mail, other sites) still opens in the browser.
        if (h.equals("taseriesofficial.github.io") || h.endsWith(".taseriesofficial.github.io")
            || h.equals("drive.google.com") || h.endsWith(".drive.google.com")
            || h.equals("docs.google.com") || h.endsWith(".docs.google.com")
            || h.equals("drive.usercontent.google.com") || h.endsWith(".googleusercontent.com")) return false;
        try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception e) { }
        return true;
      }
      @Override public void onPageStarted(WebView v, String u, Bitmap fav) { if (anim) v.setAlpha(0.2f); }
      @Override public void onPageFinished(WebView v, String u) {
        swipe.setRefreshing(false); v.animate().alpha(1f).setDuration(250).start();
        // TA SERIES app mode: when the website opens inside the app, hide its
        // own header and footer so the app home and the website feel like one
        // home. The site also supports this natively (?app=1, see app-mode.js);
        // this injection is the fallback until that site update is published.
        if (u != null && u.startsWith("https://taseriesofficial.github.io/")) {
          v.evaluateJavascript("(function(){try{"
            + "document.documentElement.classList.add('in-app');"
            + "if(!document.getElementById('ta-app-mode')){var s=document.createElement('style');s.id='ta-app-mode';"
            + "s.textContent='.site-header,.site-footer{display:none!important}';"
            + "(document.head||document.documentElement).appendChild(s);}"
            + "if(!window.taBack){window.taBack=function(){try{"
            + "if(typeof state!=='undefined'&&state&&state.path&&state.path.length>0&&!state.q&&typeof goTo==='function'){goTo(state.path.slice(0,-1));return true;}"
            + "}catch(e){} return false;};}"
            + "}catch(e){}})();", null);
        }
      }
      @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
        if (r.isForMainFrame() && w.optBoolean("noNet", true) && !online()) {
          // Don't loop if the offline page itself ever failed to load.
          String u = r.getUrl() == null ? "" : r.getUrl().toString();
          if (!u.contains("offline.html")) { failedUrl = u; showNoNet(); }
        }
      }
      @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail detail) {
        // If the WebView's renderer dies the page goes blank white. Reload the
        // home screen instead of leaving a dead white page.
        Toast.makeText(MainActivity.this, "Page had a problem — reloading.", Toast.LENGTH_SHORT).show();
        v.loadUrl("https://" + HOST + "/assets/www/index.html");
        return true;
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
      @Override public boolean onCreateWindow(WebView v, boolean isDialog, boolean isUserGesture, Message resultMsg) {
        // The TA SERIES site opens file links with target="_blank". Without this,
        // those taps do nothing in a WebView. The popup URL is caught here and
        // loaded into the main WebView instead, so shouldOverrideUrlLoading still
        // decides in-app vs browser. A separate hidden popup WebView is used:
        // reusing the main WebView as the popup target crashed the app on some
        // phones ("TA SERIES has stopped" when tapping a file to download).
        try {
          if (popup == null) {
            popup = new WebView(MainActivity.this);
            popup.setWebViewClient(new WebViewClient() {
              @Override public boolean shouldOverrideUrlLoading(WebView pv, WebResourceRequest r) {
                String dl = driveDownloadUrl(r.getUrl());
                web.loadUrl(dl != null ? dl : r.getUrl().toString());
                return true;
              }
              @Override public void onPageStarted(WebView pv, String url, Bitmap fav) {
                // Fallback if the popup's first URL was not intercepted above.
                if (url != null && url.startsWith("http")) { web.loadUrl(url); pv.stopLoading(); }
              }
            });
          }
          WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
          transport.setWebView(popup);
          resultMsg.sendToTarget();
          return true;
        } catch (Exception e) {
          return false;
        }
      }
    });
    // In-app downloads: a tap on a Drive download link saves the file to the
    // phone's Downloads folder with a notification, instead of opening Chrome.
    web.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
      try {
        // DownloadManager only handles http/https links. Anything else (for
        // example a blob: link from Drive) cannot be saved this way.
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
          Toast.makeText(MainActivity.this, "Download failed. Open the link in your browser to download it.", Toast.LENGTH_LONG).show();
          return;
        }
        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
        req.setMimeType(mimetype);
        String name = URLUtil.guessFileName(url, contentDisposition, mimetype);
        req.setTitle(name);
        req.setDescription("TA SERIES download");
        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        // TA SERIES: every download goes to the phone's shared Downloads folder,
        // inside a "TA SERIES" subfolder, so students always know where to find it.
        req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "TA SERIES/" + name);
        req.allowScanningByMediaScanner();
        ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(req);
        Toast.makeText(MainActivity.this, "Download started: " + name, Toast.LENGTH_SHORT).show();
      } catch (Exception e) {
        Toast.makeText(MainActivity.this, "Download failed. Open the link in your browser to download it.", Toast.LENGTH_LONG).show();
      }
    });
    web.addJavascriptInterface(new Bridge(), "Android");
    web.loadUrl("https://" + HOST + "/assets/www/index.html");

    if (sp.optBoolean("on", true)) addSplash();
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

  boolean online() {
    try {
      NetworkInfo n = ((ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)).getActiveNetworkInfo();
      return n != null && n.isConnected();
    } catch (Exception e) { return true; }
  }
  void showNoNet() {
    // Show the app's own offline page instead of the browser's ugly
    // "Webpage not available" error, which also shows the website address.
    web.loadUrl("https://" + HOST + "/assets/www/offline.html");
  }

  // TA SERIES: turn a Drive file-viewer link into a direct download link.
  // Returns the converted URL, or null when the URL is not a Drive file link.
  static String driveDownloadUrl(Uri u) {
    if (u == null || !"drive.google.com".equals(u.getHost())) return null;
    String p = u.getPath() == null ? "" : u.getPath();
    if (!p.startsWith("/file/d/")) return null;
    String[] parts = p.split("/");
    if (parts.length < 4 || parts[3].isEmpty()) return null;
    return "https://drive.google.com/uc?export=download&id=" + parts[3];
  }

  // TA SERIES: the JavaScript bridge is exposed to every page the WebView loads,
  // including Google pages. Only our own pages (the bundled app pages and the
  // TA SERIES website) may use the bridge methods that do something.
  boolean trusted() {
    try {
      String u = web.getUrl();
      return u != null && (u.startsWith("https://" + HOST + "/") || u.startsWith("https://taseriesofficial.github.io/"));
    } catch (Exception e) { return false; }
  }

  void handleBack() {
    // 1. Let the current page handle back first: a quiz goes up one screen
    // (and asks before leaving a running test), the website goes up one
    // folder level. The page answers true only when it handled the press.
    web.evaluateJavascript("(function(){try{return window.taBack?window.taBack():false;}catch(e){return false;}})()", handled -> {
      if ("true".equals(handled)) return;
      // 2. Otherwise use WebView history.
      if (w.optBoolean("backNav", true) && web.canGoBack()) { web.goBack(); return; }
      // 3. Root: ask before exiting.
      if (w.optBoolean("exitConfirm", true)) {
        new AlertDialog.Builder(MainActivity.this).setMessage("Exit app?").setPositiveButton("Exit", (x, y) -> finish()).setNegativeButton("Stay", null).show();
        return;
      }
      finish();
    });
  }
  @Override public void finish() { super.finish(); if (anim) overridePendingTransition(R.anim.enter, R.anim.exit); }
  @Override protected void onActivityResult(int rq, int rs, Intent it) {
    super.onActivityResult(rq, rs, it);
    if (rq == 77 && chooser != null) { chooser.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(rs, it)); chooser = null; }
  }
  @Override protected void onDestroy() { if (popup != null) popup.destroy(); super.onDestroy(); }

  class Bridge {
    @JavascriptInterface public void toast(final String m) {
      if (!f.optBoolean("toast")) return;
      runOnUiThread(() -> Toast.makeText(MainActivity.this, m, Toast.LENGTH_SHORT).show());
    }
    @JavascriptInterface public String saveFile(String name, String mime, String text) {
      if (!f.optBoolean("files")) return "disabled";
      if (!trusted()) return "disabled";
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
    // TA SERIES: the offline page's "Try again" button. Reloads the page that
    // failed, but only when the phone is back online — otherwise it says so
    // instead of failing again and looping back to the offline page.
    @JavascriptInterface public void retry() {
      if (!trusted()) return;
      runOnUiThread(() -> {
        if (online() && failedUrl != null && !failedUrl.isEmpty()) {
          String u = failedUrl; failedUrl = null;
          web.loadUrl(u);
        } else {
          Toast.makeText(MainActivity.this, "Still offline — connect to the internet and try again.", Toast.LENGTH_SHORT).show();
        }
      });
    }
    @JavascriptInterface public boolean isOnline() { return f.optBoolean("info") && online(); }
    @JavascriptInterface public String appVersion() { return f.optBoolean("info") ? root.optJSONObject("app").optString("vn") : ""; }
    // TA SERIES: the installed version code, read from the package itself, so
    // the home page update check can never drift from the real version.
    @JavascriptInterface public int versionCode() {
      if (!trusted()) return 0;
      try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode; } catch (Exception e) { return 0; }
    }
  }
}
