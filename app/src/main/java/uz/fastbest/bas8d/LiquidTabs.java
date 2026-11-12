package uz.fastbest.bas8d;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Üst sekmeler: alt çizgi sıvı gibi uzayıp yeni sekmeye akar (alt menüyle uyumlu). */
public class LiquidTabs extends HorizontalScrollView {

    public interface OnSelect { void on(int i); }

    private final Strip strip;
    private String[] names = new String[0];
    private int sel, from;
    private float p = 1f;
    private ValueAnimator anim;
    private OnSelect cb;
    private final ArgbEvaluator argb = new ArgbEvaluator();

    public LiquidTabs(Context c) {
        super(c);
        setHorizontalScrollBarEnabled(false);
        setBackgroundColor(Ui.BAR);
        strip = new Strip(c);
        addView(strip);
    }

    public void setOnSelect(OnSelect cb) { this.cb = cb; }

    /** Sekme adları değiştiyse yeniden kurar; aynıysa sadece seçimi (animasyonlu) taşır. */
    public void set(String[] n, int selected, boolean animate) {
        if (!java.util.Arrays.equals(n, names)) {
            names = n;
            strip.removeAllViews();
            for (int i = 0; i < n.length; i++) {
                final int k = i;
                TextView t = Ui.text(getContext(), n[i], 16, Ui.TXT, true);
                t.setLetterSpacing(0.08f);
                t.setGravity(Gravity.CENTER);
                int d = Ui.dp(getContext(), 20), v = Ui.dp(getContext(), 16);
                t.setPadding(d, v, d, v);
                t.setOnClickListener(x -> {
                    if (k == sel) return;
                    select(k, true);
                    if (cb != null) cb.on(k);
                });
                strip.addView(t);
            }
            sel = from = selected;
            p = 1f;
            colors();
            strip.invalidate();
            return;
        }
        if (selected != sel) select(selected, animate);
    }

    private void select(int i, boolean animate) {
        if (anim != null) anim.cancel();
        from = sel;
        sel = i;
        if (!animate) { p = 1f; colors(); strip.invalidate(); return; }
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(300);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(a -> { p = (float) a.getAnimatedValue(); colors(); strip.invalidate(); });
        anim.start();
        View v = strip.getChildAt(i);
        if (v != null) smoothScrollTo(Math.max(0, v.getLeft() - (getWidth() - v.getWidth()) / 2), 0);
    }

    private void colors() {
        for (int i = 0; i < strip.getChildCount(); i++) {
            TextView t = (TextView) strip.getChildAt(i);
            float f = i == sel ? Math.min(1f, p * 1.6f) : (i == from ? 1f - Math.min(1f, p * 1.6f) : 0f);
            if (from == sel && i == sel) f = 1f;
            t.setTextColor((int) argb.evaluate(f, Ui.TXT, Ui.ORANGE));
        }
    }

    private static float easeOut(float t) { return 1 - (float) Math.pow(1 - t, 3); }
    private static float easeIn(float t) { return t * t * t; }

    private class Strip extends LinearLayout {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        Strip(Context c) {
            super(c);
            setWillNotDraw(false);
        }

        @Override protected void dispatchDraw(Canvas c) {
            super.dispatchDraw(c);
            if (getChildCount() == 0) return;
            View a = getChildAt(Math.min(from, getChildCount() - 1));
            View b = getChildAt(Math.min(sel, getChildCount() - 1));
            float inset = Ui.dp(getContext(), 14);
            float al = a.getLeft() + inset, ar = a.getRight() - inset;
            float bl = b.getLeft() + inset, br = b.getRight() - inset;
            boolean right = b.getLeft() >= a.getLeft();
            // ön kenar hızlı, arka kenar yavaş gider: sıvı gibi uzayıp toplanır
            float lead = easeOut(Math.min(1f, p * 1.35f)), trail = easeIn(p);
            float l, rr;
            if (right) { rr = ar + (br - ar) * lead; l = al + (bl - al) * trail; }
            else { l = al + (bl - al) * lead; rr = ar + (br - ar) * trail; }
            float stretch = (float) Math.sin(Math.PI * p);
            float hgt = Ui.dp(getContext(), 4) * (1f - 0.35f * stretch);
            float bottom = getHeight();
            r.set(l, bottom - hgt, rr, bottom);
            paint.setColor(Ui.ORANGE);
            c.drawRoundRect(r, hgt, hgt, paint);
            // damla: geçişin ortasında çizginin üstünde küçük bir kabarcık
            if (stretch > 0.05f && from != sel) {
                float cx = (l + rr) / 2f;
                float rad = Ui.dp(getContext(), 5) * stretch;
                c.drawCircle(cx, bottom - hgt - rad * 0.6f, rad, paint);
            }
        }
    }
}
