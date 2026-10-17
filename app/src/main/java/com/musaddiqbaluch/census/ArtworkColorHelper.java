package com.musaddiqbaluch.census;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import androidx.palette.graphics.Palette;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.color.utilities.DynamicScheme;
import com.google.android.material.color.utilities.Hct;
import com.google.android.material.color.utilities.QuantizerCelebi;
import com.google.android.material.color.utilities.SchemeVibrant;
import com.google.android.material.color.utilities.Score;

import java.util.List;
import java.util.Map;

/**
 * Artwork color helpers backed by Material Color Utilities (MCU) Score
 * for extraction, plus AndroidX Palette API as a secondary fallback.
 *
 * Seed extraction pipeline
 * ------------------------
 *   1. MCU QuantizerCelebi + Score (PRIMARY).
 *      Google's own content-based color algorithm. It quantizes the
 *      image into a representative set of colours, filters out both
 *      very-low-chroma and extremely-rare colours, then ranks by hue
 *      neighborhood popularity within 30 degrees on the colour wheel.
 *      This is exactly the pipeline that Pixel uses to derive a
 *      wallpaper-based theme.
 *
 *      Ref: material-foundation/material-color-utilities,
 *           concepts/color_extraction.md
 *           ("prioritizes colors that are both visually appealing and
 *            frequently used within a given image")
 *
 *   2. Population gate (>= MIN_SEED_POPULATION_FRACTION of opaque
 *      pixels). A hard safety belt on top of Score: any candidate that
 *      covers less than 5% of the icon is treated as an accent, not a
 *      representative colour, and skipped. Score's own cutoff is only
 *      ~1%, which is too permissive for small app icons.
 *
 *   3. AndroidX Palette API fallback.
 *      Only reached when MCU returns nothing usable (fully desaturated
 *      or extremely-low-chroma artwork). Priority order inside the
 *      fallback is Dominant -> Muted -> Vibrant, deliberately with
 *      Vibrant LAST, because the Vibrant target weights saturation so
 *      heavily that a tiny high-chroma accent can beat a large
 *      representative area -- the exact failure mode MCU Score fixes.
 *
 *   4. Colour.TRANSPARENT -> caller substitutes the static theme.
 *
 * Scheme generation: SchemeVibrant
 * --------------------------------
 * Once the seed has been picked, SchemeVibrant maximises colourfulness
 * across primary, secondary, and tertiary palettes so every artwork-
 * driven role in the panel gets a consistent vivid register.
 *
 * Threading
 * ---------
 * Palette generation and MCU quantization are CPU-intensive. Google's
 * documentation states: "Generation should always be completed on a
 * background thread, ideally the one in which you load your image on."
 * Every public method here is designed to be called from a background
 * thread. In debug builds, the extraction methods log a warning if
 * they detect a main-thread call; they do NOT throw.
 *
 * All methods are defensive: they never throw, and return a safe
 * fallback (TRANSPARENT for colours, null for schemes) on any failure.
 */
public final class ArtworkColorHelper {

    private static final String TAG = "ArtworkColorHelper";

    /**
     * When true, prints verbose debug logs for failures. Kept false to
     * avoid log spam in production; flip to true while diagnosing.
     */
    private static final boolean VERBOSE_LOG = false;

    /**
     * MCU quantization target. 128 is the value used by the official
     * Material Color Utilities sample code and by DynamicColors'
     * content-based pipeline. Higher than Palette's default 16 because
     * MCU needs finer granularity to build accurate hue neighborhoods.
     */
    private static final int MCU_MAX_COLORS = 128;

    /**
     * Palette API quantization target, used only in the fallback path.
     * Google's docs: "For landscapes, optimal values range from 8-16,
     * while pictures with faces usually have values from 24-32."
     * App icons sit between those two: 24 gives mixed-hue icons enough
     * buckets to separate a green body from a red accent.
     */
    private static final int PALETTE_MAX_COLOR_COUNT = 24;

    /**
     * Longest dimension above which a source bitmap is downscaled before
     * extraction. Palette and MCU both walk every pixel; on a 4K bitmap
     * that is 16M reads for a 192dp icon's worth of information.
     * 512 px preserves all the visual character of an app icon while
     * keeping extraction fast and memory-safe.
     */
    private static final int MAX_SOURCE_DIMENSION = 512;

