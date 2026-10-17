package com.musaddiqbaluch.census;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import com.airbnb.lottie.LottieAnimationView;
import com.airbnb.lottie.LottieProperty;
import com.airbnb.lottie.model.KeyPath;
import com.airbnb.lottie.value.LottieFrameInfo;
import com.airbnb.lottie.value.LottieValueCallback;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.color.utilities.DynamicScheme;

/**
 * Applies Material 3 colors to the Emo Droid Lottie animation.
 *
 * Role sources
 * ------------
 *   readStaticRoles(Context)         -> static theme (TOTAL + fallback)
 *   readSchemeRoles(Context, scheme) -> artwork-driven scheme
 *
 * Light vs dark mapping
 * ---------------------
 *   Dark: direct tone mapping.
 *     head = primary, accents = primaryContainer, secondary = secondary,
 *     eyes = onPrimary, outline = outline.
 *
 *   Light:
 *     head       = primary                              (direct)
 *     headAccent = blend(primaryContainer, surface, 0.25)  ~ tone 92
 *     secondary  = blend(secondary, secondaryContainer, 0.45)
 *     eyes       = surface                                 ~ tone 98
 *     outline    = outline
 *
 * Robustness
 * ----------
 *   - LottieAnimationView is main-thread-only. A debug-mode warning is
 *     logged if a public method is called off-thread.
 *   - Every scheme role is individually validated. Any role that comes
 *     back TRANSPARENT or otherwise invalid falls back to the static
 *     theme for that specific role, so a single bad value cannot blank
 *     out a layer.
 *   - ColorUtils.blendARGB failures fall back to the input color.
 *   - isNightMode failure defaults to light theme.
 *   - Every public method is wrapped in try/catch and logs on failure.
 *   - applyRoles validates the incoming Roles before touching the view.
 */
public final class LottieThemeEngine {

    private static final String TAG = "LottieThemeEngine";

    private LottieThemeEngine() {}

    // ---- Fallback defaults (Material 3 baseline) ----

    private static final int DEFAULT_PRIMARY_LIGHT = 0xFF6750A4;
    private static final int DEFAULT_PRIMARY_DARK  = 0xFFD0BCFF;

    private static final int DEFAULT_PRIMARY_CONTAINER_LIGHT = 0xFFEADDFF;
    private static final int DEFAULT_PRIMARY_CONTAINER_DARK  = 0xFF4F378B;

    private static final int DEFAULT_SECONDARY_LIGHT = 0xFF625B71;
    private static final int DEFAULT_SECONDARY_DARK  = 0xFFCCC2DC;

    private static final int DEFAULT_SECONDARY_CONTAINER_LIGHT = 0xFFE8DEF8;
    private static final int DEFAULT_SECONDARY_CONTAINER_DARK  = 0xFF4A4458;

    private static final int DEFAULT_ON_PRIMARY_LIGHT = 0xFFFFFFFF;
    private static final int DEFAULT_ON_PRIMARY_DARK  = 0xFF381E72;

    private static final int DEFAULT_OUTLINE_LIGHT = 0xFF79747E;
    private static final int DEFAULT_OUTLINE_DARK  = 0xFF938F99;

    private static final int DEFAULT_SURFACE_LIGHT = 0xFFFFFBFE;
    private static final int DEFAULT_SURFACE_DARK  = 0xFF1C1B1F;

    // =================================================================
    // Roles
    // =================================================================

    public static final class Roles {

        public final int head;         // Layer 1
        public final int headAccent;   // Layer 2 & 3 (forehead / chin plates)
        public final int secondary;    // Layer 4, 5, 6
        public final int eyes;         // Layer 7 & 8
        public final int outline;      // all strokes

        public Roles(
                @ColorInt int head,
                @ColorInt int headAccent,
                @ColorInt int secondary,
                @ColorInt int eyes,
                @ColorInt int outline) {

            this.head = head;
            this.headAccent = headAccent;
            this.secondary = secondary;
            this.eyes = eyes;
            this.outline = outline;
        }
    }

    // =================================================================
    // Role sources
    // =================================================================

