package app.airnavig;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import org.json.JSONObject;

/**
 * Mises à jour de l'appli AIR Navig.
 *
 * L'appli lit un petit fichier JSON publié par Rami :
 *   {"versionCode": 7, "versionName": "1.4", "url": "https://.../AIR-Navig.apk",
 *    "sha256": "<empreinte SHA-256 de l'APK>", "notes": "..."}
 *
 * Avant de proposer l'installation, l'APK téléchargé doit :
 *   1. avoir exactement l'empreinte SHA-256 annoncée ;
 *   2. porter le même nom de paquet et une version plus récente ;
 *   3. être signé avec LA MÊME clé que l'appli installée (clé AIR / Rami).
 * Un APK modifié ou signé par quelqu'un d'autre est donc toujours refusé,
 * même si le serveur de publication était compromis.
 *
 * UPDATE_URL : adresse HTTPS du fichier JSON. Laisser vide désactive la
 * vérification des mises à jour de l'appli (la liste de blocage, elle, se met
 * à jour toute seule).
 */
final class Updater {
    static final String UPDATE_URL = "";
    private static final long DAY = 24L * 3600 * 1000;

    private Updater() {
    }

    static boolean configured() {
        return UPDATE_URL.startsWith("https://");
    }

    static boolean due(Context ctx) {
        return configured() && System.currentTimeMillis() - ctx.getSharedPreferences("air", 0).getLong("appChecked", 0) > DAY;
    }

    static String status() {
        return configured() ? "vérification automatique chaque jour (signature contrôlée)" : "adresse de publication à renseigner par le développeur";
    }

