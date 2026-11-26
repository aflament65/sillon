package fr.aflament.sillon;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.core.app.NotificationCompat;
import androidx.documentfile.provider.DocumentFile;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Lecteur en arrière-plan : la musique continue écran verrouillé, les boutons du volant,
 * de l'autoradio Bluetooth et de la notification pilotent la lecture, et le titre s'affiche dans la voiture.
 * La file (déjà mise dans l'ordre par l'app) est gérée ici pour que le passage au morceau suivant
 * fonctionne même quand l'app n'est pas à l'écran.
 */
public class PlayerService extends androidx.media.MediaBrowserServiceCompat {

    static class Item {
        String id = "", uri, title, artist, album, dir;
        double loud = Double.NaN; /* volume mesuré (dB, 95e centile des fenêtres de 50 ms) ; inconnu = NaN */
    }

    /* ---------- son : volume homogène et égaliseur (réglés depuis l'appli) ---------- */
    static volatile boolean normOn = true;
    static volatile float[] eqCurve = null; /* gains en dB à 60, 230, 910, 3600, 14000 Hz ; null = égaliseur coupé */
    static final double TARGET = -12;
    private static final double[] EQF = {60, 230, 910, 3600, 14000};
    private static final class Fx { android.media.audiofx.Equalizer eq; android.media.audiofx.LoudnessEnhancer le; float vol = 1f; double loud = Double.NaN; }
    private final java.util.Map<MediaPlayer, Fx> fx = new java.util.HashMap<>();

    private static double curveAt(float[] c, double hz) {
        if (hz <= EQF[0]) return c[0];
        if (hz >= EQF[4]) return c[4];
        for (int i = 0; i < 4; i++) if (hz <= EQF[i + 1]) {
            double t = (Math.log(hz) - Math.log(EQF[i])) / (Math.log(EQF[i + 1]) - Math.log(EQF[i]));
            return c[i] + t * (c[i + 1] - c[i]);
        }
        return 0;
    }

    private void attachFx(MediaPlayer p, Item it) {
        detachFx(p);
        Fx f = new Fx();
        f.loud = it.loud;
        int sid = p.getAudioSessionId();
        double g = normOn && !Double.isNaN(it.loud) ? Math.max(-12, Math.min(9, TARGET - it.loud)) : 0;
        f.vol = g < 0 ? (float) Math.pow(10, g / 20) : 1f;
        try { p.setVolume(f.vol, f.vol); } catch (Exception ignored) { }
        if (g > 0.3) try {
            f.le = new android.media.audiofx.LoudnessEnhancer(sid);
            f.le.setTargetGain((int) Math.round(g * 100));
            f.le.setEnabled(true);
        } catch (Throwable e) { f.le = null; }
        float[] c = eqCurve;
        if (c != null && c.length == 5) try {
            android.media.audiofx.Equalizer eq = new android.media.audiofx.Equalizer(0, sid);
            short[] range = eq.getBandLevelRange();
            for (short b = 0; b < eq.getNumberOfBands(); b++) {
                double db = curveAt(c, eq.getCenterFreq(b) / 1000.0);
                int mb = (int) Math.round(db * 100);
                eq.setBandLevel(b, (short) Math.max(range[0], Math.min(range[1], mb)));
            }
            eq.setEnabled(true);
            f.eq = eq;
        } catch (Throwable e) { f.eq = null; }
        fx.put(p, f);
    }

    private void detachFx(MediaPlayer p) {
        Fx f = p == null ? null : fx.remove(p);
        if (f == null) return;
        try { if (f.eq != null) f.eq.release(); } catch (Throwable ignored) { }
        try { if (f.le != null) f.le.release(); } catch (Throwable ignored) { }
    }

    private float volOf(MediaPlayer p) { Fx f = p == null ? null : fx.get(p); return f == null ? 1f : f.vol; }

    /* nouveaux réglages : appliqués tout de suite au morceau en cours et au suivant */
    void applyAudio() {
        if (mp != null && index >= 0 && index < items.size()) attachFx(mp, items.get(index));
        if (nextMp != null && nextIdx >= 0 && nextIdx < items.size()) attachFx(nextMp, items.get(nextIdx));
    }

    static volatile PlayerService instance;
    /** File transmise par l'app avant le démarrage du service. */
    static volatile List<Item> pendingItems;
    static volatile int pendingIndex;