    /**
     * Minimum fraction of the icon that a candidate seed must cover to
     * be accepted. 5% is well above MCU Score's own 1% cutoff and well
     * below the share of any colour that a human would describe as
     * "the icon's colour". This is the gate that stops a small red
     * accent from overriding a predominantly green icon.
     */
    private static final float MIN_SEED_POPULATION_FRACTION = 0.05f;

    /**
     * Contrast level passed to SchemeVibrant when the platform does not
     * provide its own.
     *
     *   0.0  = default Material contrast
     *   0.5  = higher contrast (more vivid, still accessible)
     *   1.0  = highest contrast (maximum vividness)
     *  -1.0  = reduced contrast (flatter)
     *
     * On Android 14+ (API 34) this is overridden by the user's system
     * contrast setting when it is available.
     */
    private static final float DEFAULT_CONTRAST_LEVEL = 0.0f;

    private ArtworkColorHelper() {}

    // =================================================================
    // Seed extraction
    // =================================================================

    /**
     * Extracts the most representative colour from a bitmap.
     *
     * Pipeline:
     *   1. MCU QuantizerCelebi + Score (primary)
     *   2. Population gate >= 5%
     *   3. Palette API fallback (Dominant -> Muted -> Vibrant -> highest)
     *
     * Returns Color.TRANSPARENT if nothing usable can be extracted.
     *
     * IMPORTANT: this method must be called from a background thread.
     */
    public static int extractSourceColor(@Nullable Bitmap artwork) {

        if (artwork == null || artwork.isRecycled()) {
            return Color.TRANSPARENT;
        }

        warnIfMainThread("extractSourceColor(Bitmap)");

        Bitmap scaledSource = null;

        try {
            int width = artwork.getWidth();
            int height = artwork.getHeight();
            if (width <= 0 || height <= 0) {
                return Color.TRANSPARENT;
            }

            scaledSource = prepareBitmap(artwork);
            if (scaledSource == null || scaledSource.isRecycled()) {
                return Color.TRANSPARENT;
            }

            // ---- 1. Primary path: Material Color Utilities Score ----
            int mcuColor = extractViaMaterialColorUtilities(scaledSource);
            if (mcuColor != Color.TRANSPARENT) {
                return mcuColor;
            }

            // ---- 2. Fallback: AndroidX Palette API ----
            return extractViaPaletteApi(scaledSource);

        } catch (OutOfMemoryError oom) {
            Log.w(TAG, "extractSourceColor: OOM", oom);
            return Color.TRANSPARENT;

        } catch (Throwable t) {
            logVerbose("extractSourceColor(Bitmap) failed", t);
            return Color.TRANSPARENT;

        } finally {
            if (scaledSource != null
                    && scaledSource != artwork
                    && !scaledSource.isRecycled()) {
                try { scaledSource.recycle(); }
                catch (Throwable ignored) {}
            }
        }
    }

