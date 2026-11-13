package uz.fastbest.bas8d;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Oyuklu alt menü: seçili simge çubuğun üstünde yuvarlak içinde durur.
 * Geçişte yuvarlak çubuğa batar, oyuk sıvı gibi uzayarak yeni yere kayar,
 * yuvarlak yeni yerde simgesi dönerek yükselir.
 */
public class CurvedNav extends View {

    public interface OnSelect { void on(int i); }

    private final int[] icons;
    private final String[] labels;
    private final Drawable[] dr;
    private OnSelect cb;

    private int sel, from;
    private float p = 1f; // geçiş ilerlemesi 0..1
    private ValueAnimator anim;

    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint circle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private final float rise, barH, cr, gap;

    public CurvedNav(Context c, int[] icons, String[] labels, int selected) {
        super(c);
        this.icons = icons;
        this.labels = labels;
        this.sel = this.from = selected;
        dr = new Drawable[icons.length];
        for (int i = 0; i < icons.length; i++) dr[i] = c.getDrawable(icons[i]).mutate();
        rise = Ui.dp(c, 26);
        barH = Ui.dp(c, 62);
        cr = Ui.dp(c, 27);
        gap = Ui.dp(c, 6);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        bar.setColor(Ui.BAR);
        bar.setShadowLayer(Ui.dp(c, 6), 0, -Ui.dp(c, 1), Ui.LIGHT ? 0x22000000 : 0x55000000);
        circle.setColor(Ui.ORANGE);
        circle.setShadowLayer(Ui.dp(c, 6), 0, Ui.dp(c, 2), 0x44000000);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTextSize(Ui.dp(c, 11) * Ui.SCALE);
        label.setColor(Ui.ICON);
    }

    public void setOnSelect(OnSelect cb) { this.cb = cb; }

    public int getSelected() { return sel; }

    public float riseHeight() { return rise; }

    public void select(int i, boolean animate) {
        if (i == sel && p >= 1f) return;
        if (anim != null) anim.cancel();
        from = sel;
        sel = i;
        if (!animate || from == sel) { p = 1f; invalidate(); return; }
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(380);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(a -> { p = (float) a.getAnimatedValue(); invalidate(); });
        anim.start();
    }

    @Override protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), (int) (rise + barH));
    }

    private float slotX(int i) {
        float w = getWidth() / (float) icons.length;
        return w * i + w / 2f;
    }

    private static float ease(float t) { // yumuşak başla-bitir
        return t < 0.5f ? 4 * t * t * t : 1 - (float) Math.pow(-2 * t + 2, 3) / 2;
    }


    @Override protected void onDraw(Canvas c) {
        float W = getWidth(), top = rise, H = getHeight();
        float ax = slotX(from), bx = slotX(sel);
        boolean moving = from != sel && p < 1f;

        // oyuk yuvarlakla birlikte kayar; boş oyuk ya da arkada dalga kalmaz
        float e = moving ? ease(p) : 1f;
        float cx = ax + (bx - ax) * e;
        float wave = moving ? (float) Math.sin(Math.PI * p) : 0f;
        float nr = cr + gap;
        float depth = nr * 1.08f;
        float spread = nr * (1.55f + 0.25f * wave);

        path.reset();
        path.moveTo(0, top);
        path.lineTo(cx - spread, top);
        path.cubicTo(cx - spread * 0.45f, top, cx - nr * 0.95f, top + depth, cx, top + depth);
        path.cubicTo(cx + nr * 0.95f, top + depth, cx + spread * 0.45f, top, cx + spread, top);
        path.lineTo(W, top);
        path.lineTo(W, H);
        path.lineTo(0, H);
        path.close();
        c.drawPath(path, bar);

        int isz = Ui.dp(getContext(), 26);
        float iconY = top + barH / 2f - Ui.dp(getContext(), 7);

        // seçili olmayan simgeler: yuvarlak üstlerinden geçerken gizlenir
        for (int i = 0; i < icons.length; i++) {
            float x = slotX(i);
            float dist = Math.abs(x - cx) / (nr * 1.6f);
            float alpha = Math.min(1f, Math.max(0f, dist - 0.35f));
            if (!moving && i == sel) alpha = 0f;
            if (alpha <= 0f) continue;
            Drawable d = dr[i];
            d.setTint(Ui.ICON);
            d.setAlpha((int) (alpha * 255));
            d.setBounds((int) (x - isz / 2f), (int) (iconY - isz / 2f), (int) (x + isz / 2f), (int) (iconY + isz / 2f));
            d.draw(c);
            label.setAlpha((int) (alpha * 255));
            c.drawText(labels[i], x, iconY + isz / 2f + Ui.dp(getContext(), 14), label);
        }

        float restY = top + depth - nr;
        if (moving) {
            // yuvarlak kayarken hafifçe çubuğa iner, simge dönerek değişir
            float y = restY + wave * barH * 0.18f;
            float s = 1f - 0.12f * wave;
            if (p < 0.5f) drawBubble(c, cx, y, s, from, p * 2f * 180f);
            else drawBubble(c, cx, y, s, sel, (p - 1f) * 2f * 180f);
        } else {
            drawBubble(c, bx, restY, 1f, sel, 0f);
            label.setAlpha(255);
            label.setColor(Ui.ORANGE);
            c.drawText(labels[sel], bx, iconY + isz / 2f + Ui.dp(getContext(), 14), label);
            label.setColor(Ui.ICON);
        }
    }

    private void drawBubble(Canvas c, float x, float y, float s, int idx, float rot) {
        if (s <= 0.02f) return;
        c.save();
        c.translate(x, y);
        c.scale(s, s);
        c.drawCircle(0, 0, cr, circle);
        c.rotate(rot);
        int isz = Ui.dp(getContext(), 26);
        Drawable d = dr[idx];
        d.setTint(0xFFFFFFFF);
        d.setAlpha(255);
        d.setBounds(-isz / 2, -isz / 2, isz / 2, isz / 2);
        d.draw(c);
        c.restore();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN)
            return e.getY() >= rise || (Math.abs(e.getX() - slotX(sel)) < cr && e.getY() >= rise - cr);
        if (e.getAction() == MotionEvent.ACTION_UP) {
            int i = (int) (e.getX() / (getWidth() / (float) icons.length));
            i = Math.max(0, Math.min(icons.length - 1, i));
            if (i != sel) {
                select(i, true);
                performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            }
            if (cb != null) cb.on(i);
            return true;
        }
        return true;
    }
}
