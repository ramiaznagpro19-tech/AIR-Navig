package app.airnavig;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.widget.RemoteViews;

/** Widget « barre de recherche » pour l'écran d'accueil : un appui ouvre AIR Navig. */
public final class SearchWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        String pkg = ctx.getPackageName();
        int layout = ctx.getResources().getIdentifier("air_widget_search", "layout", pkg);
        int root = ctx.getResources().getIdentifier("air_widget_root", "id", pkg);
        if (layout == 0) return;

        Intent open = new Intent(ctx, MainActivity.class);
        open.setAction(Intent.ACTION_MAIN);
        open.addCategory(Intent.CATEGORY_LAUNCHER);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open, flags);

        for (int id : ids) {
            RemoteViews v = new RemoteViews(pkg, layout);
            if (root != 0) v.setOnClickPendingIntent(root, pi);
            mgr.updateAppWidget(id, v);
        }
    }
}