    /**
     * Extracts from a drawable using its backing bitmap when available.
     * Other drawables are rasterized at their intrinsic dimensions.
     */
    public static int extractSourceColor(@Nullable Drawable drawable) {

        if (drawable == null) {
            return Color.TRANSPARENT;
        }

        warnIfMainThread("extractSourceColor(Drawable)");

        // Fast path: BitmapDrawable delegates to the bitmap overload.
        try {
            if (drawable instanceof BitmapDrawable) {
                Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
                return extractSourceColor(bitmap);
            }
        } catch (Throwable t) {
            logVerbose("BitmapDrawable fast path failed", t);
        }

        int width = 0;
        int height = 0;
        try {
            width = drawable.getIntrinsicWidth();
            height = drawable.getIntrinsicHeight();
        } catch (Throwable t) {
            logVerbose("getIntrinsicWidth/Height failed", t);
        }

        if (width <= 0 || height <= 0) {
            try {
                android.graphics.Rect bounds = drawable.getBounds();
                if (width <= 0) width = bounds.width();
                if (height <= 0) height = bounds.height();
            } catch (Throwable t) {
                logVerbose("getBounds failed", t);
            }
        }

        if (width <= 0 || height <= 0) {
            return Color.TRANSPARENT;
        }

        Drawable working = drawable;
        try {
            Drawable.ConstantState state = drawable.getConstantState();
            if (state != null) {
                Drawable copy = state.newDrawable().mutate();
                if (copy != null) {
                    working = copy;
                }
            }
        } catch (Throwable t) {
            logVerbose("ConstantState.newDrawable failed", t);
            working = drawable;
        }

        int oldLeft = 0, oldTop = 0, oldRight = 0, oldBottom = 0;
        boolean boundsCaptured = false;

        try {
            android.graphics.Rect oldBounds = working.getBounds();
            oldLeft = oldBounds.left;
            oldTop = oldBounds.top;
            oldRight = oldBounds.right;
            oldBottom = oldBounds.bottom;
            boundsCaptured = true;
        } catch (Throwable t) {
            logVerbose("capture bounds failed", t);
        }

        Bitmap bitmap = null;
        try {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            working.setBounds(0, 0, width, height);
            working.draw(canvas);
            return extractSourceColor(bitmap);

        } catch (OutOfMemoryError oom) {
            Log.w(TAG, "extractSourceColor(Drawable): OOM rasterizing", oom);
            return Color.TRANSPARENT;

        } catch (Throwable t) {
            logVerbose("extractSourceColor(Drawable) rasterize failed", t);
            return Color.TRANSPARENT;

        } finally {
            if (boundsCaptured) {
                try { working.setBounds(oldLeft, oldTop, oldRight, oldBottom); }
                catch (Throwable ignored) {}
            }
            if (bitmap != null && !bitmap.isRecycled()) {
                try { bitmap.recycle(); }
                catch (Throwable ignored) {}
            }
        }
    }

    // =================================================================
    // Primary extraction -- Material Color Utilities
    // =================================================================

    /**
     * Runs Google's own content-based extraction:
     *
     *   pixels -> QuantizerCelebi.quantize(128) -> Map<argb, count>
     *          -> Score.score(map)              -> List<Integer> ranked
     *          -> first candidate whose population >= 5% of opaque pixels
     *
     * Score already filters out colours with chroma below ~5, tone
     * outside [10, 90], and hue-neighborhood popularity below ~1%.
     * This method adds a stricter 5% population gate on top, because
     * for a small app icon the "extremely rare" cutoff in Score is
     * still permissive enough to let a 3% red accent through.
     *
     * Returns Color.TRANSPARENT when no candidate passes the gate, so
     * the caller can fall back to the Palette path.
     */
    private static int extractViaMaterialColorUtilities(@NonNull Bitmap bitmap) {

        try {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            if (width <= 0 || height <= 0) return Color.TRANSPARENT;

            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

            // 1. Quantize: Google's recommended quantizer (Celebi).
            Map<Integer, Integer> colorToCount =
                    QuantizerCelebi.quantize(pixels, MCU_MAX_COLORS);
            if (colorToCount == null || colorToCount.isEmpty()) {
                return Color.TRANSPARENT;
            }

            // 2. Score: Google's recommended ranker. Ranks by hue
            //    neighborhood popularity, then by chroma distance
            //    from 48. Filters rare and near-greyscale colours.
            List<Integer> ranked = Score.score(colorToCount);
            if (ranked == null || ranked.isEmpty()) {
                return Color.TRANSPARENT;
            }

            // 3. Population gate. Reject any candidate that covers less
            //    than MIN_SEED_POPULATION_FRACTION of the icon. This is
            //    the belt-and-braces check that stops a small accent
            //    from overriding a large representative area.
            long totalPixels = (long) width * (long) height;
            long minPixels = Math.max(1L,
                    Math.round(totalPixels * MIN_SEED_POPULATION_FRACTION));

            for (Integer candidate : ranked) {
                if (candidate == null) continue;

                Integer pop = colorToCount.get(candidate);
                if (pop == null) continue;

                if (pop.longValue() >= minPixels) {
                    return candidate;
                }
            }

            // No candidate met the population gate.
            return Color.TRANSPARENT;

        } catch (OutOfMemoryError oom) {
            Log.w(TAG, "MCU extraction: OOM", oom);
            return Color.TRANSPARENT;

        } catch (Throwable t) {
            logVerbose("MCU extraction failed", t);
            return Color.TRANSPARENT;
        }
    }

    // =================================================================
    // Fallback extraction -- AndroidX Palette API
    // =================================================================

