package app.airnavig;

import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebExtensionController;

/**
 * Extensions Firefox (addons.mozilla.org) : installation avec confirmation des permissions,
 * activation, désactivation, suppression, page d'options et bouton d'action (fenêtre de l'extension).
 */
final class Addons {
    private static final String TAG = "AIRNavig";
    static final String AMO = "https://addons.mozilla.org/fr/android/";

    private static GeckoRuntime rt;
    private static final Map<String, WebExtension> exts = new HashMap<>();
    private static final Map<String, WebExtension.Action> actions = new HashMap<>();
    private static final Map<String, String> icons = new HashMap<>();
    private static final Handler ui = new Handler(Looper.getMainLooper());

    private Addons() {}

    private static MainActivity front() {
        return Engine.front;
    }

    static void init(GeckoRuntime runtime) {
        rt = runtime;
        WebExtensionController c = runtime.getWebExtensionController();
        c.setPromptDelegate(new WebExtensionController.PromptDelegate() {
            @Nullable
            @Override
            public GeckoResult<WebExtension.PermissionPromptResponse> onInstallPromptRequest(
                    @NonNull WebExtension ext, @NonNull String[] perms, @NonNull String[] origins, @NonNull String[] data) {
                final GeckoResult<WebExtension.PermissionPromptResponse> res = new GeckoResult<>();
                MainActivity a = front();
                if (a == null || a.isFinishing()) {
                    res.complete(new WebExtension.PermissionPromptResponse(false, false, false));
                    return res;
                }
                StringBuilder sb = new StringBuilder();
                String by = ext.metaData.creatorName;
                if (by != null && !by.isEmpty()) sb.append("Par ").append(by).append("\n\n");
                List<String> lines = describe(perms, origins);
                if (lines.isEmpty()) sb.append("Cette extension ne demande aucune permission particulière.");
                else {
                    sb.append("Elle pourra :\n");
                    for (String l : lines) sb.append("• ").append(l).append('\n');
                }
                sb.append("\nN'installe que des extensions en qui tu as confiance.");
                final boolean[] done = {false};
                new AlertDialog.Builder(a)
                        .setTitle("Ajouter « " + name(ext) + " » ?")
                        .setMessage(sb.toString())
                        .setPositiveButton("Ajouter", (d, w) -> {
                            done[0] = true;
                            res.complete(new WebExtension.PermissionPromptResponse(true, false, false));
                        })
                        .setNegativeButton("Annuler", null)
                        .setOnDismissListener(d -> {
                            if (!done[0]) res.complete(new WebExtension.PermissionPromptResponse(false, false, false));
                        })
                        .show();
                return res;
            }

            @Nullable
            @Override
            public GeckoResult<AllowOrDeny> onUpdatePrompt(@NonNull WebExtension ext, @NonNull String[] perms,
                                                          @NonNull String[] origins, @NonNull String[] data) {
                return ask("« " + name(ext) + " » a été mise à jour et demande de nouvelles permissions", perms, origins);
            }

            @Nullable
            @Override
            public GeckoResult<AllowOrDeny> onOptionalPrompt(@NonNull WebExtension ext, @NonNull String[] perms,
                                                            @NonNull String[] origins, @NonNull String[] data) {
                return ask("« " + name(ext) + " » demande une permission supplémentaire", perms, origins);
            }
        });
        c.setAddonManagerDelegate(new WebExtensionController.AddonManagerDelegate() {
            @Override
            public void onInstalled(@NonNull WebExtension ext) {
                attach(ext);
                MainActivity a = front();
                if (a != null) {
                    a.notice("Extension ajoutée : " + name(ext));
                    pushList(a);
                }
            }

            @Override
            public void onReady(@NonNull WebExtension ext) {
                attach(ext);
            }

            @Override
            public void onUninstalled(@NonNull WebExtension ext) {
                exts.remove(ext.id);
                searchCache.remove(ext.id);
                actions.remove(ext.id);
                MainActivity a = front();
                if (a != null) pushList(a);
            }

            @Override
            public void onInstallationFailed(@Nullable WebExtension ext, @NonNull WebExtension.InstallException e) {
                MainActivity a = front();
                if (a != null) a.notice("Installation de l'extension impossible");
            }
        });
        c.list().accept(list -> {
            if (list != null) for (WebExtension e : list) attach(e);
        }, e -> Log.e(TAG, "Liste des extensions", e));
    }

    static String name(WebExtension e) {
        return e.metaData.name == null ? e.id : e.metaData.name;
    }

