package app.airnavig;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;

/**
 * Écran « Mots de passe » : liste, recherche, copie, suppression, import et export CSV.
 * Protégé par le verrouillage de l'appareil (code, schéma, empreinte) et interdit aux captures d'écran.
 */
public class PasswordsActivity extends Activity {
    private static final int REQ_UNLOCK = 1, REQ_IMPORT = 2, REQ_EXPORT = 3;
    private LinearLayout list;
    private EditText search;
    private TextView countView;
    private boolean unlocked;
    private boolean dark;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        String theme = getSharedPreferences("air", 0).getString("theme", "dark");
        dark = !"light".equals(theme);
        int bg = dark ? 0xFF0B0B10 : 0xFFF7F7FA;
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        if (!dark) getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        build(bg);
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (b != null && b.getBoolean("unlocked")) {
            unlocked = true;
            refresh();
        } else if (km != null && km.isDeviceSecure()) {
            @SuppressWarnings("deprecation")
            Intent i = km.createConfirmDeviceCredentialIntent("Mots de passe AIR Navig", "Déverrouille pour voir tes mots de passe");
            if (i != null) startActivityForResult(i, REQ_UNLOCK);
            else unlock();
        } else {
            unlock();
            toast("Astuce : protège ton téléphone par un code pour verrouiller tes mots de passe");
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean("unlocked", unlocked);
    }

    private void unlock() {
        unlocked = true;
        refresh();
    }

    private int fg() {
        return dark ? 0xFFF1F1F6 : 0xFF15151C;
    }

    private int muted() {
        return dark ? 0xFF9A9AB0 : 0xFF61616F;
    }

    private int card() {
        return dark ? 0xFF17171F : 0xFFFFFFFF;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private Button btn(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private void build(int bg) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        root.setPadding(dp(16), dp(14), dp(16), 0);

        TextView title = new TextView(this);
        title.setText("Mots de passe");
        title.setTextColor(fg());
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        root.addView(title);

        countView = new TextView(this);
        countView.setTextColor(muted());
        countView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        countView.setPadding(0, dp(2), 0, dp(10));
        root.addView(countView);

        LinearLayout actions = new LinearLayout(this);
        actions.addView(btn("Importer (CSV)", v -> pickImport()), new LinearLayout.LayoutParams(0, -2, 1f));
        actions.addView(btn("Exporter", v -> confirmExport()), new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(actions);

        search = new EditText(this);
        search.setHint("Rechercher un site ou un identifiant");
        search.setSingleLine(true);
        search.setTextColor(fg());
        search.setHintTextColor(muted());
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b2, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b2, int c) {}
            @Override public void afterTextChanged(Editable s) { refresh(); }
        });
        root.addView(search);

