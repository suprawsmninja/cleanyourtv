package com.gathin.tvhome;

import android.app.Activity;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * tvhome fork (config.xml shelf_slides): behind the cinematic shelf, a slideshow of what is popular on the
 * focused dock app's service, full screen, as on Apple TV. The art comes from the Watch Now app through its
 * content provider, so the launcher itself stays off the network. Apps Watch Now has nothing for keep the
 * usual blurred artwork.
 */
final class ShelfSlides {
    private static final String TAG = "TvHomeSlides";
    private static final Uri SLIDES = Uri.parse("content://com.luke.watchnow.shelf/slides/");
    private static final long SLIDE_MS = 8000;
    private static final long LIST_MAX_AGE_MS = 10 * 60 * 1000;

    interface Listener {
        /** The slide now showing (index of count), to put its title or logo where the app's name was. */
        void onSlide(Slide slide, int index, int count);
    }

    static final class Slide {
        /** logo: a transparent title logo (content uri) or null; meta: "2026 · Drama"; pkg: the app that has it. */
        final String title, image, logo, meta, service, pkg;

        Slide(String title, String image, String logo, String meta, String service, String pkg) {
            this.title = title;
            this.image = image;
            this.logo = logo;
            this.meta = meta;
            this.service = service;
            this.pkg = pkg;
        }
    }

