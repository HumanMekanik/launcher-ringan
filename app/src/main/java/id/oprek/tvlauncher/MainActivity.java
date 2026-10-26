package id.oprek.tvlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.TransitionDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * Launcher TV bergaya tvOS: wallpaper gradasi yang berganti pelan, jam besar,
 * jadwal sholat, baris "Favorit" (kartu besar), dan "Semua Aplikasi".
 * Tanpa iklan, tanpa internet, tanpa library tambahan.
 *
 * Remote:
 *  - OK            : buka
 *  - Tahan OK      : menu (favorit, info aplikasi, sembunyikan)
 *  - Back / Home   : kembali ke atas
 */
public class MainActivity extends Activity {

    private static final String PREFS = "launcher_prefs";
    private static final String KEY_HIDDEN = "hidden_packages";
    private static final String KEY_FAV = "favorites";

    /** Favorit awal (hanya yang terpasang di TV yang dipakai). */
    private static final String[] DEFAULT_FAVS = {
            "com.netflix.ninja",
            "com.google.android.youtube.tv",
            "com.disney.disneyplus",
            "com.amazon.amazonvideo.livingroom",
            "com.spotify.tv.android",
            "com.google.android.youtube.tvmusic",
            "com.android.vending"
    };

    private static final int[] WALLPAPERS = {
            R.drawable.wp_aurora, R.drawable.wp_laut, R.drawable.wp_senja,
            R.drawable.wp_hutan, R.drawable.wp_mawar, R.drawable.wp_malam
    };
    private static final long WALLPAPER_INTERVAL_MS = 60000L;

    private static final String[] PRAYER_NAMES = {"Subuh", "Zuhur", "Asar", "Magrib", "Isya"};
    private static final String[] HARI = {"Minggu", "Senin", "Selasa", "Rabu", "Kamis", "Jumat", "Sabtu"};
    private static final String[] BULAN = {"Januari", "Februari", "Maret", "April", "Mei", "Juni",
            "Juli", "Agustus", "September", "Oktober", "November", "Desember"};
    private static final TimeZone WIB = TimeZone.getTimeZone("Asia/Jakarta");

    private static final int TYPE_APP = 0;
    private static final int TYPE_SETTINGS = 1;
    private static final int TYPE_TOGGLE_HIDDEN = 2;

    private static final int DEFAULT_TILE_COLOR = 0xFF3A4A66;

    private static class Item {
        int type;
        String label;
        String pkg;
        ComponentName component;
        Drawable icon;
        Drawable banner;
        int color = DEFAULT_TILE_COLOR;
        boolean hidden;
        boolean fav;
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private float density = 1f;

    // Tampilan
    private ImageView wallpaper;
    private ScrollView mainScroll;
    private TextView greetingView;
    private TextClock clockView;
    private TextView dateView;
    private TextView nextTitle;
    private TextView nextSub;
    private LinearLayout[] prayerCells;
    private TextView[] prayerNameViews;
    private TextView[] prayerTimeViews;
    private HorizontalScrollView favScroll;
    private LinearLayout favRow;
    private TextView favEmpty;
    private LinearLayout allContainer;

    // Status
    private final Map<String, View> cells = new HashMap<String, View>();
    private String focusedTag;
    private volatile String lastSignature;
    private int loadToken = 0;
    private boolean showHidden = false;
    private int wallIndex = 0;
    private int wallToken = 0;

    private final Runnable prayerTicker = new Runnable() {
        @Override
        public void run() {
            refreshPrayer();
            handler.postDelayed(this, 15000);
        }
    };

    private final Runnable wallpaperTicker = new Runnable() {
        @Override
        public void run() {
            wallIndex = (wallIndex + 1) % WALLPAPERS.length;
            changeWallpaper(true);
            handler.postDelayed(this, WALLPAPER_INTERVAL_MS);
        }
    };

