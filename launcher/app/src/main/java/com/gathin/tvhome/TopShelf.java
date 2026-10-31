package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Apple TV's top shelf (config.xml top_shelf): with a dock app focused, the space above the dock shows what that
 * app shares with the system: its Continue watching rows first, then its preview channel. The first card's art
 * fills the screen behind them. Up from the dock reaches the cards; OK opens the title in its app.
 *
 * Needs READ_TV_LISTINGS (pm grant), which shows other apps' rows marked searchable, and INTERNET for their
 * https artwork. tvhome fork only: upstream keeps the launcher off the network.
 */
final class TopShelf {
    private static final String TAG = "TvHomeShelf";
    private static final int MAX_CARDS = 4;
    /** the Watch Now app: its shelf is the Continue watching of every app. */
    static final String WATCH_NOW = "com.luke.watchnow";
    private static final long MAX_AGE_MS = 60L * 24 * 60 * 60 * 1000;

    static final class Entry {
        String title, subtitle, label, image, intentUri;
        boolean portrait;
        float progress = -1f;
        long time;
    }

    private final Activity activity;
    private final float dp;
    private final ImageView backdrop;
    private final LinearLayout cards;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newFixedThreadPool(3);
    private final LruCache<String, Bitmap> images;
    private volatile Map<String, List<Entry>> byPackage = new HashMap<>();
    private String shownPackage;
    private String backdropUri;
    private float lastDockTop;

