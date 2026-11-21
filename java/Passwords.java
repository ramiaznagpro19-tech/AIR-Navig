package app.airnavig;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.geckoview.Autocomplete;
import org.mozilla.geckoview.GeckoResult;

/**
 * Gestionnaire de mots de passe d'AIR Navig.
 *
 * <p>Les identifiants sont gardés dans un fichier chiffré par une clé du coffre matériel
 * d'Android (voir {@link Crypto}). Gecko les propose et les enregistre via
 * {@link Autocomplete.StorageDelegate}. Import / export au format CSV (Chrome, Firefox,
 * Bitwarden, Proton Pass… : les colonnes sont reconnues par leur nom).
 */
final class Passwords implements Autocomplete.StorageDelegate {
    private static final String TAG = "AIRNavig";
    private static final String FILE = "logins.bin";
    private static final String KEY = "air_logins";

    static final class Login {
        String guid, origin, formAction, realm, user, pass;
        long created, changed, used;

        JSONObject json() throws Exception {
            return new JSONObject().put("g", guid).put("o", origin).put("f", formAction == null ? "" : formAction)
                    .put("r", realm == null ? "" : realm).put("u", user == null ? "" : user).put("p", pass == null ? "" : pass)
                    .put("c", created).put("m", changed).put("x", used);
        }

        static Login of(JSONObject j) {
            Login l = new Login();
            l.guid = j.optString("g", UUID.randomUUID().toString());
            l.origin = j.optString("o", "");
            l.formAction = emptyToNull(j.optString("f", ""));
            l.realm = emptyToNull(j.optString("r", ""));
            l.user = j.optString("u", "");
            l.pass = j.optString("p", "");
            l.created = j.optLong("c", 0);
            l.changed = j.optLong("m", 0);
            l.used = j.optLong("x", 0);
            return l;
        }

        Autocomplete.LoginEntry entry() {
            return new Autocomplete.LoginEntry.Builder()
                    .guid(guid).origin(origin).formActionOrigin(formAction).httpRealm(realm)
                    .username(user).password(pass).build();
        }

        String host() {
            String h = Uri.parse(origin == null ? "" : origin).getHost();
            return h == null ? (origin == null ? "" : origin) : h;
        }
    }

    private static Passwords instance;
    private final Context app;
    private final Map<String, Login> byGuid = new LinkedHashMap<>();
    /** Suppressions (guid → date), pour que la synchro les propage. */
    private final Map<String, Long> deleted = new LinkedHashMap<>();
    /** Sites pour lesquels l'utilisateur a répondu « Jamais ». */
    private final Set<String> never = new HashSet<>();
    /** Navigation privée : on peut remplir, jamais enregistrer. */
    static volatile boolean privateFront;

    private Passwords(Context c) {
        app = c.getApplicationContext();
        load();
    }

