package app.airnavig;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebRequestError;
import org.mozilla.geckoview.WebResponse;

/**
 * Test d'un site « pour de faux » avant de l'ouvrir.
 *
 * <p>Le site est chargé en cachette dans une session Gecko privée et isolée (conteneur
 * « air-sonde » : aucun cookie, aucune donnée partagée avec tes onglets), sans aucune
 * autorisation : rien ne peut sortir de la sonde. AIR Navig regarde ce que le site
 * ESSAIE de faire pendant quelques secondes — redirections, ouverture d'autres applis,
 * fenêtres, téléchargements automatiques, plein écran, alertes en boucle, demandes
 * d'accès, formulaires de mot de passe ou de carte, messages d'arnaque — puis la sonde
 * est détruite et ses données effacées. L'interface décide ensuite (probeVerdict).
 */
final class Probe {
    private static final String TAG = "AIRNavig";
    static final String CONTEXT = "air-sonde";
    private static final long MAX_MS = 9000;
    private static final long AFTER_LOAD_MS = 2500;

    interface Done {
        void on(JSONObject facts);
    }

    private final MainActivity act;
    private final String url;
    private final Done done;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private GeckoSession s;
    private boolean finished;

    /* Ce que le site a tenté. */
    private String finalUrl;
    private int redirects;
    private final List<String> hosts = new ArrayList<>();
    private final List<String> apps = new ArrayList<>();
    private int popups;
    private final List<String> downloads = new ArrayList<>();
    private boolean apk;
    private boolean fullscreen;
    private final List<String> perms = new ArrayList<>();
    private int alerts;
    private boolean beforeUnload;
    private boolean loadError;
    private boolean loaded;
    private JSONObject scan;

    private Probe(MainActivity act, String url, Done done) {
        this.act = act;
        this.url = url;
        this.done = done;
        this.finalUrl = url;
        addHost(url);
    }

    static void run(MainActivity act, String url, Done done) {
        new Probe(act, url, done).start();
    }

    private void addHost(String u) {
        String h = Uri.parse(u).getHost();
        if (h != null) {
            h = h.toLowerCase(Locale.ROOT);
            if (hosts.isEmpty() || !hosts.get(hosts.size() - 1).equals(h)) hosts.add(h);
        }
    }

