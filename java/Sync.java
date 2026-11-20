package app.airnavig;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Base64;
import android.util.Log;
import android.widget.EditText;
import android.widget.LinearLayout;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.json.JSONObject;

/**
 * Synchronisation par fichier chiffré, sans compte ni serveur.
 *
 * <p>Tu choisis un fichier dans n'importe quel dossier accessible au téléphone (Google Drive,
 * Nextcloud, OneDrive, clé USB…). AIR Navig y écrit favoris, historique, onglets et mots de
 * passe, chiffrés par ta phrase secrète (PBKDF2 + AES-256-GCM, voir {@link Crypto}). Sur un autre
 * appareil, tu choisis le même fichier et la même phrase : les deux se fusionnent.
 *
 * <p>Format : « AIRSYNC1 » + sel (16 octets) + IV (12) + données chiffrées.
 */
final class Sync {
    private static final String TAG = "AIRNavig";
    private static final byte[] MAGIC = "AIRSYNC1".getBytes(StandardCharsets.US_ASCII);
    private static final String KEY = "air_sync";
    static final int REQ_CREATE = 21, REQ_OPEN = 22;

    private static volatile boolean running;
    private static byte[] cachedSalt;
    private static SecretKey cachedKey;
    private static String cachedFor;
    private static final Handler ui = new Handler(Looper.getMainLooper());

    /* Fichier choisi en attente de sa phrase secrète. */
    private static Uri pendingUri;
    private static boolean pendingCreate;

