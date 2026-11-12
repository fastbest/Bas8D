package uz.fastbest.bas8d;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import uz.fastbest.bas8d.PlayerService.Track;

import static uz.fastbest.bas8d.Ui.MP;
import static uz.fastbest.bas8d.Ui.ORANGE;
import static uz.fastbest.bas8d.Ui.SUB;
import static uz.fastbest.bas8d.Ui.TXT;
import static uz.fastbest.bas8d.Ui.WC;

/** VLC tarzı ses oynatıcı ekranı. */
public class AudioActivity extends Activity {

    private PlayerService svc;
    private final Handler h = new Handler(Looper.getMainLooper());

    private TextView title, artist, tCur, tDur;
    private ImageView bQueue, bPlay, bShuffle, bRepeat, cover;
    private SeekBar seek;
    private EditText search;
    private ListView queueView;
    private FrameLayout coverBox;
    private QueueAdapter adapter;
    private final List<Integer> shown = new ArrayList<>();
    private boolean dragging, showQueue;
    private String filter = "";
    private String lastUri = "";

    private final Runnable listener = () -> h.post(this::refresh);

    private final Runnable tick = new Runnable() {
        @Override public void run() { refresh(); h.postDelayed(this, 500); }
    };

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            svc = ((PlayerService.LocalBinder) b).get();
            svc.listeners.add(listener);
            lastUri = "";
            refresh();
        }
        @Override public void onServiceDisconnected(ComponentName n) { svc = null; }
    };

    private int builtStamp;

    private int dp(float v) { return Ui.dp(this, v); }

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        Ui.apply(this);
        builtStamp = Ui.stamp;
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        showQueue = Ui.prefs(this).getBoolean("showQueue", true);
        buildUi();
        Intent i = new Intent(this, PlayerService.class);
        startService(i);
        bindService(i, conn, Context.BIND_AUTO_CREATE);
    }

    @Override protected void onResume() {
        super.onResume();
        Ui.load(this);
        if (Ui.stamp != builtStamp) { recreate(); return; }
        h.removeCallbacks(tick);
        h.post(tick);
    }

    @Override protected void onPause() {
        super.onPause();
        h.removeCallbacks(tick);
    }

    @Override protected void onDestroy() {
        if (svc != null) {
            svc.listeners.remove(listener);
            try { unbindService(conn); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (search.getVisibility() == View.VISIBLE) { toggleSearch(); return; }
        super.onBackPressed();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Ui.GLASS ? Ui.pageBg() : Ui.LIGHT ? new android.graphics.drawable.ColorDrawable(Ui.BG)
                : new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF1C1C1C, 0xFF2A2A2A, 0xFF161616}));

        // üst çubuk
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Ui.BAR);
        bar.setPadding(dp(20), dp(12), dp(4), dp(12));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        title = Ui.text(this, "", 20, TXT, false);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        title.setMarqueeRepeatLimit(-1);
        title.setSelected(true);
        artist = Ui.text(this, "", 17, SUB, false);
        artist.setSingleLine(true);
        artist.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(title);
        col.addView(artist);
        bar.addView(col, new LinearLayout.LayoutParams(0, WC, 1));
        bar.addView(Ui.btn(this, R.drawable.ic_search, TXT, 28, 12, v -> toggleSearch()));
        bQueue = Ui.btn(this, R.drawable.ic_queue_play, ORANGE, 28, 12, v -> {
            showQueue = !showQueue;
            Ui.prefs(this).edit().putBoolean("showQueue", showQueue).apply();
            applyQueueVisibility();
        });
        bar.addView(bQueue);
        bar.addView(Ui.btn(this, R.drawable.ic_more_vert, TXT, 28, 10, this::menu));
        root.addView(bar);

        View line = new View(this);
        line.setBackgroundColor(Ui.LIGHT || Ui.GLASS ? Ui.LINE : 0xFF0E0E0E);
        root.addView(line, new LinearLayout.LayoutParams(MP, dp(2)));

        search = new EditText(this);
        search.setHint("Çalma sırasında ara");
        search.setHintTextColor(SUB);
        search.setTextColor(TXT);
        search.setSingleLine(true);
        search.setBackground(Ui.round(Ui.ROW, dp(8)));
        search.setPadding(dp(14), dp(10), dp(14), dp(10));
        search.setVisibility(View.GONE);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { filter = s.toString(); rebuildQueue(); }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(MP, WC);
        slp.setMargins(dp(12), dp(10), dp(12), dp(4));
        root.addView(search, slp);

        // orta: sıra veya kapak
        FrameLayout mid = new FrameLayout(this);
        queueView = new ListView(this);
        queueView.setDivider(null);
        queueView.setSelector(new android.graphics.drawable.ColorDrawable(0));
        adapter = new QueueAdapter();
        queueView.setAdapter(adapter);
        queueView.setOnItemClickListener((p, v, pos, id) -> { if (svc != null) svc.playIndex(shown.get(pos)); });
        queueView.setOnItemLongClickListener((p, v, pos, id) -> { itemMenu(shown.get(pos)); return true; });
        mid.addView(queueView);

        coverBox = new FrameLayout(this);
        cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setClipToOutline(true);
        cover.setBackground(Ui.round(Ui.ROW, dp(8)));
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(dp(280), dp(280), Gravity.CENTER);
        coverBox.addView(cover, cp);
        mid.addView(coverBox);
        root.addView(mid, new LinearLayout.LayoutParams(MP, 0, 1));

        // zaman ve çubuk
        LinearLayout times = new LinearLayout(this);
        times.setPadding(dp(24), dp(8), dp(24), 0);
        tCur = Ui.text(this, "0:00", 17, TXT, false);
        tDur = Ui.text(this, "0:00", 17, TXT, false);
        tDur.setGravity(Gravity.END);
        times.addView(tCur, new LinearLayout.LayoutParams(0, WC, 1));
        times.addView(tDur, new LinearLayout.LayoutParams(0, WC, 1));
        root.addView(times);

        seek = new SeekBar(this);
        Ui.tint(seek, Ui.TRACK);
        seek.setPadding(dp(4), dp(14), dp(4), dp(14));
        seek.setMax(1000);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user && svc != null) tCur.setText(Ui.time((long) svc.duration() * p / 1000));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                if (svc != null) svc.seek((int) ((long) svc.duration() * s.getProgress() / 1000));
            }
        });
        root.addView(seek, new LinearLayout.LayoutParams(MP, WC));

        // denetimler
        LinearLayout ctl = new LinearLayout(this);
        ctl.setGravity(Gravity.CENTER);
        ctl.setPadding(0, dp(8), 0, dp(26));
        bShuffle = Ui.btn(this, R.drawable.ic_shuffle, TXT, 30, 12, v -> {
            if (svc == null) return;
            svc.shuffle = !svc.shuffle;
            svc.save();
            Ui.toast(this, svc.shuffle ? "Karışık çalma açık" : "Karışık çalma kapalı");
            refresh();
        });
        ImageView bPrev = Ui.btn(this, R.drawable.ic_prev, TXT, 44, 10, v -> { if (svc != null) svc.prev(); });
        bPlay = new ImageView(this);
        bPlay.setImageDrawable(Ui.icon(this, R.drawable.ic_pause, TXT));
        bPlay.setPadding(dp(20), dp(20), dp(20), dp(20));
        bPlay.setBackground(Ui.circle(0, TXT, dp(3)));
        bPlay.setOnClickListener(v -> { if (svc != null) svc.toggle(); });
        ImageView bNext = Ui.btn(this, R.drawable.ic_next, TXT, 44, 10, v -> { if (svc != null) svc.next(false); });
        bRepeat = Ui.btn(this, R.drawable.ic_repeat, ORANGE, 34, 12, v -> {
            if (svc == null) return;
            svc.repeat = (svc.repeat + 1) % 3;
            svc.save();
            Ui.toast(this, svc.repeat == 0 ? "Yineleme kapalı" : svc.repeat == 1 ? "Tümünü yinele" : "Bu kaydı yinele");
            refresh();
        });
        ctl.addView(bShuffle);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(dp(20), 1);
        ctl.addView(new View(this), gap);
        ctl.addView(bPrev);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(78), dp(78));
        pp.setMargins(dp(22), 0, dp(22), 0);
        ctl.addView(bPlay, pp);
        ctl.addView(bNext);
        ctl.addView(new View(this), new LinearLayout.LayoutParams(dp(20), 1));
        ctl.addView(bRepeat);
        root.addView(ctl, new LinearLayout.LayoutParams(MP, WC));

        setContentView(root);
        applyQueueVisibility();
    }

    private void toggleSearch() {
        boolean on = search.getVisibility() != View.VISIBLE;
        search.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) {
            if (!showQueue) { showQueue = true; applyQueueVisibility(); }
            search.requestFocus();
            android.view.inputmethod.InputMethodManager im =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (im != null) im.showSoftInput(search, 0);
        } else {
            search.setText("");
            android.view.inputmethod.InputMethodManager im =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (im != null) im.hideSoftInputFromWindow(search.getWindowToken(), 0);
        }
    }

    private void applyQueueVisibility() {
        queueView.setVisibility(showQueue ? View.VISIBLE : View.GONE);
        coverBox.setVisibility(showQueue ? View.GONE : View.VISIBLE);
        bQueue.setImageDrawable(Ui.icon(this, R.drawable.ic_queue_play, showQueue ? ORANGE : TXT));
    }

    private void rebuildQueue() {
        shown.clear();
        if (svc == null) return;
        String f = filter.toLowerCase(Locale.ROOT).trim();
        for (int i = 0; i < svc.list.size(); i++) {
            Track t = svc.list.get(i);
            if (f.isEmpty() || t.title.toLowerCase(Locale.ROOT).contains(f)
                    || t.artist.toLowerCase(Locale.ROOT).contains(f)) shown.add(i);
        }
        adapter.notifyDataSetChanged();
    }

    private void refresh() {
        if (svc == null) return;
        Track t = svc.current();
        if (t == null) {
            title.setText("Çalan kayıt yok");
            artist.setText("");
            bPlay.setImageDrawable(Ui.icon(this, R.drawable.ic_play, TXT));
            rebuildQueue();
            return;
        }
        String u = t.uri.toString() + "#" + svc.list.size() + "#" + svc.index;
        if (!u.equals(lastUri)) {
            boolean trackChanged = !lastUri.startsWith(t.uri.toString() + "#");
            lastUri = u;
            title.setText(t.title);
            artist.setText(t.video ? Lib.folderName(t.folder) : t.artist);
            rebuildQueue();
            if (trackChanged) {
                int pos = shown.indexOf(svc.index);
                if (pos >= 0) queueView.setSelectionFromTop(pos, dp(80));
                cover.setImageDrawable(null);
                cover.setScaleType(ImageView.ScaleType.CENTER);
                cover.setImageDrawable(Ui.icon(this, R.drawable.ic_note, Ui.DIM));
                final String want = t.uri.toString();
                if (t.video) Ui.thumb(cover, t);
                else Ui.art(this, t, bm -> {
                    if (bm != null && svc != null && svc.current() != null && want.equals(svc.current().uri.toString())) {
                        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
                        cover.setImageBitmap(bm);
                    }
                });
                if (t.video) cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            }
        }
        bPlay.setImageDrawable(Ui.icon(this, svc.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play, TXT));
        bShuffle.setImageDrawable(Ui.icon(this, R.drawable.ic_shuffle, svc.shuffle ? ORANGE : TXT));
        bRepeat.setImageDrawable(Ui.icon(this, svc.repeat == 2 ? R.drawable.ic_repeat_one : R.drawable.ic_repeat,
                svc.repeat == 0 ? TXT : ORANGE));
        int d = svc.duration(), p = svc.position();
        if (!dragging) {
            seek.setProgress(d > 0 ? (int) (p * 1000L / d) : 0);
            tCur.setText(Ui.time(p));
        }
        tDur.setText(Ui.time(d > 0 ? d : t.dur));
    }

    // ---------- menüler ----------

    private void menu(View anchor) {
        if (svc == null) return;
        String[] labels = {"Uyku zamanlayıcı", "Oynatma hızı", "Sıçranacak zaman", "Dengeleyici", "Yer imleri",
                "A-B yineleme", "Listeyi kaydet", "Kayıt bilgilerini paylaş", "Denetim ayarları",
                "Ses oynatıcı ipuçları", "Liste ipuçları"};
        int[] icons = {R.drawable.ic_timer, R.drawable.ic_speed, R.drawable.ic_jump, R.drawable.ic_tune,
                R.drawable.ic_bookmark, R.drawable.ic_ab, R.drawable.ic_playlist_add, R.drawable.ic_share,
                R.drawable.ic_settings, R.drawable.ic_tips, R.drawable.ic_tips};
        Ui.menu(anchor, labels, icons, k -> {
            switch (k) {
                case 0: Ui.sleepDialog(this, svc); break;
                case 1: Ui.speedDialog(this, svc); break;
                case 2: Ui.jumpDialog(this, svc); break;
                case 3: startActivity(new Intent(this, EffectsActivity.class)); break;
                case 4: Ui.bookmarksDialog(this, svc); break;
                case 5: Ui.abRepeat(this, svc); break;
                case 6: Ui.savePlaylistDialog(this, svc.list); break;
                case 7: Ui.shareInfo(this, svc.current()); break;
                case 8: Ui.controlSettings(this, false); break;
                case 9: Ui.tips(this, "Ses oynatıcı ipuçları", Ui.AUDIO_TIPS); break;
                default: Ui.tips(this, "Liste ipuçları", Ui.LIST_TIPS); break;
            }
        });
    }

    private void itemMenu(int i) {
        if (svc == null || i < 0 || i >= svc.list.size()) return;
        final Track t = svc.list.get(i);
        Ui.menu(queueView, new String[]{"Çal", "Sonraki olarak çal", "Yukarı taşı", "Aşağı taşı", "Listeye ekle",
                        "Bilgiler", "Listeden kaldır"},
                new int[]{R.drawable.ic_play, R.drawable.ic_queue_play, R.drawable.ic_sort, R.drawable.ic_sort,
                        R.drawable.ic_playlist_add, R.drawable.ic_info, R.drawable.ic_delete}, k -> {
                    if (svc == null || i >= svc.list.size()) return;
                    switch (k) {
                        case 0: svc.playIndex(i); break;
                        case 1:
                            if (i != svc.index) {
                                int target = svc.index + (i < svc.index ? 0 : 1);
                                svc.move(i, Math.min(target, svc.list.size() - 1));
                            }
                            break;
                        case 2: svc.move(i, i - 1); break;
                        case 3: svc.move(i, i + 1); break;
                        case 4: Ui.addToPlaylistDialog(this, Collections.singletonList(t)); break;
                        case 5: Ui.infoDialog(this, t, svc); break;
                        default: svc.removeAt(i); break;
                    }
                    lastUri = "";
                    refresh();
                });
    }

    private class QueueAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int pos, View cv, ViewGroup parent) {
            LinearLayout row = (LinearLayout) cv;
            if (row == null) {
                row = new LinearLayout(AudioActivity.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(20), dp(12), dp(4), dp(12));
                ImageView iv = new ImageView(AudioActivity.this);
                iv.setPadding(dp(4), dp(4), dp(4), dp(4));
                row.addView(iv, new LinearLayout.LayoutParams(dp(52), dp(52)));
                LinearLayout col = new LinearLayout(AudioActivity.this);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setPadding(dp(22), 0, dp(8), 0);
                TextView a = Ui.text(AudioActivity.this, "", 18, TXT, false);
                a.setSingleLine(true);
                a.setEllipsize(TextUtils.TruncateAt.END);
                TextView b = Ui.text(AudioActivity.this, "", 16, SUB, true);
                b.setSingleLine(true);
                b.setEllipsize(TextUtils.TruncateAt.END);
                b.setPadding(0, dp(4), 0, 0);
                col.addView(a);
                col.addView(b);
                row.addView(col, new LinearLayout.LayoutParams(0, WC, 1));
                ImageView more = Ui.img(AudioActivity.this, R.drawable.ic_more_vert, TXT, 26, 12);
                Ui.ripple(more, true);
                row.addView(more);
            }
            final int i = shown.get(pos);
            Track t = svc.list.get(i);
            boolean cur = i == svc.index;
            ((ImageView) row.getChildAt(0)).setImageDrawable(Ui.icon(AudioActivity.this,
                    cur ? R.drawable.ic_bars : (t.video ? R.drawable.ic_movie : R.drawable.ic_note), cur ? TXT : Ui.DIM));
            LinearLayout col = (LinearLayout) row.getChildAt(1);
            ((TextView) col.getChildAt(0)).setText(t.title);
            ((TextView) col.getChildAt(0)).setTextColor(TXT);
            String sub = t.video ? Lib.folderName(t.folder) : t.artist;
            ((TextView) col.getChildAt(1)).setText(t.dur > 0 ? Ui.time(t.dur) + " · " + sub : sub);
            row.getChildAt(2).setOnClickListener(v -> itemMenu(i));
            return row;
        }
    }
}