    private static final String CH = "lecture";
    private static final int NOTIF = 2;
    static final String A_PREV = "fr.aflament.sillon.PREV", A_TOGGLE = "fr.aflament.sillon.TOGGLE",
            A_NEXT = "fr.aflament.sillon.NEXT", A_STOP = "fr.aflament.sillon.STOP";

    private final List<Item> items = new ArrayList<>();
    private int index = -1;
    private String repeat = "off";
    private MediaPlayer mp;
    /* morceau suivant préparé d'avance et enchaîné par Android : pas de blanc entre deux morceaux */
    private MediaPlayer nextMp;
    private int nextIdx = -1;
    private MediaSessionCompat session;
    private AudioManager am;
    private AudioFocusRequest focusReq;
    private boolean resumeOnFocus = false;
    private Bitmap art;
    private String artDir;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            emitState(false);
            main.postDelayed(this, 500);
        }
    };

    /** Bluetooth de la voiture déconnecté ou casque débranché : pause. */
    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(i.getAction())) pause();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        session = new MediaSessionCompat(this, "Sillon");
        session.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { resume(); }
            @Override public void onPause() { pause(); }
            @Override public void onSkipToNext() { next(false); }
            @Override public void onSkipToPrevious() { prev(); }
            @Override public void onSeekTo(long pos) { seek((int) pos); }
            @Override public void onStop() { stopAll(); }
            @Override public void onPlayFromMediaId(String mediaId, android.os.Bundle extras) { playFromId(mediaId); }
            @Override public void onPlayFromSearch(String query, android.os.Bundle extras) { playFromSearch(query); }
        });
        session.setActive(true);
        setSessionToken(session.getSessionToken());
        if (Build.VERSION.SDK_INT >= 26) {
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(
                    new NotificationChannel(CH, "Lecture", NotificationManager.IMPORTANCE_LOW));
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        main.post(ticker);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground();
        String a = intent != null ? intent.getAction() : null;
        /* widget ou bouton du casque, lecteur arrêté : on reprend la dernière file */
        if (items.isEmpty() && pendingItems == null && (A_PREV.equals(a) || A_TOGGLE.equals(a) || A_NEXT.equals(a))) {
            if (!restoreQueue()) stopAll();
            return START_NOT_STICKY;
        }
        if (A_PREV.equals(a)) prev();
        else if (A_TOGGLE.equals(a)) toggle();
        else if (A_NEXT.equals(a)) next(false);
        else if (A_STOP.equals(a)) stopAll();
        else if (pendingItems != null) {
            List<Item> l = pendingItems;
            pendingItems = null;
            setQueue(l, pendingIndex, true, false);
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return super.onBind(intent); /* Android Auto et les autres lecteurs se connectent ici pour parcourir la discothèque */
    }

    /* ---------- parcours de la discothèque (Android Auto) ---------- */

    @Override
    public BrowserRoot onGetRoot(String clientPackageName, int clientUid, android.os.Bundle rootHints) {
        android.os.Bundle ex = new android.os.Bundle();
        ex.putBoolean("android.media.browse.SEARCH_SUPPORTED", true);
        ex.putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 1);
        ex.putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 1);
        return new BrowserRoot("root", ex);
    }

    private static android.support.v4.media.MediaBrowserCompat.MediaItem node(String id, String title, String sub, boolean playable) {
        android.support.v4.media.MediaDescriptionCompat d = new android.support.v4.media.MediaDescriptionCompat.Builder()
                .setMediaId(id).setTitle(title).setSubtitle(sub).build();
        return new android.support.v4.media.MediaBrowserCompat.MediaItem(d, playable
                ? android.support.v4.media.MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                : android.support.v4.media.MediaBrowserCompat.MediaItem.FLAG_BROWSABLE);
    }

    @Override
    public void onLoadChildren(String parentId, Result<List<android.support.v4.media.MediaBrowserCompat.MediaItem>> result) {
        List<android.support.v4.media.MediaBrowserCompat.MediaItem> l = new ArrayList<>();
        Catalog k = Catalog.get(this);
        try {
            if ("root".equals(parentId)) {
                if (!k.byId.isEmpty()) l.add(node("shuffle", "Tout au hasard", k.byId.size() + " morceaux", true));
                l.add(node("albums", "Albums", k.albums.size() + " albums", false));
                l.add(node("artists", "Artistes", null, false));
                if (!k.playlists.isEmpty()) l.add(node("pls", "Listes de lecture", null, false));
            } else if ("albums".equals(parentId)) {
                List<Catalog.Album> al = new ArrayList<>(k.albums);
                java.util.Collections.sort(al, (x, y) -> Catalog.norm(x.artist + " " + x.year).compareTo(Catalog.norm(y.artist + " " + y.year)));
                for (Catalog.Album a : al) l.add(node("alb:" + a.key, a.album, a.artist + (a.year.isEmpty() ? "" : " · " + a.year), false));
            } else if ("artists".equals(parentId)) {
                for (String ar : k.artists()) l.add(node("art:" + ar, ar, null, false));
            } else if ("pls".equals(parentId)) {
                for (Catalog.Playlist p : k.playlists) l.add(node("pl:" + p.id, p.name, p.ids.size() + " morceaux", false));
            } else if (parentId.startsWith("art:")) {
                String ar = parentId.substring(4);
                l.add(node("artshuf:" + ar, "Tout au hasard", ar, true));
                for (Catalog.Album a : k.albumsOf(ar)) l.add(node("alb:" + a.key, a.album, a.year, false));
            } else if (parentId.startsWith("alb:")) {
                Catalog.Album a = k.album(parentId.substring(4));
                if (a != null) for (int i = 0; i < a.tracks.size(); i++) l.add(node("tr:" + a.key + "|" + i, a.tracks.get(i).title, a.tracks.get(i).artist, true));
            } else if (parentId.startsWith("pl:")) {
                Catalog.Playlist p = k.playlist(parentId.substring(3));
                if (p != null) { List<Catalog.Track> t = k.playlistTracks(p); for (int i = 0; i < t.size(); i++) l.add(node("pt:" + p.id + "|" + i, t.get(i).title, t.get(i).artist, true)); }
            }
        } catch (Exception ignored) { }
        result.sendResult(l);
    }

    private void playList(List<Catalog.Track> tracks, int start, boolean shuffle) {
        if (tracks == null || tracks.isEmpty()) return;
        Catalog k = Catalog.get(this);
        List<Item> l = new ArrayList<>();
        for (Catalog.Track t : tracks) l.add(k.item(t));
        if (shuffle) { java.util.Collections.shuffle(l); start = 0; }
        try { androidx.core.content.ContextCompat.startForegroundService(this, new Intent(this, PlayerService.class)); } catch (Exception ignored) { }
        setQueue(l, start, true, false);
        SillonPlugin p = SillonPlugin.instance;
        if (p != null) p.emitPlayerQueue(items, index);
    }

    void playFromId(String id) {
        Catalog k = Catalog.get(this);
        try {
            if ("shuffle".equals(id)) playList(k.all(), 0, true);
            else if (id.startsWith("artshuf:")) { List<Catalog.Track> t = new ArrayList<>(); for (Catalog.Album a : k.albumsOf(id.substring(8))) t.addAll(a.tracks); playList(t, 0, true); }
            else if (id.startsWith("tr:")) { int bar = id.lastIndexOf('|'); Catalog.Album a = k.album(id.substring(3, bar)); if (a != null) playList(a.tracks, Integer.parseInt(id.substring(bar + 1)), false); }
            else if (id.startsWith("pt:")) { int bar = id.lastIndexOf('|'); Catalog.Playlist p = k.playlist(id.substring(3, bar)); if (p != null) playList(k.playlistTracks(p), Integer.parseInt(id.substring(bar + 1)), false); }
            else if (id.startsWith("alb:")) { Catalog.Album a = k.album(id.substring(4)); if (a != null) playList(a.tracks, 0, false); }
        } catch (Exception e) { emitError("Lecture impossible"); }
    }

    void playFromSearch(String q) {
        Catalog k = Catalog.get(this);
        if (q == null || q.trim().isEmpty()) { if (items.isEmpty() && !restoreQueue()) playList(k.all(), 0, true); else resume(); return; }
        Catalog.Hit h = k.search(q);
        if (h != null) playList(h.tracks, h.start, h.shuffle);
        else session.setPlaybackState(new PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_ERROR, 0, 0f)
                .setErrorMessage(PlaybackStateCompat.ERROR_CODE_NOT_SUPPORTED, "« " + q + " » n'est pas dans la discothèque").build());
    }

    /* ---------- dernière file de lecture, gardée pour reprendre appli fermée (widget, voiture) ---------- */
    private void saveQueue() {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            org.json.JSONArray a = new org.json.JSONArray();
            for (Item it : items) {
                org.json.JSONObject j = new org.json.JSONObject();
                j.put("id", it.id); j.put("uri", it.uri); j.put("title", it.title); j.put("artist", it.artist); j.put("album", it.album); j.put("dir", it.dir);
                if (!Double.isNaN(it.loud)) j.put("loud", it.loud);
                a.put(j);
            }
            o.put("items", a);
            o.put("index", index);
            o.put("pos", mp != null ? mp.getCurrentPosition() : 0);
            try (java.io.FileOutputStream f = new java.io.FileOutputStream(new java.io.File(getCacheDir(), "queue.json"))) { f.write(o.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        } catch (Exception ignored) { }
    }

    private boolean restoreQueue() {
        try {
            java.io.File f = new java.io.File(getCacheDir(), "queue.json");
            if (!f.exists()) return false;
            byte[] b = new byte[(int) f.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) { int o = 0, n; while (o < b.length && (n = in.read(b, o, b.length - o)) > 0) o += n; }
            org.json.JSONObject o = new org.json.JSONObject(new String(b, java.nio.charset.StandardCharsets.UTF_8));
            org.json.JSONArray a = o.getJSONArray("items");
            List<Item> l = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject j = a.getJSONObject(i);
                Item it = new Item();
                it.id = j.optString("id"); it.uri = j.optString("uri"); it.title = j.optString("title"); it.artist = j.optString("artist"); it.album = j.optString("album"); it.dir = j.optString("dir");
                it.loud = j.has("loud") ? j.optDouble("loud", Double.NaN) : Double.NaN;
                l.add(it);
            }
            if (l.isEmpty()) return false;
            int idx = Math.max(0, Math.min(o.optInt("index", 0), l.size() - 1)), pos = o.optInt("pos", 0);
            setQueue(l, idx, true, false);
            if (mp != null && pos > 3000) mp.seekTo(pos);
            SillonPlugin p = SillonPlugin.instance;
            if (p != null) p.emitPlayerQueue(items, index);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /* ---------- file et commandes ---------- */

    void setQueue(List<Item> list, int idx, boolean play, boolean keep) {
        String cur = (index >= 0 && index < items.size()) ? items.get(index).uri : null;
        items.clear();
        items.addAll(list);
        if (items.isEmpty()) {
            stopAll();
            return;
        }
        idx = Math.max(0, Math.min(idx, items.size() - 1));
        if (keep && cur != null && cur.equals(items.get(idx).uri) && mp != null) {
            index = idx;
            /* volume mesuré entre-temps : correction appliquée au morceau en cours */
            Fx f = fx.get(mp);
            double nl = items.get(idx).loud;
            if (f == null || (Double.isNaN(f.loud) != Double.isNaN(nl)) || (!Double.isNaN(nl) && Math.abs(f.loud - nl) > 0.05)) attachFx(mp, items.get(idx));
            updateMeta();
            prepareNext();
            emitState(true);
            return;
        }
        load(idx, play);
    }

    void jump(int idx) {
        if (idx >= 0 && idx < items.size()) load(idx, true);
    }

    void setRepeat(String r) {
        repeat = r == null ? "off" : r;
        prepareNext();
    }

    private MediaPlayer create(Item it) throws Exception {
        MediaPlayer p = new MediaPlayer();
        p.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
        p.setWakeMode(getApplicationContext(), android.os.PowerManager.PARTIAL_WAKE_LOCK);
        p.setDataSource(this, Uri.parse(it.uri));
        p.setOnCompletionListener(this::onDone);
        p.setOnErrorListener((m, w, e) -> {
            if (m != mp) return true;
            emitError("Lecture impossible de « " + it.title + " »");
            main.postDelayed(() -> next(true), 300);
            return true;
        });
        p.prepare();
        attachFx(p, it);
        return p;
    }

    /* fin d'un morceau : si le suivant était prêt, Android l'a déjà lancé ; on le reprend comme morceau courant */
    private void onDone(MediaPlayer m) {
        if (m != mp) return;
        if (nextMp != null) {
            MediaPlayer old = mp;
            mp = nextMp;
            index = nextIdx;
            saveQueue();
            nextMp = null;
            nextIdx = -1;
            detachFx(old);
            try { old.release(); } catch (Exception ignored) { }
            updateMeta();
            updateState();
            emitState(true);
            prepareNext();
        } else next(true);
    }

    private void releaseNext() {
        if (nextMp != null) {
            try { if (mp != null) mp.setNextMediaPlayer(null); } catch (Exception ignored) { }
            detachFx(nextMp);
            try { nextMp.release(); } catch (Exception ignored) { }
            nextMp = null;
        }
        nextIdx = -1;
    }

    private void prepareNext() {
        releaseNext();
        if (mp == null || items.isEmpty()) return;
        int n = "one".equals(repeat) ? index : index + 1 < items.size() ? index + 1 : "all".equals(repeat) ? 0 : -1;
        if (n < 0) return;
        try {
            MediaPlayer p = create(items.get(n));
            mp.setNextMediaPlayer(p);
            nextMp = p;
            nextIdx = n;
        } catch (Exception e) {
            nextMp = null;
            nextIdx = -1;
        }
    }

    private void load(int idx, boolean play) {
        releaseMp();
        index = idx;
        saveQueue();
        Item it = items.get(idx);
        try {
            mp = create(it);
            updateMeta();
            if (play) start();
            else updateState();
            prepareNext();
        } catch (Exception e) {
            emitError("Fichier illisible : « " + it.title + " »");
            main.postDelayed(() -> next(true), 300);
        }
        emitState(true);
    }

    private void start() {
        if (mp == null || !requestFocus()) return;
        mp.start();
        updateState();
        emitState(true);
    }

    void resume() {
        if (items.isEmpty()) { if (!restoreQueue()) stopAll(); return; }
        if (mp == null && index >= 0 && index < items.size()) {
            load(index, true);
            return;
        }
        start();
    }

    void pause() {
        resumeOnFocus = false;
        if (mp != null && mp.isPlaying()) mp.pause();
        saveQueue();
        updateState();
        emitState(true);
    }

    void toggle() {
        if (mp != null && mp.isPlaying()) pause();
        else resume();
    }

    void next(boolean auto) {
        if (items.isEmpty()) return;
        if (auto && "one".equals(repeat)) {
            load(index, true);
            return;
        }
        if (index + 1 < items.size()) load(index + 1, true);
        else if ("all".equals(repeat)) load(0, true);
        else if (auto) {
            if (mp != null) mp.seekTo(0);
            pause();
        } else load(index, false);
    }

    void prev() {
        if (items.isEmpty()) return;
        if (mp != null && mp.getCurrentPosition() > 3000 || index <= 0) {
            seek(0);
            return;
        }
        load(index - 1, true);
    }

    void seek(int ms) {
        if (mp != null) mp.seekTo(ms);
        updateState();
        emitState(true);
    }

    void stopAll() {
        if (!items.isEmpty()) saveQueue();
        releaseMp();
        items.clear();
        index = -1;
        SillonWidget.push(this, null, null, false, null);
        abandonFocus();
        emitState(true);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void releaseMp() {
        releaseNext();
        if (mp != null) {
            detachFx(mp);
            try {
                mp.release();
            } catch (Exception ignored) {
            }
            mp = null;
        }
    }

    /* ---------- focus audio (GPS, appels) ---------- */

    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        if (change == AudioManager.AUDIOFOCUS_LOSS) {
            pause();
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            boolean was = mp != null && mp.isPlaying();
            pause();
            resumeOnFocus = was;
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            if (mp != null) { float v = 0.3f * volOf(mp); mp.setVolume(v, v); }
        } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
            if (mp != null) { float v = volOf(mp); mp.setVolume(v, v); }
            if (resumeOnFocus) {
                resumeOnFocus = false;
                start();
            }
        }
    };

    private boolean requestFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusReq == null) {
                focusReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setOnAudioFocusChangeListener(focusListener).build();
            }
            return am.requestAudioFocus(focusReq) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
        return am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonFocus() {
        if (Build.VERSION.SDK_INT >= 26 && focusReq != null) am.abandonAudioFocusRequest(focusReq);
        else am.abandonAudioFocus(focusListener);
    }

    /* ---------- titre, pochette, état pour la voiture et la notification ---------- */

    private Bitmap loadArt(String dir) {
        if (dir == null || dir.isEmpty()) return null;
        if (dir.equals(artDir)) return art;
        artDir = dir;
        art = null;
        try {
            String s = getSharedPreferences("sillon", MODE_PRIVATE).getString("folderUri", null);
            if (s == null) return null;
            DocumentFile d = DocumentFile.fromTreeUri(this, Uri.parse(s));
            for (String part : dir.split("/")) {
                if (d == null) return null;
                d = d.findFile(part);
            }
            DocumentFile f = d != null ? d.findFile("folder.jpg") : null;
            if (f == null) return null;
            try (InputStream in = getContentResolver().openInputStream(f.getUri())) {
                art = BitmapFactory.decodeStream(in);
            }
        } catch (Exception ignored) {
        }
        return art;
    }

    private void updateMeta() {
        if (index < 0 || index >= items.size()) return;
        Item it = items.get(index);
        MediaMetadataCompat.Builder b = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, it.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, it.artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, it.album)
                .putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, index + 1)
                .putLong(MediaMetadataCompat.METADATA_KEY_NUM_TRACKS, items.size())
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, mp != null ? mp.getDuration() : 0);
        Bitmap bm = loadArt(it.dir);
        if (bm != null) b.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bm);
        session.setMetadata(b.build());
        updateState();
    }

    private void updateState() {
        boolean playing = mp != null && mp.isPlaying();
        session.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE | PlaybackStateCompat.ACTION_PLAY_PAUSE
                        | PlaybackStateCompat.ACTION_SKIP_TO_NEXT | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackStateCompat.ACTION_SEEK_TO | PlaybackStateCompat.ACTION_STOP
                        | PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID | PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH)
                .setState(playing ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED,
                        mp != null ? mp.getCurrentPosition() : 0, playing ? 1f : 0f)
                .build());
        goForeground();
        Item cur = (index >= 0 && index < items.size()) ? items.get(index) : null;
        SillonWidget.push(this, cur != null ? cur.title : null, cur != null ? cur.artist : null, playing, cur != null ? loadArt(cur.dir) : null);
    }

    private PendingIntent action(String a, int code) {
        Intent i = new Intent(this, PlayerService.class).setAction(a);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void goForeground() {
        Item it = (index >= 0 && index < items.size()) ? items.get(index) : null;
        boolean playing = mp != null && mp.isPlaying();
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent content = open != null ? PendingIntent.getActivity(this, 9, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE) : null;
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH)
                .setSmallIcon(getApplicationInfo().icon)
                .setContentTitle(it != null ? it.title : "Sillon")
                .setContentText(it != null ? (it.artist + (it.album != null && !it.album.isEmpty() ? " · " + it.album : "")) : "")
                .setLargeIcon(it != null ? loadArt(it.dir) : null)
                .setContentIntent(content)
                .setDeleteIntent(action(A_STOP, 4))
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setOngoing(playing)
                .addAction(android.R.drawable.ic_media_previous, "Précédent", action(A_PREV, 1))
                .addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, playing ? "Pause" : "Lecture", action(A_TOGGLE, 2))
                .addAction(android.R.drawable.ic_media_next, "Suivant", action(A_NEXT, 3))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Fermer", action(A_STOP, 5))
                .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
        Notification n = b.build();
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(NOTIF, n);
        } catch (Exception e) {
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF, n);
        }
    }

    /* ---------- vers l'app ---------- */

    private void emitState(boolean full) {
        SillonPlugin p = SillonPlugin.instance;
        if (p == null) return;
        int pos = 0, dur = 0;
        boolean playing = false;
        try {
            if (mp != null) {
                pos = mp.getCurrentPosition();
                dur = mp.getDuration();
                playing = mp.isPlaying();
            }
        } catch (Exception ignored) {
        }
        p.emitPlayerState(index, pos, dur, playing, items.size());
    }

    private void emitError(String m) {
        SillonPlugin p = SillonPlugin.instance;
        if (p != null) p.emitPlayerError(m);
    }

    @Override
    public void onDestroy() {
        main.removeCallbacks(ticker);
        try {
            unregisterReceiver(noisy);
        } catch (Exception ignored) {
        }
        releaseMp();
        abandonFocus();
        session.setActive(false);
        session.release();
        instance = null;
        super.onDestroy();
    }
}