    private final Activity activity;
    private final Listener listener;
    private final ImageView a, b;
    /** Whether b, the upper view, is the one showing. */
    private boolean topShown;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final LruCache<String, Bitmap> bitmaps = new LruCache<String, Bitmap>(24 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount() / 1024;
        }
    };
    private final Map<String, List<Slide>> lists = new HashMap<>();
    private final Map<String, Long> listTimes = new HashMap<>();
    private String pkg;
    private List<Slide> current;
    private int index;
    private boolean paused;
    /** Bumped on every show/hide, so a late load for an app no longer focused is dropped. */
    private int generation;

    private final Runnable advance = new Runnable() {
        @Override public void run() {
            if (current == null || current.isEmpty() || paused) return;
            index = (index + 1) % current.size();
            showSlide(generation);
        }
    };

    ShelfSlides(Activity activity, FrameLayout root, View above, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        a = image();
        b = image();
        int at = root.indexOfChild(above) + 1;
        root.addView(b, at, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(a, at, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private ImageView image() {
        ImageView v = new ImageView(activity);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setAlpha(0f);
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return v;
    }

    /**
     * Starts this app's slideshow. Returns true when slides are already known for it; otherwise false, and the
     * caller shows its usual art while the list loads (a later onSlide means the slideshow took over).
     */
    boolean show(String pkg) {
        if (pkg == null) {
            hide();
            return false;
        }
        if (pkg.equals(this.pkg) && current != null && !current.isEmpty()) return true;
        this.pkg = pkg;
        int gen = ++generation;
        main.removeCallbacks(advance);
        List<Slide> known = lists.get(pkg);
        Long when = listTimes.get(pkg);
        boolean fresh = when != null && SystemClock.elapsedRealtime() - when < LIST_MAX_AGE_MS;
        if (known != null && fresh) {
            start(known, gen);
            return !known.isEmpty();
        }
        current = null;
        fadeOut();
        work.execute(() -> {
            List<Slide> list = query(pkg);
            main.post(() -> {
                lists.put(pkg, list);
                listTimes.put(pkg, SystemClock.elapsedRealtime());
                if (gen == generation) start(list, gen);
            });
        });
        return known != null && !known.isEmpty();
    }

    Slide slide() {
        return showing() ? current.get(index) : null;
    }

    int index() {
        return index;
    }

    int count() {
        return showing() ? current.size() : 0;
    }

    /** Left or Right on the header: the previous or next slide straight away. */
    void step(int by) {
        if (!showing()) return;
        index = (index + by + current.size()) % current.size();
        showSlide(generation);
    }

    /** Decodes a small image (a logo) off the main thread; missing runs when there is none to show. */
    void loadLogo(String uri, ImageView into, Runnable missing) {
        into.setTag(uri);
        Bitmap hit = uri == null ? null : bitmaps.get(uri);
        if (uri == null || hit != null) {
            into.setImageBitmap(hit);
            if (hit == null) missing.run();
            return;
        }
        work.execute(() -> {
            Bitmap bmp = null;
            try (InputStream in = activity.getContentResolver().openInputStream(Uri.parse(uri))) {
                if (in != null) bmp = BitmapFactory.decodeStream(in);
            } catch (Exception e) {
                Log.d(TAG, "logo failed: " + uri + ": " + e);
            }
            Bitmap done = bmp;
            main.post(() -> {
                if (done != null) bitmaps.put(uri, done);
                if (!uri.equals(into.getTag())) return;
                into.setImageBitmap(done);
                if (done == null) missing.run();
            });
        });
    }

    boolean showing() {
        return current != null && !current.isEmpty();
    }

    void hide() {
        generation++;
        pkg = null;
        current = null;
        main.removeCallbacks(advance);
        fadeOut();
    }

    void pause() {
        paused = true;
        main.removeCallbacks(advance);
    }

    void resume() {
        paused = false;
        main.removeCallbacks(advance);
        if (showing()) main.postDelayed(advance, SLIDE_MS);
    }

    void destroy() {
        main.removeCallbacks(advance);
        work.shutdownNow();
    }

    private void start(List<Slide> list, int gen) {
        current = list;
        if (list.isEmpty()) {
            fadeOut();
            return;
        }
        // A different start for each visit, as tvOS does, so the same title is not always first.
        index = (int) ((SystemClock.elapsedRealtime() / SLIDE_MS) % list.size());
        showSlide(gen);
    }

    private void showSlide(int gen) {
        Slide s = current.get(index);
        String key = s.image;
        Bitmap hit = bitmaps.get(key);
        if (hit != null) {
            swapTo(hit, s, gen);
            return;
        }
        work.execute(() -> {
            Bitmap bmp = decode(s.image);
            main.post(() -> {
                if (gen != generation) return;
                if (bmp == null) {   // skip a broken image rather than stall the show
                    main.postDelayed(advance, 300);
                    return;
                }
                bitmaps.put(key, bmp);
                swapTo(bmp, s, gen);
            });
        });
    }

    private void swapTo(Bitmap bmp, Slide s, int gen) {
        if (gen != generation) return;
        // b sits just above a. Fading b in covers a; to show a, a goes up under b and b fades out.
        a.animate().cancel();
        b.animate().cancel();
        if (!topShown) {
            b.setImageBitmap(bmp);
            b.setAlpha(0f);
            b.animate().alpha(1f).setDuration(900).withEndAction(() -> a.setAlpha(1f)).start();
        } else {
            a.setImageBitmap(bmp);
            a.setAlpha(1f);
            b.animate().alpha(0f).setDuration(900).start();
        }
        topShown = !topShown;
        listener.onSlide(s, index, current.size());
        main.removeCallbacks(advance);
        if (!paused) main.postDelayed(advance, SLIDE_MS);
    }

    private void fadeOut() {
        topShown = false;
        a.animate().cancel();
        b.animate().cancel();
        a.animate().alpha(0f).setDuration(300).start();
        b.animate().alpha(0f).setDuration(300).start();
    }

    private List<Slide> query(String pkg) {
        List<Slide> out = new ArrayList<>();
        try (Cursor c = activity.getContentResolver().query(Uri.withAppendedPath(SLIDES, pkg), null, null, null, null)) {
            if (c == null) return out;
            int iTitle = c.getColumnIndex("title"), iImage = c.getColumnIndex("image"), iLogo = c.getColumnIndex("logo"),
                    iMeta = c.getColumnIndex("meta"), iService = c.getColumnIndex("service"), iPkg = c.getColumnIndex("package");
            while (c.moveToNext()) {
                String image = iImage < 0 ? null : c.getString(iImage);
                if (image != null) out.add(new Slide(col(c, iTitle), image, iLogo < 0 ? null : c.getString(iLogo),
                        col(c, iMeta), col(c, iService), col(c, iPkg)));
            }
        } catch (RuntimeException e) {
            Log.d(TAG, "no slides for " + pkg + ": " + e);
        }
        return out;
    }

    private static String col(Cursor c, int i) {
        String s = i < 0 ? null : c.getString(i);
        return s == null ? "" : s;
    }

    private Bitmap decode(String uri) {
        int width = activity.getResources().getDisplayMetrics().widthPixels;
        try (InputStream in = activity.getContentResolver().openInputStream(Uri.parse(uri))) {
            if (in == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap bmp = BitmapFactory.decodeStream(in, null, o);
            if (bmp != null && bmp.getWidth() > width * 1.5f) {
                Bitmap scaled = Bitmap.createScaledBitmap(bmp, width, Math.round(bmp.getHeight() * (width / (float) bmp.getWidth())), true);
                bmp.recycle();
                bmp = scaled;
            }
            return bmp;
        } catch (Exception e) {
            Log.d(TAG, "slide failed: " + uri + ": " + e);
            return null;
        }
    }
}
