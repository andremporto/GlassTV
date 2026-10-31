package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages integrated wallpapers from assets/wallpapers and pre-generated 320x180 thumbnails.
 * Highly optimized for low-RAM embedded Android TV devices:
 * - Decodes backgrounds in RGB_565 (50% RAM saving vs ARGB_8888).
 * - Loads lightweight pre-rendered 320x180 thumbnails from assets/wallpapers/thumbs/.
 * - Flushes thumbnail memory cache immediately when the selector is closed.
 */
final class Wallpapers {
    private Wallpapers() {}

    static final class Item {
        final String key, label;
        Bitmap thumbnail;

        Item(String key, String label) {
            this.key = key;
            this.label = label;
        }
    }

    private static String activeKey;
    private static Backdrop activeBackdrop;
    private static final Map<String, Bitmap> THUMB_CACHE = new HashMap<>();
    private static List<Item> cachedItems;

    /** Returns all available wallpapers: "default" first, followed by assets/wallpapers. */
    static synchronized List<Item> list(Context c) {
        if (cachedItems != null) return cachedItems;
        List<Item> items = new ArrayList<>();
        items.add(new Item("default", Ui.tr("Padrão")));

        List<String> files = new ArrayList<>();
        try {
            String[] list = c.getAssets().list("wallpapers");
            if (list != null) {
                for (String f : list) {
                    if ("thumbs".equals(f)) continue;
                    String lower = f.toLowerCase();
                    if (lower.endsWith(".webp") || lower.endsWith(".jpg") || lower.endsWith(".png")) {
                        files.add(f);
                    }
                }
            }
        } catch (IOException e) {
            AppLog.w("Error listing wallpapers: " + e.getMessage());
        }

        Collections.sort(files, String::compareToIgnoreCase);

        for (String file : files) {
            int dot = file.lastIndexOf('.');
            String base = dot > 0 ? file.substring(0, dot) : file;
            // Convert kebab-case into human readable label: "avatar-aang" -> "Avatar Aang"
            String[] words = base.split("-");
            StringBuilder sb = new StringBuilder();
            for (String w : words) {
                if (w.isEmpty()) continue;
                if (sb.length() > 0) sb.append(" ");
                sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
            }
            items.add(new Item(file, sb.toString()));
        }

        cachedItems = items;
        return items;
    }

    /** Thumbnail for the selector cards (320x180). Loaded directly from assets/wallpapers/thumbs/. */
    static synchronized Bitmap thumbnail(Context c, Item item, int w, int h) {
        if (item.thumbnail != null && !item.thumbnail.isRecycled()) return item.thumbnail;
        if (THUMB_CACHE.containsKey(item.key)) {
            Bitmap cached = THUMB_CACHE.get(item.key);
            if (cached != null && !cached.isRecycled()) {
                item.thumbnail = cached;
                return cached;
            }
        }

        Bitmap thumb;
        if ("default".equals(item.key)) {
            thumb = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
            Canvas canvas = new Canvas(thumb);
            Paint paint = new Paint();
            paint.setShader(new LinearGradient(0, 0, w, h, 0xFF1C1C22, 0xFF0A0A0D, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);

            // Subtle aurora glow preview
            Paint glow = new Paint();
            glow.setShader(new RadialGradient(w * 0.35f, h * 0.45f, w * 0.5f,
                    0x664A90E2, 0x00000000, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, glow);

            glow.setShader(new RadialGradient(w * 0.75f, h * 0.55f, w * 0.45f,
                    0x559013FE, 0x00000000, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, glow);
        } else {
            // First try loading pre-generated 320x180 thumbnail
            thumb = loadFromAsset(c, "wallpapers/thumbs/" + item.key, w, h);
            if (thumb == null) {
                // Fallback to downscaling full wallpaper if thumb is missing
                thumb = loadFromAsset(c, "wallpapers/" + item.key, w, h);
            }
            if (thumb == null) {
                thumb = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
                new Canvas(thumb).drawColor(0xFF222226);
            }
        }

        thumb.setHasMipMap(true);
        THUMB_CACHE.put(item.key, thumb);
        item.thumbnail = thumb;
        return thumb;
    }

    private static Bitmap loadFromAsset(Context c, String path, int reqW, int reqH) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            opts.inJustDecodeBounds = true;
            try (InputStream in = c.getAssets().open(path)) {
                BitmapFactory.decodeStream(in, null, opts);
            }
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null;

            opts.inSampleSize = 1;
            while (opts.outWidth / (opts.inSampleSize * 2) >= reqW &&
                   opts.outHeight / (opts.inSampleSize * 2) >= reqH) {
                opts.inSampleSize *= 2;
            }
            opts.inJustDecodeBounds = false;

            Bitmap b;
            try (InputStream in = c.getAssets().open(path)) {
                b = BitmapFactory.decodeStream(in, null, opts);
            }
            if (b == null) return null;
            if (b.getWidth() == reqW && b.getHeight() == reqH) return b;
            return Ui.downscale(b, reqW, reqH);
        } catch (IOException e) {
            return null;
        }
    }

    /** Decodes full wallpaper and builds a Backdrop for HomeView. Uses RGB_565 for 50% RAM reduction. */
    static synchronized Backdrop backdrop(Context c, String key) {
        if (key == null || "default".equals(key)) return null;
        if (key.equals(activeKey) && activeBackdrop != null) return activeBackdrop;

        String path = "wallpapers/" + key;
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            opts.inJustDecodeBounds = true;
            try (InputStream in = c.getAssets().open(path)) {
                BitmapFactory.decodeStream(in, null, opts);
            }

            opts.inSampleSize = 1;
            while (opts.outWidth / (opts.inSampleSize * 2) >= Backdrop.W) {
                opts.inSampleSize *= 2;
            }
            opts.inJustDecodeBounds = false;

            Bitmap b;
            try (InputStream in = c.getAssets().open(path)) {
                b = BitmapFactory.decodeStream(in, null, opts);
            }
            if (b == null) return null;

            Backdrop backdrop = Backdrop.forWallpaper(b);
            activeKey = key;
            activeBackdrop = backdrop;
            return backdrop;
        } catch (IOException e) {
            AppLog.w("Error loading wallpaper " + key + ": " + e.getMessage());
            return null;
        }
    }

    /** Immediately frees all selector thumbnail bitmaps from RAM. */
    static synchronized void clearThumbnails() {
        for (Bitmap b : THUMB_CACHE.values()) {
            if (b != null && !b.isRecycled()) b.recycle();
        }
        THUMB_CACHE.clear();
        if (cachedItems != null) {
            for (Item it : cachedItems) {
                it.thumbnail = null;
            }
        }
    }

    /** Called on system memory pressure (onTrimMemory). */
    static synchronized void clearAll() {
        clearThumbnails();
    }
}
