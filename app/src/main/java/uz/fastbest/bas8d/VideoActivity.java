package uz.fastbest.bas8d;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.Rational;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import uz.fastbest.bas8d.PlayerService.Track;

import static uz.fastbest.bas8d.Ui.ORANGE;
import static uz.fastbest.bas8d.Ui.TXT;
import static uz.fastbest.bas8d.Ui.WC;

/** VLC tarzı video oynatıcı. */
public class VideoActivity extends Activity implements SurfaceHolder.Callback {

    private PlayerService svc;
    private final Handler h = new Handler(Looper.getMainLooper());
    private SharedPreferences sp;
    private AudioManager am;

    private FrameLayout root;
    private SurfaceView surface;
    private SurfaceHolder holder;
    private LinearLayout top, bottom;
    private TextView title, tCur, tDur, info;
    private ImageView bPlay, lockBtn;
    private SeekBar seek;

    private boolean dragging, shown = true, keepOnce, locked, pip;
    private int fit; // 0 sığdır, 1 doldur, 2 uzat, 3 16:9, 4 4:3, 5 orijinal
    private Uri pending;
    private float bright = -1, volAcc;
    private int scrollMode; // 0 yok, 1 parlaklık, 2 ses, 3 sarma
    private int seekTarget = -1;

    private static final String[] FIT_NAMES = {"En iyi sığdır", "Ekranı doldur", "Ekrana uzat", "16:9", "4:3", "Orijinal boyut"};

    private final Runnable listener = () -> h.post(() -> { refresh(); layoutVideo(); });
    private final Runnable tick = new Runnable() {
        @Override public void run() { refresh(); h.postDelayed(this, 500); }
    };
    private final Runnable hide = () -> showControls(false);
    private final Runnable hideInfo = () -> info.setVisibility(View.GONE);
    private final Runnable hideLock = () -> { if (lockBtn != null) lockBtn.setVisibility(View.GONE); };

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

