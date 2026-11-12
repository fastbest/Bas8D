package uz.fastbest.bas8d;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.util.LruCache;
import android.util.Size;
import android.widget.ImageView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    interface IntCb { void on(int v); }
    interface Fmt { String f(int v); }

    static final int BG = 0xFF0E0F14, CARD = 0xFF1A1C25, ACC = 0xFF7C5CFF, ACC2 = 0xFF35D6C3,
            TXT = 0xFFFFFFFF, SUB = 0xFF9AA0B4;

    private PlayerService svc;
    private final Handler h = new Handler(Looper.getMainLooper());

    private final List<PlayerService.Track> all = new ArrayList<>();
    private final List<PlayerService.Track> shown = new ArrayList<>();
    private String filter = "";

    private FrameLayout content;
    private View songsView;
    private TextView[] tabs;
    private int tab;
    private ListView listView;
    private TextView emptyView;
    private SongAdapter adapter;

    private TextView npTitle, npArtist, tCur, tDur, bPlay, bShuffle, bRepeat;
    private SeekBar seek;
    private boolean dragging;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            svc = ((PlayerService.LocalBinder) b).get();
            svc.listeners.add(uiListener);
            showTab(tab);
            refresh();
        }
        @Override public void onServiceDisconnected(ComponentName n) { svc = null; }
    };

    private final Runnable uiListener = new Runnable() {
        @Override public void run() { h.post(new Runnable() { @Override public void run() { refresh(); } }); }
    };

    // videolar
    private final List<PlayerService.Track> vAll = new ArrayList<>();
    private final List<PlayerService.Track> vShown = new ArrayList<>();
    private String vFilter = "";
    private View videosView;
    private TextView vEmpty;
    private VideoAdapter vAdapter;
    private final LruCache<String, Bitmap> thumbs = new LruCache<>(80);
    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refresh();
            h.postDelayed(this, 500);
        }
    };

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        buildUi();
        Intent i = new Intent(this, PlayerService.class);
        startService(i);
        bindService(i, conn, Context.BIND_AUTO_CREATE);
        askPermission();
        tab = getIntent().getIntExtra("tab", 0);
        showTab(tab);
    }

    @Override protected void onNewIntent(Intent in) {
        super.onNewIntent(in);
        if (in != null && in.hasExtra("tab")) showTab(in.getIntExtra("tab", 0));
    }

    @Override protected void onResume() {
        super.onResume();
        h.removeCallbacks(tick);
        h.post(tick);
    }

    @Override protected void onPause() {
        super.onPause();
        h.removeCallbacks(tick);
    }

    @Override protected void onDestroy() {
        if (svc != null) {
            svc.listeners.remove(uiListener);
            boolean idle = !svc.isPlaying();
            try { unbindService(conn); } catch (Exception ignored) {}
            if (idle) stopService(new Intent(this, PlayerService.class));
        }
        super.onDestroy();
    }

    // ---------- izin ve şarkı listesi ----------

    private String audioPerm() {
        return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private String videoPerm() {
        return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_VIDEO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private boolean granted(String p) {
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    private void askPermission() {
        if (granted(audioPerm()) && granted(videoPerm())) {
            loadSongs();
            loadVideos();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{audioPerm(), videoPerm(), Manifest.permission.POST_NOTIFICATIONS}, 1);
        } else {
            requestPermissions(new String[]{audioPerm()}, 1);
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        if (granted(audioPerm())) loadSongs();
        else applyFilter();
        if (granted(videoPerm())) loadVideos();
        else applyVideoFilter();
    }

    private void loadSongs() {
        new Thread(new Runnable() {
            @Override public void run() {
                final List<PlayerService.Track> found = new ArrayList<>();
                try {
                    Uri base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                    Cursor c = getContentResolver().query(base,
                            new String[]{MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
                                    MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION},
                            MediaStore.Audio.Media.IS_MUSIC + "!=0", null,
                            MediaStore.Audio.Media.TITLE + " COLLATE NOCASE");
                    if (c != null) {
                        while (c.moveToNext()) {
                            PlayerService.Track t = new PlayerService.Track();
                            t.uri = ContentUris.withAppendedId(base, c.getLong(0));
                            t.title = c.getString(1) == null ? "Adsız" : c.getString(1);
                            String a = c.getString(2);
                            t.artist = a == null || a.equals("<unknown>") ? "Bilinmeyen sanatçı" : a;
                            t.dur = c.getLong(3);
                            found.add(t);
                        }
                        c.close();
                    }
                } catch (Exception ignored) {}
                h.post(new Runnable() {
                    @Override public void run() {
                        List<PlayerService.Track> added = new ArrayList<>();
                        for (PlayerService.Track t : all) {
                            if (!"content".equals(t.uri.getScheme())
                                    || !"media".equals(t.uri.getAuthority())) added.add(t);
                        }
                        all.clear();
                        all.addAll(added);
                        all.addAll(found);
                        applyFilter();
                    }
                });
            }
        }).start();
    }

    private void applyFilter() {
        shown.clear();
        String f = filter.toLowerCase(Locale.ROOT).trim();
        for (PlayerService.Track t : all) {
            if (f.isEmpty() || t.title.toLowerCase(Locale.ROOT).contains(f)
                    || t.artist.toLowerCase(Locale.ROOT).contains(f)) shown.add(t);
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        if (emptyView != null) {
            boolean granted = checkSelfPermission(audioPerm()) == PackageManager.PERMISSION_GRANTED;
            emptyView.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
            emptyView.setText(!granted
                    ? "Telefondaki müzikleri göstermek için izin gerekli.\nİzin vermek için buraya dokunun veya \"Dosya aç\" ile şarkı seçin."
                    : all.isEmpty() ? "Telefonda müzik bulunamadı.\n\"Dosya aç\" ile şarkı seçebilirsiniz."
                    : "Aramaya uygun şarkı yok.");
        }
    }

    private void openFiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/*", "video/*"});
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try { startActivityForResult(i, 2); }
        catch (Exception e) { Toast.makeText(this, "Dosya seçici açılamadı", Toast.LENGTH_SHORT).show(); }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != 2 || res != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        ClipData cd = data.getClipData();
        if (cd != null) for (int i = 0; i < cd.getItemCount(); i++) uris.add(cd.getItemAt(i).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        if (uris.isEmpty()) return;
        List<PlayerService.Track> picked = new ArrayList<>();
        List<PlayerService.Track> vPicked = new ArrayList<>();
        for (Uri u : uris) {
            try {
                getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {}
            PlayerService.Track t = new PlayerService.Track();
            t.uri = u;
            t.title = fileName(u);
            String mime = null;
            try { mime = getContentResolver().getType(u); } catch (Exception ignored) {}
            t.video = mime != null && mime.startsWith("video");
            t.artist = t.video ? "Seçilen video" : "Seçilen dosya";
            (t.video ? vPicked : picked).add(t);
        }
        if (!vPicked.isEmpty()) {
            vAll.addAll(0, vPicked);
            vFilter = "";
            applyVideoFilter();
            showTab(1);
            if (svc != null) {
                startService(new Intent(this, PlayerService.class));
                svc.setList(vPicked, 0);
                startActivity(new Intent(this, VideoActivity.class));
            }
            if (!picked.isEmpty()) { all.addAll(0, picked); applyFilter(); }
            return;
        }
        all.addAll(0, picked);
        filter = "";
        applyFilter();
        if (svc != null) {
            startService(new Intent(this, PlayerService.class));
            svc.setList(shown, 0);
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
        if (name == null) return "Şarkı";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    // ---------- arayüz ----------

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private GradientDrawable round(int color, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(r));
        return g;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout.LayoutParams lp(int w, int hgt, float weight) {
        return new LinearLayout.LayoutParams(w, hgt, weight);
    }

    private static final int MP = ViewGroup.LayoutParams.MATCH_PARENT, WC = ViewGroup.LayoutParams.WRAP_CONTENT;

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);

        TextView title = text("Bas8D Müzik", 20, TXT, true);
        title.setPadding(dp(18), dp(14), dp(18), dp(8));
        root.addView(title);

        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setPadding(dp(12), 0, dp(12), dp(8));
        String[] names = {"Şarkılar", "Videolar", "Ekolayzır", "8D Efekt"};
        tabs = new TextView[names.length];
        for (int i = 0; i < names.length; i++) {
            final int k = i;
            TextView t = text(names[i], 12.5f, TXT, true);
            t.setSingleLine(true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(10), 0, dp(10));
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showTab(k); }
            });
            LinearLayout.LayoutParams p = lp(0, WC, 1);
            p.setMargins(dp(3), 0, dp(3), 0);
            tabRow.addView(t, p);
            tabs[i] = t;
        }
        root.addView(tabRow);

        content = new FrameLayout(this);
        root.addView(content, lp(MP, 0, 1));

        root.addView(buildPlayerBar());
        setContentView(root);
        songsView = buildSongs();
        videosView = buildVideos();
    }

    private void showTab(int k) {
        tab = k;
        for (int i = 0; i < tabs.length; i++) {
            tabs[i].setBackground(round(i == k ? ACC : CARD, 12));
            tabs[i].setTextColor(i == k ? TXT : SUB);
        }
        content.removeAllViews();
        if (k == 0) content.addView(songsView);
        else if (k == 1) content.addView(videosView);
        else if (svc == null) content.addView(text("Yükleniyor…", 14, SUB, false));
        else content.addView(k == 2 ? buildEq() : buildFx());
    }

    private View buildSongs() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(16), 0, dp(16), dp(8));
        EditText search = new EditText(this);
        search.setHint("Şarkı veya sanatçı ara");
        search.setHintTextColor(SUB);
        search.setTextColor(TXT);
        search.setTextSize(14);
        search.setSingleLine(true);
        search.setBackground(round(CARD, 12));
        search.setPadding(dp(14), dp(10), dp(14), dp(10));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                filter = s.toString();
                applyFilter();
            }
        });
        top.addView(search, lp(0, WC, 1));
        TextView open = text("Dosya aç", 13, TXT, true);
        open.setBackground(round(ACC, 12));
        open.setPadding(dp(14), dp(11), dp(14), dp(11));
        open.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openFiles(); }
        });
        LinearLayout.LayoutParams op = lp(WC, WC, 0);
        op.leftMargin = dp(8);
        top.addView(open, op);
        box.addView(top);

        FrameLayout f = new FrameLayout(this);
        listView = new ListView(this);
        listView.setDivider(null);
        listView.setSelector(new android.graphics.drawable.ColorDrawable(0));
        adapter = new SongAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> p, View v, int pos, long id) {
                if (svc == null) return;
                startService(new Intent(MainActivity.this, PlayerService.class));
                svc.setList(shown, pos);
            }
        });
        f.addView(listView);
        emptyView = text("Şarkılar yükleniyor…", 14, SUB, false);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(dp(32), dp(32), dp(32), dp(32));
        emptyView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (checkSelfPermission(audioPerm()) != PackageManager.PERMISSION_GRANTED) askPermission();
            }
        });
        f.addView(emptyView, new FrameLayout.LayoutParams(MP, MP));
        box.addView(f, lp(MP, 0, 1));
        return box;
    }

    // ---------- videolar ----------

    private void loadVideos() {
        new Thread(new Runnable() {
            @Override public void run() {
                final List<PlayerService.Track> found = new ArrayList<>();
                try {
                    Uri base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                    Cursor c = getContentResolver().query(base,
                            new String[]{MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
                                    MediaStore.Video.Media.BUCKET_DISPLAY_NAME, MediaStore.Video.Media.DURATION},
                            null, null, MediaStore.Video.Media.DATE_ADDED + " DESC");
                    if (c != null) {
                        while (c.moveToNext()) {
                            PlayerService.Track t = new PlayerService.Track();
                            t.id = c.getLong(0);
                            t.uri = ContentUris.withAppendedId(base, t.id);
                            String n = c.getString(1) == null ? "Video" : c.getString(1);
                            int dot = n.lastIndexOf('.');
                            t.title = dot > 0 ? n.substring(0, dot) : n;
                            t.artist = c.getString(2) == null ? "Video" : c.getString(2);
                            t.dur = c.getLong(3);
                            t.video = true;
                            found.add(t);
                        }
                        c.close();
                    }
                } catch (Exception ignored) {}
                h.post(new Runnable() {
                    @Override public void run() {
                        List<PlayerService.Track> added = new ArrayList<>();
                        for (PlayerService.Track t : vAll) if (t.id == 0) added.add(t);
                        vAll.clear();
                        vAll.addAll(added);
                        vAll.addAll(found);
                        applyVideoFilter();
                    }
                });
            }
        }).start();
    }

    private void applyVideoFilter() {
        vShown.clear();
        String f = vFilter.toLowerCase(Locale.ROOT).trim();
        for (PlayerService.Track t : vAll) {
            if (f.isEmpty() || t.title.toLowerCase(Locale.ROOT).contains(f)
                    || t.artist.toLowerCase(Locale.ROOT).contains(f)) vShown.add(t);
        }
        if (vAdapter != null) vAdapter.notifyDataSetChanged();
        if (vEmpty != null) {
            vEmpty.setVisibility(vShown.isEmpty() ? View.VISIBLE : View.GONE);
            vEmpty.setText(!granted(videoPerm())
                    ? "Telefondaki videoları göstermek için izin gerekli.\nİzin vermek için buraya dokunun veya \"Dosya aç\" ile video seçin."
                    : vAll.isEmpty() ? "Telefonda video bulunamadı.\n\"Dosya aç\" ile video seçebilirsiniz."
                    : "Aramaya uygun video yok.");
        }
    }

    private View buildVideos() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(16), 0, dp(16), dp(8));
        EditText search = new EditText(this);
        search.setHint("Video veya klasör ara");
        search.setHintTextColor(SUB);
        search.setTextColor(TXT);
        search.setTextSize(14);
        search.setSingleLine(true);
        search.setBackground(round(CARD, 12));
        search.setPadding(dp(14), dp(10), dp(14), dp(10));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                vFilter = s.toString();
                applyVideoFilter();
            }
        });
        top.addView(search, lp(0, WC, 1));
        TextView open = text("Dosya aç", 13, TXT, true);
        open.setBackground(round(ACC, 12));
        open.setPadding(dp(14), dp(11), dp(14), dp(11));
        open.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openFiles(); }
        });
        LinearLayout.LayoutParams op = lp(WC, WC, 0);
        op.leftMargin = dp(8);
        top.addView(open, op);
        box.addView(top);

        FrameLayout f = new FrameLayout(this);
        ListView lv = new ListView(this);
        lv.setDivider(null);
        lv.setSelector(new android.graphics.drawable.ColorDrawable(0));
        vAdapter = new VideoAdapter();
        lv.setAdapter(vAdapter);
        lv.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> p, View v, int pos, long id) {
                if (svc == null) return;
                startService(new Intent(MainActivity.this, PlayerService.class));
                svc.setList(vShown, pos);
                startActivity(new Intent(MainActivity.this, VideoActivity.class));
            }
        });
        f.addView(lv);
        vEmpty = text("Videolar yükleniyor…", 14, SUB, false);
        vEmpty.setGravity(Gravity.CENTER);
        vEmpty.setPadding(dp(32), dp(32), dp(32), dp(32));
        vEmpty.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!granted(videoPerm())) askPermission();
            }
        });
        f.addView(vEmpty, new FrameLayout.LayoutParams(MP, MP));
        box.addView(f, lp(MP, 0, 1));
        return box;
    }

    private class VideoAdapter extends BaseAdapter {
        @Override public int getCount() { return vShown.size(); }
        @Override public Object getItem(int i) { return vShown.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int i, View cv, ViewGroup parent) {
            LinearLayout row = (LinearLayout) cv;
            if (row == null) {
                row = new LinearLayout(MainActivity.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(16), dp(7), dp(16), dp(7));
                ImageView iv = new ImageView(MainActivity.this);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setBackground(round(CARD, 10));
                iv.setClipToOutline(true);
                row.addView(iv, lp(dp(120), dp(68), 0));
                LinearLayout col = new LinearLayout(MainActivity.this);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setPadding(dp(12), 0, 0, 0);
                TextView a = text("", 14, TXT, false);
                a.setMaxLines(2);
                a.setEllipsize(TextUtils.TruncateAt.END);
                TextView b = text("", 12, SUB, false);
                b.setSingleLine(true);
                b.setEllipsize(TextUtils.TruncateAt.END);
                col.addView(a);
                col.addView(b);
                row.addView(col, lp(0, WC, 1));
            }
            PlayerService.Track t = vShown.get(i);
            boolean cur = svc != null && svc.current() != null && svc.current().uri.equals(t.uri);
            ImageView iv = (ImageView) row.getChildAt(0);
            LinearLayout col = (LinearLayout) row.getChildAt(1);
            TextView a = (TextView) col.getChildAt(0);
            a.setText(t.title);
            a.setTextColor(cur ? ACC2 : TXT);
            ((TextView) col.getChildAt(1)).setText(t.dur > 0 ? time(t.dur) + " · " + t.artist : t.artist);
            loadThumb(iv, t);
            return row;
        }
    }

    private void loadThumb(final ImageView iv, final PlayerService.Track t) {
        final String key = t.uri.toString();
        iv.setTag(key);
        Bitmap b = thumbs.get(key);
        iv.setImageBitmap(b);
        if (b != null) return;
        pool.submit(new Runnable() {
            @Override public void run() {
                Bitmap bm = null;
                try {
                    if (Build.VERSION.SDK_INT >= 29) {
                        bm = getContentResolver().loadThumbnail(t.uri, new Size(320, 180), null);
                    } else if (t.id > 0) {
                        bm = MediaStore.Video.Thumbnails.getThumbnail(getContentResolver(), t.id,
                                MediaStore.Video.Thumbnails.MINI_KIND, null);
                    }
                } catch (Throwable ignored) {}
                if (bm == null) return;
                final Bitmap fb = bm;
                h.post(new Runnable() {
                    @Override public void run() {
                        thumbs.put(key, fb);
                        if (key.equals(iv.getTag())) iv.setImageBitmap(fb);
                    }
                });
            }
        });
    }

    private class SongAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int i, View cv, ViewGroup parent) {
            LinearLayout row = (LinearLayout) cv;
            if (row == null) {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(20), dp(10), dp(20), dp(10));
                TextView a = text("", 15, TXT, false);
                a.setSingleLine(true);
                a.setEllipsize(TextUtils.TruncateAt.END);
                TextView b = text("", 12, SUB, false);
                b.setSingleLine(true);
                b.setEllipsize(TextUtils.TruncateAt.END);
                row.addView(a);
                row.addView(b);
            }
            PlayerService.Track t = shown.get(i);
            boolean cur = svc != null && svc.current() != null && svc.current().uri.equals(t.uri);
            TextView a = (TextView) row.getChildAt(0);
            a.setText(t.title);
            a.setTextColor(cur ? ACC2 : TXT);
            a.setTypeface(cur ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            ((TextView) row.getChildAt(1)).setText(t.dur > 0 ? t.artist + " · " + time(t.dur) : t.artist);
            return row;
        }
    }

    private View buildPlayerBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadii(new float[]{dp(20), dp(20), dp(20), dp(20), 0, 0, 0, 0});
        bar.setBackground(g);
        bar.setPadding(dp(16), dp(12), dp(16), dp(10));

        npTitle = text("Şarkı seçin", 15, TXT, true);
        npTitle.setSingleLine(true);
        npTitle.setEllipsize(TextUtils.TruncateAt.END);
        npArtist = text("", 12, SUB, false);
        npArtist.setSingleLine(true);
        View.OnClickListener openVideo = new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (svc != null && svc.current() != null && svc.current().video)
                    startActivity(new Intent(MainActivity.this, VideoActivity.class));
            }
        };
        npTitle.setOnClickListener(openVideo);
        npArtist.setOnClickListener(openVideo);
        bar.addView(npTitle);
        bar.addView(npArtist);

        seek = new SeekBar(this);
        tint(seek);
        seek.setPadding(dp(8), dp(10), dp(8), dp(4));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user) tCur.setText(time(p));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                if (svc != null) svc.seek(s.getProgress());
            }
        });
        bar.addView(seek);

        LinearLayout times = new LinearLayout(this);
        tCur = text("0:00", 11, SUB, false);
        tDur = text("0:00", 11, SUB, false);
        tDur.setGravity(Gravity.END);
        times.addView(tCur, lp(0, WC, 1));
        times.addView(tDur, lp(0, WC, 1));
        bar.addView(times);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        bShuffle = ctl("🔀", 18);
        TextView bPrev = ctl("⏮", 24);
        bPlay = ctl("▶", 30);
        TextView bNext = ctl("⏭", 24);
        bRepeat = ctl("🔁", 18);
        row.addView(bShuffle, lp(0, WC, 1));
        row.addView(bPrev, lp(0, WC, 1));
        row.addView(bPlay, lp(0, WC, 1));
        row.addView(bNext, lp(0, WC, 1));
        row.addView(bRepeat, lp(0, WC, 1));
        bar.addView(row);

        bShuffle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (svc == null) return;
                svc.shuffle = !svc.shuffle;
                svc.save();
                toast(svc.shuffle ? "Karışık çalma açık" : "Karışık çalma kapalı");
                refresh();
            }
        });
        bRepeat.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (svc == null) return;
                svc.repeat = (svc.repeat + 1) % 3;
                svc.save();
                toast(svc.repeat == 0 ? "Tekrar kapalı" : svc.repeat == 1 ? "Tüm listeyi tekrarla" : "Bu şarkıyı tekrarla");
                refresh();
            }
        });
        bPrev.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (svc != null) svc.prev(); }
        });
        bNext.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (svc != null) svc.next(false); }
        });
        bPlay.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (svc == null) return;
                startService(new Intent(MainActivity.this, PlayerService.class));
                if (svc.current() == null && !shown.isEmpty()) svc.setList(shown, 0);
                else svc.toggle();
            }
        });
        return bar;
    }

    private TextView ctl(String s, float sp) {
        TextView t = text(s, sp, TXT, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(6), 0, dp(6));
        return t;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private Uri lastUri;

    private void refresh() {
        if (svc == null || npTitle == null) return;
        PlayerService.Track t = svc.current();
        npTitle.setText(t == null ? "Şarkı seçin" : t.title);
        npArtist.setText(t == null ? "" : t.video ? "▶ Videoyu açmak için dokunun" : t.artist);
        bPlay.setText(svc.isPlaying() ? "⏸" : "▶");
        bShuffle.setAlpha(svc.shuffle ? 1f : 0.35f);
        bRepeat.setAlpha(svc.repeat == 0 ? 0.35f : 1f);
        bRepeat.setText(svc.repeat == 2 ? "🔂" : "🔁");
        int d = svc.duration();
        if (!dragging) {
            seek.setMax(Math.max(d, 1));
            seek.setProgress(svc.position());
            tCur.setText(time(svc.position()));
        }
        tDur.setText(time(d));
        Uri u = t == null ? null : t.uri;
        if ((u == null ? lastUri != null : !u.equals(lastUri)) && adapter != null) {
            lastUri = u;
            adapter.notifyDataSetChanged();
            if (vAdapter != null) vAdapter.notifyDataSetChanged();
        }
    }

    private static String time(long ms) {
        long s = ms / 1000;
        if (s >= 3600) return String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private void tint(SeekBar s) {
        s.setProgressTintList(ColorStateList.valueOf(ACC));
        s.setThumbTintList(ColorStateList.valueOf(ACC2));
        s.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF4A4F63));
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
            t.setBackground(round(i == selected ? ACC : 0xFF2A2D3A, 20));
            LinearLayout.LayoutParams p = lp(WC, WC, 0);
            p.rightMargin = dp(8);
            row.addView(t, p);
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    for (int j = 0; j < row.getChildCount(); j++) {
                        row.getChildAt(j).setBackground(round(j == k ? ACC : 0xFF2A2D3A, 20));
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
                    showTab(2);
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
                showTab(3);
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

        long left = svc.sleepAt - System.currentTimeMillis();
        LinearLayout ct = card(p, "Uyku zamanlayıcı", svc.sleepAt > 0 && left > 0
                ? "Müzik yaklaşık " + (left / 60000 + 1) + " dk sonra duracak."
                : "Seçilen süre sonunda müzik kendiliğinden durur.");
        final int[] mins = {0, 15, 30, 45, 60, 90};
        chips(ct, new String[]{"Kapalı", "15 dk", "30 dk", "45 dk", "60 dk", "90 dk"},
                svc.sleepAt > 0 ? -1 : 0, new IntCb() {
                    @Override public void on(int k) {
                        svc.setSleep(mins[k]);
                        toast(mins[k] == 0 ? "Zamanlayıcı kapatıldı" : mins[k] + " dk sonra duracak");
                    }
                });

        TextView reset = text("Tüm ayarları sıfırla", 14, TXT, true);
        reset.setGravity(Gravity.CENTER);
        reset.setBackground(round(0xFF2A2D3A, 14));
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
                showTab(3);
            }
        });
        return sv;
    }
}
