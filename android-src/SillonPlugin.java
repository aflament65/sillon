package fr.aflament.sillon;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import java.io.ByteArrayOutputStream;
import org.json.JSONObject;
import androidx.core.app.NotificationManagerCompat;
import android.os.Looper;
import android.os.Handler;
import android.media.session.PlaybackState;
import android.media.session.MediaSessionManager;
import android.media.session.MediaController;
import android.media.MediaMetadata;
import android.graphics.Bitmap;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.media.projection.MediaProjectionManager;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.net.Uri;
import android.os.Build;
import android.util.Base64;
import android.view.WindowManager;

import androidx.activity.result.ActivityResult;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pont natif de Sillon : choix du dossier de sauvegarde (Music par exemple),
 * écriture des MP3 dans des sous-dossiers Artiste/Album, écran allumé pendant l'enregistrement.
 */
@CapacitorPlugin(
        name = "Sillon",
        permissions = {@Permission(alias = "microphone", strings = {Manifest.permission.RECORD_AUDIO})}
)
public class SillonPlugin extends Plugin {

    /** Instance active, utilisée par CaptureService pour transmettre le son capturé. */
    static volatile SillonPlugin instance;

    @Override
    public void load() {
        instance = this;
    }

    private static final String PREFS = "sillon";
    private static final String KEY_URI = "folderUri";

    private final Map<String, DocumentFile> dirCache = new HashMap<>();
    private final Map<String, Uri> fileCache = new HashMap<>();

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private Uri savedFolder() {
        String s = prefs().getString(KEY_URI, null);
        if (s == null) return null;
        Uri u = Uri.parse(s);
        for (UriPermission p : getContext().getContentResolver().getPersistedUriPermissions()) {
            if (p.getUri().equals(u) && p.isWritePermission()) return u;
        }
        return null;
    }

    private JSObject folderInfo(Uri u) {
        JSObject o = new JSObject();
        if (u == null) {
            o.put("uri", null);
            o.put("name", null);
            return o;
        }
        DocumentFile d = DocumentFile.fromTreeUri(getContext(), u);
        o.put("uri", u.toString());
        o.put("name", d != null ? d.getName() : "Dossier choisi");
        return o;
    }

    @PluginMethod
    public void getFolder(PluginCall call) {
        call.resolve(folderInfo(savedFolder()));
    }

