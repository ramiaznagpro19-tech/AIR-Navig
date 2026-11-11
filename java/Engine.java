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
                .configFilePath(writeConfig(context))
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

    /* ======================= Télémétrie coupée ======================= */

    /**
     * Réglages imposés au moteur Gecko à chaque démarrage : aucune télémétrie, aucun envoi de
     * données (Glean/FOG compris), aucune étude, aucun rapport de plantage, aucune connexion
     * de « vérification » vers les serveurs de Mozilla.
     */
    static final String[][] PRIVACY_PREFS = {
        // Télémétrie Gecko
        {"toolkit.telemetry.enabled", "false"},
        {"toolkit.telemetry.unified", "false"},
        {"toolkit.telemetry.archive.enabled", "false"},
        {"toolkit.telemetry.server", "'data:,'"},
        {"toolkit.telemetry.geckoview.streaming", "false"},
        {"toolkit.telemetry.newProfilePing.enabled", "false"},
        {"toolkit.telemetry.shutdownPingSender.enabled", "false"},
        {"toolkit.telemetry.firstShutdownPing.enabled", "false"},
        {"toolkit.telemetry.updatePing.enabled", "false"},
        {"toolkit.telemetry.bhrPing.enabled", "false"},
        {"toolkit.telemetry.dap_enabled", "false"},
        {"toolkit.telemetry.user_characteristics_ping.opt-out", "true"},
        {"toolkit.telemetry.coverage.opt-out", "true"},
        {"toolkit.coverage.opt-out", "true"},
        {"toolkit.coverage.endpoint.base", "''"},
        {"dom.security.unexpected_system_load_telemetry_enabled", "false"},
        {"browser.ping-centre.telemetry", "false"},
        // Envoi de données (Glean / FOG)
        {"datareporting.healthreport.uploadEnabled", "false"},
        {"datareporting.policy.dataSubmissionEnabled", "false"},
        {"datareporting.usage.uploadEnabled", "false"},
        // Mesure publicitaire (PPA)
        {"dom.private-attribution.submission.enabled", "false"},
        // Études et expériences à distance
        {"app.normandy.enabled", "false"},
        {"app.normandy.api_url", "''"},
        {"app.shield.optoutstudies.enabled", "false"},
        // Rapports de plantage
        {"breakpad.reportURL", "''"},
        {"browser.crashReports.unsubmittedCheck.enabled", "false"},
        {"browser.crashReports.unsubmittedCheck.autoSubmit2", "false"},
        // Connexions de vérification vers Mozilla
        {"network.captive-portal-service.enabled", "false"},
        {"network.connectivity-service.enabled", "false"},
        {"captivedetect.canonicalURL", "''"},
        {"security.certerrors.mitm.priming.enabled", "false"},
        // Navigation sécurisée gardée, sans envoi d'empreintes de fichiers à Google
        {"browser.safebrowsing.downloads.remote.enabled", "false"},
    };

    /** Écrit le fichier de réglages lu par GeckoView au démarrage du moteur. */
    @Nullable
    private static String writeConfig(@NonNull Context context) {
        StringBuilder y = new StringBuilder("# AIR Navig : télémétrie et rapports coupés\n");
        y.append("env:\n  MOZ_CRASHREPORTER_DISABLE: '1'\n  MOZ_CRASHREPORTER_NO_REPORT: '1'\n");
        y.append("prefs:\n");
        for (String[] p : PRIVACY_PREFS) y.append("  ").append(p[0]).append(": ").append(p[1]).append('\n');
        java.io.File f = new java.io.File(context.getFilesDir(), "air-gecko.yaml");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
            out.write(y.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return f.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "Réglages de confidentialité non écrits", e);
            return null;
        }
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
