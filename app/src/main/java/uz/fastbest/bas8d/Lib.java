package uz.fastbest.bas8d;

import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import uz.fastbest.bas8d.PlayerService.Track;

/** Telefondaki medya kitaplığı ve kayıtlı veriler (listeler, geçmiş, yer imleri). */
public class Lib {

    public static final List<Track> audio = new ArrayList<>();
    public static final List<Track> video = new ArrayList<>();
    public static boolean loaded;

    public static class Group {
        public String name = "", key = "";
        public final List<Track> items = new ArrayList<>();
    }

    // ---------- yükleme ----------

    public static synchronized void load(Context c, boolean audioOk, boolean videoOk) {
        List<Track> a = audioOk ? query(c, false) : new ArrayList<>();
        List<Track> v = videoOk ? query(c, true) : new ArrayList<>();
        audio.clear();
        audio.addAll(a);
        video.clear();
        video.addAll(v);
        loaded = true;
    }

    public static List<Track> query(Context c, boolean isVideo) {
        List<Track> out = new ArrayList<>();
        Uri base = isVideo ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] cols = isVideo
                ? new String[]{"_id", "_display_name", "title", "duration", "_data", "_size", "mime_type", "date_added", "width", "height", "bucket_display_name", "orientation"}
                : new String[]{"_id", "_display_name", "title", "duration", "_data", "_size", "mime_type", "date_added", "artist", "album"};
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(base, cols, isVideo ? null : "is_music!=0", null, null);
        } catch (Exception e) {
            try {
                cur = c.getContentResolver().query(base, null, isVideo ? null : "is_music!=0", null, null);
            } catch (Exception ignored) {}
        }
        if (cur == null) return out;
        try {
            while (cur.moveToNext()) {
                Track t = new Track();
                t.video = isVideo;
                t.id = getLong(cur, "_id");
                t.uri = ContentUris.withAppendedId(base, t.id);
                String name = getStr(cur, "_display_name");
                String title = getStr(cur, "title");
                if (isVideo || title.isEmpty()) {
                    title = name;
                    int dot = title.lastIndexOf('.');
                    if (isVideo && dot > 0) title = title.substring(0, dot);
                }
                t.title = title.isEmpty() ? L.t("Adsız") : title;
                t.dur = getLong(cur, "duration");
                t.path = getStr(cur, "_data");
                t.size = getLong(cur, "_size");
                t.mime = getStr(cur, "mime_type");
                t.date = getLong(cur, "date_added");
                if (isVideo) {
                    t.w = (int) getLong(cur, "width");
                    t.h = (int) getLong(cur, "height");
                    long rot = getLong(cur, "orientation");
                    if (rot == 90 || rot == 270) { int tmp = t.w; t.w = t.h; t.h = tmp; }
                    t.artist = "";
                } else {
                    String ar = getStr(cur, "artist");
                    t.artist = ar.isEmpty() || ar.equals("<unknown>") ? L.t("Bilinmeyen sanatçı") : ar;
                    String al = getStr(cur, "album");
                    t.album = al.isEmpty() || al.equals("<unknown>") ? L.t("Bilinmeyen albüm") : al;
                }
                String dir = parent(t.path);
                if (!dir.isEmpty()) t.folder = dir;
                else {
                    String b = getStr(cur, "bucket_display_name");
                    t.folder = b.isEmpty() ? "/Diğer" : "/" + b;
                }
                if (t.path.isEmpty()) t.path = t.folder + "/" + name;
                out.add(t);
            }
        } catch (Exception ignored) {
        } finally {
            cur.close();
        }
        return out;
    }

    private static String getStr(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        if (i < 0) return "";
        try { String s = c.getString(i); return s == null ? "" : s; } catch (Exception e) { return ""; }
    }

    private static long getLong(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        if (i < 0) return 0;
        try { return c.getLong(i); } catch (Exception e) { return 0; }
    }

    public static String parent(String path) {
        if (path == null) return "";
        int i = path.lastIndexOf('/');
        return i > 0 ? path.substring(0, i) : "";
    }

    public static String baseName(String path) {
        if (path == null || path.isEmpty()) return "";
        int i = path.lastIndexOf('/');
        return i >= 0 ? path.substring(i + 1) : path;
    }

    // ---------- gruplama ----------

    public static final int BY_FOLDER = 0, BY_ARTIST = 1, BY_ALBUM = 2;

    public static List<Group> group(List<Track> src, int by) {
        Map<String, Group> m = new LinkedHashMap<>();
        for (Track t : src) {
            String key = by == BY_FOLDER ? t.folder : by == BY_ARTIST ? t.artist : t.album;
            Group g = m.get(key);
            if (g == null) {
                g = new Group();
                g.key = key;
                g.name = by == BY_FOLDER ? folderName(key) : key;
                m.put(key, g);
            }
            g.items.add(t);
        }
        List<Group> out = new ArrayList<>(m.values());
        Collections.sort(out, (x, y) -> x.name.compareToIgnoreCase(y.name));
        for (Group g : out) sortByName(g.items);
        return out;
    }

    public static String folderName(String dir) {
        String n = baseName(dir);
        if (n.equals("0") || n.isEmpty()) return L.t("Dahili hafıza");
        return n;
    }

    public static void sortByName(List<Track> l) {
        Collections.sort(l, (x, y) -> x.title.compareToIgnoreCase(y.title));
    }

    public static void sortByDate(List<Track> l) {
        Collections.sort(l, (x, y) -> Long.compare(y.date, x.date));
    }

    /** L.t("Göz at") için depolama kökü (ör. /storage/emulated/0). */
    public static String storageRoot(String path) {
        String[] p = path.split("/");
        if (path.startsWith("/storage/emulated/") && p.length > 4) return "/" + p[1] + "/" + p[2] + "/" + p[3];
        if (path.startsWith("/storage/") && p.length > 3) return "/" + p[1] + "/" + p[2];
        if (p.length > 2) return "/" + p[1];
        return "/";
    }

    public static String rootName(String root) {
        if (root.startsWith("/storage/emulated")) return L.t("Dahili hafıza");
        if (root.startsWith("/storage/")) return L.t("SD kart (") + baseName(root) + ")";
        return root;
    }

    public static List<Track> all() {
        List<Track> l = new ArrayList<>(video);
        l.addAll(audio);
        return l;
    }

    public static List<Track> search(String q) {
        String f = q.toLowerCase(Locale.ROOT).trim();
        List<Track> out = new ArrayList<>();
        if (f.isEmpty()) return out;
        for (Track t : all()) {
            if (t.title.toLowerCase(Locale.ROOT).contains(f) || t.artist.toLowerCase(Locale.ROOT).contains(f)
                    || t.album.toLowerCase(Locale.ROOT).contains(f)
                    || folderName(t.folder).toLowerCase(Locale.ROOT).contains(f)) out.add(t);
        }
        return out;
    }

    // ---------- kayıt (json) ----------

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("kitaplik", Context.MODE_PRIVATE);
    }

    public static JSONObject toJson(Track t) {
        JSONObject o = new JSONObject();
        try {
            o.put("u", t.uri.toString()).put("t", t.title).put("a", t.artist).put("al", t.album)
                    .put("f", t.folder).put("p", t.path).put("d", t.dur).put("v", t.video)
                    .put("w", t.w).put("h", t.h).put("id", t.id).put("s", t.size).put("m", t.mime);
        } catch (Exception ignored) {}
        return o;
    }

    public static Track fromJson(JSONObject o) {
        Track t = new Track();
        t.uri = Uri.parse(o.optString("u"));
        t.title = o.optString("t");
        t.artist = o.optString("a");
        t.album = o.optString("al");
        t.folder = o.optString("f");
        t.path = o.optString("p");
        t.dur = o.optLong("d");
        t.video = o.optBoolean("v");
        t.w = o.optInt("w");
        t.h = o.optInt("h");
        t.id = o.optLong("id");
        t.size = o.optLong("s");
        t.mime = o.optString("m");
        return t;
    }

    private static JSONArray arr(List<Track> l) {
        JSONArray a = new JSONArray();
        for (Track t : l) a.put(toJson(t));
        return a;
    }

    private static List<Track> list(JSONArray a) {
        List<Track> l = new ArrayList<>();
        if (a == null) return l;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) l.add(fromJson(o));
        }
        return l;
    }

    // geçmiş
    public static List<Track> history(Context c) {
        try { return list(new JSONArray(sp(c).getString("history", "[]"))); } catch (Exception e) { return new ArrayList<>(); }
    }

    public static void addHistory(Context c, Track t) {
        List<Track> h = history(c);
        for (Iterator<Track> it = h.iterator(); it.hasNext(); ) if (it.next().uri.equals(t.uri)) it.remove();
        h.add(0, t);
        while (h.size() > 60) h.remove(h.size() - 1);
        sp(c).edit().putString("history", arr(h).toString()).apply();
    }

    public static void clearHistory(Context c) {
        sp(c).edit().remove("history").apply();
    }

    // listeler
    private static JSONObject playlistsObj(Context c) {
        try { return new JSONObject(sp(c).getString("lists", "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    public static List<String> playlistNames(Context c) {
        JSONObject o = playlistsObj(c);
        List<String> l = new ArrayList<>();
        for (Iterator<String> it = o.keys(); it.hasNext(); ) l.add(it.next());
        Collections.sort(l, String::compareToIgnoreCase);
        return l;
    }

    public static List<Track> playlist(Context c, String name) {
        return list(playlistsObj(c).optJSONArray(name));
    }

    public static void savePlaylist(Context c, String name, List<Track> l) {
        JSONObject o = playlistsObj(c);
        try { o.put(name, arr(l)); } catch (Exception ignored) {}
        sp(c).edit().putString("lists", o.toString()).apply();
    }

    public static void addToPlaylist(Context c, String name, List<Track> add) {
        List<Track> l = playlist(c, name);
        l.addAll(add);
        savePlaylist(c, name, l);
    }

    public static void deletePlaylist(Context c, String name) {
        JSONObject o = playlistsObj(c);
        o.remove(name);
        sp(c).edit().putString("lists", o.toString()).apply();
    }

    // yer imleri
    public static List<Integer> bookmarks(Context c, Uri u) {
        List<Integer> l = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString("bm:" + u, "[]"));
            for (int i = 0; i < a.length(); i++) l.add(a.getInt(i));
        } catch (Exception ignored) {}
        Collections.sort(l);
        return l;
    }

    public static void setBookmarks(Context c, Uri u, List<Integer> l) {
        JSONArray a = new JSONArray();
        for (int v : l) a.put(v);
        sp(c).edit().putString("bm:" + u, a.toString()).apply();
    }

    public static Comparator<Track> byName() {
        return (x, y) -> x.title.compareToIgnoreCase(y.title);
    }
}
