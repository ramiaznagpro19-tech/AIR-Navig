package app.airnavig;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.PictureInPictureParams;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.util.Rational;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.BasicSelectionActionDelegate;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.MediaSession;
import org.mozilla.geckoview.PictureInPictureController;
import org.mozilla.geckoview.StorageController;
import org.mozilla.geckoview.TranslationsController;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebRequestError;
import org.mozilla.geckoview.WebResponse;

/**
 * AIR Navig — navigateur de la marque AIR, créé par Rami, un peu passionné de développement.
 *
 * <p>Moteur : GeckoView, le moteur Gecko de Firefox pour Android (Mozilla). Plus aucun WebView : l'interface (chrome.html)
 * et les sites tournent dans Gecko. Chaque onglet a sa propre session Gecko (page, défilement et
 * formulaires gardés quand on change d'onglet), avec un aperçu réel capturé à l'écran.
 * L'interface parle à l'appli par la messagerie native de l'extension intégrée « air@airnavig.app ».
 *
 * <p>Sécurité conservée : pont réservé à la page d'interface, HTTPS en priorité, aucune erreur
 * de certificat contournable, autorisations demandées site par site, accès fichiers coupés par
 * défaut, liste de blocage d'origine documentée.
 */
public class MainActivity extends Activity {
    static final String CREATOR = "Rami";
    static final String BRAND = "AIR";
    private static final String TAG = "AIRNavig";

    private static final int BG = 0xFF000000;
    private static final int BG_LIGHT = 0xFFFFFFFF;
    private static final String[] INTERNAL_PAGES = {"home.html", "blocked.html", "error.html"};
    static final int POS_BOTTOM = 0;
    static final int POS_TOP = 1;
    static final int POS_RIGHT = 2;
    private static final int REQ_ANDROID_PERMS = 7;
    static final int REQ_FILE = 9;
    private static final int REQ_OPEN = 11;
    private static final int REQ_STORAGE = 12;
    private static final int MAX_ARG = 256 * 1024;
    private static final int MAX_SYNC_ARG = 16 * 1024 * 1024;
    /** Nombre d'onglets gardés « vivants » en mémoire ; les autres sont rechargés à la demande. */
    private static final int MAX_LIVE = 5;
    static final Pattern PRIVATE_HOST = Pattern.compile("^(localhost|.+\\.localhost|127\\.\\d+\\.\\d+\\.\\d+|0\\.0\\.0\\.0|10\\.\\d+\\.\\d+\\.\\d+|192\\.168\\.\\d+\\.\\d+|172\\.(1[6-9]|2\\d|3[01])\\.\\d+\\.\\d+|169\\.254\\.\\d+\\.\\d+|\\[?::1\\]?|\\[?f[cd][0-9a-f]{2}:.*|.+\\.local|.+\\.lan|.+\\.home\\.arpa|.+\\.internal)$");

    private GeckoView chromeView;
    private GeckoView contentView;
    private GeckoSession uiSession;
    /** Session de l'onglet affiché (raccourci vers cur.s). */
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

    /* État de la page affichée (copie de l'onglet courant). */
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
    /* Hôtes pour lesquels l'utilisateur a accepté le HTTP non chiffré. */
    private final Set<String> httpAllowed = Collections.synchronizedSet(new HashSet<String>());
    private final Set<String> grantedContent = Collections.synchronizedSet(new HashSet<String>());

    private boolean desktop = false;
    private boolean siteDark = false;
    private boolean siteForce = false;
    private boolean lightUi = false;
    private int pos = POS_BOTTOM;
    private int barSize = 120;
    private boolean barFull = false;

    /* Traduction : off, offer (proposer) ou auto. */
    private String translateMode = "offer";

    /* ======================= Onglets ======================= */

    /** Un onglet = une session Gecko (fermée quand l'onglet dort, rouverte avec son historique). */
    static final class Tab {
        final String id;
        GeckoSession s;
        String url = "";
        String title = "";
        boolean canBack, canFwd, loading;
        String state;
        String opener;
        long used;
        boolean playing;
        long vw = 16, vh = 9;
        TranslationsController.SessionTranslation.TranslationState tstate;
        boolean translated;
        boolean offered;
        boolean readerable;
        WebExtension.Port port;

        Tab(String id) {
            this.id = id;
        }
    }

    private final LinkedHashMap<String, Tab> tabs = new LinkedHashMap<>();
    private Tab cur;
    private final Map<String, String> thumbs = new HashMap<>();
    private final Map<String, String> savedStates = new HashMap<>();
    private int tabSeq;

    private String newId() {
        return "n" + Long.toString(System.currentTimeMillis(), 36) + (tabSeq++);
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
        if (Engine.runtime != null) Engine.runtime.configurationChanged(configuration);
        toJs("window.onSysDark&&onSysDark(" + systemDark() + ")");
    }

