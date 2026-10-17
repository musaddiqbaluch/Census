package com.musaddiqbaluch.census;

import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;

import com.google.android.material.R;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.tabs.TabLayout;

/**
 * Applies a Material 3 rounded-top tab indicator and a 16%-opacity
 * primary ripple to a TabLayout.
 *
 * Indicator ordering
 * ------------------
 *   Material Components' TabLayout has a subtle trap:
 *
 *       setSelectedTabIndicatorHeight(int) internally sets
 *       `tabSelectedIndicator = null`, discarding any custom
 *       indicator drawable that was previously applied.
 *
 *   (Source: TabLayout.java, deprecated method with the comment
 *   "Don't overwrite the custom tab indicator color.")
 *
 *   So the height MUST be set BEFORE the custom drawable. If the
 *   order is reversed, the custom GradientDrawable is silently
 *   thrown away and the TabLayout falls back to its theme-default
 *   indicator.
 *
 *   applyIndicator() below enforces the correct order.
 *
 * Robustness
 * ----------
 *   - attach() / detach() are safe to call from the main thread; a
 *     debug-only warning is logged if they are called off-thread.
 *   - Density is validated; a broken DisplayMetrics falls back to
 *     DENSITY_DEFAULT.
 *   - The theme's colorPrimary is validated; a failure or a
 *     transparent value falls back to a hardcoded M3 primary.
 *   - The ripple resource R.color.m3_tabs_ripple_color is optional;
 *     if missing, a ColorStateList is built from primary at 16% alpha.
 *   - Every TabLayout mutation is individually wrapped so one
 *     failure does not prevent the others.
 */
public final class MaterialTabScrollBar {

    private static final String TAG = "MaterialTabScrollBar";

    private static final float INDICATOR_HEIGHT_DP = 3f;

    /** Material 3 standard for tab ripples. */
    private static final float RIPPLE_ALPHA = 0.16f;

    private static final int FALLBACK_PRIMARY_LIGHT = 0xFF6750A4;
    private static final int FALLBACK_PRIMARY_DARK  = 0xFFD0BCFF;

    private static final int FALLBACK_DENSITY_DPI = DisplayMetrics.DENSITY_DEFAULT;

    private MaterialTabScrollBar() {
        // Utility class.
    }

    // ============================================================
    // Public API
    // ============================================================

    public static void attach(@NonNull TabLayout tabLayout) {

        warnIfNotMainThread("attach");

        if (tabLayout == null) return;

        try {
            final float density = resolveDensity(tabLayout);

            final int indicatorHeight = Math.max(
                    1,
                    Math.round(INDICATOR_HEIGHT_DP * density));

            final int primaryColor = resolvePrimaryColor(tabLayout);

            applyRipple(tabLayout, primaryColor);

            applyIndicator(tabLayout, primaryColor, indicatorHeight);

            try {
                tabLayout.setTabIndicatorFullWidth(false);
            } catch (Throwable t) {
                Log.e(TAG, "setTabIndicatorFullWidth failed", t);
            }

        } catch (Throwable t) {
            Log.e(TAG, "attach failed", t);
        }
    }

    public static void detach(@NonNull TabLayout tabLayout) {

        warnIfNotMainThread("detach");

        if (tabLayout == null) return;

        try {
            tabLayout.setSelectedTabIndicator(null);
        } catch (Throwable t) {
            Log.e(TAG, "detach: setSelectedTabIndicator(null) failed", t);
        }

        try {
            tabLayout.setTabRippleColor(null);
        } catch (Throwable t) {
            Log.e(TAG, "detach: setTabRippleColor(null) failed", t);
        }
    }

    // ============================================================
    // Indicator -- ORDER MATTERS
    // ============================================================

