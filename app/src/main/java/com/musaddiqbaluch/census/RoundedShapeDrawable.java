package com.musaddiqbaluch.census;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.Looper;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import com.google.android.material.R;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.shape.MaterialShapeDrawable;
import com.google.android.material.shape.ShapeAppearanceModel;

/**
 * Material 3 shape drawable wrapper with a chainable API for
 * backgrounds, corners, and strokes.
 *
 * Typical usage:
 *
 *   new RoundedShapeDrawable(view)
 *           .radius(28)
 *           .backgroundAttr(R.attr.colorSurfaceContainerLow)
 *           .stroke(1f, 0.40f)
 *           .applyToView();
 *
 * Fading divider (left and right ends fade out over 10 dp):
 *
 *   new RoundedShapeDrawable(dividerView)
 *           .backgroundAttr(R.attr.colorOutlineVariant)
 *           .fadeEdges(10f)
 *           .applyToView();
 *
 * Robustness
 * ----------
 *   - Every shape role, background, stroke, and apply call is wrapped
 *     in try/catch; a broken theme or stripped build degrades to a
 *     sensible default instead of crashing.
 *   - Theme color reads fall back to hardcoded M3 defaults.
 *   - Density reads fall back to 1.0f on a broken configuration.
 *   - applyToView() emits a debug warning if called off the main
 *     thread; it does not throw, because building a drawable
 *     off-thread before applying it on the main thread is a valid
 *     pattern.
 *   - A missing shape style resource leaves the previous shape intact
 *     rather than replacing it with an empty one.
 */
public class RoundedShapeDrawable extends MaterialShapeDrawable {

    private static final String TAG = "RoundedShapeDrawable";

    // Fallback M3 colors for the most common roles, used only when the
    // theme cannot be read.
    private static final int FALLBACK_SURFACE_CONTAINER_LIGHT = 0xFFF3EDF7;
    private static final int FALLBACK_SURFACE_CONTAINER_DARK  = 0xFF211F26;
    private static final int FALLBACK_SURFACE_LIGHT           = 0xFFFFFBFE;
    private static final int FALLBACK_SURFACE_DARK            = 0xFF1C1B1F;
    private static final int FALLBACK_OUTLINE_VARIANT_LIGHT   = 0xFFCAC4D0;
    private static final int FALLBACK_OUTLINE_VARIANT_DARK    = 0xFF49454F;

    /** Upper bound for radius() so a bad caller cannot produce an
     *  absurd shape. Any real view is far smaller than 200 dp. */
    private static final float MAX_RADIUS_DP = 200f;

    private final View view;

    private boolean customBackground = false;
    private boolean strokeEnabled = false;

    // Horizontal edge fade. 0 = off.
    private float fadeLengthPx = 0f;
    @Nullable private Paint fadePaint;
    private int fadeBoundsLeft = Integer.MIN_VALUE;
    private int fadeBoundsRight = Integer.MIN_VALUE;

    private float strokeWidth = 0f;
    private int strokeColor = 0x00000000;

    public RoundedShapeDrawable(@NonNull View view) {
        super();
        this.view = view;
    }

    // ==================================================
    // MATERIAL 3 SHAPE ROLES
    // ==================================================

    public RoundedShapeDrawable extraSmall() {
        return shape(R.style.ShapeAppearance_Material3_Corner_ExtraSmall);
    }

    public RoundedShapeDrawable small() {
        return shape(R.style.ShapeAppearance_Material3_Corner_Small);
    }

    public RoundedShapeDrawable medium() {
        return shape(R.style.ShapeAppearance_Material3_Corner_Medium);
    }

    public RoundedShapeDrawable large() {
        return shape(R.style.ShapeAppearance_Material3_Corner_Large);
    }

    public RoundedShapeDrawable largeIncreased() {
        return shape(R.style.ShapeAppearance_Material3_Corner_LargeIncreased);
    }

    public RoundedShapeDrawable extraLarge() {
        return shape(R.style.ShapeAppearance_Material3_Corner_ExtraLarge);
    }

    public RoundedShapeDrawable extraLargeIncreased() {
        return shape(R.style.ShapeAppearance_Material3_Corner_ExtraLargeIncreased);
    }