    // ------------------------------------------------------------------
    // Siklus hidup
    // ------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        // Ukuran dihitung dari lebar layar (desain 960 satuan), jadi sama di 720p/1080p/4K.
        density = getResources().getDisplayMetrics().widthPixels / 960f;
        wallIndex = Calendar.getInstance().get(Calendar.DAY_OF_YEAR) % WALLPAPERS.length;
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadApps();
        if (wallpaper.getDrawable() == null) {
            changeWallpaper(false);
        }
        handler.removeCallbacks(prayerTicker);
        handler.post(prayerTicker);
        handler.removeCallbacks(wallpaperTicker);
        handler.postDelayed(wallpaperTicker, WALLPAPER_INTERVAL_MS);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(prayerTicker);
        handler.removeCallbacks(wallpaperTicker);
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Tombol Home ditekan saat launcher sudah terbuka: kembali ke atas.
        goToTop();
    }

    @Override
    public void onBackPressed() {
        // Launcher tidak boleh "keluar"; Back hanya kembali ke atas.
        goToTop();
    }

    private void goToTop() {
        if (mainScroll == null) {
            return;
        }
        mainScroll.smoothScrollTo(0, 0);
        View first = favRow.getChildCount() > 0 ? favRow.getChildAt(0)
                : (allContainer.getChildCount() > 0
                ? ((ViewGroup) allContainer.getChildAt(0)).getChildAt(0) : null);
        if (first != null) {
            first.requestFocus();
        }
    }

    // ------------------------------------------------------------------
    // Ukuran dan gambar bantu
    // ------------------------------------------------------------------

    private int dp(float units) {
        return Math.round(units * density);
    }

