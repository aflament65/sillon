package fr.aflament.sillon;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
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

import androidx.core.app.NotificationCompat;

/**
 * Service de premier plan qui capture le son joué par le téléphone (Android 10 et plus).
 * Android impose ce service et sa notification pendant toute la durée de la capture.
 * Les applications qui interdisent la capture (souvent les applis de streaming) donnent du silence.
 */
public class CaptureService extends Service {

    static final int RATE = 44100;
    private static final int BLOCK = 4410 * 4; // 100 ms en stéréo 16 bits

    private MediaProjection projection;
    private AudioRecord rec;
    private Thread reader;
    private volatile boolean running;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @SuppressLint("MissingPermission")
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || Build.VERSION.SDK_INT < 29) {
            stopSelf();
            return START_NOT_STICKY;
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel("capture", "Enregistrement du son", NotificationManager.IMPORTANCE_LOW));
        }
        Notification n = new NotificationCompat.Builder(this, "capture")
                .setContentTitle("Sillon enregistre le son du téléphone")
                .setContentText("Reviens dans Sillon pour arrêter l'enregistrement.")
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
        }, new Handler(Looper.getMainLooper()));

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
            rec.startRecording();
        } catch (Exception e) {
            fail("capture audio impossible (" + e.getMessage() + ")");
            return START_NOT_STICKY;
        }

        running = true;
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
        return START_NOT_STICKY;
    }

    private void fail(String msg) {
        SillonPlugin p = SillonPlugin.instance;
        if (p != null) p.emitCaptureStopped(msg);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        running = false;
        try {
            if (reader != null) reader.join(500);
        } catch (InterruptedException ignored) {
        }
        try {
            if (rec != null) {
                rec.stop();
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
