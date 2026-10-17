package com.musaddiqbaluch.census;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.graphics.shapes.CornerRounding;
import androidx.graphics.shapes.Cubic;
import androidx.graphics.shapes.RoundedPolygon;
import androidx.graphics.shapes.ShapesKt;

import java.util.List;

/**
 * Drawable that renders the Google Material 3 Expressive "cookie
 * 4-sided" shape: a 4-pointed star with rounded inner and outer
 * corners, rotated -45 degrees.
 *
 * Behaviour
 * ---------
 *   - The shape is built ONCE, in the constructor.
 *   - Its cubics are captured once, rotated once, and cached as a flat
 *     float[] array, so onBoundsChange only performs scale + offset.
 *   - The rotated-and-scaled Path is rebuilt only when the bounds
 *     actually change; drawing during an animation loop is cheap.
 *   - The shape is scaled uniformly to fit the current bounds and
 *     centered.
 *
 * Robustness
 * ----------
 *   - If the shapes library fails at construction (missing, broken
 *     polygon, empty cubics list), the drawable degrades to an empty
 *     (invisible) drawable instead of crashing.
 *   - Zero-sized or non-finite bounds are ignored; the previous path
 *     is discarded rather than keeping stale geometry.
 *   - draw() is a no-op when the path is empty or the canvas is null.
 *
 * Bounding box note
 * -----------------
 *   The rotated bounds are computed from ALL four control points of
 *   each cubic. This over-estimates the true curve extents because
 *   Bezier control points are not on the curve, so the final shape
 *   is very slightly smaller than the maximum possible. This matches
 *   the original class's behaviour; it is called out here so future
 *   readers know it is intentional.
 */
public class Cookie4SidedDrawable extends Drawable {

    private static final String TAG = "Cookie4SidedDrawable";

    private static final float ROTATION_DEGREES = -45f;

    private final Paint paint;
    private final Path path = new Path();

    /**
     * Flat cubic data per cubic:
     *   [x0, y0, cx0, cy0, cx1, cy1, x1, y1]
     * Already rotated by ROTATION_DEGREES.
     *
     * Null if the shape could not be built.
     */
    @Nullable
    private final float[] cubicsFlat;

    /** Cached bounds of the rotated polygon (before scaling). */
    private final float sourceMinX;
    private final float sourceMinY;
    private final float sourceWidth;
    private final float sourceHeight;

    /** True only if the shape was built and the cubics are usable. */
    private final boolean usable;

    public Cookie4SidedDrawable(int color) {

        paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);

        // Precompute rotation constants once.
        final double angle = Math.toRadians(ROTATION_DEGREES);
        final float cos = (float) Math.cos(angle);
        final float sin = (float) Math.sin(angle);

        float[] flat = null;
        float minX = 0f;
        float minY = 0f;
        float width = 0f;
        float height = 0f;
        boolean builtOk = false;