    @NonNull
    public static Roles readStaticRoles(@NonNull Context context) {

        try {
            boolean dark = safeIsNightMode(context);

            return buildRoles(
                    dark,
                    safeThemeColor(context,
                            com.google.android.material.R.attr.colorPrimary,
                            dark ? DEFAULT_PRIMARY_DARK : DEFAULT_PRIMARY_LIGHT),
                    safeThemeColor(context,
                            com.google.android.material.R.attr.colorPrimaryContainer,
                            dark ? DEFAULT_PRIMARY_CONTAINER_DARK
                                 : DEFAULT_PRIMARY_CONTAINER_LIGHT),
                    safeThemeColor(context,
                            com.google.android.material.R.attr.colorSecondary,
                            dark ? DEFAULT_SECONDARY_DARK : DEFAULT_SECONDARY_LIGHT),
                    safeThemeColor(context,
                            com.google.android.material.R.attr.colorSecondaryContainer,
                            dark ? DEFAULT_SECONDARY_CONTAINER_DARK
                                 : DEFAULT_SECONDARY_CONTAINER_LIGHT),
                    safeThemeColor(context,
                            com.google.android.material.R.attr.colorOnPrimary,
                            dark ? DEFAULT_ON_PRIMARY_DARK : DEFAULT_ON_PRIMARY_LIGHT),
                    safeThemeColor(context,
                            com.google.android.material.R.attr.colorOutline,
                            dark ? DEFAULT_OUTLINE_DARK : DEFAULT_OUTLINE_LIGHT),
                    safeThemeColor(context,
                            com.google.android.material.R.attr.colorSurface,
                            dark ? DEFAULT_SURFACE_DARK : DEFAULT_SURFACE_LIGHT)
            );

        } catch (Throwable t) {
            Log.e(TAG, "readStaticRoles failed, using hardcoded defaults", t);
            return hardcodedRoles(false);
        }
    }

    /**
     * Reads every role the mascot needs from a DynamicScheme produced
     * by ArtworkColorHelper.createScheme(...).
     *
     * Each scheme role is individually validated: if a scheme returns
     * TRANSPARENT (or is otherwise unusable) for a particular role, that
     * role falls back to the corresponding STATIC theme value rather
     * than blanking out the layer.
     *
     * If scheme is null, the static theme is used entirely.
     */
    @NonNull
    public static Roles readSchemeRoles(
            @NonNull Context fallbackContext,
            @Nullable DynamicScheme scheme) {

        try {
            if (scheme == null) {
                return readStaticRoles(fallbackContext);
            }

            // Static roles double as per-role fallbacks.
            boolean dark = safeIsNightMode(fallbackContext);
            Roles staticRoles = readStaticRoles(fallbackContext);

            return buildRoles(
                    scheme.isDark,
                    sanitizeRole(scheme.getPrimary(), staticRoles.head),
                    sanitizeRole(scheme.getPrimaryContainer(), staticRoles.headAccent),
                    sanitizeRole(scheme.getSecondary(), staticRoles.secondary),
                    sanitizeRole(scheme.getSecondaryContainer(), staticRoles.secondary),
                    sanitizeRole(scheme.getOnPrimary(), staticRoles.eyes),
                    sanitizeRole(scheme.getOutline(), staticRoles.outline),
                    sanitizeRole(scheme.getSurface(), staticRoles.eyes)
            );

        } catch (Throwable t) {
            Log.e(TAG, "readSchemeRoles failed, falling back to static", t);
            return readStaticRoles(fallbackContext);
        }
    }

    // =================================================================
    // Theme-aware mapping
    // =================================================================

    @NonNull
    private static Roles buildRoles(
            boolean dark,
            int primary,
            int primaryContainer,
            int secondary,
            int secondaryContainer,
            int onPrimary,
            int outline,
            int surface) {

        try {
            if (dark) {
                return new Roles(
                        primary,
                        primaryContainer,
                        secondary,
                        onPrimary,
                        outline
                );
            }

            // Light theme: soften the accents and use near-white eyes.
            return new Roles(
                    // Head = primary directly, matching the static-theme
                    // Total-Apps baseline.
                    primary,

                    // Forehead / chin ~ tone 92.
                    safeBlend(primaryContainer, surface, 0.25f),

                    // Antenna / ears / cheeks softened below the head.
                    safeBlend(secondary, secondaryContainer, 0.45f),

                    // Eyes near-white for strong contrast on any hue.
                    surface,

                    outline
            );

        } catch (Throwable t) {
            Log.e(TAG, "buildRoles failed, using hardcoded defaults", t);
            return hardcodedRoles(dark);
        }
    }

    // =================================================================
    // Application
    // =================================================================

    public static void applyRoles(
            @NonNull LottieAnimationView animationView,
            @NonNull Roles roles) {

        warnIfNotMainThread("applyRoles");

        try {
            // Bind head.
            bindFillColor(animationView, "Layer 1", roles.head);

            // Forehead / chin plates.
            bindFillColor(animationView, "Layer 2", roles.headAccent);
            bindFillColor(animationView, "Layer 3", roles.headAccent);

            // Antenna / ears / cheeks.
            bindFillColor(animationView, "Layer 4", roles.secondary);
            bindFillColor(animationView, "Layer 5", roles.secondary);
            bindFillColor(animationView, "Layer 6", roles.secondary);

            // Eyes / pupils.
            bindFillColor(animationView, "Layer 7", roles.eyes);
            bindFillColor(animationView, "Layer 8", roles.eyes);

            // All strokes.
            bindStrokeColor(animationView, "**", roles.outline);

            // Optional named overrides, ignored if the layers don't exist.
            bindFillColor(animationView, "Forehead", roles.headAccent);
            bindFillColor(animationView, "Chin", roles.headAccent);

        } catch (Throwable t) {
            Log.e(TAG, "applyRoles failed", t);
        }
    }