    static synchronized Passwords get(Context c) {
        if (instance == null) instance = new Passwords(c);
        return instance;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    /** « https://exemple.fr/chemin » → « https://exemple.fr ». */
    static String originOf(String url) {
        if (url == null) return "";
        url = url.trim();
        if (url.isEmpty()) return "";
        if (!url.contains("://")) url = "https://" + url;
        Uri u = Uri.parse(url);
        if (u.getScheme() == null || u.getHost() == null) return "";
        return u.getScheme().toLowerCase(Locale.ROOT) + "://" + u.getHost().toLowerCase(Locale.ROOT)
                + (u.getPort() > 0 ? ":" + u.getPort() : "");
    }

    /* ======================= Fichier chiffré ======================= */

    private synchronized void load() {
        File f = new File(app.getFilesDir(), FILE);
        if (!f.exists()) return;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] blob = new byte[(int) f.length()];
            int off = 0, n;
            while (off < blob.length && (n = in.read(blob, off, blob.length - off)) > 0) off += n;
            JSONObject j = new JSONObject(new String(Crypto.openDevice(Crypto.deviceKey(KEY), blob), StandardCharsets.UTF_8));
            JSONArray items = j.optJSONArray("items");
            if (items != null) for (int i = 0; i < items.length(); i++) {
                Login l = Login.of(items.getJSONObject(i));
                byGuid.put(l.guid, l);
            }
            JSONObject d = j.optJSONObject("deleted");
            if (d != null) for (Iterator<String> it = d.keys(); it.hasNext(); ) {
                String k = it.next();
                deleted.put(k, d.optLong(k));
            }
            JSONArray nv = j.optJSONArray("never");
            if (nv != null) for (int i = 0; i < nv.length(); i++) never.add(nv.optString(i));
        } catch (Exception e) {
            Log.e(TAG, "Mots de passe illisibles", e);
        }
    }

    private synchronized void save() {
        try {
            JSONObject j = new JSONObject();
            JSONArray items = new JSONArray();
            for (Login l : byGuid.values()) items.put(l.json());
            j.put("items", items);
            long limit = System.currentTimeMillis() - 180L * 86400_000L;
            JSONObject d = new JSONObject();
            for (Map.Entry<String, Long> e : deleted.entrySet()) if (e.getValue() > limit) d.put(e.getKey(), e.getValue());
            j.put("deleted", d);
            j.put("never", new JSONArray(never));
            byte[] blob = Crypto.sealDevice(Crypto.deviceKey(KEY), j.toString().getBytes(StandardCharsets.UTF_8));
            File tmp = new File(app.getFilesDir(), FILE + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(blob);
                out.getFD().sync();
            }
            if (!tmp.renameTo(new File(app.getFilesDir(), FILE))) Log.e(TAG, "Mots de passe non enregistrés");
        } catch (Exception e) {
            Log.e(TAG, "Mots de passe non enregistrés", e);
        }
    }

    /* ======================= Accès ======================= */

    synchronized List<Login> all() {
        List<Login> l = new ArrayList<>(byGuid.values());
        Collections.sort(l, (a, b) -> a.host().compareToIgnoreCase(b.host()));
        return l;
    }

    synchronized int count() {
        return byGuid.size();
    }

    synchronized void delete(String guid) {
        if (byGuid.remove(guid) != null) {
            deleted.put(guid, System.currentTimeMillis());
            save();
        }
    }

    synchronized void deleteAll() {
        long now = System.currentTimeMillis();
        for (String g : byGuid.keySet()) deleted.put(g, now);
        byGuid.clear();
        save();
    }

    synchronized boolean isNever(String origin) {
        return never.contains(originOf(origin));
    }

    synchronized void setNever(String origin) {
        never.add(originOf(origin));
        save();
    }

    private static boolean hostMatches(String loginHost, String domain) {
        if (loginHost == null || domain == null) return false;
        loginHost = loginHost.toLowerCase(Locale.ROOT);
        domain = domain.toLowerCase(Locale.ROOT);
        if (domain.contains("://")) {
            String h = Uri.parse(domain).getHost();
            if (h != null) domain = h;
        }
        if (loginHost.startsWith("www.")) loginHost = loginHost.substring(4);
        if (domain.startsWith("www.")) domain = domain.substring(4);
        return loginHost.equals(domain) || loginHost.endsWith("." + domain) || domain.endsWith("." + loginHost);
    }

    /** Ajoute ou met à jour (même guid, ou même site + même identifiant). */
    synchronized Login upsert(String guid, String origin, String formAction, String realm, String user, String pass) {
        long now = System.currentTimeMillis();
        origin = originOf(origin);
        if (origin.isEmpty() || pass == null || pass.isEmpty()) return null;
        Login l = guid == null ? null : byGuid.get(guid);
        if (l == null) {
            for (Login x : byGuid.values()) {
                if (x.origin.equals(origin) && eq(x.user, user)) {
                    l = x;
                    break;
                }
            }
        }
        if (l == null) {
            l = new Login();
            l.guid = guid != null && !guid.isEmpty() ? guid : UUID.randomUUID().toString();
            l.created = now;
            l.changed = now;
            byGuid.put(l.guid, l);
        } else if (!eq(l.pass, pass) || !eq(l.user, user)) {
            l.changed = now;
        }
        l.origin = origin;
        if (formAction != null) l.formAction = formAction;
        if (realm != null) l.realm = realm;
        l.user = user == null ? "" : user;
        l.pass = pass;
        deleted.remove(l.guid);
        save();
        return l;
    }

    private static boolean eq(String a, String b) {
        return (a == null ? "" : a).equals(b == null ? "" : b);
    }

    /* ======================= Gecko ======================= */

    @Nullable
    @Override
    public GeckoResult<Autocomplete.LoginEntry[]> onLoginFetch(@NonNull String domain) {
        List<Autocomplete.LoginEntry> out = new ArrayList<>();
        synchronized (this) {
            for (Login l : byGuid.values()) if (hostMatches(l.host(), domain)) out.add(l.entry());
        }
        return GeckoResult.fromValue(out.toArray(new Autocomplete.LoginEntry[0]));
    }

    @Nullable
    @Override
    public GeckoResult<Autocomplete.LoginEntry[]> onLoginFetch() {
        List<Autocomplete.LoginEntry> out = new ArrayList<>();
        synchronized (this) {
            for (Login l : byGuid.values()) out.add(l.entry());
        }
        return GeckoResult.fromValue(out.toArray(new Autocomplete.LoginEntry[0]));
    }

    @Override
    public void onLoginSave(@NonNull Autocomplete.LoginEntry e) {
        if (privateFront) return;
        upsert(e.guid, e.origin, e.formActionOrigin, e.httpRealm, e.username, e.password);
    }

    @Override
    public void onLoginUsed(@NonNull Autocomplete.LoginEntry e, int usedFields) {
        synchronized (this) {
            Login l = e.guid == null ? null : byGuid.get(e.guid);
            if (l != null) {
                l.used = System.currentTimeMillis();
                save();
            }
        }
    }

    /* ======================= CSV ======================= */

    private static List<String> csvLine(BufferedReader r) throws Exception {
        List<String> out = new ArrayList<>();
        StringBuilder f = new StringBuilder();
        boolean q = false, any = false;
        int c;
        while ((c = r.read()) != -1) {
            any = true;
            char ch = (char) c;
            if (q) {
                if (ch == '"') {
                    r.mark(1);
                    int n = r.read();
                    if (n == '"') f.append('"');
                    else {
                        q = false;
                        if (n != -1) r.reset();
                    }
                } else f.append(ch);
            } else if (ch == '"') q = true;
            else if (ch == ',') {
                out.add(f.toString());
                f.setLength(0);
            } else if (ch == '\n') {
                break;
            } else if (ch != '\r') f.append(ch);
        }
        if (!any) return null;
        out.add(f.toString());
        return out;
    }

    private static int col(List<String> head, String... names) {
        for (String n : names) {
            for (int i = 0; i < head.size(); i++) {
                String h = head.get(i).trim().toLowerCase(Locale.ROOT).replace("﻿", "");
                if (h.equals(n)) return i;
            }
        }
        return -1;
    }

    /** Importe un CSV. Renvoie le nombre d'identifiants ajoutés ou mis à jour. */
    int importCsv(InputStream in) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        List<String> head = csvLine(r);
        if (head == null) return 0;
        int cu = col(head, "url", "login_uri", "website", "origin", "web site", "site", "hostname");
        int cn = col(head, "username", "login_username", "user", "login", "email", "identifiant");
        int cp = col(head, "password", "login_password", "mot de passe", "pass");
        int cr = col(head, "httprealm");
        int cf = col(head, "formactionorigin");
        if (cu < 0 || cp < 0) throw new IllegalArgumentException("colonnes url / password introuvables");
        int n = 0;
        List<String> row;
        synchronized (this) {
            while ((row = csvLine(r)) != null) {
                if (row.size() <= Math.max(cu, cp)) continue;
                String url = row.get(cu);
                // Bitwarden peut mettre plusieurs adresses séparées par des virgules : on garde la première.
                if (url.contains(",")) url = url.substring(0, url.indexOf(','));
                String user = cn >= 0 && cn < row.size() ? row.get(cn) : "";
                String pass = row.get(cp);
                String realm = cr >= 0 && cr < row.size() ? emptyToNull(row.get(cr)) : null;
                String form = cf >= 0 && cf < row.size() ? emptyToNull(row.get(cf)) : null;
                if (realm == null && form == null) form = originOf(url);
                if (upsertNoSave(url, form, realm, user, pass)) n++;
            }
            save();
        }
        return n;
    }

    private boolean upsertNoSave(String url, String form, String realm, String user, String pass) {
        String origin = originOf(url);
        if (origin.isEmpty() || pass == null || pass.isEmpty()) return false;
        long now = System.currentTimeMillis();
        for (Login x : byGuid.values()) {
            if (x.origin.equals(origin) && eq(x.user, user)) {
                if (!eq(x.pass, pass)) {
                    x.pass = pass;
                    x.changed = now;
                }
                return true;
            }
        }
        Login l = new Login();
        l.guid = UUID.randomUUID().toString();
        l.origin = origin;
        l.formAction = form;
        l.realm = realm;
        l.user = user == null ? "" : user;
        l.pass = pass;
        l.created = now;
        l.changed = now;
        byGuid.put(l.guid, l);
        return true;
    }

    private static String q(String s) {
        s = s == null ? "" : s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    /** Export au format Firefox (relisible par Chrome, Bitwarden, AIR Navig…). */
    synchronized int exportCsv(OutputStream out) throws Exception {
        StringBuilder sb = new StringBuilder("\"url\",\"username\",\"password\",\"httpRealm\",\"formActionOrigin\",\"guid\",\"timeCreated\",\"timeLastUsed\",\"timePasswordChanged\"\n");
        for (Login l : byGuid.values()) {
            sb.append(q(l.origin)).append(',').append(q(l.user)).append(',').append(q(l.pass)).append(',')
                    .append(q(l.realm)).append(',').append(q(l.formAction)).append(',').append(q("{" + l.guid + "}")).append(',')
                    .append(q(String.valueOf(l.created))).append(',').append(q(String.valueOf(l.used))).append(',')
                    .append(q(String.valueOf(l.changed))).append('\n');
        }
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        return byGuid.size();
    }
}