    public RoundedShapeDrawable extraExtraLarge() {
        return shape(R.style.ShapeAppearance_Material3_Corner_ExtraExtraLarge);
    }

    public RoundedShapeDrawable full() {
        return shape(R.style.ShapeAppearance_Material3_Corner_Full);
    }

    /**
     * Applies a Material 3 corner shape from a style resource.
     *
     * If the resource is missing (stripped build, OEM overlay) or the
     * builder throws, the previous shape is preserved. This keeps the
     * chain usable even when one role is unavailable.
     */
    private RoundedShapeDrawable shape(int shapeResId) {

        try {
            ShapeAppearanceModel shape = ShapeAppearanceModel.builder(
                    view.getContext(),
                    shapeResId,
                    0
            ).build();

            setShapeAppearanceModel(shape);

        } catch (Throwable t) {
            Log.e(TAG, "shape(styleRes=" + shapeResId + ") failed", t);
        }

        return this;
    }

    // ==================================================
    // CUSTOM RADIUS
    // ==================================================

    public RoundedShapeDrawable radius(float radiusDp) {

        try {
            // Clamp to a sane range so a bad caller cannot produce an
            // absurd shape.
            float safeDp = radiusDp;
            if (Float.isNaN(safeDp) || Float.isInfinite(safeDp)) {
                safeDp = 0f;
            }
            if (safeDp < 0f) safeDp = 0f;
            if (safeDp > MAX_RADIUS_DP) safeDp = MAX_RADIUS_DP;

            float d = safeDensity();

            ShapeAppearanceModel shape = ShapeAppearanceModel.builder()
                    .setAllCornerSizes(safeDp * d)
                    .build();

            setShapeAppearanceModel(shape);

        } catch (Throwable t) {
            Log.e(TAG, "radius(" + radiusDp + ") failed", t);
        }

        return this;
    }

    // ==================================================
    // BACKGROUND: DIRECT COLOR
    // ==================================================

    public RoundedShapeDrawable backgroundColor(int color) {

        try {
            setFillColor(ColorStateList.valueOf(color));
            customBackground = true;
        } catch (Throwable t) {
            Log.e(TAG, "backgroundColor failed", t);
        }

        return this;
    }

    // ==================================================
    // BACKGROUND: MATERIAL 3 COLOR ATTRIBUTE
    // ==================================================

    public RoundedShapeDrawable backgroundAttr(int colorAttr) {

        try {
            int color = safeThemeColor(colorAttr, fallbackSurfaceContainer());

            setFillColor(ColorStateList.valueOf(color));
            customBackground = true;

        } catch (Throwable t) {
            Log.e(TAG, "backgroundAttr failed", t);
        }

        return this;
    }

    // ==================================================
    // BACKGROUND: BLEND TWO MATERIAL 3 COLORS
    // ==================================================

    public RoundedShapeDrawable backgroundFrom(
            int fromAttr,
            int toAttr,
            float amount) {

        try {
            int fromColor = safeThemeColor(fromAttr, fallbackSurfaceContainer());
            int toColor = safeThemeColor(toAttr, fallbackSurfaceContainer());

            int color = safeBlend(fromColor, toColor, amount);

            setFillColor(ColorStateList.valueOf(color));
            customBackground = true;

        } catch (Throwable t) {
            Log.e(TAG, "backgroundFrom failed", t);
        }

        return this;
    }

    // ==================================================
    // DEFAULT MATERIAL 3 SURFACE BACKGROUND
    // ==================================================

    public RoundedShapeDrawable backgroundContrast(float amount) {

        try {
            int surfaceContainer = safeThemeColor(
                    R.attr.colorSurfaceContainer,
                    fallbackSurfaceContainer());

            int surface = safeThemeColor(
                    R.attr.colorSurface,
                    fallbackSurface());

            int color = safeBlend(surfaceContainer, surface, amount);

            setFillColor(ColorStateList.valueOf(color));
            customBackground = true;

        } catch (Throwable t) {
            Log.e(TAG, "backgroundContrast failed", t);
        }

        return this;
    }