    private static void applyIndicator(
            @NonNull TabLayout tabLayout,
            int primaryColor,
            int indicatorHeight) {

        // -----------------------------------------------------------------
        // STEP 1 -- HEIGHT FIRST.
        //
        // setSelectedTabIndicatorHeight() internally nulls out any custom
        // indicator drawable the TabLayout was previously using. It MUST
        // run before setSelectedTabIndicator(), otherwise it wipes the
        // custom drawable we are about to install.
        //
        // (Deprecated in favor of "use a custom drawable with an intrinsic
        // height" -- but the deprecated setter remains the only reliable
        // way to control the indicator height in older Material Components
        // releases, and this class targets those releases.)
        // -----------------------------------------------------------------
        try {
            tabLayout.setSelectedTabIndicatorHeight(indicatorHeight);
        } catch (Throwable t) {
            Log.e(TAG, "setSelectedTabIndicatorHeight failed", t);
        }

        // -----------------------------------------------------------------
        // STEP 2 -- CUSTOM DRAWABLE SECOND.
        //
        // Now that the height is set, install the custom rounded-top
        // indicator. It will not be discarded because we do not call the
        // deprecated setter again afterward.
        // -----------------------------------------------------------------
        GradientDrawable indicator = null;

        try {
            // Top corners fully rounded, bottom corners flat.
            //
            // The radius must equal the FULL indicator height. With
            // height / 2 the curve only covers the upper half of the bar
            // and the shape still looks boxy. With the full height each top
            // corner is a complete quarter circle that runs all the way
            // down to the flat bottom edge.
            final float topRadius = indicatorHeight;

            indicator = new GradientDrawable();
            indicator.setShape(GradientDrawable.RECTANGLE);
            indicator.setColor(primaryColor);
            indicator.setCornerRadii(new float[]{
                    topRadius, topRadius,   // top-left
                    topRadius, topRadius,   // top-right
                    0f, 0f,                 // bottom-right
                    0f, 0f                  // bottom-left
            });

            // Intrinsic height so the drawable is self-describing even if
            // the framework consults it before layout.
            indicator.setSize(-1, indicatorHeight);

        } catch (Throwable t) {
            Log.e(TAG, "Failed to build indicator drawable", t);
            indicator = null;
        }

        if (indicator != null) {
            try {
                tabLayout.setSelectedTabIndicator(indicator);
            } catch (Throwable t) {
                Log.e(TAG, "setSelectedTabIndicator failed", t);
            }
        }
    }

    // ============================================================
    // Ripple
    // ============================================================

    private static void applyRipple(
            @NonNull TabLayout tabLayout,
            int primaryColor) {

        // Preferred: use the theme resource.
        try {
            tabLayout.setTabRippleColorResource(R.color.m3_tabs_ripple_color);
            return;
        } catch (Resources.NotFoundException e) {
            Log.w(TAG, "R.color.m3_tabs_ripple_color not found; "
                    + "falling back to manual ripple", e);
        } catch (Throwable t) {
            Log.e(TAG, "setTabRippleColorResource failed; "
                    + "falling back to manual ripple", t);
        }

        // Fallback: primary at 16% alpha.
        try {
            int rippleColor = ColorUtils.setAlphaComponent(
                    primaryColor,
                    Math.round(RIPPLE_ALPHA * 255f));

            tabLayout.setTabRippleColor(
                    ColorStateList.valueOf(rippleColor));

        } catch (Throwable t) {
            Log.e(TAG, "Fallback ripple application failed", t);
        }
    }

    // ============================================================
    // Color helpers
    // ============================================================

    private static int resolvePrimaryColor(@NonNull TabLayout tabLayout) {

        boolean dark = safeIsNightMode(tabLayout);
        int fallback = dark ? FALLBACK_PRIMARY_DARK : FALLBACK_PRIMARY_LIGHT;

        int color;
        try {
            color = MaterialColors.getColor(tabLayout, R.attr.colorPrimary);
        } catch (Throwable t) {
            Log.e(TAG, "getColor(colorPrimary) failed, using fallback", t);
            return fallback;
        }

        if (color == android.graphics.Color.TRANSPARENT) return fallback;
        if (((color >>> 24) & 0xFF) == 0) return fallback;

        return color;
    }

    // ============================================================
    // Density helpers
    // ============================================================

    private static float resolveDensity(@NonNull TabLayout tabLayout) {

        try {
            Resources res = tabLayout.getResources();
            if (res == null) return 1.0f;

            DisplayMetrics metrics = res.getDisplayMetrics();
            if (metrics == null) return 1.0f;

            float density = metrics.density;
            if (density <= 0f) {
                int dpi = metrics.densityDpi;
                if (dpi > 0) return dpi / (float) FALLBACK_DENSITY_DPI;
                return 1.0f;
            }

            return density;

        } catch (Throwable t) {
            Log.e(TAG, "resolveDensity failed, using 1.0f", t);
            return 1.0f;
        }
    }

    // ============================================================
    // Misc helpers
    // ============================================================

    private static boolean safeIsNightMode(@NonNull TabLayout tabLayout) {
        try {
            int flags = tabLayout.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return flags == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void warnIfNotMainThread(String methodName) {
        try {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                Log.w(TAG, methodName + " called off the main thread. "
                        + "TabLayout is main-thread-only.");
            }
        } catch (Throwable ignored) {
            // Logging must never break the pipeline.
        }
    }
}
