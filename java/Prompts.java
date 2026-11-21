package app.airnavig;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.net.Uri;
import android.widget.EditText;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.Autocomplete;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSession.PromptDelegate;

/**
 * Fenêtres demandées par les pages (alert, confirm, prompt, listes déroulantes, dates, fichiers…).
 * Le WebView les affichait lui-même ; avec Gecko, c'est à l'appli de les dessiner.
 */
final class Prompts implements PromptDelegate {
    private final MainActivity act;
    private GeckoResult<PromptResponse> fileResult;
    private FilePrompt filePrompt;

    Prompts(MainActivity act) {
        this.act = act;
    }

    private boolean gone() {
        return act.isFinishing() || act.isDestroyed();
    }

    private static String clip(@Nullable String s) {
        if (s == null) return "";
        return s.length() > 1000 ? s.substring(0, 1000) + "…" : s;
    }

    @Nullable
    @Override
    public GeckoResult<PromptResponse> onAlertPrompt(@NonNull GeckoSession session, @NonNull AlertPrompt prompt) {
        if (gone()) return GeckoResult.fromValue(prompt.dismiss());
        final GeckoResult<PromptResponse> res = new GeckoResult<>();
        new AlertDialog.Builder(act)
                .setTitle(prompt.title)
                .setMessage(clip(prompt.message))
                .setPositiveButton(android.R.string.ok, null)
                .setOnDismissListener(d -> res.complete(prompt.dismiss()))
                .show();
        return res;
    }