    private Sync() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("air", 0);
    }

    static boolean configured(Context c) {
        return prefs(c).getString("sync_uri", null) != null && prefs(c).getString("sync_pass", null) != null;
    }

    static String deviceId(Context c) {
        SharedPreferences p = prefs(c);
        String id = p.getString("sync_dev", null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            p.edit().putString("sync_dev", id).apply();
        }
        return id;
    }

    static String deviceName() {
        String m = Build.MODEL == null ? "Android" : Build.MODEL;
        String b = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
        if (!b.isEmpty() && !m.toLowerCase().startsWith(b.toLowerCase())) m = Character.toUpperCase(b.charAt(0)) + b.substring(1) + " " + m;
        return m;
    }

    static long last(Context c) {
        return prefs(c).getLong("sync_last", 0);
    }

    static JSONObject status(Context c) {
        try {
            return new JSONObject().put("on", configured(c)).put("last", last(c))
                    .put("file", prefs(c).getString("sync_name", "")).put("device", deviceName())
                    .put("err", prefs(c).getString("sync_err", ""));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static String pass(Context c) {
        try {
            String s = prefs(c).getString("sync_pass", null);
            if (s == null) return null;
            byte[] p = Crypto.openDevice(Crypto.deviceKey(KEY), Base64.decode(s, Base64.NO_WRAP));
            return new String(p, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.e(TAG, "Phrase de synchro illisible", e);
            return null;
        }
    }

    private static void savePass(Context c, String pass) throws Exception {
        byte[] b = Crypto.sealDevice(Crypto.deviceKey(KEY), pass.getBytes(StandardCharsets.UTF_8));
        prefs(c).edit().putString("sync_pass", Base64.encodeToString(b, Base64.NO_WRAP)).apply();
    }

    /* ======================= Réglage ======================= */

    static void setup(final MainActivity a) {
        AlertDialog.Builder b = new AlertDialog.Builder(a).setTitle("Synchronisation chiffrée");
        if (configured(a)) {
            b.setMessage("Fichier : " + prefs(a).getString("sync_name", "?") + "\n\nTes données sont chiffrées par ta phrase secrète avant d'être écrites dans ce fichier.")
                    .setPositiveButton("Synchroniser maintenant", (d, w) -> run(a, true))
                    .setNeutralButton("Arrêter la synchro", (d, w) -> {
                        prefs(a).edit().remove("sync_uri").remove("sync_pass").remove("sync_name").remove("sync_last").remove("sync_err").apply();
                        cachedKey = null;
                        a.call("onSyncStatus", status(a).toString());
                        a.notice("Synchronisation arrêtée (le fichier n'a pas été supprimé)");
                    })
                    .setNegativeButton("Fermer", null);
        } else {
            b.setMessage("Choisis un fichier dans Google Drive, Nextcloud, OneDrive ou un autre dossier. AIR Navig y range favoris, historique, onglets et mots de passe, chiffrés par une phrase secrète que toi seul connais.\n\nSur ton autre appareil, choisis « J'ai déjà un fichier » et le même fichier.")
                    .setPositiveButton("Créer un fichier", (d, w) -> pick(a, true))
                    .setNeutralButton("J'ai déjà un fichier", (d, w) -> pick(a, false))
                    .setNegativeButton("Annuler", null);
        }
        b.show();
    }

    private static void pick(MainActivity a, boolean create) {
        Intent i = new Intent(create ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(create ? "application/octet-stream" : "*/*");
        if (create) i.putExtra(Intent.EXTRA_TITLE, "AIR-Navig-synchro.airsync");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            a.startActivityForResult(i, create ? REQ_CREATE : REQ_OPEN);
        } catch (Exception e) {
            a.notice("Aucun gestionnaire de fichiers disponible");
        }
    }

    /** Résultat du sélecteur de fichier. */
    static void onPicked(final MainActivity a, int req, Intent data) {
        if (data == null || data.getData() == null) return;
        Uri u = data.getData();
        try {
            a.getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception e) {
            a.notice("Ce dossier ne permet pas d'écrire durablement : choisis-en un autre");
            return;
        }
        pendingUri = u;
        pendingCreate = req == REQ_CREATE;
        askPass(a);
    }

    private static void askPass(final MainActivity a) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (20 * a.getResources().getDisplayMetrics().density);
        box.setPadding(p, p / 2, p, 0);
        final EditText e1 = new EditText(a);
        e1.setHint(pendingCreate ? "Phrase secrète (10 caractères ou plus)" : "Phrase secrète du fichier");
        e1.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(e1);
        final EditText e2 = new EditText(a);
        if (pendingCreate) {
            e2.setHint("Retape-la");
            e2.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            box.addView(e2);
        }
        new AlertDialog.Builder(a)
                .setTitle(pendingCreate ? "Choisis ta phrase secrète" : "Phrase secrète")
                .setMessage(pendingCreate ? "Sans elle, personne (ni toi) ne peut relire le fichier. Note-la en lieu sûr : elle ne peut pas être récupérée." : null)
                .setView(box)
                .setPositiveButton("Valider", (d, w) -> {
                    String s1 = e1.getText().toString();
                    if (pendingCreate && (s1.length() < 10 || !s1.equals(e2.getText().toString()))) {
                        a.notice(s1.length() < 10 ? "Phrase trop courte (10 caractères minimum)" : "Les deux phrases sont différentes");
                        askPass(a);
                        return;
                    }
                    finishSetup(a, s1);
                })
                .setNegativeButton("Annuler", (d, w) -> pendingUri = null)
                .show();
    }

    private static void finishSetup(final MainActivity a, final String pass) {
        final Uri u = pendingUri;
        final boolean create = pendingCreate;
        pendingUri = null;
        if (u == null) return;
        new Thread(() -> {
            // Fichier existant : on vérifie la phrase avant de l'enregistrer.
            if (!create) {
                try {
                    byte[] blob = read(a, u);
                    if (blob.length > 0) decrypt(blob, pass);
                } catch (javax.crypto.AEADBadTagException e) {
                    a.notice("Phrase secrète incorrecte pour ce fichier");
                    ui.post(() -> {
                        pendingUri = u;
                        pendingCreate = false;
                        askPass(a);
                    });
                    return;
                } catch (Exception e) {
                    a.notice("Ce fichier n'est pas un fichier de synchro AIR Navig");
                    return;
                }
            }
            try {
                savePass(a, pass);
            } catch (Exception e) {
                a.notice("Impossible de protéger la phrase secrète sur ce téléphone");
                return;
            }
            prefs(a).edit().putString("sync_uri", u.toString()).putString("sync_name", displayName(a, u)).remove("sync_err").apply();
            cachedKey = null;
            ui.post(() -> run(a, true));
        }).start();
    }

    private static String displayName(Context c, Uri u) {
        try (android.database.Cursor cur = c.getContentResolver().query(u, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) return cur.getString(0);
        } catch (Exception ignored) {
        }
        String s = u.getLastPathSegment();
        return s == null ? "fichier" : s;
    }

    /* ======================= Lecture / écriture ======================= */

    private static byte[] read(Context c, Uri u) throws Exception {
        try (InputStream in = c.getContentResolver().openInputStream(u)) {
            if (in == null) return new byte[0];
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
                if (bo.size() > 64 * 1024 * 1024) throw new IllegalStateException("fichier trop gros");
            }
            return bo.toByteArray();
        }
    }

    private static synchronized SecretKey key(String pass, byte[] salt) throws Exception {
        String id = pass.hashCode() + ":" + Base64.encodeToString(salt, Base64.NO_WRAP);
        if (cachedKey != null && id.equals(cachedFor)) return cachedKey;
        cachedKey = Crypto.derive(pass.toCharArray(), salt);
        cachedFor = id;
        cachedSalt = salt;
        return cachedKey;
    }

    private static JSONObject decrypt(byte[] blob, String pass) throws Exception {
        if (blob.length < MAGIC.length + 16 + 12 + 16 || !Arrays.equals(Arrays.copyOf(blob, MAGIC.length), MAGIC)) {
            throw new IllegalArgumentException("format");
        }
        byte[] salt = Arrays.copyOfRange(blob, MAGIC.length, MAGIC.length + 16);
        int off = MAGIC.length + 16;
        byte[] plain = Crypto.open(key(pass, salt), blob, off, blob.length - off, MAGIC);
        return new JSONObject(new String(plain, StandardCharsets.UTF_8));
    }

    private static byte[] encrypt(JSONObject j, String pass) throws Exception {
        byte[] salt = cachedSalt != null ? cachedSalt : Crypto.random(16);
        byte[] sealed = Crypto.seal(key(pass, salt), j.toString().getBytes(StandardCharsets.UTF_8), MAGIC);
        byte[] out = new byte[MAGIC.length + 16 + sealed.length];
        System.arraycopy(MAGIC, 0, out, 0, MAGIC.length);
        System.arraycopy(salt, 0, out, MAGIC.length, 16);
        System.arraycopy(sealed, 0, out, MAGIC.length + 16, sealed.length);
        return out;
    }

    /* ======================= Synchronisation ======================= */

    private static JSONObject remotePasswords;
    private static Runnable timeout;

    /** Lit le fichier, le fait fusionner par l'interface, puis écrit le résultat. */
    static void run(final MainActivity a, final boolean manual) {
        if (!configured(a) || running) return;
        running = true;
        if (manual) a.notice("Synchronisation…");
        final Uri u = Uri.parse(prefs(a).getString("sync_uri", ""));
        new Thread(() -> {
            String p = pass(a);
            JSONObject remote;
            try {
                if (p == null) throw new IllegalStateException("phrase");
                byte[] blob = read(a, u);
                if (blob.length == 0) {
                    cachedSalt = null;
                    remote = new JSONObject();
                } else {
                    remote = decrypt(blob, p);
                }
            } catch (javax.crypto.AEADBadTagException e) {
                fail(a, "Phrase secrète incorrecte (le fichier a été créé avec une autre phrase)", manual);
                return;
            } catch (SecurityException e) {
                fail(a, "Accès au fichier de synchro perdu : choisis-le à nouveau", manual);
                return;
            } catch (Exception e) {
                Log.e(TAG, "Synchro : lecture", e);
                fail(a, "Fichier de synchro inaccessible (hors ligne ?)", manual);
                return;
            }
            remotePasswords = remote.optJSONObject("passwords");
            remote.remove("passwords");
            final String forUi = remote.toString();
            ui.post(() -> {
                timeout = () -> {
                    running = false;
                    if (manual) a.notice("Synchronisation interrompue");
                };
                ui.postDelayed(timeout, 30_000);
                a.toJs("window.syncMerge&&syncMerge(" + org.json.JSONObject.quote(forUi) + ","
                        + org.json.JSONObject.quote(deviceId(a)) + "," + org.json.JSONObject.quote(deviceName()) + "," + manual + ")");
            });
        }).start();
    }

    /** Réponse de l'interface : données fusionnées (sans les mots de passe). */
    static void put(final MainActivity a, String merged) {
        if (!running) return;
        if (timeout != null) ui.removeCallbacks(timeout);
        final JSONObject j;
        try {
            j = new JSONObject(merged);
        } catch (Exception e) {
            running = false;
            return;
        }
        final boolean manual = j.optBoolean("manual", false);
        j.remove("manual");
        final Uri u = Uri.parse(prefs(a).getString("sync_uri", ""));
        new Thread(() -> {
            try {
                Passwords pw = Passwords.get(a);
                pw.mergeSync(remotePasswords);
                j.put("passwords", pw.toSync());
                j.put("v", 1);
                j.put("written", System.currentTimeMillis());
                String p = pass(a);
                if (p == null) throw new IllegalStateException("phrase");
                byte[] blob = encrypt(j, p);
                try (OutputStream out = a.getContentResolver().openOutputStream(u, "wt")) {
                    if (out == null) throw new IllegalStateException("écriture");
                    out.write(blob);
                }
                prefs(a).edit().putLong("sync_last", System.currentTimeMillis()).remove("sync_err").apply();
                running = false;
                ui.post(() -> a.call("onSyncStatus", status(a).toString()));
                if (manual) a.notice("Synchronisé ✓");
            } catch (Exception e) {
                Log.e(TAG, "Synchro : écriture", e);
                fail(a, "Écriture du fichier de synchro impossible", manual);
            }
        }).start();
    }

    private static void fail(MainActivity a, String msg, boolean manual) {
        running = false;
        prefs(a).edit().putString("sync_err", msg).apply();
        ui.post(() -> a.call("onSyncStatus", status(a).toString()));
        if (manual) a.notice(msg);
    }
}
