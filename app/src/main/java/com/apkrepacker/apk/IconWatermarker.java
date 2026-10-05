package com.apkrepacker.apk;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.DisplayMetrics;
import android.util.TypedValue;

import com.apkrepacker.apk.axml.AxmlFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Stamps a corner-ribbon watermark onto the launcher icon's raster files.
 *
 * <p>The launcher icon is a resource ID, not a fixed path. We resolve it (and, for an
 * adaptive icon, its {@code <foreground>}/{@code <background>} layers) to the actual
 * {@code res/...} image entries via the installed app's {@link Resources}, composite the
 * watermark with a {@link Canvas}, and return replacements keyed by those exact ZIP entry
 * paths — so {@link ApkBuilder} swaps the bytes while the resource table and IDs stay intact.
 *
 * <p>Vector-only icons have no raster to stamp; those are reported and skipped.
 */
public final class IconWatermarker {

    /** Densities to probe so every size variant of the icon gets stamped. */
    private static final int[] DENSITIES = {
            DisplayMetrics.DENSITY_MEDIUM,   // 160
            DisplayMetrics.DENSITY_HIGH,     // 240
            DisplayMetrics.DENSITY_XHIGH,    // 320
            DisplayMetrics.DENSITY_XXHIGH,   // 480
            DisplayMetrics.DENSITY_XXXHIGH,  // 640
    };

