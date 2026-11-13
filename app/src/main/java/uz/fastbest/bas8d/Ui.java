package uz.fastbest.bas8d;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.util.LruCache;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.NumberPicker;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import uz.fastbest.bas8d.PlayerService.Track;

public class Ui {

    public interface IntCb { void on(int v); }

    // ---------- tema (Devona ile aynı seçenekler) ----------
    public static int BG = 0xFF121212, BAR = 0xFF1C1C1C, SURF = 0xFF2B2B2B, ORANGE = 0xFFFF8800,
            TXT = 0xFFFFFFFF, SUB = 0xFFAAAAAA, ICON = 0xFFBDBDBD, LINE = 0xFF2A2A2A,
            ROW = 0xFF2A2A2A, DIM = 0xFF8A8A8A, TRACK = 0xFF4A4A4A, FIELD = 0xFF2E2E2E, OUTLINE = 0xFF4A4A4A,
            G1, G2, G3;
    public static boolean LIGHT, GLASS;
    public static float SCALE = 1f;
    /** Tema her değiştiğinde artar; açık ekranlar kendini yeniden kurar. */
    public static int stamp;
    private static String loadedKey = "";

    public static final String[][] SWATCHES = {
            {"Turuncu", "#FF8800"}, {"Turkuaz", "#5FA3B0"}, {"Okyanus", "#3B8FE0"}, {"Lavanta", "#8E7CE8"},
            {"Gül", "#E0679C"}, {"Mercan", "#F2655B"}, {"Zümrüt", "#2EA56F"}, {"Kehribar", "#E0A23B"},
            {"Grafit", "#7D8396"}};
    public static final String[] THEMES = {"Sistem", "Açık", "Koyu"};
    public static final float[] ZOOMS = {0.85f, 1f, 1.15f, 1.3f};
    public static final String[] ZOOM_LABELS = {"Küçük", "Normal", "Büyük", "Çok büyük"};

    private static int mix(int a, int b, float t) {
        return android.graphics.Color.rgb(
                (int) (android.graphics.Color.red(a) * t + android.graphics.Color.red(b) * (1 - t)),
                (int) (android.graphics.Color.green(a) * t + android.graphics.Color.green(b) * (1 - t)),
                (int) (android.graphics.Color.blue(a) * t + android.graphics.Color.blue(b) * (1 - t)));
    }

    private static int col(String s) { return android.graphics.Color.parseColor(s); }

    public static void load(Context c) {
        SharedPreferences sp = prefs(c);
        int t = sp.getInt("tema", 2);
        boolean nightSys = (c.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        int sw = sp.getInt("renk", 0);
        if (sw < 0 || sw >= SWATCHES.length) sw = 0;
        boolean glass = sp.getBoolean("cam", false);
        float scale = sp.getFloat("yazi", 1f);
        boolean light = t == 1 || (t == 0 && !nightSys);
        String key = light + "/" + glass + "/" + sw + "/" + scale;
        if (key.equals(loadedKey)) return;
        loadedKey = key;
        stamp++;
        LIGHT = light;
        GLASS = glass;
        SCALE = scale;
        if (LIGHT) {
            BG = col("#F3F4F8"); BAR = col("#FFFFFF"); SURF = col("#FFFFFF");
            TXT = col("#1E2130"); SUB = col("#6B7085"); ICON = col("#5F6476"); LINE = col("#E3E5EB");
            ROW = col("#E7E9EF"); DIM = col("#8A8F9E"); TRACK = col("#D3D6DF"); FIELD = col("#E8EAF0");
            OUTLINE = col("#D3D6DF");
        } else {
            BG = 0xFF121212; BAR = 0xFF1C1C1C; SURF = 0xFF2B2B2B;
            TXT = 0xFFFFFFFF; SUB = 0xFFAAAAAA; ICON = 0xFFBDBDBD; LINE = 0xFF2A2A2A;
            ROW = 0xFF2A2A2A; DIM = 0xFF8A8A8A; TRACK = 0xFF4A4A4A; FIELD = 0xFF2E2E2E; OUTLINE = 0xFF4A4A4A;
        }
        ORANGE = col(SWATCHES[sw][1]);
        if (GLASS) {
            int base = LIGHT ? col("#F1F3F9") : col("#171A2B");
            int second = col(SWATCHES[(sw + 2) % SWATCHES.length][1]);
            G1 = mix(ORANGE, base, LIGHT ? 0.45f : 0.50f);
            G2 = mix(ORANGE, base, LIGHT ? 0.10f : 0.12f);
            G3 = mix(second, base, LIGHT ? 0.35f : 0.38f);
            BG = mix(ORANGE, base, 0.08f);
            SURF = LIGHT ? col("#F7FFFFFF") : mix(ORANGE, base, 0.22f);
            if (LIGHT) {
                BAR = col("#8CFFFFFF"); FIELD = col("#A6FFFFFF"); ROW = col("#BFFFFFFF");
                OUTLINE = col("#E6FFFFFF"); LINE = col("#1A000000"); SUB = col("#565B70");
                TRACK = col("#33000000");
            } else {
                BAR = col("#24FFFFFF"); FIELD = col("#2BFFFFFF"); ROW = col("#30FFFFFF");
                OUTLINE = col("#4DFFFFFF"); LINE = col("#22FFFFFF"); SUB = col("#C9CCDA");
                TRACK = col("#44FFFFFF");
            }
        }
    }

    /** Ekranın arka planı: cam stilde renk geçişi, değilse düz renk. */
    public static Drawable pageBg() {
        if (GLASS) return new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{G1, G2, G3});
        return new ColorDrawable(BG);
    }