    /**
     * Fallback used when MCU returns nothing usable. Priority order:
     *
     *   1. Dominant  -- highest-population swatch (representative colour)
     *   2. Muted     -- least-saturated swatch (representative again)
     *   3. Vibrant   -- most-saturated swatch (only as a last resort;
     *                   the Vibrant target weights saturation heavily,
     *                   so it can be fooled by small accents)
     *   4. Highest-population swatch across all scored swatches
     */
    private static int extractViaPaletteApi(@NonNull Bitmap bitmap) {

        try {
            Palette palette = Palette.from(bitmap)
                    .maximumColorCount(PALETTE_MAX_COLOR_COUNT)
                    .generate();

            Palette.Swatch swatch = palette.getDominantSwatch();
            if (swatch == null) swatch = palette.getMutedSwatch();
            if (swatch == null) swatch = palette.getVibrantSwatch();
            if (swatch == null) swatch = pickHighestPopulationSwatch(palette.getSwatches());

            if (swatch == null) return Color.TRANSPARENT;

            return swatch.getRgb();

        } catch (OutOfMemoryError oom) {
            Log.w(TAG, "Palette extraction: OOM", oom);
            return Color.TRANSPARENT;

        } catch (Throwable t) {
            logVerbose("Palette extraction failed", t);
            return Color.TRANSPARENT;
        }
    }

    /**
     * Downscales a bitmap whose longest edge exceeds
     * MAX_SOURCE_DIMENSION. Returns the original bitmap unchanged when
     * it is already small enough, or when downscaling fails.
     */
    @Nullable
    private static Bitmap prepareBitmap(@NonNull Bitmap source) {

        int width = source.getWidth();
        int height = source.getHeight();
        if (width <= 0 || height <= 0) return null;

        int longest = Math.max(width, height);
        if (longest <= MAX_SOURCE_DIMENSION) {
            return source;
        }

        try {
            float scale = (float) MAX_SOURCE_DIMENSION / (float) longest;
            int newWidth = Math.max(1, Math.round(width * scale));
            int newHeight = Math.max(1, Math.round(height * scale));
            Bitmap scaled = Bitmap.createScaledBitmap(
                    source, newWidth, newHeight, true);
            return scaled != null ? scaled : source;

        } catch (OutOfMemoryError oom) {
            Log.w(TAG, "prepareBitmap: OOM scaling, using original", oom);
            return source;

        } catch (Throwable t) {
            logVerbose("prepareBitmap failed, using original", t);
            return source;
        }
    }

    /**
     * Returns the swatch with the highest pixel population among the
     * given list, or null if the list is empty.
     */
    @Nullable
    private static Palette.Swatch pickHighestPopulationSwatch(
            @Nullable List<Palette.Swatch> swatches) {

        if (swatches == null || swatches.isEmpty()) return null;

        Palette.Swatch best = null;
        int bestPopulation = -1;

        for (int i = 0; i < swatches.size(); i++) {
            Palette.Swatch candidate = swatches.get(i);
            if (candidate == null) continue;
            int pop = candidate.getPopulation();
            if (pop > bestPopulation) {
                bestPopulation = pop;
                best = candidate;
            }
        }

        return best;
    }

    // =================================================================
    // Scheme generation -- SchemeVibrant
    // =================================================================

    /**
     * Builds a DynamicScheme from a raw seed using SchemeVibrant.
     *
     * SchemeVibrant maximizes colorfulness across primary, secondary,
     * AND tertiary palettes. Returns null for a TRANSPARENT seed or on
     * any internal failure; callers should treat null as "use the
     * static theme instead".
     */
    @Nullable
    public static DynamicScheme createScheme(
            @NonNull Context context,
            int sourceColor) {

        if (sourceColor == Color.TRANSPARENT) return null;

        try {
            boolean isDark = isNightMode(context);
            Hct hct = Hct.fromInt(sourceColor);

            float contrast = getContrast(context);
            if (contrast < 0f) {
                contrast = DEFAULT_CONTRAST_LEVEL;
            }

            return new SchemeVibrant(hct, isDark, contrast);

        } catch (Throwable t) {
            logVerbose("createScheme failed", t);
            return null;
        }
    }

    // =================================================================
    // Transforms
    // =================================================================