    private static List<String> describe(String[] perms, String[] origins) {
        List<String> out = new ArrayList<>();
        boolean allSites = false;
        if (origins != null) for (String o : origins) if ("<all_urls>".equals(o) || o.startsWith("*://*/") || o.equals("http://*/*") || o.equals("https://*/*")) allSites = true;
        if (allSites) out.add("lire et modifier tes données sur tous les sites");
        else if (origins != null && origins.length > 0) out.add("lire et modifier tes données sur " + origins.length + " site(s)");
        if (perms != null) for (String p : perms) {
            switch (p) {
                case "tabs": out.add("voir tes onglets ouverts"); break;
                case "history": out.add("lire et effacer ton historique"); break;
                case "bookmarks": out.add("lire et modifier tes favoris"); break;
                case "downloads": out.add("gérer tes téléchargements"); break;
                case "cookies": out.add("lire et modifier les cookies"); break;
                case "webRequest": case "webRequestBlocking": if (!out.contains("bloquer ou modifier les requêtes réseau")) out.add("bloquer ou modifier les requêtes réseau"); break;
                case "clipboardRead": out.add("lire le presse-papiers"); break;
                case "clipboardWrite": out.add("écrire dans le presse-papiers"); break;
                case "geolocation": out.add("connaître ta position"); break;
                case "nativeMessaging": out.add("communiquer avec d'autres applis"); break;
                case "privacy": out.add("modifier les réglages de confidentialité"); break;
                case "proxy": out.add("contrôler les connexions (proxy)"); break;
                case "storage": case "unlimitedStorage": case "alarms": case "contextMenus": case "menus": case "activeTab": case "scripting": break;
                default: out.add(p);
            }
        }
        return out;
    }

    private static GeckoResult<AllowOrDeny> ask(String title, String[] perms, String[] origins) {
        final GeckoResult<AllowOrDeny> res = new GeckoResult<>();
        MainActivity a = front();
        if (a == null || a.isFinishing()) {
            res.complete(AllowOrDeny.DENY);
            return res;
        }
        StringBuilder sb = new StringBuilder();
        for (String l : describe(perms, origins)) sb.append("• ").append(l).append('\n');
        final boolean[] done = {false};
        new AlertDialog.Builder(a)
                .setTitle(title)
                .setMessage(sb.length() == 0 ? "Autoriser ?" : sb.toString())
                .setPositiveButton("Autoriser", (d, w) -> {
                    done[0] = true;
                    res.complete(AllowOrDeny.ALLOW);
                })
                .setNegativeButton("Refuser", null)
                .setOnDismissListener(d -> {
                    if (!done[0]) res.complete(AllowOrDeny.DENY);
                })
                .show();
        return res;
    }

    /** Branche une extension tierce : bouton d'action, onglets, page d'options. */
    static void attach(final WebExtension e) {
        if (e == null || e.isBuiltIn) return;
        exts.put(e.id, e);
        if (e.metaData.icon != null && !icons.containsKey(e.id)) {
            e.metaData.icon.getBitmap(64).accept(b -> {
                if (b != null) icons.put(e.id, png(b));
            }, err -> { });
        }
        e.setActionDelegate(new WebExtension.ActionDelegate() {
            @Override
            public void onBrowserAction(@NonNull WebExtension ext, @Nullable GeckoSession s, @NonNull WebExtension.Action action) {
                if (s == null) actions.put(ext.id, action);
            }

            @Override
            public void onPageAction(@NonNull WebExtension ext, @Nullable GeckoSession s, @NonNull WebExtension.Action action) {
                if (s == null && !actions.containsKey(ext.id)) actions.put(ext.id, action);
            }

            @Nullable
            @Override
            public GeckoResult<GeckoSession> onTogglePopup(@NonNull WebExtension ext, @NonNull WebExtension.Action action) {
                return popup(ext);
            }

            @Nullable
            @Override
            public GeckoResult<GeckoSession> onOpenPopup(@NonNull WebExtension ext, @NonNull WebExtension.Action action) {
                return popup(ext);
            }
        });
        e.setTabDelegate(new WebExtension.TabDelegate() {
            @Nullable
            @Override
            public GeckoResult<GeckoSession> onNewTab(@NonNull WebExtension ext, @NonNull WebExtension.CreateTabDetails d) {
                MainActivity a = front();
                if (a == null) return null;
                return GeckoResult.fromValue(a.newTabSession(d.url, d.active == null || d.active));
            }

            @Override
            public void onOpenOptionsPage(@NonNull WebExtension ext) {
                openOptions(ext);
            }
        });
    }