        ScrollView sv = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(6), 0, dp(24));
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
    }

    private void refresh() {
        if (!unlocked || list == null) return;
        Passwords pw = Passwords.get(this);
        List<Passwords.Login> all = pw.all();
        countView.setText(all.size() + " identifiant" + (all.size() > 1 ? "s" : "") + " · chiffrés sur ce téléphone"
                + (Sync.configured(this) ? " · synchronisés" : ""));
        String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        list.removeAllViews();
        int shown = 0;
        for (final Passwords.Login l : all) {
            if (!q.isEmpty() && !l.host().toLowerCase(Locale.ROOT).contains(q) && !(l.user == null ? "" : l.user).toLowerCase(Locale.ROOT).contains(q)) continue;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setBackgroundColor(card());
            row.setPadding(dp(14), dp(12), dp(14), dp(12));
            TextView h = new TextView(this);
            h.setText(l.host());
            h.setTextColor(fg());
            h.setTypeface(Typeface.DEFAULT_BOLD);
            h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            TextView u = new TextView(this);
            u.setText(l.user == null || l.user.isEmpty() ? "(sans identifiant)" : l.user);
            u.setTextColor(muted());
            u.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            row.addView(h);
            row.addView(u);
            row.setOnClickListener(v -> details(l));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(8);
            list.addView(row, lp);
            shown++;
        }
        if (shown == 0) {
            TextView t = new TextView(this);
            t.setText(all.isEmpty() ? "Aucun mot de passe pour l'instant.\n\nAIR Navig te proposera de les enregistrer quand tu te connectes à un site. Tu peux aussi importer un fichier CSV exporté depuis Chrome, Firefox, Bitwarden ou un autre gestionnaire."
                    : "Aucun résultat.");
            t.setTextColor(muted());
            t.setGravity(Gravity.CENTER_HORIZONTAL);
            t.setPadding(dp(8), dp(28), dp(8), 0);
            list.addView(t);
        }
    }

    private void copy(String label, String value, boolean sensitive) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm == null) return;
        ClipData clip = ClipData.newPlainText(label, value == null ? "" : value);
        if (sensitive) {
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean(Build.VERSION.SDK_INT >= 33 ? ClipDescription.EXTRA_IS_SENSITIVE : "android.content.extra.IS_SENSITIVE", true);
            clip.getDescription().setExtras(extras);
        }
        cm.setPrimaryClip(clip);
        toast(label + " copié");
    }

    private void details(final Passwords.Login l) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(8), dp(22), 0);
        final TextView user = new TextView(this);
        user.setText("Identifiant : " + (l.user == null || l.user.isEmpty() ? "—" : l.user));
        user.setTextIsSelectable(true);
        final TextView pass = new TextView(this);
        pass.setText("Mot de passe : ••••••••");
        pass.setPadding(0, dp(8), 0, 0);
        final TextView site = new TextView(this);
        site.setText(l.origin);
        site.setTextColor(muted());
        site.setPadding(0, dp(8), 0, 0);
        Button show = btn("Afficher", null);
        show.setOnClickListener(v -> {
            boolean hidden = pass.getText().toString().endsWith("••••••••");
            pass.setText("Mot de passe : " + (hidden ? l.pass : "••••••••"));
            show.setText(hidden ? "Masquer" : "Afficher");
        });
        Button cu = btn("Copier l'identifiant", v -> copy("Identifiant", l.user, false));
        Button cp = btn("Copier le mot de passe", v -> copy("Mot de passe", l.pass, true));
        box.addView(user);
        box.addView(pass);
        box.addView(site);
        box.addView(show);
        box.addView(cu);
        box.addView(cp);
        new AlertDialog.Builder(this)
                .setTitle(l.host())
                .setView(box)
                .setPositiveButton("Fermer", null)
                .setNeutralButton("Ouvrir le site", (d, w) -> {
                    Intent i = new Intent(this, MainActivity.class);
                    i.setAction(Intent.ACTION_VIEW);
                    i.setData(Uri.parse(l.origin));
                    startActivity(i);
                })
                .setNegativeButton("Supprimer", (d, w) -> new AlertDialog.Builder(this)
                        .setTitle("Supprimer cet identifiant ?")
                        .setMessage(l.host() + (l.user == null || l.user.isEmpty() ? "" : " · " + l.user))
                        .setPositiveButton("Supprimer", (d2, w2) -> {
                            Passwords.get(this).delete(l.guid);
                            refresh();
                        })
                        .setNegativeButton("Annuler", null)
                        .show())
                .show();
    }

    private void pickImport() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/csv", "text/comma-separated-values", "text/plain", "application/csv", "application/vnd.ms-excel", "application/octet-stream"});
        try {
            startActivityForResult(i, REQ_IMPORT);
        } catch (Exception e) {
            toast("Aucun gestionnaire de fichiers disponible");
        }
    }

    private void confirmExport() {
        new AlertDialog.Builder(this)
                .setTitle("Exporter en clair ?")
                .setMessage("Le fichier CSV contiendra tous tes mots de passe NON chiffrés. Garde-le en lieu sûr et supprime-le après usage.")
                .setPositiveButton("Exporter", (d, w) -> {
                    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("text/csv");
                    i.putExtra(Intent.EXTRA_TITLE, "AIR-Navig-mots-de-passe.csv");
                    try {
                        startActivityForResult(i, REQ_EXPORT);
                    } catch (Exception e) {
                        toast("Aucun gestionnaire de fichiers disponible");
                    }
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_UNLOCK) {
            if (res == RESULT_OK) unlock();
            else finish();
            return;
        }
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri u = data.getData();
        if (req == REQ_IMPORT) {
            new Thread(() -> {
                String msg;
                try (InputStream in = getContentResolver().openInputStream(u)) {
                    int n = Passwords.get(this).importCsv(in);
                    msg = n + " identifiant" + (n > 1 ? "s" : "") + " importé" + (n > 1 ? "s" : "");
                } catch (IllegalArgumentException e) {
                    msg = "Fichier non reconnu : il faut un CSV avec des colonnes url et password";
                } catch (Exception e) {
                    msg = "Import impossible";
                }
                final String m = msg;
                runOnUiThread(() -> {
                    toast(m);
                    refresh();
                });
            }).start();
        } else if (req == REQ_EXPORT) {
            new Thread(() -> {
                String msg;
                try (OutputStream out = getContentResolver().openOutputStream(u, "wt")) {
                    if (out == null) throw new IllegalStateException();
                    int n = Passwords.get(this).exportCsv(out);
                    msg = n + " identifiant" + (n > 1 ? "s" : "") + " exporté" + (n > 1 ? "s" : "");
                } catch (Exception e) {
                    msg = "Export impossible";
                }
                final String m = msg;
                runOnUiThread(() -> toast(m));
            }).start();
        }
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }
}
