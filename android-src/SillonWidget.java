package fr.aflament.sillon;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.widget.RemoteViews;

/* Widget d'écran d'accueil : pochette, titre, artiste et boutons précédent / lecture / suivant.
   Les boutons commandent le lecteur Android ; s'il est arrêté, « lecture » reprend la dernière file. */
public class SillonWidget extends AppWidgetProvider {
    private static String title, sub;
    private static boolean playing;
    private static Bitmap art;

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) { render(c, m, ids); }

    static void push(Context c, String t, String s, boolean p, Bitmap a) {
        title = t; sub = s; playing = p;
        art = a == null ? null : scaled(a);
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            int[] ids = m.getAppWidgetIds(new ComponentName(c, SillonWidget.class));
            if (ids != null && ids.length > 0) render(c, m, ids);
        } catch (Exception ignored) { }
    }

    /* les widgets refusent les images trop lourdes */
    private static Bitmap scaled(Bitmap a) {
        int max = 256;
        if (a.getWidth() <= max && a.getHeight() <= max) return a;
        float k = max / (float) Math.max(a.getWidth(), a.getHeight());
        return Bitmap.createScaledBitmap(a, Math.round(a.getWidth() * k), Math.round(a.getHeight() * k), true);
    }

    private static PendingIntent svc(Context c, String action, int code) {
        Intent i = new Intent(c, PlayerService.class).setAction(action);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return Build.VERSION.SDK_INT >= 26 ? PendingIntent.getForegroundService(c, 100 + code, i, fl) : PendingIntent.getService(c, 100 + code, i, fl);
    }

    private static void render(Context c, AppWidgetManager m, int[] ids) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.sillon_widget);
        v.setTextViewText(R.id.w_title, title != null ? title : "Sillon");
        v.setTextViewText(R.id.w_sub, title != null ? (sub != null ? sub : "") : "Touche lecture pour reprendre");
        if (art != null) v.setImageViewBitmap(R.id.w_art, art);
        else v.setImageViewResource(R.id.w_art, R.mipmap.ic_launcher);
        v.setImageViewResource(R.id.w_play, playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
        v.setOnClickPendingIntent(R.id.w_prev, svc(c, PlayerService.A_PREV, 1));
        v.setOnClickPendingIntent(R.id.w_play, svc(c, PlayerService.A_TOGGLE, 2));
        v.setOnClickPendingIntent(R.id.w_next, svc(c, PlayerService.A_NEXT, 3));
        Intent open = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
        if (open != null) v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(c, 109, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        m.updateAppWidget(ids, v);
    }
}
