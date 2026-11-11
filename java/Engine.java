package app.airnavig;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.WebExtension;

/**
 * Moteur GeckoView (Mozilla) partagé par toute l'appli : Gecko ne peut exister qu'une fois par processus.
 *
 * <p>Il installe l'extension intégrée « air@airnavig.app » qui contient l'interface
 * (chrome.html), le bloqueur de traqueurs (background.js) et le mode sombre des sites (content.js).
 */
final class Engine {
    static final String EXT_ID = "air@airnavig.app";
    static final String EXT_URI = "resource://android/assets/air/";
    private static final String TAG = "AIRNavig";

    static GeckoRuntime runtime;
    static WebExtension ext;
    /** Adresse de base des pages internes : moz-extension://…/ */
    static String base;

    private static boolean starting;
    private static final List<Runnable> waiting = new ArrayList<>();
    private static final Handler ui = new Handler(Looper.getMainLooper());

    /* Liaison avec background.js (bloqueur). */
    private static WebExtension.Port bgPort;
    private static String hostsPayload;
    private static String rulesPayload = "";
    private static boolean blockOn = true;
    private static boolean lookDark;
    private static boolean lookForce;

    /** Activités ouvertes (navigation normale, privée) : reçoivent les traqueurs bloqués. */
    static final List<MainActivity> live = new ArrayList<>();
    static MainActivity front;

    private Engine() {}

    /** Démarre le moteur si besoin puis appelle {@code ready} (sur le thread principal). */
    static void start(@NonNull Context context, @NonNull Runnable ready) {
        if (base != null) {
            ready.run();
            return;
        }
        waiting.add(ready);
        if (starting) return;
        starting = true;

        boolean adapt = context.getSharedPreferences("air", 0).getBoolean("adapt", true);
        // Protections Gecko par défaut : anti-pistage standard, cookies tiers isolés par site,
        // navigation sécurisée. Aucune télémétrie ni rapport de plantage : l'appli n'active
        // ni Glean ni le gestionnaire de plantages de GeckoView.
        GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
                .remoteDebuggingEnabled(false)
                .consoleOutput(false)
                .aboutConfigEnabled(false)
                .fontInflation(adapt)
                .build();
        runtime = GeckoRuntime.create(context.getApplicationContext(), settings);

        runtime.getWebExtensionController()
                .ensureBuiltIn(EXT_URI, EXT_ID)
                .then(e -> runtime.getWebExtensionController().setAllowedInPrivateBrowsing(e, true))
                .accept(e -> {
                    ext = e;
                    base = e.metaData.baseUrl;
                    if (!base.endsWith("/")) base += "/";
                    e.setMessageDelegate(new BackgroundDelegate(), "air-bg");
                    // Filet de sécurité : selon la version de Gecko, les messages de l'interface
                    // peuvent arriver ici plutôt qu'au niveau de la session.
                    e.setMessageDelegate(new UiFallbackDelegate(), "air-ui");
                    starting = false;
                    List<Runnable> list = new ArrayList<>(waiting);
                    waiting.clear();
                    for (Runnable r : list) r.run();
                }, err -> Log.e(TAG, "Extension AIR introuvable", err));
    }

    /* ======================= Bloqueur (background.js) ======================= */

    /** Envoie la liste de blocage (StevenBlack) à background.js. */
    static void sendHosts() {
        StringBuilder sb = new StringBuilder(Blocklist.hosts.size() * 20);
        for (String h : Blocklist.hosts) sb.append(h).append('\n');
        final String s = sb.toString();
        ui.post(() -> {
            hostsPayload = s;
            post(json("hosts", s));
        });
    }

    static void setRules(@NonNull String commaList) {
        rulesPayload = commaList;
        post(json("rules", commaList));
    }

    static void setBlock(boolean on) {
        blockOn = on;
        post(json("block", on));
    }

    static void setLook(boolean dark, boolean force) {
        lookDark = dark;
        lookForce = force;
        try {
            post(new JSONObject().put("look", new JSONObject().put("dark", dark).put("force", force)));
        } catch (Exception ignored) {
        }
    }

    private static JSONObject json(String k, Object v) {
        try {
            return new JSONObject().put(k, v);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static void post(JSONObject m) {
        if (bgPort != null) bgPort.postMessage(m);
    }

    private static void resendAll() {
        if (hostsPayload != null) post(json("hosts", hostsPayload));
        post(json("rules", rulesPayload));
        post(json("block", blockOn));
        setLook(lookDark, lookForce);
    }

    private static final class BackgroundDelegate implements WebExtension.MessageDelegate {
        @Override
        public void onConnect(@NonNull WebExtension.Port port) {
            bgPort = port;
            port.setDelegate(new WebExtension.PortDelegate() {
                @Override
                public void onPortMessage(@NonNull Object message, @NonNull WebExtension.Port p) {
                    if (!(message instanceof JSONObject)) return;
                    JSONArray list = ((JSONObject) message).optJSONArray("blocked");
                    if (list == null) return;
                    MainActivity a = front;
                    if (a != null) a.onBlocked(list);
                }

                @Override
                public void onDisconnect(@NonNull WebExtension.Port p) {
                    if (bgPort == p) bgPort = null;
                }
            });
            resendAll();
        }

        @Nullable
        @Override
        public GeckoResult<Object> onMessage(@NonNull String nativeApp, @NonNull Object message,
                                             @NonNull WebExtension.MessageSender sender) {
            return null;
        }
    }

    private static final class UiFallbackDelegate implements WebExtension.MessageDelegate {
        @Override
        public void onConnect(@NonNull WebExtension.Port port) {
            for (MainActivity a : live) {
                if (a.ownsUi(port.sender.session)) {
                    a.acceptUiPort(port);
                    return;
                }
            }
            port.disconnect();
        }

        @Nullable
        @Override
        public GeckoResult<Object> onMessage(@NonNull String nativeApp, @NonNull Object message,
                                             @NonNull WebExtension.MessageSender sender) {
            return null;
        }
    }
}
