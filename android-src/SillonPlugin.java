package fr.aflament.sillon;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.net.Uri;
import android.util.Base64;
import android.view.WindowManager;

import androidx.activity.result.ActivityResult;
import androidx.documentfile.provider.DocumentFile;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;

import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Pont natif de Sillon : choix du dossier de sauvegarde (Music par exemple),
 * écriture des MP3 dans des sous-dossiers Artiste/Album, écran allumé pendant l'enregistrement.
 */
@CapacitorPlugin(name = "Sillon")
public class SillonPlugin extends Plugin {

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
}
