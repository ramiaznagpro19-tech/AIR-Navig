package app.airnavig;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebBackForwardList;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Pattern;
import org.json.JSONObject;

/**
 * AIR Navig — navigateur de la marque AIR, créé par Rami, un peu passionné de développement.
 *
 * Version durcie : pont JavaScript verrouillé, accès fichiers coupés par défaut,
 * HTTPS en priorité, aucune erreur de certificat contournable, autorisations
 * demandées site par site, liste de blocage d'origine documentée et mise à jour
 * automatique, isolation renforcée des WebView.
 */
public class MainActivity extends Activity {
    static final String CREATOR = "Rami";
    static final String BRAND = "AIR";

    private static final String ADAPT_JS = "(function(){try{if(window.__airAdapt)return;window.__airAdapt=1;}catch(e){}})();";
    private static final int BG = 0xFF000000;
    private static final int BG_LIGHT = 0xFFFFFFFF;
    private static final String DARK_JS = "(function(){try{if(document.getElementById('__airdark'))return;function lum(c){var m=(c||'').match(/rgba?\\(([^)]+)\\)/);if(!m)return -1;var p=m[1].split(',').map(parseFloat);if(p.length>3&&p[3]===0)return -1;return (0.2126*p[0]+0.7152*p[1]+0.0722*p[2])/255;}var b=document.body,l=b?lum(getComputedStyle(b).backgroundColor):-1;if(l<0)l=lum(getComputedStyle(document.documentElement).backgroundColor);if(l>=0&&l<0.4)return;var s=document.createElement('style');s.id='__airdark';s.textContent='html{filter:invert(1) hue-rotate(180deg)!important;background:#fff!important}img,video,picture,canvas,iframe,embed,object,svg image,[style*=\"background-image\"]{filter:invert(1) hue-rotate(180deg)!important}';(document.head||document.documentElement).appendChild(s);}catch(e){}})();";
    private static final String INCOG_SUFFIX = "inconnu";
    static final String ASSET = "file:///android_asset/";
    static final String CHROME_URL = ASSET + "chrome.html";
    private static final String ERROR_URL = ASSET + "error.html";
    private static final String[] INTERNAL_PAGES = {"home.html", "blocked.html", "error.html"};
    static final int POS_BOTTOM = 0;
    static final int POS_TOP = 1;
    static final int POS_RIGHT = 2;
    static final int POS_LEFT = 3;
    private static final int REQ_MEDIA = 7;
    private static final int REQ_GEO = 8;
    private static final int REQ_FILE = 9;
    private static final int REQ_OPEN = 11;
    private static final int MAX_ARG = 256 * 1024;
    private static final Pattern PRIVATE_HOST = Pattern.compile("^(localhost|127\\.\\d+\\.\\d+\\.\\d+|10\\.\\d+\\.\\d+\\.\\d+|192\\.168\\.\\d+\\.\\d+|172\\.(1[6-9]|2\\d|3[01])\\.\\d+\\.\\d+|\\[::1\\]|.+\\.local)$");

    private WebView chrome;
    private LinearLayout col;
    private WebView content;
    private WebChromeClient.CustomViewCallback customCallback;
    private View customView;
    private float density;
    private ValueCallback<Uri[]> fileCallback;
    private GeolocationPermissions.Callback geoCallback;
    private String geoOrigin;
    private String mobileUA;
    private PermissionRequest pendingRequest;
    private String[] pendingResources;
    private AlertDialog permDialog;
    private SharedPreferences prefs;
    private FrameLayout root;

    static volatile boolean blockOn = true;
    static volatile String[] rules = new String[0];
    /* Vitesse : règles en table de hachage (au lieu d'un parcours de 5000 règles par requête)
       et verdict mémorisé par domaine, consulté depuis les threads réseau du WebView. */
    static volatile Set<String> ruleSet = new HashSet<String>();
    static final ConcurrentHashMap<String, Boolean> verdicts = new ConcurrentHashMap<String, Boolean>();
    /* Vitesse : les domaines bloqués sont envoyés à l'interface par lots, pas un par un. */
    private final ConcurrentLinkedQueue<String> blockedQueue = new ConcurrentLinkedQueue<String>();
    private volatile boolean flushScheduled = false;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private int lastProgress = -1;
    private String adaptedUrl = null;
    private boolean forcedThisPage = false;

    /* Autorisations globales : prudentes par défaut. Micro/caméra/position = le site peut DEMANDER (confirmation à chaque site). */
    volatile boolean allowMic = true;
    volatile boolean allowCam = true;
    volatile boolean allowGeo = true;
    volatile boolean allowPopups = false;
    volatile boolean allowDownloads = true;
    volatile boolean allowLocal = false;
    volatile boolean adapt = true;

    /* Décisions par site (origine + type d'accès). En navigation privée : en mémoire seulement. */
    private final Map<String, Boolean> siteDecisions = Collections.synchronizedMap(new HashMap<String, Boolean>());
    /* HTTPS en priorité : hôtes pour lesquels l'utilisateur a accepté le HTTP non chiffré. */
    private final Set<String> httpAllowed = Collections.synchronizedSet(new HashSet<String>());
    private final Set<String> upgradedHosts = Collections.synchronizedSet(new HashSet<String>());
    /* Fichiers ouverts par l'utilisateur via le sélecteur ou une autre appli. */
    /* Page principale en cours de chargement (pour distinguer une erreur de la page d'une erreur d'image). */
    private volatile String pendingMain;
    private final Set<String> grantedContent = Collections.synchronizedSet(new HashSet<String>());

    private boolean desktop = false;
    private String desktopUA = "";
    private boolean siteDark = false;
    private boolean siteForce = false;
    private boolean lightUi = false;
    private int pos = POS_BOTTOM;
    private int barSize = 120;
    private boolean barFull = false;

    static String forceJs(boolean z) {
        String str = z ? "#000" : "#fff";
        String str2 = z ? "#fff" : "#000";
        String str3 = z ? "#333" : "#ddd";
        String str4 = z ? "#111" : "#f5f5f5";
        return "(function(){try{var d=document.getElementById('__airdark');if(d)d.remove();var id='__airforce',css=" + JSONObject.quote("html,body{background:" + str + "!important}*:not(img):not(video):not(canvas):not(svg):not(picture):not(iframe):not(embed):not(object){background-color:" + str + "!important;color:" + str2 + "!important;border-color:" + str3 + "!important;text-shadow:none!important}a,a *{color:" + (z ? "#8ab4ff" : "#1a0dab") + "!important}a:visited,a:visited *{color:" + (z ? "#c58af9" : "#681da8") + "!important}input,textarea,select,button{background-color:" + str4 + "!important;color:" + str2 + "!important}::placeholder{color:" + (z ? "#999" : "#666") + "!important}svg{fill:currentColor}") + ";function put(){var s=document.getElementById(id);if(!s){s=document.createElement('style');s.id=id;(document.head||document.documentElement).appendChild(s)}if(s.textContent!==css)s.textContent=css}put();if(!window.__airForceObs){window.__airForceObs=new MutationObserver(function(){if(!document.getElementById(id))put()});window.__airForceObs.observe(document.documentElement,{childList:true});if(document.head)window.__airForceObs.observe(document.head,{childList:true})}}catch(e){}})();";
    }

