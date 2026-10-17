package com.musaddiqbaluch.census;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.tabs.TabLayout;

import java.util.WeakHashMap;

/**
 * Pill-shaped settings container with a Material 3 tonal-elevation
 * crossfade and an onSurface ripple.
 *
 * Behaviour
 * ---------
 *   - The container background crossfades between colorSurface (not
 *     scrolled) and a tonal-elevated surface (scrolled), synchronised
 *     with the TabLayout's horizontal scroll state.
 *   - The shape is a full pill, matching the container height.
 *   - The ripple uses colorOnSurface at 8% alpha, applied by a
 *     RippleDrawable whose mask clips it to the pill's rounded corners.
 *
 * Robustness
 * ----------
 *   - Public methods are safe to call from any thread; the instance
 *     registry is synchronized.
 *   - Every listener and animator callback is wrapped in try/catch.
 *   - A broken theme falls back to hardcoded M3 defaults.
 *   - RippleDrawable construction failure falls back to a plain
 *     GradientDrawable.
 *   - detach() is idempotent; every cleanup step is independently
 *     wrapped.
 *   - destroy() short-circuits every subsequent public call.
 *   - Sticky-ripple protection: delayed force-clear only when the
 *     pressed state is still true after the normal event sequence
 *     (never interferes with click or normal ripple).
 */
public final class MaterialSettingsRipple {

    private static final String TAG = "MaterialSettingsRipple";

    private static final long COLOR_CHANGE_DURATION = 250L;
    private static final float DECELERATE_FACTOR = 1.4f;

    /** M3 tonal elevation for the scrolled state. */
    private static final float TONAL_ELEVATION_DP = 3f;

    /** M3 standard pressed-state alpha on a surface. */
    private static final float RIPPLE_ALPHA = 0.08f;

    /**
     * Delay after UP/CANCEL before checking for a stuck pressed state.
     * Long enough for the framework to finish its normal exit path,
     * short enough that a real sticky ripple never becomes visible.
     */
    private static final long STICKY_CHECK_DELAY_MS = 80L;

    // Fallback M3 baseline, used only if the theme cannot be read.
    private static final int FALLBACK_SURFACE_LIGHT        = 0xFFFFFBFE;
    private static final int FALLBACK_SURFACE_DARK         = 0xFF1C1B1F;
    private static final int FALLBACK_PRIMARY_LIGHT        = 0xFF6750A4;
    private static final int FALLBACK_PRIMARY_DARK         = 0xFFD0BCFF;
    private static final int FALLBACK_ON_SURFACE_LIGHT     = 0xFF1D1B20;
    private static final int FALLBACK_ON_SURFACE_DARK      = 0xFFE6E0E9;

    private static final WeakHashMap<View, MaterialSettingsRipple>
            INSTANCES = new WeakHashMap<>();

    /** Guards every access to INSTANCES. */
    private static final Object LOCK = new Object();

    private final Context context;
    private final View view;
    private final TabLayout tabLayout;

    @Nullable
    private final Drawable originalBackground;

    @Nullable private GradientDrawable backgroundDrawable;
    @Nullable private GradientDrawable maskDrawable;
    @Nullable private RippleDrawable rippleDrawable;
    @Nullable private ValueAnimator colorAnimator;

    private int surfaceColor;
    private int surfaceTintColor;
    private int elevatedSurfaceColor;
    private int onSurfaceColor;
    private int currentBackgroundColor;

    private boolean isScrolled;
    private boolean attached;
    private boolean destroyed;

    // ------------------------------------------------------------------
    // Listeners (kept as fields so they can be cleanly removed)
    // ------------------------------------------------------------------

    private final ViewTreeObserver.OnScrollChangedListener scrollListener =
            new ViewTreeObserver.OnScrollChangedListener() {
                @Override
                public void onScrollChanged() {
                    try {
                        updateScrollState();
                    } catch (Throwable t) {
                        Log.e(TAG, "scrollListener failed", t);
                    }
                }
            };