    private int dp(float v) { return Ui.dp(this, v); }

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        sp = Ui.prefs(this);
        fit = sp.getInt("fit", 0);
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(0xFF000000);
        getWindow().setNavigationBarColor(0xFF000000);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        buildUi();
        immersive();
        readIntent(getIntent());
        Intent i = new Intent(this, PlayerService.class);
        startService(i);
        bindService(i, conn, Context.BIND_AUTO_CREATE);
    }

    private void readIntent(Intent in) {
        if (in != null && Intent.ACTION_VIEW.equals(in.getAction()) && in.getData() != null) pending = in.getData();
    }

    @Override protected void onNewIntent(Intent in) {
        super.onNewIntent(in);
        readIntent(in);
        playPending();
    }

    private void playPending() {
        if (svc == null || pending == null) return;
        Track t = new Track();
        t.uri = pending;
        t.video = true;
        t.title = fileName(pending);
        try { String m = getContentResolver().getType(pending); if (m != null) t.mime = m; } catch (Exception ignored) {}
        List<Track> l = new ArrayList<>();
        l.add(t);
        startService(new Intent(this, PlayerService.class));
        svc.setList(l, 0);
        pending = null;
    }

    @Override protected void onResume() {
        super.onResume();
        keepOnce = false;
        immersive();
        h.removeCallbacks(tick);
        h.post(tick);
        if (!pip) showControls(true);
    }

    @Override protected void onPause() {
        super.onPause();
        if (!pip) h.removeCallbacks(tick);
    }

    @Override protected void onStop() {
        super.onStop();
        if (svc != null && !keepOnce && !isChangingConfigurations() && !sp.getBoolean("bgvideo", false)) svc.pause();
    }

    @Override protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        if (svc != null) {
            svc.listeners.remove(listener);
            try { unbindService(conn); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (locked) { flashLock(); return; }
        super.onBackPressed();
    }

    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        root.post(this::layoutVideo);
    }

    @Override public void onWindowFocusChanged(boolean f) {
        super.onWindowFocusChanged(f);
        if (f) immersive();
    }

    @Override public void onPictureInPictureModeChanged(boolean in, Configuration c) {
        super.onPictureInPictureModeChanged(in, c);
        pip = in;
        if (in) {
            top.setVisibility(View.GONE);
            bottom.setVisibility(View.GONE);
            info.setVisibility(View.GONE);
        } else showControls(true);
        root.post(this::layoutVideo);
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
            float va = vw / (float) vh;
            if (fit == 3) va = 16f / 9;
            else if (fit == 4) va = 4f / 3;
            float sa = W / (float) H;
            if (fit == 2) { w = W; hh = H; }
            else if (fit == 5) { w = Math.min(vw, W * 3); hh = Math.round(w / va); }
            else {
                boolean wider = va > sa;
                boolean fill = fit == 1;
                if (wider != fill) { w = W; hh = Math.round(W / va); }
                else { hh = H; w = Math.round(H * va); }
            }
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

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        surface = new SurfaceView(this);
        surface.getHolder().addCallback(this);
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        View touch = new View(this);
        root.addView(touch, new FrameLayout.LayoutParams(-1, -1));
        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { volAcc = 0; scrollMode = 0; return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (locked) { flashLock(); return true; }
                showControls(!shown);
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                if (locked || svc == null) return true;
                if (!sp.getBoolean("dtap", true)) { svc.toggle(); return true; }
                int sec = sp.getInt("seekSec", 10);
                float x = e.getX(), W = root.getWidth();
                if (x > W * 0.35f && x < W * 0.65f) { svc.toggle(); return true; }
                boolean right = x > W / 2f;
                svc.seekBy((right ? 1 : -1) * sec * 1000);
                flash(right ? "+" + sec + " sn" : "-" + sec + " sn");
                return true;
            }
            @Override public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                if (locked || a == null || svc == null) return false;
                if (scrollMode == 0) {
                    if (Math.abs(dx) > Math.abs(dy)) scrollMode = 3;
                    else if (!sp.getBoolean("gestures", true)) return false;
                    else scrollMode = a.getX() < root.getWidth() / 2f ? 1 : 2;
                }
                if (scrollMode == 3) {
                    int d = svc.duration();
                    if (d <= 0) return true;
                    if (seekTarget < 0) seekTarget = svc.position();
                    float total = b.getX() - a.getX();
                    int delta = Math.round(total / root.getWidth() * Math.min(d, 300000));
                    int target = Math.max(0, Math.min(d, svc.position() + 0));
                    target = Math.max(0, Math.min(d - 500, seekTarget + delta));
                    flash((delta >= 0 ? "+" : "-") + Ui.time(Math.abs(delta)) + "\n" + Ui.time(target) + " / " + Ui.time(d));
                    seekPreview = target;
                    return true;
                }
                float frac = dy / Math.max(1, root.getHeight());
                if (scrollMode == 1) {
                    WindowManager.LayoutParams lp = getWindow().getAttributes();
                    if (bright < 0) bright = lp.screenBrightness < 0 ? 0.5f : lp.screenBrightness;
                    bright = Math.max(0.01f, Math.min(1f, bright + frac * 1.5f));
                    lp.screenBrightness = bright;
                    getWindow().setAttributes(lp);
                    flash("Parlaklık %" + Math.round(bright * 100));
                } else {
                    int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    volAcc += frac * max * 1.5f;
                    int step = (int) volAcc;
                    if (step != 0) {
                        volAcc -= step;
                        int v = Math.max(0, Math.min(max, am.getStreamVolume(AudioManager.STREAM_MUSIC) + step));
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
                    }
                    flash("Ses %" + Math.round(am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max));
                }
                return true;
            }
        });
        touch.setOnTouchListener((v, e) -> {
            boolean r = gd.onTouchEvent(e);
            if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
                if (scrollMode == 3 && seekPreview >= 0 && svc != null) svc.seek(seekPreview);
                seekPreview = -1;
                seekTarget = -1;
                scrollMode = 0;
            }
            return r;
        });

        info = Ui.text(this, "", 20, TXT, true);
        info.setGravity(Gravity.CENTER);
        info.setPadding(dp(20), dp(12), dp(20), dp(12));
        info.setBackground(Ui.round(0x99000000, dp(12)));
        info.setVisibility(View.GONE);
        root.addView(info, new FrameLayout.LayoutParams(WC, WC, Gravity.CENTER));

        // üst
        top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xCC000000, 0x00000000}));
        top.setPadding(dp(6), dp(16), dp(6), dp(30));
        top.addView(Ui.btn(this, R.drawable.ic_back, TXT, 26, 10, v -> finish()));
        title = Ui.text(this, "", 18, TXT, false);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(dp(8), 0, dp(8), 0);
        top.addView(title, new LinearLayout.LayoutParams(0, WC, 1));
        top.addView(Ui.btn(this, R.drawable.ic_rotate, TXT, 24, 11, v -> rotate()));
        top.addView(Ui.btn(this, R.drawable.ic_more_vert, TXT, 26, 10, this::menu));
        root.addView(top, new FrameLayout.LayoutParams(-1, WC, Gravity.TOP));

        // alt
        bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xDD000000, 0x00000000}));
        bottom.setPadding(dp(10), dp(30), dp(10), dp(12));

        LinearLayout sr = new LinearLayout(this);
        sr.setGravity(Gravity.CENTER_VERTICAL);
        tCur = Ui.text(this, "0:00", 14, TXT, false);
        tDur = Ui.text(this, "0:00", 14, TXT, false);
        tDur.setOnClickListener(v -> {
            sp.edit().putBoolean("remain", !sp.getBoolean("remain", false)).apply();
            refresh();
        });
        seek = new SeekBar(this);
        Ui.tint(seek, 0x66FFFFFF);
        seek.setMax(1000);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user && svc != null) {
                    int ms = (int) ((long) svc.duration() * p / 1000);
                    tCur.setText(Ui.time(ms));
                    svc.seek(ms);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) { dragging = true; h.removeCallbacks(hide); }
            @Override public void onStopTrackingTouch(SeekBar s) { dragging = false; poke(); }
        });
        sr.addView(tCur);
        sr.addView(seek, new LinearLayout.LayoutParams(0, WC, 1));
        sr.addView(tDur);
        bottom.addView(sr);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, 0);
        ImageView bLock = Ui.btn(this, R.drawable.ic_lock, TXT, 24, 12, v -> lock(true));
        ImageView bBack = Ui.btn(this, R.drawable.ic_rewind, TXT, 30, 10, v -> jump(-1));
        ImageView bPrev = Ui.btn(this, R.drawable.ic_prev, TXT, 32, 10, v -> { if (svc != null) svc.prev(); poke(); });
        bPlay = new ImageView(this);
        bPlay.setImageDrawable(Ui.icon(this, R.drawable.ic_pause, TXT));
        bPlay.setPadding(dp(14), dp(14), dp(14), dp(14));
        bPlay.setBackground(Ui.circle(0, TXT, dp(2)));
        bPlay.setOnClickListener(v -> { if (svc != null) svc.toggle(); poke(); });
        ImageView bNext = Ui.btn(this, R.drawable.ic_next, TXT, 32, 10, v -> { if (svc != null) svc.next(false); poke(); });
        ImageView bFwd = Ui.btn(this, R.drawable.ic_jump, TXT, 30, 10, v -> jump(1));
        ImageView bFit = Ui.btn(this, R.drawable.ic_aspect, TXT, 24, 12, v -> {
            fit = (fit + 1) % FIT_NAMES.length;
            sp.edit().putInt("fit", fit).apply();
            layoutVideo();
            flash(FIT_NAMES[fit]);
            poke();
        });
        row.addView(bLock);
        row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        row.addView(bBack);
        row.addView(bPrev);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(60), dp(60));
        pp.setMargins(dp(10), 0, dp(10), 0);
        row.addView(bPlay, pp);
        row.addView(bNext);
        row.addView(bFwd);
        row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        row.addView(bFit);
        bottom.addView(row);
        root.addView(bottom, new FrameLayout.LayoutParams(-1, WC, Gravity.BOTTOM));

        // kilit açma düğmesi
        lockBtn = new ImageView(this);
        lockBtn.setImageDrawable(Ui.icon(this, R.drawable.ic_lock, ORANGE));
        lockBtn.setPadding(dp(14), dp(14), dp(14), dp(14));
        lockBtn.setBackground(Ui.circle(0xAA000000, ORANGE, dp(2)));
        lockBtn.setVisibility(View.GONE);
        lockBtn.setOnClickListener(v -> lock(false));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM | Gravity.START);
        lp.setMargins(dp(24), 0, 0, dp(40));
        root.addView(lockBtn, lp);

        setContentView(root);
    }

    private int seekPreview = -1;

    private void jump(int dir) {
        if (svc == null) return;
        int sec = sp.getInt("seekSec", 10);
        svc.seekBy(dir * sec * 1000);
        flash((dir > 0 ? "+" : "-") + sec + " sn");
        poke();
    }

    private void rotate() {
        boolean land = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        setRequestedOrientation(land ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        poke();
    }

    private void lock(boolean on) {
        locked = on;
        if (on) {
            showControls(false);
            flashLock();
            Ui.toast(this, "Ekran kilitlendi. Açmak için kilit düğmesine dokunun.");
        } else {
            lockBtn.setVisibility(View.GONE);
            showControls(true);
        }
    }

    private void flashLock() {
        lockBtn.setVisibility(View.VISIBLE);
        h.removeCallbacks(hideLock);
        h.postDelayed(hideLock, 3000);
    }

    private void showControls(boolean on) {
        if (locked || pip) on = false;
        shown = on;
        top.setVisibility(on ? View.VISIBLE : View.GONE);
        bottom.setVisibility(on ? View.VISIBLE : View.GONE);
        h.removeCallbacks(hide);
        if (on) h.postDelayed(hide, 4000);
        else immersive();
    }

    private void poke() {
        h.removeCallbacks(hide);
        if (shown) h.postDelayed(hide, 4000);
    }

    private void flash(String s) {
        info.setText(s);
        info.setVisibility(View.VISIBLE);
        h.removeCallbacks(hideInfo);
        h.postDelayed(hideInfo, 900);
    }

    private void refresh() {
        if (svc == null) return;
        Track t = svc.current();
        title.setText(t == null ? "" : t.title);
        bPlay.setImageDrawable(Ui.icon(this, svc.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play, TXT));
        int d = svc.duration(), p = svc.position();
        if (!dragging) {
            seek.setProgress(d > 0 ? (int) (p * 1000L / d) : 0);
            tCur.setText(Ui.time(p));
        }
        tDur.setText(sp.getBoolean("remain", false) ? "-" + Ui.time(d - p) : Ui.time(d));
    }

    // ---------- menü ----------

    private void menu(View anchor) {
        if (svc == null) return;
        poke();
        String[] labels = {"Kilitle", "Uyku zamanlayıcı", "Oynatma hızı", "Sıçranacak zaman", "Dengeleyici",
                "Ses olarak oynat", "Açılan pencerede oynatıcı", "Yineleme kipi", "Görüntü bilgileri", "Yer imleri",
                "A-B yineleme", "Listeyi kaydet", "Denetim ayarları", "Görüntü oynatıcı ipuçları"};
        int[] icons = {R.drawable.ic_lock, R.drawable.ic_timer, R.drawable.ic_speed, R.drawable.ic_jump,
                R.drawable.ic_tune, R.drawable.ic_headset, R.drawable.ic_pip, R.drawable.ic_repeat, R.drawable.ic_info,
                R.drawable.ic_bookmark, R.drawable.ic_ab, R.drawable.ic_playlist_add, R.drawable.ic_settings,
                R.drawable.ic_tips};
        Ui.menu(anchor, labels, icons, k -> {
            switch (k) {
                case 0: lock(true); break;
                case 1: Ui.sleepDialog(this, svc); break;
                case 2: Ui.speedDialog(this, svc); break;
                case 3: Ui.jumpDialog(this, svc); break;
                case 4:
                    keepOnce = true;
                    startActivity(new Intent(this, EffectsActivity.class));
                    break;
                case 5:
                    keepOnce = true;
                    startActivity(new Intent(this, AudioActivity.class));
                    finish();
                    break;
                case 6: enterPip(); break;
                case 7: repeatDialog(); break;
                case 8: Ui.infoDialog(this, svc.current(), svc); break;
                case 9: Ui.bookmarksDialog(this, svc); break;
                case 10: Ui.abRepeat(this, svc); break;
                case 11: Ui.savePlaylistDialog(this, svc.list); break;
                case 12: Ui.controlSettings(this, true); break;
                default: Ui.tips(this, "Görüntü oynatıcı ipuçları", Ui.VIDEO_TIPS); break;
            }
        });
    }

    private void repeatDialog() {
        String[] items = {"Yineleme yok", "Tümünü yinele", "Bunu yinele"};
        Ui.dlg(this).setTitle("Yineleme kipi").setSingleChoiceItems(items, svc.repeat, (d, i) -> {
            svc.repeat = i;
            svc.save();
            d.dismiss();
            Ui.toast(this, items[i]);
        }).show();
    }

    private void enterPip() {
        if (Build.VERSION.SDK_INT < 26) { Ui.toast(this, "Bu telefon açılan pencereyi desteklemiyor"); return; }
        try {
            int w = svc.vw > 0 ? svc.vw : 16, hh = svc.vh > 0 ? svc.vh : 9;
            float r = w / (float) hh;
            if (r > 2.39f) { w = 239; hh = 100; }
            else if (r < 0.42f) { w = 42; hh = 100; }
            PictureInPictureParams p = new PictureInPictureParams.Builder().setAspectRatio(new Rational(w, hh)).build();
            enterPictureInPictureMode(p);
        } catch (Exception e) {
            Ui.toast(this, "Açılan pencere açılamadı. Telefon ayarlarından bu uygulamaya \"pencere içinde pencere\" izni verin.");
        }
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