    private void setSize(TextView t, float units) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, units * density);
    }

    private GradientDrawable glass(float radiusUnits, int fill, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusUnits));
        d.setStroke(Math.max(1, dp(1)), stroke);
        return d;
    }

    private GradientDrawable gradient(int top, int bottom) {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bottom});
    }

    /** f > 0: mendekati putih; f < 0: mendekati hitam. */
    private static int shade(int color, float f) {
        float t = Math.abs(f);
        int target = f > 0 ? 255 : 0;
        int r = Math.round(Color.red(color) + (target - Color.red(color)) * t);
        int g = Math.round(Color.green(color) + (target - Color.green(color)) * t);
        int b = Math.round(Color.blue(color) + (target - Color.blue(color)) * t);
        return Color.rgb(r, g, b);
    }

    private Drawable copyOf(Drawable d) {
        if (d == null) {
            return null;
        }
        Drawable.ConstantState cs = d.getConstantState();
        return cs != null ? cs.newDrawable(getResources()).mutate() : d;
    }

    private int averageColor(Drawable d) {
        try {
            Bitmap bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            d.setBounds(0, 0, 16, 16);
            d.draw(c);
            int[] px = new int[256];
            bmp.getPixels(px, 0, 16, 0, 0, 16, 16);
            bmp.recycle();
            long r = 0, g = 0, b = 0, n = 0;
            for (int p : px) {
                if (Color.alpha(p) < 128) {
                    continue;
                }
                r += Color.red(p);
                g += Color.green(p);
                b += Color.blue(p);
                n++;
            }
            if (n == 0) {
                return DEFAULT_TILE_COLOR;
            }
            return Color.rgb((int) (r / n), (int) (g / n), (int) (b / n));
        } catch (Exception e) {
            return DEFAULT_TILE_COLOR;
        }
    }

    private Drawable gearIcon() {
        int s = 128;
        Bitmap bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        float cx = s / 2f;
        float cy = s / 2f;
        for (int i = 0; i < 8; i++) {
            c.save();
            c.rotate(i * 45f, cx, cy);
            c.drawRoundRect(new RectF(cx - 9, 6, cx + 9, 34), 4, 4, p);
            c.restore();
        }
        c.drawCircle(cx, cy, 40, p);
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        c.drawCircle(cx, cy, 17, p);
        return new BitmapDrawable(getResources(), bmp);
    }

    private Drawable dotsIcon() {
        int s = 128;
        Bitmap bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        c.drawCircle(26, 64, 12, p);
        c.drawCircle(64, 64, 12, p);
        c.drawCircle(102, 64, 12, p);
        return new BitmapDrawable(getResources(), bmp);
    }

    // ------------------------------------------------------------------
    // Susunan layar (dibuat lewat kode, tanpa file layout XML)
    // ------------------------------------------------------------------

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        wallpaper = new ImageView(this);
        wallpaper.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(wallpaper, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x40000000, 0x26000000, 0x99000000}));
        root.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        content.addView(buildTopBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mainScroll = new ScrollView(this);
        mainScroll.setVerticalScrollBarEnabled(false);
        mainScroll.setFocusable(false);
        mainScroll.setClipChildren(false);
        mainScroll.setClipToPadding(false);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setClipChildren(false);
        page.setPadding(0, 0, 0, dp(48));
        mainScroll.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        page.addView(buildPrayerStrip());

        page.addView(sectionTitle("Favorit"));

        favEmpty = new TextView(this);
        favEmpty.setText("Belum ada favorit. Tahan tombol OK pada aplikasi di bawah, lalu pilih \"Tambahkan ke favorit\".");
        setSize(favEmpty, 14);
        favEmpty.setTextColor(0xB3FFFFFF);
        favEmpty.setPadding(dp(56), dp(10), dp(56), dp(10));
        favEmpty.setVisibility(View.GONE);
        page.addView(favEmpty);

        favScroll = new HorizontalScrollView(this);
        favScroll.setHorizontalScrollBarEnabled(false);
        favScroll.setFocusable(false);
        favScroll.setClipChildren(false);
        favScroll.setClipToPadding(false);
        favRow = new LinearLayout(this);
        favRow.setOrientation(LinearLayout.HORIZONTAL);
        favRow.setClipChildren(false);
        favRow.setPadding(dp(56), dp(16), dp(56), dp(16));
        favScroll.addView(favRow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(favScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        page.addView(sectionTitle("Semua Aplikasi"));

        allContainer = new LinearLayout(this);
        allContainer.setOrientation(LinearLayout.VERTICAL);
        allContainer.setClipChildren(false);
        page.addView(allContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        content.addView(mainScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(56), dp(24), dp(56), dp(4));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);

        greetingView = new TextView(this);
        setSize(greetingView, 15);
        greetingView.setTextColor(0xCCFFFFFF);
        left.addView(greetingView);

        clockView = new TextClock(this);
        clockView.setFormat24Hour("HH:mm");
        clockView.setFormat12Hour("HH:mm");
        clockView.setTimeZone("Asia/Jakarta");
        clockView.setTextSize(TypedValue.COMPLEX_UNIT_PX, 56 * density);
        clockView.setTextColor(Color.WHITE);
        clockView.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clockView.setIncludeFontPadding(false);
        left.addView(clockView);

        dateView = new TextView(this);
        setSize(dateView, 15);
        dateView.setTextColor(0xB3FFFFFF);
        dateView.setPadding(0, dp(4), 0, 0);
        left.addView(dateView);

        bar.addView(left, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View spacer = new View(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.VERTICAL);
        pill.setGravity(Gravity.END);
        pill.setPadding(dp(20), dp(12), dp(20), dp(12));
        pill.setBackground(glass(18, 0x2EFFFFFF, 0x26FFFFFF));

        nextTitle = new TextView(this);
        setSize(nextTitle, 20);
        nextTitle.setTextColor(Color.WHITE);
        nextTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        nextTitle.setGravity(Gravity.END);
        pill.addView(nextTitle);

        nextSub = new TextView(this);
        setSize(nextSub, 14);
        nextSub.setTextColor(0xCCFFFFFF);
        nextSub.setGravity(Gravity.END);
        pill.addView(nextSub);

        bar.addView(pill, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return bar;
    }

    private View buildPrayerStrip() {
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);

        prayerCells = new LinearLayout[5];
        prayerNameViews = new TextView[5];
        prayerTimeViews = new TextView[5];

        for (int i = 0; i < 5; i++) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(0, dp(10), 0, dp(10));

            TextView name = new TextView(this);
            name.setText(PRAYER_NAMES[i]);
            setSize(name, 12);
            name.setGravity(Gravity.CENTER);

            TextView time = new TextView(this);
            setSize(time, 24);
            time.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            time.setGravity(Gravity.CENTER);

            cell.addView(name);
            cell.addView(time);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i < 4) {
                lp.rightMargin = dp(10);
            }
            strip.addView(cell, lp);

            prayerCells[i] = cell;
            prayerNameViews[i] = name;
            prayerTimeViews[i] = time;
        }

        LinearLayout.LayoutParams stripParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stripParams.leftMargin = dp(56);
        stripParams.rightMargin = dp(56);
        stripParams.topMargin = dp(14);
        strip.setLayoutParams(stripParams);
        return strip;
    }

    private TextView sectionTitle(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        setSize(t, 19);
        t.setTextColor(Color.WHITE);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(dp(56), dp(26), dp(56), dp(2));
        return t;
    }

    // ------------------------------------------------------------------
    // Wallpaper (6 gambar kecil di res/drawable-nodpi, berganti dengan crossfade)
    // ------------------------------------------------------------------

    private void changeWallpaper(final boolean animate) {
        final int token = ++wallToken;
        final int resId = WALLPAPERS[wallIndex];
        new Thread(new Runnable() {
            @Override
            public void run() {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inScaled = false;
                final Bitmap bmp = BitmapFactory.decodeResource(getResources(), resId, o);
                if (bmp == null) {
                    return;
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || token != wallToken) {
                            return;
                        }
                        final BitmapDrawable next = new BitmapDrawable(getResources(), bmp);
                        Drawable old = wallpaper.getDrawable();
                        if (animate && old != null) {
                            TransitionDrawable td = new TransitionDrawable(new Drawable[]{old, next});
                            wallpaper.setImageDrawable(td);
                            td.startTransition(1600);
                            handler.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    if (token == wallToken) {
                                        wallpaper.setImageDrawable(next);
                                    }
                                }
                            }, 1700);
                        } else {
                            wallpaper.setImageDrawable(next);
                        }
                    }
                });
            }
        }).start();
    }

    // ------------------------------------------------------------------
    // Jadwal sholat (dihitung offline oleh PrayerTimes, WIB)
    // ------------------------------------------------------------------

    private static String formatTime(int minutesOfDay) {
        return String.format(Locale.US, "%02d:%02d", minutesOfDay / 60, minutesOfDay % 60);
    }

    private void refreshPrayer() {
        Calendar now = Calendar.getInstance(WIB);
        int hour = now.get(Calendar.HOUR_OF_DAY);
        int nowSeconds = hour * 3600 + now.get(Calendar.MINUTE) * 60 + now.get(Calendar.SECOND);

        String greeting;
        if (hour >= 4 && hour < 11) {
            greeting = "Selamat pagi";
        } else if (hour >= 11 && hour < 15) {
            greeting = "Selamat siang";
        } else if (hour >= 15 && hour < 18) {
            greeting = "Selamat sore";
        } else {
            greeting = "Selamat malam";
        }
        greetingView.setText(greeting);
        dateView.setText(HARI[now.get(Calendar.DAY_OF_WEEK) - 1] + ", "
                + now.get(Calendar.DAY_OF_MONTH) + " " + BULAN[now.get(Calendar.MONTH)]
                + " " + now.get(Calendar.YEAR));

        int[] times = PrayerTimes.compute(now.get(Calendar.YEAR),
                now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH));

        int next = -1;
        for (int i = 0; i < 5; i++) {
            if (times[i] * 60 > nowSeconds) {
                next = i;
                break;
            }
        }

        int secondsLeft;
        boolean tomorrow = false;
        int[] nextDayTimes = times;
        if (next == -1) {
            // Sudah lewat Isya: berikutnya Subuh besok
            Calendar t = Calendar.getInstance(WIB);
            t.add(Calendar.DAY_OF_MONTH, 1);
            nextDayTimes = PrayerTimes.compute(t.get(Calendar.YEAR),
                    t.get(Calendar.MONTH) + 1, t.get(Calendar.DAY_OF_MONTH));
            next = 0;
            tomorrow = true;
            secondsLeft = (86400 - nowSeconds) + nextDayTimes[0] * 60;
        } else {
            secondsLeft = times[next] * 60 - nowSeconds;
        }

        for (int i = 0; i < 5; i++) {
            // Setelah Isya, kartu yang ditampilkan tetap jadwal hari ini; Subuh disorot sebagai berikutnya
            prayerTimeViews[i].setText(formatTime(times[i]));
            boolean isNext = (i == next);
            if (isNext) {
                prayerCells[i].setBackground(glass(14, 0xF2FFFFFF, 0xF2FFFFFF));
                prayerNameViews[i].setTextColor(0xFF52606D);
                prayerTimeViews[i].setTextColor(0xFF0B1220);
            } else {
                prayerCells[i].setBackground(glass(14, 0x2EFFFFFF, 0x26FFFFFF));
                prayerNameViews[i].setTextColor(0xB3FFFFFF);
                prayerTimeViews[i].setTextColor(Color.WHITE);
            }
        }

        int minutes = (secondsLeft + 59) / 60;
        int h = minutes / 60;
        int m = minutes % 60;
        String countdown = h > 0 ? (h + " jam " + m + " menit lagi") : (m + " menit lagi");
        nextTitle.setText(PRAYER_NAMES[next] + "  " + formatTime(nextDayTimes[next])
                + (tomorrow ? " (besok)" : ""));
        nextSub.setText(countdown);
    }

    // ------------------------------------------------------------------
    // Memuat daftar aplikasi (di thread terpisah; hanya dibangun ulang bila berubah)
    // ------------------------------------------------------------------

    private void collect(PackageManager pm, Map<String, ResolveInfo> map, String category) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(category);
        List<ResolveInfo> found = pm.queryIntentActivities(intent, 0);
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) {
                continue;
            }
            String pkg = ri.activityInfo.packageName;
            if (!map.containsKey(pkg)) {
                map.put(pkg, ri);
            }
        }
    }

    private List<String> readFavorites(Set<String> installed) {
        String raw = prefs.getString(KEY_FAV, null);
        List<String> list = new ArrayList<String>();
        if (raw == null) {
            for (String p : DEFAULT_FAVS) {
                if (installed.contains(p)) {
                    list.add(p);
                }
            }
            if (!list.isEmpty()) {
                prefs.edit().putString(KEY_FAV, TextUtils.join(",", list)).apply();
            }
            return list;
        }
        if (raw.length() > 0) {
            for (String p : raw.split(",")) {
                if (p.length() > 0) {
                    list.add(p);
                }
            }
        }
        return list;
    }

    private void saveFavorites(List<String> favs) {
        prefs.edit().putString(KEY_FAV, TextUtils.join(",", favs)).apply();
    }

    private Drawable loadBannerSafe(ResolveInfo ri, PackageManager pm) {
        try {
            Drawable b = ri.activityInfo.loadBanner(pm);
            if (b == null && ri.activityInfo.applicationInfo != null) {
                b = ri.activityInfo.applicationInfo.loadBanner(pm);
            }
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    private void loadApps() {
        final int token = ++loadToken;
        final boolean showHiddenNow = showHidden;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    PackageManager pm = getPackageManager();
                    Map<String, ResolveInfo> found = new LinkedHashMap<String, ResolveInfo>();
                    collect(pm, found, Intent.CATEGORY_LEANBACK_LAUNCHER);
                    collect(pm, found, Intent.CATEGORY_LAUNCHER);
                    found.remove(getPackageName());

                    final List<String> favs = readFavorites(found.keySet());
                    final Set<String> hidden = new HashSet<String>(
                            prefs.getStringSet(KEY_HIDDEN, new HashSet<String>()));

                    // Tanda pengenal daftar saat ini: bila sama dengan yang sudah tampil, tidak perlu bangun ulang
                    List<String> keys = new ArrayList<String>(found.keySet());
                    Collections.sort(keys);
                    List<String> hiddenSorted = new ArrayList<String>(hidden);
                    Collections.sort(hiddenSorted);
                    final String signature = TextUtils.join(";", keys) + "|"
                            + TextUtils.join(",", favs) + "|"
                            + TextUtils.join(",", hiddenSorted) + "|" + showHiddenNow;
                    if (signature.equals(lastSignature)) {
                        return;
                    }

                    final List<Item> apps = new ArrayList<Item>();
                    for (Map.Entry<String, ResolveInfo> e : found.entrySet()) {
                        ResolveInfo ri = e.getValue();
                        Item item = new Item();
                        item.type = TYPE_APP;
                        item.pkg = e.getKey();
                        item.component = new ComponentName(item.pkg, ri.activityInfo.name);
                        CharSequence label = ri.loadLabel(pm);
                        item.label = label != null ? label.toString() : item.pkg;
                        item.icon = ri.loadIcon(pm);
                        if (item.icon != null) {
                            item.color = averageColor(item.icon);
                        }
                        if (favs.contains(item.pkg)) {
                            item.banner = loadBannerSafe(ri, pm);
                        }
                        apps.add(item);
                    }

                    final Collator collator = Collator.getInstance();
                    collator.setStrength(Collator.PRIMARY);
                    Collections.sort(apps, new java.util.Comparator<Item>() {
                        @Override
                        public int compare(Item a, Item b) {
                            return collator.compare(a.label, b.label);
                        }
                    });

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing() || token != loadToken) {
                                return;
                            }
                            lastSignature = signature;
                            applyItems(apps, favs, hidden);
                        }
                    });
                } catch (Exception e) {
                    // Gagal memuat daftar: biarkan tampilan yang lama.
                }
            }
        }).start();
    }

    // ------------------------------------------------------------------
    // Membangun baris Favorit dan Semua Aplikasi
    // ------------------------------------------------------------------

    private void applyItems(List<Item> apps, List<String> favs, Set<String> hidden) {
        favRow.removeAllViews();
        allContainer.removeAllViews();
        cells.clear();

        int hiddenCount = 0;
        Map<String, Item> byPkg = new HashMap<String, Item>();
        List<Item> visible = new ArrayList<Item>();
        for (Item a : apps) {
            a.hidden = hidden.contains(a.pkg);
            a.fav = favs.contains(a.pkg);
            byPkg.put(a.pkg, a);
            if (a.hidden) {
                hiddenCount++;
                if (!showHidden) {
                    continue;
                }
            }
            visible.add(a);
        }

        // Baris favorit (kartu besar 16:9)
        int favCount = 0;
        for (String p : favs) {
            Item it = byPkg.get(p);
            if (it == null || (it.hidden && !showHidden)) {
                continue;
            }
            View card = buildCard(it, true, true, "f:" + it.pkg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(18);
            favRow.addView(card, lp);
            favCount++;
        }
        favEmpty.setVisibility(favCount == 0 ? View.VISIBLE : View.GONE);
        favScroll.setVisibility(favCount == 0 ? View.GONE : View.VISIBLE);

        // Semua aplikasi (kotak kecil, beberapa baris)
        List<Item> grid = new ArrayList<Item>();
        Item settings = new Item();
        settings.type = TYPE_SETTINGS;
        settings.label = "Pengaturan";
        grid.add(settings);
        grid.addAll(visible);
        if (hiddenCount > 0 || showHidden) {
            Item toggle = new Item();
            toggle.type = TYPE_TOGGLE_HIDDEN;
            toggle.label = showHidden ? "Sembunyikan lagi" : "Tersembunyi (" + hiddenCount + ")";
            grid.add(toggle);
        }

        int widthUnits = Math.round(getResources().getDisplayMetrics().widthPixels / density);
        int columns = Math.max(3, (widthUnits - 112 + 14) / (100 + 14));

        LinearLayout row = null;
        for (int i = 0; i < grid.size(); i++) {
            if (i % columns == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                row.setPadding(dp(56), dp(8), dp(56), dp(8));
                allContainer.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            Item it = grid.get(i);
            String key = it.type == TYPE_APP ? it.pkg
                    : (it.type == TYPE_SETTINGS ? "__settings" : "__toggle");
            View card = buildCard(it, false, false, "a:" + key);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(14);
            row.addView(card, lp);
        }

        // Kembalikan fokus ke tempat semula (atau ke kartu pertama)
        View target = focusedTag != null ? cells.get(focusedTag) : null;
        if (target == null && favRow.getChildCount() > 0) {
            target = favRow.getChildAt(0);
        }
        if (target == null && allContainer.getChildCount() > 0) {
            target = ((ViewGroup) allContainer.getChildAt(0)).getChildAt(0);
        }
        if (target != null) {
            target.requestFocus();
        }
    }

    private View buildCard(final Item item, final boolean large, final boolean inFavRow,
                           final String tag) {
        final int tw = dp(large ? 232 : 100);
        final int th = dp(large ? 130 : 100);
        final int radius = dp(large ? 14 : 20);

        final LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setClipChildren(false);
        cell.setFocusable(true);
        if (Build.VERSION.SDK_INT >= 26) {
            cell.setDefaultFocusHighlightEnabled(false);
        }
        cell.setAlpha(item.hidden ? 0.5f : 1f);

        FrameLayout tile = new FrameLayout(this);
        tile.setClipToOutline(true);
        tile.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });

        if (item.type == TYPE_APP) {
            tile.setBackground(gradient(shade(item.color, 0.12f), shade(item.color, -0.5f)));
            if (large && item.banner != null) {
                ImageView banner = new ImageView(this);
                banner.setScaleType(ImageView.ScaleType.CENTER_CROP);
                banner.setImageDrawable(copyOf(item.banner));
                tile.addView(banner, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            } else {
                ImageView icon = new ImageView(this);
                icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                icon.setImageDrawable(copyOf(item.icon));
                int size = dp(large ? 68 : 56);
                tile.addView(icon, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
            }
        } else {
            tile.setBackground(glass(0, 0x38FFFFFF, 0x00000000));
            ImageView icon = new ImageView(this);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setImageDrawable(item.type == TYPE_SETTINGS ? gearIcon() : dotsIcon());
            int size = dp(46);
            tile.addView(icon, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
        }

        // Bingkai putih tipis saat difokus
        final View border = new View(this);
        GradientDrawable stroke = new GradientDrawable();
        stroke.setColor(Color.TRANSPARENT);
        stroke.setCornerRadius(radius);
        stroke.setStroke(Math.max(2, dp(3)), Color.WHITE);
        border.setBackground(stroke);
        border.setAlpha(0f);
        tile.addView(border, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        cell.addView(tile, new LinearLayout.LayoutParams(tw, th));

        final TextView label = new TextView(this);
        label.setText(item.label);
        setSize(label, large ? 14 : 12);
        label.setTextColor(Color.WHITE);
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setAlpha(0.62f);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                tw, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelParams.topMargin = dp(8);
        cell.addView(label, labelParams);

        cell.setTag(tag);
        cells.put(tag, cell);

        cell.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                float scale = hasFocus ? (large ? 1.10f : 1.14f) : 1f;
                v.animate().scaleX(scale).scaleY(scale)
                        .translationZ(hasFocus ? dp(10) : 0f).setDuration(150).start();
                border.animate().alpha(hasFocus ? 1f : 0f).setDuration(150).start();
                label.animate().alpha(hasFocus ? 1f : 0.62f).setDuration(150).start();
                if (hasFocus) {
                    focusedTag = tag;
                    if (inFavRow) {
                        mainScroll.smoothScrollTo(0, 0);
                        ensureVisible(favScroll, v);
                    }
                }
            }
        });

        cell.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onItemActivated(item);
            }
        });

        if (item.type == TYPE_APP) {
            cell.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    showOptions(item);
                    return true;
                }
            });
        }
        return cell;
    }

    /** Geser baris horizontal supaya kartu yang difokus tidak menempel di tepi layar. */
    private void ensureVisible(HorizontalScrollView sv, View cell) {
        int viewWidth = sv.getWidth();
        if (viewWidth == 0) {
            return;
        }
        int margin = dp(56);
        int left = cell.getLeft() - sv.getScrollX();
        int right = cell.getRight() - sv.getScrollX();
        if (left < margin) {
            sv.smoothScrollBy(left - margin, 0);
        } else if (right > viewWidth - margin) {
            sv.smoothScrollBy(right - (viewWidth - margin), 0);
        }
    }

    // ------------------------------------------------------------------
    // Aksi
    // ------------------------------------------------------------------

    private void onItemActivated(Item item) {
        switch (item.type) {
            case TYPE_SETTINGS:
                safeStart(new Intent(Settings.ACTION_SETTINGS));
                break;
            case TYPE_TOGGLE_HIDDEN:
                showHidden = !showHidden;
                loadApps();
                break;
            default:
                launch(item);
                break;
        }
    }

    private void launch(Item item) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setComponent(item.component);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        safeStart(intent);
    }

    private void safeStart(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            toast("Tidak bisa membuka ini di TV");
        } catch (SecurityException e) {
            toast("Tidak diizinkan membuka ini");
        }
    }

    private void showOptions(final Item item) {
        final List<String> labels = new ArrayList<String>();
        final List<Runnable> actions = new ArrayList<Runnable>();

        labels.add("Buka");
        actions.add(new Runnable() {
            @Override
            public void run() {
                launch(item);
            }
        });

        labels.add(item.fav ? "Hapus dari favorit" : "Tambahkan ke favorit");
        actions.add(new Runnable() {
            @Override
            public void run() {
                toggleFavorite(item);
            }
        });

        if (item.fav) {
            labels.add("Pindahkan ke awal favorit");
            actions.add(new Runnable() {
                @Override
                public void run() {
                    moveFavoriteFirst(item);
                }
            });
        }

        labels.add("Info aplikasi (nonaktifkan / copot)");
        actions.add(new Runnable() {
            @Override
            public void run() {
                openAppInfo(item);
            }
        });

        labels.add(item.hidden ? "Tampilkan lagi" : "Sembunyikan dari layar");
        actions.add(new Runnable() {
            @Override
            public void run() {
                toggleHidden(item);
            }
        });

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(item.label)
                .setItems(labels.toArray(new CharSequence[0]),
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                actions.get(which).run();
                            }
                        })
                .show();
    }

    private void toggleFavorite(Item item) {
        List<String> favs = readFavorites(Collections.<String>emptySet());
        if (favs.contains(item.pkg)) {
            favs.remove(item.pkg);
        } else {
            favs.add(item.pkg);
        }
        saveFavorites(favs);
        loadApps();
    }

    private void moveFavoriteFirst(Item item) {
        List<String> favs = readFavorites(Collections.<String>emptySet());
        favs.remove(item.pkg);
        favs.add(0, item.pkg);
        saveFavorites(favs);
        focusedTag = "f:" + item.pkg;
        loadApps();
    }

    private void openAppInfo(Item item) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", item.pkg, null));
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            toast("Buka Pengaturan > Aplikasi secara manual");
        } catch (SecurityException e) {
            toast("Buka Pengaturan > Aplikasi secara manual");
        }
    }

    private void toggleHidden(Item item) {
        Set<String> hidden = new HashSet<String>(
                prefs.getStringSet(KEY_HIDDEN, new HashSet<String>()));
        if (item.hidden) {
            hidden.remove(item.pkg);
        } else {
            hidden.add(item.pkg);
        }
        prefs.edit().putStringSet(KEY_HIDDEN, hidden).apply();
        loadApps();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