    @PluginMethod
    public void pickFolder(PluginCall call) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(call, i, "folderResult");
    }

    @ActivityCallback
    private void folderResult(PluginCall call, ActivityResult result) {
        if (call == null) return;
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null || result.getData().getData() == null) {
            call.reject("Aucun dossier choisi");
            return;
        }
        Uri u = result.getData().getData();
        try {
            getContext().getContentResolver().takePersistableUriPermission(u,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException e) {
            call.reject("Android refuse l'accès à ce dossier");
            return;
        }
        prefs().edit().putString(KEY_URI, u.toString()).apply();
        dirCache.clear();
        fileCache.clear();
        call.resolve(folderInfo(u));
    }

    /** Renvoie (en le créant si besoin) le sous-dossier "a/b/c" du dossier choisi. */
    private DocumentFile dir(Uri root, String rel) {
        if (rel.isEmpty()) {
            DocumentFile d = dirCache.get("");
            if (d == null) {
                d = DocumentFile.fromTreeUri(getContext(), root);
                dirCache.put("", d);
            }
            return d;
        }
        DocumentFile cached = dirCache.get(rel);
        if (cached != null) return cached;
        int slash = rel.lastIndexOf('/');
        String parentRel = slash < 0 ? "" : rel.substring(0, slash);
        String name = slash < 0 ? rel : rel.substring(slash + 1);
        DocumentFile parent = dir(root, parentRel);
        if (parent == null) return null;
        DocumentFile d = parent.findFile(name);
        if (d == null || !d.isDirectory()) d = parent.createDirectory(name);
        if (d != null) dirCache.put(rel, d);
        return d;
    }

    @PluginMethod
    public void writeFile(PluginCall call) {
        final String path = call.getString("path");
        final String data = call.getString("data", "");
        final boolean append = Boolean.TRUE.equals(call.getBoolean("append", false));
        final String mime = call.getString("mime", "application/octet-stream");
        final Uri root = savedFolder();
        if (root == null) {
            call.reject("Choisis d'abord un dossier de sauvegarde");
            return;
        }
        if (path == null || path.isEmpty()) {
            call.reject("Chemin manquant");
            return;
        }
        new Thread(() -> {
            try {
                Uri target = append ? fileCache.get(path) : null;
                if (target == null) {
                    int slash = path.lastIndexOf('/');
                    String parentRel = slash < 0 ? "" : path.substring(0, slash);
                    String name = slash < 0 ? path : path.substring(slash + 1);
                    DocumentFile parent = dir(root, parentRel);
                    if (parent == null) throw new Exception("Impossible de créer le dossier " + parentRel);
                    DocumentFile existing = parent.findFile(name);
                    if (existing != null && !append) existing.delete();
                    DocumentFile f = (existing != null && append) ? existing : parent.createFile(mime, name);
                    if (f == null) throw new Exception("Impossible de créer " + name);
                    target = f.getUri();
                    fileCache.put(path, target);
                }
                byte[] bytes = Base64.decode(data, Base64.DEFAULT);
                try (OutputStream os = getContext().getContentResolver().openOutputStream(target, append ? "wa" : "wt")) {
                    if (os == null) throw new Exception("Écriture refusée");
                    os.write(bytes);
                }
                JSObject o = new JSObject();
                o.put("uri", target.toString());
                call.resolve(o);
            } catch (Exception e) {
                call.reject(e.getMessage() != null ? e.getMessage() : "Erreur d'écriture");
            }
        }).start();
    }

    @PluginMethod
    public void keepAwake(PluginCall call) {
        final boolean on = Boolean.TRUE.equals(call.getBoolean("on", true));
        getActivity().runOnUiThread(() -> {
            if (on) getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            call.resolve();
        });
    }

    /** Scanner de codes-barres de Google Play Services : bonne caméra, mise au point et zoom automatiques. */
    @PluginMethod
    public void scanBarcode(PluginCall call) {
        GmsBarcodeScannerOptions opts = new GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E)
                .enableAutoZoom()
                .build();
        GmsBarcodeScanning.getClient(getActivity(), opts).startScan()
                .addOnSuccessListener(b -> {
                    JSObject o = new JSObject();
                    o.put("value", b.getRawValue());
                    call.resolve(o);
                })
                .addOnCanceledListener(() -> call.reject("Scan annulé", "CANCELED"))
                .addOnFailureListener(e -> call.reject(e.getMessage() != null ? e.getMessage() : "Scanner indisponible", "FAILED"));
    }

    /** Ouvre un lien (dépôt GitHub, téléchargement de la mise à jour) dans le navigateur du téléphone. */
    @PluginMethod
    public void openUrl(PluginCall call) {
        String url = call.getString("url");
        if (url == null || !url.startsWith("https://")) {
            call.reject("Lien invalide");
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getActivity().startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject("Aucun navigateur pour ouvrir ce lien");
        }
    }

    /* ---------- Capture du son joué par le téléphone (Android 10+) ---------- */

    private boolean captureBubble = false;

    @PluginMethod
    public void canDrawOverlays(PluginCall call) {
        JSObject o = new JSObject();
        o.put("granted", Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(getContext()));
        call.resolve(o);
    }

    @PluginMethod
    public void openOverlaySettings(PluginCall call) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getContext().getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getActivity().startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject("Réglage introuvable : " + e.getMessage());
        }
    }

    @PluginMethod
    public void startCapture(PluginCall call) {
        captureBubble = Boolean.TRUE.equals(call.getBoolean("bubble", false));
        if (Build.VERSION.SDK_INT < 29) {
            call.reject("Il faut Android 10 ou plus pour enregistrer le son du téléphone", "UNSUPPORTED");
            return;
        }
        if (getPermissionState("microphone") != PermissionState.GRANTED) {
            requestPermissionForAlias("microphone", call, "micPermResult");
            return;
        }
        askProjection(call);
    }

    @PermissionCallback
    private void micPermResult(PluginCall call) {
        if (getPermissionState("microphone") == PermissionState.GRANTED) askProjection(call);
        else call.reject("Autorisation d'enregistrer l'audio refusée", "DENIED");
    }

    private void askProjection(PluginCall call) {
        MediaProjectionManager mpm = (MediaProjectionManager) getContext().getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        /* Android 14+ : on impose « tout l'écran », sinon « une seule appli » ne capte que le son de cette appli (silence) */
        Intent ask = Build.VERSION.SDK_INT >= 34
                ? mpm.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
                : mpm.createScreenCaptureIntent();
        startActivityForResult(call, ask, "projectionResult");
    }

    @ActivityCallback
    private void projectionResult(PluginCall call, ActivityResult result) {
        if (call == null) return;
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            call.reject("Capture refusée", "DENIED");
            return;
        }
        Intent i = new Intent(getContext(), CaptureService.class);
        i.putExtra("code", result.getResultCode());
        i.putExtra("data", result.getData());
        i.putExtra("bubble", captureBubble);
        try {
            ContextCompat.startForegroundService(getContext(), i);
            call.resolve();
        } catch (Exception e) {
            call.reject("Démarrage de la capture impossible : " + e.getMessage(), "FAILED");
        }
    }

    @PluginMethod
    public void stopCapture(PluginCall call) {
        getContext().stopService(new Intent(getContext(), CaptureService.class));
        call.resolve();
    }

    void emitPcm(byte[] buf, int len) {
        JSObject o = new JSObject();
        o.put("data", Base64.encodeToString(buf, 0, len, Base64.NO_WRAP));
        notifyListeners("pcm", o);
    }

    void emitCaptureInfo(long bytes, int peak, String players, int recState, int rate) {
        JSObject o = new JSObject();
        o.put("bytes", bytes);
        o.put("peak", peak);
        o.put("players", players);
        o.put("recording", recState == android.media.AudioRecord.RECORDSTATE_RECORDING);
        o.put("rate", rate);
        notifyListeners("captureInfo", o);
    }

    void emitCaptureArmed() {
        notifyListeners("captureArmed", new JSObject());
    }

    void emitCaptureStarted() {
        notifyListeners("captureStarted", new JSObject());
    }

    void emitCaptureStopped(String reason) {
        JSObject o = new JSObject();
        o.put("reason", reason);
        notifyListeners("captureStopped", o);
    }

    /* ---------- Fichiers : liste d'un dossier, lecture par blocs, suppression ---------- */

    /** Dossier existant "a/b" sous le dossier choisi, sans le créer. */
    private DocumentFile findDir(Uri root, String rel) {
        DocumentFile d = DocumentFile.fromTreeUri(getContext(), root);
        if (rel == null || rel.isEmpty()) return d;
        for (String part : rel.split("/")) {
            if (d == null) return null;
            DocumentFile c = d.findFile(part);
            if (c == null || !c.isDirectory()) return null;
            d = c;
        }
        return d;
    }

    @PluginMethod
    public void listDir(PluginCall call) {
        final String path = call.getString("path", "");
        final Uri root = savedFolder();
        if (root == null) {
            call.reject("Choisis d'abord un dossier de sauvegarde");
            return;
        }
        new Thread(() -> {
            JSArray arr = new JSArray();
            DocumentFile d = findDir(root, path);
            if (d != null) {
                for (DocumentFile f : d.listFiles()) {
                    if (!f.isFile()) continue;
                    JSObject o = new JSObject();
                    o.put("name", f.getName());
                    o.put("uri", f.getUri().toString());
                    o.put("size", f.length());
                    arr.put(o);
                }
            }
            JSObject res = new JSObject();
            res.put("exists", d != null);
            res.put("files", arr);
            call.resolve(res);
        }).start();
    }

    @PluginMethod
    public void readFile(PluginCall call) {
        final String uri = call.getString("uri");
        final long offset = call.getData().optLong("offset", 0L);
        final int length = call.getData().optInt("length", 786432);
        if (uri == null) {
            call.reject("Fichier manquant");
            return;
        }
        new Thread(() -> {
            try (InputStream in = getContext().getContentResolver().openInputStream(Uri.parse(uri))) {
                if (in == null) throw new Exception("Lecture refusée");
                long skipped = 0;
                while (skipped < offset) {
                    long k = in.skip(offset - skipped);
                    if (k <= 0) break;
                    skipped += k;
                }
                byte[] buf = new byte[length];
                int got = 0;
                while (got < length) {
                    int k = in.read(buf, got, length - got);
                    if (k < 0) break;
                    got += k;
                }
                JSObject o = new JSObject();
                o.put("data", Base64.encodeToString(buf, 0, got, Base64.NO_WRAP));
                o.put("eof", got < length);
                call.resolve(o);
            } catch (Exception e) {
                call.reject(e.getMessage() != null ? e.getMessage() : "Lecture impossible");
            }
        }).start();
    }

    /** Supprime un dossier (album) et son contenu ; retire aussi le dossier parent (artiste) s'il devient vide. */
    @PluginMethod
    public void deletePath(PluginCall call) {
        final String path = call.getString("path", "");
        final boolean prune = Boolean.TRUE.equals(call.getBoolean("pruneParent", false));
        final boolean onlyEmpty = Boolean.TRUE.equals(call.getBoolean("onlyIfEmpty", false));
        final Uri root = savedFolder();
        if (root == null || path == null || path.isEmpty()) {
            call.reject("Dossier introuvable");
            return;
        }
        new Thread(() -> {
            try {
                DocumentFile d = findDir(root, path);
                boolean ok = false;
                int left = 0;
                if (d != null) {
                    if (onlyEmpty) {
                        /* On ne supprime le dossier que s'il ne reste rien d'autre que la pochette */
                        DocumentFile cover = null;
                        for (DocumentFile f : d.listFiles()) {
                            if (f.isFile() && "folder.jpg".equalsIgnoreCase(f.getName())) cover = f;
                            else left++;
                        }
                        if (left == 0) {
                            if (cover != null) cover.delete();
                            ok = d.delete();
                        }
                    } else ok = d.delete();
                }
                if (prune) {
                    int k = path.lastIndexOf('/');
                    if (k > 0) {
                        DocumentFile parent = findDir(root, path.substring(0, k));
                        if (parent != null && parent.listFiles().length == 0) parent.delete();
                    }
                }
                dirCache.clear();
                fileCache.clear();
                JSObject o = new JSObject();
                o.put("deleted", ok);
                o.put("left", left);
                call.resolve(o);
            } catch (Exception e) {
                call.reject(e.getMessage() != null ? e.getMessage() : "Suppression impossible");
            }
        }).start();
    }

    /* Liste tous les MP3 du dossier de sauvegarde (sous-dossiers compris) */
    private void scanInto(DocumentFile d, String rel, int depth, JSArray out) {
        if (d == null || depth > 4) return;
        for (DocumentFile f : d.listFiles()) {
            String n = f.getName();
            if (n == null) continue;
            if (f.isDirectory()) scanInto(f, rel.isEmpty() ? n : rel + "/" + n, depth + 1, out);
            else if (n.toLowerCase().endsWith(".mp3")) {
                JSObject o = new JSObject();
                o.put("dir", rel);
                o.put("name", n);
                o.put("uri", f.getUri().toString());
                o.put("size", f.length());
                out.put(o);
            }
        }
    }

    @PluginMethod
    public void scanMp3(PluginCall call) {
        final Uri root = savedFolder();
        if (root == null) {
            call.reject("Choisis d'abord un dossier de sauvegarde");
            return;
        }
        new Thread(() -> {
            try {
                JSArray arr = new JSArray();
                scanInto(DocumentFile.fromTreeUri(getContext(), root), "", 0, arr);
                JSObject res = new JSObject();
                res.put("files", arr);
                call.resolve(res);
            } catch (Exception e) {
                call.reject(e.getMessage() != null ? e.getMessage() : "Lecture du dossier impossible");
            }
        }).start();
    }

    @PluginMethod
    public void renameFile(PluginCall call) {
        final String uri = call.getString("uri");
        final String name = call.getString("name");
        if (uri == null || name == null || name.isEmpty()) {
            call.reject("Fichier manquant");
            return;
        }
        try {
            Uri u = DocumentsContract.renameDocument(getContext().getContentResolver(), Uri.parse(uri), name);
            fileCache.clear();
            if (u == null) throw new Exception("Renommage refusé");
            JSObject o = new JSObject();
            o.put("uri", u.toString());
            call.resolve(o);
        } catch (Exception e) {
            call.reject(e.getMessage() != null ? e.getMessage() : "Renommage impossible");
        }
    }

    @PluginMethod
    public void deleteFile(PluginCall call) {
        String uri = call.getString("uri");
        try {
            boolean ok = DocumentsContract.deleteDocument(getContext().getContentResolver(), Uri.parse(uri));
            fileCache.values().remove(Uri.parse(uri));
            JSObject o = new JSObject();
            o.put("deleted", ok);
            call.resolve(o);
        } catch (Exception e) {
            call.reject(e.getMessage() != null ? e.getMessage() : "Suppression impossible");
        }
    }

    /* ---------- Lecteur : délégué au service de lecture (arrière-plan, Bluetooth, voiture) ---------- */

    private List<PlayerService.Item> parseItems(JSArray arr) {
        List<PlayerService.Item> l = new ArrayList<>();
        if (arr == null) return l;
        for (int i = 0; i < arr.length(); i++) {
            try {
                org.json.JSONObject o = arr.getJSONObject(i);
                PlayerService.Item it = new PlayerService.Item();
                it.uri = o.optString("uri");
                it.title = o.optString("title");
                it.artist = o.optString("artist");
                it.album = o.optString("album");
                it.dir = o.optString("dir");
                l.add(it);
            } catch (Exception ignored) {
            }
        }
        return l;
    }

    @PluginMethod
    public void playerSetQueue(PluginCall call) {
        final List<PlayerService.Item> l = parseItems(call.getArray("items"));
        final int idx = call.getInt("index", 0);
        final boolean play = Boolean.TRUE.equals(call.getBoolean("play", true));
        final boolean keep = Boolean.TRUE.equals(call.getBoolean("keep", false));
        getActivity().runOnUiThread(() -> {
            PlayerService s = PlayerService.instance;
            if (s == null) {
                if (l.isEmpty()) {
                    call.resolve();
                    return;
                }
                PlayerService.pendingItems = l;
                PlayerService.pendingIndex = idx;
                try {
                    ContextCompat.startForegroundService(getContext(), new Intent(getContext(), PlayerService.class));
                    call.resolve();
                } catch (Exception e) {
                    call.reject("Lecteur indisponible : " + e.getMessage());
                }
                return;
            }
            s.setQueue(l, idx, play, keep);
            call.resolve();
        });
    }

    private interface Cmd {
        void run(PlayerService s);
    }

    private void withPlayer(PluginCall call, Cmd c) {
        getActivity().runOnUiThread(() -> {
            PlayerService s = PlayerService.instance;
            if (s != null) c.run(s);
            call.resolve();
        });
    }

    @PluginMethod
    public void playerJump(PluginCall call) {
        final int idx = call.getInt("index", 0);
        withPlayer(call, s -> s.jump(idx));
    }

    @PluginMethod
    public void playerToggle(PluginCall call) {
        withPlayer(call, PlayerService::toggle);
    }

    @PluginMethod
    public void playerNext(PluginCall call) {
        withPlayer(call, s -> s.next(false));
    }

    @PluginMethod
    public void playerPrev(PluginCall call) {
        withPlayer(call, PlayerService::prev);
    }

    @PluginMethod
    public void playerSeek(PluginCall call) {
        final int ms = call.getInt("ms", 0);
        withPlayer(call, s -> s.seek(ms));
    }

    @PluginMethod
    public void playerSetRepeat(PluginCall call) {
        final String m = call.getString("mode", "off");
        withPlayer(call, s -> s.setRepeat(m));
    }

    @PluginMethod
    public void playerStop(PluginCall call) {
        withPlayer(call, PlayerService::stopAll);
    }

    void emitPlayerState(int index, int pos, int dur, boolean playing, int size) {
        JSObject o = new JSObject();
        o.put("index", index);
        o.put("pos", pos);
        o.put("dur", dur);
        o.put("playing", playing);
        o.put("size", size);
        notifyListeners("playerState", o);
    }

    void emitPlayerError(String m) {
        JSObject o = new JSObject();
        o.put("message", m);
        notifyListeners("playerError", o);
    }

    /* ---------- Infos « en cours de lecture » des autres applis (titre, artiste, vignette) ---------- */

    private MediaSessionManager msm;
    private MediaSessionManager.OnActiveSessionsChangedListener sessListener;
    private final Map<MediaController, MediaController.Callback> ctrls = new HashMap<>();
    private String lastNowKey;

    private ComponentName listenerName() {
        return new ComponentName(getContext(), NowPlayingListener.class);
    }

    @PluginMethod
    public void hasNowPlayingAccess(PluginCall call) {
        JSObject o = new JSObject();
        o.put("granted", NotificationManagerCompat.getEnabledListenerPackages(getContext()).contains(getContext().getPackageName()));
        call.resolve(o);
    }

    @PluginMethod
    public void openNowPlayingSettings(PluginCall call) {
        Intent i;
        if (Build.VERSION.SDK_INT >= 30) {
            i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS);
            i.putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, listenerName().flattenToString());
        } else {
            i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            getActivity().startActivity(i);
        } catch (Exception e) {
            Intent j = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            j.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getActivity().startActivity(j);
        }
        call.resolve();
    }

    @PluginMethod
    public void startNowPlaying(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            try {
                msm = (MediaSessionManager) getContext().getSystemService(Context.MEDIA_SESSION_SERVICE);
                lastNowKey = null;
                lastMarkerKey = null;
                attach(msm.getActiveSessions(listenerName()));
                if (sessListener == null) {
                    sessListener = this::attach;
                    msm.addOnActiveSessionsChangedListener(sessListener, listenerName());
                }
                call.resolve();
            } catch (SecurityException e) {
                call.reject("Accès aux notifications non autorisé", "DENIED");
            } catch (Exception e) {
                call.reject(e.getMessage() != null ? e.getMessage() : "Infos de lecture indisponibles");
            }
        });
    }

    @PluginMethod
    public void stopNowPlaying(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            try {
                if (msm != null && sessListener != null) msm.removeOnActiveSessionsChangedListener(sessListener);
            } catch (Exception ignored) {
            }
            sessListener = null;
            for (Map.Entry<MediaController, MediaController.Callback> e : ctrls.entrySet()) {
                try {
                    e.getKey().unregisterCallback(e.getValue());
                } catch (Exception ignored) {
                }
            }
            ctrls.clear();
            lastNowKey = null;
            call.resolve();
        });
    }

    private void attach(List<MediaController> list) {
        if (list == null) return;
        for (MediaController c : list) {
            if (getContext().getPackageName().equals(c.getPackageName())) continue;
            boolean known = false;
            for (MediaController k : ctrls.keySet()) {
                if (k.getSessionToken().equals(c.getSessionToken())) {
                    known = true;
                    break;
                }
            }
            if (known) continue;
            MediaController.Callback cb = new MediaController.Callback() {
                @Override
                public void onMetadataChanged(MediaMetadata m) {
                    emitNow(c);
                }

                @Override
                public void onPlaybackStateChanged(PlaybackState st) {
                    emitNow(c);
                }

                @Override
                public void onSessionDestroyed() {
                    try {
                        c.unregisterCallback(this);
                    } catch (Exception ignored) {
                    }
                    ctrls.remove(c);
                }
            };
            c.registerCallback(cb, new Handler(Looper.getMainLooper()));
            ctrls.put(c, cb);
            emitNow(c);
        }
    }

    private void emitNow(MediaController c) {
        MediaMetadata m = c.getMetadata();
        if (m == null) return;
        PlaybackState st = c.getPlaybackState();
        String title = m.getString(MediaMetadata.METADATA_KEY_TITLE);
        if (title == null) title = m.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        String artist = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (artist == null) artist = m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        if (artist == null) artist = m.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        String album = m.getString(MediaMetadata.METADATA_KEY_ALBUM);
        JSObject o = new JSObject();
        o.put("pkg", c.getPackageName());
        o.put("title", title);
        o.put("artist", artist);
        o.put("album", album);
        o.put("dur", m.getLong(MediaMetadata.METADATA_KEY_DURATION));
        o.put("playing", st != null && st.getState() == PlaybackState.STATE_PLAYING);
        o.put("pos", st != null ? st.getPosition() : 0);
        String key = c.getPackageName() + "|" + title + "|" + artist;
        if (!key.equals(lastNowKey)) {
            lastNowKey = key;
            Bitmap b = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            if (b != null) {
                try {
                    float k = Math.min(1f, 600f / Math.max(b.getWidth(), b.getHeight()));
                    Bitmap sb = k < 1f ? Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * k), Math.round(b.getHeight() * k), true) : b;
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    sb.compress(Bitmap.CompressFormat.JPEG, 88, out);
                    lastArt = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
                    lastArtKey = key;
                    o.put("art", lastArt);
                } catch (Exception ignored) {
                }
            }
        }
        CaptureService cs = CaptureService.instance;
        if (cs != null && cs.isRecording()) {
            if (!key.equals(lastMarkerKey) && (st == null || st.getState() == PlaybackState.STATE_PLAYING)) {
                lastMarkerKey = key;
                try {
                    JSONObject mk = new JSONObject(o.toString());
                    mk.put("t", cs.elapsed());
                    if (!mk.has("art") && key.equals(lastArtKey) && lastArt != null) mk.put("art", lastArt);
                    synchronized (CaptureService.markers) {
                        CaptureService.markers.add(mk);
                    }
                } catch (Exception ignored) {
                }
            }
        }
        notifyListeners("nowPlaying", o);
    }

    private String lastMarkerKey, lastArt, lastArtKey;

    @PluginMethod
    public void getCaptureMarkers(PluginCall call) {
        JSArray a = new JSArray();
        synchronized (CaptureService.markers) {
            for (JSONObject m : CaptureService.markers) a.put(m);
        }
        JSObject o = new JSObject();
        o.put("markers", a);
        call.resolve(o);
    }

    @PluginMethod
    public void readCapture(PluginCall call) {
        final long offset = call.getData().optLong("offset", 0L);
        final int length = call.getData().optInt("length", 1048576);
        new Thread(() -> {
            try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(CaptureService.captureFile(getContext()), "r")) {
                long size = f.length();
                f.seek(Math.min(offset, size));
                int n = (int) Math.max(0, Math.min(length, size - offset));
                byte[] buf = new byte[n];
                f.readFully(buf);
                JSObject o = new JSObject();
                o.put("data", Base64.encodeToString(buf, Base64.NO_WRAP));
                o.put("eof", offset + n >= size);
                o.put("size", size);
                call.resolve(o);
            } catch (Exception e) {
                call.reject("Enregistrement introuvable : " + e.getMessage());
            }
        }).start();
    }
}