    private void start() {
        if (Engine.runtime == null) {
            finish();
            return;
        }
        Engine.runtime.getStorageController().clearDataForSessionContext(CONTEXT);
        s = new GeckoSession(new GeckoSessionSettings.Builder()
                .usePrivateMode(true)
                .contextId(CONTEXT)
                .suspendMediaWhenInactive(true)
                .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
                .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
                .build());

        s.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override
            public void onLocationChange(@NonNull GeckoSession g, @Nullable String u,
                                         @NonNull List<GeckoSession.PermissionDelegate.ContentPermission> p,
                                         @NonNull Boolean gesture) {
                if (u != null && (u.startsWith("http://") || u.startsWith("https://"))) {
                    finalUrl = u;
                    addHost(u);
                }
            }

            @Nullable
            @Override
            public GeckoResult<AllowOrDeny> onLoadRequest(@NonNull GeckoSession g, @NonNull LoadRequest r) {
                String sc = Uri.parse(r.uri).getScheme();
                sc = sc == null ? "" : sc.toLowerCase(Locale.ROOT);
                if ("http".equals(sc) || "https".equals(sc)) {
                    if (r.isRedirect) redirects++;
                    return GeckoResult.allow();
                }
                if ("about".equals(sc) && "about:blank".equals(r.uri)) return GeckoResult.allow();
                if ("blob".equals(sc)) return GeckoResult.allow();
                // Toute autre adresse (intent:, market:, tel:, sms:, data:…) : tentative notée, jamais suivie.
                if (!apps.contains(sc) && apps.size() < 6) apps.add(sc);
                return GeckoResult.deny();
            }

            @Nullable
            @Override
            public GeckoResult<GeckoSession> onNewSession(@NonNull GeckoSession g, @NonNull String uri) {
                popups++;
                return null;
            }

            @Nullable
            @Override
            public GeckoResult<String> onLoadError(@NonNull GeckoSession g, @Nullable String uri, @NonNull WebRequestError e) {
                if (uri == null || uri.equals(url) || hosts.size() <= 1) loadError = true;
                finishSoon(300);
                return null;
            }
        });

        s.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override
            public void onPageStop(@NonNull GeckoSession g, boolean success) {
                loaded = true;
                finishSoon(AFTER_LOAD_MS);
            }
        });

        s.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override
            public void onExternalResponse(@NonNull GeckoSession g, @NonNull WebResponse r) {
                try {
                    if (r.body != null) r.body.close();
                } catch (Exception ignored) {
                }
                String type = null;
                for (java.util.Map.Entry<String, String> e : r.headers.entrySet()) {
                    if (e.getKey() != null && e.getKey().equalsIgnoreCase("content-type")) type = e.getValue();
                }
                String name = MainActivity.fileName(r.uri == null ? "fichier" : r.uri, null, type);
                String low = name.toLowerCase(Locale.ROOT);
                if (low.endsWith(".apk") || low.endsWith(".apks") || low.endsWith(".xapk") || low.endsWith(".exe")
                        || low.endsWith(".msi") || low.endsWith(".bat") || low.endsWith(".scr")
                        || (type != null && type.contains("android.package-archive"))) apk = true;
                if (downloads.size() < 5) downloads.add(name);
            }

            @Override
            public void onFullScreen(@NonNull GeckoSession g, boolean full) {
                if (full) fullscreen = true;
            }

            @Override
            public void onCrash(@NonNull GeckoSession g) {
                finish();
            }

            @Override
            public void onKill(@NonNull GeckoSession g) {
                finish();
            }
        });

        s.setPermissionDelegate(new GeckoSession.PermissionDelegate() {
            @Override
            public void onAndroidPermissionsRequest(@NonNull GeckoSession g, @Nullable String[] p, @NonNull Callback cb) {
                cb.reject();
            }

            @Nullable
            @Override
            public GeckoResult<Integer> onContentPermissionRequest(@NonNull GeckoSession g, @NonNull ContentPermission p) {
                String what;
                switch (p.permission) {
                    case PERMISSION_DESKTOP_NOTIFICATION: what = "notifications"; break;
                    case PERMISSION_GEOLOCATION: what = "position"; break;
                    case PERMISSION_LOCAL_NETWORK_ACCESS:
                    case PERMISSION_LOCAL_DEVICE_ACCESS: what = "réseau local"; break;
                    case PERMISSION_XR: what = "réalité virtuelle"; break;
                    default: what = null;
                }
                if (what != null && !perms.contains(what)) perms.add(what);
                return GeckoResult.fromValue(ContentPermission.VALUE_DENY);
            }

            @Override
            public void onMediaPermissionRequest(@NonNull GeckoSession g, @NonNull String uri, @Nullable MediaSource[] video,
                                                 @Nullable MediaSource[] audio, @NonNull MediaCallback cb) {
                if (video != null && !perms.contains("caméra")) perms.add("caméra");
                if (audio != null && !perms.contains("micro")) perms.add("micro");
                cb.reject();
            }
        });

        s.setPromptDelegate(new GeckoSession.PromptDelegate() {
            @Nullable
            @Override
            public GeckoResult<PromptResponse> onAlertPrompt(@NonNull GeckoSession g, @NonNull AlertPrompt p) {
                alerts++;
                return GeckoResult.fromValue(p.dismiss());
            }

            @Nullable
            @Override
            public GeckoResult<PromptResponse> onButtonPrompt(@NonNull GeckoSession g, @NonNull ButtonPrompt p) {
                alerts++;
                return GeckoResult.fromValue(p.dismiss());
            }

            @Nullable
            @Override
            public GeckoResult<PromptResponse> onTextPrompt(@NonNull GeckoSession g, @NonNull TextPrompt p) {
                alerts++;
                return GeckoResult.fromValue(p.dismiss());
            }

            @Nullable
            @Override
            public GeckoResult<PromptResponse> onBeforeUnloadPrompt(@NonNull GeckoSession g, @NonNull BeforeUnloadPrompt p) {
                beforeUnload = true;
                return GeckoResult.fromValue(p.confirm(AllowOrDeny.ALLOW));
            }

            @Nullable
            @Override
            public GeckoResult<PromptResponse> onPopupPrompt(@NonNull GeckoSession g, @NonNull PopupPrompt p) {
                popups++;
                return GeckoResult.fromValue(p.confirm(AllowOrDeny.DENY));
            }

            @Nullable
            @Override
            public GeckoResult<PromptResponse> onAuthPrompt(@NonNull GeckoSession g, @NonNull AuthPrompt p) {
                return GeckoResult.fromValue(p.dismiss());
            }

            @Nullable
            @Override
            public GeckoResult<PromptResponse> onFilePrompt(@NonNull GeckoSession g, @NonNull FilePrompt p) {
                return GeckoResult.fromValue(p.dismiss());
            }
        });

        // Le script de page (content.js) envoie son analyse : formulaires, titre, messages d'arnaque.
        if (Engine.ext != null) {
            s.getWebExtensionController().setMessageDelegate(Engine.ext, new WebExtension.MessageDelegate() {
                @Override
                public void onConnect(@NonNull WebExtension.Port port) {
                    port.setDelegate(new WebExtension.PortDelegate() {
                        @Override
                        public void onPortMessage(@NonNull Object m, @NonNull WebExtension.Port p) {
                            if (m instanceof JSONObject && ((JSONObject) m).has("scan")) {
                                scan = ((JSONObject) m).optJSONObject("scan");
                            }
                        }

                        @Override
                        public void onDisconnect(@NonNull WebExtension.Port p) {
                        }
                    });
                    try {
                        port.postMessage(new JSONObject().put("probe", 1));
                    } catch (Exception ignored) {
                    }
                }

                @Nullable
                @Override
                public GeckoResult<Object> onMessage(@NonNull String app, @NonNull Object message,
                                                     @NonNull WebExtension.MessageSender sender) {
                    return null;
                }
            }, "air_page");
        }

        s.open(Engine.runtime);
        s.setActive(true);
        s.loadUri(url);
        ui.postDelayed(this::finish, MAX_MS);
    }

    private final Runnable finishTask = this::finish;

    private void finishSoon(long ms) {
        ui.removeCallbacks(finishTask);
        ui.postDelayed(finishTask, ms);
    }

    private void finish() {
        if (finished) return;
        finished = true;
        ui.removeCallbacksAndMessages(null);
        JSONObject f = new JSONObject();
        try {
            f.put("url", url);
            f.put("final", finalUrl);
            f.put("redirects", redirects);
            f.put("hosts", new JSONArray(hosts));
            f.put("apps", new JSONArray(apps));
            f.put("popups", popups);
            f.put("downloads", new JSONArray(downloads));
            f.put("apk", apk);
            f.put("fullscreen", fullscreen);
            f.put("perms", new JSONArray(perms));
            f.put("alerts", alerts);
            f.put("beforeUnload", beforeUnload);
            f.put("error", loadError);
            f.put("loaded", loaded);
            if (scan != null) f.put("scan", scan);
        } catch (Exception ignored) {
        }
        if (s != null) {
            try {
                s.stop();
                s.close();
            } catch (Throwable ignored) {
            }
            s = null;
        }
        if (Engine.runtime != null) Engine.runtime.getStorageController().clearDataForSessionContext(CONTEXT);
        Log.i(TAG, "sonde : " + f);
        done.on(f);
    }
}