    TopShelf(Activity activity, FrameLayout root, View above) {
        this.activity = activity;
        this.dp = activity.getResources().getDisplayMetrics().density;
        images = new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 1024 / 10)) {
            @Override protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
        backdrop = new ImageView(activity);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdrop.setAlpha(0f);
        backdrop.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(backdrop, root.indexOfChild(above) + 1,
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        cards = new LinearLayout(activity);
        cards.setOrientation(LinearLayout.HORIZONTAL);
        cards.setGravity(Gravity.BOTTOM);
        cards.setClipChildren(false);
        cards.setClipToPadding(false);
        cards.setPadding(px(56), 0, px(56), 0);
        cards.setAlpha(0f);
        cards.setVisibility(View.GONE);
        root.addView(cards, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** Re-reads what every app shares. Cheap: two local queries. */
    void refresh() {
        work.execute(() -> {
            Map<String, List<Entry>> next = read();
            main.post(() -> {
                byPackage = next;
                if (shownPackage != null) {
                    String pkg = shownPackage;
                    shownPackage = null;
                    show(pkg, lastDockTop);
                }
            });
        });
    }

    boolean has(String pkg) {
        List<Entry> list = pkg == null ? null : byPackage.get(pkg);
        return list != null && !list.isEmpty();
    }

    boolean isShowing() {
        return shownPackage != null;
    }

    /** Shows this app's shelf above the dock; false (and hidden) when the app shares nothing. */
    boolean show(String pkg, float dockTopInRoot) {
        lastDockTop = dockTopInRoot;
        if (!has(pkg)) {
            hide();
            return false;
        }
        if (pkg.equals(shownPackage) && cards.getVisibility() == View.VISIBLE) {
            placeCards(dockTopInRoot);
            return true;
        }
        shownPackage = pkg;
        List<Entry> list = byPackage.get(pkg);
        cards.animate().cancel();
        cards.removeAllViews();
        // One label per group, as tvOS does: a card repeats none of the label before it.
        String previous = null;
        for (int i = 0; i < Math.min(MAX_CARDS, list.size()); i++) {
            Entry e = list.get(i);
            cards.addView(card(e, e.label.equals(previous) ? "" : e.label));
            previous = e.label;
        }
        cards.setVisibility(View.VISIBLE);
        cards.setAlpha(0f);
        cards.setTranslationX(px(16));
        cards.animate().alpha(1f).translationX(0f).setDuration(260).start();
        cards.post(() -> placeCards(dockTopInRoot));
        setBackdrop(list.get(0).image);
        return true;
    }

    void hide() {
        if (shownPackage == null && cards.getVisibility() == View.GONE) return;
        shownPackage = null;
        backdropUri = null;
        cards.animate().cancel();
        cards.animate().alpha(0f).setDuration(160).withEndAction(() -> {
            if (shownPackage == null) {
                cards.setVisibility(View.GONE);
                cards.removeAllViews();
            }
        }).start();
        backdrop.animate().cancel();
        backdrop.animate().alpha(0f).setDuration(300).start();
    }

    void destroy() {
        work.shutdownNow();
    }

    private void placeCards(float dockTopInRoot) {
        cards.setY(dockTopInRoot - cards.getHeight() - px(30));
    }

    private void setBackdrop(String uri) {
        if (uri == null || uri.equals(backdropUri)) {
            if (uri != null) backdrop.animate().alpha(0.6f).setDuration(300).start();
            return;
        }
        backdropUri = uri;
        backdrop.animate().cancel();
        backdrop.animate().alpha(0f).setDuration(140).withEndAction(() -> load(uri, activity.getResources().getDisplayMetrics().widthPixels, bmp -> {
            if (!uri.equals(backdropUri)) return;
            backdrop.setImageBitmap(bmp);
            backdrop.animate().alpha(0.6f).setDuration(420).start();
        })).start();
    }

    // ---- cards ----------------------------------------------------------------------------------

    private View card(Entry e, String heading) {
        int h = px(150), w = e.portrait ? px(100) : px(266);
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMarginEnd(px(24));
        col.setLayoutParams(clp);

        TextView label = text(15, 0xE6FFFFFF, true);
        label.setText(heading);
        label.setPadding(px(2), 0, 0, px(8));
        col.addView(label, new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout frame = new FrameLayout(activity);
        frame.setFocusable(true);
        frame.setClickable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1C1F26);
        bg.setCornerRadius(px(12));
        frame.setBackground(bg);
        frame.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 12 * dp);
            }
        });
        frame.setClipToOutline(true);
        col.addView(frame, new LinearLayout.LayoutParams(w, h));

        ImageView image = new ImageView(activity);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (e.image != null) load(e.image, w, image::setImageBitmap);

        if (e.progress >= 0f) {
            FrameLayout track = new FrameLayout(activity);
            track.setBackgroundColor(0x66FFFFFF);
            FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(4), Gravity.BOTTOM);
            tlp.setMargins(px(12), 0, px(12), px(10));
            frame.addView(track, tlp);
            View bar = new View(activity);
            bar.setBackgroundColor(0xFFFFFFFF);
            track.addView(bar, new FrameLayout.LayoutParams(Math.max(px(6), Math.round(e.progress * (w - px(24)))),
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }

        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(px(12));
        ring.setStroke(px(3), 0xFFFFFFFF);
        TextView title = text(13, 0x99FFFFFF, false);
        title.setText(e.subtitle == null || e.subtitle.isEmpty() ? e.title : e.title + "  ·  " + e.subtitle);
        title.setPadding(px(2), px(8), 0, 0);
        col.addView(title, new LinearLayout.LayoutParams(Math.max(w, px(200)), ViewGroup.LayoutParams.WRAP_CONTENT));

        frame.setOnFocusChangeListener((v, focused) -> {
            frame.setForeground(focused ? ring : null);
            frame.animate().scaleX(focused ? 1.08f : 1f).scaleY(focused ? 1.08f : 1f)
                    .translationZ(focused ? px(10) : 0).setDuration(150).start();
            title.setTextColor(focused ? 0xFFFFFFFF : 0x99FFFFFF);
            if (focused && e.image != null) setBackdrop(e.image);
        });
        frame.setOnClickListener(v -> open(e));
        frame.setOnKeyListener((v, keyCode, event) -> keyCode == KeyEvent.KEYCODE_DPAD_UP);   // nothing above the shelf
        return col;
    }

    private TextView text(int sp, int color, boolean medium) {
        TextView t = new TextView(activity);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        if (medium) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setShadowLayer(8, 0, 1, 0x99000000);
        return t;
    }

    private void open(Entry e) {
        try {
            Intent i = Intent.parseUri(e.intentUri, Intent.URI_INTENT_SCHEME);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(i);
        } catch (java.net.URISyntaxException | ActivityNotFoundException | SecurityException | NullPointerException ex) {
            Log.w(TAG, "could not open " + e.title, ex);
            if (shownPackage != null) {
                Intent i = activity.getPackageManager().getLeanbackLaunchIntentForPackage(shownPackage);
                if (i != null) activity.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        }
    }

    // ---- data -----------------------------------------------------------------------------------

    private Map<String, List<Entry>> read() {
        Map<String, List<Entry>> out = new HashMap<>();
        long now = System.currentTimeMillis();
        try (Cursor c = activity.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI, null, null, null, null)) {
            while (c != null && c.moveToNext()) {
                String pkg = str(c, TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME);
                Entry e = entry(c);
                if (pkg == null || e == null) continue;
                e.time = num(c, TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS);
                if (e.time > 0 && now - e.time > MAX_AGE_MS) continue;
                if (e.progress > 0.95f) continue;
                int type = (int) num(c, TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE);
                e.label = type == TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT ? "Up Next"
                        : type == TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_NEW ? "New Episode" : "Continue Watching";
                out.computeIfAbsent(pkg, k -> new ArrayList<>()).add(e);
            }
        } catch (RuntimeException ex) {
            Log.w(TAG, "Watch Next not readable (pm grant READ_TV_LISTINGS?)", ex);
        }
        for (List<Entry> list : out.values()) {
            Collections.sort(list, (a, b) -> Long.compare(b.time, a.time));
            Set<String> seen = new HashSet<>();
            list.removeIf(e -> !seen.add(e.title));
        }
        // Watch Now's tile shows every app's Continue watching together, newest first.
        List<Entry> all = new ArrayList<>();
        for (List<Entry> list : out.values()) all.addAll(list);
        Collections.sort(all, (a, b) -> Long.compare(b.time, a.time));
        if (!all.isEmpty()) out.put(WATCH_NOW, all);
        Map<String, String> channelNames = new HashMap<>();
        try (Cursor c = activity.getContentResolver().query(TvContract.Channels.CONTENT_URI,
                new String[]{TvContract.Channels._ID, TvContract.Channels.COLUMN_DISPLAY_NAME}, null, null, null)) {
            while (c != null && c.moveToNext()) channelNames.put(String.valueOf(c.getLong(0)), c.getString(1));
        } catch (RuntimeException ex) {
            Log.w(TAG, "channels not readable", ex);
        }
        try (Cursor c = activity.getContentResolver().query(TvContract.PreviewPrograms.CONTENT_URI, null, null, null, null)) {
            while (c != null && c.moveToNext()) {
                String pkg = str(c, TvContract.PreviewPrograms.COLUMN_PACKAGE_NAME);
                if (pkg == null) continue;
                if (pkg.equals(WATCH_NOW)) continue;
                List<Entry> list = out.computeIfAbsent(pkg, k -> new ArrayList<>());
                if (list.size() >= MAX_CARDS) continue;
                Entry e = entry(c);
                if (e == null) continue;
                String channel = channelNames.get(String.valueOf(num(c, TvContract.PreviewPrograms.COLUMN_CHANNEL_ID)));
                e.label = channel == null || channel.isEmpty() ? "You Might Like" : channel;
                list.add(e);
            }
        } catch (RuntimeException ex) {
            Log.w(TAG, "preview programs not readable", ex);
        }
        out.values().removeIf(List::isEmpty);
        Log.i(TAG, "top shelf apps: " + out.keySet());
        return out;
    }

    /** Title, art, intent and progress: the columns Watch Next and preview programs share. */
    private static Entry entry(Cursor c) {
        Entry e = new Entry();
        e.title = str(c, TvContract.WatchNextPrograms.COLUMN_TITLE);
        e.intentUri = str(c, TvContract.WatchNextPrograms.COLUMN_INTENT_URI);
        if (e.title == null || e.intentUri == null) return null;
        String season = str(c, TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER);
        String episode = str(c, TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER);
        StringBuilder sub = new StringBuilder();
        if (season != null) sub.append('S').append(season);
        if (episode != null) sub.append(sub.length() > 0 ? " E" : "E").append(episode);
        e.subtitle = sub.toString();
        String poster = str(c, TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI);
        String thumb = str(c, TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI);
        int ratio = (int) num(c, TvContract.WatchNextPrograms.COLUMN_POSTER_ART_ASPECT_RATIO);
        boolean posterPortrait = ratio == TvContract.PreviewPrograms.ASPECT_RATIO_2_3
                || ratio == 5;   // ASPECT_RATIO_MOVIE_POSTER (AndroidX only)
        // A wide thumbnail beats a portrait poster on the shelf's wide cards.
        if (posterPortrait && thumb != null) {
            e.image = thumb;
        } else {
            e.image = poster != null ? poster : thumb;
            e.portrait = posterPortrait && poster != null;
        }
        long pos = num(c, TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS);
        long dur = num(c, TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS);
        if (pos > 0 && dur > 0) e.progress = Math.min(1f, (float) pos / dur);
        return e;
    }

    private static String str(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        if (i < 0 || c.isNull(i)) return null;
        String s = c.getString(i);
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    private static long num(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        return i < 0 || c.isNull(i) ? 0 : c.getLong(i);
    }

    // ---- images ---------------------------------------------------------------------------------

    private interface Loaded { void done(Bitmap bmp); }

    private void load(String uri, int widthPx, Loaded done) {
        String key = uri + "@" + widthPx;
        Bitmap hit = images.get(key);
        if (hit != null) {
            done.done(hit);
            return;
        }
        work.execute(() -> {
            Bitmap bmp = decode(uri, widthPx);
            if (bmp == null) return;
            images.put(key, bmp);
            main.post(() -> done.done(bmp));
        });
    }

    private Bitmap decode(String uri, int widthPx) {
        try {
            byte[] data;
            if (uri.startsWith("https://") || uri.startsWith("http://")) {
                HttpURLConnection c = (HttpURLConnection) new URL(uri).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(15000);
                try (InputStream in = c.getInputStream()) {
                    data = readAll(in);
                } finally {
                    c.disconnect();
                }
            } else {
                try (InputStream in = activity.getContentResolver().openInputStream(Uri.parse(uri))) {
                    if (in == null) return null;
                    data = readAll(in);
                }
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= widthPx) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (IOException | RuntimeException e) {
            Log.d(TAG, "art failed: " + uri + ": " + e);
            return null;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private int px(float v) {
        return Math.round(v * dp);
    }
}