        try {
            CornerRounding rounding = new CornerRounding(0.3f, 0.0f);

            RoundedPolygon cookie = ShapesKt.star(
                    RoundedPolygon.Companion,
                    4,
                    1.0f,
                    0.5f,
                    rounding,
                    null,
                    null,
                    0.0f,
                    0.0f
            );

            if (cookie != null) {
                List<Cubic> cubics = cookie.getCubics();

                if (cubics != null && !cubics.isEmpty()) {

                    int n = cubics.size();
                    flat = new float[n * 8];

                    float localMinX = Float.MAX_VALUE;
                    float localMinY = Float.MAX_VALUE;
                    float localMaxX = -Float.MAX_VALUE;
                    float localMaxY = -Float.MAX_VALUE;

                    for (int i = 0; i < n; i++) {
                        Cubic c = cubics.get(i);
                        if (c == null) continue;

                        int o = i * 8;

                        float x0 = c.getAnchor0X();
                        float y0 = c.getAnchor0Y();
                        float cx0 = c.getControl0X();
                        float cy0 = c.getControl0Y();
                        float cx1 = c.getControl1X();
                        float cy1 = c.getControl1Y();
                        float x1 = c.getAnchor1X();
                        float y1 = c.getAnchor1Y();

                        // Rotate once, cache the rotated coordinates.
                        float rx0 = x0 * cos - y0 * sin;
                        float ry0 = x0 * sin + y0 * cos;
                        float rcx0 = cx0 * cos - cy0 * sin;
                        float rcy0 = cx0 * sin + cy0 * cos;
                        float rcx1 = cx1 * cos - cy1 * sin;
                        float rcy1 = cx1 * sin + cy1 * cos;
                        float rx1 = x1 * cos - y1 * sin;
                        float ry1 = x1 * sin + y1 * cos;

                        flat[o]     = rx0;
                        flat[o + 1] = ry0;
                        flat[o + 2] = rcx0;
                        flat[o + 3] = rcy0;
                        flat[o + 4] = rcx1;
                        flat[o + 5] = rcy1;
                        flat[o + 6] = rx1;
                        flat[o + 7] = ry1;

                        // Track rotated bounds using all four control
                        // points (over-estimate, see class Javadoc).
                        localMinX = Math.min(localMinX, Math.min(rx0, Math.min(rcx0, Math.min(rcx1, rx1))));
                        localMinY = Math.min(localMinY, Math.min(ry0, Math.min(rcy0, Math.min(rcy1, ry1))));
                        localMaxX = Math.max(localMaxX, Math.max(rx0, Math.max(rcx0, Math.max(rcx1, rx1))));
                        localMaxY = Math.max(localMaxY, Math.max(ry0, Math.max(rcy0, Math.max(rcy1, ry1))));
                    }

                    width = localMaxX - localMinX;
                    height = localMaxY - localMinY;

                    if (width > 0f && height > 0f) {
                        minX = localMinX;
                        minY = localMinY;
                        builtOk = true;
                    } else {
                        flat = null;
                    }
                }
            }

        } catch (Throwable t) {
            Log.e(TAG, "Failed to build cookie shape; "
                    + "using empty drawable", t);
            flat = null;
            builtOk = false;
        }

        this.cubicsFlat = flat;
        this.sourceMinX = minX;
        this.sourceMinY = minY;
        this.sourceWidth = width;
        this.sourceHeight = height;
        this.usable = builtOk;
    }

    @Override
    protected void onBoundsChange(@NonNull Rect bounds) {

        super.onBoundsChange(bounds);

        path.reset();

        if (!usable || cubicsFlat == null) return;

        final int bw = bounds.width();
        final int bh = bounds.height();
        if (bw <= 0 || bh <= 0) {
            // Zero-sized bounds: leave the path empty. Do NOT keep a
            // stale path from a previous layout.
            return;
        }

        final float targetSize = Math.min(bw, bh);
        final float scale = targetSize / Math.max(sourceWidth, sourceHeight);

        if (scale <= 0f || Float.isNaN(scale) || Float.isInfinite(scale)) {
            return;
        }

        final float finalWidth = sourceWidth * scale;
        final float finalHeight = sourceHeight * scale;

        final float offsetX = bounds.left + (bw - finalWidth) / 2f;
        final float offsetY = bounds.top + (bh - finalHeight) / 2f;

        final int cubics = cubicsFlat.length / 8;

        for (int i = 0; i < cubics; i++) {
            final int o = i * 8;

            final float x0  = offsetX + (cubicsFlat[o]     - sourceMinX) * scale;
            final float y0  = offsetY + (cubicsFlat[o + 1] - sourceMinY) * scale;
            final float cx0 = offsetX + (cubicsFlat[o + 2] - sourceMinX) * scale;
            final float cy0 = offsetY + (cubicsFlat[o + 3] - sourceMinY) * scale;
            final float cx1 = offsetX + (cubicsFlat[o + 4] - sourceMinX) * scale;
            final float cy1 = offsetY + (cubicsFlat[o + 5] - sourceMinY) * scale;
            final float x1  = offsetX + (cubicsFlat[o + 6] - sourceMinX) * scale;
            final float y1  = offsetY + (cubicsFlat[o + 7] - sourceMinY) * scale;

            if (i == 0) {
                path.moveTo(x0, y0);
            }

            path.cubicTo(cx0, cy0, cx1, cy1, x1, y1);
        }

        path.close();
    }

    @Override
    public void draw(@NonNull Canvas canvas) {

        if (path.isEmpty()) return;

        try {
            canvas.drawPath(path, paint);
        } catch (Throwable t) {
            // A pathological path (NaN coordinates from a bad
            // configuration) can make some OEM canvases throw.
            Log.e(TAG, "draw failed", t);
        }
    }

    // ============================================================
    // Public API
    // ============================================================

    public void setFillColor(int color) {
        paint.setColor(color);
        invalidateSelf();
    }

    // ============================================================
    // Drawable overrides
    // ============================================================

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public int getAlpha() {
        return paint.getAlpha();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}