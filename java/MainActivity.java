package app.airnavig;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.air.core.AIRCore;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.StorageController;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebRequestError;
import org.mozilla.geckoview.WebResponse;

/**
 * AIR Navig — navigateur de la marque AIR, créé par Rami, un peu passionné de développement.
 *
 * <p>Moteur : AIRCore (Gecko, sans télémétrie). Plus aucun WebView : l'interface (chrome.html)
 * et les sites tournent tous les deux dans Gecko, dans deux sessions séparées. L'interface
 * parle à l'appli par la messagerie native de l'extension intégrée « air@airnavig.app ».
 *
 * <p>Sécurité conservée : pont réservé à la page d'interface, HTTPS en priorité, aucune erreur
 * de certificat contournable, autorisations demandées site par site, accès fichiers coupés par
 * défaut, liste de blocage d'origine documentée.
 */
public class MainActivity extends Activity {
    static final String CREATOR = "Rami";
    static final String BRAND = "AIR";

    private static final int BG = 0xFF000000;
    private static final int BG_LIGHT = 0xFFFFFFFF;
    private static final String[] INTERNAL_PAGES = {"home.html", "blocked.html", "error.html"};
    static final int POS_BOTTOM = 0;
    static final int POS_TOP = 1;
    static final int POS_RIGHT = 2;
    private static final int REQ_ANDROID_PERMS = 7;
    static final int REQ_FILE = 9;
    private static final int REQ_OPEN = 11;
    private static final int MAX_ARG = 256 * 1024;
    private static final Pattern PRIVATE_HOST = Pattern.compile("^(localhost|127\\.\\d+\\.\\d+\\.\\d+|10\\.\\d+\\.\\d+\\.\\d+|192\\.168\\.\\d+\\.\\d+|172\\.(1[6-9]|2\\d|3[01])\\.\\d+\\.\\d+|\\[::1\\]|.+\\.local)$");

    private GeckoView chromeView;
    private GeckoView contentView;
    private GeckoSession uiSession;
    private GeckoSession content;
    private WebExtension.Port uiPort;
    private boolean uiReady;
    private final List<String> jsQueue = new ArrayList<>();

    private LinearLayout col;
    private FrameLayout root;
    private float density;
    private SharedPreferences prefs;
    private AlertDialog permDialog;
    private Prompts prompts;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /* État de la page affichée. */
    private String curUrl = "";
    private boolean canBack;
    private boolean canFwd;
    private boolean loading;
    private int lastProgress = -1;
    private boolean fullscreen;

    /* Autorisations globales : prudentes par défaut. Micro/caméra/position = le site peut DEMANDER (confirmation à chaque site). */
    volatile boolean allowMic = true;
    volatile boolean allowCam = true;
    volatile boolean allowGeo = true;
    volatile boolean allowPopups = false;
    volatile boolean allowDownloads = true;
    volatile boolean allowLocal = false;
    volatile boolean adapt = true;
    private String rulesCsv = "";
    private boolean blockOn = true;

    /* Décisions par site (origine + type d'accès). En navigation privée : en mémoire seulement. */
    private final Map<String, Boolean> siteDecisions = Collections.synchronizedMap(new HashMap<String, Boolean>());
    /* HTTPS en priorité : hôtes pour lesquels l'utilisateur a accepté le HTTP non chiffré. */
    private final Set<String> httpAllowed = Collections.synchronizedSet(new HashSet<String>());
    private final Set<String> upgradedHosts = Collections.synchronizedSet(new HashSet<String>());
    private final Set<String> grantedContent = Collections.synchronizedSet(new HashSet<String>());

    private boolean desktop = false;
    private boolean siteDark = false;
    private boolean siteForce = false;
    private boolean lightUi = false;
    private int pos = POS_BOTTOM;
    private int barSize = 120;
    private boolean barFull = false;

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
        int flags = decor.getSystemUiVisibility() & ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        if (light) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        decor.setSystemUiVisibility(flags);
        if (root != null) root.setBackgroundColor(c);
        if (chromeView != null) chromeView.coverUntilFirstPaint(c);
        if (contentView != null) contentView.coverUntilFirstPaint(c);
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

