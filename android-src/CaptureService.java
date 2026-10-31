package fr.aflament.sillon;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.core.app.NotificationCompat;

/**
 * Service de premier plan qui capture le son joué par le téléphone (Android 10 et plus).
 * Mode normal : la capture démarre tout de suite.
 * Mode « bouton flottant » : la capture est préparée, un bouton rond flotte par-dessus les autres applis ;
 * le toucher démarre puis arrête l'enregistrement, et Sillon revient au premier plan à l'arrêt.
 * Les applications qui interdisent la capture (souvent les applis de streaming) donnent du silence.
 */
public class CaptureService extends Service {

    static final int RATE = 44100;
    private static final int BLOCK = 4410 * 4; // 100 ms en stéréo 16 bits

    private MediaProjection projection;
    private AudioRecord rec;
    private Thread reader;
    private volatile boolean running;
    private boolean bubbleMode;
    private long startedAt;

    private WindowManager wm;
    private TextView bubble;
    private WindowManager.LayoutParams lp;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable clock = new Runnable() {
        @Override
        public void run() {
            if (bubble != null && running) {
                long s = (System.currentTimeMillis() - startedAt) / 1000;
                bubble.setText(String.format("■\n%d:%02d", s / 60, s % 60));
                main.postDelayed(this, 1000);
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @SuppressLint("MissingPermission")
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || Build.VERSION.SDK_INT < 29) {
            stopSelf();
            return START_NOT_STICKY;
        }
        bubbleMode = intent.getBooleanExtra("bubble", false);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel("capture", "Enregistrement du son", NotificationManager.IMPORTANCE_LOW));
        }
        Notification n = new NotificationCompat.Builder(this, "capture")
                .setContentTitle(bubbleMode ? "Sillon est prêt à enregistrer" : "Sillon enregistre le son du téléphone")
                .setContentText(bubbleMode ? "Touche le bouton flottant pour démarrer ou arrêter." : "Reviens dans Sillon pour arrêter l'enregistrement.")
                .setSmallIcon(getApplicationInfo().icon)
                .setOngoing(true)
                .build();
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);

        int code = intent.getIntExtra("code", 0);
        Intent data = Build.VERSION.SDK_INT >= 33 ? intent.getParcelableExtra("data", Intent.class) : intent.getParcelableExtra("data");
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        try {
            projection = mpm.getMediaProjection(code, data);
        } catch (Exception e) {
            fail("Android refuse la capture (" + e.getMessage() + ")");
            return START_NOT_STICKY;
        }
        if (projection == null) {
            fail("Android refuse la capture");
            return START_NOT_STICKY;
        }
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                running = false;
                SillonPlugin p = SillonPlugin.instance;
                if (p != null) p.emitCaptureStopped("arrêtée par Android");
                stopSelf();
            }
        }, main);

        try {
            AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build();
            AudioFormat fmt = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build();
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord.Builder()
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(Math.max(min, BLOCK * 8))
                    .setAudioPlaybackCaptureConfig(cfg)
                    .build();
        } catch (Exception e) {
            fail("capture audio impossible (" + e.getMessage() + ")");
            return START_NOT_STICKY;
        }

        if (bubbleMode) {
            showBubble();
            SillonPlugin p = SillonPlugin.instance;
            if (p != null) p.emitCaptureArmed();
        } else {
            startReading();
        }
        return START_NOT_STICKY;
    }

    private void startReading() {
        if (running || rec == null) return;
        try {
            rec.startRecording();
        } catch (Exception e) {
            fail("capture audio impossible (" + e.getMessage() + ")");
            return;
        }
        running = true;
        startedAt = System.currentTimeMillis();
        reader = new Thread(() -> {
            byte[] buf = new byte[BLOCK];
            while (running) {
                int got = rec.read(buf, 0, buf.length);
                if (got > 0) {
                    SillonPlugin p = SillonPlugin.instance;
                    if (p != null) p.emitPcm(buf, got);
                } else if (got < 0) {
                    break;
                }
            }
        }, "sillon-capture");
        reader.start();
        SillonPlugin p = SillonPlugin.instance;
        if (p != null) p.emitCaptureStarted();
        if (bubble != null) {
            styleBubble(true);
            main.post(clock);
        }
    }

    /** Arrêt depuis le bouton flottant : on prévient l'app, on la ramène au premier plan et on termine. */
    private void stopFromBubble() {
        running = false;
        SillonPlugin p = SillonPlugin.instance;
        if (p != null) p.emitCaptureStopped("bouton flottant");
        try {
            Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (open != null) {
                open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(open);
            }
        } catch (Exception ignored) {
        }
        stopSelf();
    }

    /* ---------- bouton flottant ---------- */

    private void styleBubble(boolean recording) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(recording ? 0xFFD32F2F : 0xEE1A1D22);
        bg.setStroke(dp(3), recording ? 0xFFFFFFFF : 0xFFD32F2F);
        bubble.setBackground(bg);
        bubble.setText(recording ? "■\n0:00" : "●\nREC");
    }

    @SuppressLint("ClickableViewAccessibility")
    private void showBubble() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) return;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        bubble = new TextView(this);
        bubble.setTextColor(Color.WHITE);
        bubble.setTextSize(13);
        bubble.setTypeface(Typeface.DEFAULT_BOLD);
        bubble.setGravity(Gravity.CENTER);
        bubble.setElevation(dp(8));
        bubble.setContentDescription("Sillon : démarrer ou arrêter l'enregistrement");
        styleBubble(false);
        int size = dp(72);
        lp = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = getResources().getDisplayMetrics().widthPixels - size - dp(12);
        lp.y = dp(220);
        bubble.setOnTouchListener(new View.OnTouchListener() {
            int sx, sy;
            float dx, dy;
            boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = lp.x;
                        sy = lp.y;
                        dx = e.getRawX();
                        dy = e.getRawY();
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int mx = (int) (e.getRawX() - dx), my = (int) (e.getRawY() - dy);
                        if (Math.abs(mx) > dp(8) || Math.abs(my) > dp(8)) moved = true;
                        if (moved) {
                            lp.x = sx + mx;
                            lp.y = sy + my;
                            try {
                                wm.updateViewLayout(bubble, lp);
                            } catch (Exception ignored) {
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) {
                            if (running) stopFromBubble();
                            else startReading();
                        }
                        return true;
                }
                return false;
            }
        });
        try {
            wm.addView(bubble, lp);
        } catch (Exception e) {
            bubble = null;
        }
    }

    private void removeBubble() {
        main.removeCallbacks(clock);
        if (bubble != null && wm != null) {
            try {
                wm.removeView(bubble);
            } catch (Exception ignored) {
            }
        }
        bubble = null;
    }

    private void fail(String msg) {
        SillonPlugin p = SillonPlugin.instance;
        if (p != null) p.emitCaptureStopped(msg);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        running = false;
        removeBubble();
        try {
            if (reader != null) reader.join(500);
        } catch (InterruptedException ignored) {
        }
        try {
            if (rec != null) {
                if (rec.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) rec.stop();
                rec.release();
            }
        } catch (Exception ignored) {
        }
        try {
            if (projection != null) projection.stop();
        } catch (Exception ignored) {
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
