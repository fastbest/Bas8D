package uz.fastbest.bas8d;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
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
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.TreeMap;
import java.util.function.Supplier;

import uz.fastbest.bas8d.PlayerService.Track;

import static uz.fastbest.bas8d.Ui.BAR;
import static uz.fastbest.bas8d.Ui.BG;
import static uz.fastbest.bas8d.Ui.ICON;
import static uz.fastbest.bas8d.Ui.MP;
import static uz.fastbest.bas8d.Ui.ORANGE;
import static uz.fastbest.bas8d.Ui.SUB;
import static uz.fastbest.bas8d.Ui.TXT;
import static uz.fastbest.bas8d.Ui.WC;

public class MainActivity extends Activity {

    // ---------- veri modeli ----------

    static class Row {
        static final int HEADER = 0, GROUP = 1, TRACK = 2, ACTION = 3;
        int type;
        String title = "", sub = "";
        int icon;
        Track t;
        List<Track> group;
        Runnable click;
        Runnable more;
    }

    static class Level {
        String title;
        Supplier<List<Row>> build;
        Level(String title, Supplier<List<Row>> build) { this.title = title; this.build = build; }
    }

    private static final String[] SECTION_NAMES = {L.t("Görüntü"), L.t("Ses"), L.t("Göz at"), L.t("Listeler"), L.t("Diğerleri")};
    private static final int[] SECTION_ICONS = {R.drawable.ic_movie, R.drawable.ic_note, R.drawable.ic_folder,
            R.drawable.ic_queue, R.drawable.ic_more_horiz};
    private static final String[][] SUBTABS = {
            {L.t("KLASÖRLER"), L.t("TÜM VİDEOLAR")},
            {L.t("KLASÖRLER"), L.t("KAYITLAR"), L.t("SANATÇILAR"), L.t("ALBÜMLER")},
            {}, {}, {}};

    private PlayerService svc;
    private final Handler h = new Handler(Looper.getMainLooper());
    private SharedPreferences sp;

    private int section = 1;
    private final int[] subTab = {0, 0, 0, 0, 0};
    private final ArrayList<Level> stack = new ArrayList<>();
    private String query;
    private List<Row> rows = new ArrayList<>();
    private boolean loading = true;

    // görünümler
    private LinearLayout topBar;
    private LiquidTabs tabs;
    private CurvedNav nav;
    private ListView listView;
    private androidx.recyclerview.widget.RecyclerView grid;
    private GridAdapter gridAdapter;
    private boolean gridMode;
    private RowAdapter adapter;
    private TextView emptyView;
    private ImageView fab;
    private LinearLayout mini;
    private TextView miniTitle, miniSub, miniTime;
    private ImageView miniPlay;
    private ProgressBar miniProg;
    private String lastUri = "";