    Prompts prompts() {
        return prompts;
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
        translateMode = prefs.getString("translate", "offer");

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
        Passwords.privateFront = incognito();
        final String initial = incomingUrl(getIntent());
        if (!incognito()) loadTabStore();
        Engine.start(this, () -> {
            if (isFinishing() || isDestroyed()) return;
            applyLook();
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
        uiSession.setSelectionActionDelegate(new BasicSelectionActionDelegate(this));
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
        }, "air_ui");
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
            init.put("sync", Sync.status(this));
            init.put("translate", translateMode);
        } catch (Exception ignored) {
        }
        uiSession.loadUri(Engine.base + "chrome.html?i=" + Uri.encode(init.toString())
                + "#" + Uri.encode(initial == null ? "" : initial));
    }

    /** L'interface et elle seule peut piloter l'appli. */
    boolean ownsUi(@Nullable GeckoSession s) {
        return s != null && s == uiSession;
    }

    /** L'interface de cette activité n'est pas encore reliée. */
    boolean waitingUi() {
        return uiSession != null && uiPort == null;
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
                    Log.i(TAG, "interface prête, " + jsQueue.size() + " messages en attente");
                    uiReady = true;
                    List<String> q = new ArrayList<>(jsQueue);
                    jsQueue.clear();
                    for (String js : q) sendJs(js);
                    return;
                }
                String c = m.optString("c", "");
                if (c.isEmpty() || c.length() > 32) return;
                String a = m.optString("a", "");
                int max = "syncput".equals(c) ? MAX_SYNC_ARG : MAX_ARG;
                if (a.length() > max) a = a.substring(0, max);
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

    /* ======================= Sessions des onglets ======================= */

    private GeckoSessionSettings tabSettings() {
        return new GeckoSessionSettings.Builder()
                .usePrivateMode(incognito())
                .userAgentMode(desktop ? GeckoSessionSettings.USER_AGENT_MODE_DESKTOP : GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
                .viewportMode(desktop ? GeckoSessionSettings.VIEWPORT_MODE_DESKTOP : GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
                .suspendMediaWhenInactive(true)
                .build();
    }

    /** Nouvelle session (pas encore ouverte) reliée à l'onglet {@code t}. */
    private GeckoSession newSession(Tab t) {
        GeckoSession s = new GeckoSession(tabSettings());
        s.setNavigationDelegate(new Nav(t));
        s.setProgressDelegate(new Progress(t));
        s.setContentDelegate(new Content(t));
        s.setPermissionDelegate(new Perms(this));
        s.setPromptDelegate(prompts);
        s.setSelectionActionDelegate(new BasicSelectionActionDelegate(this));
        s.setMediaSessionDelegate(new Media(t));
        s.setPictureInPictureDelegate(new Pip(t));
        s.setTranslationsSessionDelegate(new Translate(t));
        if (Engine.ext != null) s.getWebExtensionController().setMessageDelegate(Engine.ext, new PageLink(t), "air_page");
        return s;
    }

    /** Rouvre la session d'un onglet endormi (avec son historique si on l'a). Renvoie true s'il faut charger son adresse. */
    private boolean wake(Tab t) {
        if (t.s != null) return false;
        t.s = newSession(t);
        t.s.open(Engine.runtime);
        String st = t.state != null ? t.state : savedStates.get(t.id);
        if (st != null) {
            try {
                t.s.restoreState(GeckoSession.SessionState.fromString(st));
                return false;
            } catch (Throwable e) {
                Log.w(TAG, "État d'onglet illisible", e);
            }
        }
        return true;
    }

    /** Endort les onglets les moins récents au-delà de MAX_LIVE. */
    private void trimLive() {
        int live = 0;
        for (Tab t : tabs.values()) if (t.s != null) live++;
        while (live > MAX_LIVE) {
            Tab old = null;
            for (Tab t : tabs.values()) {
                if (t.s == null || t == cur || t.playing) continue;
                if (old == null || t.used < old.used) old = t;
            }
            if (old == null) break;
            try {
                old.s.close();
            } catch (Throwable ignored) {
            }
            old.s = null;
            live--;
        }
    }

    /** Affiche l'onglet {@code id} (le crée s'il n'existe pas) ; charge {@code url} si l'onglet est neuf. */
    void switchTab(String id, String url) {
        if (Engine.runtime == null || id == null || id.isEmpty()) return;
        Tab t = tabs.get(id);
        if (t == null) {
            t = new Tab(id);
            tabs.put(id, t);
        }
        if (t == cur && t.s != null) {
            if (url != null && !url.isEmpty() && (t.url == null || t.url.isEmpty()) && t.state == null) navigate(url);
            return;
        }
        Tab prev = cur;
        if (prev != null && prev.s != null) {
            captureThumb(prev);
            prev.s.setActive(false);
            Engine.runtime.getWebExtensionController().setTabActive(prev.s, false);
        }
        boolean load = wake(t);
        cur = t;
        t.used = System.currentTimeMillis();
        try {
            contentView.releaseSession();
        } catch (Throwable ignored) {
        }
        contentView.setSession(t.s);
        content = t.s;
        t.s.setActive(true);
        Engine.runtime.getWebExtensionController().setTabActive(t.s, true);
        curUrl = t.url == null ? "" : t.url;
        canBack = t.canBack;
        canFwd = t.canFwd;
        loading = t.loading;
        if (load && url != null && !url.isEmpty()) navigate(url);
        else onNav();
        call("onTitle", t.title == null ? "" : t.title);
        pushTranslateState(t);
        trimLive();
    }

    private void closeTab(String id) {
        Tab t = tabs.remove(id);
        if (t == null) return;
        if (t == cur) {
            try {
                contentView.releaseSession();
            } catch (Throwable ignored) {
            }
            cur = null;
            content = null;
        }
        if (t.s != null) {
            try {
                t.s.close();
            } catch (Throwable ignored) {
            }
        }
        thumbs.remove(id);
        savedStates.remove(id);
        if (!incognito()) {
            //noinspection ResultOfMethodCallIgnored
            new File(thumbDir(), id + ".jpg").delete();
            saveTabStoreLater();
        }
    }

    /** Session pour un nouvel onglet ouvert par une page ou une extension (Gecko l'ouvre lui-même). */
    GeckoSession newTabSession(@Nullable String url, boolean active) {
        final Tab t = new Tab(newId());
        t.opener = cur == null ? null : cur.id;
        t.url = url == null ? "" : url;
        t.s = newSession(t);
        tabs.put(t.id, t);
        final GeckoSession s = t.s;
        final int[] tries = {0};
        final Runnable[] show = new Runnable[1];
        show[0] = () -> {
            if (!s.isOpen()) {
                if (tries[0]++ < 100) ui.postDelayed(show[0], 30);
                return;
            }
            try {
                call("onNewTab", new JSONObject().put("id", t.id).put("url", t.url).put("opener", t.opener == null ? "" : t.opener).put("active", active).toString());
            } catch (Exception ignored) {
            }
            if (active) switchTab(t.id, null);
        };
        ui.post(show[0]);
        return s;
    }

    /** Ouvre une adresse dans un nouvel onglet (géré par l'interface). */
    void openInNewTab(String url) {
        call("openUrl", url);
    }

    /* ======================= Aperçus des onglets ======================= */

    private File thumbDir() {
        File d = new File(getCacheDir(), "thumbs");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** Capture l'écran de l'onglet affiché (aperçu réel pour la liste des onglets). */
    private GeckoResult<Void> captureThumb(final Tab t) {
        final GeckoResult<Void> done = new GeckoResult<>();
        if (t == null || t != cur || t.s == null || contentView.getVisibility() != View.VISIBLE
                || contentView.getWidth() == 0 || contentView.getHeight() == 0 || fullscreen) {
            done.complete(null);
            return done;
        }
        try {
            contentView.capturePixels().accept(bmp -> {
                if (bmp != null) storeThumb(t.id, bmp);
                done.complete(null);
            }, e -> done.complete(null));
        } catch (Throwable e) {
            done.complete(null);
        }
        return done;
    }

    private void storeThumb(final String id, Bitmap bmp) {
        try {
            int w = 360;
            int h = Math.max(1, Math.round(bmp.getHeight() * (w / (float) bmp.getWidth())));
            Bitmap small = Bitmap.createScaledBitmap(bmp, w, h, true);
            int ch = Math.min(h, Math.round(w * 1.4f));
            Bitmap crop = ch < h ? Bitmap.createBitmap(small, 0, 0, w, ch) : small;
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            crop.compress(Bitmap.CompressFormat.JPEG, 62, bo);
            final byte[] jpg = bo.toByteArray();
            String data = "data:image/jpeg;base64," + Base64.encodeToString(jpg, Base64.NO_WRAP);
            thumbs.put(id, data);
            call("onThumb", id, data);
            if (!incognito()) {
                new Thread(() -> {
                    try (FileOutputStream out = new FileOutputStream(new File(thumbDir(), id + ".jpg"))) {
                        out.write(jpg);
                    } catch (Exception ignored) {
                    }
                }).start();
            }
        } catch (Throwable e) {
            Log.w(TAG, "Aperçu d'onglet", e);
        }
    }

    private final Runnable thumbLater = () -> captureThumb(cur);

    /* ======================= Mémoire des onglets ======================= */

    private File tabStoreFile() {
        return new File(getFilesDir(), "tabstates.json");
    }

    private void loadTabStore() {
        try {
            File f = tabStoreFile();
            if (f.exists()) {
                byte[] b = new byte[(int) f.length()];
                try (InputStream in = new java.io.FileInputStream(f)) {
                    int off = 0, n;
                    while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
                }
                JSONObject j = new JSONObject(new String(b, StandardCharsets.UTF_8));
                for (Iterator<String> it = j.keys(); it.hasNext(); ) {
                    String k = it.next();
                    savedStates.put(k, j.getString(k));
                }
            }
            File[] list = thumbDir().listFiles();
            if (list != null) for (File t : list) {
                String n = t.getName();
                if (!n.endsWith(".jpg") || t.length() > 400_000) continue;
                byte[] b = new byte[(int) t.length()];
                try (InputStream in = new java.io.FileInputStream(t)) {
                    int off = 0, r;
                    while (off < b.length && (r = in.read(b, off, b.length - off)) > 0) off += r;
                }
                thumbs.put(n.substring(0, n.length() - 4), "data:image/jpeg;base64," + Base64.encodeToString(b, Base64.NO_WRAP));
            }
        } catch (Exception e) {
            Log.w(TAG, "Onglets enregistrés illisibles", e);
        }
    }

    private final Runnable saveTabStore = () -> {
        if (incognito()) return;
        final JSONObject j = new JSONObject();
        try {
            for (Map.Entry<String, String> e : savedStates.entrySet()) j.put(e.getKey(), e.getValue());
            for (Tab t : tabs.values()) if (t.state != null) j.put(t.id, t.state);
        } catch (Exception ignored) {
        }
        new Thread(() -> {
            try {
                File tmp = new File(getFilesDir(), "tabstates.json.tmp");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(j.toString().getBytes(StandardCharsets.UTF_8));
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(tabStoreFile());
            } catch (Exception ignored) {
            }
        }).start();
    };

    private void saveTabStoreLater() {
        ui.removeCallbacks(saveTabStore);
        ui.postDelayed(saveTabStore, 1500);
    }

    /** L'interface donne la liste des onglets existants : on oublie les autres. */
    private void keepTabs(JSONArray ids) {
        Set<String> keep = new HashSet<>();
        for (int i = 0; i < ids.length(); i++) keep.add(ids.optString(i));
        for (String k : new ArrayList<>(savedStates.keySet())) if (!keep.contains(k)) savedStates.remove(k);
        for (String k : new ArrayList<>(thumbs.keySet())) {
            if (!keep.contains(k)) {
                thumbs.remove(k);
                //noinspection ResultOfMethodCallIgnored
                new File(thumbDir(), k + ".jpg").delete();
            }
        }
        for (String k : new ArrayList<>(tabs.keySet())) if (!keep.contains(k) && tabs.get(k) != cur) closeTab(k);
        saveTabStoreLater();
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
            Iterator<String> it = j.keys();
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
        if (barFull && !fullscreen) {
            bp = new LinearLayout.LayoutParams(-1, -1);
            contentView.setVisibility(View.GONE);
        } else {
            bp = side ? new LinearLayout.LayoutParams(px(barSize), -1) : new LinearLayout.LayoutParams(-1, px(barSize));
            contentView.setVisibility(View.VISIBLE);
        }
        boolean pip = Build.VERSION.SDK_INT >= 26 && isInPictureInPictureMode();
        chromeView.setVisibility(fullscreen || pip ? View.GONE : View.VISIBLE);
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
            if (barFull) return;
            barFull = true;
            // Aperçu de la page juste avant de la cacher (liste des onglets, menu…).
            final boolean[] hidden = {false};
            final Runnable hide = () -> {
                if (hidden[0] || !barFull) return;
                hidden[0] = true;
                contentView.setVisibility(View.GONE);
                ViewGroup.LayoutParams lp = chromeView.getLayoutParams();
                lp.width = -1;
                lp.height = -1;
                chromeView.setLayoutParams(lp);
            };
            captureThumb(cur).accept(v -> hide.run(), e -> hide.run());
            ui.postDelayed(hide, 250);
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
            // Ancien champ « webview » : c'est désormais le moteur GeckoView.
            String gecko = org.mozilla.geckoview.BuildConfig.MOZ_APP_VERSION;
            j.put("webview", "GeckoView " + gecko + " (Mozilla)");
            j.put("engine", "GeckoView");
            j.put("gecko", gecko);
            j.put("passwords", Passwords.get(this).count());
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
                case "tab":
                    tabCmd(new JSONObject(a));
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
                case "perms": {
                    boolean wasLocal = allowLocal;
                    loadPermissions(a);
                    savePref("perms2", a);
                    if (allowLocal && !wasLocal) requestFileAccess();
                    break;
                }
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
                    if (on != desktop) {
                        desktop = on;
                        for (Tab t : tabs.values()) {
                            if (t.s == null) continue;
                            t.s.getSettings().setUserAgentMode(on ? GeckoSessionSettings.USER_AGENT_MODE_DESKTOP : GeckoSessionSettings.USER_AGENT_MODE_MOBILE);
                            t.s.getSettings().setViewportMode(on ? GeckoSessionSettings.VIEWPORT_MODE_DESKTOP : GeckoSessionSettings.VIEWPORT_MODE_MOBILE);
                        }
                        if (content != null) content.reload();
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
                    for (Tab t : tabs.values()) if (t.s != null) t.s.purgeHistory();
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
                /* ----- Nouveautés ----- */
                case "passwords":
                    startActivity(new Intent(this, PasswordsActivity.class));
                    break;
                case "sync":
                    if (incognito()) {
                        notice("La synchro n'est pas disponible en navigation privée");
                        break;
                    }
                    if ("now".equals(a)) Sync.run(this, true);
                    else if ("status".equals(a)) call("onSyncStatus", Sync.status(this).toString());
                    else Sync.setup(this);
                    break;
                case "syncput":
                    Sync.put(this, a);
                    break;
                case "addons":
                    Addons.cmd(this, a.startsWith("{") ? new JSONObject(a) : new JSONObject().put("op", a));
                    break;
                case "reader":
                    toggleReader();
                    break;
                case "pip":
                    requestPip(true);
                    break;
                case "translate":
                    translateCmd(a);
                    break;
                case "find":
                    if (content != null && !a.isEmpty()) content.getFinder().find(a, 0);
                    break;
                default:
                    // Commandes inconnues ou retirées : ignorées.
                    break;
            }
        } catch (Exception e) {
            Log.w(TAG, "Commande " + c, e);
        }
    }

    /** Commandes d'onglets : {op: switch|close|closeall|keep|thumbs, id, url, ids}. */
    private void tabCmd(JSONObject m) {
        switch (m.optString("op")) {
            case "switch":
                switchTab(m.optString("id"), m.optString("url", ""));
                break;
            case "close":
                closeTab(m.optString("id"));
                break;
            case "keep": {
                JSONArray ids = m.optJSONArray("ids");
                if (ids != null) keepTabs(ids);
                break;
            }
            case "thumbs":
                captureThumb(cur).accept(v -> {
                    for (Map.Entry<String, String> e : thumbs.entrySet()) call("onThumb", e.getKey(), e.getValue());
                }, e -> { });
                break;
            default:
                break;
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

    /** Page d'une autre extension (options, fenêtre…), jamais celle de l'interface d'AIR Navig. */
    private boolean isOtherExtension(String u) {
        return u != null && u.startsWith("moz-extension://") && (Engine.base == null || !u.startsWith(Engine.base));
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

    static boolean isPrivateHost(@Nullable String h) {
        return h != null && PRIVATE_HOST.matcher(h.toLowerCase(Locale.ROOT)).matches();
    }

    /** Toute navigation demandée par l'interface passe par ici : liste blanche de schémas. HTTPS en priorité (géré par Gecko). */
    void navigate(String url) {
        if (url == null) return;
        // Pas encore d'onglet affiché : l'interface en choisit un juste après (switchTab).
        if (content == null) return;
        url = url.trim();
        if (url.isEmpty()) return;
        Uri u = Uri.parse(url);
        String s = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        switch (s) {
            case "https":
            case "http":
                content.loadUri(url);
                return;
            case "moz-extension":
                if (isInternal(url) || isOtherExtension(url)) content.loadUri(url);
                return;
            case "file":
                if (allowLocal) content.loadUri(url);
                else askLocalFiles(url);
                return;
            case "content":
                if (contentAllowed(url)) content.loadUri(url);
                else notice("Fichier non autorisé : ouvre-le avec « Fichier HTML »");
                return;
            case "about":
                if ("about:blank".equals(url) || url.startsWith("about:reader?")) content.loadUri(url);
                return;
            default:
                // javascript:, data:, intent:, blob:… refusés depuis la barre d'adresse.
        }
    }

    private void askLocalFiles(final String url) {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle("Ouvrir les fichiers du téléphone ?")
                .setMessage("Les fichiers locaux (file://) sont désactivés par sécurité. Les activer permet d'ouvrir tes pages HTML, PDF et dossiers (file:///sdcard/…). Tu pourras les couper dans Autorisations.")
                .setPositiveButton("Activer", (d, w) -> {
                    toJs("window.setPerm&&setPerm('local',true)");
                    allowLocal = true;
                    requestFileAccess();
                    if (content != null) content.loadUri(url);
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    /** Accès aux fichiers du stockage partagé pour file:// (Android 11+ : « accès à tous les fichiers »). */
    private void requestFileAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (Environment.isExternalStorageManager() || isFinishing()) return;
            new AlertDialog.Builder(this)
                    .setTitle("Accès aux fichiers")
                    .setMessage("Pour ouvrir les fichiers de ton téléphone (file:///sdcard/…), Android demande l'autorisation « Accès à tous les fichiers ». Les fichiers que tu ouvres avec « Fichier HTML » marchent sans elle.")
                    .setPositiveButton("Ouvrir les réglages", (d, w) -> {
                        try {
                            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())));
                        } catch (Exception e) {
                            try {
                                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                            } catch (Exception ignored) {
                            }
                        }
                    })
                    .setNegativeButton("Plus tard", null)
                    .show();
        } else if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != 0) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_STORAGE);
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
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/html", "application/xhtml+xml", "text/plain", "image/*", "application/pdf", "image/svg+xml"});
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

    void call(String fn, String a1, String a2) {
        toJs("window." + fn + "&&" + fn + "(" + JSONObject.quote(a1 == null ? "" : a1) + "," + JSONObject.quote(a2 == null ? "" : a2) + ")");
    }

    private void onNav() {
        toJs("onNav(" + JSONObject.quote(curUrl == null ? "" : curUrl) + "," + canBack + "," + canFwd + "," + loading + ")");
    }

    /** Traqueurs bloqués par background.js (par lots). */
    void onBlocked(JSONArray list) {
        toJs("onBlockedBatch(" + list.toString() + ")");
    }

    String errorPage(String kind, String url) {
        String t = lightUi ? "light" : "dark";
        return Engine.base + "error.html#k=" + kind + "&t=" + t + "&u=" + Uri.encode(url == null ? "" : url);
    }

    /* ======================= Délégués Gecko : sites ======================= */

    private final class Nav implements GeckoSession.NavigationDelegate {
        final Tab t;

        Nav(Tab t) {
            this.t = t;
        }

        @Override
        public void onLocationChange(@NonNull GeckoSession s, @Nullable String url,
                                     @NonNull List<GeckoSession.PermissionDelegate.ContentPermission> perms,
                                     @NonNull Boolean hasUserGesture) {
            String prev = t.url == null ? "" : t.url;
            t.url = url == null ? "" : url;
            if (!stripHash(prev).equals(stripHash(t.url))) {
                t.translated = false;
                t.offered = false;
            }
            if (t == cur) {
                curUrl = t.url;
                onNav();
            } else {
                tabInfo(t);
            }
        }

        @Override
        public void onCanGoBack(@NonNull GeckoSession s, boolean b) {
            t.canBack = b;
            if (t == cur) canBack = b;
        }

        @Override
        public void onCanGoForward(@NonNull GeckoSession s, boolean f) {
            t.canFwd = f;
            if (t == cur) canFwd = f;
        }

        /** Navigations décidées par les pages elles-mêmes (ou par l'appli). */
        @Nullable
        @Override
        public GeckoResult<AllowOrDeny> onLoadRequest(@NonNull GeckoSession s, @NonNull LoadRequest r) {
            final String url = r.uri;
            Uri u = Uri.parse(url);
            String sc = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            String from = t.url;
            boolean fromInternal = isInternal(from);
            switch (sc) {
                case "https":
                case "http":
                    // HTTPS en priorité : Gecko essaie d'abord https:// et revient seul en http:// si besoin.
                    return GeckoResult.allow();
                case "moz-extension":
                    if (isOtherExtension(url)) return GeckoResult.allow();
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
                                ui.post(() -> s.loadUri(target));
                            }
                        }
                        return GeckoResult.deny();
                    }
                    if (t == cur) call("onAir", url);
                    return GeckoResult.deny();
                case "file":
                    // Un fichier local ne s'ouvre que si l'utilisateur l'a autorisé, et jamais depuis un site web.
                    return allowLocal && (r.isDirectNavigation || (from != null && from.startsWith("file:")))
                            ? GeckoResult.allow() : GeckoResult.deny();
                case "content":
                    return r.isDirectNavigation && contentAllowed(url) ? GeckoResult.allow() : GeckoResult.deny();
                case "about":
                    return "about:blank".equals(url) || url.startsWith("about:reader?") ? GeckoResult.allow() : GeckoResult.deny();
                case "blob":
                    // Fichiers générés par la page elle-même (PDF, aperçus…).
                    return GeckoResult.allow();
                case "javascript":
                case "data":
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
                            if (fb != null && (fb.startsWith("https://") || fb.startsWith("http://"))) ui.post(() -> s.loadUri(fb));
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

        /** Lien target=_blank ou window.open() autorisé : nouvel onglet (window.opener garde le lien, utile aux connexions « Se connecter avec… »). */
        @Nullable
        @Override
        public GeckoResult<GeckoSession> onNewSession(@NonNull GeckoSession s, @NonNull String uri) {
            return GeckoResult.fromValue(newTabSession(uri, true));
        }

        /** Erreurs : pages d'AIR Navig. Certificat invalide = connexion toujours refusée, sans contournement. */
        @Nullable
        @Override
        public GeckoResult<String> onLoadError(@NonNull GeckoSession s, @Nullable String uri, @NonNull WebRequestError error) {
            String url = uri == null ? "" : uri;
            String kind;
            if (error.category == WebRequestError.ERROR_CATEGORY_SECURITY) {
                kind = "ssl";
                if (t == cur) call("onNotice", "Certificat non valide : connexion bloquée");
            } else {
                kind = "net";
            }
            return GeckoResult.fromValue(errorPage(kind, url));
        }
    }

    private static String stripHash(String u) {
        int i = u.indexOf('#');
        return i < 0 ? u : u.substring(0, i);
    }

    private void tabInfo(Tab t) {
        try {
            call("onTabInfo", new JSONObject().put("id", t.id).put("url", t.url).put("title", t.title).toString());
        } catch (Exception ignored) {
        }
    }

    private final class Progress implements GeckoSession.ProgressDelegate {
        final Tab t;

        Progress(Tab t) {
            this.t = t;
        }

        @Override
        public void onPageStart(@NonNull GeckoSession s, @NonNull String url) {
            t.loading = true;
            t.url = url;
            if (t != cur) return;
            loading = true;
            lastProgress = -1;
            curUrl = url;
            onNav();
        }

        @Override
        public void onPageStop(@NonNull GeckoSession s, boolean success) {
            t.loading = false;
            if (t != cur) {
                tabInfo(t);
                return;
            }
            loading = false;
            onNav();
            toJs("onProgress(100)");
            ui.removeCallbacks(thumbLater);
            ui.postDelayed(thumbLater, 900);
        }

        @Override
        public void onProgressChange(@NonNull GeckoSession s, int p) {
            if (t != cur) return;
            // La barre de progression n'est mise à jour que par paliers de 10 %.
            if (p >= 100 || p - lastProgress >= 10 || p < lastProgress) {
                lastProgress = p;
                toJs("onProgress(" + p + ")");
            }
        }

        @Override
        public void onSessionStateChange(@NonNull GeckoSession s, @NonNull GeckoSession.SessionState state) {
            t.state = state.toString();
            if (!incognito()) saveTabStoreLater();
        }
    }

    private final class Content implements GeckoSession.ContentDelegate {
        final Tab t;

        Content(Tab t) {
            this.t = t;
        }

        @Override
        public void onTitleChange(@NonNull GeckoSession s, @Nullable String title) {
            t.title = title == null ? "" : title;
            if (t == cur) call("onTitle", t.title);
            else tabInfo(t);
        }

        @Override
        public void onFullScreen(@NonNull GeckoSession s, boolean full) {
            if (t != cur) return;
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
        public void onCloseRequest(@NonNull GeckoSession s) {
            // window.close() d'une fenêtre ouverte par une page (connexion terminée…).
            if (t.opener != null) toJs("window.onTabClosed&&onTabClosed(" + JSONObject.quote(t.id) + ")");
        }

        @Override
        public void onContextMenu(@NonNull GeckoSession s, int x, int y, @NonNull ContextElement el) {
            showContextMenu(el);
        }

        @Override
        public void onExternalResponse(@NonNull GeckoSession s, @NonNull WebResponse response) {
            download(response);
        }

        @Override
        public void onCrash(@NonNull GeckoSession s) {
            restartTab(t);
        }

        @Override
        public void onKill(@NonNull GeckoSession s) {
            restartTab(t);
        }
    }

    /** Un site qui fait planter le moteur ne fait pas planter l'appli. */
    private void restartTab(Tab t) {
        final String last = t.url;
        try {
            if (t == cur) contentView.releaseSession();
            if (t.s != null) t.s.close();
        } catch (Throwable ignored) {
        }
        t.s = null;
        t.state = null;
        if (t == cur) {
            cur = null;
            content = null;
            switchTab(t.id, last);
            call("onNotice", "La page a planté et a été rechargée");
        }
    }

    /* ======================= Appui long : menu du lien / de l'image ======================= */

    private void showContextMenu(final GeckoSession.ContentDelegate.ContextElement el) {
        if (isFinishing()) return;
        final String link = el.linkUri;
        final String src = el.srcUri;
        final boolean linkOk = link != null && (link.startsWith("http://") || link.startsWith("https://"));
        final boolean imgOk = el.type == GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE && src != null
                && (src.startsWith("http://") || src.startsWith("https://"));
        if (!linkOk && !imgOk) return;
        final List<String> labels = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();
        if (linkOk) {
            labels.add("Ouvrir dans un nouvel onglet");
            acts.add(() -> openInNewTab(link));
            labels.add("Ouvrir en arrière-plan");
            acts.add(() -> toJs("window.openUrlBg&&openUrlBg(" + JSONObject.quote(link) + ")"));
            labels.add("Ouvrir dans le bac à sable");
            acts.add(() -> runCmd("sandbox", link));
            labels.add("Copier l'adresse du lien");
            acts.add(() -> copyText(link));
            labels.add("Partager le lien");
            acts.add(() -> runCmd("share", link));
        }
        if (imgOk) {
            labels.add("Ouvrir l'image dans un nouvel onglet");
            acts.add(() -> openInNewTab(src));
            labels.add("Télécharger l'image");
            acts.add(() -> downloadUrl(src));
            labels.add("Copier l'adresse de l'image");
            acts.add(() -> copyText(src));
        }
        String title = linkOk ? link : src;
        new AlertDialog.Builder(this)
                .setTitle(title.length() > 90 ? title.substring(0, 90) + "…" : title)
                .setItems(labels.toArray(new String[0]), (d, w) -> acts.get(w).run())
                .show();
    }

    private void copyText(String s) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("lien", s));
            notice("Copié");
        }
    }

    private void downloadUrl(String url) {
        if (!allowDownloads) {
            call("onDlBlocked", url);
            return;
        }
        try {
            DownloadManager.Request q = new DownloadManager.Request(Uri.parse(url));
            String name = fileName(url, null, null);
            q.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            q.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(q);
            call("onDownload", name);
        } catch (Exception e) {
            call("onNotice", "Téléchargement impossible");
        }
    }

    /* ======================= Mode lecture ======================= */

    /** Liaison avec le script de la page (mode lecture). */
    private final class PageLink implements WebExtension.MessageDelegate {
        final Tab t;

        PageLink(Tab t) {
            this.t = t;
        }

        @Override
        public void onConnect(@NonNull WebExtension.Port port) {
            if (!port.sender.isTopLevel()) {
                port.disconnect();
                return;
            }
            t.port = port;
            t.readerable = false;
            port.setDelegate(new WebExtension.PortDelegate() {
                @Override
                public void onPortMessage(@NonNull Object message, @NonNull WebExtension.Port p) {
                    if (p != t.port || !(message instanceof JSONObject)) return;
                    JSONObject m = (JSONObject) message;
                    if (m.has("readerable")) {
                        t.readerable = m.optBoolean("readerable");
                        if (t == cur) toJs("window.onReaderable&&onReaderable(" + t.readerable + ")");
                    } else if (m.has("readerFail") && t == cur) {
                        notice("Pas d'article à mettre en mode lecture sur cette page");
                    } else if (m.has("reading") && t == cur) {
                        toJs("window.onReading&&onReading(true)");
                    }
                }

                @Override
                public void onDisconnect(@NonNull WebExtension.Port p) {
                    if (t.port == p) t.port = null;
                }
            });
        }

        @Nullable
        @Override
        public GeckoResult<Object> onMessage(@NonNull String nativeApp, @NonNull Object message,
                                             @NonNull WebExtension.MessageSender sender) {
            return null;
        }
    }

    /* ======================= Mode lecture ======================= */

    private void toggleReader() {
        if (cur == null || curUrl == null) return;
        if (!curUrl.startsWith("http://") && !curUrl.startsWith("https://") && !curUrl.startsWith("file:")) {
            notice("Le mode lecture fonctionne sur les articles des sites web");
            return;
        }
        if (cur.port == null) {
            notice("La page n'est pas encore prête");
            return;
        }
        try {
            cur.port.postMessage(new JSONObject().put("reader", 1));
        } catch (Exception e) {
            notice("Mode lecture indisponible sur cette page");
        }
    }

    /* ======================= Vidéo en incrustation (Picture-in-Picture) ======================= */

    private final class Media implements MediaSession.Delegate {
        final Tab t;

        Media(Tab t) {
            this.t = t;
        }

        @Override
        public void onPlay(@NonNull GeckoSession s, @NonNull MediaSession m) {
            t.playing = true;
        }

        @Override
        public void onPause(@NonNull GeckoSession s, @NonNull MediaSession m) {
            t.playing = false;
        }

        @Override
        public void onStop(@NonNull GeckoSession s, @NonNull MediaSession m) {
            t.playing = false;
        }

        @Override
        public void onDeactivated(@NonNull GeckoSession s, @NonNull MediaSession m) {
            t.playing = false;
        }

        @Override
        public void onFullscreen(@NonNull GeckoSession s, @NonNull MediaSession m, boolean enabled,
                                 @Nullable MediaSession.ElementMetadata meta) {
            if (meta != null && meta.width > 0 && meta.height > 0) {
                t.vw = meta.width;
                t.vh = meta.height;
            }
        }
    }

    private final class Pip implements PictureInPictureController.SessionPictureInPicture.Delegate {
        final Tab t;

        Pip(Tab t) {
            this.t = t;
        }

        @Override
        public void onEnterPictureInPicture(@NonNull GeckoSession s, @Nullable String info) {
            if (t == cur) enterPip();
        }

        @Override
        public void onExitPictureInPicture(@NonNull GeckoSession s) {
            if (t == cur && Build.VERSION.SDK_INT >= 26 && isInPictureInPictureMode()) {
                // La vidéo a été arrêtée par la page : on revient à l'appli.
                Intent i = new Intent(MainActivity.this, MainActivity.this.getClass());
                i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(i);
            }
        }
    }

    private void requestPip(final boolean manual) {
        if (content == null || cur == null) return;
        try {
            content.getSessionPictureInPicture().request().accept(v -> {
                if (Build.VERSION.SDK_INT >= 26 && !isInPictureInPictureMode()) enterPip();
            }, e -> {
                if (fullscreen && cur != null && cur.playing) enterPip();
                else if (manual) notice("Lance d'abord une vidéo pour la mettre en incrustation");
            });
        } catch (Throwable e) {
            if (fullscreen) enterPip();
        }
    }

    private void enterPip() {
        if (Build.VERSION.SDK_INT < 26 || isFinishing()) return;
        try {
            long w = cur == null ? 16 : cur.vw, h = cur == null ? 9 : cur.vh;
            float r = w / (float) h;
            if (r > 2.39f) { w = 239; h = 100; }
            else if (r < 1 / 2.39f) { w = 100; h = 239; }
            PictureInPictureParams p = new PictureInPictureParams.Builder()
                    .setAspectRatio(new Rational((int) Math.min(w, Integer.MAX_VALUE), (int) Math.min(h, Integer.MAX_VALUE)))
                    .build();
            chromeView.setVisibility(View.GONE);
            if (!enterPictureInPictureMode(p)) arrange();
        } catch (Throwable e) {
            arrange();
            Log.w(TAG, "Incrustation", e);
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean in, Configuration c) {
        super.onPictureInPictureModeChanged(in, c);
        if (!in && content != null) {
            try {
                content.getSessionPictureInPicture().exit();
            } catch (Throwable ignored) {
            }
        }
        arrange();
    }

    /** Quitter l'appli pendant une vidéo plein écran : elle continue en incrustation. */
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (cur != null && cur.playing && fullscreen) requestPip(false);
    }

    /* ======================= Traduction (sur l'appareil, moteur Firefox Translations) ======================= */

    private final class Translate implements TranslationsController.SessionTranslation.Delegate {
        final Tab t;

        Translate(Tab t) {
            this.t = t;
        }

        @Override
        public void onOfferTranslate(@NonNull GeckoSession s) {
            if (t.offered) return;
            t.offered = true;
            if (t != cur) return;
            if ("auto".equals(translateMode)) translateNow(t, false);
            else if ("offer".equals(translateMode)) call("onTranslateOffer", langName(docLang(t)));
        }

        @Override
        public void onExpectedTranslate(@NonNull GeckoSession s) {
            if (t == cur && "auto".equals(translateMode) && !t.translated) translateNow(t, false);
        }

        @Override
        public void onTranslationStateChange(@NonNull GeckoSession s,
                                             @Nullable TranslationsController.SessionTranslation.TranslationState st) {
            t.tstate = st;
            if (st != null && st.requestedTranslationPair != null) t.translated = true;
            if (t == cur) pushTranslateState(t);
            // Gecko ne propose pas toujours lui-même : on décide à partir de la langue détectée.
            if (!t.offered && !t.translated && canTranslate(t)) {
                t.offered = true;
                if (t == cur) {
                    if ("auto".equals(translateMode)) translateNow(t, false);
                    else if ("offer".equals(translateMode)) call("onTranslateOffer", langName(docLang(t)));
                }
            }
        }
    }

    @Nullable
    private static String docLang(Tab t) {
        if (t == null || t.tstate == null || t.tstate.detectedLanguages == null) return null;
        return t.tstate.detectedLanguages.docLangTag;
    }

    private static String userLang(Tab t) {
        String u = t == null || t.tstate == null || t.tstate.detectedLanguages == null ? null : t.tstate.detectedLanguages.userLangTag;
        if (u == null || u.isEmpty()) u = Locale.getDefault().getLanguage();
        return u == null || u.isEmpty() ? "fr" : u;
    }

    static String langName(@Nullable String tag) {
        if (tag == null || tag.isEmpty()) return "langue inconnue";
        String n = Locale.forLanguageTag(tag).getDisplayLanguage(Locale.FRENCH);
        return n == null || n.isEmpty() ? tag : n;
    }

    private static boolean canTranslate(Tab t) {
        String d = docLang(t);
        return t != null && t.tstate != null && t.tstate.detectedLanguages != null
                && Boolean.TRUE.equals(t.tstate.detectedLanguages.isDocLangTagSupported)
                && d != null && !d.isEmpty() && !baseLang(d).equalsIgnoreCase(baseLang(userLang(t)));
    }

    private static String baseLang(String tag) {
        int i = tag.indexOf('-');
        return i > 0 ? tag.substring(0, i) : tag;
    }

    private void pushTranslateState(Tab t) {
        try {
            JSONObject j = new JSONObject();
            String d = docLang(t);
            j.put("doc", d == null ? "" : d);
            j.put("docName", langName(d));
            j.put("user", userLang(t));
            j.put("on", t.translated);
            boolean supported = t.tstate != null && t.tstate.detectedLanguages != null
                    && Boolean.TRUE.equals(t.tstate.detectedLanguages.isDocLangTagSupported);
            j.put("can", supported && canTranslate(t));
            j.put("err", t.tstate == null || t.tstate.error == null ? "" : t.tstate.error);
            call("onTranslateState", j.toString());
        } catch (Exception ignored) {
        }
    }

    private void translateNow(final Tab t, boolean manual) {
        if (t == null || t.s == null) return;
        String from = docLang(t);
        String to = userLang(t);
        if (from == null || from.isEmpty()) {
            if (manual) notice("Langue de la page pas encore détectée : réessaie dans un instant");
            return;
        }
        if (baseLang(from).equalsIgnoreCase(baseLang(to))) {
            if (manual) notice("La page est déjà en " + langName(to));
            return;
        }
        TranslationsController.SessionTranslation st = t.s.getSessionTranslation();
        if (st == null) {
            notice("Traduction non disponible sur ce téléphone");
            return;
        }
        notice("Traduction de " + langName(from) + " vers " + langName(to) + "… (la 1re fois, le modèle de langue est téléchargé)");
        st.translate(from, to, new TranslationsController.SessionTranslation.TranslationOptions.Builder().downloadModel(true).build())
                .accept(v -> {
                    t.translated = true;
                    if (t == cur) pushTranslateState(t);
                }, e -> notice("Traduction impossible" + (e instanceof TranslationsController.TranslationsException
                        && ((TranslationsController.TranslationsException) e).code == TranslationsController.TranslationsException.ERROR_LANGUAGE_NOT_SUPPORTED
                        ? " : langue non prise en charge" : "")));
    }

    private void translateCmd(String a) {
        if (a.startsWith("mode:")) {
            translateMode = a.substring(5);
            savePref("translate", translateMode);
            return;
        }
        if (cur == null || cur.s == null) return;
        TranslationsController.SessionTranslation st = cur.s.getSessionTranslation();
        switch (a) {
            case "do":
                translateNow(cur, true);
                break;
            case "restore":
                if (st != null) st.restoreOriginalPage().accept(v -> {
                    if (cur != null) {
                        cur.translated = false;
                        pushTranslateState(cur);
                    }
                }, e -> notice("Impossible de revenir à la page d'origine"));
                break;
            case "toggle":
                if (cur.translated) translateCmd("restore");
                else translateNow(cur, true);
                break;
            case "never":
                if (st != null) st.setNeverTranslateSiteSetting(true);
                notice("Ce site ne sera plus traduit");
                break;
            default:
                break;
        }
    }

    /* ======================= Téléchargements ======================= */

    private void download(final WebResponse r) {
        String disp = header(r, "content-disposition");
        String mime = header(r, "content-type");
        if (mime != null && mime.contains(";")) mime = mime.substring(0, mime.indexOf(';')).trim();
        // Extension Firefox : installation (avec confirmation des permissions) au lieu d'un téléchargement.
        if ("application/x-xpinstall".equals(mime) || (r.uri != null && r.uri.toLowerCase(Locale.ROOT).matches("^https://[^?#]+\\.xpi([?#].*)?$"))) {
            closeQuietly(r.body);
            if (incognito()) {
                notice("Installe les extensions depuis la navigation normale");
                return;
            }
            Addons.install(r.uri);
            return;
        }
        if (!allowDownloads) {
            call("onDlBlocked", r.uri);
            closeQuietly(r.body);
            return;
        }
        Uri u = Uri.parse(r.uri);
        String sc = u.getScheme();
        boolean web = "https".equals(sc) || "http".equals(sc);
        if (!web && !"blob".equals(sc) && !"data".equals(sc)) {
            call("onNotice", "Téléchargement non pris en charge pour ce type de lien");
            closeQuietly(r.body);
            return;
        }
        final String name = fileName(web ? r.uri : "telechargement", disp, mime);
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
        if (!web) {
            call("onNotice", "Téléchargement impossible sur cette version d'Android");
            return;
        }
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
    private GeckoSession.PermissionDelegate.Callback androidCallback;

    void requestAndroid(String[] perms, GeckoSession.PermissionDelegate.Callback cb) {
        List<String> missing = new ArrayList<>();
        for (String p : perms) {
            // Position approximative seulement : la position précise n'est jamais demandée.
            if (Manifest.permission.ACCESS_FINE_LOCATION.equals(p)) p = Manifest.permission.ACCESS_COARSE_LOCATION;
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
        } else if (code == Sync.REQ_CREATE || code == Sync.REQ_OPEN) {
            if (result == RESULT_OK) Sync.onPicked(this, code, data);
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
        if (testHook(intent)) return;
        String u = incomingUrl(intent);
        if (u != null) call("openUrl", u);
    }

    /** Essais automatiques (versions de test uniquement) : adb shell am start … --es airtest "js:…" ou "cmd:arg". */
    private boolean testHook(Intent intent) {
        if (intent == null || (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return false;
        String t = intent.getStringExtra("airtest");
        if (t == null) return false;
        if (t.startsWith("js:")) toJs(t.substring(3));
        else if (t.startsWith("show:")) toJs("show(" + JSONObject.quote(t.substring(5)) + ")");
        else if (t.equals("collapse")) toJs("collapse()");
        else if (t.startsWith("type:")) toJs("go(" + JSONObject.quote(t.substring(5)) + ")");
        else {
            int i = t.indexOf(':');
            runCmd(i < 0 ? t : t.substring(0, i), i < 0 ? "" : t.substring(i + 1));
        }
        return true;
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
        Passwords.privateFront = incognito();
        if (content != null) content.setActive(true);
        if (!incognito() && Sync.configured(this) && System.currentTimeMillis() - Sync.last(this) > 10 * 60_000L) {
            ui.postDelayed(() -> {
                if (uiReady) Sync.run(this, false);
            }, 4000);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!incognito()) {
            ui.removeCallbacks(saveTabStore);
            saveTabStore.run();
            if (Sync.configured(this) && uiReady && System.currentTimeMillis() - Sync.last(this) > 2 * 60_000L) Sync.run(this, false);
        }
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
        // Fermer les sessions privées efface leurs cookies, leur cache et leur historique.
        for (Tab t : tabs.values()) {
            if (t.s != null) {
                try {
                    t.s.close();
                } catch (Throwable ignored) {
                }
            }
        }
        tabs.clear();
        if (uiSession != null) uiSession.close();
        super.onDestroy();
    }
}