    /* ======================= Thème ======================= */

    private void applySavedTheme() {
        if (prefs != null) applyTheme(prefs.getString("theme", "dark"));
    }

    private void applyTheme(String str) {
        boolean light = "light".equals(str);
        lightUi = light;
        int c = light ? BG_LIGHT : BG;
        Window window = getWindow();
        window.setStatusBarColor(c);
        window.setNavigationBarColor(c);
        View decor = window.getDecorView();
        int flags = decor.getSystemUiVisibility() & ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | 16);
        if (light) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) flags |= 16;
        }
        decor.setSystemUiVisibility(flags);
        if (root != null) root.setBackgroundColor(c);
        if (chrome != null) chrome.setBackgroundColor(c);
        if (content != null) content.setBackgroundColor(c);
    }

    protected boolean incognito() {
        return false;
    }

    private void savePref(String key, Object v) {
        if (prefs == null || incognito()) return;
        SharedPreferences.Editor e = prefs.edit();
        if (v instanceof Boolean) e.putBoolean(key, (Boolean) v);
        else if (v instanceof Integer) e.putInt(key, (Integer) v);
        else e.putString(key, String.valueOf(v));
        e.apply();
    }

    private void wipeIncognito() {
        File[] dirs = {new File(getApplicationInfo().dataDir), getCacheDir()};
        for (File d : dirs) {
            File[] list = d.listFiles();
            if (list == null) continue;
            for (File f : list) if (f.getName().contains(INCOG_SUFFIX)) deleteTree(f);
        }
    }

    private static void deleteTree(File file) {
        File[] list = file.listFiles();
        if (list != null) for (File f : list) deleteTree(f);
        file.delete();
    }

    boolean systemDark() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    @Override
    public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        toJs("window.onSysDark&&onSysDark(" + systemDark() + ")");
    }

    /* ======================= Création ======================= */

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        if (incognito()) {
            wipeIncognito();
            if (Build.VERSION.SDK_INT >= 28) {
                try {
                    WebView.setDataDirectorySuffix(INCOG_SUFFIX);
                } catch (Exception ignored) {
                }
            }
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        }
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        density = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences("air", 0);
        loadPermissions(prefs.getString("perms2", ""));
        if (!incognito()) loadSiteDecisions();
        siteDark = prefs.getBoolean("siteDark", false);
        siteForce = prefs.getBoolean("siteForce", false);
        pos = prefs.getInt("pos", POS_BOTTOM);
        barSize = isSide(pos) ? 64 : pos == POS_TOP ? 60 : 120;
        adapt = prefs.getBoolean("adapt", true);

        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        col = new LinearLayout(this);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));
        content = createContent();
        CookieManager.getInstance().setAcceptCookie(!incognito() || Build.VERSION.SDK_INT >= 28);

        chrome = new WebView(this);
        setupCommon(chrome);
        WebSettings cs = chrome.getSettings();
        cs.setAllowFileAccess(false);
        cs.setAllowContentAccess(false);
        cs.setAllowFileAccessFromFileURLs(false);
        cs.setAllowUniversalAccessFromFileURLs(false);
        cs.setGeolocationEnabled(false);
        cs.setJavaScriptCanOpenWindowsAutomatically(false);
        noDarkening(chrome);
        chrome.setBackgroundColor(BG);
        // Le pont n'est attaché qu'à l'interface locale, jamais aux sites visités.
        chrome.addJavascriptInterface(new Bridge(), "AIR");
        chrome.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String u = r.getUrl().toString();
                if (u.startsWith(CHROME_URL)) return false;
                String s = r.getUrl().getScheme();
                if ("https".equals(s) || "http".equals(s)) navigate(u);
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                // L'interface ne charge que ses fichiers, des images data: et les icônes des sites.
                String s = r.getUrl().getScheme();
                String u = r.getUrl().toString();
                if (u.startsWith(ASSET) || "data".equals(s) || "https".equals(s)) return null;
                return empty();
            }

            @Override
            public void onReceivedSslError(WebView v, SslErrorHandler h, SslError e) {
                h.cancel();
            }

            @Override
            public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
                recreateSafely();
                return true;
            }
        });
        arrange();
        setContentView(root);
        applySavedTheme();

        ui.postDelayed(sleepTick, 60000);
        String initial = incomingUrl(getIntent());
        chrome.loadUrl(CHROME_URL + "#" + Uri.encode(initial == null ? "" : initial));

        if (!incognito()) {
            new Thread(new Runnable() { @Override public void run() {
                Blocklist.ensureLoaded(getApplicationContext());
                if (Blocklist.due(getApplicationContext())) Blocklist.update(getApplicationContext());
                if (Updater.due(getApplicationContext())) Updater.check(MainActivity.this, false);
            } }).start();
        } else {
            new Thread(new Runnable() { @Override public void run() { Blocklist.ensureLoaded(getApplicationContext()); } }).start();
        }
    }

    private void recreateSafely() {
        runOnUiThread(new Runnable() { @Override public void run() {
            try {
                if (content != null) {
                    col.removeView(content);
                    content.destroy();
                }
            } catch (Throwable ignored) {
            }
            content = null;
            recreate();
        } });
    }

    /** Lien reçu d'une autre appli : seulement http(s) ou un fichier HTML partagé (content://). */
    private String incomingUrl(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return null;
        Uri d = intent.getData();
        if (d == null || d.getScheme() == null) return null;
        String s = d.getScheme().toLowerCase(Locale.ROOT);
        if ("http".equals(s) || "https".equals(s)) return d.toString();
        if ("content".equals(s) && (intent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
            grantedContent.add(d.toString());
            return d.toString();
        }
        return null;
    }

    private WebView createContent() {
        WebView w = new WebView(new ContextThemeWrapper(this, siteDark ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar));
        content = w;
        setupCommon(w);
        if (mobileUA == null) {
            mobileUA = w.getSettings().getUserAgentString().replace("; wv)", ")").replaceAll("Version/\\d+(\\.\\d+)* ", "");
        }
        w.getSettings().setUserAgentString(desktop ? desktopUA : mobileUA);
        w.getSettings().setLayoutAlgorithm(adapt ? WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING : WebSettings.LayoutAlgorithm.NORMAL);
        applyPermissions();
        applySiteDark(w);
        w.setBackgroundColor(lightUi ? BG_LIGHT : BG);
        w.setWebViewClient(new ContentClient());
        w.setWebChromeClient(new ContentChrome());
        w.setDownloadListener(new Downloads());
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, false);
        // Vitesse : rendu prioritaire pour la page visitée, dessin anticipé pour un défilement fluide.
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                w.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true);
            } catch (Throwable ignored) {
            }
        }
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                w.getSettings().setOffscreenPreRaster(true);
            } catch (Throwable ignored) {
            }
        }
        return w;
    }

    private void noDarkening(WebView w) {
        WebSettings s = w.getSettings();
        try {
            if (Build.VERSION.SDK_INT >= 33) s.setAlgorithmicDarkeningAllowed(false);
            else if (Build.VERSION.SDK_INT >= 29) s.setForceDark(WebSettings.FORCE_DARK_OFF);
        } catch (Throwable ignored) {
        }
    }

    private void applySiteDark(WebView w) {
        WebSettings s = w.getSettings();
        boolean on = siteDark && !siteForce;
        try {
            if (Build.VERSION.SDK_INT >= 33) s.setAlgorithmicDarkeningAllowed(on);
            else if (Build.VERSION.SDK_INT >= 29) s.setForceDark(on ? WebSettings.FORCE_DARK_ON : WebSettings.FORCE_DARK_OFF);
        } catch (Throwable ignored) {
        }
    }

    private void rebuildContent() {
        WebView old = content;
        if (old == null) return;
        Bundle b = new Bundle();
        WebBackForwardList list = null;
        try {
            list = old.saveState(b);
        } catch (Throwable ignored) {
        }
        String url = old.getUrl();
        int vis = old.getVisibility();
        col.removeView(old);
        try {
            old.stopLoading();
            old.destroy();
        } catch (Throwable ignored) {
        }
        WebView w = createContent();
        w.setVisibility(vis);
        arrange();
        boolean restored = false;
        if (list != null) {
            try {
                restored = w.restoreState(b) != null;
            } catch (Throwable ignored) {
            }
        }
        if (restored) w.reload();
        else if (url != null) navigate(url);
    }

    @SuppressWarnings("deprecation")
    private void setupCommon(WebView w) {
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSupportMultipleWindows(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setSaveFormData(false);
        // Jamais d'accès d'une page locale aux autres fichiers ou à Internet sans restriction.
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                s.setSafeBrowsingEnabled(true);
            } catch (Throwable ignored) {
            }
        }
    }

    private void applyPermissions() {
        if (content == null) return;
        WebSettings s = content.getSettings();
        String u = content.getUrl();
        boolean localPage = u != null && (u.startsWith("file:") || u.startsWith("content:")) && !u.startsWith(ASSET);
        s.setAllowFileAccess(allowLocal && localPage);
        s.setAllowContentAccess(localPage);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setJavaScriptCanOpenWindowsAutomatically(allowPopups);
        s.setGeolocationEnabled(allowGeo);
    }

    /** Ajuste les accès fichiers à la page qui se charge : jamais pour un site web. */
    private void scopeAccess(WebView w, String url) {
        if (w != content || url == null) return;
        WebSettings s = w.getSettings();
        boolean file = url.startsWith("file:") && !url.startsWith(ASSET);
        boolean cont = url.startsWith("content:");
        s.setAllowFileAccess(allowLocal && file);
        s.setAllowContentAccess(cont);
    }

    private void loadPermissions(String str) {
        if (str == null || str.isEmpty()) return;
        for (String part : str.split(",")) {
            String[] kv = part.split("=");
            if (kv.length != 2) continue;
            boolean on = "1".equals(kv[1]);
            switch (kv[0]) {
                case "mic": allowMic = on; break;
                case "cam": allowCam = on; break;
                case "geo": allowGeo = on; break;
                case "popups": allowPopups = on; break;
                case "dl": allowDownloads = on; break;
                case "local": allowLocal = on; break;
                default: break;
            }
        }
    }

    private void loadSiteDecisions() {
        try {
            JSONObject j = new JSONObject(prefs.getString("sitePerms", "{}"));
            java.util.Iterator<String> it = j.keys();
            while (it.hasNext()) {
                String k = it.next();
                siteDecisions.put(k, j.getBoolean(k));
            }
        } catch (Exception ignored) {
        }
    }

    private void saveSiteDecisions() {
        if (incognito()) return;
        JSONObject j;
        synchronized (siteDecisions) {
            j = new JSONObject(siteDecisions);
        }
        prefs.edit().putString("sitePerms", j.toString()).apply();
    }

    int px(int i) {
        return (int) (i * density);
    }

    static boolean isSide(int p) {
        return p == POS_RIGHT || p == POS_LEFT;
    }

    /* ======================= Onglets vivants et onglets en veille =======================
       Chaque onglet récent garde son propre WebView (retour instantané, historique et
       défilement conservés). Au-delà de maxLive, ou après sleepAfterMs sans utilisation,
       l'onglet est mis en veille : son WebView est détruit pour libérer la mémoire, seule
       son adresse est gardée par l'interface, et il se recharge quand on y revient. */
    private final java.util.LinkedHashMap<String, WebView> pool = new java.util.LinkedHashMap<String, WebView>(16, 0.75f, true);
    private final HashMap<String, Long> lastUsed = new HashMap<String, Long>();
    private String currentTab = null;
    private int maxLive = 3;
    private long sleepAfterMs = 30 * 60000L;
    private final Runnable sleepTick = new Runnable() {
        @Override
        public void run() {
            sleepIdle();
            ui.postDelayed(this, 60000);
        }
    };

    private void switchTab(String id, String url) {
        if (id == null || id.isEmpty() || id.length() > 64) {
            navigate(url);
            return;
        }
        if (id.equals(currentTab) && content != null) {
            if (content.getUrl() == null && !url.isEmpty()) navigate(url);
            else reportTab();
            return;
        }
        WebView old = content;
        if (old != null) {
            if (currentTab != null) {
                try {
                    old.onPause();
                } catch (Throwable ignored) {
                }
                pool.put(currentTab, old);
                lastUsed.put(currentTab, System.currentTimeMillis());
            } else {
                // WebView de démarrage pas encore rattaché à un onglet : on le réutilise.
                currentTab = id;
                navigate(url.isEmpty() ? HOME_URL_FALLBACK() : url);
                reportLive();
                return;
            }
        }
        WebView w = pool.remove(id);
        lastUsed.remove(id);
        currentTab = id;
        if (w != null) {
            content = w;
            try {
                w.onResume();
            } catch (Throwable ignored) {
            }
            col.removeView(old);
            arrange();
            applyPermissions();
            reportTab();
        } else {
            if (old != null) col.removeView(old);
            content = null;
            createContent();
            arrange();
            navigate(url.isEmpty() ? HOME_URL_FALLBACK() : url);
        }
        trimPool();
        reportLive();
    }

    private String HOME_URL_FALLBACK() {
        return ASSET + "home.html";
    }

    /** Envoie à l'interface l'état de l'onglet qui vient de reprendre la main. */
    private void reportTab() {
        if (content == null) return;
        String u = content.getUrl();
        int pr = content.getProgress();
        onNav(content, u, pr < 100);
        lastProgress = pr;
        toJs("onProgress(" + pr + ")");
        String t = content.getTitle();
        if (t != null && u != null && !u.startsWith(ASSET)) call("onTitle", t);
    }

    private void destroyView(WebView w) {
        if (w == null) return;
        try {
            w.stopLoading();
            col.removeView(w);
            w.destroy();
        } catch (Throwable ignored) {
        }
    }

    private void sleepTab(String id) {
        if (id == null || id.equals(currentTab)) return;
        WebView w = pool.remove(id);
        lastUsed.remove(id);
        if (w != null) {
            destroyView(w);
            reportLive();
        }
    }

    /** Met en veille tous les onglets sauf celui affiché. */
    void sleepBackground() {
        if (pool.isEmpty()) return;
        for (WebView w : pool.values()) destroyView(w);
        pool.clear();
        lastUsed.clear();
        reportLive();
    }

    private void closeTabView(String id) {
        if ("*".equals(id)) {
            sleepBackground();
            return;
        }
        sleepTab(id);
    }

    /** Garde au plus maxLive onglets vivants (l'onglet affiché compris). */
    private void trimPool() {
        boolean changed = false;
        while (pool.size() > Math.max(0, maxLive - 1)) {
            String eldest = pool.keySet().iterator().next();
            destroyView(pool.remove(eldest));
            lastUsed.remove(eldest);
            changed = true;
        }
        if (changed) reportLive();
    }

    private void sleepIdle() {
        if (sleepAfterMs <= 0 || pool.isEmpty()) return;
        long now = System.currentTimeMillis();
        List<String> idle = new ArrayList<String>();
        for (String id : pool.keySet()) {
            Long t = lastUsed.get(id);
            if (t != null && now - t > sleepAfterMs) idle.add(id);
        }
        for (String id : idle) {
            destroyView(pool.remove(id));
            lastUsed.remove(id);
        }
        if (!idle.isEmpty()) reportLive();
    }

    private void reportLive() {
        org.json.JSONArray arr = new org.json.JSONArray();
        if (currentTab != null) arr.put(currentTab);
        for (String id : pool.keySet()) arr.put(id);
        toJs("window.onLive&&onLive(" + arr.toString() + ")");
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // Le système manque de mémoire : on endort tout ce qui n'est pas affiché.
        if (level >= TRIM_MEMORY_RUNNING_LOW) sleepBackground();
    }

    private void arrange() {
        if (col == null || content == null || chrome == null) return;
        col.removeAllViews();
        boolean side = isSide(pos);
        col.setOrientation(side ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cp = side ? new LinearLayout.LayoutParams(0, -1, 1f) : new LinearLayout.LayoutParams(-1, 0, 1f);
        LinearLayout.LayoutParams bp;
        if (barFull) {
            bp = new LinearLayout.LayoutParams(-1, -1);
            content.setVisibility(View.GONE);
        } else {
            bp = side ? new LinearLayout.LayoutParams(px(barSize), -1) : new LinearLayout.LayoutParams(-1, px(barSize));
            if (customView == null) content.setVisibility(View.VISIBLE);
        }
        if (pos == POS_TOP || pos == POS_LEFT) {
            col.addView(chrome, bp);
            col.addView(content, cp);
        } else {
            col.addView(content, cp);
            col.addView(chrome, bp);
        }
    }

    private void setPos(String str) {
        int p = "top".equals(str) ? POS_TOP : "right".equals(str) ? POS_RIGHT : "left".equals(str) ? POS_LEFT : POS_BOTTOM;
        if (p == pos) return;
        pos = p;
        savePref("pos", p);
        barSize = isSide(p) ? 64 : p == POS_TOP ? 60 : 120;
        arrange();
    }

    private void setBar(int i) {
        if (i <= 0) {
            barFull = true;
            content.setVisibility(View.GONE);
            ViewGroup.LayoutParams lp = chrome.getLayoutParams();
            lp.width = -1;
            lp.height = -1;
            chrome.setLayoutParams(lp);
            return;
        }
        barFull = false;
        barSize = Math.min(i, 2000);
        ViewGroup.LayoutParams lp = chrome.getLayoutParams();
        if (isSide(pos)) {
            lp.width = px(barSize);
            lp.height = -1;
        } else {
            lp.width = -1;
            lp.height = px(barSize);
        }
        content.setVisibility(View.VISIBLE);
        chrome.setLayoutParams(lp);
    }

    /* ======================= Pont JavaScript verrouillé ======================= */

    /** Vrai seulement si l'interface affichée est bien la page locale chrome.html (thread UI). */
    private boolean chromeTrusted() {
        if (chrome == null) return false;
        String u = chrome.getUrl();
        return u != null && (u.equals(CHROME_URL) || u.startsWith(CHROME_URL + "#"));
    }

    final class Bridge {
        Bridge() {
        }

        @JavascriptInterface
        public void cmd(final String c, final String arg) {
            if (c == null || c.length() > 32) return;
            final String a = arg == null ? "" : arg.length() > MAX_ARG ? arg.substring(0, MAX_ARG) : arg;
            runOnUiThread(new Runnable() { @Override public void run() {
                if (chromeTrusted()) runCmd(c, a);
            } });
        }

        @JavascriptInterface
        public boolean sysDark() {
            return systemDark();
        }

        @JavascriptInterface
        public boolean incog() {
            return incognito();
        }

        @JavascriptInterface
        public String initSettings() {
            if (!incognito()) return "";
            Intent i = getIntent();
            String s = i == null ? null : i.getStringExtra("init");
            return s == null || s.length() > MAX_ARG ? "" : s;
        }

        @JavascriptInterface
        public String pos() {
            return pos == POS_TOP ? "top" : pos == POS_RIGHT ? "right" : pos == POS_LEFT ? "left" : "bottom";
        }

        @JavascriptInterface
        public String about() {
            try {
                JSONObject j = new JSONObject();
                String vn = "?";
                int vc = 0;
                try {
                    android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
                    vn = pi.versionName;
                    vc = pi.versionCode;
                } catch (Exception ignored) {
                }
                j.put("creator", CREATOR);
                j.put("brand", BRAND);
                j.put("version", vn + " (" + vc + ")");
                j.put("signer", Updater.signerFingerprint(MainActivity.this));
                j.put("listSource", Blocklist.SOURCE_NAME);
                j.put("listHome", Blocklist.SOURCE_HOME);
                j.put("listCount", Blocklist.hosts.size());
                j.put("listDate", Blocklist.date);
                j.put("listOrigin", Blocklist.origin);
                j.put("listSha", Blocklist.sha256);
                j.put("appUpdate", Updater.status());
                j.put("webview", webViewVersion());
                return j.toString();
            } catch (Exception e) {
                return "{}";
            }
        }
    }

    private String webViewVersion() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                android.content.pm.PackageInfo p = WebView.getCurrentWebViewPackage();
                if (p != null) return p.packageName + " " + p.versionName;
            }
        } catch (Throwable ignored) {
        }
        return "?";
    }

    void runCmd(String c, String a) {
        try {
            switch (c) {
                case "theme":
                    applyTheme(a);
                    savePref("theme", a);
                    break;
                case "go":
                    navigate(a);
                    break;
                case "tab": {
                    // Changer d'onglet : {"id":"…","url":"…"}
                    JSONObject j = new JSONObject(a);
                    switchTab(j.optString("id", ""), j.optString("url", ""));
                    break;
                }
                case "tabclose":
                    closeTabView(a);
                    break;
                case "sleep":
                    if ("*".equals(a)) sleepBackground();
                    else for (String id : a.split(",")) sleepTab(id.trim());
                    break;
                case "pool": {
                    // Réglages de veille : {"max":3,"min":30}
                    JSONObject j = new JSONObject(a);
                    maxLive = Math.max(1, Math.min(8, j.optInt("max", 3)));
                    sleepAfterMs = Math.max(0, Math.min(24 * 60, j.optInt("min", 30))) * 60000L;
                    trimPool();
                    break;
                }
                case "back":
                    if (content.canGoBack()) content.goBack();
                    break;
                case "fwd":
                    if (content.canGoForward()) content.goForward();
                    break;
                case "reload":
                    content.reload();
                    break;
                case "stop":
                    content.stopLoading();
                    break;
                case "bar":
                    setBar(Integer.parseInt(a));
                    break;
                case "pos":
                    setPos(a);
                    break;
                case "dark":
                    setDark("1".equals(a));
                    break;
                case "force": {
                    boolean on = "1".equals(a);
                    if (on != siteForce) {
                        siteForce = on;
                        savePref("siteForce", on);
                        sleepBackground();
                        applySiteDark(content);
                        content.reload();
                    }
                    break;
                }
                case "incognito": {
                    Intent i = new Intent(this, IncognitoActivity.class);
                    i.putExtra("init", a);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
                    startActivity(i);
                    break;
                }
                case "toast":
                    Toast.makeText(this, a.length() > 200 ? a.substring(0, 200) : a, Toast.LENGTH_SHORT).show();
                    break;
                case "block":
                    blockOn = "1".equals(a);
                    verdicts.clear();
                    break;
                case "rules": {
                    List<String> ok = new ArrayList<String>();
                    for (String r : a.split(",")) {
                        String t = r.trim().toLowerCase(Locale.ROOT);
                        if (t.matches("^\\.[a-z0-9.-]{3,253}$")) ok.add(t);
                        if (ok.size() >= 5000) break;
                    }
                    rules = ok.toArray(new String[0]);
                    Set<String> rs = new HashSet<String>();
                    for (String r : ok) rs.add(r.substring(1));
                    ruleSet = rs;
                    verdicts.clear();
                    break;
                }
                case "perms":
                    loadPermissions(a);
                    savePref("perms2", a);
                    applyPermissions();
                    break;
                case "forgetperms":
                    siteDecisions.clear();
                    saveSiteDecisions();
                    GeolocationPermissions.getInstance().clearAll();
                    httpAllowed.clear();
                    notice("Autorisations des sites oubliées");
                    break;
                case "sandbox": {
                    if (!a.startsWith("https://") && !a.startsWith("http://")) break;
                    Intent i = new Intent(this, SandboxActivity.class);
                    i.putExtra("url", a);
                    i.putExtra("rules", rules);
                    i.putExtra("block", blockOn);
                    startActivity(i);
                    break;
                }
                case "desktop": {
                    boolean on = !a.isEmpty();
                    if (on) {
                        if (a.length() > 512 || a.contains("\n")) break;
                        desktopUA = a;
                    }
                    if (on != desktop || (on && !a.equals(content.getSettings().getUserAgentString()))) {
                        desktop = on;
                        sleepBackground();
                        content.getSettings().setUserAgentString(on ? a : mobileUA);
                        content.reload();
                    }
                    break;
                }
                case "adapt": {
                    boolean on = "1".equals(a);
                    if (on != adapt) {
                        adapt = on;
                        savePref("adapt", on);
                        sleepBackground();
                        content.getSettings().setLayoutAlgorithm(on ? WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING : WebSettings.LayoutAlgorithm.NORMAL);
                        content.reload();
                    }
                    break;
                }
                case "clear":
                    content.clearCache(true);
                    content.clearHistory();
                    content.clearFormData();
                    CookieManager.getInstance().removeAllCookies(null);
                    CookieManager.getInstance().flush();
                    WebStorage.getInstance().deleteAllData();
                    GeolocationPermissions.getInstance().clearAll();
                    siteDecisions.clear();
                    saveSiteDecisions();
                    httpAllowed.clear();
                    break;
                case "openfile":
                    openLocalFile();
                    break;
                case "share": {
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("text/plain");
                    i.putExtra(Intent.EXTRA_TEXT, a);
                    startActivity(Intent.createChooser(i, null));
                    break;
                }
                case "exit":
                    finish();
                    break;
                case "updlist":
                    if (incognito()) break;
                    notice("Mise à jour de la liste de blocage…");
                    new Thread(new Runnable() { @Override public void run() {
                        String m = Blocklist.update(getApplicationContext());
                        notice(m);
                        runOnUiThread(new Runnable() { @Override public void run() { toJs("window.onAbout&&onAbout()"); } });
                    } }).start();
                    break;
                case "update":
                    new Thread(new Runnable() { @Override public void run() { Updater.check(MainActivity.this, true); } }).start();
                    break;
                default:
                    // Commandes inconnues ou retirées (ex. « js », « ext ») : ignorées.
                    break;
            }
        } catch (Exception ignored) {
        }
    }

    /* ======================= Navigation sûre ======================= */

    private static boolean isInternal(String u) {
        if (u == null || !u.startsWith(ASSET) || u.contains("..") || u.contains("%2e") || u.contains("%2E")) return false;
        String rest = u.substring(ASSET.length());
        for (String p : INTERNAL_PAGES) {
            if (rest.equals(p) || rest.startsWith(p + "#") || rest.startsWith(p + "?")) return true;
        }
        return false;
    }

    private boolean contentAllowed(String u) {
        if (grantedContent.contains(u)) return true;
        try {
            for (UriPermission p : getContentResolver().getPersistedUriPermissions()) {
                if (p.isReadPermission() && p.getUri().toString().equals(u)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private boolean shouldUpgrade(Uri u) {
        String h = u.getHost();
        if (h == null) return false;
        h = h.toLowerCase(Locale.ROOT);
        return !httpAllowed.contains(h) && !PRIVATE_HOST.matcher(h).matches();
    }

    /** Toute navigation demandée par l'interface passe par ici : liste blanche de schémas, HTTPS en priorité. */
    void navigate(String url) {
        if (url == null || content == null) return;
        url = url.trim();
        if (url.isEmpty()) return;
        Uri u = Uri.parse(url);
        String s = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        switch (s) {
            case "https":
                pendingMain = url;
                content.loadUrl(url);
                return;
            case "http":
                if (shouldUpgrade(u)) {
                    upgradedHosts.add(u.getHost().toLowerCase(Locale.ROOT));
                    pendingMain = "https" + url.substring(4);
                    content.loadUrl(pendingMain);
                } else {
                    pendingMain = url;
                    content.loadUrl(url);
                }
                return;
            case "file":
                if (isInternal(url)) {
                    content.loadUrl(url);
                } else if (allowLocal) {
                    scopeAccess(content, url);
                    content.loadUrl(url);
                } else {
                    notice("Fichiers locaux désactivés (Autorisations › Fichiers locaux)");
                }
                return;
            case "content":
                if (contentAllowed(url)) {
                    scopeAccess(content, url);
                    content.loadUrl(url);
                } else {
                    notice("Fichier non autorisé : ouvre-le avec « Fichier HTML »");
                }
                return;
            case "about":
                if ("about:blank".equals(url)) content.loadUrl(url);
                return;
            default:
                // javascript:, data:, intent:, blob:… refusés depuis la barre d'adresse.
        }
    }

    private void setDark(boolean on) {
        if (on == siteDark) return;
        siteDark = on;
        savePref("siteDark", on);
        sleepBackground();
        rebuildContent();
    }

    private void openLocalFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/html", "application/xhtml+xml", "text/plain", "image/*"});
        try {
            startActivityForResult(i, REQ_OPEN);
        } catch (ActivityNotFoundException e) {
            call("onNotice", "Aucun gestionnaire de fichiers disponible");
        }
    }

    /** Ouvre un lien dans une autre appli, après confirmation, sans jamais cibler un composant précis. */
    void openExternal(final Intent intent, String label) {
        if (intent == null) return;
        intent.setComponent(null);
        intent.setSelector(null);
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setFlags(intent.getFlags() & ~(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION));
        if (getPackageName().equals(intent.getPackage())) return;
        Uri d = intent.getData();
        if (d != null && d.getScheme() != null) {
            String s = d.getScheme().toLowerCase(Locale.ROOT);
            if (s.equals("file") || s.equals("content") || s.equals("javascript") || s.equals("air")) return;
        }
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle("Ouvrir dans une autre appli ?")
                .setMessage(label.length() > 300 ? label.substring(0, 300) + "…" : label)
                .setPositiveButton("Ouvrir", new android.content.DialogInterface.OnClickListener() { @Override public void onClick(android.content.DialogInterface dlg, int w) {
                    try {
                        startActivity(intent);
                    } catch (Exception e) {
                        call("onNotice", "Aucune appli ne peut ouvrir ce lien");
                    }
                } })
                .setNegativeButton("Annuler", null)
                .show();
    }

    void notice(final String m) {
        runOnUiThread(new Runnable() { @Override public void run() { call("onNotice", m); } });
    }

    void toJs(String js) {
        if (chrome != null && chromeTrusted()) chrome.evaluateJavascript(js, null);
    }

    void call(String fn, String arg) {
        toJs(fn + "(" + JSONObject.quote(arg == null ? "" : arg) + ")");
    }

    void onNav(WebView w, String url, boolean loading) {
        if (w != content) return;
        toJs("onNav(" + JSONObject.quote(url == null ? "" : url) + "," + w.canGoBack() + "," + w.canGoForward() + "," + loading + ")");
    }

    void adaptPage(WebView w, String url) {
        if (url == null || url.startsWith(ASSET)) return;
        // Vitesse : une seule injection par page (avant : à chaque étape de chargement).
        if (url.equals(adaptedUrl)) return;
        adaptedUrl = url;
        if (url.startsWith("http") || url.startsWith("file:") || url.startsWith("content:")) {
            if (siteForce) w.evaluateJavascript(forceJs(siteDark), null);
            else if (siteDark && Build.VERSION.SDK_INT < 29) w.evaluateJavascript(DARK_JS, null);
            if (adapt && !desktop) w.evaluateJavascript(ADAPT_JS, null);
        }
    }

    static boolean isTracker(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        Boolean v = verdicts.get(h);
        if (v != null) return v;
        boolean hit = false;
        Set<String> rs = ruleSet;
        if (!rs.isEmpty()) {
            // Même logique que « se termine par .domaine » : on remonte les domaines parents.
            String d = h;
            while (true) {
                if (rs.contains(d)) { hit = true; break; }
                int i = d.indexOf('.');
                if (i < 0) break;
                d = d.substring(i + 1);
            }
        }
        if (!hit) hit = Blocklist.contains(h);
        // La liste peut ne pas être encore chargée au démarrage : on ne mémorise qu'une fois prête.
        if (Blocklist.isLoaded()) {
            if (verdicts.size() > 4000) verdicts.clear();
            verdicts.put(h, hit);
        }
        return hit;
    }

    /** Regroupe les domaines bloqués et les envoie à l'interface au plus toutes les 600 ms. */
    private void reportBlocked(String host) {
        blockedQueue.add(host);
        if (flushScheduled) return;
        flushScheduled = true;
        ui.postDelayed(new Runnable() { @Override public void run() {
            flushScheduled = false;
            org.json.JSONArray arr = new org.json.JSONArray();
            String x;
            int n = 0;
            while ((x = blockedQueue.poll()) != null) {
                if (n++ < 300) arr.put(x);
            }
            if (arr.length() > 0) toJs("onBlockedBatch(" + arr.toString() + ")");
        } }, 600);
    }

    private static WebResourceResponse empty() {
        return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
    }

    private static boolean sameHost(String a, String b) {
        String x = Uri.parse(a).getHost(), y = Uri.parse(b).getHost();
        return x != null && x.equalsIgnoreCase(y);
    }

    private void showError(String kind, String url) {
        String t = lightUi ? "light" : "dark";
        content.loadUrl(ERROR_URL + "#k=" + kind + "&t=" + t + "&u=" + Uri.encode(url == null ? "" : url));
    }

    final class ContentClient extends WebViewClient {
        ContentClient() {
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
            return handleUrl(v, r.getUrl().toString(), r.isForMainFrame());
        }

        @SuppressWarnings("deprecation")
        @Override
        public boolean shouldOverrideUrlLoading(WebView v, String url) {
            return handleUrl(v, url, true);
        }

        /** Navigations décidées par les pages elles-mêmes. true = bloquée/gérée ici. */
        private boolean handleUrl(WebView v, String url, boolean main) {
            Uri u = Uri.parse(url);
            String s = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            String cur = v.getUrl();
            boolean fromInternal = isInternal(cur);
            switch (s) {
                case "https":
                    if (main) pendingMain = url;
                    return false;
                case "http":
                    if (main) pendingMain = url;
                    if (main && shouldUpgrade(u)) {
                        String h = u.getHost().toLowerCase(Locale.ROOT);
                        if (upgradedHosts.contains(h) && cur != null && cur.startsWith("https://" + h)) {
                            // Le site renvoie lui-même vers HTTP : on prévient au lieu de boucler.
                            showError("http", url);
                            return true;
                        }
                        upgradedHosts.add(h);
                        pendingMain = "https" + url.substring(4);
                        v.loadUrl(pendingMain);
                        return true;
                    }
                    return false;
                case "air":
                    // Liens internes : uniquement depuis les pages d'AIR Navig, jamais depuis un site.
                    if (!fromInternal) return true;
                    if ("insecure".equals(u.getHost())) {
                        String target = u.getQueryParameter("u");
                        if (target != null && target.startsWith("http://")) {
                            String h = Uri.parse(target).getHost();
                            if (h != null) {
                                httpAllowed.add(h.toLowerCase(Locale.ROOT));
                                v.loadUrl(target);
                            }
                        }
                        return true;
                    }
                    call("onAir", url);
                    return true;
                case "file":
                    if (isInternal(url)) return !fromInternal;
                    // Un fichier local ne peut ouvrir qu'un autre fichier local, et seulement si autorisé.
                    return !(allowLocal && cur != null && cur.startsWith("file:") && !cur.startsWith(ASSET));
                case "content":
                case "javascript":
                case "data":
                case "blob":
                    return true;
                case "about":
                    return !"about:blank".equals(url);
                case "intent":
                    try {
                        Intent i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                        String fb = i.getStringExtra("browser_fallback_url");
                        if (i.getPackage() != null && i.getPackage().equals(getPackageName())) return true;
                        if (getPackageManager().resolveActivity(i.addCategory(Intent.CATEGORY_BROWSABLE), 0) == null) {
                            if (fb != null && (fb.startsWith("https://") || fb.startsWith("http://"))) navigate(fb);
                            return true;
                        }
                        openExternal(i, "Ce site veut ouvrir une autre appli.");
                    } catch (Exception ignored) {
                    }
                    return true;
                default:
                    if (main) {
                        openExternal(new Intent(Intent.ACTION_VIEW, u), "Ce site veut ouvrir : " + s + ":");
                    }
                    return true;
            }
        }

        @Override
        public void onPageStarted(WebView v, String url, Bitmap fav) {
            if (v == content) {
                adaptedUrl = null;
                forcedThisPage = false;
                lastProgress = -1;
            }
            scopeAccess(v, url);
            onNav(v, url, true);
        }

        @Override
        public void onPageFinished(WebView v, String url) {
            onNav(v, url, false);
            adaptPage(v, url);
        }

        @Override
        public void onPageCommitVisible(WebView v, String url) {
            if (v == content) pendingMain = null;
            adaptPage(v, url);
        }

        @Override
        public void doUpdateVisitedHistory(WebView v, String url, boolean reload) {
            onNav(v, url, false);
        }

        /** Erreur de certificat : connexion toujours refusée, jamais de contournement. */
        @Override
        public void onReceivedSslError(WebView v, SslErrorHandler h, SslError e) {
            h.cancel();
            String url = e.getUrl();
            String pm = pendingMain;
            if (v == content && url != null && pm != null && sameHost(url, pm)) {
                pendingMain = null;
                String host = Uri.parse(url).getHost();
                boolean upgraded = host != null && upgradedHosts.contains(host.toLowerCase(Locale.ROOT));
                showError(upgraded ? "https" : "ssl", upgraded ? "http" + url.substring(5) : url);
            }
            call("onNotice", "Certificat non valide : connexion bloquée");
        }

        @Override
        public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError err) {
            if (!r.isForMainFrame() || v != content) return;
            Uri u = r.getUrl();
            if (!"https".equals(u.getScheme()) || u.getHost() == null) return;
            if (upgradedHosts.contains(u.getHost().toLowerCase(Locale.ROOT))) {
                showError("https", "http" + u.toString().substring(5));
            }
        }

        @Override
        public void onReceivedHttpAuthRequest(WebView v, android.webkit.HttpAuthHandler h, String host, String realm) {
            h.cancel();
        }

        @Override
        public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
            // Un site qui fait planter le moteur ne fait pas planter l'appli.
            if (v != content && pool.containsValue(v)) {
                String dead = null;
                for (Map.Entry<String, WebView> e : pool.entrySet()) if (e.getValue() == v) dead = e.getKey();
                if (dead != null) {
                    pool.remove(dead);
                    lastUsed.remove(dead);
                }
                try {
                    v.destroy();
                } catch (Throwable ignored) {
                }
                reportLive();
                return true;
            }
            if (v == content) {
                final String last = v.getUrl();
                col.removeView(v);
                try {
                    v.destroy();
                } catch (Throwable ignored) {
                }
                content = null;
                createContent();
                arrange();
                call("onNotice", "La page a planté et a été fermée par sécurité");
                if (last != null && (last.startsWith("https://") || last.startsWith("http://"))) navigate(last);
                return true;
            }
            recreateSafely();
            return true;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
            if (r.isForMainFrame()) return null;
            Uri u = r.getUrl();
            final String host = u.getHost();
            if (host != null && blockOn && isTracker(host)) {
                reportBlocked(host);
                return empty();
            }
            return null;
        }
    }

    final class ContentChrome extends WebChromeClient {
        ContentChrome() {
        }

        @Override
        public void onReceivedTitle(WebView v, String t) {
            if (v == content) call("onTitle", t);
        }

        @Override
        public void onProgressChanged(WebView v, int p) {
            if (v != content) return;
            // Vitesse : la barre de progression n'est mise à jour que par paliers de 10 %.
            if (p >= 100 || p - lastProgress >= 10 || p < lastProgress) {
                lastProgress = p;
                toJs("onProgress(" + p + ")");
            }
            if (p >= 25 && siteForce && !forcedThisPage) {
                forcedThisPage = true;
                adaptPage(v, v.getUrl());
            }
        }

        @Override
        public void onShowCustomView(View view, WebChromeClient.CustomViewCallback cb) {
            showCustom(view, cb);
        }

        @Override
        public void onHideCustomView() {
            hideCustom();
        }

        @Override
        public void onPermissionRequest(final PermissionRequest req) {
            final String origin = String.valueOf(req.getOrigin());
            final List<String> wanted = new ArrayList<String>();
            final List<String> undecided = new ArrayList<String>();
            for (String r : req.getResources()) {
                String kind = kindOf(r);
                if (kind == null) continue; // MIDI SysEx etc. : refusé
                if ("cam".equals(kind) && !allowCam) continue;
                if ("mic".equals(kind) && !allowMic) continue;
                Boolean d = siteDecisions.get(origin + "|" + kind);
                if (d == null) {
                    wanted.add(r);
                    undecided.add(r);
                } else if (d) {
                    wanted.add(r);
                }
            }
            if (wanted.isEmpty()) {
                req.deny();
                call("onPerm", origin + "|refusé");
                return;
            }
            if (undecided.isEmpty()) {
                grantWithAndroid(req, wanted);
                return;
            }
            askSite(origin, describe(undecided), new Answer() { @Override public void on(boolean allow, boolean remember) {
                for (String r : undecided) {
                    if (remember) siteDecisions.put(origin + "|" + kindOf(r), allow);
                    if (!allow) wanted.remove(r);
                }
                if (remember) saveSiteDecisions();
                if (wanted.isEmpty()) {
                    req.deny();
                    call("onPerm", origin + "|refusé");
                } else {
                    grantWithAndroid(req, wanted);
                }
            } }, new Runnable() { @Override public void run() { req.deny(); } });
        }

        @Override
        public void onPermissionRequestCanceled(PermissionRequest req) {
            if (permDialog != null && permDialog.isShowing()) permDialog.dismiss();
            if (pendingRequest == req) {
                pendingRequest = null;
                pendingResources = null;
            }
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(final String origin, final GeolocationPermissions.Callback cb) {
            if (!allowGeo || origin == null || !origin.startsWith("https://")) {
                cb.invoke(origin, false, false);
                return;
            }
            Boolean d = siteDecisions.get(origin + "|geo");
            if (d != null) {
                if (d) grantGeo(origin, cb);
                else cb.invoke(origin, false, false);
                return;
            }
            askSite(origin, "ta position (approximative)", new Answer() { @Override public void on(boolean allow, boolean remember) {
                if (remember) {
                    siteDecisions.put(origin + "|geo", allow);
                    saveSiteDecisions();
                }
                if (allow) grantGeo(origin, cb);
                else {
                    cb.invoke(origin, false, false);
                    call("onPerm", origin + "|refusé");
                }
            } }, new Runnable() { @Override public void run() { cb.invoke(origin, false, false); } });
        }

        @Override
        public void onGeolocationPermissionsHidePrompt() {
            if (permDialog != null && permDialog.isShowing()) permDialog.dismiss();
        }

        @Override
        public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, WebChromeClient.FileChooserParams p) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = cb;
            try {
                startActivityForResult(p.createIntent(), REQ_FILE);
                return true;
            } catch (Exception e) {
                fileCallback = null;
                return false;
            }
        }
    }

    static String kindOf(String res) {
        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res)) return "cam";
        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)) return "mic";
        if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(res)) return "drm";
        return null;
    }

    interface Answer {
        void on(boolean allow, boolean remember);
    }

    /** Fenêtre de confirmation par site : rien n'est accordé sans l'accord explicite de l'utilisateur. */
    private void askSite(String origin, String what, final Answer ans, final Runnable cancel) {
        if (isFinishing()) {
            cancel.run();
            return;
        }
        if (permDialog != null && permDialog.isShowing()) permDialog.dismiss();
        String host = Uri.parse(origin).getHost();
        final boolean[] done = {false};
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle((host == null ? origin : host) + " demande l'accès")
                .setMessage("Ce site veut utiliser : " + what + ".\n\nN'accepte que si tu fais confiance à ce site.")
                .setPositiveButton("Autoriser cette fois", new android.content.DialogInterface.OnClickListener() { @Override public void onClick(android.content.DialogInterface d, int w) {
                    done[0] = true;
                    ans.on(true, false);
                } })
                .setNegativeButton("Refuser", new android.content.DialogInterface.OnClickListener() { @Override public void onClick(android.content.DialogInterface d, int w) {
                    done[0] = true;
                    ans.on(false, !incognito());
                } })
                .setOnDismissListener(new android.content.DialogInterface.OnDismissListener() { @Override public void onDismiss(android.content.DialogInterface d) {
                    if (!done[0]) cancel.run();
                } });
        if (!incognito()) {
            b.setNeutralButton("Toujours pour ce site", new android.content.DialogInterface.OnClickListener() { @Override public void onClick(android.content.DialogInterface d, int w) {
                done[0] = true;
                ans.on(true, true);
            } });
        }
        permDialog = b.show();
    }

    private void grantWithAndroid(PermissionRequest req, List<String> res) {
        List<String> missing = new ArrayList<String>();
        for (String r : res) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) && checkSelfPermission(android.Manifest.permission.CAMERA) != 0) missing.add(android.Manifest.permission.CAMERA);
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r) && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != 0) missing.add(android.Manifest.permission.RECORD_AUDIO);
        }
        if (!missing.isEmpty()) {
            pendingRequest = req;
            pendingResources = res.toArray(new String[0]);
            requestPermissions(missing.toArray(new String[0]), REQ_MEDIA);
            return;
        }
        req.grant(res.toArray(new String[0]));
        call("onPerm", req.getOrigin() + "|" + describe(res));
    }

    private void grantGeo(String origin, GeolocationPermissions.Callback cb) {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != 0) {
            geoCallback = cb;
            geoOrigin = origin;
            requestPermissions(new String[]{android.Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_GEO);
        } else {
            cb.invoke(origin, true, false);
            call("onPerm", origin + "|position");
        }
    }

    static String describe(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (String r : list) {
            String k = kindOf(r);
            String n = "cam".equals(k) ? "caméra" : "mic".equals(k) ? "micro" : "vidéo protégée (DRM)";
            if (sb.length() > 0) sb.append(", ");
            sb.append(n);
        }
        return sb.toString();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == REQ_MEDIA && pendingRequest != null) {
            List<String> ok = new ArrayList<String>();
            for (String r : pendingResources) {
                boolean cam = !PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) || checkSelfPermission(android.Manifest.permission.CAMERA) == 0;
                boolean mic = !PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r) || checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == 0;
                if (cam && mic) ok.add(r);
            }
            if (ok.isEmpty()) pendingRequest.deny();
            else {
                pendingRequest.grant(ok.toArray(new String[0]));
                call("onPerm", pendingRequest.getOrigin() + "|" + describe(ok));
            }
            pendingRequest = null;
            pendingResources = null;
            return;
        }
        if (code == REQ_GEO && geoCallback != null) {
            boolean ok = false;
            for (int r : res) ok |= r == 0;
            geoCallback.invoke(geoOrigin, ok, false);
            if (ok) call("onPerm", geoOrigin + "|position");
            geoCallback = null;
        }
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == REQ_FILE && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data));
            fileCallback = null;
        } else if (code == REQ_OPEN && result == RESULT_OK && data != null && data.getData() != null) {
            Uri d = data.getData();
            if (!"content".equals(d.getScheme())) return;
            try {
                getContentResolver().takePersistableUriPermission(d, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            grantedContent.add(d.toString());
            call("openUrl", d.toString());
        }
    }

    final class Downloads implements DownloadListener {
        Downloads() {
        }

        @Override
        public void onDownloadStart(String url, String ua, String disp, String mime, long len) {
            if (!allowDownloads) {
                call("onDlBlocked", url);
                return;
            }
            Uri u = Uri.parse(url);
            String s = u.getScheme();
            if (!"https".equals(s) && !"http".equals(s)) {
                call("onNotice", "Téléchargement non pris en charge pour ce type de lien");
                return;
            }
            try {
                DownloadManager.Request r = new DownloadManager.Request(u);
                if (mime != null) r.setMimeType(mime);
                r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                String name = URLUtil.guessFileName(url, disp, mime).replace("/", "_");
                r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                String cookie = CookieManager.getInstance().getCookie(url);
                if (cookie != null) r.addRequestHeader("Cookie", cookie);
                if (ua != null) r.addRequestHeader("User-Agent", ua);
                ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(r);
                call("onDownload", name);
            } catch (Exception e) {
                call("onNotice", "Téléchargement impossible");
            }
        }
    }

    void showCustom(View v, WebChromeClient.CustomViewCallback cb) {
        if (customView != null) {
            cb.onCustomViewHidden();
            return;
        }
        customView = v;
        customCallback = cb;
        root.addView(v, new FrameLayout.LayoutParams(-1, -1));
        col.setVisibility(View.INVISIBLE);
        getWindow().getDecorView().setSystemUiVisibility(4102);
    }

    void hideCustom() {
        if (customView == null) return;
        root.removeView(customView);
        customView = null;
        col.setVisibility(View.VISIBLE);
        getWindow().getDecorView().setSystemUiVisibility(0);
        applySavedTheme();
        if (customCallback != null) {
            customCallback.onCustomViewHidden();
            customCallback = null;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String u = incomingUrl(intent);
        if (u != null) call("openUrl", u);
    }

    @Override
    public void onBackPressed() {
        if (customView == null) toJs("onBack()");
        else hideCustom();
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        boolean inc = incognito();
        if (permDialog != null && permDialog.isShowing()) permDialog.dismiss();
        if (content != null) {
            if (inc) {
                try {
                    content.stopLoading();
                    content.clearCache(true);
                    content.clearHistory();
                    content.clearFormData();
                    if (Build.VERSION.SDK_INT >= 28) {
                        CookieManager.getInstance().removeAllCookies(null);
                        CookieManager.getInstance().flush();
                        WebStorage.getInstance().deleteAllData();
                    }
                } catch (Throwable ignored) {
                }
            }
            content.destroy();
        }
        ui.removeCallbacksAndMessages(null);
        for (WebView w : pool.values()) {
            try {
                if (inc) w.clearCache(true);
                w.destroy();
            } catch (Throwable ignored) {
            }
        }
        pool.clear();
        if (chrome != null) chrome.destroy();
        super.onDestroy();
        if (inc) {
            wipeIncognito();
            Process.killProcess(Process.myPid());
        }
    }
}
