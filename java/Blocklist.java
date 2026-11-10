package app.airnavig;

import android.content.Context;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Pattern;

/**
 * Liste de blocage AIR Navig.
 *
 * Origine documentée : StevenBlack/hosts (licence MIT)
 *   https://github.com/StevenBlack/hosts
 * Une copie datée est embarquée dans l'APK (assets/blocklist.txt) et l'appli
 * la remet à jour automatiquement (au plus une fois par semaine) depuis
 * BLOCKLIST_URL, en HTTPS, avec contrôles d'intégrité (format, taille,
 * nombre de domaines) avant de remplacer la copie locale.
 */
final class Blocklist {
    static final String SOURCE_NAME = "StevenBlack/hosts (licence MIT)";
    static final String SOURCE_HOME = "https://github.com/StevenBlack/hosts";
    static final String BLOCKLIST_URL = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts";
    private static final String FILE = "blocklist.txt";
    private static final long WEEK = 7L * 24 * 3600 * 1000;
    private static final int MAX_BYTES = 12 * 1024 * 1024;
    private static final int MIN_DOMAINS = 20000;
    private static final int MAX_DOMAINS = 600000;
    private static final Pattern HOST = Pattern.compile("^[a-z0-9_]([a-z0-9_-]{0,62})(\\.[a-z0-9_]([a-z0-9_-]{0,62}))+$");

    static volatile Set<String> hosts = new HashSet<String>();
    static volatile String date = "?";
    static volatile String sha256 = "";
    static volatile String origin = "intégrée à l'appli";
    static volatile boolean updating = false;
    private static volatile boolean loaded = false;

    private Blocklist() {
    }

    /** Domaine bloqué par la liste (correspondance exacte, comme un fichier hosts). */
    static boolean contains(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        return hosts.contains(h);
    }

    static boolean isLoaded() {
        return loaded && !hosts.isEmpty();
    }

    static synchronized void ensureLoaded(Context ctx) {
        if (loaded) return;
        loaded = true;
        File f = new File(ctx.getFilesDir(), FILE);
        if (f.isFile()) {
            try {
                if (read(new FileInputStream(f), "mise à jour automatique")) return;
            } catch (Exception ignored) {
            }
        }
        try {
            read(ctx.getAssets().open(FILE), "intégrée à l'appli");
        } catch (Exception ignored) {
        }
    }

    private static boolean read(InputStream in, String from) throws Exception {
        Set<String> set = new HashSet<String>(120000);
        String d = "?", sum = "";
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        try {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("#")) {
                    if (line.startsWith("# Date:")) d = line.substring(7).trim();
                    else if (line.startsWith("# SHA-256:")) sum = line.substring(10).trim();
                    continue;
                }
                String l = line.trim();
                if (!l.isEmpty()) set.add(l);
            }
        } finally {
            r.close();
        }
        if (set.size() < MIN_DOMAINS) return false;
        hosts = set;
        MainActivity.verdicts.clear();
        date = d;
        sha256 = sum;
        origin = from;
        return true;
    }

    static boolean due(Context ctx) {
        long last = ctx.getSharedPreferences("air", 0).getLong("blocklistChecked", 0);
        return System.currentTimeMillis() - last > WEEK;
    }

    /** Télécharge, vérifie et installe une nouvelle liste. Retourne un message lisible. */
    static synchronized String update(Context ctx) {
        if (updating) return "Mise à jour déjà en cours";
        updating = true;
        HttpURLConnection c = null;
        try {
            URL url = new URL(BLOCKLIST_URL);
            if (!"https".equals(url.getProtocol())) return "Adresse de mise à jour non sécurisée";
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "AIR-Navig");
            if (c.getResponseCode() != 200) return "Serveur de la liste indisponible (" + c.getResponseCode() + ")";
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream(4 * 1024 * 1024);
            byte[] b = new byte[16384];
            int n, total = 0;
            while ((n = in.read(b)) > 0) {
                total += n;
                if (total > MAX_BYTES) return "Liste refusée : taille anormale";
                md.update(b, 0, n);
                buf.write(b, 0, n);
            }
            in.close();
            String text = buf.toString("UTF-8");
            String srcDate = null;
            Set<String> set = new HashSet<String>(120000);
            for (String line : text.split("\n")) {
                String l = line.trim();
                if (l.startsWith("# Date:") && srcDate == null) srcDate = l.substring(7).trim();
                if (l.isEmpty() || l.startsWith("#")) continue;
                int hash = l.indexOf('#');
                if (hash > 0) l = l.substring(0, hash).trim();
                String[] parts = l.split("\\s+");
                if (parts.length != 2) continue;
                if (!"0.0.0.0".equals(parts[0])) continue;
                String h = parts[1].toLowerCase(Locale.ROOT);
                if ("0.0.0.0".equals(h) || h.length() > 253 || !HOST.matcher(h).matches()) continue;
                set.add(h);
            }
            if (set.size() < MIN_DOMAINS || set.size() > MAX_DOMAINS) {
                return "Liste refusée : contenu inattendu (" + set.size() + " domaines)";
            }
            StringBuilder hex = new StringBuilder();
            for (byte x : md.digest()) hex.append(String.format(Locale.ROOT, "%02x", x));
            if (srcDate == null) {
                SimpleDateFormat f = new SimpleDateFormat("dd MMMM yyyy HH:mm", Locale.FRANCE);
                f.setTimeZone(TimeZone.getTimeZone("UTC"));
                srcDate = f.format(new Date()) + " (UTC)";
            }
            File tmp = new File(ctx.getFilesDir(), FILE + ".tmp");
            Writer w = new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8");
            try {
                w.write("# Source: " + SOURCE_NAME + " - " + SOURCE_HOME + "\n");
                w.write("# Date: " + srcDate + "\n");
                w.write("# SHA-256: " + hex + "\n");
                for (String h : set) {
                    w.write(h);
                    w.write('\n');
                }
            } finally {
                w.close();
            }
            File dst = new File(ctx.getFilesDir(), FILE);
            if (!tmp.renameTo(dst)) return "Impossible d'enregistrer la liste";
            hosts = set;
            MainActivity.verdicts.clear();
            date = srcDate;
            sha256 = hex.toString();
            origin = "mise à jour automatique";
            ctx.getSharedPreferences("air", 0).edit().putLong("blocklistChecked", System.currentTimeMillis()).apply();
            return "Liste de blocage à jour : " + set.size() + " domaines";
        } catch (Exception e) {
            return "Mise à jour de la liste impossible (réseau ?)";
        } finally {
            if (c != null) c.disconnect();
            updating = false;
        }
    }
}