    /** À appeler hors du thread principal. */
    static void check(final MainActivity a, final boolean manual) {
        if (!configured()) {
            if (manual) a.notice("Mises à jour de l'appli : adresse de publication non configurée");
            return;
        }
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(UPDATE_URL).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setInstanceFollowRedirects(false);
            if (c.getResponseCode() != 200) throw new Exception("http " + c.getResponseCode());
            InputStream in = c.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n, total = 0;
            while ((n = in.read(b)) > 0 && (total += n) < 65536) buf.write(b, 0, n);
            in.close();
            a.getSharedPreferences("air", 0).edit().putLong("appChecked", System.currentTimeMillis()).apply();
            final JSONObject j = new JSONObject(buf.toString("UTF-8"));
            final int vc = j.getInt("versionCode");
            final String apk = j.getString("url");
            final String sum = j.getString("sha256").toLowerCase(Locale.ROOT);
            final String name = j.optString("versionName", String.valueOf(vc));
            if (vc <= currentVersion(a)) {
                if (manual) a.notice("AIR Navig est à jour");
                return;
            }
            if (!apk.startsWith("https://") || !sum.matches("[0-9a-f]{64}")) {
                a.notice("Mise à jour refusée : annonce invalide");
                return;
            }
            a.runOnUiThread(new Runnable() {
                public void run() {
                    if (a.isFinishing()) return;
                    new AlertDialog.Builder(a)
                            .setTitle("Mise à jour AIR Navig " + name)
                            .setMessage(j.optString("notes", "Une nouvelle version est disponible.") + "\n\nElle sera vérifiée (empreinte et signature AIR) avant l'installation.")
                            .setPositiveButton("Télécharger", new android.content.DialogInterface.OnClickListener() { @Override public void onClick(android.content.DialogInterface d, int w) { download(a, apk, sum, vc); } })
                            .setNegativeButton("Plus tard", null)
                            .show();
                }
            });
        } catch (Exception e) {
            if (manual) a.notice("Vérification des mises à jour impossible");
        } finally {
            if (c != null) c.disconnect();
        }
    }

    static int currentVersion(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionCode;
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }

    private static void download(final MainActivity a, String apk, final String sum, final int vc) {
        try {
            final File dir = a.getExternalFilesDir(null);
            if (dir == null) throw new Exception("stockage");
            final File out = new File(dir, "AIR-Navig-update.apk");
            if (out.exists()) out.delete();
            DownloadManager dm = (DownloadManager) a.getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(apk));
            r.setDestinationUri(Uri.fromFile(out));
            r.setTitle("Mise à jour AIR Navig");
            r.setMimeType("application/vnd.android.package-archive");
            final long id = dm.enqueue(r);
            a.notice("Téléchargement de la mise à jour…");
            BroadcastReceiver br = new BroadcastReceiver() {
                public void onReceive(Context ctx, Intent i) {
                    if (i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return;
                    try {
                        a.unregisterReceiver(this);
                    } catch (Exception ignored) {
                    }
                    new Thread(new Runnable() { @Override public void run() { verifyAndInstall(a, out, sum, vc, id); } }).start();
                }
            };
            IntentFilter f = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
            if (Build.VERSION.SDK_INT >= 33) a.registerReceiver(br, f, Context.RECEIVER_EXPORTED);
            else a.registerReceiver(br, f);
        } catch (Exception e) {
            a.notice("Téléchargement impossible");
        }
    }

    private static void verifyAndInstall(final MainActivity a, File apk, String sum, int vc, final long id) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            FileInputStream in = new FileInputStream(apk);
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) md.update(b, 0, n);
            in.close();
            StringBuilder hex = new StringBuilder();
            for (byte x : md.digest()) hex.append(String.format(Locale.ROOT, "%02x", x));
            if (!hex.toString().equals(sum)) throw new SecurityException("Empreinte SHA-256 différente");
            PackageManager pm = a.getPackageManager();
            int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
            PackageInfo nu = pm.getPackageArchiveInfo(apk.getPath(), flags);
            PackageInfo me = pm.getPackageInfo(a.getPackageName(), flags);
            if (nu == null || !a.getPackageName().equals(nu.packageName)) throw new SecurityException("Paquet inattendu");
            if (nu.versionCode < vc || nu.versionCode <= me.versionCode) throw new SecurityException("Version inattendue");
            if (!Arrays.equals(certs(nu), certs(me))) throw new SecurityException("Signature différente de celle d'AIR");
            final Uri uri = ((DownloadManager) a.getSystemService(Context.DOWNLOAD_SERVICE)).getUriForDownloadedFile(id);
            a.runOnUiThread(new Runnable() { @Override public void run() {
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setDataAndType(uri, "application/vnd.android.package-archive");
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                    a.startActivity(i);
                } catch (Exception e) {
                    a.notice("Installation impossible");
                }
            } });
        } catch (SecurityException e) {
            apk.delete();
            a.notice("Mise à jour refusée : " + e.getMessage());
        } catch (Exception e) {
            apk.delete();
            a.notice("Vérification de la mise à jour impossible");
        }
    }

    @SuppressWarnings("deprecation")
    private static byte[] certs(PackageInfo pi) throws Exception {
        Signature[] s;
        if (Build.VERSION.SDK_INT >= 28 && pi.signingInfo != null) {
            s = pi.signingInfo.hasMultipleSigners() ? pi.signingInfo.getApkContentsSigners() : pi.signingInfo.getSigningCertificateHistory();
            if (!pi.signingInfo.hasMultipleSigners() && s != null && s.length > 0) s = new Signature[]{s[s.length - 1]};
        } else {
            s = pi.signatures;
        }
        if (s == null || s.length == 0) throw new SecurityException("APK non signé");
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (Signature x : s) md.update(x.toByteArray());
        return md.digest();
    }

    /** Empreinte SHA-256 du certificat qui signe l'appli installée (affichée dans « À propos »). */
    @SuppressWarnings("deprecation")
    static String signerFingerprint(Context ctx) {
        try {
            PackageManager pm = ctx.getPackageManager();
            Signature[] s;
            if (Build.VERSION.SDK_INT >= 28) {
                PackageInfo pi = pm.getPackageInfo(ctx.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
                s = pi.signingInfo.hasMultipleSigners() ? pi.signingInfo.getApkContentsSigners() : pi.signingInfo.getSigningCertificateHistory();
            } else {
                s = pm.getPackageInfo(ctx.getPackageName(), PackageManager.GET_SIGNATURES).signatures;
            }
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s[s.length - 1].toByteArray());
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < d.length; i++) {
                if (i > 0) hex.append(':');
                hex.append(String.format(Locale.ROOT, "%02X", d[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            return "?";
        }
    }
}
