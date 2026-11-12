package uz.fastbest.bas8d;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/** Dengeleyici: bas/tiz, ekolayzır, 8D, ses yükseltme, surround, yankı, perde. */
public class EffectsActivity extends Activity {

    interface IntCb { void on(int v); }
    interface Fmt { String f(int v); }

    int BG, CARD, ACC, ACC2, TXT, SUB;
    private int builtStamp;
    private static final int MP = ViewGroup.LayoutParams.MATCH_PARENT, WC = ViewGroup.LayoutParams.WRAP_CONTENT;

    private PlayerService svc;
    private FrameLayout content;
    private TextView[] tabs;
    private int tab;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            svc = ((PlayerService.LocalBinder) b).get();
            rebuild();
        }
        @Override public void onServiceDisconnected(ComponentName n) { svc = null; }
    };

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        Ui.apply(this);
        builtStamp = Ui.stamp;
        BG = Ui.BG; ACC = Ui.ORANGE; ACC2 = Ui.ORANGE; TXT = Ui.TXT; SUB = Ui.SUB;
        CARD = Ui.GLASS ? Ui.ROW : Ui.LIGHT ? 0xFFFFFFFF : 0xFF1E1E1E;
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        tab = getIntent().getIntExtra("tab", 0);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Ui.pageBg());

        LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundColor(Ui.BAR);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(4), dp(6), dp(12), dp(6));
        bar.addView(Ui.btn(this, R.drawable.ic_back, TXT, 24, 12, v -> finish()));
        TextView t = text("Dengeleyici", 20, TXT, true);
        t.setPadding(dp(8), 0, 0, 0);
        bar.addView(t);
        root.addView(bar);

        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setBackgroundColor(Ui.BAR);
        String[] names = {"EKOLAYZIR", "8D VE EFEKTLER"};
        tabs = new TextView[names.length];
        for (int i = 0; i < names.length; i++) {
            final int k = i;
            TextView tv = text(names[i], 14, TXT, true);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(0, dp(12), 0, dp(12));
            tv.setOnClickListener(v -> { tab = k; rebuild(); });
            tabRow.addView(tv, lp(0, WC, 1));
            tabs[i] = tv;
        }
        root.addView(tabRow);

        content = new FrameLayout(this);
        root.addView(content, lp(MP, 0, 1));
        setContentView(root);

        Intent i = new Intent(this, PlayerService.class);
        startService(i);
        bindService(i, conn, Context.BIND_AUTO_CREATE);
    }

    @Override protected void onResume() {
        super.onResume();
        Ui.load(this);
        if (Ui.stamp != builtStamp) recreate();
    }

    @Override protected void onDestroy() {
        try { unbindService(conn); } catch (Exception ignored) {}
        super.onDestroy();
    }

    private void rebuild() {
        for (int i = 0; i < tabs.length; i++) {
            tabs[i].setTextColor(i == tab ? ACC : SUB);
            GradientDrawable g = new GradientDrawable();
            g.setColor(Ui.BAR);
            tabs[i].setBackground(i == tab ? underline() : g);
        }
        content.removeAllViews();
        if (svc == null) return;
        View v = tab == 0 ? buildEq() : buildFx();
        v.setPadding(0, dp(10), 0, 0);
        content.addView(v);
    }

    private android.graphics.drawable.Drawable underline() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.BAR);
        GradientDrawable line = new GradientDrawable();
        line.setColor(ACC);
        android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{bg, line});
        ld.setLayerInset(1, dp(30), 0, dp(30), 0);
        ld.setLayerGravity(1, Gravity.BOTTOM | Gravity.FILL_HORIZONTAL);
        ld.setLayerHeight(1, dp(3));
        return ld;
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override public void finish() {
        super.finish();
        overridePendingTransition(R.anim.left_in, R.anim.right_out);
    }

    private GradientDrawable round(int color, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(r));
        return g;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp * Ui.SCALE);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout.LayoutParams lp(int w, int hgt, float weight) {
        return new LinearLayout.LayoutParams(w, hgt, weight);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private void tint(SeekBar s) {
        s.setProgressTintList(ColorStateList.valueOf(ACC));
        s.setThumbTintList(ColorStateList.valueOf(ACC2));
        s.setProgressBackgroundTintList(ColorStateList.valueOf(Ui.TRACK));
    }

    private LinearLayout page(ScrollView sv) {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(12), 0, dp(12), dp(16));
        sv.addView(p);
        return p;
    }

    private LinearLayout card(LinearLayout parent, String title, String sub) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(CARD, 16));
        c.setPadding(dp(16), dp(14), dp(16), dp(12));
        LinearLayout.LayoutParams p = lp(MP, WC, 0);
        p.setMargins(dp(4), dp(4), dp(4), dp(8));
        parent.addView(c, p);
        if (title != null) c.addView(text(title, 16, TXT, true));
        if (sub != null) {
            TextView s = text(sub, 12, SUB, false);
            s.setPadding(0, dp(2), 0, dp(4));
            c.addView(s);
        }
        return c;
    }

    private SeekBar slider(LinearLayout parent, String name, final int min, int max, int val,
                           final Fmt fmt, final IntCb cb) {
        LinearLayout head = new LinearLayout(this);
        head.setPadding(0, dp(8), 0, 0);
        head.addView(text(name, 13, TXT, false), lp(0, WC, 1));
        final TextView v = text(fmt.f(val), 13, ACC2, true);
        head.addView(v);
        parent.addView(head);
        SeekBar s = new SeekBar(this);
        tint(s);
        s.setPadding(dp(8), dp(8), dp(8), dp(8));
        s.setMax(max - min);
        s.setProgress(val - min);
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {
                v.setText(fmt.f(p + min));
                if (user) cb.on(p + min);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) { if (svc != null) svc.save(); }
        });
        parent.addView(s);
        return s;
    }

    private void chips(LinearLayout parent, String[] names, int selected, final IntCb cb) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        final LinearLayout row = new LinearLayout(this);
        row.setPadding(0, dp(8), 0, dp(4));
        hs.addView(row);
        for (int i = 0; i < names.length; i++) {
            final int k = i;
            TextView t = text(names[i], 13, TXT, false);
            t.setPadding(dp(14), dp(8), dp(14), dp(8));
            t.setBackground(round(i == selected ? ACC : (Ui.LIGHT ? Ui.FIELD : 0xFF3A3A3A), 20));
            LinearLayout.LayoutParams p = lp(WC, WC, 0);
            p.rightMargin = dp(8);
            row.addView(t, p);
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    for (int j = 0; j < row.getChildCount(); j++) {
                        row.getChildAt(j).setBackground(round(j == k ? ACC : (Ui.LIGHT ? Ui.FIELD : 0xFF3A3A3A), 20));
                    }
                    cb.on(k);
                }
            });
        }
        parent.addView(hs);
    }

    private final Fmt pct = new Fmt() { @Override public String f(int v) { return "%" + v; } };

    // ---------- ekolayzır sekmesi ----------

    private static final String[] PRESET_NAMES = {"Düz", "Güçlü bas", "Güçlü tiz", "Kulaklık", "Rock", "Pop",
            "Vokal", "Dans", "Klasik"};
    private static final int[][] PRESETS = {
            {0, 0, 0, 0, 0}, {7, 4, 0, 0, 0}, {0, 0, 0, 4, 7}, {5, 2, 0, 2, 4}, {5, 3, -1, 3, 5},
            {-1, 2, 4, 2, -1}, {-2, 0, 4, 3, 0}, {6, 3, 0, 2, 4}, {4, 2, 0, 2, 4}};

    private View buildEq() {
        ScrollView sv = new ScrollView(this);
        LinearLayout p = page(sv);

        LinearLayout c1 = card(p, "Bas ve Tiz", "Hızlı ayar: bası ve tizi tek kaydırıcıyla güçlendirin.");
        slider(c1, "Bas", 0, 100, svc.bass, pct, new IntCb() {
            @Override public void on(int v) { svc.bass = v; svc.applyEq(); }
        });
        slider(c1, "Tiz (treble)", 0, 100, svc.treble, pct, new IntCb() {
            @Override public void on(int v) { svc.treble = v; svc.applyEq(); }
        });

        if (svc.hasEq()) {
            LinearLayout c2 = card(p, "Ekolayzır", "Hazır ayar seçin veya bantları tek tek ayarlayın.");
            chips(c2, PRESET_NAMES, -1, new IntCb() {
                @Override public void on(int k) {
                    svc.usePreset(PRESETS[k]);
                    rebuild();
                }
            });
            final int min = svc.bandMin(), max = svc.bandMax();
            for (int i = 0; i < svc.bands.length; i++) {
                final int k = i;
                int f = svc.bandFreq(i);
                String name = f >= 1000 ? String.format(Locale.US, "%.1f kHz", f / 1000f) : f + " Hz";
                slider(c2, name, min, max, Math.max(min, Math.min(max, svc.bands[i])), new Fmt() {
                    @Override public String f(int v) {
                        return (v > 0 ? "+" : "") + String.format(Locale.US, "%.1f dB", v / 100f);
                    }
                }, new IntCb() {
                    @Override public void on(int v) { svc.bands[k] = v; svc.applyEq(); }
                });
            }
        } else {
            card(p, "Ekolayzır", "Bu telefon ekolayzırı desteklemiyor.");
        }
        return sv;
    }

    // ---------- efektler sekmesi ----------

    private View buildFx() {
        ScrollView sv = new ScrollView(this);
        LinearLayout p = page(sv);

        LinearLayout c8 = card(p, "8D Ses", "Ses kulaklıkta başınızın etrafında döner. Kulaklıkla dinleyin.");
        Switch sw = new Switch(this);
        sw.setText("8D açık");
        sw.setTextColor(TXT);
        sw.setTextSize(14);
        sw.setChecked(svc.d8);
        sw.setPadding(0, dp(6), 0, 0);
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                svc.set8d(on);
                if (on && svc.reverb == 0 && svc.hasReverb()) {
                    svc.reverb = 5;
                    svc.applyReverb();
                }
                if (on && svc.surround == 0 && svc.hasSurround()) {
                    svc.surround = 60;
                    svc.applySurround();
                }
                svc.save();
                rebuild();
            }
        });
        c8.addView(sw);
        slider(c8, "Dönüş süresi", 3, 20, svc.period, new Fmt() {
            @Override public String f(int v) { return v + " sn"; }
        }, new IntCb() {
            @Override public void on(int v) { svc.period = v; }
        });

        LinearLayout cb = card(p, "Ses yükseltme", "Telefonun en yüksek sesinin üstüne çıkar. Yüksek ayarda ses bozulabilir; uzun süre çok yüksek sesle dinlemek kulağa zarar verir.");
        if (svc.hasBoost()) {
            slider(cb, "Güçlendirme", 0, 100, svc.boost, new Fmt() {
                @Override public String f(int v) {
                    return String.format(Locale.US, "+%.1f dB", v * 0.15f);
                }
            }, new IntCb() {
                @Override public void on(int v) { svc.boost = v; svc.applyBoost(); }
            });
        } else {
            cb.addView(text("Bu telefon ses yükseltmeyi desteklemiyor.", 13, SUB, false));
        }

        LinearLayout cs = card(p, "Surround ve Yankı", null);
        if (svc.hasSurround()) {
            slider(cs, "Surround (3D genişlik)", 0, 100, svc.surround, pct, new IntCb() {
                @Override public void on(int v) { svc.surround = v; svc.applySurround(); }
            });
        }
        if (svc.hasReverb()) {
            TextView rl = text("Yankı", 13, TXT, false);
            rl.setPadding(0, dp(8), 0, 0);
            cs.addView(rl);
            chips(cs, new String[]{"Kapalı", "Küçük oda", "Orta oda", "Büyük oda", "Salon", "Büyük salon", "Metalik"},
                    svc.reverb, new IntCb() {
                        @Override public void on(int k) { svc.reverb = k; svc.applyReverb(); svc.save(); }
                    });
        }
        if (!svc.d8) {
            slider(cs, "Sol / Sağ dengesi", -100, 100, svc.balance, new Fmt() {
                @Override public String f(int v) {
                    return v == 0 ? "Orta" : v < 0 ? "Sol %" + (-v) : "Sağ %" + v;
                }
            }, new IntCb() {
                @Override public void on(int v) { svc.balance = v; svc.applyVolume(); }
            });
        }

        LinearLayout ch = card(p, "Hız ve Perde", "Yavaşlatılmış (slowed) veya hızlandırılmış (nightcore) dinleme.");
        Fmt x = new Fmt() {
            @Override public String f(int v) { return String.format(Locale.US, "%.2fx", v / 100f); }
        };
        slider(ch, "Hız", 50, 200, svc.speed, x, new IntCb() {
            @Override public void on(int v) { svc.speed = v; svc.applySpeed(); }
        });
        slider(ch, "Perde (ses inceliği)", 50, 150, svc.pitch, x, new IntCb() {
            @Override public void on(int v) { svc.pitch = v; svc.applySpeed(); }
        });

        TextView reset = text("Tüm ayarları sıfırla", 14, TXT, true);
        reset.setGravity(Gravity.CENTER);
        reset.setBackground(round((Ui.LIGHT ? Ui.FIELD : 0xFF3A3A3A), 14));
        reset.setPadding(0, dp(12), 0, dp(12));
        LinearLayout.LayoutParams rp = lp(MP, WC, 0);
        rp.setMargins(dp(4), dp(4), dp(4), dp(8));
        p.addView(reset, rp);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                svc.bass = 0; svc.treble = 0; svc.boost = 0; svc.surround = 0; svc.reverb = 0;
                svc.balance = 0; svc.speed = 100; svc.pitch = 100; svc.period = 8; svc.d8 = false;
                for (int i = 0; i < svc.bands.length; i++) svc.bands[i] = 0;
                svc.applyAll();
                svc.save();
                toast("Ayarlar sıfırlandı");
                rebuild();
            }
        });
        return sv;
    }
}
