package fr.aflament.sillon;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/* Résumé de la discothèque écrit par l'appli (library.json dans le cache) : de quoi parcourir les albums,
   artistes et listes depuis Android Auto, et retrouver des morceaux par la voix, appli fermée. */
final class Catalog {
    static final class Track { String id, title, artist, uri, dir; double loud = Double.NaN; int no; }
    static final class Album { String key, artist, album, year, dir; final List<Track> tracks = new ArrayList<>(); }
    static final class Playlist { String id, name; final List<String> ids = new ArrayList<>(); }

    final List<Album> albums = new ArrayList<>();
    final List<Playlist> playlists = new ArrayList<>();
    final Map<String, Track> byId = new LinkedHashMap<>();
    final Map<String, Album> albumOf = new LinkedHashMap<>();
    private static Catalog cache;
    private static long cacheStamp = -1;

    static File file(Context c) { return new File(c.getCacheDir(), "library.json"); }

    static void write(Context c, String json) throws Exception {
        File tmp = new File(c.getCacheDir(), "library.json.tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(json.getBytes(StandardCharsets.UTF_8)); }
        if (!tmp.renameTo(file(c))) throw new Exception("Écriture du catalogue impossible");
        synchronized (Catalog.class) { cache = null; }
    }

    static synchronized Catalog get(Context c) {
        File f = file(c);
        long st = f.exists() ? f.lastModified() * 31 + f.length() : 0;
        if (cache != null && st == cacheStamp) return cache;
        Catalog k = new Catalog();
        try {
            if (f.exists()) {
                byte[] b = new byte[(int) f.length()];
                try (FileInputStream in = new FileInputStream(f)) { int o = 0, n; while (o < b.length && (n = in.read(b, o, b.length - o)) > 0) o += n; }
                k.parse(new JSONObject(new String(b, StandardCharsets.UTF_8)));
            }
        } catch (Exception ignored) { }
        cache = k;
        cacheStamp = st;
        return k;
    }

    private void parse(JSONObject o) {
        JSONArray al = o.optJSONArray("albums");
        if (al != null) for (int i = 0; i < al.length(); i++) {
            JSONObject a = al.optJSONObject(i);
            if (a == null) continue;
            Album A = new Album();
            A.key = a.optString("k"); A.artist = a.optString("ar"); A.album = a.optString("al"); A.year = a.optString("y"); A.dir = a.optString("d");
            JSONArray tr = a.optJSONArray("tr");
            if (tr != null) for (int j = 0; j < tr.length(); j++) {
                JSONObject t = tr.optJSONObject(j);
                if (t == null || t.optString("u").isEmpty()) continue;
                Track T = new Track();
                T.id = t.optString("i"); T.title = t.optString("t"); T.artist = t.optString("a", A.artist); if (T.artist.isEmpty()) T.artist = A.artist;
                T.uri = t.optString("u"); T.dir = t.optString("d", A.dir); if (T.dir.isEmpty()) T.dir = A.dir; T.no = t.optInt("n", j + 1);
                if (t.has("l") && !t.isNull("l")) T.loud = t.optDouble("l", Double.NaN);
                A.tracks.add(T);
                byId.put(T.id, T);
                albumOf.put(T.id, A);
            }
            if (!A.tracks.isEmpty()) albums.add(A);
        }
        JSONArray pl = o.optJSONArray("pls");
        if (pl != null) for (int i = 0; i < pl.length(); i++) {
            JSONObject p = pl.optJSONObject(i);
            if (p == null) continue;
            Playlist P = new Playlist();
            P.id = p.optString("i"); P.name = p.optString("n");
            JSONArray it = p.optJSONArray("it");
            if (it != null) for (int j = 0; j < it.length(); j++) P.ids.add(it.optString(j));
            playlists.add(P);
        }
    }

    Album album(String key) { for (Album a : albums) if (a.key.equals(key)) return a; return null; }
    Playlist playlist(String id) { for (Playlist p : playlists) if (p.id.equals(id)) return p; return null; }

    List<String> artists() {
        List<String> l = new ArrayList<>();
        for (Album a : albums) if (!a.artist.isEmpty() && !l.contains(a.artist)) l.add(a.artist);
        Collections.sort(l, (x, y) -> norm(x).compareTo(norm(y)));
        return l;
    }

    List<Album> albumsOf(String artist) {
        List<Album> l = new ArrayList<>();
        for (Album a : albums) if (a.artist.equals(artist)) l.add(a);
        Collections.sort(l, (x, y) -> x.year.compareTo(y.year));
        return l;
    }

    List<Track> playlistTracks(Playlist p) {
        List<Track> l = new ArrayList<>();
        for (String id : p.ids) { Track t = byId.get(id); if (t != null) l.add(t); }
        return l;
    }

    List<Track> all() { return new ArrayList<>(byId.values()); }

    PlayerService.Item item(Track t) {
        PlayerService.Item it = new PlayerService.Item();
        Album a = albumOf.get(t.id);
        it.id = t.id; it.uri = t.uri; it.title = t.title; it.artist = t.artist; it.album = a != null ? a.album : ""; it.dir = t.dir; it.loud = t.loud;
        return it;
    }

    static String norm(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase();
        return n.replaceAll("[^a-z0-9]+", " ").trim();
    }

    /* recherche vocale (« joue … sur Sillon ») : album, puis artiste, puis titre */
    static final class Hit { List<Track> tracks; int start; boolean shuffle; }
    Hit search(String query) {
        String q = norm(query);
        Hit h = new Hit();
        if (q.isEmpty()) return null;
        Album bestA = null; int bestAs = 0;
        for (Album a : albums) { int s = score(norm(a.album), q); if (s > bestAs) { bestAs = s; bestA = a; } }
        String bestAr = null; int bestArs = 0;
        for (String ar : artists()) { int s = score(norm(ar), q); if (s > bestArs) { bestArs = s; bestAr = ar; } }
        Track bestT = null; int bestTs = 0;
        for (Track t : byId.values()) { int s = score(norm(t.title), q); if (s > bestTs) { bestTs = s; bestT = t; } }
        if (bestAs >= 2 && bestAs >= bestArs && bestAs >= bestTs) { h.tracks = bestA.tracks; h.start = 0; return h; }
        if (bestArs >= 2 && bestArs >= bestTs) {
            h.tracks = new ArrayList<>();
            for (Album a : albumsOf(bestAr)) h.tracks.addAll(a.tracks);
            h.shuffle = true; return h;
        }
        if (bestTs >= 2) { Album a = albumOf.get(bestT.id); h.tracks = a.tracks; h.start = a.tracks.indexOf(bestT); return h; }
        return null;
    }

    /* 3 = identique, 2 = l'un contient l'autre, 1 = tous les mots présents */
    private static int score(String name, String q) {
        if (name.isEmpty()) return 0;
        if (name.equals(q)) return 3;
        if (name.contains(q) || (q.length() > 3 && q.contains(name) && name.length() > 3)) return 2;
        for (String w : q.split(" ")) if (w.length() > 2 && !name.contains(w)) return 0;
        return 1;
    }
}
