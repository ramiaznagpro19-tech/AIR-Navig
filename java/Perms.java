package app.airnavig;

import android.Manifest;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSession.PermissionDelegate;

/**
 * Autorisations des sites : réglages globaux d'abord (Autorisations › Micro / Caméra / Position),
 * puis confirmation site par site. Les décisions « toujours » sont gardées (sauf en navigation privée).
 */
final class Perms implements PermissionDelegate {
    private final MainActivity act;

    Perms(MainActivity act) {
        this.act = act;
    }

    private static String origin(String uri) {
        Uri u = Uri.parse(uri == null ? "" : uri);
        if (u.getScheme() == null || u.getHost() == null) return uri == null ? "" : uri;
        return u.getScheme() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
    }

    /** Gecko a besoin d'une autorisation Android (caméra, micro, position). */
    @Override
    public void onAndroidPermissionsRequest(@NonNull GeckoSession session, @Nullable String[] permissions,
                                            @NonNull Callback callback) {
        if (permissions == null || permissions.length == 0) {
            callback.grant();
            return;
        }
        act.requestAndroid(permissions, callback);
    }

    @Nullable
    @Override
    public GeckoResult<Integer> onContentPermissionRequest(@NonNull GeckoSession session, @NonNull ContentPermission perm) {
        final String origin = origin(perm.uri);
        switch (perm.permission) {
            case PERMISSION_GEOLOCATION:
                if (!act.allowGeo || !origin.startsWith("https://")) return deny(origin);
                return ask(origin, "geo", "ta position (approximative)");
            case PERMISSION_MEDIA_KEY_SYSTEM_ACCESS:
                return ask(origin, "drm", "vidéo protégée (DRM)");
            case PERMISSION_AUTOPLAY_INAUDIBLE:
                return GeckoResult.fromValue(ContentPermission.VALUE_ALLOW);
            case PERMISSION_AUTOPLAY_AUDIBLE:
                // Son automatique : seulement après un geste de l'utilisateur (comme avant).
                return GeckoResult.fromValue(ContentPermission.VALUE_DENY);
            case PERMISSION_TRACKING:
            case PERMISSION_STORAGE_ACCESS:
                // Exception au blocage anti-pistage ou accès aux cookies d'un site tiers : non.
                return GeckoResult.fromValue(ContentPermission.VALUE_DENY);
            default:
                // Notifications, stockage persistant, réalité virtuelle, appareils locaux… : refusés.
                return GeckoResult.fromValue(ContentPermission.VALUE_DENY);
        }
    }

    private GeckoResult<Integer> deny(String origin) {
        act.call("onPerm", origin + "|refusé");
        return GeckoResult.fromValue(ContentPermission.VALUE_DENY);
    }

    private GeckoResult<Integer> ask(final String origin, final String kind, final String what) {
        Boolean d = act.siteDecision(origin + "|" + kind);
        if (d != null) {
            if (d) {
                act.call("onPerm", origin + "|" + what);
                return GeckoResult.fromValue(ContentPermission.VALUE_ALLOW);
            }
            return deny(origin);
        }
        final GeckoResult<Integer> res = new GeckoResult<>();
        act.askSite(origin, what, (allow, remember) -> {
            if (remember) act.rememberSite(origin + "|" + kind, allow);
            if (allow) {
                act.call("onPerm", origin + "|" + what);
                res.complete(ContentPermission.VALUE_ALLOW);
            } else {
                act.call("onPerm", origin + "|refusé");
                res.complete(ContentPermission.VALUE_DENY);
            }
        }, () -> res.complete(ContentPermission.VALUE_DENY));
        return res;
    }

    /** Caméra / micro (getUserMedia). Le partage d'écran est refusé. */
    @Override
    public void onMediaPermissionRequest(@NonNull GeckoSession session, @NonNull String uri,
                                         @Nullable MediaSource[] video, @Nullable MediaSource[] audio,
                                         @NonNull MediaCallback callback) {
        final String origin = origin(uri);
        MediaSource cam = null;
        MediaSource mic = null;
        if (video != null && act.allowCam) {
            for (MediaSource s : video) {
                if (s.source == MediaSource.SOURCE_CAMERA) {
                    cam = s;
                    break;
                }
            }
        }
        if (audio != null && act.allowMic) {
            for (MediaSource s : audio) {
                if (s.source == MediaSource.SOURCE_MICROPHONE) {
                    mic = s;
                    break;
                }
            }
        }
        // Décisions déjà prises pour ce site.
        Boolean dc = cam == null ? null : act.siteDecision(origin + "|cam");
        Boolean dm = mic == null ? null : act.siteDecision(origin + "|mic");
        if (Boolean.FALSE.equals(dc)) cam = null;
        if (Boolean.FALSE.equals(dm)) mic = null;
        if (cam == null && mic == null) {
            act.call("onPerm", origin + "|refusé");
            callback.reject();
            return;
        }
        final List<String> undecided = new ArrayList<>();
        if (cam != null && dc == null) undecided.add("caméra");
        if (mic != null && dm == null) undecided.add("micro");
        final MediaSource fCam = cam;
        final MediaSource fMic = mic;
        if (undecided.isEmpty()) {
            grantMedia(origin, fCam, fMic, callback);
            return;
        }
        act.askSite(origin, android.text.TextUtils.join(", ", undecided), (allow, remember) -> {
            if (remember) {
                if (fCam != null) act.rememberSite(origin + "|cam", allow);
                if (fMic != null) act.rememberSite(origin + "|mic", allow);
            }
            if (allow) grantMedia(origin, fCam, fMic, callback);
            else {
                act.call("onPerm", origin + "|refusé");
                callback.reject();
            }
        }, callback::reject);
    }

    private void grantMedia(String origin, @Nullable MediaSource cam, @Nullable MediaSource mic, MediaCallback cb) {
        List<String> need = new ArrayList<>();
        if (cam != null) need.add(Manifest.permission.CAMERA);
        if (mic != null) need.add(Manifest.permission.RECORD_AUDIO);
        act.requestAndroid(need.toArray(new String[0]), new Callback() {
            @Override
            public void grant() {
                cb.grant(cam, mic);
                String what = cam != null && mic != null ? "caméra, micro" : cam != null ? "caméra" : "micro";
                act.call("onPerm", origin + "|" + what);
            }

            @Override
            public void reject() {
                cb.reject();
            }
        });
    }
}