    boolean systemDark() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration configuration) {
        super.onConfigurationChanged(configuration);
        toJs("window.onSysDark&&onSysDark(" + systemDark() + ")");
    }

    /* ======================= Création ======================= */

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        if (incognito()) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        density = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences("air", 0);
        loadPermissions(prefs.getString("perms2", ""));
        if (!incognito()) loadSiteDecisions();
        siteDark = prefs.getBoolean("siteDark", false);
        siteForce = prefs.getBoolean("siteForce", false);
        pos = prefs.getInt("pos", POS_BOTTOM);
        barSize = pos == POS_RIGHT ? 64 : pos == POS_TOP ? 60 : 120;
        adapt = prefs.getBoolean("adapt", true);

        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        col = new LinearLayout(this);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));
        contentView = new GeckoView(this);
        chromeView = new GeckoView(this);
        prompts = new Prompts(this);
        arrange();
        setContentView(root);
        applySavedTheme();

        Engine.live.add(this);
        Engine.front = this;
        final String initial = incomingUrl(getIntent());
        Engine.start(this, () -> {
            if (isFinishing() || isDestroyed()) return;
            applyLook();
            createContent();
            createUi(initial);
        });

        new Thread(() -> {
            Blocklist.ensureLoaded(getApplicationContext());
            Engine.sendHosts();
            if (!incognito()) {
                if (Blocklist.due(getApplicationContext())) Blocklist.update(getApplicationContext());
                if (Updater.due(getApplicationContext())) Updater.check(MainActivity.this, false);
            }
        }).start();
    }

    /** Session de l'interface : la page chrome.html de l'extension intégrée. */
    private void createUi(String initial) {
        uiSession = new GeckoSession(new GeckoSessionSettings.Builder()
                .usePrivateMode(false)
                .suspendMediaWhenInactive(true)
                .build());
        uiSession.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Nullable
            @Override
            public GeckoResult<AllowOrDeny> onLoadRequest(@NonNull GeckoSession s, @NonNull LoadRequest r) {
                if (r.uri.startsWith(Engine.base + "chrome.html")) return GeckoResult.allow();
                // Un lien cliqué dans l'interface s'ouvre dans la page, jamais dans l'interface.
                String sc = Uri.parse(r.uri).getScheme();
                if ("https".equals(sc) || "http".equals(sc)) ui.post(() -> navigate(r.uri));
                return GeckoResult.deny();
            }

            @Nullable
            @Override
            public GeckoResult<GeckoSession> onNewSession(@NonNull GeckoSession s, @NonNull String uri) {
                ui.post(() -> navigate(uri));
                return null;
            }
        });
        uiSession.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override
            public void onCrash(@NonNull GeckoSession s) {
                recreateSafely();
            }

            @Override
            public void onKill(@NonNull GeckoSession s) {
                recreateSafely();
            }
        });
        uiSession.setPromptDelegate(prompts);
        uiSession.getWebExtensionController().setMessageDelegate(Engine.ext, new WebExtension.MessageDelegate() {
            @Override
            public void onConnect(@NonNull WebExtension.Port port) {
                acceptUiPort(port);
            }

            @Nullable
            @Override
            public GeckoResult<Object> onMessage(@NonNull String nativeApp, @NonNull Object message,
                                                 @NonNull WebExtension.MessageSender sender) {
                return null;
            }
        }, "air-ui");
        uiSession.open(Engine.runtime);
        chromeView.setSession(uiSession);

        JSONObject init = new JSONObject();
        try {
            init.put("sysDark", systemDark());
            init.put("incog", incognito());
            init.put("pos", pos == POS_TOP ? "top" : pos == POS_RIGHT ? "right" : "bottom");
            if (incognito()) {
                String s = getIntent() == null ? null : getIntent().getStringExtra("init");
                if (s != null && s.length() <= MAX_ARG) init.put("initSettings", s);
            }
            init.put("about", about());
        } catch (Exception ignored) {
        }
        uiSession.loadUri(Engine.base + "chrome.html?i=" + Uri.encode(init.toString())
                + "#" + Uri.encode(initial == null ? "" : initial));
    }

    /** L'interface et elle seule peut piloter l'appli. */
    boolean ownsUi(@Nullable GeckoSession s) {
        return s != null && s == uiSession;
    }

    void acceptUiPort(@NonNull WebExtension.Port port) {
        String from = port.sender.url;
        if (from == null || !from.startsWith(Engine.base + "chrome.html")) {
            port.disconnect();
            return;
        }
        uiPort = port;
        port.setDelegate(new WebExtension.PortDelegate() {
            @Override
            public void onPortMessage(@NonNull Object message, @NonNull WebExtension.Port p) {
                if (p != uiPort || !(message instanceof JSONObject)) return;
                JSONObject m = (JSONObject) message;
                if (m.has("ready")) {
                    uiReady = true;
                    List<String> q = new ArrayList<>(jsQueue);
                    jsQueue.clear();
                    for (String js : q) sendJs(js);
                    return;
                }
                String c = m.optString("c", "");
                if (c.isEmpty() || c.length() > 32) return;
                String a = m.optString("a", "");
                if (a.length() > MAX_ARG) a = a.substring(0, MAX_ARG);
                runCmd(c, a);
            }

            @Override
            public void onDisconnect(@NonNull WebExtension.Port p) {
                if (p == uiPort) {
                    uiPort = null;
                    uiReady = false;
                }
            }
        });
    }

    /** Session des sites. Navigation privée : rien n'est gardé sur le disque. */
    private void createContent() {
        content = new GeckoSession(new GeckoSessionSettings.Builder()
                .usePrivateMode(incognito())
                .userAgentMode(desktop ? GeckoSessionSettings.USER_AGENT_MODE_DESKTOP : GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
                .viewportMode(desktop ? GeckoSessionSettings.VIEWPORT_MODE_DESKTOP : GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
                .suspendMediaWhenInactive(true)
                .build());
        content.setNavigationDelegate(new Nav());
        content.setProgressDelegate(new Progress());
        content.setContentDelegate(new Content());
        content.setPermissionDelegate(new Perms(this));
        content.setPromptDelegate(prompts);
        content.open(Engine.runtime);
        contentView.setSession(content);
    }

    private void recreateSafely() {
        ui.post(this::recreate);
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

    /** Mode sombre des sites : thème préféré annoncé aux sites + scripts de content.js. */
    private void applyLook() {
        Engine.setLook(siteDark, siteForce);
        if (Engine.runtime != null) {
            Engine.runtime.getSettings().setPreferredColorScheme(siteDark
                    ? GeckoRuntimeSettings.COLOR_SCHEME_DARK : GeckoRuntimeSettings.COLOR_SCHEME_LIGHT);
        }
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

    void saveSiteDecisions() {
        if (incognito()) return;
        JSONObject j;
        synchronized (siteDecisions) {
            j = new JSONObject(siteDecisions);
        }
        prefs.edit().putString("sitePerms", j.toString()).apply();
    }

    Boolean siteDecision(String key) {
        return siteDecisions.get(key);
    }

    void rememberSite(String key, boolean allow) {
        siteDecisions.put(key, allow);
        saveSiteDecisions();
    }

    int px(int i) {
        return (int) (i * density);
    }

    private void arrange() {
        if (col == null || contentView == null || chromeView == null) return;
        col.removeAllViews();
        boolean side = pos == POS_RIGHT;
        col.setOrientation(side ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cp = side ? new LinearLayout.LayoutParams(0, -1, 1f) : new LinearLayout.LayoutParams(-1, 0, 1f);
        LinearLayout.LayoutParams bp;
        if (barFull) {
            bp = new LinearLayout.LayoutParams(-1, -1);
            contentView.setVisibility(View.GONE);
        } else {
            bp = side ? new LinearLayout.LayoutParams(px(barSize), -1) : new LinearLayout.LayoutParams(-1, px(barSize));
            contentView.setVisibility(View.VISIBLE);
        }
        if (fullscreen) chromeView.setVisibility(View.GONE);
        else chromeView.setVisibility(View.VISIBLE);
        if (pos == POS_TOP) {
            col.addView(chromeView, bp);
            col.addView(contentView, cp);
        } else {
            col.addView(contentView, cp);
            col.addView(chromeView, bp);
        }
    }

    private void setPos(String str) {
        int p = "top".equals(str) ? POS_TOP : "right".equals(str) ? POS_RIGHT : POS_BOTTOM;
        if (p == pos) return;
        pos = p;
        savePref("pos", p);
        barSize = p == POS_RIGHT ? 64 : p == POS_TOP ? 60 : 120;
        arrange();
    }

    private void setBar(int i) {
        if (i <= 0) {
            barFull = true;
            contentView.setVisibility(View.GONE);
            ViewGroup.LayoutParams lp = chromeView.getLayoutParams();
            lp.width = -1;
            lp.height = -1;
            chromeView.setLayoutParams(lp);
            return;
        }
        barFull = false;
        barSize = Math.min(i, 2000);
        ViewGroup.LayoutParams lp = chromeView.getLayoutParams();
        if (pos == POS_RIGHT) {
            lp.width = px(barSize);
            lp.height = -1;
        } else {
            lp.width = -1;
            lp.height = px(barSize);
        }
        contentView.setVisibility(View.VISIBLE);
        chromeView.setLayoutParams(lp);
    }

    /* ======================= Interface ↔ appli ======================= */

    private JSONObject about() {
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
            j.put("signer", Updater.signerFingerprint(this));
            j.put("listSource", Blocklist.SOURCE_NAME);
            j.put("listHome", Blocklist.SOURCE_HOME);
            j.put("listCount", Blocklist.hosts.size());
            j.put("listDate", Blocklist.date);
            j.put("listOrigin", Blocklist.origin);
            j.put("listSha", Blocklist.sha256);
            j.put("appUpdate", Updater.status());
            // Ancien champ « webview » : c'est désormais le moteur AIRCore.
            j.put("webview", AIRCore.NAME + " " + AIRCore.VERSION + " (Gecko " + AIRCore.geckoVersion() + ")");
            j.put("engine", AIRCore.NAME + " " + AIRCore.VERSION);
            j.put("gecko", AIRCore.geckoVersion());
            return j;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void pushAbout() {
        if (uiPort == null) return;
        try {
            uiPort.postMessage(new JSONObject().put("about", about()));
        } catch (Exception ignored) {
        }
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
                case "back":
                    if (canBack && content != null) content.goBack();
                    break;
                case "fwd":
                    if (canFwd && content != null) content.goForward();
                    break;
                case "reload":
                    if (content != null) content.reload();
                    break;
                case "stop":
                    if (content != null) content.stop();
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
                        applyLook();
                        if (content != null) content.reload();
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
                    Engine.setBlock(blockOn);
                    break;
                case "rules": {
                    List<String> ok = new ArrayList<>();
                    for (String r : a.split(",")) {
                        String t = r.trim().toLowerCase(Locale.ROOT);
                        if (t.matches("^\\.[a-z0-9.-]{3,253}$")) ok.add(t.substring(1));
                        if (ok.size() >= 5000) break;
                    }
                    rulesCsv = android.text.TextUtils.join(",", ok);
                    Engine.setRules(rulesCsv);
                    break;
                }
                case "perms":
                    loadPermissions(a);
                    savePref("perms2", a);
                    break;
                case "forgetperms":
                    siteDecisions.clear();
                    saveSiteDecisions();
                    httpAllowed.clear();
                    Engine.runtime.getStorageController().clearData(StorageController.ClearFlags.PERMISSIONS);
                    notice("Autorisations des sites oubliées");
                    break;
                case "sandbox": {
                    if (!a.startsWith("https://") && !a.startsWith("http://")) break;
                    Intent i = new Intent(this, SandboxActivity.class);
                    i.putExtra("url", a);
                    startActivity(i);
                    break;
                }
                case "desktop": {
                    boolean on = !a.isEmpty();
                    if (on != desktop && content != null) {
                        desktop = on;
                        content.getSettings().setUserAgentMode(on ? GeckoSessionSettings.USER_AGENT_MODE_DESKTOP : GeckoSessionSettings.USER_AGENT_MODE_MOBILE);
                        content.getSettings().setViewportMode(on ? GeckoSessionSettings.VIEWPORT_MODE_DESKTOP : GeckoSessionSettings.VIEWPORT_MODE_MOBILE);
                        content.reload();
                    }
                    break;
                }
                case "adapt": {
                    boolean on = "1".equals(a);
                    if (on != adapt) {
                        adapt = on;
                        savePref("adapt", on);
                        Engine.runtime.getSettings().setFontInflationEnabled(on);
                        if (content != null) content.reload();
                    }
                    break;
                }
                case "clear":
                    Engine.runtime.getStorageController().clearData(StorageController.ClearFlags.ALL);
                    if (content != null) content.purgeHistory();
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
                    new Thread(() -> {
                        String m = Blocklist.update(getApplicationContext());
                        notice(m);
                        runOnUiThread(() -> {
                            pushAbout();
                            toJs("window.onAbout&&onAbout()");
                        });
                    }).start();
                    break;
                case "update":
                    new Thread(() -> Updater.check(MainActivity.this, true)).start();
                    break;
                default:
                    // Commandes inconnues ou retirées : ignorées.
                    break;
            }
        } catch (Exception ignored) {
        }
    }

    /* ======================= Navigation sûre ======================= */

    boolean isInternal(String u) {
        String base = Engine.base;
        if (u == null || base == null || !u.startsWith(base) || u.contains("..") || u.contains("%2e") || u.contains("%2E")) return false;
        String rest = u.substring(base.length());
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
                content.loadUri(url);
                return;
            case "http":
                if (shouldUpgrade(u)) {
                    upgradedHosts.add(u.getHost().toLowerCase(Locale.ROOT));
                    content.loadUri("https" + url.substring(4));
                } else {
                    content.loadUri(url);
                }
                return;
            case "moz-extension":
                if (isInternal(url)) content.loadUri(url);
                return;
            case "file":
                if (allowLocal) content.loadUri(url);
                else notice("Fichiers locaux désactivés (Autorisations › Fichiers locaux)");
                return;
            case "content":
                if (contentAllowed(url)) content.loadUri(url);
                else notice("Fichier non autorisé : ouvre-le avec « Fichier HTML »");
                return;
            case "about":
                if ("about:blank".equals(url)) content.loadUri(url);
                return;
            default:
                // javascript:, data:, intent:, blob:… refusés depuis la barre d'adresse.
        }
    }

    private void setDark(boolean on) {
        if (on == siteDark) return;
        siteDark = on;
        savePref("siteDark", on);
        applyLook();
        if (content != null) content.reload();
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
                .setPositiveButton("Ouvrir", (dlg, w) -> {
                    try {
                        startActivity(intent);
                    } catch (Exception e) {
                        call("onNotice", "Aucune appli ne peut ouvrir ce lien");
                    }
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    void notice(final String m) {
        runOnUiThread(() -> call("onNotice", m));
    }

    /** Exécute du JavaScript dans l'interface (file d'attente tant qu'elle n'est pas prête). */
    void toJs(String js) {
        if (uiPort != null && uiReady) sendJs(js);
        else if (jsQueue.size() < 300) jsQueue.add(js);
    }

    private void sendJs(String js) {
        try {
            uiPort.postMessage(new JSONObject().put("js", js));
        } catch (Exception ignored) {
        }
    }

    void call(String fn, String arg) {
        toJs(fn + "(" + JSONObject.quote(arg == null ? "" : arg) + ")");
    }

    private void onNav() {
        toJs("onNav(" + JSONObject.quote(curUrl == null ? "" : curUrl) + "," + canBack + "," + canFwd + "," + loading + ")");
    }

    /** Traqueurs bloqués par background.js (par lots). */
    void onBlocked(JSONArray list) {
        toJs("onBlockedBatch(" + list.toString() + ")");
    }

    private static boolean sameHost(String a, String b) {
        String x = Uri.parse(a).getHost(), y = Uri.parse(b).getHost();
        return x != null && x.equalsIgnoreCase(y);
    }

    String errorPage(String kind, String url) {
        String t = lightUi ? "light" : "dark";
        return Engine.base + "error.html#k=" + kind + "&t=" + t + "&u=" + Uri.encode(url == null ? "" : url);
    }

    /* ======================= Délégués Gecko : sites ======================= */

    private final class Nav implements GeckoSession.NavigationDelegate {
        @Override
        public void onLocationChange(@NonNull GeckoSession s, @Nullable String url,
                                     @NonNull List<org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission> perms,
                                     @NonNull Boolean hasUserGesture) {
            curUrl = url == null ? "" : url;
            onNav();
        }

        @Override
        public void onCanGoBack(@NonNull GeckoSession s, boolean b) {
            canBack = b;
        }

        @Override
        public void onCanGoForward(@NonNull GeckoSession s, boolean f) {
            canFwd = f;
        }

        /** Navigations décidées par les pages elles-mêmes (ou par l'appli). */
        @Nullable
        @Override
        public GeckoResult<AllowOrDeny> onLoadRequest(@NonNull GeckoSession s, @NonNull LoadRequest r) {
            final String url = r.uri;
            Uri u = Uri.parse(url);
            String sc = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            String cur = curUrl;
            boolean fromInternal = isInternal(cur);
            switch (sc) {
                case "https":
                    return GeckoResult.allow();
                case "http":
                    if (shouldUpgrade(u)) {
                        String h = u.getHost().toLowerCase(Locale.ROOT);
                        if (upgradedHosts.contains(h) && cur != null && cur.startsWith("https://" + h)) {
                            // Le site renvoie lui-même vers HTTP : on prévient au lieu de boucler.
                            ui.post(() -> content.loadUri(errorPage("http", url)));
                            return GeckoResult.deny();
                        }
                        upgradedHosts.add(h);
                        ui.post(() -> content.loadUri("https" + url.substring(4)));
                        return GeckoResult.deny();
                    }
                    return GeckoResult.allow();
                case "moz-extension":
                    return isInternal(url) && (r.isDirectNavigation || fromInternal) ? GeckoResult.allow() : GeckoResult.deny();
                case "air":
                    // Liens internes : uniquement depuis les pages d'AIR Navig, jamais depuis un site.
                    if (!fromInternal) return GeckoResult.deny();
                    if ("insecure".equals(u.getHost())) {
                        String target = u.getQueryParameter("u");
                        if (target != null && target.startsWith("http://")) {
                            String h = Uri.parse(target).getHost();
                            if (h != null) {
                                httpAllowed.add(h.toLowerCase(Locale.ROOT));
                                ui.post(() -> content.loadUri(target));
                            }
                        }
                        return GeckoResult.deny();
                    }
                    call("onAir", url);
                    return GeckoResult.deny();
                case "file":
                    // Un fichier local ne s'ouvre que si l'utilisateur l'a autorisé.
                    return allowLocal && (r.isDirectNavigation || (cur != null && cur.startsWith("file:")))
                            ? GeckoResult.allow() : GeckoResult.deny();
                case "content":
                    return r.isDirectNavigation && contentAllowed(url) ? GeckoResult.allow() : GeckoResult.deny();
                case "about":
                    return "about:blank".equals(url) ? GeckoResult.allow() : GeckoResult.deny();
                case "javascript":
                case "data":
                case "blob":
                case "resource":
                case "chrome":
                case "jar":
                    return GeckoResult.deny();
                case "intent":
                    try {
                        Intent i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                        String fb = i.getStringExtra("browser_fallback_url");
                        if (i.getPackage() != null && i.getPackage().equals(getPackageName())) return GeckoResult.deny();
                        if (getPackageManager().resolveActivity(i.addCategory(Intent.CATEGORY_BROWSABLE), 0) == null) {
                            if (fb != null && (fb.startsWith("https://") || fb.startsWith("http://"))) ui.post(() -> navigate(fb));
                            return GeckoResult.deny();
                        }
                        openExternal(i, "Ce site veut ouvrir une autre appli.");
                    } catch (Exception ignored) {
                    }
                    return GeckoResult.deny();
                default:
                    openExternal(new Intent(Intent.ACTION_VIEW, u), "Ce site veut ouvrir : " + sc + ":");
                    return GeckoResult.deny();
            }
        }

        @Nullable
        @Override
        public GeckoResult<GeckoSession> onNewSession(@NonNull GeckoSession s, @NonNull String uri) {
            // Pas de fenêtres multiples : une fenêtre autorisée s'ouvre à la place de la page.
            if (allowPopups) ui.post(() -> navigate(uri));
            return null;
        }

        /** Erreurs : pages d'AIR Navig. Certificat invalide = connexion toujours refusée, sans contournement. */
        @Nullable
        @Override
        public GeckoResult<String> onLoadError(@NonNull GeckoSession s, @Nullable String uri, @NonNull WebRequestError error) {
            String url = uri == null ? "" : uri;
            Uri u = Uri.parse(url);
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
            boolean upgraded = url.startsWith("https://") && upgradedHosts.contains(host);
            String kind;
            String shown = url;
            if (error.category == WebRequestError.ERROR_CATEGORY_SECURITY) {
                kind = upgraded ? "https" : "ssl";
                if (upgraded) shown = "http" + url.substring(5);
                call("onNotice", "Certificat non valide : connexion bloquée");
            } else if (upgraded) {
                kind = "https";
                shown = "http" + url.substring(5);
            } else {
                kind = "net";
            }
            return GeckoResult.fromValue(errorPage(kind, shown));
        }
    }

    private final class Progress implements GeckoSession.ProgressDelegate {
        @Override
        public void onPageStart(@NonNull GeckoSession s, @NonNull String url) {
            loading = true;
            lastProgress = -1;
            curUrl = url;
            onNav();
        }

        @Override
        public void onPageStop(@NonNull GeckoSession s, boolean success) {
            loading = false;
            onNav();
            toJs("onProgress(100)");
        }

        @Override
        public void onProgressChange(@NonNull GeckoSession s, int p) {
            // La barre de progression n'est mise à jour que par paliers de 10 %.
            if (p >= 100 || p - lastProgress >= 10 || p < lastProgress) {
                lastProgress = p;
                toJs("onProgress(" + p + ")");
            }
        }
    }

    private final class Content implements GeckoSession.ContentDelegate {
        @Override
        public void onTitleChange(@NonNull GeckoSession s, @Nullable String title) {
            call("onTitle", title == null ? "" : title);
        }

        @Override
        public void onFullScreen(@NonNull GeckoSession s, boolean full) {
            fullscreen = full;
            View decor = getWindow().getDecorView();
            if (full) {
                decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            } else {
                decor.setSystemUiVisibility(0);
                applySavedTheme();
            }
            arrange();
        }

        @Override
        public void onExternalResponse(@NonNull GeckoSession s, @NonNull WebResponse response) {
            download(response);
        }

        @Override
        public void onCrash(@NonNull GeckoSession s) {
            restartContent();
        }

        @Override
        public void onKill(@NonNull GeckoSession s) {
            restartContent();
        }
    }

    /** Un site qui fait planter le moteur ne fait pas planter l'appli. */
    private void restartContent() {
        final String last = curUrl;
        try {
            contentView.releaseSession();
            if (content != null) content.close();
        } catch (Throwable ignored) {
        }
        createContent();
        call("onNotice", "La page a planté et a été fermée par sécurité");
        if (last != null && (last.startsWith("https://") || last.startsWith("http://"))) navigate(last);
    }

    /* ======================= Téléchargements ======================= */

    private void download(final WebResponse r) {
        if (!allowDownloads) {
            call("onDlBlocked", r.uri);
            closeQuietly(r.body);
            return;
        }
        Uri u = Uri.parse(r.uri);
        String sc = u.getScheme();
        if (!"https".equals(sc) && !"http".equals(sc)) {
            call("onNotice", "Téléchargement non pris en charge pour ce type de lien");
            closeQuietly(r.body);
            return;
        }
        String disp = header(r, "content-disposition");
        String mime = header(r, "content-type");
        if (mime != null && mime.contains(";")) mime = mime.substring(0, mime.indexOf(';')).trim();
        final String name = fileName(r.uri, disp, mime);
        final String type = mime;
        if (Build.VERSION.SDK_INT >= 29 && r.body != null) {
            // Le fichier reçu par Gecko est écrit directement dans « Téléchargements ».
            new Thread(() -> {
                try {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    if (type != null) v.put(MediaStore.Downloads.MIME_TYPE, type);
                    v.put(MediaStore.Downloads.IS_PENDING, 1);
                    Uri dst = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (dst == null) throw new IllegalStateException();
                    try (InputStream in = r.body; OutputStream out = getContentResolver().openOutputStream(dst)) {
                        if (out == null) throw new IllegalStateException();
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                    v.clear();
                    v.put(MediaStore.Downloads.IS_PENDING, 0);
                    getContentResolver().update(dst, v, null, null);
                    notice("Téléchargement terminé : " + name);
                } catch (Exception e) {
                    notice("Téléchargement impossible");
                }
            }).start();
            call("onDownload", name);
            return;
        }
        closeQuietly(r.body);
        try {
            DownloadManager.Request q = new DownloadManager.Request(u);
            if (type != null) q.setMimeType(type);
            q.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            q.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(q);
            call("onDownload", name);
        } catch (Exception e) {
            call("onNotice", "Téléchargement impossible");
        }
    }

    /** Nom du fichier téléchargé : en-tête Content-Disposition, sinon fin de l'adresse. */
    static String fileName(String url, @Nullable String disp, @Nullable String mime) {
        String name = null;
        if (disp != null) {
            java.util.regex.Matcher m = Pattern.compile("filename\\*=(?:UTF-8|utf-8)''([^;]+)").matcher(disp);
            if (m.find()) {
                try {
                    name = java.net.URLDecoder.decode(m.group(1).trim(), "UTF-8");
                } catch (Exception ignored) {
                }
            }
            if (name == null) {
                m = Pattern.compile("filename=\"?([^\";]+)\"?").matcher(disp);
                if (m.find()) name = m.group(1).trim();
            }
        }
        if (name == null || name.isEmpty()) {
            String seg = Uri.parse(url).getLastPathSegment();
            name = seg == null || seg.isEmpty() ? "telechargement" : seg;
        }
        name = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        if (name.length() > 120) name = name.substring(name.length() - 120);
        if (!name.contains(".") && mime != null) {
            String ext = mime.equals("application/pdf") ? "pdf" : mime.equals("application/zip") ? "zip"
                    : mime.startsWith("image/") ? mime.substring(6) : mime.equals("text/plain") ? "txt"
                    : mime.equals("text/html") ? "html" : mime.startsWith("video/") ? mime.substring(6)
                    : mime.startsWith("audio/") ? mime.substring(6) : null;
            if (ext != null && ext.matches("[a-z0-9]{1,8}")) name += "." + ext;
        }
        return name;
    }

    private static String header(WebResponse r, String name) {
        for (Map.Entry<String, String> e : r.headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }

    private static void closeQuietly(InputStream in) {
        try {
            if (in != null) in.close();
        } catch (Exception ignored) {
        }
    }

    /* ======================= Autorisations ======================= */

    interface Answer {
        void on(boolean allow, boolean remember);
    }

    /** Fenêtre de confirmation par site : rien n'est accordé sans l'accord explicite de l'utilisateur. */
    void askSite(String origin, String what, final Answer ans, final Runnable cancel) {
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
                .setPositiveButton("Autoriser cette fois", (d, w) -> {
                    done[0] = true;
                    ans.on(true, false);
                })
                .setNegativeButton("Refuser", (d, w) -> {
                    done[0] = true;
                    ans.on(false, !incognito());
                })
                .setOnDismissListener(d -> {
                    if (!done[0]) cancel.run();
                });
        if (!incognito()) {
            b.setNeutralButton("Toujours pour ce site", (d, w) -> {
                done[0] = true;
                ans.on(true, true);
            });
        }
        permDialog = b.show();
    }

    /* Demandes d'autorisations Android faites pour Gecko (caméra, micro, position). */
    private org.mozilla.geckoview.GeckoSession.PermissionDelegate.Callback androidCallback;

    void requestAndroid(String[] perms, org.mozilla.geckoview.GeckoSession.PermissionDelegate.Callback cb) {
        List<String> missing = new ArrayList<>();
        for (String p : perms) {
            // Position approximative seulement : la position précise n'est jamais demandée.
            if (android.Manifest.permission.ACCESS_FINE_LOCATION.equals(p)) p = android.Manifest.permission.ACCESS_COARSE_LOCATION;
            if (checkSelfPermission(p) != 0 && !missing.contains(p)) missing.add(p);
        }
        if (missing.isEmpty()) {
            cb.grant();
            return;
        }
        if (androidCallback != null) androidCallback.reject();
        androidCallback = cb;
        requestPermissions(missing.toArray(new String[0]), REQ_ANDROID_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms, @NonNull int[] res) {
        if (code == REQ_ANDROID_PERMS && androidCallback != null) {
            boolean ok = res.length > 0;
            for (int r : res) ok &= r == 0;
            if (ok) androidCallback.grant();
            else androidCallback.reject();
            androidCallback = null;
        }
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == REQ_FILE) {
            prompts.onFileResult(result, data);
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

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String u = incomingUrl(intent);
        if (u != null) call("openUrl", u);
    }

    @Override
    public void onBackPressed() {
        if (fullscreen && content != null) content.exitFullScreen();
        else toJs("onBack()");
    }

    @Override
    protected void onResume() {
        super.onResume();
        Engine.front = this;
        if (content != null) content.setActive(true);
    }

    @Override
    protected void onDestroy() {
        if (permDialog != null && permDialog.isShowing()) permDialog.dismiss();
        Engine.live.remove(this);
        if (Engine.front == this) Engine.front = Engine.live.isEmpty() ? null : Engine.live.get(Engine.live.size() - 1);
        ui.removeCallbacksAndMessages(null);
        try {
            contentView.releaseSession();
            chromeView.releaseSession();
        } catch (Throwable ignored) {
        }
        // Fermer la session privée efface ses cookies, son cache et son historique.
        if (content != null) content.close();
        if (uiSession != null) uiSession.close();
        super.onDestroy();
    }
}