    private final View.OnLayoutChangeListener layoutChangeListener =
            new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int left, int top,
                                           int right, int bottom,
                                           int oldLeft, int oldTop,
                                           int oldRight, int oldBottom) {
                    try {
                        if (!attached || destroyed) return;
                        updatePillShape();
                    } catch (Throwable t) {
                        Log.e(TAG, "layoutChangeListener failed", t);
                    }
                }
            };

    /**
     * Never consumes the event and never touches the pressed state
     * on the current event.  Only schedules a delayed check that
     * forces a clear when the pressed state is still true after the
     * normal framework processing has finished (the real sticky case).
     * This leaves OnClickListener and the normal ripple path completely
     * untouched.
     */
    private final View.OnTouchListener stickyRippleGuard =
            new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    try {
                        final int action = event.getActionMasked();
                        if (action == MotionEvent.ACTION_UP
                                || action == MotionEvent.ACTION_CANCEL) {

                            v.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        if (!attached || destroyed) return;

                                        // Only act when the state is
                                        // genuinely stuck.
                                        if (v.isPressed()) {
                                            v.setPressed(false);
                                            v.jumpDrawablesToCurrentState();
                                        }
                                    } catch (Throwable t) {
                                        Log.e(TAG,
                                                "stickyRippleGuard delayed failed",
                                                t);
                                    }
                                }
                            }, STICKY_CHECK_DELAY_MS);
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "stickyRippleGuard failed", t);
                    }
                    // Critical: never consume the event.
                    return false;
                }
            };

    public MaterialSettingsRipple(
            @NonNull Context context,
            @NonNull View view,
            @NonNull TabLayout tabLayout) {

        this.context = context;
        this.view = view;
        this.tabLayout = tabLayout;

        Drawable original = null;
        try {
            original = view.getBackground();
        } catch (Throwable t) {
            Log.e(TAG, "Failed to read original background", t);
        }
        this.originalBackground = original;
    }

    // ============================================================
    // Public instance API
    // ============================================================

    public void attach() {

        if (attached || destroyed) return;

        try {
            attached = true;

            // ---- Resolve theme colors (with fallbacks) ----
            boolean dark = safeIsNightMode();

            surfaceColor = safeThemeColor(
                    com.google.android.material.R.attr.colorSurface,
                    dark ? FALLBACK_SURFACE_DARK : FALLBACK_SURFACE_LIGHT);

            surfaceTintColor = safeThemeColor(
                    com.google.android.material.R.attr.colorPrimary,
                    dark ? FALLBACK_PRIMARY_DARK : FALLBACK_PRIMARY_LIGHT);

            onSurfaceColor = safeThemeColor(
                    com.google.android.material.R.attr.colorOnSurface,
                    dark ? FALLBACK_ON_SURFACE_DARK : FALLBACK_ON_SURFACE_LIGHT);

            // ---- Tonal elevation ----
            elevatedSurfaceColor = surfaceColorAtElevation(
                    surfaceColor, surfaceTintColor, TONAL_ELEVATION_DP);

            // ---- Scroll state ----
            try {
                isScrolled = tabLayout.canScrollHorizontally(-1);
            } catch (Throwable t) {
                Log.e(TAG, "canScrollHorizontally failed", t);
                isScrolled = false;
            }

            currentBackgroundColor = getTargetBackgroundColor();

            // ---- Visuals ----
            try { createRippleBackground(); }
            catch (Throwable t) { Log.e(TAG, "createRippleBackground failed", t); }

            try { updatePillShape(); }
            catch (Throwable t) { Log.e(TAG, "updatePillShape failed", t); }

            try { installScrollListener(); }
            catch (Throwable t) { Log.e(TAG, "installScrollListener failed", t); }

            try { installLayoutChangeListener(); }
            catch (Throwable t) { Log.e(TAG, "installLayoutChangeListener failed", t); }

            try { installStickyRippleGuard(); }
            catch (Throwable t) { Log.e(TAG, "installStickyRippleGuard failed", t); }

            // ---- Deferred: re-measure once the view has real size ----
            try {
                view.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (!attached || destroyed) return;
                            updatePillShape();
                            updateScrollState();
                        } catch (Throwable t) {
                            Log.e(TAG, "deferred post failed", t);
                        }
                    }
                });
            } catch (Throwable t) {
                Log.e(TAG, "view.post failed", t);
            }

        } catch (Throwable t) {
            Log.e(TAG, "attach failed", t);
        }
    }

    public void detach() {

        if (!attached) return;

        try {
            attached = false;

            // Force any residual ripple to finish.
            forceClearRippleState();

            try { cancelColorAnimation(); }
            catch (Throwable t) { Log.e(TAG, "cancelColorAnimation failed", t); }

            // Remove scroll listener.
            try {
                ViewTreeObserver observer = tabLayout.getViewTreeObserver();
                if (observer != null && observer.isAlive()) {
                    observer.removeOnScrollChangedListener(scrollListener);
                }
            } catch (Throwable t) {
                Log.e(TAG, "remove scroll listener failed", t);
            }

            // Remove layout-change listener.
            try {
                view.removeOnLayoutChangeListener(layoutChangeListener);
            } catch (Throwable t) {
                Log.e(TAG, "remove layoutChangeListener failed", t);
            }

            // Remove sticky-ripple guard.
            try {
                view.setOnTouchListener(null);
            } catch (Throwable t) {
                Log.e(TAG, "remove stickyRippleGuard failed", t);
            }

            // Restore original background.
            try {
                view.setBackground(originalBackground);
            } catch (Throwable t) {
                Log.e(TAG, "restore background failed", t);
            }

            backgroundDrawable = null;
            maskDrawable = null;
            rippleDrawable = null;

        } catch (Throwable t) {
            Log.e(TAG, "detach failed", t);
        }
    }

    /**
     * Permanently releases this instance. Idempotent. Every public
     * method becomes a no-op after destroy().
     */
    public void destroy() {
        if (destroyed) return;
        destroyed = true;
        try { detach(); } catch (Throwable ignored) {}
    }

    // ============================================================
    // Background construction
    // ============================================================

    private void createRippleBackground() {

        // ---- Base content layer ----
        try {
            backgroundDrawable = new GradientDrawable();
            backgroundDrawable.setShape(GradientDrawable.RECTANGLE);
            backgroundDrawable.setColor(currentBackgroundColor);
        } catch (Throwable t) {
            Log.e(TAG, "backgroundDrawable creation failed", t);
            backgroundDrawable = null;
            return;
        }

        // ---- Mask layer (clips the ripple to the pill) ----
        try {
            maskDrawable = new GradientDrawable();
            maskDrawable.setShape(GradientDrawable.RECTANGLE);
            maskDrawable.setColor(0xFFFFFFFF);
        } catch (Throwable t) {
            Log.e(TAG, "maskDrawable creation failed", t);
            maskDrawable = null;
        }

        // ---- Ripple color: onSurface at 8% alpha ----
        int rippleColor = onSurfaceColor;
        try {
            int alpha = Math.round(255 * RIPPLE_ALPHA);
            rippleColor = Color.argb(
                    alpha,
                    Color.red(onSurfaceColor),
                    Color.green(onSurfaceColor),
                    Color.blue(onSurfaceColor));
        } catch (Throwable t) {
            Log.e(TAG, "ripple color computation failed", t);
        }

        ColorStateList rippleCsl;
        try {
            rippleCsl = ColorStateList.valueOf(rippleColor);
        } catch (Throwable t) {
            Log.e(TAG, "ripple ColorStateList failed", t);
            rippleCsl = null;
        }

        // ---- RippleDrawable (with fallback to plain GradientDrawable) ----
        try {
            if (rippleCsl == null) {
                throw new IllegalStateException("null ripple CSL");
            }

            rippleDrawable = new RippleDrawable(
                    rippleCsl,
                    backgroundDrawable,
                    maskDrawable);

            view.setBackground(rippleDrawable);

        } catch (Throwable t) {
            Log.e(TAG,
                    "RippleDrawable creation failed; using plain "
                            + "GradientDrawable", t);

            rippleDrawable = null;

            try {
                view.setBackground(backgroundDrawable);
            } catch (Throwable t2) {
                Log.e(TAG, "view.setBackground failed", t2);
            }
        }

        // ---- Clickability ----
        try { view.setClickable(true); }
        catch (Throwable t) { Log.e(TAG, "setClickable failed", t); }

        try { view.setFocusable(true); }
        catch (Throwable t) { Log.e(TAG, "setFocusable failed", t); }
    }

    private void installScrollListener() {

        try {
            ViewTreeObserver observer = tabLayout.getViewTreeObserver();
            if (observer == null) return;

            if (!observer.isAlive()) {
                // Retry on the next layout pass.
                tabLayout.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (!attached || destroyed) return;
                            ViewTreeObserver o =
                                    tabLayout.getViewTreeObserver();
                            if (o != null && o.isAlive()) {
                                o.addOnScrollChangedListener(scrollListener);
                            }
                        } catch (Throwable t) {
                            Log.e(TAG,
                                    "deferred scroll listener failed", t);
                        }
                    }
                });
                return;
            }

            observer.addOnScrollChangedListener(scrollListener);

        } catch (Throwable t) {
            Log.e(TAG, "installScrollListener failed", t);
        }
    }

    private void installLayoutChangeListener() {
        try {
            view.addOnLayoutChangeListener(layoutChangeListener);
        } catch (Throwable t) {
            Log.e(TAG, "installLayoutChangeListener failed", t);
        }
    }

    private void installStickyRippleGuard() {
        try {
            view.setOnTouchListener(stickyRippleGuard);
        } catch (Throwable t) {
            Log.e(TAG, "installStickyRippleGuard failed", t);
        }
    }

    // ============================================================
    // Scroll state
    // ============================================================

    private void updateScrollState() {

        if (!attached || destroyed) return;

        try {
            boolean newState = tabLayout.canScrollHorizontally(-1);
            if (newState == isScrolled) return;

            isScrolled = newState;
            animateBackground();

        } catch (Throwable t) {
            Log.e(TAG, "updateScrollState failed", t);
        }
    }

    private void animateBackground() {

        final int start = currentBackgroundColor;
        final int target = getTargetBackgroundColor();

        cancelColorAnimation();

        try {
            final ValueAnimator animator = ValueAnimator.ofObject(
                    new ArgbEvaluator(), start, target);
            animator.setDuration(COLOR_CHANGE_DURATION);
            animator.setInterpolator(
                    new DecelerateInterpolator(DECELERATE_FACTOR));

            animator.addUpdateListener(
                    new ValueAnimator.AnimatorUpdateListener() {
                        @Override
                        public void onAnimationUpdate(ValueAnimator a) {
                            try {
                                if (!attached || destroyed) return;

                                Object value = a.getAnimatedValue();
                                if (!(value instanceof Integer)) return;

                                currentBackgroundColor = (Integer) value;

                                if (backgroundDrawable != null) {
                                    backgroundDrawable.setColor(
                                            currentBackgroundColor);
                                }

                                if (rippleDrawable != null) {
                                    rippleDrawable.invalidateSelf();
                                }
                            } catch (Throwable t) {
                                Log.e(TAG,
                                        "color animator frame failed", t);
                            }
                        }
                    });

            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator a) {
                    if (colorAnimator == animator) {
                        colorAnimator = null;
                    }
                    forceClearRippleStateIfNotPressed();
                }

                @Override
                public void onAnimationCancel(Animator a) {
                    if (colorAnimator == animator) {
                        colorAnimator = null;
                    }
                    forceClearRippleStateIfNotPressed();
                }
            });

            colorAnimator = animator;
            animator.start();

        } catch (Throwable t) {
            Log.e(TAG, "animateBackground failed, snapping to target", t);
            currentBackgroundColor = target;
            try {
                if (backgroundDrawable != null) {
                    backgroundDrawable.setColor(target);
                }
                if (rippleDrawable != null) {
                    rippleDrawable.invalidateSelf();
                }
            } catch (Throwable ignored) {}
            forceClearRippleStateIfNotPressed();
        }
    }

    private int getTargetBackgroundColor() {
        return isScrolled ? elevatedSurfaceColor : surfaceColor;
    }

    // ============================================================
    // Pill shape
    // ============================================================

    private void updatePillShape() {

        if (!attached || destroyed) return;

        if (backgroundDrawable == null && maskDrawable == null) return;

        try {
            int height = view.getHeight();
            if (height <= 0) return;

            float radius = height / 2f;

            if (backgroundDrawable != null) {
                backgroundDrawable.setCornerRadius(radius);
            }
            if (maskDrawable != null) {
                maskDrawable.setCornerRadius(radius);
            }
            if (rippleDrawable != null) {
                rippleDrawable.invalidateSelf();
            }

        } catch (Throwable t) {
            Log.e(TAG, "updatePillShape failed", t);
        }
    }

    // ============================================================
    // Sticky-ripple protection
    // ============================================================

    private void forceClearRippleState() {
        try {
            if (view.isPressed()) {
                view.setPressed(false);
            }
            view.jumpDrawablesToCurrentState();
        } catch (Throwable t) {
            Log.e(TAG, "forceClearRippleState failed", t);
        }
    }

    private void forceClearRippleStateIfNotPressed() {
        try {
            if (!attached || destroyed) return;
            if (!view.isPressed()) {
                view.jumpDrawablesToCurrentState();
            }
        } catch (Throwable t) {
            Log.e(TAG, "forceClearRippleStateIfNotPressed failed", t);
        }
    }

    // ============================================================
    // Tonal elevation
    // ============================================================

    /**
     * Exact Material 3 Compose algorithm:
     *
     *   alpha  = ((4.5 * ln(elevation + 1)) + 2) / 100
     *   result = surfaceTint.copy(alpha).compositeOver(surface)
     */
    private static int surfaceColorAtElevation(
            int surface,
            int surfaceTint,
            float elevationDp) {

        if (elevationDp <= 0f) return surface;

        try {
            float alpha =
                    ((4.5f * (float) Math.log(elevationDp + 1f)) + 2f) / 100f;
            float inverseAlpha = 1f - alpha;

            float r = (Color.red(surfaceTint)   * alpha)
                    + (Color.red(surface)   * inverseAlpha);
            float g = (Color.green(surfaceTint) * alpha)
                    + (Color.green(surface) * inverseAlpha);
            float b = (Color.blue(surfaceTint)  * alpha)
                    + (Color.blue(surface)  * inverseAlpha);

            return Color.rgb(
                    clampColorChannel((int) (r + 0.5f)),
                    clampColorChannel((int) (g + 0.5f)),
                    clampColorChannel((int) (b + 0.5f)));

        } catch (Throwable t) {
            Log.e(TAG, "surfaceColorAtElevation failed", t);
            return surface;
        }
    }

    private static int clampColorChannel(int value) {
        return Math.max(0, Math.min(255, value));
    }

    // ============================================================
    // Safe helpers
    // ============================================================

    private int safeThemeColor(int attrResId, int fallback) {
        try {
            return MaterialColors.getColor(view, attrResId, fallback);
        } catch (Throwable t) {
            return fallback;
        }
    }

    private boolean safeIsNightMode() {
        try {
            int flags = context.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return flags == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return false;
        }
    }

    private void cancelColorAnimation() {
        try {
            if (colorAnimator != null) {
                colorAnimator.cancel();
                colorAnimator = null;
            }
        } catch (Throwable t) {
            Log.e(TAG, "cancelColorAnimation failed", t);
        }
    }

    // ============================================================
    // Static registry
    // ============================================================

    public static void attach(
            @NonNull Context context,
            @NonNull View view,
            @NonNull TabLayout tabLayout) {

        try {
            MaterialSettingsRipple old;
            synchronized (LOCK) {
                old = INSTANCES.remove(view);
            }

            if (old != null) {
                try { old.destroy(); }
                catch (Throwable t) { Log.e(TAG, "old destroy failed", t); }
            }

            MaterialSettingsRipple helper =
                    new MaterialSettingsRipple(context, view, tabLayout);

            synchronized (LOCK) {
                INSTANCES.put(view, helper);
            }

            helper.attach();

        } catch (Throwable t) {
            Log.e(TAG, "attach failed", t);
        }
    }

    public static void attach(
            @NonNull View view,
            @NonNull TabLayout tabLayout) {

        try {
            attach(view.getContext(), view, tabLayout);
        } catch (Throwable t) {
            Log.e(TAG, "attach(view, tabLayout) failed", t);
        }
    }

    public static void detach(@NonNull View view) {

        try {
            MaterialSettingsRipple helper;
            synchronized (LOCK) {
                helper = INSTANCES.remove(view);
            }

            if (helper != null) {
                helper.destroy();
            }

        } catch (Throwable t) {
            Log.e(TAG, "detach failed", t);
        }
    }
}