    // ==================================================
    // MATERIAL 3 OUTLINE STROKE
    // ==================================================

    public RoundedShapeDrawable stroke(float widthDp, float amount) {

        try {
            strokeWidth = safeStrokeWidth(widthDp);

            int outline = safeThemeColor(
                    R.attr.colorOutlineVariant,
                    fallbackOutlineVariant());

            int surfaceContainer = safeThemeColor(
                    R.attr.colorSurfaceContainer,
                    fallbackSurfaceContainer());

            strokeColor = safeBlend(outline, surfaceContainer, amount);
            strokeEnabled = strokeWidth > 0f;

        } catch (Throwable t) {
            Log.e(TAG, "stroke failed", t);
        }

        return this;
    }

    // ==================================================
    // DIRECT STROKE COLOR
    // ==================================================

    public RoundedShapeDrawable strokeColor(float widthDp, int color) {

        try {
            strokeWidth = safeStrokeWidth(widthDp);
            strokeColor = color;
            strokeEnabled = strokeWidth > 0f;
        } catch (Throwable t) {
            Log.e(TAG, "strokeColor failed", t);
        }

        return this;
    }

    // ==================================================
    // MATERIAL 3 STROKE COLOR ATTRIBUTE
    // ==================================================

    public RoundedShapeDrawable strokeAttr(float widthDp, int colorAttr) {

        try {
            strokeWidth = safeStrokeWidth(widthDp);
            strokeColor = safeThemeColor(colorAttr, fallbackOutlineVariant());
            strokeEnabled = strokeWidth > 0f;
        } catch (Throwable t) {
            Log.e(TAG, "strokeAttr failed", t);
        }

        return this;
    }

    // ==================================================
    // DISABLE STROKE
    // ==================================================

    public RoundedShapeDrawable noStroke() {
        strokeEnabled = false;
        strokeWidth = 0f;
        return this;
    }

    // ==================================================
    // EDGE FADE
    // ==================================================