    public static void applyMaterialYouColors(
            @NonNull LottieAnimationView animationView) {

        warnIfNotMainThread("applyMaterialYouColors");

        try {
            Context ctx = null;
            try {
                ctx = animationView.getContext();
            } catch (Throwable t) {
                Log.e(TAG, "getContext failed", t);
            }

            if (ctx == null) {
                Log.w(TAG, "applyMaterialYouColors: context null, skipping");
                return;
            }

            applyRoles(animationView, readStaticRoles(ctx));

        } catch (Throwable t) {
            Log.e(TAG, "applyMaterialYouColors failed", t);
        }
    }

    // =================================================================
    // Binders
    // =================================================================

    public static void bindFillColor(
            @NonNull LottieAnimationView animationView,
            @NonNull String layerName,
            @ColorInt final int targetColor) {

        try {
            animationView.addValueCallback(
                    new KeyPath(layerName, "**", "Fill 1"),
                    LottieProperty.COLOR,
                    new LottieValueCallback<Integer>() {
                        @Override
                        public Integer getValue(LottieFrameInfo<Integer> frameInfo) {
                            return targetColor;
                        }
                    }
            );
        } catch (Throwable t) {
            Log.e(TAG, "bindFillColor failed for layer " + layerName, t);
        }
    }

    public static void bindStrokeColor(
            @NonNull LottieAnimationView animationView,
            @NonNull String keyPathPattern,
            @ColorInt final int targetColor) {

        try {
            animationView.addValueCallback(
                    new KeyPath(keyPathPattern, "Stroke 1"),
                    LottieProperty.COLOR,
                    new LottieValueCallback<Integer>() {
                        @Override
                        public Integer getValue(LottieFrameInfo<Integer> frameInfo) {
                            return targetColor;
                        }
                    }
            );
        } catch (Throwable t) {
            Log.e(TAG, "bindStrokeColor failed for " + keyPathPattern, t);
        }
    }

    // =================================================================
    // Safe helpers
    // =================================================================

    /**
     * Returns candidate only if it is a usable (non-transparent) color.
     * Otherwise returns fallback. Guards against a DynamicScheme
     * returning TRANSPARENT for a role that should always be visible.
     */
    private static int sanitizeRole(int candidate, int fallback) {
        if (candidate == Color.TRANSPARENT) return fallback;
        // A fully-transparent alpha channel is unusable for any visible
        // role; Lottie would render the layer invisible.
        if (((candidate >>> 24) & 0xFF) == 0) return fallback;
        return candidate;
    }

    private static int safeBlend(int from, int to, float amount) {
        try {
            return ColorUtils.blendARGB(from, to, amount);
        } catch (Throwable t) {
            // Fall back to the source color (no blend).
            return from;
        }
    }

    private static int safeThemeColor(
            @NonNull Context context,
            int attrResId,
            int fallback) {

        try {
            return MaterialColors.getColor(context, attrResId, fallback);
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static boolean safeIsNightMode(@NonNull Context context) {
        try {
            int flags = context.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK;
            return flags == Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            // Safer default: light-theme mapping is more forgiving
            // than dark-theme mapping if the configuration is broken.
            return false;
        }
    }

    /**
     * Absolute last-resort Roles. Uses only hardcoded constants, so it
     * cannot fail. Called only if both scheme and static-theme lookups
     * have already failed.
     */
    @NonNull
    private static Roles hardcodedRoles(boolean dark) {
        return new Roles(
                dark ? DEFAULT_PRIMARY_DARK : DEFAULT_PRIMARY_LIGHT,
                dark ? DEFAULT_PRIMARY_CONTAINER_DARK
                     : DEFAULT_PRIMARY_CONTAINER_LIGHT,
                dark ? DEFAULT_SECONDARY_DARK : DEFAULT_SECONDARY_LIGHT,
                dark ? DEFAULT_ON_PRIMARY_DARK : DEFAULT_ON_PRIMARY_LIGHT,
                dark ? DEFAULT_OUTLINE_DARK : DEFAULT_OUTLINE_LIGHT
        );
    }

    private static void warnIfNotMainThread(@NonNull String methodName) {
        try {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                Log.w(TAG, methodName + " called off the main thread. "
                        + "LottieAnimationView is main-thread-only; the "
                        + "call will proceed but may cause crashes or "
                        + "corrupted view state.");
            }
        } catch (Throwable ignored) {
            // Logging must never break the pipeline.
        }
    }
}