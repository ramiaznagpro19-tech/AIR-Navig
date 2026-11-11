package app.airnavig;

import android.app.Activity;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.List;
import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebResponse;

/**
 * Bac à sable AIR Navig : le site s'ouvre dans une session privée AIRCore isolée.
 * Aucun accès au micro, à la caméra, à la position ni aux fichiers ; téléchargements bloqués ;
 * tout (cookies, cache, historique) est effacé à la fermeture. Le bloqueur reste actif.
 */
public class SandboxActivity extends Activity {
    private GeckoSession session;
    private GeckoView view;
    private TextView urlView;
    private boolean canBack;

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        final String url = getIntent().getStringExtra("url");
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            finish();
            return;
        }
        getWindow().setStatusBarColor(0xFF2A0E14);
        getWindow().setNavigationBarColor(0xFF0F1117);
        float f = getResources().getDisplayMetrics().density;
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(0xFF0F1117);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(16);
        bar.setBackgroundColor(0xFF2A0E14);
        int p12 = (int) (12 * f), p8 = (int) (8 * f), p6 = (int) (6 * f);
        bar.setPadding(p12, p8, p6, p8);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("🛡 Bac à sable AIR Navig");
        title.setTextColor(0xFFFFC2C9);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(2, 14f);
        urlView = new TextView(this);
        urlView.setTextColor(0xFFE9EBF1);
        urlView.setTextSize(2, 13f);
        urlView.setSingleLine(true);
        urlView.setText(url);
        texts.addView(title);
        texts.addView(urlView);
        bar.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView quit = new TextView(this);
        quit.setText("Quitter");
        quit.setTextColor(0xFFFFFFFF);
        quit.setTypeface(Typeface.DEFAULT_BOLD);
        quit.setTextSize(2, 14f);
        quit.setPadding(p12, p8, p12, p8);
        quit.setBackgroundColor(0xFF8A2633);
        quit.setOnClickListener(v -> finish());
        bar.addView(quit);
        page.addView(bar);
        TextView info = new TextView(this);
        info.setText("Site isolé dans un espace séparé : aucun accès au micro, à la caméra, à ta position ni à tes fichiers. Téléchargements bloqués. Tout est effacé quand tu quittes.");
        info.setTextColor(0xFF8B91A1);
        info.setTextSize(2, 11.5f);
        info.setPadding(p12, p6, p12, p6);
        info.setBackgroundColor(0xFF16181F);
        page.addView(info);
        view = new GeckoView(this);
        page.addView(view, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(page);

        Engine.start(this, () -> {
            if (isFinishing() || isDestroyed()) return;
            session = new GeckoSession(new GeckoSessionSettings.Builder()
                    .usePrivateMode(true)
                    .suspendMediaWhenInactive(true)
                    .build());
            session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
                @Override
                public void onLocationChange(@NonNull GeckoSession s, @Nullable String u,
                                             @NonNull List<GeckoSession.PermissionDelegate.ContentPermission> perms,
                                             @NonNull Boolean gesture) {
                    if (u != null) urlView.setText(u);
                }

                @Override
                public void onCanGoBack(@NonNull GeckoSession s, boolean b) {
                    canBack = b;
                }

                @Nullable
                @Override
                public GeckoResult<AllowOrDeny> onLoadRequest(@NonNull GeckoSession s, @NonNull LoadRequest r) {
                    String sc = Uri.parse(r.uri).getScheme();
                    return "http".equals(sc) || "https".equals(sc) ? GeckoResult.allow() : GeckoResult.deny();
                }

                @Nullable
                @Override
                public GeckoResult<GeckoSession> onNewSession(@NonNull GeckoSession s, @NonNull String uri) {
                    return null;
                }
            });
            session.setPermissionDelegate(new GeckoSession.PermissionDelegate() {
                @Override
                public void onAndroidPermissionsRequest(@NonNull GeckoSession s, @Nullable String[] perms, @NonNull Callback cb) {
                    cb.reject();
                }

                @Nullable
                @Override
                public GeckoResult<Integer> onContentPermissionRequest(@NonNull GeckoSession s, @NonNull ContentPermission perm) {
                    return GeckoResult.fromValue(perm.permission == PERMISSION_AUTOPLAY_INAUDIBLE
                            ? ContentPermission.VALUE_ALLOW : ContentPermission.VALUE_DENY);
                }

                @Override
                public void onMediaPermissionRequest(@NonNull GeckoSession s, @NonNull String uri, @Nullable MediaSource[] video,
                                                     @Nullable MediaSource[] audio, @NonNull MediaCallback cb) {
                    cb.reject();
                }
            });
            session.setPromptDelegate(new GeckoSession.PromptDelegate() {
                @Nullable
                @Override
                public GeckoResult<PromptResponse> onFilePrompt(@NonNull GeckoSession s, @NonNull FilePrompt prompt) {
                    Toast.makeText(SandboxActivity.this, "Envoi de fichiers bloqué dans le bac à sable", Toast.LENGTH_SHORT).show();
                    return GeckoResult.fromValue(prompt.dismiss());
                }

                @Nullable
                @Override
                public GeckoResult<PromptResponse> onAuthPrompt(@NonNull GeckoSession s, @NonNull AuthPrompt prompt) {
                    return GeckoResult.fromValue(prompt.dismiss());
                }
            });
            session.setContentDelegate(new GeckoSession.ContentDelegate() {
                @Override
                public void onExternalResponse(@NonNull GeckoSession s, @NonNull WebResponse response) {
                    try {
                        if (response.body != null) response.body.close();
                    } catch (Exception ignored) {
                    }
                    Toast.makeText(SandboxActivity.this, "Téléchargement bloqué dans le bac à sable", Toast.LENGTH_LONG).show();
                }

                @Override
                public void onCrash(@NonNull GeckoSession s) {
                    finish();
                }

                @Override
                public void onKill(@NonNull GeckoSession s) {
                    finish();
                }
            });
            session.open(Engine.runtime);
            view.setSession(session);
            session.loadUri(url);
        });
    }

    @Override
    public void onBackPressed() {
        if (session != null && canBack) session.goBack();
        else finish();
    }

    @Override
    protected void onDestroy() {
        try {
            if (view != null) view.releaseSession();
        } catch (Throwable ignored) {
        }
        // Fermer la session privée efface tout ce que le site a stocké.
        if (session != null) session.close();
        super.onDestroy();
    }
}
