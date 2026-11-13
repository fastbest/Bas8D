package uz.fastbest.bas8d;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/** Çeviriler: anahtar Türkçe metindir; seçili dilin karşılığını döndürür. */
public class L {

    public static final String[][] LANGS = {
            {"tr", "Türkçe"}, {"en", "English"}, {"ru", "Русский"}, {"uz", "Oʻzbekcha"},
            {"ar", "العربية"}, {"es", "Español"}, {"pt", "Português"}, {"fr", "Français"},
            {"de", "Deutsch"}, {"hi", "हिन्दी"}, {"id", "Bahasa Indonesia"}, {"zh", "中文"}};

    public static String code = "tr";
    private static HashMap<String, String> map;

    public static void init(Context c) {
        String pref = c.getSharedPreferences("ayar", Context.MODE_PRIVATE).getString("lang", "");
        code = pref.isEmpty() ? pick(Locale.getDefault().getLanguage()) : pref;
        map = null;
        if (code.equals("tr")) return;
        try {
            List<String> keys = read(c, "i18n/keys.txt");
            List<String> vals = read(c, "i18n/" + code + ".txt");
            HashMap<String, String> m = new HashMap<>();
            for (int i = 0; i < keys.size() && i < vals.size(); i++) m.put(keys.get(i), vals.get(i));
            map = m;
        } catch (Exception e) {
            map = null;
        }
    }

    /** Sistem dili destekleniyorsa o, değilse İngilizce. */
    public static String pick(String sys) {
        if (sys == null) return "en";
        if (sys.equals("in")) sys = "id";
        for (String[] l : LANGS) if (l[0].equals(sys)) return sys;
        return "en";
    }

    public static String name(String c) {
        for (String[] l : LANGS) if (l[0].equals(c)) return l[1];
        return c;
    }

    public static boolean rtl() { return code.equals("ar"); }

    private static List<String> read(Context c, String path) throws Exception {
        List<String> out = new ArrayList<>();
        BufferedReader r = new BufferedReader(new InputStreamReader(c.getAssets().open(path), "UTF-8"));
        String line;
        while ((line = r.readLine()) != null) out.add(line.replace("\\n", "\n"));
        r.close();
        return out;
    }

    public static String t(String s) {
        if (map == null || s == null || s.isEmpty()) return s;
        int a = 0, b = s.length();
        while (a < b && Character.isWhitespace(s.charAt(a))) a++;
        while (b > a && Character.isWhitespace(s.charAt(b - 1))) b--;
        String v = map.get(s.substring(a, b));
        return v == null ? s : s.substring(0, a) + v + s.substring(b);
    }

    public static String[] t(String[] arr) {
        String[] out = new String[arr.length];
        for (int i = 0; i < arr.length; i++) out[i] = t(arr[i]);
        return out;
    }
}
