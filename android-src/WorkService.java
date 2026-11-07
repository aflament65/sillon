package fr.aflament.sillon;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/* Service de premier plan pendant les tâches longues (conversion, enrichissement, rangement) :
   le processeur reste actif écran éteint, et la progression s'affiche dans la notification. */
public class WorkService extends Service {
    static final String CH = "sillon_work";
    static final int ID = 7303;
    private PowerManager.WakeLock lock;
    private boolean started = false;

    @Override
    public IBinder onBind(Intent i) { return null; }

    private Notification build(String label, String sub, int done, int total) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null) {
            NotificationChannel c = new NotificationChannel(CH, "Tâches en cours", NotificationManager.IMPORTANCE_LOW);
            c.setShowBadge(false);
            nm.createNotificationChannel(c);
        }
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(label == null ? "Sillon travaille" : label)
                .setContentText(sub == null ? "" : sub).setOngoing(true).setOnlyAlertOnce(true).setContentIntent(pi);
        if (total > 0) b.setProgress(total, Math.min(done, total), false); else b.setProgress(0, 0, true);
        return b.build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "stop".equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String label = intent != null ? intent.getStringExtra("label") : null;
        String sub = intent != null ? intent.getStringExtra("sub") : null;
        int done = intent != null ? intent.getIntExtra("done", 0) : 0, total = intent != null ? intent.getIntExtra("total", 0) : 0;
        Notification n = build(label, sub, done, total);
        if (!started) {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(ID, n);
            started = true;
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sillon:work");
            lock.acquire(6 * 3600 * 1000L);
        } else {
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(ID, n);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (lock != null && lock.isHeld()) lock.release();
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true);
        super.onDestroy();
    }
}