    private final Runnable uiListener = () -> h.post(this::refreshMini);

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            svc = ((PlayerService.LocalBinder) b).get();
            svc.listeners.add(uiListener);
            refreshMini();
        }
        @Override public void onServiceDisconnected(ComponentName n) { svc = null; }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refreshMini();
            h.postDelayed(this, 500);
        }
    };

    private int builtStamp;

    private int dp(float v) { return Ui.dp(this, v); }

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        sp = Ui.prefs(this);
        section = sp.getInt("section", 1);
        Ui.apply(this);
        builtStamp = Ui.stamp;
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        buildUi();
        Intent i = new Intent(this, PlayerService.class);
        startService(i);
        bindService(i, conn, Context.BIND_AUTO_CREATE);
        askPermission();
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        Ui.load(this);
        if (Ui.stamp != builtStamp) { recreate(); return; }
        h.removeCallbacks(tick);
        h.post(tick);
        if (section == 3 || section == 4) render();
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

    @Override public void onBackPressed() {
        if (query != null) { closeSearch(); return; }
        if (!stack.isEmpty()) {
            stack.remove(stack.size() - 1);
            render(-1);
            return;
        }
        super.onBackPressed();
    }

    // ---------- izin ve kitaplık ----------

    private String audioPerm() {
        return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private String videoPerm() {
        return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_VIDEO : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private boolean granted(String p) {
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    private void askPermission() {
        if (granted(audioPerm()) && granted(videoPerm())) { loadLibrary(); return; }
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{audioPerm(), videoPerm(), Manifest.permission.POST_NOTIFICATIONS}, 1);
        } else {
            requestPermissions(new String[]{audioPerm()}, 1);
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        loadLibrary();
    }

    private void loadLibrary() {
        loading = true;
        final boolean a = granted(audioPerm()), v = granted(videoPerm());
        new Thread(() -> {
            final List<Track> au = a ? Lib.query(this, false) : new ArrayList<>();
            final List<Track> vi = v ? Lib.query(this, true) : new ArrayList<>();
            h.post(() -> {
                Lib.audio.clear();
                Lib.audio.addAll(au);
                Lib.video.clear();
                Lib.video.addAll(vi);
                Lib.loaded = true;
                loading = false;
                render();
            });
        }).start();
    }

    // ---------- arayüz iskeleti ----------

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Ui.pageBg());

        topBar = new LinearLayout(this);
        topBar.setBackgroundColor(BAR);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setMinimumHeight(dp(64));
        root.addView(topBar, new LinearLayout.LayoutParams(MP, WC));

        tabs = new LiquidTabs(this);
        tabs.setOnSelect(k -> {
            int dir = k > subTab[section] ? 1 : -1;
            subTab[section] = k;
            render(dir);
            listView.setSelection(0);
        });
        root.addView(tabs, new LinearLayout.LayoutParams(MP, WC));

        FrameLayout content = new FrameLayout(this);
        listView = new ListView(this);
        listView.setDivider(null);
        listView.setSelector(new android.graphics.drawable.ColorDrawable(0));
        listView.setFastScrollEnabled(true);
        listView.setClipToPadding(false);
        listView.setPadding(0, dp(4), 0, dp(120));
        adapter = new RowAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((p, v, pos, id) -> {
            Row r = rows.get(pos);
            if (r.click != null) r.click.run();
        });
        listView.setOnItemLongClickListener((p, v, pos, id) -> {
            Row r = rows.get(pos);
            if (r.more != null) { r.more.run(); return true; }
            return false;
        });
        content.addView(listView);

        grid = new androidx.recyclerview.widget.RecyclerView(this);
        androidx.recyclerview.widget.StaggeredGridLayoutManager lm =
                new androidx.recyclerview.widget.StaggeredGridLayoutManager(2, androidx.recyclerview.widget.StaggeredGridLayoutManager.VERTICAL);
        lm.setGapStrategy(androidx.recyclerview.widget.StaggeredGridLayoutManager.GAP_HANDLING_MOVE_ITEMS_BETWEEN_SPANS);
        grid.setLayoutManager(lm);
        grid.setItemAnimator(null);
        grid.setClipToPadding(false);
        grid.setPadding(dp(8), dp(8), dp(8), dp(130));
        gridAdapter = new GridAdapter();
        grid.setAdapter(gridAdapter);
        grid.setVisibility(View.GONE);
        content.addView(grid);

        emptyView = Ui.text(this, "", 15, SUB, false);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(dp(32), dp(32), dp(32), dp(32));
        emptyView.setOnClickListener(v -> { if (!granted(audioPerm()) || !granted(videoPerm())) askPermission(); });
        content.addView(emptyView, new FrameLayout.LayoutParams(MP, MP));

        fab = new ImageView(this);
        fab.setImageDrawable(Ui.icon(this, R.drawable.ic_shuffle, 0xFFFFFFFF));
        fab.setPadding(dp(17), dp(17), dp(17), dp(17));
        fab.setBackground(Ui.circle(ORANGE, 0, 0));
        fab.setElevation(dp(6));
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(dp(60), dp(60), Gravity.BOTTOM | Gravity.END);
        fp.setMargins(0, 0, dp(18), dp(18) + dp(26));
        content.addView(fab, fp);
        fab.setOnClickListener(v -> shuffleAll());
        root.addView(content, new LinearLayout.LayoutParams(MP, 0, 1));

        root.addView(buildMini());

        nav = new CurvedNav(this, SECTION_ICONS, SECTION_NAMES, section);
        nav.setOnSelect(k -> {
            if (k == section) {
                if (!stack.isEmpty() || query != null) { stack.clear(); query = null; render(-1); }
                listView.smoothScrollToPosition(0);
                grid.smoothScrollToPosition(0);
                return;
            }
            int dir = k > section ? 1 : -1;
            section = k;
            stack.clear();
            query = null;
            sp.edit().putInt("section", k).apply();
            render(dir);
            listView.setSelection(0);
        });
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(MP, WC);
        np.topMargin = -(int) nav.riseHeight();
        root.addView(nav, np);
        setContentView(root);
    }

    private View buildMini() {
        mini = new LinearLayout(this);
        mini.setOrientation(LinearLayout.VERTICAL);
        mini.setBackgroundColor(BAR);
        miniProg = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        miniProg.setProgressTintList(android.content.res.ColorStateList.valueOf(ORANGE));
        miniProg.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Ui.TRACK));
        miniProg.setMax(1000);
        mini.addView(miniProg, new LinearLayout.LayoutParams(MP, dp(3)));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(8), dp(12), dp(8) + dp(26));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        miniTitle = Ui.text(this, "", 17, TXT, false);
        miniTitle.setSingleLine(true);
        miniTitle.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        miniTitle.setMarqueeRepeatLimit(-1);
        miniTitle.setSelected(true);
        miniSub = Ui.text(this, "", 14, SUB, false);
        miniSub.setSingleLine(true);
        miniSub.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(miniTitle);
        col.addView(miniSub);
        row.addView(col, new LinearLayout.LayoutParams(0, WC, 1));
        miniTime = Ui.text(this, "", 15, TXT, false);
        miniTime.setPadding(dp(10), 0, dp(14), 0);
        row.addView(miniTime);
        miniPlay = new ImageView(this);
        miniPlay.setImageDrawable(Ui.icon(this, R.drawable.ic_pause, TXT));
        miniPlay.setPadding(dp(11), dp(11), dp(11), dp(11));
        miniPlay.setBackground(Ui.circle(0, TXT, dp(2)));
        miniPlay.setOnClickListener(v -> { if (svc != null) svc.toggle(); });
        row.addView(miniPlay, new LinearLayout.LayoutParams(dp(46), dp(46)));
        mini.addView(row);
        mini.setOnClickListener(v -> openPlayer());
        mini.setVisibility(View.GONE);
        return mini;
    }

    private void openPlayer() {
        if (svc == null || svc.current() == null) return;
        go(new Intent(this, svc.current().video ? VideoActivity.class : AudioActivity.class), true);
    }

    private void refreshMini() {
        if (svc == null || mini == null) return;
        Track t = svc.current();
        if (t == null) { mini.setVisibility(View.GONE); return; }
        mini.setVisibility(View.VISIBLE);
        if (!t.title.equals(miniTitle.getText().toString())) miniTitle.setText(t.title);
        miniSub.setText(t.video ? Lib.folderName(t.folder) : t.artist);
        int d = svc.duration(), p = svc.position();
        miniTime.setText(Ui.time(p));
        miniProg.setProgress(d > 0 ? (int) (p * 1000L / d) : 0);
        miniPlay.setImageDrawable(Ui.icon(this, svc.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play, TXT));
        String u = t.uri.toString();
        if (!u.equals(lastUri)) {
            lastUri = u;
            adapter.notifyDataSetChanged();
            if (gridMode) gridAdapter.notifyDataSetChanged();
        }
    }

    // ---------- üst çubuk ----------

    private void buildTopBar() {
        topBar.removeAllViews();
        if (query != null) {
            topBar.setPadding(dp(4), 0, dp(4), 0);
            topBar.addView(Ui.btn(this, R.drawable.ic_back, TXT, 24, 12, v -> closeSearch()));
            final EditText e = new EditText(this);
            e.setHint(L.t("Kitaplıkta ara"));
            e.setHintTextColor(SUB);
            e.setTextColor(TXT);
            e.setTextSize(18);
            e.setSingleLine(true);
            e.setBackground(null);
            e.setText(query);
            e.setSelection(query.length());
            e.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable s) {
                    query = s.toString();
                    rows = buildRows();
                    adapter.notifyDataSetChanged();
                    updateEmpty();
                }
            });
            topBar.addView(e, new LinearLayout.LayoutParams(0, WC, 1));
            topBar.addView(Ui.btn(this, R.drawable.ic_close, TXT, 24, 12, v -> {
                if (e.getText().length() > 0) e.setText(""); else closeSearch();
            }));
            e.requestFocus();
            h.postDelayed(() -> {
                InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (im != null) im.showSoftInput(e, 0);
            }, 150);
            return;
        }
        if (stack.isEmpty()) {
            topBar.setPadding(dp(16), 0, dp(4), 0);
            ImageView logo = new ImageView(this);
            logo.setImageResource(R.drawable.logo);
            topBar.addView(logo, new LinearLayout.LayoutParams(dp(40), dp(40)));
            TextView t = Ui.text(this, "Myplayer", 26, TXT, true);
            t.setPadding(dp(16), 0, 0, 0);
            topBar.addView(t, new LinearLayout.LayoutParams(0, WC, 1));
        } else {
            topBar.setPadding(dp(4), 0, dp(4), 0);
            topBar.addView(Ui.btn(this, R.drawable.ic_back, TXT, 24, 12, v -> onBackPressed()));
            TextView t = Ui.text(this, stack.get(stack.size() - 1).title, 20, TXT, true);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setPadding(dp(8), 0, 0, 0);
            topBar.addView(t, new LinearLayout.LayoutParams(0, WC, 1));
        }
        topBar.addView(Ui.btn(this, R.drawable.ic_search, TXT, 28, 12, v -> {
            query = "";
            render();
        }));
        topBar.addView(Ui.btn(this, R.drawable.ic_history, TXT, 28, 12, v -> openHistory()));
        topBar.addView(Ui.btn(this, R.drawable.ic_more_vert, TXT, 28, 10, this::topMenu));
    }

    private void closeSearch() {
        query = null;
        InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(topBar.getWindowToken(), 0);
        render();
    }

    private void topMenu(View anchor) {
        int sort = sp.getInt("sort", 0);
        String[] labels = {sort == 0 ? L.t("✓ Ada göre sırala") : L.t("Ada göre sırala"),
                sort == 1 ? L.t("✓ Tarihe göre sırala") : L.t("Tarihe göre sırala"),
                L.t("Kitaplığı yenile"), L.t("Dengeleyici"), L.t("Denetim ayarları"), L.t("Uyku zamanlayıcı"), L.t("Görünüm")};
        int[] icons = {R.drawable.ic_sort, R.drawable.ic_history, R.drawable.ic_refresh, R.drawable.ic_tune,
                R.drawable.ic_settings, R.drawable.ic_timer, R.drawable.ic_aspect};
        Ui.menu(anchor, labels, icons, k -> {
            if (k == 0 || k == 1) { sp.edit().putInt("sort", k).apply(); render(); }
            else if (k == 2) { loadLibrary(); Ui.toast(this, L.t("Kitaplık yenileniyor…")); }
            else if (k == 3) go(new Intent(this, EffectsActivity.class), false);
            else if (k == 4) Ui.controlSettings(this, false);
            else if (k == 5 && svc != null) Ui.sleepDialog(this, svc);
            else if (k == 6) go(new Intent(this, AppearanceActivity.class), false);
        });
    }

    // ---------- sekmeler ----------

    private void buildTabs() {
        String[] names = SUBTABS[section];
        boolean show = stack.isEmpty() && query == null && names.length > 0;
        boolean was = tabs.getVisibility() == View.VISIBLE;
        tabs.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        tabs.set(names, subTab[section], true);
        if (!was) {
            tabs.setAlpha(0f);
            tabs.setTranslationY(-dp(12));
            tabs.animate().alpha(1f).translationY(0).setDuration(170)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        }
    }

    private android.graphics.drawable.Drawable underline() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BAR);
        GradientDrawable line = new GradientDrawable();
        line.setColor(ORANGE);
        line.setCornerRadius(dp(2));
        LayerDrawable ld = new LayerDrawable(new android.graphics.drawable.Drawable[]{bg, line});
        ld.setLayerGravity(1, Gravity.BOTTOM | Gravity.FILL_HORIZONTAL);
        ld.setLayerHeight(1, dp(4));
        return ld;
    }

    // ---------- içerik ----------

    private void render() { render(0); }

    /** dir: 0 animasyonsuz, 1 sağdan gelir (ileri), -1 soldan gelir (geri). */
    private void render(int dir) {
        buildTopBar();
        buildTabs();
        if (nav.getSelected() != section) nav.select(section, true);
        rows = buildRows();
        gridMode = query == null && stack.isEmpty() && section == 0 && subTab[0] == 1;
        listView.setVisibility(gridMode ? View.GONE : View.VISIBLE);
        grid.setVisibility(gridMode ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
        gridAdapter.notifyDataSetChanged();
        if (gridMode && dir != 0) grid.scrollToPosition(0);
        updateEmpty();
        animateContent(dir);
    }

    private void animateContent(int dir) {
        if (dir == 0) return;
        android.view.animation.Interpolator in = new android.view.animation.DecelerateInterpolator(2.2f);
        for (View v : new View[]{gridMode ? grid : listView, emptyView}) {
            v.animate().cancel();
            v.setTranslationX(dir * dp(80));
            v.setAlpha(0f);
            v.animate().translationX(0).alpha(1f).setDuration(210).setInterpolator(in).start();
        }
        if (fab.getVisibility() == View.VISIBLE) {
            fab.animate().cancel();
            fab.setScaleX(0f);
            fab.setScaleY(0f);
            fab.setRotation(-120f);
            fab.animate().scaleX(1f).scaleY(1f).rotation(0f).setStartDelay(50).setDuration(260)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(2f)).start();
        }
    }

    void go(Intent i, boolean player) {
        startActivity(i);
        if (player) overridePendingTransition(R.anim.up_in, R.anim.hold);
        else overridePendingTransition(R.anim.right_in, R.anim.left_out);
    }

    private void updateEmpty() {
        boolean empty = rows.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            if (loading && query == null) emptyView.setText(L.t("Yükleniyor…"));
            else if (query != null) emptyView.setText(query.trim().isEmpty() ? L.t("Aramak için yazın") : L.t("Sonuç bulunamadı"));
            else if (!granted(audioPerm()) || !granted(videoPerm()))
                emptyView.setText(L.t("Müzik ve videoları göstermek için izin gerekli.\nİzin vermek için dokunun."));
            else if (section == 3 && stack.isEmpty())
                emptyView.setText(L.t("Henüz liste yok.\nOynatıcı menüsünden \"Listeyi kaydet\" veya bir kaydın menüsünden \"Listeye ekle\" ile liste oluşturun."));
            else emptyView.setText(L.t("Burada hiç kayıt yok"));
        }
        int tracks = 0;
        for (Row r : rows) if (r.type == Row.TRACK) tracks++;
        fab.setVisibility(tracks > 1 && query == null ? View.VISIBLE : View.GONE);
    }

    private List<Row> buildRows() {
        if (query != null) return trackRows(Lib.search(query), false, null);
        if (!stack.isEmpty()) return stack.get(stack.size() - 1).build.get();
        switch (section) {
            case 0:
                if (subTab[0] == 0) return groupRows(Lib.group(Lib.video, Lib.BY_FOLDER), R.drawable.ic_folder, L.t("video"));
                return trackRows(sorted(Lib.video), false, null);
            case 1:
                if (subTab[1] == 0) return groupRows(Lib.group(Lib.audio, Lib.BY_FOLDER), R.drawable.ic_folder, L.t("kayıt"));
                if (subTab[1] == 1) return trackRows(sorted(Lib.audio), sp.getInt("sort", 0) == 0, null);
                if (subTab[1] == 2) return groupRows(Lib.group(Lib.audio, Lib.BY_ARTIST), R.drawable.ic_person, L.t("kayıt"));
                return groupRows(Lib.group(Lib.audio, Lib.BY_ALBUM), R.drawable.ic_album, L.t("kayıt"));
            case 2:
                return browseRoots();
            case 3:
                return playlistRows();
            default:
                return moreRows();
        }
    }

    private List<Track> sorted(List<Track> src) {
        List<Track> l = new ArrayList<>(src);
        if (sp.getInt("sort", 0) == 0) Lib.sortByName(l); else Lib.sortByDate(l);
        return l;
    }

    private List<Row> trackRows(List<Track> list, boolean headers, String playlistName) {
        List<Row> out = new ArrayList<>();
        String last = null;
        for (int i = 0; i < list.size(); i++) {
            final Track t = list.get(i);
            final int idx = i;
            if (headers) {
                String c = t.title.isEmpty() ? "#" : t.title.substring(0, 1).toUpperCase(new Locale("tr"));
                if (!Character.isLetter(c.charAt(0))) c = "#";
                if (!c.equals(last)) {
                    Row hr = new Row();
                    hr.type = Row.HEADER;
                    hr.title = c;
                    out.add(hr);
                    last = c;
                }
            }
            Row r = new Row();
            r.type = Row.TRACK;
            r.t = t;
            r.title = t.title;
            if (t.video) r.sub = (t.dur > 0 ? Ui.time(t.dur) : "") + (t.w > 0 ? " · " + t.w + "×" + t.h : "");
            else r.sub = (t.dur > 0 ? Ui.time(t.dur) + " · " : "") + t.artist;
            r.click = () -> play(list, idx);
            r.more = () -> trackMenu(t, list, idx, playlistName);
            out.add(r);
        }
        return out;
    }

    private List<Row> groupRows(List<Lib.Group> groups, int icon, String unit) {
        List<Row> out = new ArrayList<>();
        for (Lib.Group g : groups) {
            Row r = new Row();
            r.type = Row.GROUP;
            r.icon = icon;
            r.title = g.name;
            r.sub = g.items.size() + " " + unit;
            r.group = g.items;
            final String title = g.name;
            final List<Track> items = g.items;
            r.click = () -> push(title, () -> trackRows(sorted(items), false, null));
            r.more = () -> groupMenu(items);
            out.add(r);
        }
        return out;
    }

    private void push(String title, Supplier<List<Row>> build) {
        stack.add(new Level(title, build));
        render(1);
        listView.setSelection(0);
    }

    // Göz at
    private List<Row> browseRoots() {
        TreeMap<String, Integer> roots = new TreeMap<>();
        for (Track t : Lib.all()) {
            String r = Lib.storageRoot(t.path);
            Integer n = roots.get(r);
            roots.put(r, n == null ? 1 : n + 1);
        }
        List<Row> out = new ArrayList<>();
        for (String r : roots.keySet()) {
            Row row = new Row();
            row.type = Row.GROUP;
            row.icon = R.drawable.ic_storage;
            row.title = Lib.rootName(r);
            row.sub = roots.get(r) + L.t(" medya dosyası");
            final String path = r;
            final String title = row.title;
            row.click = () -> push(title, () -> browse(path));
            out.add(row);
        }
        return out;
    }

    private List<Row> browse(String dir) {
        String pre = dir.endsWith("/") ? dir : dir + "/";
        TreeMap<String, List<Track>> sub = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        List<Track> files = new ArrayList<>();
        for (Track t : Lib.all()) {
            if (!t.path.startsWith(pre)) continue;
            String rest = t.path.substring(pre.length());
            int s = rest.indexOf('/');
            if (s < 0) files.add(t);
            else {
                String name = rest.substring(0, s);
                List<Track> l = sub.get(name);
                if (l == null) { l = new ArrayList<>(); sub.put(name, l); }
                l.add(t);
            }
        }
        List<Row> out = new ArrayList<>();
        for (String name : sub.keySet()) {
            Row r = new Row();
            r.type = Row.GROUP;
            r.icon = R.drawable.ic_folder;
            r.title = name;
            r.sub = sub.get(name).size() + L.t(" medya dosyası");
            final String path = pre + name;
            final List<Track> items = sub.get(name);
            r.click = () -> push(name, () -> browse(path));
            r.more = () -> groupMenu(items);
            out.add(r);
        }
        Lib.sortByName(files);
        out.addAll(trackRows(files, false, null));
        return out;
    }

    // Listeler
    private List<Row> playlistRows() {
        List<Row> out = new ArrayList<>();
        for (final String n : Lib.playlistNames(this)) {
            final List<Track> l = Lib.playlist(this, n);
            Row r = new Row();
            r.type = Row.GROUP;
            r.icon = R.drawable.ic_queue;
            r.title = n;
            r.sub = l.size() + L.t(" kayıt");
            r.group = l;
            r.click = () -> push(n, () -> trackRows(Lib.playlist(this, n), false, n));
            r.more = () -> {
                View anchor = listView;
                Ui.menu(anchor, new String[]{L.t("Çal"), L.t("Karışık çal"), L.t("Sıraya ekle"), L.t("Adını değiştir"), L.t("Sil")},
                        new int[]{R.drawable.ic_play, R.drawable.ic_shuffle, R.drawable.ic_queue_play,
                                R.drawable.ic_tune, R.drawable.ic_delete}, k -> {
                            List<Track> cur = Lib.playlist(this, n);
                            if (k == 0) play(cur, 0);
                            else if (k == 1) shuffle(cur);
                            else if (k == 2) enqueue(cur);
                            else if (k == 3) renamePlaylist(n);
                            else {
                                Ui.dlg(this).setTitle(L.t("Silinsin mi?")).setMessage("\"" + n + L.t("\" listesi silinecek. Dosyalar silinmez."))
                                        .setPositiveButton(L.t("Sil"), (d, w) -> { Lib.deletePlaylist(this, n); render(); })
                                        .setNegativeButton(L.t("İptal"), null).show();
                            }
                        });
            };
            out.add(r);
        }
        return out;
    }

    private void renamePlaylist(String old) {
        final EditText e = new EditText(this);
        e.setText(old);
        e.setSingleLine(true);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(e, new LinearLayout.LayoutParams(MP, WC));
        Ui.dlg(this).setTitle(L.t("Adını değiştir")).setView(box).setPositiveButton(L.t("Kaydet"), (d, w) -> {
            String n = e.getText().toString().trim();
            if (n.isEmpty() || n.equals(old)) return;
            List<Track> l = Lib.playlist(this, old);
            Lib.deletePlaylist(this, old);
            Lib.savePlaylist(this, n, l);
            render();
        }).setNegativeButton(L.t("İptal"), null).show();
    }

    // Diğerleri
    private List<Row> moreRows() {
        List<Row> out = new ArrayList<>();
        out.add(action(L.t("Görünüm"), L.t("Tema ve renk: ") + Ui.THEMES[Math.max(0, Math.min(2, sp.getInt("tema", 2)))]
                        + (Ui.GLASS ? L.t(" · Cam · ") : " · ") + Ui.SWATCHES[Math.max(0, Math.min(Ui.SWATCHES.length - 1, sp.getInt("renk", 0)))][0],
                R.drawable.ic_aspect, () -> go(new Intent(this, AppearanceActivity.class), false)));
        out.add(action(L.t("Dil") + " / Language", L.name(L.code), R.drawable.ic_language, () -> Ui.languageDialog(this)));
        out.add(action(L.t("Geçmiş"), L.t("Son oynatılan kayıtlar"), R.drawable.ic_history, this::openHistory));
        out.add(action(L.t("Dengeleyici"), L.t("Bas, tiz ve ekolayzır"), R.drawable.ic_tune,
                () -> go(new Intent(this, EffectsActivity.class).putExtra("tab", 0), false)));
        out.add(action(L.t("8D ses ve efektler"), L.t("8D, ses yükseltme, surround, yankı, perde"), R.drawable.ic_headset,
                () -> go(new Intent(this, EffectsActivity.class).putExtra("tab", 1), false)));
        out.add(action(L.t("Uyku zamanlayıcı"), L.t("Belirlenen süre sonunda oynatmayı durdur"), R.drawable.ic_timer,
                () -> { if (svc != null) Ui.sleepDialog(this, svc); }));
        out.add(action(L.t("Denetim ayarları"), L.t("Atlama süresi, jestler, kulaklık"), R.drawable.ic_settings,
                () -> Ui.controlSettings(this, false)));
        out.add(action(L.t("Kitaplığı yenile"), Lib.audio.size() + L.t(" ses, ") + Lib.video.size() + L.t(" video"), R.drawable.ic_refresh,
                () -> { loadLibrary(); Ui.toast(this, L.t("Kitaplık yenileniyor…")); }));
        out.add(action(L.t("Hakkında"), "Myplayer", R.drawable.ic_info, () -> Ui.tips(this, "Myplayer",
                L.t("Kulaklık için müzik ve video oynatıcı.\n\nBas, tiz, ekolayzır, 8D ses, ses yükseltme, surround, yankı, hız ve perde ayarları hem müzikte hem videoda çalışır."))));
        return out;
    }

    private Row action(String title, String sub, int icon, Runnable click) {
        Row r = new Row();
        r.type = Row.ACTION;
        r.title = title;
        r.sub = sub;
        r.icon = icon;
        r.click = click;
        return r;
    }

    private void openHistory() {
        query = null;
        push(L.t("Geçmiş"), () -> {
            List<Track> hist = Lib.history(this);
            List<Row> out = new ArrayList<>();
            if (!hist.isEmpty()) out.add(action(L.t("Geçmişi temizle"), hist.size() + L.t(" kayıt"), R.drawable.ic_delete, () ->
                    Ui.dlg(this).setTitle(L.t("Geçmiş temizlensin mi?"))
                            .setPositiveButton(L.t("Temizle"), (d, w) -> { Lib.clearHistory(this); render(); })
                            .setNegativeButton(L.t("İptal"), null).show()));
            out.addAll(trackRows(hist, false, null));
            return out;
        });
    }

    // ---------- oynatma ----------

    private void play(List<Track> l, int i) {
        if (svc == null || l.isEmpty()) return;
        startService(new Intent(this, PlayerService.class));
        svc.setList(l, i);
        if (l.get(i).video) go(new Intent(this, VideoActivity.class), true);
    }

    private void shuffle(List<Track> l) {
        if (svc == null || l.isEmpty()) return;
        svc.shuffle = true;
        svc.save();
        play(l, new Random().nextInt(l.size()));
    }

    private void enqueue(List<Track> l) {
        if (svc == null) return;
        startService(new Intent(this, PlayerService.class));
        for (Track t : l) svc.addToQueue(t);
        Ui.toast(this, l.size() + L.t(" kayıt sıraya eklendi"));
    }

    private void shuffleAll() {
        List<Track> l = new ArrayList<>();
        for (Row r : rows) if (r.type == Row.TRACK) l.add(r.t);
        shuffle(l);
        Ui.toast(this, L.t("Karışık çalınıyor"));
    }

    private void trackMenu(Track t, List<Track> list, int idx, String playlistName) {
        List<String> labels = new ArrayList<>();
        List<Integer> icons = new ArrayList<>();
        labels.add("Çal"); icons.add(R.drawable.ic_play);
        labels.add("Sonraki olarak çal"); icons.add(R.drawable.ic_queue_play);
        labels.add("Sıraya ekle"); icons.add(R.drawable.ic_queue);
        labels.add("Listeye ekle"); icons.add(R.drawable.ic_playlist_add);
        if (t.video) { labels.add("Ses olarak oynat"); icons.add(R.drawable.ic_headset); }
        labels.add("Bilgiler"); icons.add(R.drawable.ic_info);
        labels.add("Paylaş"); icons.add(R.drawable.ic_share);
        if (playlistName != null) { labels.add("Listeden kaldır"); icons.add(R.drawable.ic_delete); }
        int[] ic = new int[icons.size()];
        for (int i = 0; i < ic.length; i++) ic[i] = icons.get(i);
        Ui.menu(listView, labels.toArray(new String[0]), ic, k -> {
            String a = labels.get(k);
            switch (a) {
                case "Çal": play(list, idx); break;
                case "Sonraki olarak çal":
                    if (svc != null) { startService(new Intent(this, PlayerService.class)); svc.playNext(t); Ui.toast(this, L.t("Sonraki olarak çalınacak")); }
                    break;
                case "Sıraya ekle": enqueue(Collections.singletonList(t)); break;
                case "Listeye ekle": Ui.addToPlaylistDialog(this, Collections.singletonList(t)); break;
                case "Ses olarak oynat":
                    if (svc != null) {
                        startService(new Intent(this, PlayerService.class));
                        svc.setList(list, idx);
                        go(new Intent(this, AudioActivity.class), true);
                    }
                    break;
                case "Bilgiler": Ui.infoDialog(this, t, svc); break;
                case "Paylaş": Ui.shareFile(this, t); break;
                case "Listeden kaldır":
                    List<Track> l = Lib.playlist(this, playlistName);
                    for (int i = 0; i < l.size(); i++) {
                        if (l.get(i).uri.equals(t.uri)) { l.remove(i); break; }
                    }
                    Lib.savePlaylist(this, playlistName, l);
                    render();
                    break;
            }
        });
    }

    private void groupMenu(List<Track> items) {
        Ui.menu(listView, new String[]{L.t("Çal"), L.t("Karışık çal"), L.t("Sıraya ekle"), L.t("Listeye ekle")},
                new int[]{R.drawable.ic_play, R.drawable.ic_shuffle, R.drawable.ic_queue, R.drawable.ic_playlist_add}, k -> {
                    List<Track> l = sorted(items);
                    if (k == 0) play(l, 0);
                    else if (k == 1) shuffle(l);
                    else if (k == 2) enqueue(l);
                    else Ui.addToPlaylistDialog(this, l);
                });
    }

    // ---------- liste görünümü ----------

    private class RowAdapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int i) { return rows.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public int getViewTypeCount() { return 4; }
        @Override public int getItemViewType(int i) {
            Row r = rows.get(i);
            if (r.type == Row.HEADER) return 0;
            if (r.type == Row.TRACK && r.t.video) return 1;
            if (r.type == Row.TRACK) return 2;
            return 3;
        }
        @Override public boolean isEnabled(int i) { return rows.get(i).type != Row.HEADER; }

        @Override public View getView(int i, View cv, ViewGroup parent) {
            Row r = rows.get(i);
            int vt = getItemViewType(i);
            if (vt == 0) {
                TextView t = cv instanceof TextView ? (TextView) cv : Ui.text(MainActivity.this, "", 22, ORANGE, true);
                t.setPadding(dp(28), dp(14), dp(16), dp(4));
                t.setText(r.title);
                return t;
            }
            LinearLayout row = (LinearLayout) cv;
            if (row == null) row = makeRow(vt);
            ImageView iv = (ImageView) row.getChildAt(0);
            LinearLayout col = (LinearLayout) row.getChildAt(1);
            TextView a = (TextView) col.getChildAt(0);
            TextView b = (TextView) col.getChildAt(1);
            ImageView more = (ImageView) row.getChildAt(2);
            a.setText(r.title);
            b.setText(r.sub);
            b.setVisibility(r.sub.isEmpty() ? View.GONE : View.VISIBLE);
            boolean cur = r.type == Row.TRACK && svc != null && svc.current() != null
                    && svc.current().uri.equals(r.t.uri);
            a.setTextColor(cur ? ORANGE : TXT);
            if (vt == 1) {
                Ui.thumb(iv, r.t);
            } else if (vt == 2) {
                iv.setImageDrawable(Ui.icon(MainActivity.this, cur ? R.drawable.ic_bars : R.drawable.ic_note,
                        cur ? TXT : Ui.DIM));
            } else {
                iv.setImageDrawable(Ui.icon(MainActivity.this, r.icon == 0 ? R.drawable.ic_folder : r.icon,
                        r.type == Row.ACTION ? ORANGE : Ui.DIM));
            }
            more.setVisibility(r.more != null ? View.VISIBLE : View.INVISIBLE);
            final Runnable m = r.more;
            more.setOnClickListener(v -> { if (m != null) m.run(); });
            return row;
        }

        private LinearLayout makeRow(int vt) {
            Context c = MainActivity.this;
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(vt == 1 ? 16 : 20), dp(10), dp(4), dp(10));
            row.setMinimumHeight(dp(76));
            ImageView iv = new ImageView(c);
            if (vt == 1) {
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setBackground(Ui.round(Ui.ROW, dp(6)));
                iv.setClipToOutline(true);
                row.addView(iv, new LinearLayout.LayoutParams(dp(120), dp(68)));
            } else {
                iv.setPadding(dp(4), dp(4), dp(4), dp(4));
                row.addView(iv, new LinearLayout.LayoutParams(dp(52), dp(52)));
            }
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(vt == 1 ? 14 : 22), 0, dp(8), 0);
            TextView a = Ui.text(c, "", 18, TXT, false);
            a.setMaxLines(vt == 1 ? 2 : 1);
            a.setEllipsize(TextUtils.TruncateAt.END);
            TextView b = Ui.text(c, "", 15, SUB, false);
            b.setSingleLine(true);
            b.setEllipsize(TextUtils.TruncateAt.END);
            b.setPadding(0, dp(3), 0, 0);
            col.addView(a);
            col.addView(b);
            row.addView(col, new LinearLayout.LayoutParams(0, WC, 1));
            ImageView more = Ui.img(c, R.drawable.ic_more_vert, TXT, 26, 12);
            Ui.ripple(more, true);
            row.addView(more);
            return row;
        }
    }

    // ---------- Pinterest tarzı video ızgarası ----------

    private class GridHolder extends androidx.recyclerview.widget.RecyclerView.ViewHolder {
        final FrameLayout box;
        final ImageView img, more;
        final TextView title, sub, dur, playing;

        GridHolder(LinearLayout card, FrameLayout box, ImageView img, TextView dur, TextView playing,
                   TextView title, TextView sub, ImageView more) {
            super(card);
            this.box = box; this.img = img; this.dur = dur; this.playing = playing;
            this.title = title; this.sub = sub; this.more = more;
        }
    }

    private class GridAdapter extends androidx.recyclerview.widget.RecyclerView.Adapter<GridHolder> {
        @Override public int getItemCount() { return gridMode ? rows.size() : 0; }

        @Override public GridHolder onCreateViewHolder(ViewGroup parent, int type) {
            Context c = MainActivity.this;
            LinearLayout card = new LinearLayout(c);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(5), dp(4), dp(5), dp(10));
            androidx.recyclerview.widget.StaggeredGridLayoutManager.LayoutParams clp =
                    new androidx.recyclerview.widget.StaggeredGridLayoutManager.LayoutParams(MP, WC);
            card.setLayoutParams(clp);

            FrameLayout box = new FrameLayout(c);
            box.setBackground(Ui.round(Ui.ROW, dp(18)));
            box.setClipToOutline(true);
            ImageView img = new ImageView(c);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            box.addView(img, new FrameLayout.LayoutParams(MP, MP));

            TextView dur = Ui.text(c, "", 11, 0xFFFFFFFF, true);
            dur.setPadding(dp(7), dp(2), dp(7), dp(2));
            dur.setBackground(Ui.round(0x99000000, dp(10)));
            FrameLayout.LayoutParams dp1 = new FrameLayout.LayoutParams(WC, WC, Gravity.BOTTOM | Gravity.END);
            dp1.setMargins(0, 0, dp(8), dp(8));
            box.addView(dur, dp1);

            TextView playing = Ui.text(c, "▶", 11, 0xFFFFFFFF, true);
            playing.setPadding(dp(8), dp(3), dp(8), dp(3));
            playing.setBackground(Ui.round(ORANGE, dp(10)));
            FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(WC, WC, Gravity.TOP | Gravity.START);
            pp.setMargins(dp(8), dp(8), 0, 0);
            box.addView(playing, pp);
            card.addView(box, new LinearLayout.LayoutParams(MP, dp(160)));

            LinearLayout row = new LinearLayout(c);
            row.setPadding(dp(4), dp(6), 0, 0);
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView title = Ui.text(c, "", 13.5f, TXT, true);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            TextView sub = Ui.text(c, "", 11.5f, SUB, false);
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            sub.setPadding(0, dp(2), 0, 0);
            col.addView(title);
            col.addView(sub);
            row.addView(col, new LinearLayout.LayoutParams(0, WC, 1));
            ImageView more = Ui.img(c, R.drawable.ic_more_vert, Ui.ICON, 18, 4);
            Ui.ripple(more, true);
            row.addView(more);
            card.addView(row);
            return new GridHolder(card, box, img, dur, playing, title, sub, more);
        }

        @Override public void onBindViewHolder(GridHolder h, int pos) {
            final Row r = rows.get(pos);
            Track t = r.t;
            int colW = (getResources().getDisplayMetrics().widthPixels - dp(16)) / 2 - dp(10);
            float ratio = t.w > 0 && t.h > 0 ? t.h / (float) t.w : 0.5625f;
            ratio = Math.max(0.56f, Math.min(1.78f, ratio));
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) h.box.getLayoutParams();
            int hh = Math.round(colW * ratio);
            if (lp.height != hh) { lp.height = hh; h.box.setLayoutParams(lp); }
            Ui.thumb(h.img, t);
            h.dur.setText(Ui.time(t.dur));
            h.dur.setVisibility(t.dur > 0 ? View.VISIBLE : View.GONE);
            boolean cur = svc != null && svc.current() != null && svc.current().uri.equals(t.uri);
            h.playing.setVisibility(cur ? View.VISIBLE : View.GONE);
            h.title.setText(t.title);
            h.title.setTextColor(cur ? ORANGE : TXT);
            h.sub.setText(Lib.folderName(t.folder));
            h.itemView.setOnClickListener(v -> {
                v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(70)
                        .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(110).start()).start();
                if (r.click != null) r.click.run();
            });
            h.itemView.setOnLongClickListener(v -> { if (r.more != null) r.more.run(); return true; });
            h.more.setOnClickListener(v -> { if (r.more != null) r.more.run(); });
        }
    }
}