    private static String png(Bitmap b) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, bo);
        return "data:image/png;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
    }

    /** Fenêtre d'une extension (popup du bouton d'action), dans une boîte de dialogue. */
    private static GeckoResult<GeckoSession> popup(WebExtension ext) {
        final MainActivity a = front();
        if (a == null || a.isFinishing()) return null;
        final GeckoSession s = new GeckoSession();
        final Dialog dlg = new Dialog(a);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        final GeckoView v = new GeckoView(a);
        FrameLayout box = new FrameLayout(a);
        int h = (int) (a.getResources().getDisplayMetrics().heightPixels * 0.7);
        box.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h));
        dlg.setContentView(box);
        dlg.setOnDismissListener(d -> {
            try {
                v.releaseSession();
                s.close();
            } catch (Throwable ignored) {
            }
        });
        s.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override
            public void onCloseRequest(@NonNull GeckoSession session) {
                dlg.dismiss();
            }
        });
        s.setPromptDelegate(a.prompts());
        // Gecko ouvre lui-même la session renvoyée ; on l'affiche dès qu'elle est prête.
        final Runnable[] attach = new Runnable[1];
        final int[] tries = {0};
        attach[0] = () -> {
            if (s.isOpen()) {
                v.setSession(s);
                dlg.show();
                if (dlg.getWindow() != null) dlg.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            } else if (tries[0]++ < 60) {
                ui.postDelayed(attach[0], 50);
            }
        };
        ui.post(attach[0]);
        return GeckoResult.fromValue(s);
    }

    static void openOptions(WebExtension e) {
        MainActivity a = front();
        String u = e.metaData.optionsPageUrl;
        if (a == null) return;
        if (u == null || u.isEmpty()) {
            a.notice("Cette extension n'a pas de page d'options");
            return;
        }
        a.openInNewTab(u);
    }

    static void install(String url) {
        if (rt == null) return;
        MainActivity a = front();
        if (a != null) a.notice("Téléchargement de l'extension…");
        rt.getWebExtensionController().install(url, WebExtensionController.INSTALLATION_METHOD_MANAGER)
                .accept(e -> { }, err -> {
                    Log.e(TAG, "Installation extension", err);
                    MainActivity b = front();
                    if (b != null && !(err instanceof WebExtension.InstallException
                            && ((WebExtension.InstallException) err).code == WebExtension.InstallException.ErrorCodes.ERROR_USER_CANCELED)) {
                        b.notice("Installation de l'extension impossible");
                    }
                });
    }

    /** Envoie la liste des extensions à l'interface. */
    static void pushList(final MainActivity a) {
        if (rt == null) return;
        rt.getWebExtensionController().list().accept(list -> {
            JSONArray arr = new JSONArray();
            if (list != null) for (WebExtension e : list) {
                if (e.isBuiltIn) continue;
                attach(e);
                try {
                    WebExtension.Action act = actions.get(e.id);
                    arr.put(new JSONObject()
                            .put("id", e.id)
                            .put("name", name(e))
                            .put("version", e.metaData.version == null ? "" : e.metaData.version)
                            .put("desc", e.metaData.description == null ? "" : e.metaData.description)
                            .put("on", e.metaData.enabled)
                            .put("options", e.metaData.optionsPageUrl != null && !e.metaData.optionsPageUrl.isEmpty())
                            .put("action", act != null ? (act.title == null || act.title.isEmpty() ? name(e) : act.title) : "")
                            .put("priv", e.metaData.allowedInPrivateBrowsing)
                            .put("icon", icons.containsKey(e.id) ? icons.get(e.id) : "")
                            .put("search", searchProvider(a, e.id)));
                } catch (Exception ignored) {
                }
            }
            a.call("onAddons", arr.toString());
        }, err -> a.call("onAddons", "[]"));
    }

    /* ======================= Moteurs de recherche fournis par les extensions ======================= */

    private static final Map<String, JSONObject> searchCache = new HashMap<>();

    /** Fichier .xpi installé d'une extension, dans le profil Gecko de l'appli. */
    @Nullable
    private static java.io.File findXpi(java.io.File dir, String name, int depth) {
        java.io.File[] list = dir.listFiles();
        if (list == null) return null;
        for (java.io.File f : list) if (f.isFile() && f.getName().equals(name)) return f;
        if (depth <= 0) return null;
        for (java.io.File f : list) {
            if (!f.isDirectory()) continue;
            java.io.File r = findXpi(f, name, depth - 1);
            if (r != null) return r;
        }
        return null;
    }

    private static String readEntry(java.util.zip.ZipFile z, String name) throws Exception {
        java.util.zip.ZipEntry en = z.getEntry(name);
        if (en == null || en.getSize() > 2_000_000) return null;
        try (java.io.InputStream in = z.getInputStream(en)) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            String txt = new String(bo.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            return txt.startsWith("\uFEFF") ? txt.substring(1) : txt;
        }
    }

    /** Remplace « __MSG_nom__ » par le texte traduit de l'extension (français si disponible). */
    private static String msg(java.util.zip.ZipFile z, String defLocale, String v) {
        if (v == null || !v.startsWith("__MSG_") || !v.endsWith("__")) return v;
        String key = v.substring(6, v.length() - 2);
        for (String loc : new String[]{"fr", "fr_FR", defLocale, "en", "en_US"}) {
            if (loc == null) continue;
            try {
                String j = readEntry(z, "_locales/" + loc + "/messages.json");
                if (j == null) continue;
                JSONObject m = new JSONObject(j);
                for (java.util.Iterator<String> it = m.keys(); it.hasNext(); ) {
                    String k = it.next();
                    if (k.equalsIgnoreCase(key)) return m.getJSONObject(k).optString("message", v);
                }
            } catch (Exception ignored) {
            }
        }
        return v;
    }

    /**
     * Moteur de recherche déclaré par l'extension (manifest : chrome_settings_overrides.search_provider),
     * pour l'ajouter aux moteurs d'AIR Navig. null si l'extension n'en fournit pas.
     */
    @Nullable
    static JSONObject searchProvider(android.content.Context c, String id) {
        if (searchCache.containsKey(id)) return searchCache.get(id);
        JSONObject out = null;
        try {
            java.io.File root = new java.io.File(c.getFilesDir(), "mozilla");
            java.io.File xpi = findXpi(root.exists() ? root : c.getFilesDir(), id + ".xpi", 4);
            if (xpi != null) {
                try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(xpi)) {
                    String mf = readEntry(z, "manifest.json");
                    if (mf != null) {
                        JSONObject m = new JSONObject(mf.replaceAll("(?m)^\\s*//.*$", ""));
                        JSONObject o = m.optJSONObject("chrome_settings_overrides");
                        JSONObject sp = o == null ? null : o.optJSONObject("search_provider");
                        String loc = m.optString("default_locale", null);
                        if (sp != null) {
                            String url = msg(z, loc, sp.optString("search_url", ""));
                            String params = msg(z, loc, sp.optString("search_url_get_params", ""));
                            if (!params.isEmpty()) url += (url.contains("?") ? "&" : "?") + params;
                            if (url.startsWith("https://") && url.contains("{searchTerms}")) {
                                out = new JSONObject()
                                        .put("name", msg(z, loc, sp.optString("name", m.optString("name", id))))
                                        .put("url", url)
                                        .put("keyword", msg(z, loc, sp.optString("keyword", "")))
                                        .put("icon", msg(z, loc, sp.optString("favicon_url", "")))
                                        .put("def", sp.optBoolean("is_default", false));
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Moteur de l'extension " + id, e);
        }
        if (out != null) searchCache.put(id, out);
        return out;
    }

    /** Commande de l'interface : {op, id}. */
    static void cmd(final MainActivity a, JSONObject m) {
        String op = m.optString("op");
        if ("list".equals(op)) {
            pushList(a);
            return;
        }
        if ("browse".equals(op)) {
            a.openInNewTab(AMO);
            return;
        }
        final WebExtension e = exts.get(m.optString("id"));
        if (e == null || rt == null) return;
        WebExtensionController c = rt.getWebExtensionController();
        switch (op) {
            case "toggle":
                (e.metaData.enabled ? c.disable(e, WebExtensionController.EnableSource.USER)
                        : c.enable(e, WebExtensionController.EnableSource.USER))
                        .accept(x -> {
                            if (x != null) attach(x);
                            pushList(a);
                        }, err -> a.notice("Action impossible"));
                break;
            case "private":
                c.setAllowedInPrivateBrowsing(e, !e.metaData.allowedInPrivateBrowsing)
                        .accept(x -> {
                            if (x != null) attach(x);
                            pushList(a);
                        }, err -> a.notice("Action impossible"));
                break;
            case "remove":
                new AlertDialog.Builder(a)
                        .setTitle("Supprimer « " + name(e) + " » ?")
                        .setPositiveButton("Supprimer", (d, w) -> c.uninstall(e).accept(x -> pushList(a), err -> a.notice("Suppression impossible")))
                        .setNegativeButton("Annuler", null)
                        .show();
                break;
            case "options":
                openOptions(e);
                break;
            case "action": {
                WebExtension.Action act = actions.get(e.id);
                if (act != null) act.click();
                else a.notice("Cette extension n'a pas de bouton");
                break;
            }
            default:
                break;
        }
    }
}