    @Nullable
    @Override
    public GeckoResult<PromptResponse> onButtonPrompt(@NonNull GeckoSession session, @NonNull ButtonPrompt prompt) {
        if (gone()) return GeckoResult.fromValue(prompt.dismiss());
        final GeckoResult<PromptResponse> res = new GeckoResult<>();
        final boolean[] done = {false};
        new AlertDialog.Builder(act)
                .setTitle(prompt.title)
                .setMessage(clip(prompt.message))
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    done[0] = true;
                    res.complete(prompt.confirm(ButtonPrompt.Type.POSITIVE));
                })
                .setNegativeButton(android.R.string.cancel, (d, w) -> {
                    done[0] = true;
                    res.complete(prompt.confirm(ButtonPrompt.Type.NEGATIVE));
                })
                .setOnDismissListener(d -> {
                    if (!done[0]) res.complete(prompt.dismiss());
                })
                .show();
        return res;
    }

    @Nullable
    @Override
    public GeckoResult<PromptResponse> onTextPrompt(@NonNull GeckoSession session, @NonNull TextPrompt prompt) {
        if (gone()) return GeckoResult.fromValue(prompt.dismiss());
        final GeckoResult<PromptResponse> res = new GeckoResult<>();
        final EditText input = new EditText(act);
        input.setText(prompt.defaultValue == null ? "" : prompt.defaultValue);
        final boolean[] done = {false};
        new AlertDialog.Builder(act)
                .setTitle(prompt.title)
                .setMessage(clip(prompt.message))
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    done[0] = true;
                    res.complete(prompt.confirm(input.getText().toString()));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .setOnDismissListener(d -> {
                    if (!done[0]) res.complete(prompt.dismiss());
                })
                .show();
        return res;
    }

    /** Listes déroulantes <select> et menus. */
    @Nullable
    @Override
    public GeckoResult<PromptResponse> onChoicePrompt(@NonNull GeckoSession session, @NonNull ChoicePrompt prompt) {
        if (gone()) return GeckoResult.fromValue(prompt.dismiss());
        final List<ChoicePrompt.Choice> flat = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        flatten(prompt.choices, flat, labels, "");
        if (flat.isEmpty()) return GeckoResult.fromValue(prompt.dismiss());
        final GeckoResult<PromptResponse> res = new GeckoResult<>();
        final boolean[] done = {false};
        String[] items = labels.toArray(new String[0]);
        AlertDialog.Builder b = new AlertDialog.Builder(act).setTitle(prompt.title);
        if (prompt.type == ChoicePrompt.Type.MULTIPLE) {
            final boolean[] checked = new boolean[flat.size()];
            for (int i = 0; i < checked.length; i++) checked[i] = flat.get(i).selected;
            b.setMultiChoiceItems(items, checked, (d, which, isChecked) -> {
                if (flat.get(which).disabled) ((AlertDialog) d).getListView().setItemChecked(which, !isChecked);
                else checked[which] = isChecked;
            });
            b.setPositiveButton(android.R.string.ok, (d, w) -> {
                done[0] = true;
                List<String> ids = new ArrayList<>();
                for (int i = 0; i < checked.length; i++) if (checked[i]) ids.add(flat.get(i).id);
                res.complete(prompt.confirm(ids.toArray(new String[0])));
            });
            b.setNegativeButton(android.R.string.cancel, null);
        } else {
            int sel = -1;
            for (int i = 0; i < flat.size(); i++) if (flat.get(i).selected) sel = i;
            b.setSingleChoiceItems(items, sel, (d, which) -> {
                ChoicePrompt.Choice c = flat.get(which);
                if (c.disabled || c.separator) return;
                done[0] = true;
                res.complete(prompt.confirm(c));
                d.dismiss();
            });
        }
        b.setOnDismissListener(d -> {
            if (!done[0]) res.complete(prompt.dismiss());
        });
        b.show();
        return res;
    }

    private static void flatten(@Nullable ChoicePrompt.Choice[] in, List<ChoicePrompt.Choice> out, List<String> labels, String indent) {
        if (in == null) return;
        for (ChoicePrompt.Choice c : in) {
            if (c.items != null) {
                // Groupe <optgroup> : son titre n'est pas sélectionnable, ses options sont décalées.
                flatten(c.items, out, labels, indent + "   ");
            } else if (!c.separator) {
                out.add(c);
                labels.add(indent + c.label);
            }
        }
    }

    @Nullable
    @Override
    public GeckoResult<PromptResponse> onColorPrompt(@NonNull GeckoSession session, @NonNull ColorPrompt prompt) {
        if (gone()) return GeckoResult.fromValue(prompt.dismiss());
        final GeckoResult<PromptResponse> res = new GeckoResult<>();
        final EditText input = new EditText(act);
        input.setText(prompt.defaultValue == null ? "#000000" : prompt.defaultValue);
        final boolean[] done = {false};
        new AlertDialog.Builder(act)
                .setTitle("Couleur (#rrggbb)")
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String v = input.getText().toString().trim();
                    if (!v.matches("^#[0-9a-fA-F]{6}$")) return;
                    done[0] = true;
                    res.complete(prompt.confirm(v.toLowerCase(Locale.ROOT)));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .setOnDismissListener(d -> {
                    if (!done[0]) res.complete(prompt.dismiss());
                })
                .show();
        return res;
    }

    /** Champs date / heure : sélecteurs Android. */
    @Nullable
    @Override
    public GeckoResult<PromptResponse> onDateTimePrompt(@NonNull GeckoSession session, @NonNull DateTimePrompt prompt) {
        if (gone()) return GeckoResult.fromValue(prompt.dismiss());
        final GeckoResult<PromptResponse> res = new GeckoResult<>();
        final boolean[] done = {false};
        final Calendar cal = Calendar.getInstance();
        String def = prompt.defaultValue == null ? "" : prompt.defaultValue;
        try {
            if (def.matches("^\\d{4}-\\d{2}-\\d{2}.*")) {
                cal.set(Integer.parseInt(def.substring(0, 4)), Integer.parseInt(def.substring(5, 7)) - 1, Integer.parseInt(def.substring(8, 10)));
            }
            int t = def.indexOf(':');
            if (t >= 2) {
                cal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(def.substring(t - 2, t)));
                cal.set(Calendar.MINUTE, Integer.parseInt(def.substring(t + 1, t + 3)));
            }
        } catch (Exception ignored) {
        }
        if (prompt.type == DateTimePrompt.Type.TIME) {
            TimePickerDialog d = new TimePickerDialog(act, (v, h, m) -> {
                done[0] = true;
                res.complete(prompt.confirm(String.format(Locale.ROOT, "%02d:%02d", h, m)));
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true);
            d.setOnDismissListener(x -> {
                if (!done[0]) res.complete(prompt.dismiss());
            });
            d.show();
            return res;
        }
        DatePickerDialog d = new DatePickerDialog(act, (v, y, mo, day) -> {
            String date = String.format(Locale.ROOT, "%04d-%02d-%02d", y, mo + 1, day);
            if (prompt.type == DateTimePrompt.Type.MONTH) {
                done[0] = true;
                res.complete(prompt.confirm(String.format(Locale.ROOT, "%04d-%02d", y, mo + 1)));
            } else if (prompt.type == DateTimePrompt.Type.WEEK) {
                Calendar c = Calendar.getInstance();
                c.setMinimalDaysInFirstWeek(4);
                c.setFirstDayOfWeek(Calendar.MONDAY);
                c.set(y, mo, day);
                done[0] = true;
                res.complete(prompt.confirm(String.format(Locale.ROOT, "%04d-W%02d", c.getWeekYear(), c.get(Calendar.WEEK_OF_YEAR))));
            } else if (prompt.type == DateTimePrompt.Type.DATETIME_LOCAL) {
                done[0] = true;
                new TimePickerDialog(act, (tv, h, m) -> res.complete(prompt.confirm(date + String.format(Locale.ROOT, "T%02d:%02d", h, m))),
                        cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show();
            } else {
                done[0] = true;
                res.complete(prompt.confirm(date));
            }
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH));
        d.setOnDismissListener(x -> {
            if (!done[0]) res.complete(prompt.dismiss());
        });
        d.show();
        return res;
    }

    /** <input type="file"> : sélecteur de documents Android. */
    @Nullable
    @Override
    public GeckoResult<PromptResponse> onFilePrompt(@NonNull GeckoSession session, @NonNull FilePrompt prompt) {
        if (gone() || prompt.type == FilePrompt.Type.FOLDER) return GeckoResult.fromValue(prompt.dismiss());
        if (fileResult != null) fileResult.complete(filePrompt.dismiss());
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        String[] mimes = prompt.mimeTypes;
        if (mimes != null && mimes.length == 1 && mimes[0] != null && mimes[0].contains("/")) i.setType(mimes[0]);
        else {
            i.setType("*/*");
            if (mimes != null && mimes.length > 1) {
                List<String> ok = new ArrayList<>();
                for (String m : mimes) if (m != null && m.contains("/")) ok.add(m);
                if (!ok.isEmpty()) i.putExtra(Intent.EXTRA_MIME_TYPES, ok.toArray(new String[0]));
            }
        }
        if (prompt.type == FilePrompt.Type.MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        fileResult = new GeckoResult<>();
        filePrompt = prompt;
        try {
            act.startActivityForResult(Intent.createChooser(i, null), MainActivity.REQ_FILE);
        } catch (Exception e) {
            GeckoResult<PromptResponse> r = fileResult;
            fileResult = null;
            filePrompt = null;
            r.complete(prompt.dismiss());
            return r;
        }
        return fileResult;
    }

    void onFileResult(int result, @Nullable Intent data) {
        if (fileResult == null) return;
        GeckoResult<PromptResponse> r = fileResult;
        FilePrompt p = filePrompt;
        fileResult = null;
        filePrompt = null;
        if (result != Activity.RESULT_OK || data == null) {
            r.complete(p.dismiss());
            return;
        }
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int k = 0; k < data.getClipData().getItemCount(); k++) uris.add(data.getClipData().getItemAt(k).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) r.complete(p.dismiss());
        else if (p.type == FilePrompt.Type.MULTIPLE) r.complete(p.confirm(act, uris.toArray(new Uri[0])));
        else r.complete(p.confirm(act, uris.get(0)));
    }

    /** Fenêtres surgissantes : selon le réglage « Fenêtres pop-up ». */
    @Nullable
    @Override
    public GeckoResult<PromptResponse> onPopupPrompt(@NonNull GeckoSession session, @NonNull PopupPrompt prompt) {
        return GeckoResult.fromValue(prompt.confirm(act.allowPopups ? AllowOrDeny.ALLOW : AllowOrDeny.DENY));
    }

    @Nullable
    @Override
    public GeckoResult<PromptResponse> onBeforeUnloadPrompt(@NonNull GeckoSession session, @NonNull BeforeUnloadPrompt prompt) {
        if (gone()) return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW));
        final GeckoResult<PromptResponse> res = new GeckoResult<>();
        final boolean[] done = {false};
        new AlertDialog.Builder(act)
                .setTitle("Quitter cette page ?")
                .setMessage("Les modifications que tu as faites pourraient ne pas être enregistrées.")
                .setPositiveButton("Quitter", (d, w) -> {
                    done[0] = true;
                    res.complete(prompt.confirm(AllowOrDeny.ALLOW));
                })
                .setNegativeButton("Rester", null)
                .setOnDismissListener(d -> {
                    if (!done[0]) res.complete(prompt.confirm(AllowOrDeny.DENY));
                })
                .show();
        return res;
    }

    @Nullable
    @Override
    public GeckoResult<PromptResponse> onRepostConfirmPrompt(@NonNull GeckoSession session, @NonNull RepostConfirmPrompt prompt) {
        if (gone()) return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.DENY));
        final GeckoResult<PromptResponse> res = new GeckoResult<>();
        final boolean[] done = {false};
        new AlertDialog.Builder(act)
                .setTitle("Renvoyer le formulaire ?")
                .setMessage("Cette page contient des données déjà envoyées (achat, message…). Les renvoyer peut répéter l'action.")
                .setPositiveButton("Renvoyer", (d, w) -> {
                    done[0] = true;
                    res.complete(prompt.confirm(AllowOrDeny.ALLOW));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .setOnDismissListener(d -> {
                    if (!done[0]) res.complete(prompt.confirm(AllowOrDeny.DENY));
                })
                .show();
        return res;
    }

    /** Authentification HTTP (routeurs, serveurs locaux, intranets) : identifiant + mot de passe. */
    @Nullable
    @Override
    public GeckoResult<PromptResponse> onAuthPrompt(@NonNull GeckoSession session, @NonNull AuthPrompt prompt) {
        if (gone()) return GeckoResult.fromValue(prompt.dismiss());
        final boolean onlyPass = (prompt.authOptions.flags & AuthPrompt.AuthOptions.Flags.ONLY_PASSWORD) != 0;
        final GeckoResult<PromptResponse> res = new GeckoResult<>();
        final boolean[] done = {false};
        android.widget.LinearLayout box = new android.widget.LinearLayout(act);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int p = act.px(20);
        box.setPadding(p, p / 2, p, 0);
        final EditText user = new EditText(act);
        user.setHint("Identifiant");
        user.setSingleLine(true);
        if (prompt.authOptions.username != null) user.setText(prompt.authOptions.username);
        final EditText pass = new EditText(act);
        pass.setHint("Mot de passe");
        pass.setSingleLine(true);
        pass.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if (!onlyPass) box.addView(user);
        box.addView(pass);
        String host = prompt.authOptions.uri == null ? null : Uri.parse(prompt.authOptions.uri).getHost();
        String msg = clip(prompt.message);
        if ((prompt.authOptions.flags & AuthPrompt.AuthOptions.Flags.PREVIOUS_FAILED) != 0) msg = "Identifiant ou mot de passe refusé.\n\n" + msg;
        new AlertDialog.Builder(act)
                .setTitle(host == null ? "Connexion demandée" : host + " demande une connexion")
                .setMessage(msg)
                .setView(box)
                .setPositiveButton("Se connecter", (d, w) -> {
                    done[0] = true;
                    res.complete(onlyPass ? prompt.confirm(pass.getText().toString())
                            : prompt.confirm(user.getText().toString(), pass.getText().toString()));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .setOnDismissListener(d -> {
                    if (!done[0]) res.complete(prompt.dismiss());
                })
                .show();
        return res;
    }

    /* ======================= Mots de passe ======================= */

    /** Gecko a détecté un identifiant tapé dans un formulaire : proposer de l'enregistrer. */
    @Nullable
    @Override
    public GeckoResult<PromptResponse> onLoginSave(@NonNull GeckoSession session,
                                                   @NonNull AutocompleteRequest<Autocomplete.LoginSaveOption> request) {
        if (gone() || act.incognito() || request.options.length == 0) return GeckoResult.fromValue(request.dismiss());
        final Autocomplete.LoginSaveOption opt = request.options[0];
        final Autocomplete.LoginEntry e = opt.value;
        final Passwords pw = Passwords.get(act);
        if (pw.isNever(e.origin)) return GeckoResult.fromValue(request.dismiss());
        final GeckoResult<PromptResponse> res = new GeckoResult<>();
        final boolean[] done = {false};
        boolean update = e.guid != null && !e.guid.isEmpty();
        String host = Uri.parse(e.origin == null ? "" : e.origin).getHost();
        new AlertDialog.Builder(act)
                .setTitle(update ? "Mettre à jour le mot de passe ?" : "Enregistrer le mot de passe ?")
                .setMessage((host == null ? e.origin : host) + (e.username == null || e.username.isEmpty() ? "" : "\nIdentifiant : " + e.username)
                        + "\n\nIl sera chiffré sur ce téléphone.")
                .setPositiveButton(update ? "Mettre à jour" : "Enregistrer", (d, w) -> {
                    done[0] = true;
                    res.complete(request.confirm(opt));
                })
                .setNeutralButton("Jamais pour ce site", (d, w) -> {
                    done[0] = true;
                    pw.setNever(e.origin);
                    res.complete(request.dismiss());
                })
                .setNegativeButton("Pas maintenant", null)
                .setOnDismissListener(d -> {
                    if (!done[0]) res.complete(request.dismiss());
                })
                .show();
        return res;
    }

    /** Plusieurs identifiants pour ce site : choisir lequel remplir. */
    @Nullable
    @Override
    public GeckoResult<PromptResponse> onLoginSelect(@NonNull GeckoSession session,
                                                     @NonNull AutocompleteRequest<Autocomplete.LoginSelectOption> request) {
        if (gone() || request.options.length == 0) return GeckoResult.fromValue(request.dismiss());
        final GeckoResult<PromptResponse> res = new GeckoResult<>();
        final boolean[] done = {false};
        String[] names = new String[request.options.length];
        for (int i = 0; i < names.length; i++) {
            Autocomplete.LoginEntry e = request.options[i].value;
            names[i] = e.username == null || e.username.isEmpty() ? "(sans identifiant)" : e.username;
        }
        new AlertDialog.Builder(act)
                .setTitle("Se connecter avec")
                .setItems(names, (d, w) -> {
                    done[0] = true;
                    res.complete(request.confirm(request.options[w]));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .setOnDismissListener(d -> {
                    if (!done[0]) res.complete(request.dismiss());
                })
                .show();
        return res;
    }

    /** navigator.share() : partage Android. */
    @Nullable
    @Override
    public GeckoResult<PromptResponse> onSharePrompt(@NonNull GeckoSession session, @NonNull SharePrompt prompt) {
        StringBuilder sb = new StringBuilder();
        if (prompt.text != null) sb.append(prompt.text);
        if (prompt.uri != null) sb.append(sb.length() > 0 ? "\n" : "").append(prompt.uri);
        if (sb.length() == 0) return GeckoResult.fromValue(prompt.confirm(SharePrompt.Result.FAILURE));
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, sb.toString());
        if (prompt.title != null) i.putExtra(Intent.EXTRA_SUBJECT, prompt.title);
        try {
            act.startActivity(Intent.createChooser(i, null));
            return GeckoResult.fromValue(prompt.confirm(SharePrompt.Result.SUCCESS));
        } catch (Exception e) {
            return GeckoResult.fromValue(prompt.confirm(SharePrompt.Result.FAILURE));
        }
    }
}
