package uz.fastbest.bas8d;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.function.IntConsumer;

import static uz.fastbest.bas8d.Ui.MP;
import static uz.fastbest.bas8d.Ui.WC;

/** Görünüm: Devona ile aynı tema seçenekleri (Tema, Stil, Renk, Yazı boyutu, Ön izleme). */
public class AppearanceActivity extends Activity {

    private SharedPreferences sp;

    private int dp(float v) { return Ui.dp(this, v); }

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        sp = Ui.prefs(this);
        Ui.apply(this);
        build();
    }

    private void changed() {
        Ui.load(this);
        recreate();
    }

    private void build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Ui.pageBg());

        LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundColor(Ui.BAR);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setMinimumHeight(dp(64));
        bar.setPadding(dp(4), 0, dp(12), 0);
        bar.addView(Ui.btn(this, R.drawable.ic_back, Ui.TXT, 24, 12, v -> finish()));
        TextView t = Ui.text(this, "Görünüm", 21, Ui.TXT, true);
        t.setPadding(dp(8), 0, 0, 0);
        bar.addView(t);
        root.addView(bar);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(18), 0, dp(18), dp(30));

        body.addView(label("Tema"));
        body.addView(segmented(Ui.THEMES, sp.getInt("tema", 2), k -> {
            sp.edit().putInt("tema", k).commit();
            changed();
        }));

        body.addView(label("Stil"));
        body.addView(segmented(new String[]{"Düz", "Cam"}, Ui.GLASS ? 1 : 0, k -> {
            sp.edit().putBoolean("cam", k == 1).commit();
            changed();
        }));

        body.addView(label("Renk"));
        final int sel = Math.max(0, Math.min(Ui.SWATCHES.length - 1, sp.getInt("renk", 0)));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout sw = new LinearLayout(this);
        sw.setGravity(Gravity.CENTER_VERTICAL);
        sw.setPadding(0, dp(4), 0, dp(4));
        for (int i = 0; i < Ui.SWATCHES.length; i++) {
            final int k = i;
            int color = Color.parseColor(Ui.SWATCHES[i][1]);
            View c = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(color);
            if (i == sel) g.setStroke(dp(3), Ui.GLASS ? Ui.G2 : Ui.BG);
            c.setBackground(g);
            FrameLayout ring = new FrameLayout(this);
            GradientDrawable rg = new GradientDrawable();
            rg.setShape(GradientDrawable.OVAL);
            rg.setColor(i == sel ? color : Color.TRANSPARENT);
            ring.setBackground(rg);
            if (i == sel) ring.setElevation(dp(6));
            ring.addView(c, new FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER));
            ring.setOnClickListener(v -> {
                sp.edit().putInt("renk", k).commit();
                changed();
            });
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(dp(42), dp(42));
            rp.setMargins(0, 0, dp(10), 0);
            sw.addView(ring, rp);
        }
        hs.addView(sw);
        body.addView(hs);
        TextView name = Ui.text(this, Ui.SWATCHES[sel][0], 14, Ui.ORANGE, true);
        name.setPadding(dp(2), dp(8), 0, 0);
        body.addView(name);

        body.addView(label("Yazı boyutu"));
        int zsel = 1;
        float cur = sp.getFloat("yazi", 1f);
        for (int i = 0; i < Ui.ZOOMS.length; i++) if (Math.abs(Ui.ZOOMS[i] - cur) < 0.01f) zsel = i;
        body.addView(segmented(Ui.ZOOM_LABELS, zsel, k -> {
            sp.edit().putFloat("yazi", Ui.ZOOMS[k]).commit();
            changed();
        }));

        body.addView(label("Ön izleme"));
        body.addView(preview());

        ScrollView sv = new ScrollView(this);
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(MP, 0, 1));
        setContentView(root);
    }

    private TextView label(String s) {
        TextView t = Ui.text(this, s, 15, Ui.TXT, true);
        t.setPadding(dp(2), dp(18), 0, dp(10));
        return t;
    }

    private LinearLayout segmented(String[] items, int selected, IntConsumer cb) {
        LinearLayout seg = new LinearLayout(this);
        seg.setBackground(Ui.round(Ui.FIELD, dp(22)));
        seg.setPadding(dp(4), dp(4), dp(4), dp(4));
        for (int i = 0; i < items.length; i++) {
            final int k = i;
            TextView t = Ui.text(this, items[i], 14.5f, i == selected ? Ui.TXT : Ui.SUB, i == selected);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(9), 0, dp(9));
            if (i == selected) {
                t.setBackground(Ui.round(Ui.LIGHT ? Color.WHITE : Ui.OUTLINE, dp(18)));
                t.setElevation(dp(2));
            }
            t.setOnClickListener(v -> { if (k != selected) cb.accept(k); });
            seg.addView(t, new LinearLayout.LayoutParams(0, WC, 1));
        }
        return seg;
    }

    /** Küçük ön izleme: üst çubuk, bir kayıt satırı ve mini oynatıcı. */
    private View preview() {
        LinearLayout prev = new LinearLayout(this);
        prev.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable pg = Ui.round(Ui.GLASS ? Ui.ROW : Ui.BG, dp(22));
        pg.setStroke(dp(1), Ui.OUTLINE);
        prev.setBackground(pg);
        prev.setClipToOutline(true);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(Ui.BAR);
        top.setPadding(dp(14), dp(10), dp(8), dp(10));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher);
        top.addView(logo, new LinearLayout.LayoutParams(dp(30), dp(30)));
        TextView nm = Ui.text(this, "Bas8D", 18, Ui.TXT, true);
        nm.setPadding(dp(10), 0, 0, 0);
        top.addView(nm, new LinearLayout.LayoutParams(0, WC, 1));
        top.addView(Ui.img(this, R.drawable.ic_search, Ui.TXT, 22, 6));
        top.addView(Ui.img(this, R.drawable.ic_more_vert, Ui.TXT, 22, 6));
        prev.addView(top);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setBackgroundColor(Ui.BAR);
        tabs.setPadding(dp(14), 0, dp(14), 0);
        TextView a = Ui.text(this, "KLASÖRLER", 13, Ui.ORANGE, true);
        a.setPadding(0, dp(6), dp(20), dp(8));
        TextView b = Ui.text(this, "KAYITLAR", 13, Ui.TXT, true);
        b.setPadding(0, dp(6), 0, dp(8));
        tabs.addView(a);
        tabs.addView(b);
        prev.addView(tabs);

        prev.addView(sampleRow(R.drawable.ic_folder, "Music", "24 kayıt", false));
        prev.addView(sampleRow(R.drawable.ic_bars, "Örnek kayıt", "3:45 · Sanatçı", true));

        LinearLayout mini = new LinearLayout(this);
        mini.setGravity(Gravity.CENTER_VERTICAL);
        mini.setBackgroundColor(Ui.BAR);
        mini.setPadding(dp(14), dp(8), dp(10), dp(8));
        TextView mt = Ui.text(this, "Örnek kayıt", 14, Ui.TXT, false);
        mini.addView(mt, new LinearLayout.LayoutParams(0, WC, 1));
        TextView tm = Ui.text(this, "1:12", 13, Ui.TXT, false);
        tm.setPadding(0, 0, dp(10), 0);
        mini.addView(tm);
        ImageView pl = new ImageView(this);
        pl.setImageDrawable(Ui.icon(this, R.drawable.ic_pause, Ui.TXT));
        pl.setPadding(dp(8), dp(8), dp(8), dp(8));
        pl.setBackground(Ui.circle(0, Ui.TXT, dp(2)));
        mini.addView(pl, new LinearLayout.LayoutParams(dp(36), dp(36)));
        View prog = new View(this);
        prog.setBackgroundColor(Ui.ORANGE);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(120), dp(3));
        prev.addView(prog, pp);
        prev.addView(mini);
        return prev;
    }

    private View sampleRow(int icon, String title, String sub, boolean current) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(14), dp(10), dp(8), dp(10));
        r.addView(Ui.img(this, icon, current ? Ui.TXT : Ui.DIM, 28, 4));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), 0, 0, 0);
        col.addView(Ui.text(this, title, 16, current ? Ui.ORANGE : Ui.TXT, false));
        col.addView(Ui.text(this, sub, 13, Ui.SUB, false));
        r.addView(col, new LinearLayout.LayoutParams(0, WC, 1));
        r.addView(Ui.img(this, R.drawable.ic_more_vert, Ui.TXT, 22, 6));
        return r;
    }
}