    /**
     * Gramophone's COLOR_CONTRAST_FAINTED transform, applied to the
     * scheme's secondaryContainer to produce the progress-bar track.
     * Returns TRANSPARENT on failure.
     */
    public static int createTrackColor(
            @NonNull Context context,
            int secondaryContainerColor) {

        try {
            float[] hsl = new float[3];
            ColorUtils.colorToHSL(secondaryContainerColor, hsl);

            boolean isDark = isNightMode(context);

            if (isDark) {
                hsl[2] *= 0.5f; hsl[2] = Math.min(hsl[2], 1f);
                hsl[1] *= 0.8f; hsl[1] = Math.min(hsl[1], 1f);
            } else {
                hsl[1] *= 0.7f; hsl[1] = Math.min(hsl[1], 1f);
                hsl[2] *= 0.97f; hsl[2] = Math.min(hsl[2], 1f);
            }

            return ColorUtils.HSLToColor(hsl);

        } catch (Throwable t) {
            logVerbose("createTrackColor failed", t);
            return Color.TRANSPARENT;
        }
    }

    /**
     * Gramophone's COLOR_BACKGROUND_ELEVATED transform, kept for
     * callers that want the matching elevated surface color.
     * Returns TRANSPARENT on failure.
     */
    public static int createElevatedBackgroundColor(
            @NonNull Context context,
            int surfaceColor) {

        try {
            float[] hsl = new float[3];
            ColorUtils.colorToHSL(surfaceColor, hsl);

            hsl[1] *= 1.2f; hsl[1] = Math.min(hsl[1], 1f);
            hsl[2] *= 0.99f; hsl[2] = Math.min(hsl[2], 1f);

            return ColorUtils.HSLToColor(hsl);

        } catch (Throwable t) {
            logVerbose("createElevatedBackgroundColor failed", t);
            return Color.TRANSPARENT;
        }
    }

    // =================================================================
    // Misc
    // =================================================================

    public static int getColorRole(
            @NonNull Context context,
            int attrResId,
            int fallback) {

        try {
            return MaterialColors.getColor(context, attrResId, fallback);
        } catch (Throwable t) {
            logVerbose("getColorRole failed", t);
            return fallback;
        }
    }

    public static int harmonizeWithTheme(
            @NonNull Context context,
            int color) {

        if (color == Color.TRANSPARENT) return Color.TRANSPARENT;

        try {
            return MaterialColors.harmonizeWithPrimary(context, color);
        } catch (Throwable t) {
            logVerbose("harmonizeWithTheme failed", t);
            return color;
        }
    }

    public static int extractHarmonizedColor(
            @NonNull Context context,
            @Nullable Bitmap artwork) {

        int source = extractSourceColor(artwork);
        return source == Color.TRANSPARENT
                ? Color.TRANSPARENT
                : harmonizeWithTheme(context, source);
    }

    public static int extractHarmonizedColor(
            @NonNull Context context,
            @Nullable Drawable drawable) {

        int source = extractSourceColor(drawable);
        return source == Color.TRANSPARENT
                ? Color.TRANSPARENT
                : harmonizeWithTheme(context, source);
    }

    // =================================================================
    // Internal helpers
    // =================================================================

    private static float getContrast(@NonNull Context context) {
        try {
            if (android.os.Build.VERSION.SDK_INT
                    >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                android.app.UiModeManager uiModeManager =
                        (android.app.UiModeManager)
                                context.getSystemService(
                                        Context.UI_MODE_SERVICE);
                if (uiModeManager != null) {
                    return uiModeManager.getContrast();
                }
            }
        } catch (Throwable t) {
            logVerbose("getContrast failed", t);
        }
        return -1.0f;
    }

    private static boolean isNightMode(@NonNull Context context) {
        try {
            int flags = context.getResources()
                    .getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK;
            return flags == Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            logVerbose("isNightMode failed", t);
            return false;
        }
    }

    private static void warnIfMainThread(@NonNull String methodName) {
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                Log.w(TAG, methodName
                        + " called on the main thread. "
                        + "Extraction should run on a background thread "
                        + "(Google's documentation). "
                        + "The call will proceed but may cause jank.");
            }
        } catch (Throwable ignored) {}
    }

    private static void logVerbose(@NonNull String message, @NonNull Throwable t) {
        if (!VERBOSE_LOG) return;
        try { Log.w(TAG, message, t); }
        catch (Throwable ignored) {}
    }
}