    /**
     * Fades the left and right ends of the whole drawable (fill and
     * stroke) to transparent over the given length.
     *
     * Works with any fill color, including colors that are changed or
     * animated later through setFillColor(), because the fade is applied
     * as an alpha mask at draw time. The fade is capped at half of the
     * drawable's width. Pass 0 to turn it off.
     */
    public RoundedShapeDrawable fadeEdges(float lengthDp) {

        try {
            float safeDp = lengthDp;
            if (Float.isNaN(safeDp) || Float.isInfinite(safeDp)) safeDp = 0f;
            if (safeDp < 0f) safeDp = 0f;
            if (safeDp > MAX_RADIUS_DP) safeDp = MAX_RADIUS_DP;

            fadeLengthPx = safeDp * safeDensity();
            fadeBoundsLeft = Integer.MIN_VALUE;
            invalidateSelf();

        } catch (Throwable t) {
            Log.e(TAG, "fadeEdges(" + lengthDp + ") failed", t);
        }

        return this;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {

        if (fadeLengthPx <= 0f) {
            super.draw(canvas);
            return;
        }

        final Rect bounds = getBounds();
        if (bounds.isEmpty()) {
            super.draw(canvas);
            return;
        }

        int saveCount = -1;
        try {
            // Draw the shape into an offscreen layer, then multiply it by
            // a horizontal alpha ramp (0 -> 1 -> 1 -> 0).
            saveCount = canvas.saveLayer(
                    bounds.left, bounds.top, bounds.right, bounds.bottom,
                    null);

            super.draw(canvas);

            canvas.drawRect(bounds, obtainFadePaint(bounds));

        } catch (Throwable t) {
            Log.e(TAG, "draw with edge fade failed", t);
        } finally {
            if (saveCount >= 0) {
                try { canvas.restoreToCount(saveCount); }
                catch (Throwable ignored) {}
            }
        }
    }

    @NonNull
    private Paint obtainFadePaint(@NonNull Rect bounds) {

        Paint paint = fadePaint;
        if (paint == null) {
            paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setStyle(Paint.Style.FILL);
            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
            fadePaint = paint;
        }

        if (fadeBoundsLeft != bounds.left || fadeBoundsRight != bounds.right) {
            float width = bounds.width();
            float fraction = width > 0f
                    ? Math.min(0.5f, fadeLengthPx / width) : 0f;

            // Only the alpha of these colors matters with DST_IN.
            paint.setShader(new LinearGradient(
                    bounds.left, 0f, bounds.right, 0f,
                    new int[]{0x00000000, 0xFF000000, 0xFF000000, 0x00000000},
                    new float[]{0f, fraction, 1f - fraction, 1f},
                    Shader.TileMode.CLAMP));

            fadeBoundsLeft = bounds.left;
            fadeBoundsRight = bounds.right;
        }

        return paint;
    }

    // ==================================================
    // APPLY
    // ==================================================

    public RoundedShapeDrawable applyToView() {

        warnIfNotMainThread("applyToView");

        // 1. Default background if none was specified.
        if (!customBackground) {
            try {
                int surfaceContainer = safeThemeColor(
                        R.attr.colorSurfaceContainer,
                        fallbackSurfaceContainer());

                setFillColor(ColorStateList.valueOf(surfaceContainer));
                customBackground = true;

            } catch (Throwable t) {
                Log.e(TAG, "applyToView: default background failed", t);
            }
        }

        // 2. Stroke (or clear it).
        try {
            if (strokeEnabled) {
                setStroke(strokeWidth, ColorStateList.valueOf(strokeColor));
            } else {
                setStrokeWidth(0f);
            }
        } catch (Throwable t) {
            Log.e(TAG, "applyToView: stroke failed", t);
        }

        // 3. Install as the view's background.
        try {
            view.setBackground(this);
        } catch (Throwable t) {
            Log.e(TAG, "applyToView: setBackground failed", t);
        }

        return this;
    }

    // ==================================================
    // INTERNAL HELPERS
    // ==================================================

    /**
     * Reads a theme color attribute, falling back to the given value on
     * any failure. Never throws.
     */
    private int safeThemeColor(int attrResId, int fallback) {
        try {
            return MaterialColors.getColor(view, attrResId, fallback);
        } catch (Throwable t) {
            return fallback;
        }
    }

    /**
     * Returns the display density, or 1.0f when the metrics are broken.
     */
    private float safeDensity() {
        try {
            float d = view.getResources().getDisplayMetrics().density;
            if (d <= 0f || Float.isNaN(d) || Float.isInfinite(d)) {
                return 1.0f;
            }
            return d;
        } catch (Throwable t) {
            return 1.0f;
        }
    }

    /**
     * Converts a dp stroke width to pixels, clamped to a sane range.
     */
    private float safeStrokeWidth(float widthDp) {
        float d = safeDensity();
        if (Float.isNaN(widthDp) || Float.isInfinite(widthDp)) return 0f;
        if (widthDp < 0f) return 0f;
        // Upper bound matches the radius clamp so nothing becomes
        // pathological.
        if (widthDp > MAX_RADIUS_DP) widthDp = MAX_RADIUS_DP;
        return widthDp * d;
    }

    private static int safeBlend(int from, int to, float amount) {
        try {
            return ColorUtils.blendARGB(from, to, amount);
        } catch (Throwable t) {
            return from;
        }
    }

    private int fallbackSurfaceContainer() {
        return isNightMode()
                ? FALLBACK_SURFACE_CONTAINER_DARK
                : FALLBACK_SURFACE_CONTAINER_LIGHT;
    }

    private int fallbackSurface() {
        return isNightMode()
                ? FALLBACK_SURFACE_DARK
                : FALLBACK_SURFACE_LIGHT;
    }

    private int fallbackOutlineVariant() {
        return isNightMode()
                ? FALLBACK_OUTLINE_VARIANT_DARK
                : FALLBACK_OUTLINE_VARIANT_LIGHT;
    }

    private boolean isNightMode() {
        try {
            int flags = view.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return flags == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void warnIfNotMainThread(@NonNull String methodName) {
        try {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                Log.w(TAG, methodName + " called off the main thread. "
                        + "View.setBackground is a UI operation.");
            }
        } catch (Throwable ignored) {
            // Logging must never break the pipeline.
        }
    }
}