package uz.fastbest.bas8d;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class VideoActivity extends Activity implements SurfaceHolder.Callback {

    private PlayerService svc;
    private final Handler h = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private SurfaceView surface;
    private SurfaceHolder holder;
    private LinearLayout top, bottom;
    private TextView title, tCur, tDur, bPlay, info, bFit, bBg;
    private SeekBar seek;
    private boolean dragging, shown = true, fill, bg, keepOnce;
    private Uri pending;
    private SharedPreferences sp;
    private AudioManager am;
    private float bright = -1;
    private float volAcc;

    private final Runnable listener = new Runnable() {
        @Override public void run() { h.post(new Runnable() { @Override public void run() { refresh(); layoutVideo(); } }); }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() { refresh(); h.postDelayed(this, 500); }
    };

    private final Runnable hide = new Runnable() {
        @Override public void run() { showControls(false); }
    };

    private final Runnable hideInfo = new Runnable() {
        @Override public void run() { info.setVisibility(View.GONE); }
    };

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            svc = ((PlayerService.LocalBinder) b).get();
            svc.listeners.add(listener);
            playPending();
            if (holder != null) attach();
            refresh();
            layoutVideo();
        }
        @Override public void onServiceDisconnected(ComponentName n) { svc = null; }
    };

    private void playPending() {
            if (svc != null && pending != null) {
                PlayerService.Track t = new PlayerService.Track();
                t.uri = pending;
                t.video = true;
                t.title = fileName(pending);
                t.artist = "Video";
                List<PlayerService.Track> l = new ArrayList<>();
                l.add(t);
                svc.setList(l, 0);
                pending = null;
            }
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        sp = getSharedPreferences("ayar", MODE_PRIVATE);
        bg = sp.getBoolean("bgvideo", false);
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(0xFF000000);
        getWindow().setNavigationBarColor(0xFF000000);
        buildUi();
        immersive();

        Intent in = getIntent();
        if (in != null && Intent.ACTION_VIEW.equals(in.getAction()) && in.getData() != null) pending = in.getData();

        Intent i = new Intent(this, PlayerService.class);
        startService(i);
        bindService(i, conn, Context.BIND_AUTO_CREATE);
    }

    @Override protected void onNewIntent(Intent in) {
        super.onNewIntent(in);
        if (in != null && Intent.ACTION_VIEW.equals(in.getAction()) && in.getData() != null) {
            pending = in.getData();
            playPending();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        keepOnce = false;
        immersive();
        h.removeCallbacks(tick);
        h.post(tick);
        showControls(true);
    }

    @Override protected void onPause() {
        super.onPause();
        h.removeCallbacks(tick);
    }

    @Override protected void onStop() {
        super.onStop();
        if (svc != null && !bg && !keepOnce && !isChangingConfigurations()) svc.pause();
    }

    @Override protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        if (svc != null) {
            svc.listeners.remove(listener);
            try { unbindService(conn); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        root.post(new Runnable() { @Override public void run() { layoutVideo(); } });
    }

    @Override public void onWindowFocusChanged(boolean f) {
        super.onWindowFocusChanged(f);
        if (f) immersive();
    }

    @SuppressWarnings("deprecation")
    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    // ---------- yüzey ----------

    private void attach() {
        svc.setDisplay(holder);
        if (!svc.isPlaying()) svc.seek(svc.position());
    }

    @Override public void surfaceCreated(SurfaceHolder sh) {
        holder = sh;
        if (svc != null) attach();
    }

    @Override public void surfaceChanged(SurfaceHolder sh, int f, int w, int hh) {}

    @Override public void surfaceDestroyed(SurfaceHolder sh) {
        holder = null;
        if (svc != null) svc.setDisplay(null);
    }

    private void layoutVideo() {
        if (svc == null || root.getWidth() == 0) return;
        int vw = svc.vw, vh = svc.vh;
        int W = root.getWidth(), H = root.getHeight();
        int w = W, hh = H;
        if (vw > 0 && vh > 0) {
            float va = vw / (float) vh, sa = W / (float) H;
            boolean wider = va > sa;
            if (wider != fill) { w = W; hh = Math.round(W / va); }
            else { hh = H; w = Math.round(H * va); }
        }
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) surface.getLayoutParams();
        if (p.width != w || p.height != hh) {
            p.width = w;
            p.height = hh;
            p.gravity = Gravity.CENTER;
            surface.setLayoutParams(p);
        }
    }

    // ---------- arayüz ----------

    private TextView text(String s, float sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(0xFFFFFFFF);
        t.setShadowLayer(4, 0, 0, 0xAA000000);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView btn(String s, float sp) {
        TextView t = text(s, sp, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), dp(8), dp(10), dp(8));
        return t;
    }

    private GradientDrawable shade(boolean fromTop) {
        GradientDrawable g = new GradientDrawable(fromTop ? GradientDrawable.Orientation.TOP_BOTTOM
                : GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xCC000000, 0x00000000});
        return g;
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        surface = new SurfaceView(this);
        surface.getHolder().addCallback(this);
        root.addView(surface, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        View touch = new View(this);
        root.addView(touch, new FrameLayout.LayoutParams(-1, -1));
        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { volAcc = 0; return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) { showControls(!shown); return true; }
            @Override public boolean onDoubleTap(MotionEvent e) {
                if (svc == null) return true;
                boolean right = e.getX() > root.getWidth() / 2f;
                svc.seekBy(right ? 10000 : -10000);
                flash(right ? "+10 sn ⏩" : "⏪ -10 sn");
                return true;
            }
            @Override public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                if (a == null || Math.abs(dy) < Math.abs(dx)) return false;
                float frac = dy / Math.max(1, root.getHeight());
                if (a.getX() < root.getWidth() / 2f) {
                    WindowManager.LayoutParams lp = getWindow().getAttributes();
                    if (bright < 0) bright = lp.screenBrightness < 0 ? 0.5f : lp.screenBrightness;
                    bright = Math.max(0.01f, Math.min(1f, bright + frac * 1.5f));
                    lp.screenBrightness = bright;
                    getWindow().setAttributes(lp);
                    flash("☀ Parlaklık %" + Math.round(bright * 100));
                } else {
                    int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    volAcc += frac * max * 1.5f;
                    int step = (int) volAcc;
                    if (step != 0) {
                        volAcc -= step;
                        int v = Math.max(0, Math.min(max, am.getStreamVolume(AudioManager.STREAM_MUSIC) + step));
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
                    }
                    flash("🔊 Ses %" + Math.round(am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max));
                }
                return true;
            }
        });
        touch.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) { return gd.onTouchEvent(e); }
        });

        info = text("", 18, true);
        info.setPadding(dp(18), dp(10), dp(18), dp(10));
        GradientDrawable ib = new GradientDrawable();
        ib.setColor(0x99000000);
        ib.setCornerRadius(dp(14));
        info.setBackground(ib);
        info.setVisibility(View.GONE);
        root.addView(info, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        // üst çubuk
        top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackground(shade(true));
        top.setPadding(dp(6), dp(14), dp(6), dp(28));
        TextView back = btn("←", 22);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        top.addView(back);
        title = text("", 15, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        bBg = btn("🎧", 18);
        bBg.setAlpha(bg ? 1f : 0.4f);
        bBg.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                bg = !bg;
                sp.edit().putBoolean("bgvideo", bg).apply();
                bBg.setAlpha(bg ? 1f : 0.4f);
                toast(bg ? "Arka planda çalma açık: uygulamadan çıkınca ses devam eder"
                        : "Arka planda çalma kapalı");
                poke();
            }
        });
        top.addView(bBg);
        TextView fx = btn("🎚", 18);
        fx.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                keepOnce = true;
                startActivity(new Intent(VideoActivity.this, MainActivity.class).putExtra("tab", 3));
            }
        });
        top.addView(fx);
        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // alt çubuk
        bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackground(shade(false));
        bottom.setPadding(dp(12), dp(28), dp(12), dp(12));

        LinearLayout sr = new LinearLayout(this);
        sr.setGravity(Gravity.CENTER_VERTICAL);
        tCur = text("0:00", 12, false);
        tDur = text("0:00", 12, false);
        seek = new SeekBar(this);
        seek.setProgressTintList(ColorStateList.valueOf(MainActivity.ACC));
        seek.setThumbTintList(ColorStateList.valueOf(MainActivity.ACC2));
        seek.setProgressBackgroundTintList(ColorStateList.valueOf(0x66FFFFFF));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user) { tCur.setText(time(p)); if (svc != null) svc.seek(p); }
            }
            @Override public void onStartTrackingTouch(SeekBar s) { dragging = true; h.removeCallbacks(hide); }
            @Override public void onStopTrackingTouch(SeekBar s) { dragging = false; poke(); }
        });
        sr.addView(tCur);
        sr.addView(seek, new LinearLayout.LayoutParams(0, -2, 1));
        sr.addView(tDur);
        bottom.addView(sr);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        bFit = btn("⛶", 20);
        TextView bPrev = btn("⏮", 24);
        TextView bBack = btn("⏪", 22);
        bPlay = btn("⏸", 32);
        TextView bFwd = btn("⏩", 22);
        TextView bNext = btn("⏭", 24);
        TextView bRot = btn("⟳", 22);
        TextView[] all = {bFit, bPrev, bBack, bPlay, bFwd, bNext, bRot};
        for (TextView t : all) row.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        bottom.addView(row);
        root.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        bPlay.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (svc != null) svc.toggle(); poke(); }
        });
        bPrev.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (svc != null) svc.prev(); poke(); }
        });
        bNext.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (svc != null) svc.next(false); poke(); }
        });
        bBack.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (svc != null) svc.seekBy(-10000); poke(); }
        });
        bFwd.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (svc != null) svc.seekBy(10000); poke(); }
        });
        bFit.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                fill = !fill;
                layoutVideo();
                flash(fill ? "Ekranı doldur" : "Sığdır");
                poke();
            }
        });
        bRot.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean land = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
                setRequestedOrientation(land ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                        : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                poke();
            }
        });

        setContentView(root);
    }

    private void showControls(boolean on) {
        shown = on;
        top.setVisibility(on ? View.VISIBLE : View.GONE);
        bottom.setVisibility(on ? View.VISIBLE : View.GONE);
        h.removeCallbacks(hide);
        if (on) h.postDelayed(hide, 3500);
        else immersive();
    }

    private void poke() {
        h.removeCallbacks(hide);
        if (shown) h.postDelayed(hide, 3500);
    }

    private void flash(String s) {
        info.setText(s);
        info.setVisibility(View.VISIBLE);
        h.removeCallbacks(hideInfo);
        h.postDelayed(hideInfo, 900);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private void refresh() {
        if (svc == null) return;
        PlayerService.Track t = svc.current();
        title.setText(t == null ? "" : t.title);
        bPlay.setText(svc.isPlaying() ? "⏸" : "▶");
        int d = svc.duration();
        if (!dragging) {
            seek.setMax(Math.max(1, d));
            seek.setProgress(svc.position());
            tCur.setText(time(svc.position()));
        }
        tDur.setText(time(d));
    }

    private static String time(long ms) {
        long s = ms / 1000;
        if (s >= 3600) return String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private String fileName(Uri u) {
        String name = null;
        try {
            Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null) {
                if (c.moveToFirst()) name = c.getString(0);
                c.close();
            }
        } catch (Exception ignored) {}
        if (name == null) name = u.getLastPathSegment();
        if (name == null) return "Video";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
