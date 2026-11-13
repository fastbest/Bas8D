package uz.fastbest.bas8d;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.media.audiofx.LoudnessEnhancer;
import android.media.audiofx.PresetReverb;
import android.media.audiofx.Virtualizer;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.view.SurfaceHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class PlayerService extends Service implements MediaPlayer.OnPreparedListener,
        MediaPlayer.OnCompletionListener, MediaPlayer.OnErrorListener,
        AudioManager.OnAudioFocusChangeListener {

    public static class Track {
        public Uri uri;
        public String title = "", artist = "", album = "", folder = "", path = "", mime = "";
        public long dur, id, size, date;
        public int w, h;
        public boolean video;
    }

    public class LocalBinder extends Binder {
        PlayerService get() { return PlayerService.this; }
    }

    private final IBinder binder = new LocalBinder();
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();

    public List<Track> list = new ArrayList<>();
    public int index = -1;
    public final List<Runnable> listeners = new ArrayList<>();

    private MediaPlayer mp;
    private boolean prepared, foreground, focusPaused;
    private int errors, sid;
    private AudioManager am;
    private SharedPreferences sp;
    private SurfaceHolder holder;
    public int vw, vh;

    private Equalizer eq;
    private BassBoost bb;
    private Virtualizer vz;
    private LoudnessEnhancer le;
    private PresetReverb rv;

    // ayarlar
    public int bass, treble, boost, surround, reverb, period, balance, speed, pitch, repeat;
    public boolean d8, shuffle;
    public int[] bands = new int[0];
    public long sleepAt;
    public boolean stopAtEnd;
    public int abA = -1, abB = -1;

    private double phase;
    private float pan;

    private final Runnable spin = new Runnable() {
        @Override public void run() {
            if (!d8) return;
            if (isPlaying()) {
                phase += 2 * Math.PI * 0.04 / Math.max(2, period);
                if (phase > 2 * Math.PI) phase -= 2 * Math.PI;
                pan = (float) Math.sin(phase);
                applyVolume();
            }
            h.postDelayed(this, 40);
        }
    };

    private final Runnable abRun = new Runnable() {
        @Override public void run() {
            if (abA < 0 || abB < 0) return;
            if (isPlaying() && position() >= abB) seek(abA);
            h.postDelayed(this, 120);
        }
    };

    private final Runnable sleepRun = () -> { sleepAt = 0; pause(); };

    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (sp.getBoolean("unplug", true)) pause();
        }
    };

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public void onCreate() {
        super.onCreate();
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        sp = getSharedPreferences("ayar", MODE_PRIVATE);
        bass = sp.getInt("bass", 0);
        treble = sp.getInt("treble", 0);
        boost = sp.getInt("boost", 0);
        surround = sp.getInt("surround", 0);
        reverb = sp.getInt("reverb", 0);
        period = sp.getInt("period", 8);
        balance = sp.getInt("balance", 0);
        speed = sp.getInt("speed", 100);
        pitch = sp.getInt("pitch", 100);
        repeat = sp.getInt("repeat", 1);
        d8 = sp.getBoolean("d8", false);
        shuffle = sp.getBoolean("shuffle", false);

        sid = am.generateAudioSessionId();
        mp = new MediaPlayer();
        mp.setOnPreparedListener(this);
        mp.setOnCompletionListener(this);
        mp.setOnErrorListener(this);
        mp.setOnVideoSizeChangedListener((m, w, hh) -> { vw = w; vh = hh; changed(); });
        try { mp.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK); } catch (Exception ignored) {}

        try { eq = new Equalizer(0, sid); eq.setEnabled(true); } catch (Throwable t) { eq = null; }
        try { bb = new BassBoost(0, sid); } catch (Throwable t) { bb = null; }
        try { vz = new Virtualizer(0, sid); } catch (Throwable t) { vz = null; }
        try { le = new LoudnessEnhancer(sid); } catch (Throwable t) { le = null; }
        try { rv = new PresetReverb(0, sid); } catch (Throwable t) { rv = null; }

        int n = bandCount();
        bands = new int[n];
        for (int i = 0; i < n; i++) bands[i] = sp.getInt("band" + i, 0);
        applyAll();

        IntentFilter f = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(noisy, f);

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel("calma", "Oynatma", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if ("prev".equals(a)) prev();
        else if ("toggle".equals(a)) toggle();
        else if ("next".equals(a)) next(false);
        else if ("close".equals(a)) close();
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        savePos();
        h.removeCallbacksAndMessages(null);
        try { unregisterReceiver(noisy); } catch (Exception ignored) {}
        try { am.abandonAudioFocus(this); } catch (Exception ignored) {}
        release(eq); release(bb); release(vz); release(le); release(rv);
        try { mp.release(); } catch (Exception ignored) {}
        prepared = false;
        super.onDestroy();
    }

    private void release(android.media.audiofx.AudioEffect e) {
        try { if (e != null) e.release(); } catch (Exception ignored) {}
    }

    // ---------- liste ----------

    public void setList(List<Track> l, int start) {
        savePos();
        list = new ArrayList<>(l);
        errors = 0;
        playIndex(start);
    }

    public void addToQueue(Track t) {
        list.add(t);
        if (index < 0) playIndex(list.size() - 1);
        else changed();
    }

    public void playNext(Track t) {
        if (index < 0) { list.add(t); playIndex(list.size() - 1); return; }
        list.add(index + 1, t);
        changed();
    }

    public void removeAt(int i) {
        if (i < 0 || i >= list.size()) return;
        if (i == index) {
            list.remove(i);
            if (list.isEmpty()) {
                index = -1;
                try { mp.reset(); } catch (Exception ignored) {}
                prepared = false;
                changed();
            } else playIndex(Math.min(i, list.size() - 1));
            return;
        }
        list.remove(i);
        if (i < index) index--;
        changed();
    }

    public void move(int i, int to) {
        if (i < 0 || i >= list.size() || to < 0 || to >= list.size()) return;
        Track t = list.remove(i);
        list.add(to, t);
        if (index == i) index = to;
        else if (i < index && to >= index) index--;
        else if (i > index && to <= index) index++;
        changed();
    }

    // ---------- çalma ----------

    public void playIndex(int i) {
        if (list.isEmpty()) return;
        if (i < 0) i = list.size() - 1;
        if (i >= list.size()) i = 0;
        if (prepared && i != index) savePos();
        index = i;
        prepared = false;
        abA = -1; abB = -1;
        h.removeCallbacks(abRun);
        Track t = list.get(i);
        try {
            mp.reset();
            mp.setAudioSessionId(sid);
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(t.video ? AudioAttributes.CONTENT_TYPE_MOVIE : AudioAttributes.CONTENT_TYPE_MUSIC).build());
            vw = t.w; vh = t.h;
            if (holder != null) mp.setDisplay(holder);
            mp.setDataSource(this, t.uri);
            mp.prepareAsync();
            Lib.addHistory(this, t);
        } catch (Exception e) {
            skipBroken();
        }
        changed();
    }

    private void skipBroken() {
        errors++;
        if (errors < list.size() && errors < 20) h.post(() -> next(false));
    }

    @Override public void onPrepared(MediaPlayer m) {
        prepared = true;
        errors = 0;
        Track t = current();
        if (t != null && t.video && sp.getBoolean("resume", true)) {
            int p = sp.getInt("pos:" + t.uri, 0);
            if (p > 5000 && p < duration() - 5000) seek(p);
        }
        startPlay();
    }

    @Override public void onCompletion(MediaPlayer m) {
        if (!prepared) return;
        Track t = current();
        if (t != null) sp.edit().remove("pos:" + t.uri).apply();
        if (stopAtEnd) {
            stopAtEnd = false;
            changed();
            return;
        }
        next(true);
    }

    @Override public boolean onError(MediaPlayer m, int what, int extra) {
        prepared = false;
        skipBroken();
        return true;
    }

    private void startPlay() {
        if (!prepared) return;
        am.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        focusPaused = false;
        try {
            mp.start();
            applySpeed();
            applyVolume();
        } catch (Exception ignored) {}
        goForeground();
        changed();
    }

    public void pause() {
        try { if (prepared && mp.isPlaying()) mp.pause(); } catch (Exception ignored) {}
        savePos();
        changed();
    }

    public void toggle() {
        if (index < 0 || !prepared) {
            if (index < 0 && !list.isEmpty()) playIndex(0);
            return;
        }
        if (isPlaying()) { focusPaused = false; pause(); } else startPlay();
    }

    public void next(boolean auto) {
        if (list.isEmpty()) return;
        if (auto && repeat == 2) { playIndex(index); return; }
        int i;
        if (shuffle && list.size() > 1) {
            do { i = rnd.nextInt(list.size()); } while (i == index);
        } else {
            i = index + 1;
            if (i >= list.size()) {
                if (auto && repeat == 0) {
                    try { mp.seekTo(0); mp.pause(); } catch (Exception ignored) {}
                    changed();
                    return;
                }
                i = 0;
            }
        }
        playIndex(i);
    }

    public void prev() {
        if (list.isEmpty()) return;
        if (position() > 4000) { seek(0); return; }
        playIndex(index - 1);
    }

    public void close() {
        pause();
        if (foreground) {
            stopForeground(true);
            foreground = false;
        }
        stopSelf();
    }

    public boolean isPlaying() {
        try { return prepared && mp.isPlaying(); } catch (Exception e) { return false; }
    }

    public int position() {
        try { return prepared ? mp.getCurrentPosition() : 0; } catch (Exception e) { return 0; }
    }

    public int duration() {
        try { return prepared ? mp.getDuration() : 0; } catch (Exception e) { return 0; }
    }

    public void seek(int ms) {
        try { if (prepared) mp.seekTo(Math.max(0, ms)); } catch (Exception ignored) {}
    }

    public void seekBy(int ms) {
        int d = duration();
        seek(Math.max(0, Math.min(d > 0 ? d - 500 : 0, position() + ms)));
    }

    public Track current() {
        return index >= 0 && index < list.size() ? list.get(index) : null;
    }

    public void setDisplay(SurfaceHolder sh) {
        holder = sh;
        try { mp.setDisplay(sh); } catch (Exception ignored) {}
    }

    private void savePos() {
        Track t = current();
        if (t == null || !t.video || !prepared) return;
        int p = position();
        if (p > 5000 && p < duration() - 5000) sp.edit().putInt("pos:" + t.uri, p).apply();
    }

    /** A-B: 1. çağrı A, 2. çağrı B, 3. çağrı iptal. Döner: 1=A, 2=B, 0=iptal. */
    public int abStep() {
        if (abA < 0) { abA = position(); changed(); return 1; }
        if (abB < 0 && position() > abA + 500) {
            abB = position();
            h.removeCallbacks(abRun);
            h.post(abRun);
            seek(abA);
            changed();
            return 2;
        }
        abA = -1; abB = -1;
        h.removeCallbacks(abRun);
        changed();
        return 0;
    }

    @Override public void onAudioFocusChange(int f) {
        if (f == AudioManager.AUDIOFOCUS_LOSS) {
            pause();
        } else if (f == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                || f == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            if (isPlaying()) { pause(); focusPaused = true; }
        } else if (f == AudioManager.AUDIOFOCUS_GAIN) {
            if (focusPaused) startPlay();
        }
    }

    // ---------- efektler ----------

    public int bandCount() {
        try { return eq == null ? 0 : eq.getNumberOfBands(); } catch (Exception e) { return 0; }
    }

    public int bandFreq(int i) {
        try { return eq.getCenterFreq((short) i) / 1000; } catch (Exception e) { return 0; }
    }

    public int bandMin() {
        try { return eq.getBandLevelRange()[0]; } catch (Exception e) { return -1500; }
    }

    public int bandMax() {
        try { return eq.getBandLevelRange()[1]; } catch (Exception e) { return 1500; }
    }

    public boolean hasEq() { return eq != null && bandCount() > 0; }
    public boolean hasBoost() { return le != null; }
    public boolean hasSurround() { return vz != null; }
    public boolean hasReverb() { return rv != null; }

    public void save() {
        SharedPreferences.Editor e = sp.edit();
        e.putInt("bass", bass).putInt("treble", treble).putInt("boost", boost)
                .putInt("surround", surround).putInt("reverb", reverb).putInt("period", period)
                .putInt("balance", balance).putInt("speed", speed).putInt("pitch", pitch)
                .putInt("repeat", repeat).putBoolean("d8", d8).putBoolean("shuffle", shuffle);
        for (int i = 0; i < bands.length; i++) e.putInt("band" + i, bands[i]);
        e.apply();
    }

    public void applyAll() {
        applyEq();
        applyBoost();
        applySurround();
        applyReverb();
        applySpeed();
        set8d(d8);
    }

    public void applyEq() {
        try {
            if (hasEq()) {
                int min = bandMin(), max = bandMax();
                for (int i = 0; i < bands.length; i++) {
                    int f = bandFreq(i);
                    float lvl = bands[i];
                    if (f <= 300) lvl += bass / 100f * max * 0.6f;
                    if (f >= 8000) lvl += treble / 100f * max;
                    else if (f >= 3000) lvl += treble / 100f * max * 0.6f;
                    int v = Math.max(min, Math.min(max, Math.round(lvl)));
                    eq.setBandLevel((short) i, (short) v);
                }
            }
        } catch (Exception ignored) {}
        try {
            if (bb != null) {
                bb.setStrength((short) Math.min(1000, bass * 10));
                bb.setEnabled(bass > 0);
            }
        } catch (Exception ignored) {}
    }

    public void applyBoost() {
        try {
            if (le != null) {
                le.setTargetGain(boost * 15);
                le.setEnabled(boost > 0);
            }
        } catch (Exception ignored) {}
    }

    public void applySurround() {
        try {
            if (vz != null) {
                vz.setStrength((short) Math.min(1000, surround * 10));
                vz.setEnabled(surround > 0);
            }
        } catch (Exception ignored) {}
    }

    public void applyReverb() {
        try {
            if (rv != null) {
                rv.setPreset((short) reverb);
                rv.setEnabled(reverb > 0);
            }
        } catch (Exception ignored) {}
    }

    public void applySpeed() {
        try {
            if (prepared && mp.isPlaying()) {
                mp.setPlaybackParams(new PlaybackParams().setSpeed(speed / 100f).setPitch(pitch / 100f));
            }
        } catch (Exception ignored) {}
    }

    public void set8d(boolean on) {
        d8 = on;
        h.removeCallbacks(spin);
        if (on) h.post(spin);
        else { pan = 0; applyVolume(); }
    }

    public void applyVolume() {
        float l, r;
        if (d8) {
            double a = (pan * 0.92 + 1) * Math.PI / 4;
            l = (float) Math.min(1, Math.cos(a) * 1.4142);
            r = (float) Math.min(1, Math.sin(a) * 1.4142);
        } else {
            l = balance > 0 ? 1 - balance / 100f : 1;
            r = balance < 0 ? 1 + balance / 100f : 1;
        }
        try { mp.setVolume(l, r); } catch (Exception ignored) {}
    }

    /** dB cinsinden 5 noktalı hazır ayarı (60, 230, 910, 3600, 14000 Hz) bantlara uygular. */
    public void usePreset(int[] db) {
        int[] ref = {60, 230, 910, 3600, 14000};
        for (int i = 0; i < bands.length; i++) {
            double f = Math.log(Math.max(1, bandFreq(i)));
            int best = 0;
            double bd = Double.MAX_VALUE;
            for (int k = 0; k < ref.length; k++) {
                double d = Math.abs(f - Math.log(ref[k]));
                if (d < bd) { bd = d; best = k; }
            }
            bands[i] = Math.max(bandMin(), Math.min(bandMax(), db[best] * 100));
        }
        applyEq();
        save();
    }

    public void setSleep(int minutes) {
        h.removeCallbacks(sleepRun);
        stopAtEnd = false;
        if (minutes <= 0) { sleepAt = 0; changed(); return; }
        sleepAt = System.currentTimeMillis() + minutes * 60000L;
        h.postDelayed(sleepRun, minutes * 60000L);
        changed();
    }

    public void setStopAtEnd() {
        h.removeCallbacks(sleepRun);
        sleepAt = 0;
        stopAtEnd = true;
        changed();
    }

    // ---------- bildirim ----------

    private void changed() {
        if (foreground) {
            try {
                ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(1, buildNotification());
            } catch (Exception ignored) {}
        }
        for (Runnable r : new ArrayList<>(listeners)) r.run();
    }

    private PendingIntent action(String a, int code) {
        Intent i = new Intent(this, PlayerService.class).setAction(a);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification() {
        Track t = current();
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, "calma") : new Notification.Builder(this);
        Class<?> target = t != null && t.video ? VideoActivity.class : AudioActivity.class;
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, target), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        boolean playing = isPlaying();
        b.setSmallIcon(R.drawable.ic_note)
                .setContentTitle(t == null ? "Myplayer" : t.title)
                .setContentText(t == null ? "" : t.artist)
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setOngoing(playing)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(android.R.drawable.ic_media_previous, "Önceki", action("prev", 1))
                .addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        playing ? "Duraklat" : "Çal", action("toggle", 2))
                .addAction(android.R.drawable.ic_media_next, "Sonraki", action("next", 3))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Kapat", action("close", 4))
                .setStyle(new Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2));
        return b.build();
    }

    private void goForeground() {
        if (foreground) return;
        try {
            Notification n = buildNotification();
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(1, n);
            }
            foreground = true;
        } catch (Exception ignored) {}
    }
}
