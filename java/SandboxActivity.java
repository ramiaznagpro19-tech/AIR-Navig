package app.airnavig;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.net.http.SslError;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayInputStream;
import java.io.File;

/** Bac à sable AIR Navig : processus séparé, aucun accès sensible, tout est effacé à la sortie. */
public class SandboxActivity extends Activity {
    private static final String SUFFIX = "bacasable";
    private TextView urlView;
    private WebView web;
    private String[] rules = new String[0];
    private boolean block = true;

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        wipe();
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                WebView.setDataDirectorySuffix(SUFFIX);
            } catch (Exception unused) {
            }
        }
        getWindow().setStatusBarColor(-14020844);
        getWindow().setNavigationBarColor(-15789801);
        String[] stringArrayExtra = getIntent().getStringArrayExtra("rules");
        if (stringArrayExtra != null && stringArrayExtra.length <= 5000) {
            this.rules = stringArrayExtra;
        }
        this.block = getIntent().getBooleanExtra("block", true);
        float f = getResources().getDisplayMetrics().density;
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(1);
        linearLayout.setBackgroundColor(-15789801);
        LinearLayout linearLayout2 = new LinearLayout(this);
        linearLayout2.setOrientation(0);
        linearLayout2.setGravity(16);
        linearLayout2.setBackgroundColor(-14020844);
        int i = (int) (12.0f * f);
        int i2 = (int) (8.0f * f);
        int i3 = (int) (f * 6.0f);
        linearLayout2.setPadding(i, i2, i3, i2);
        LinearLayout linearLayout3 = new LinearLayout(this);
        linearLayout3.setOrientation(1);
        TextView textView = new TextView(this);
        textView.setText("🛡 Bac à sable AIR Navig");
        textView.setTextColor(-15671);
        textView.setTypeface(Typeface.DEFAULT_BOLD);
        textView.setTextSize(2, 14.0f);
        this.urlView = new TextView(this);
        this.urlView.setTextColor(-1446927);
        this.urlView.setTextSize(2, 13.0f);
        this.urlView.setSingleLine(true);
        linearLayout3.addView(textView);
        linearLayout3.addView(this.urlView);
        linearLayout2.addView(linearLayout3, new LinearLayout.LayoutParams(0, -2, 1.0f));
        TextView textView2 = new TextView(this);
        textView2.setText("Quitter");
        textView2.setTextColor(-1);
        textView2.setTypeface(Typeface.DEFAULT_BOLD);
        textView2.setTextSize(2, 14.0f);
        textView2.setPadding(i, i2, i, i2);
        textView2.setBackgroundColor(-7723469);
        textView2.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                SandboxActivity.this.finish();
            }
        });
        linearLayout2.addView(textView2);
        linearLayout.addView(linearLayout2);
        TextView textView3 = new TextView(this);
        textView3.setText("Site isolé dans un espace séparé : aucun accès au micro, à la caméra, à ta position ni à tes fichiers. Téléchargements bloqués. Tout est effacé quand tu quittes.");
        textView3.setTextColor(-7630431);
        textView3.setTextSize(2, 11.5f);
        textView3.setPadding(i, i3, i, i3);
        textView3.setBackgroundColor(-15328990);
        linearLayout.addView(textView3);
        this.web = new WebView(this);
        WebSettings settings = this.web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setGeolocationEnabled(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setDatabaseEnabled(false);
        settings.setSaveFormData(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                settings.setSafeBrowsingEnabled(true);
            } catch (Throwable ignored) {
            }
        }
        Blocklist.ensureLoaded(getApplicationContext());
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(this.web, false);
        this.web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView webView, WebResourceRequest webResourceRequest) {
                String scheme = webResourceRequest.getUrl().getScheme();
                return ("http".equals(scheme) || "https".equals(scheme)) ? false : true;
            }

            @Override
            public void onReceivedSslError(WebView webView, SslErrorHandler handler, SslError error) {
                handler.cancel();
                Toast.makeText(SandboxActivity.this, "Certificat non valide : connexion bloquée", Toast.LENGTH_LONG).show();
            }

            @Override
            public void onReceivedHttpAuthRequest(WebView webView, android.webkit.HttpAuthHandler h, String host, String realm) {
                h.cancel();
            }

            @Override
            public boolean onRenderProcessGone(WebView webView, RenderProcessGoneDetail detail) {
                finish();
                return true;
            }

            @Override
            public void onPageStarted(WebView webView, String str, Bitmap bitmap) {
                SandboxActivity.this.urlView.setText(str);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView webView, WebResourceRequest webResourceRequest) {
                Uri url = webResourceRequest.getUrl();
                String scheme = url.getScheme();
                if (!"http".equals(scheme) && !"https".equals(scheme)) {
                    return SandboxActivity.empty();
                }
                String host = url.getHost();
                if (!SandboxActivity.this.block || host == null || webResourceRequest.isForMainFrame()) {
                    return null;
                }
                String str = "." + host.toLowerCase(java.util.Locale.ROOT);
                for (String str2 : SandboxActivity.this.rules) {
                    if (str.endsWith(str2)) {
                        return SandboxActivity.empty();
                    }
                }
                return Blocklist.contains(host) ? SandboxActivity.empty() : null;
            }
        });
        this.web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest permissionRequest) {
                permissionRequest.deny();
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String str, GeolocationPermissions.Callback callback) {
                callback.invoke(str, false, false);
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> valueCallback, WebChromeClient.FileChooserParams fileChooserParams) {
                valueCallback.onReceiveValue(null);
                Toast.makeText(SandboxActivity.this, "Envoi de fichiers bloqué dans le bac à sable", 0).show();
                return true;
            }
        });
        this.web.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String str, String str2, String str3, String str4, long j) {
                Toast.makeText(SandboxActivity.this, "Téléchargement bloqué dans le bac à sable", 1).show();
            }
        });
        linearLayout.addView(this.web, new LinearLayout.LayoutParams(-1, 0, 1.0f));
        setContentView(linearLayout);
        String stringExtra = getIntent().getStringExtra("url");
        if (stringExtra != null && (stringExtra.startsWith("http://") || stringExtra.startsWith("https://"))) {
            this.urlView.setText(stringExtra);
            this.web.loadUrl(stringExtra);
        } else {
            finish();
        }
    }

    public static WebResourceResponse empty() {
        return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
    }

    @Override
    public void onBackPressed() {
        if (this.web == null || !this.web.canGoBack()) {
            finish();
        } else {
            this.web.goBack();
        }
    }

    @Override
    protected void onDestroy() {
        if (this.web != null) {
            this.web.stopLoading();
            this.web.clearCache(true);
            this.web.clearHistory();
            CookieManager.getInstance().removeAllCookies(null);
            CookieManager.getInstance().flush();
            WebStorage.getInstance().deleteAllData();
            this.web.destroy();
            this.web = null;
        }
        super.onDestroy();
        wipe();
        Process.killProcess(Process.myPid());
    }

    private void wipe() {
        for (File file : new File[]{new File(getApplicationInfo().dataDir), getCacheDir()}) {
            File[] listFiles = file.listFiles();
            if (listFiles != null) {
                for (File file2 : listFiles) {
                    if (file2.getName().contains(SUFFIX)) {
                        delete(file2);
                    }
                }
            }
        }
    }

    private static void delete(File file) {
        File[] listFiles = file.listFiles();
        if (listFiles != null) {
            for (File file2 : listFiles) {
                delete(file2);
            }
        }
        file.delete();
    }
}