    @SuppressWarnings("deprecation")
    public static void apply(Activity a) {
        load(a);
        a.getWindow().setStatusBarColor(GLASS ? G1 : BAR);
        a.getWindow().setNavigationBarColor(GLASS ? G3 : BAR);
        int f = 0;
        if (LIGHT) {
            f |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) f |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        a.getWindow().getDecorView().setSystemUiVisibility(f);
        if (GLASS) a.getWindow().setBackgroundDrawable(pageBg());
    }
    public static final int MP = ViewGroup.LayoutParams.MATCH_PARENT, WC = ViewGroup.LayoutParams.WRAP_CONTENT;

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> THUMBS = new LruCache<>(100);
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static Drawable icon(Context c, int res, int color) {
        Drawable d = c.getDrawable(res).mutate();
        d.setTint(color);
        return d;
    }

    public static ImageView img(Context c, int res, int color, int sizeDp, int padDp) {
        ImageView v = new ImageView(c);
        v.setImageDrawable(icon(c, res, color));
        int p = dp(c, padDp);
        v.setPadding(p, p, p, p);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp + padDp * 2), dp(c, sizeDp + padDp * 2)));
        return v;
    }

    /** Eşit genişlikte yuva: içindeki görünüm tam ortada durur (butonları ortalamak için). */
    public static android.widget.FrameLayout slot(View v, int wPx, int hPx) {
        android.widget.FrameLayout f = new android.widget.FrameLayout(v.getContext());
        if (v.getParent() != null) ((ViewGroup) v.getParent()).removeView(v);
        f.addView(v, new android.widget.FrameLayout.LayoutParams(wPx, hPx, Gravity.CENTER));
        return f;
    }

    public static ImageView btn(Context c, int res, int color, int sizeDp, int padDp, View.OnClickListener l) {
        ImageView v = img(c, res, color, sizeDp, padDp);
        ripple(v, true);
        v.setOnClickListener(l);
        return v;
    }

    public static void ripple(View v, boolean borderless) {
        TypedValue tv = new TypedValue();
        v.getContext().getTheme().resolveAttribute(borderless ? android.R.attr.selectableItemBackgroundBorderless
                : android.R.attr.selectableItemBackground, tv, true);
        try { v.setBackgroundResource(tv.resourceId); } catch (Exception ignored) {}
    }

    public static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp * SCALE);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    public static GradientDrawable round(int color, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(r);
        return g;
    }

    public static GradientDrawable circle(int fill, int stroke, int strokeW) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(fill);
        if (strokeW > 0) g.setStroke(strokeW, stroke);
        return g;
    }

    public static void tint(SeekBar s, int bg) {
        s.setProgressTintList(ColorStateList.valueOf(ORANGE));
        s.setThumbTintList(ColorStateList.valueOf(ORANGE));
        s.setProgressBackgroundTintList(ColorStateList.valueOf(bg));
    }

    public static String time(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        if (s >= 3600) return String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    public static String size(long b) {
        if (b <= 0) return "-";
        if (b < 1024 * 1024) return String.format(Locale.US, "%.0f KB", b / 1024f);
        if (b < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", b / 1048576f);
        return String.format(Locale.US, "%.2f GB", b / 1073741824f);
    }

    public static void toast(Context c, String s) {
        Toast.makeText(c, s, Toast.LENGTH_SHORT).show();
    }

    public static AlertDialog.Builder dlg(Context c) {
        return new AlertDialog.Builder(c, LIGHT ? android.R.style.Theme_Material_Light_Dialog_Alert
                : android.R.style.Theme_Material_Dialog_Alert);
    }

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("ayar", Context.MODE_PRIVATE);
    }

    // ---------- açılır menü (VLC tarzı) ----------

    public static PopupWindow menu(View anchor, String[] labels, int[] icons, IntCb cb) {
        Context c = anchor.getContext();
        ScrollView sv = new ScrollView(c);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(c, 8), 0, dp(c, 8));
        sv.addView(box);
        sv.setBackground(round(SURF, dp(c, 4)));
        final PopupWindow pw = new PopupWindow(sv, WC, WC, true);
        for (int i = 0; i < labels.length; i++) {
            final int k = i;
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(c, 16), dp(c, 13), dp(c, 28), dp(c, 13));
            row.setMinimumWidth(dp(c, 230));
            ripple(row, false);
            if (icons != null && icons[i] != 0) {
                ImageView iv = img(c, icons[i], TXT, 26, 0);
                row.addView(iv);
            }
            TextView t = text(c, labels[i], 17, TXT, false);
            t.setPadding(icons != null ? dp(c, 22) : dp(c, 6), 0, 0, 0);
            row.addView(t);
            row.setOnClickListener(v -> { pw.dismiss(); cb.on(k); });
            box.addView(row, new LinearLayout.LayoutParams(MP, WC));
        }
        pw.setBackgroundDrawable(new ColorDrawable(0));
        pw.setOutsideTouchable(true);
        if (Build.VERSION.SDK_INT >= 21) pw.setElevation(dp(c, 10));
        int screenH = c.getResources().getDisplayMetrics().heightPixels;
        sv.measure(View.MeasureSpec.makeMeasureSpec(c.getResources().getDisplayMetrics().widthPixels, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        int y = Math.max(dp(c, 24), loc[1]);
        int maxH = screenH - y - dp(c, 16);
        if (sv.getMeasuredHeight() > maxH) {
            y = Math.max(dp(c, 16), screenH - sv.getMeasuredHeight() - dp(c, 16));
            if (sv.getMeasuredHeight() > screenH - dp(c, 32)) {
                pw.setHeight(screenH - dp(c, 32));
                y = dp(c, 16);
            }
        }
        pw.showAtLocation(anchor, Gravity.TOP | Gravity.END, dp(c, 6), y);
        return pw;
    }

    // ---------- küçük resim ----------

    public static void thumb(ImageView iv, Track t) {
        final String key = t.uri.toString();
        iv.setTag(key);
        Bitmap b = THUMBS.get(key);
        if (b != null) { iv.setImageBitmap(b); return; }
        iv.setImageDrawable(null);
        final Context c = iv.getContext().getApplicationContext();
        POOL.submit(() -> {
            Bitmap bm = null;
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    bm = c.getContentResolver().loadThumbnail(t.uri, new Size(320, 180), null);
                } else if (t.id > 0) {
                    bm = MediaStore.Video.Thumbnails.getThumbnail(c.getContentResolver(), t.id,
                            MediaStore.Video.Thumbnails.MINI_KIND, null);
                }
            } catch (Throwable ignored) {}
            if (bm == null) return;
            final Bitmap fb = bm;
            H.post(() -> {
                THUMBS.put(key, fb);
                if (key.equals(iv.getTag())) iv.setImageBitmap(fb);
            });
        });
    }

    /** Ses dosyasının kapak resmi (yoksa null ile çağrılır). */
    public static void art(Context ctx, Track t, java.util.function.Consumer<Bitmap> cb) {
        final Context c = ctx.getApplicationContext();
        final String key = "art:" + t.uri;
        Bitmap b = THUMBS.get(key);
        if (b != null) { cb.accept(b); return; }
        POOL.submit(() -> {
            Bitmap bm = null;
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(c, t.uri);
                byte[] pic = r.getEmbeddedPicture();
                if (pic != null) bm = BitmapFactory.decodeByteArray(pic, 0, pic.length);
            } catch (Throwable ignored) {
            } finally {
                try { r.release(); } catch (Throwable ignored) {}
            }
            final Bitmap fb = bm;
            H.post(() -> {
                if (fb != null) THUMBS.put(key, fb);
                cb.accept(fb);
            });
        });
    }

    // ---------- oynatıcı menüleri ----------

    public static void sleepDialog(Context c, PlayerService s) {
        final int[] mins = {0, 5, 10, 15, 20, 30, 45, 60, 90, -1};
        String[] names = {"Kapalı", "5 dakika", "10 dakika", "15 dakika", "20 dakika", "30 dakika",
                "45 dakika", "1 saat", "1,5 saat", "Bu kayıt bitince"};
        String title = "Uyku zamanlayıcı";
        long left = s.sleepAt - System.currentTimeMillis();
        if (s.sleepAt > 0 && left > 0) title += " (" + Ui.time(left) + " kaldı)";
        else if (s.stopAtEnd) title += " (kayıt bitince)";
        dlg(c).setTitle(title).setItems(names, (d, i) -> {
            if (mins[i] < 0) { s.setStopAtEnd(); toast(c, "Bu kayıt bitince duracak"); }
            else {
                s.setSleep(mins[i]);
                toast(c, mins[i] == 0 ? "Uyku zamanlayıcı kapatıldı" : names[i] + " sonra duracak");
            }
        }).show();
    }

    public static void speedDialog(Context c, PlayerService s) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(c, 20), dp(c, 12), dp(c, 20), 0);
        final TextView val = text(c, fmtSpeed(s.speed), 28, TXT, true);
        val.setGravity(Gravity.CENTER);
        box.addView(val);
        final SeekBar sb = new SeekBar(c);
        tint(sb, TRACK);
        sb.setMax((400 - 25) / 5);
        sb.setProgress((s.speed - 25) / 5);
        sb.setPadding(dp(c, 8), dp(c, 16), dp(c, 8), dp(c, 16));
        box.addView(sb);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean u) {
                s.speed = 25 + p * 5;
                val.setText(fmtSpeed(s.speed));
                s.applySpeed();
            }
            @Override public void onStartTrackingTouch(SeekBar b) {}
            @Override public void onStopTrackingTouch(SeekBar b) { s.save(); }
        });
        HorizontalScrollView hs = new HorizontalScrollView(c);
        LinearLayout row = new LinearLayout(c);
        hs.addView(row);
        int[] presets = {50, 75, 100, 125, 150, 200, 300};
        for (int p : presets) {
            TextView t = text(c, fmtSpeed(p), 14, TXT, false);
            t.setBackground(round(SURF, dp(c, 16)));
            t.setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WC, WC);
            lp.rightMargin = dp(c, 8);
            t.setTextColor(LIGHT ? TXT : 0xFFFFFFFF);
            t.setBackground(round(LIGHT ? FIELD : 0xFF3A3A3A, dp(c, 16)));
            row.addView(t, lp);
            t.setOnClickListener(v -> sb.setProgress((p - 25) / 5));
        }
        box.addView(hs);
        dlg(c).setTitle("Oynatma hızı").setView(box)
                .setPositiveButton("Tamam", (d, w) -> s.save())
                .setNeutralButton("Sıfırla", (d, w) -> { s.speed = 100; s.applySpeed(); s.save(); })
                .show();
    }

    private static String fmtSpeed(int v) {
        return String.format(Locale.US, "%.2fx", v / 100f);
    }

    public static void jumpDialog(Context c, PlayerService s) {
        int dur = s.duration();
        if (dur <= 0) { toast(c, "Kayıt henüz hazır değil"); return; }
        int pos = s.position() / 1000;
        boolean hours = dur >= 3600000;
        LinearLayout box = new LinearLayout(c);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(c, 16), dp(c, 16), dp(c, 16), 0);
        final NumberPicker ph = new NumberPicker(c), pm = new NumberPicker(c), ps = new NumberPicker(c);
        ph.setMinValue(0); ph.setMaxValue(Math.max(0, dur / 3600000)); ph.setValue(pos / 3600);
        pm.setMinValue(0); pm.setMaxValue(hours ? 59 : Math.max(0, dur / 60000)); pm.setValue(hours ? pos / 60 % 60 : pos / 60);
        ps.setMinValue(0); ps.setMaxValue(59); ps.setValue(pos % 60);
        if (hours) { box.addView(ph); box.addView(sep(c)); }
        box.addView(pm);
        box.addView(sep(c));
        box.addView(ps);
        dlg(c).setTitle("Sıçranacak zaman (en fazla " + Ui.time(dur) + ")").setView(box)
                .setPositiveButton("Git", (d, w) -> {
                    int ms = ((hours ? ph.getValue() * 3600 : 0) + pm.getValue() * 60 + ps.getValue()) * 1000;
                    s.seek(Math.min(ms, dur - 500));
                })
                .setNegativeButton("İptal", null).show();
    }

    private static TextView sep(Context c) {
        TextView t = text(c, ":", 24, TXT, true);
        t.setPadding(dp(c, 6), 0, dp(c, 6), 0);
        return t;
    }

    public static void bookmarksDialog(Context c, PlayerService s) {
        final Track t = s.current();
        if (t == null) return;
        final List<Integer> bm = Lib.bookmarks(c, t.uri);
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < bm.size(); i++) rows.add("Yer imi " + (i + 1) + "   —   " + Ui.time(bm.get(i)));
        ListView lv = new ListView(c);
        lv.setAdapter(new ArrayAdapter<>(c, android.R.layout.simple_list_item_1, rows));
        AlertDialog.Builder b = dlg(c).setTitle("Yer imleri")
                .setPositiveButton("Şu anı ekle", (d, w) -> {
                    bm.add(s.position());
                    Lib.setBookmarks(c, t.uri, bm);
                    toast(c, "Yer imi eklendi: " + Ui.time(s.position()));
                })
                .setNegativeButton("Kapat", null);
        if (rows.isEmpty()) b.setMessage("Henüz yer imi yok.\n\"Şu anı ekle\" ile bulunduğunuz yeri kaydedin. Yer imine dokununca oraya gidilir, basılı tutunca silinir.");
        else b.setView(lv);
        final AlertDialog d = b.show();
        lv.setOnItemClickListener((p, v, i, id) -> { s.seek(bm.get(i)); d.dismiss(); });
        lv.setOnItemLongClickListener((p, v, i, id) -> {
            bm.remove(i);
            Lib.setBookmarks(c, t.uri, bm);
            toast(c, "Yer imi silindi");
            d.dismiss();
            bookmarksDialog(c, s);
            return true;
        });
    }

    public static void abRepeat(Context c, PlayerService s) {
        int r = s.abStep();
        if (r == 1) toast(c, "A noktası: " + Ui.time(s.abA) + ". B noktası için tekrar seçin.");
        else if (r == 2) toast(c, "A-B yineleme: " + Ui.time(s.abA) + " – " + Ui.time(s.abB));
        else toast(c, "A-B yineleme kapatıldı");
    }

    public static void savePlaylistDialog(Context c, List<Track> tracks) {
        if (tracks.isEmpty()) { toast(c, "Liste boş"); return; }
        final EditText e = new EditText(c);
        e.setHint("Liste adı");
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        LinearLayout box = new LinearLayout(c);
        box.setPadding(dp(c, 20), dp(c, 8), dp(c, 20), 0);
        box.addView(e, new LinearLayout.LayoutParams(MP, WC));
        dlg(c).setTitle("Listeyi kaydet").setView(box)
                .setPositiveButton("Kaydet", (d, w) -> {
                    String n = e.getText().toString().trim();
                    if (n.isEmpty()) n = "Liste " + (Lib.playlistNames(c).size() + 1);
                    Lib.savePlaylist(c, n, tracks);
                    toast(c, "\"" + n + "\" kaydedildi (" + tracks.size() + " kayıt)");
                })
                .setNegativeButton("İptal", null).show();
    }

    public static void addToPlaylistDialog(Context c, List<Track> tracks) {
        final List<String> names = Lib.playlistNames(c);
        String[] items = new String[names.size() + 1];
        items[0] = "+ Yeni liste";
        for (int i = 0; i < names.size(); i++) items[i + 1] = names.get(i);
        dlg(c).setTitle("Listeye ekle").setItems(items, (d, i) -> {
            if (i == 0) savePlaylistDialog(c, tracks);
            else {
                Lib.addToPlaylist(c, names.get(i - 1), tracks);
                toast(c, names.get(i - 1) + " listesine eklendi");
            }
        }).show();
    }

    public static void infoDialog(Context c, Track t, PlayerService s) {
        if (t == null) return;
        StringBuilder b = new StringBuilder();
        b.append("Ad: ").append(t.title).append("\n\n");
        if (!t.video && !t.artist.isEmpty()) b.append("Sanatçı: ").append(t.artist).append("\n");
        if (!t.video && !t.album.isEmpty()) b.append("Albüm: ").append(t.album).append("\n");
        long dur = t.dur > 0 ? t.dur : (s != null && s.current() == t ? s.duration() : 0);
        b.append("Süre: ").append(Ui.time(dur)).append("\n");
        int w = t.w, h = t.h;
        if (s != null && s.current() == t && s.vw > 0) { w = s.vw; h = s.vh; }
        if (t.video && w > 0) b.append("Çözünürlük: ").append(w).append(" × ").append(h).append("\n");
        b.append("Boyut: ").append(size(t.size)).append("\n");
        if (!t.mime.isEmpty()) b.append("Biçim: ").append(t.mime).append("\n");
        b.append("Klasör: ").append(Lib.folderName(t.folder)).append("\n\n");
        b.append("Konum: ").append(t.path.isEmpty() ? t.uri.toString() : t.path);
        dlg(c).setTitle(t.video ? "Görüntü bilgileri" : "Kayıt bilgileri").setMessage(b.toString())
                .setPositiveButton("Tamam", null).show();
    }

    public static void shareInfo(Context c, Track t) {
        if (t == null) return;
        String msg = (t.video ? "Şu anda izliyorum: " : "Şu anda dinliyorum: ") + t.title
                + (t.artist.isEmpty() || t.video ? "" : " – " + t.artist);
        Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, msg);
        try { c.startActivity(Intent.createChooser(i, "Paylaş")); } catch (Exception ignored) {}
    }

    public static void shareFile(Context c, Track t) {
        Intent i = new Intent(Intent.ACTION_SEND)
                .setType(t.mime.isEmpty() ? (t.video ? "video/*" : "audio/*") : t.mime)
                .putExtra(Intent.EXTRA_STREAM, t.uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { c.startActivity(Intent.createChooser(i, "Dosyayı paylaş")); } catch (Exception ignored) {}
    }

    public static void controlSettings(Context c, boolean video) {
        final SharedPreferences sp = prefs(c);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(c, 20), dp(c, 8), dp(c, 20), 0);
        sw(box, "Kulaklık çıkınca duraklat", sp, "unplug", true);
        sw(box, "Videoya kaldığı yerden devam et", sp, "resume", true);
        sw(box, "Ses ve parlaklık için kaydırma", sp, "gestures", true);
        sw(box, "Çift dokunuşla ileri / geri sar", sp, "dtap", true);
        sw(box, "Video ekranında kalan süreyi göster", sp, "remain", false);
        sw(box, "Video ekranından çıkınca ses arka planda sürsün", sp, "bgvideo", false);
        TextView l = text(c, "İleri / geri atlama süresi", 15, TXT, false);
        l.setPadding(0, dp(c, 14), 0, dp(c, 6));
        box.addView(l);
        final int[] secs = {5, 10, 15, 20, 30, 60};
        LinearLayout row = new LinearLayout(c);
        final List<TextView> chips = new ArrayList<>();
        int cur = sp.getInt("seekSec", 10);
        for (int s : secs) {
            TextView t = text(c, s + " sn", 14, TXT, false);
            t.setPadding(dp(c, 10), dp(c, 7), dp(c, 10), dp(c, 7));
            t.setBackground(round(s == cur ? ORANGE : (LIGHT ? FIELD : 0xFF3A3A3A), dp(c, 14)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WC, WC);
            lp.rightMargin = dp(c, 6);
            row.addView(t, lp);
            chips.add(t);
            t.setOnClickListener(v -> {
                sp.edit().putInt("seekSec", s).apply();
                for (int j = 0; j < chips.size(); j++)
                    chips.get(j).setBackground(round(secs[j] == s ? ORANGE : (LIGHT ? FIELD : 0xFF3A3A3A), dp(c, 14)));
            });
        }
        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.addView(row);
        box.addView(hs);
        ScrollView sv = new ScrollView(c);
        sv.addView(box);
        dlg(c).setTitle("Denetim ayarları").setView(sv).setPositiveButton("Tamam", null).show();
    }

    private static void sw(LinearLayout box, String label, SharedPreferences sp, String key, boolean def) {
        Context c = box.getContext();
        Switch s = new Switch(c);
        s.setText(label);
        s.setTextColor(TXT);
        s.setTextSize(15);
        s.setChecked(sp.getBoolean(key, def));
        s.setPadding(0, dp(c, 10), 0, dp(c, 10));
        s.setThumbTintList(ColorStateList.valueOf(ORANGE));
        s.setOnCheckedChangeListener((b, on) -> sp.edit().putBoolean(key, on).apply());
        box.addView(s, new LinearLayout.LayoutParams(MP, WC));
    }

    public static void tips(Context c, String title, String body) {
        dlg(c).setTitle(title).setMessage(body).setPositiveButton("Anladım", null).show();
    }

    public static final String AUDIO_TIPS =
            "• Sağ üstteki liste düğmesi çalma sırasını açar / kapatır.\n\n"
            + "• Büyüteç ile çalma sırasında arama yapabilirsiniz.\n\n"
            + "• Önceki düğmesine kayıt başındayken basınca önceki kayda, 4 saniyeden sonra basınca kaydın başına gider.\n\n"
            + "• Yineleme düğmesi: kapalı → tüm liste → tek kayıt.\n\n"
            + "• Karışık düğmesi rastgele sırayla çalar.\n\n"
            + "• Dengeleyici menüsünden bas, tiz, ekolayzır, 8D ses ve ses yükseltmeyi ayarlayın.\n\n"
            + "• A-B yineleme: bir kez seçin (A), sonra tekrar seçin (B); aradaki bölüm tekrar eder. Üçüncü seçim kapatır.";

    public static final String LIST_TIPS =
            "• Bir kayda dokunun: o kayıt çalar.\n\n"
            + "• Kaydın sağındaki üç nokta: listeden kaldır, yukarı / aşağı taşı, sonraki olarak çal, başka listeye ekle.\n\n"
            + "• \"Listeyi kaydet\" ile şu anki çalma sırasını adlandırıp saklayın; \"Listeler\" sekmesinde bulursunuz.\n\n"
            + "• Ana ekranda bir kaydın üç noktasından \"Sıraya ekle\" ile listeye kayıt ekleyebilirsiniz.";

    public static final String VIDEO_TIPS =
            "• Ekrana tek dokunuş: denetimleri gösterir / gizler.\n\n"
            + "• Çift dokunuş: sağ tarafta ileri, sol tarafta geri sarar (süre Denetim ayarlarından değişir).\n\n"
            + "• Sol tarafta yukarı-aşağı kaydırma: parlaklık. Sağ tarafta: ses.\n\n"
            + "• Ekranda yatay kaydırma: videoda ileri / geri gider.\n\n"
            + "• Kilitle: ekranı dokunmaya kapatır; açmak için ekrana dokunun, çıkan kilit düğmesini 3 saniye basılı tutun.\n\n"
            + "• Ses olarak oynat: görüntüyü kapatıp sadece sesi çalar.\n\n"
            + "• Açılan pencerede oynatıcı: video küçük pencerede diğer uygulamaların üstünde oynar.";
}