    public Map<String, File> watermark(Context context, String installedPackage, File apk,
                                        String text, File outDir, BuildLog log) {
        Map<String, File> replacements = new HashMap<>();
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(installedPackage, 0);
            if (ai.icon == 0) {
                log.warn("Icon watermark: app declares no icon resource; skipped.");
                return replacements;
            }
            Resources res = pm.getResourcesForApplication(installedPackage);
            Set<String> entries = listEntries(apk);
            Set<String> rasterPaths = resolveRasterPaths(res, ai.icon, apk, entries, log);

            if (rasterPaths.isEmpty()) {
                log.warn("Icon watermark: no raster icon files found (icon is likely an "
                        + "adaptive/vector drawable); skipped. Package rename is unaffected.");
                return replacements;
            }

            String mark = (text == null || text.trim().isEmpty()) ? "MOD" : text.trim();
            if (mark.length() > 6) {
                mark = mark.substring(0, 6);
            }

            int i = 0;
            for (String entry : rasterPaths) {
                byte[] stamped = stamp(apk, entry, mark);
                if (stamped == null) {
                    log.warn("Icon watermark: could not decode " + entry + "; left unchanged.");
                    continue;
                }
                File f = new File(outDir, "wm_icon_" + (i++) + ".png");
                Files.write(f.toPath(), stamped);
                replacements.put(entry, f);
                log.info("Icon watermark applied to " + entry);
            }
        } catch (PackageManager.NameNotFoundException e) {
            log.warn("Icon watermark: could not load resources for " + installedPackage
                    + "; skipped.");
        } catch (Exception e) {
            log.warn("Icon watermark skipped: " + e.getMessage());
        }
        return replacements;
    }

    private Set<String> resolveRasterPaths(Resources res, int iconId, File apk,
                                           Set<String> zipEntries, BuildLog log) {
        List<Integer> ids = new ArrayList<>();
        ids.add(iconId);

        // If the icon default resolves to an XML (adaptive icon / layer-list), pull its
        // layer drawable references and probe those too.
        String def = valuePath(res, iconId);
        if (def != null && def.toLowerCase(Locale.US).endsWith(".xml")) {
            try {
                byte[] xml = readEntry(apk, def);
                if (xml != null) {
                    AxmlFile ax = AxmlFile.parse(xml);
                    ids.addAll(ax.getAttrReferences("foreground", AxmlFile.ATTR_DRAWABLE));
                    ids.addAll(ax.getAttrReferences("background", AxmlFile.ATTR_DRAWABLE));
                    ids.addAll(ax.getAttrReferences("item", AxmlFile.ATTR_DRAWABLE));
                }
            } catch (Exception ignore) {
                // Fall through: treat as no raster.
            }
        }

        Set<String> out = new LinkedHashSet<>();
        for (int id : ids) {
            if (id == 0) continue;
            String d = valuePath(res, id);
            if (isRaster(d) && zipEntries.contains(d)) {
                out.add(d);
            }
            for (int dpi : DENSITIES) {
                String p = valueForDensity(res, id, dpi);
                if (isRaster(p) && zipEntries.contains(p)) {
                    out.add(p);
                }
            }
        }
        return out;
    }

    private static String valuePath(Resources res, int id) {
        try {
            TypedValue tv = new TypedValue();
            res.getValue(id, tv, true);
            return tv.string != null ? tv.string.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String valueForDensity(Resources res, int id, int dpi) {
        try {
            TypedValue tv = new TypedValue();
            res.getValueForDensity(id, dpi, tv, true);
            return tv.string != null ? tv.string.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isRaster(String p) {
        if (p == null) return false;
        String s = p.toLowerCase(Locale.US);
        return s.endsWith(".png") || s.endsWith(".webp")
                || s.endsWith(".jpg") || s.endsWith(".jpeg");
    }

    private byte[] stamp(File apk, String entry, String text) throws Exception {
        byte[] src = readEntry(apk, entry);
        if (src == null) return null;
        Bitmap decoded = BitmapFactory.decodeByteArray(src, 0, src.length);
        if (decoded == null) return null;
        Bitmap bmp = decoded.copy(Bitmap.Config.ARGB_8888, true);
        if (bmp == null) return null;

        drawCornerRibbon(bmp, text);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        // Always re-encode PNG (lossless). Android loads drawables by content sniffing, so a
        // PNG payload is fine even if the original entry carried a .webp name.
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos);
        return bos.toByteArray();
    }

    /** Draws a filled diagonal ribbon with centered text across the bottom-right corner. */
    private static void drawCornerRibbon(Bitmap b, String text) {
        int w = b.getWidth();
        int h = b.getHeight();
        float size = Math.min(w, h);
        if (size < 8) return; // too small to mark meaningfully

        Canvas c = new Canvas(b);
        float half = size * 0.42f;   // ribbon half-length along the diagonal
        float thick = size * 0.17f;  // ribbon thickness

        Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
        band.setColor(0xDD1565C0); // translucent blue
        Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setColor(0xFF0D47A1);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(Math.max(1f, size * 0.012f));

        c.save();
        c.translate(w, h);
        c.rotate(-45f);
        c.translate(0f, -size * 0.12f); // pull the band slightly inward from the corner

        RectF r = new RectF(-half, -thick / 2f, half, thick / 2f);
        c.drawRect(r, band);
        c.drawRect(r, border);

        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(Color.WHITE);
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setFakeBoldText(true);
        tp.setTextSize(thick * 0.72f);
        Paint.FontMetrics fm = tp.getFontMetrics();
        float ty = -(fm.ascent + fm.descent) / 2f;
        c.drawText(text, 0f, ty, tp);

        c.restore();
    }

    private static Set<String> listEntries(File apk) throws Exception {
        Set<String> names = new LinkedHashSet<>();
        try (ZipFile zip = new ZipFile(apk)) {
            java.util.Enumeration<? extends ZipEntry> e = zip.entries();
            while (e.hasMoreElements()) {
                names.add(e.nextElement().getName());
            }
        }
        return names;
    }

    private static byte[] readEntry(File apk, String name) throws Exception {
        try (ZipFile zip = new ZipFile(apk)) {
            ZipEntry e = zip.getEntry(name);
            if (e == null) return null;
            try (InputStream in = zip.getInputStream(e)) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
                return bos.toByteArray();
            }
        }
    }
}
