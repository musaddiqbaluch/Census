package com.musaddiqbaluch.census;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.tabs.TabLayout;

import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

/**
 * Syncs a toolbar's background (and the status bar behind it on
 * Android 14 and below) with the horizontal scroll state of a TabLayout.
 *
 * Behaviour
 * ---------
 *   Not scrolled -> colorSurface
 *   Scrolled     -> colorSurface elevated 3 dp with colorPrimary
 *                   (Material 3 surfaceColorAtElevation)
 *
 * Status bar
 * ----------
 *   Android 15+ (API 35) is edge-to-edge: the status bar is transparent
 *   and the toolbar shows through it, so nothing needs to be done.
 *   Below API 35 the status bar is an opaque layer painted with
 *   Window.statusBarColor, so this helper mirrors the toolbar colour onto
 *   it (every animation frame) and flips the status bar icon tint
 *   (light/dark) according to the colour's luminance. The original
 *   colour and icon tint are restored on detach().
 */
public final class MaterialToolbarScrollHelper {

    private static final String TAG = "ToolbarScrollHelper";

    private static final long COLOR_CHANGE_DURATION = 250L;
    private static final float DECELERATE_FACTOR = 1.4f;
    private static final float TONAL_ELEVATION_DP = 3f;

    private static final int FALLBACK_SURFACE_LIGHT = 0xFFFFFBFE;
    private static final int FALLBACK_SURFACE_DARK  = 0xFF1C1B1F;
    private static final int FALLBACK_PRIMARY_LIGHT = 0xFF6750A4;
    private static final int FALLBACK_PRIMARY_DARK  = 0xFFD0BCFF;

    private static final WeakHashMap<View, MaterialToolbarScrollHelper>
            INSTANCES = new WeakHashMap<>();
    private static final Object LOCK = new Object();

    private final Context context;
    private final View view;
    private final TabLayout tabLayout;

    @Nullable private final Drawable originalBackground;

    @Nullable private GradientDrawable backgroundDrawable;
    @Nullable private ValueAnimator colorAnimator;

    // Status bar state
    @Nullable private WeakReference<Window> windowRef;
    private boolean statusBarManaged;
    private int originalStatusBarColor;
    private boolean originalLightStatusBars;
    private boolean originalStatusBarCaptured;
    private int lastAppliedLightStatusBars = -1; // -1 unknown, 0 false, 1 true

    private int surfaceColor;
    private int surfaceTintColor;
    private int elevatedSurfaceColor;
    private int currentBackgroundColor;

    private boolean isScrolled;
    private boolean attached;
    private boolean destroyed;

    // ------------------------------------------------------------------
    // Listener
    // ------------------------------------------------------------------

    private final ViewTreeObserver.OnScrollChangedListener scrollListener =
            new ViewTreeObserver.OnScrollChangedListener() {
                @Override
                public void onScrollChanged() {
                    try { updateScrollState(); }
                    catch (Throwable t) { Log.e(TAG, "scrollListener failed", t); }
                }
            };

    public MaterialToolbarScrollHelper(
            @NonNull Context context,
            @NonNull View view,
            @NonNull TabLayout tabLayout) {

        this.context = context;
        this.view = view;
        this.tabLayout = tabLayout;

        Drawable original = null;
        try { original = view.getBackground(); }
        catch (Throwable t) { Log.e(TAG, "Failed to read original background", t); }
        this.originalBackground = original;
    }

    // ============================================================
    // Public API
    // ============================================================

    public void attach() {
        if (attached || destroyed) return;

        try {
            attached = true;

            resolveWindow();
            captureOriginalStatusBar();
            resolveColors();
            resolveInitialScrollState();
            createBackground();
            applyStatusBar(currentBackgroundColor);
            installScrollListener();

            // Critical: re-check scroll state AFTER layout.
            view.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (!attached || destroyed) return;
                        updateScrollState();
                        applyCurrentColorImmediately();
                    } catch (Throwable t) {
                        Log.e(TAG, "deferred post failed", t);
                    }
                }
            });

        } catch (Throwable t) {
            Log.e(TAG, "attach failed", t);
        }
    }

    /**
     * Call this after a theme change (night-mode switch, dynamic colour,
     * etc.) when the Activity is NOT recreated. Re-resolves every colour
     * and immediately applies the colour that matches the current scroll
     * state (toolbar and status bar).
     */
    public void refresh() {
        if (!attached || destroyed) return;

        try {
            cancelColorAnimation();
            resolveColors();
            // Keep the existing isScrolled flag - do not reset it.
            lastAppliedLightStatusBars = -1;
            applyCurrentColorImmediately();
        } catch (Throwable t) {
            Log.e(TAG, "refresh failed", t);
        }
    }

    public void detach() {
        if (!attached) return;

        try {
            attached = false;
            cancelColorAnimation();

            try {
                ViewTreeObserver observer = tabLayout.getViewTreeObserver();
                if (observer != null && observer.isAlive()) {
                    observer.removeOnScrollChangedListener(scrollListener);
                }
            } catch (Throwable t) {
                Log.e(TAG, "remove scroll listener failed", t);
            }

            try { view.setBackground(originalBackground); }
            catch (Throwable t) { Log.e(TAG, "restore background failed", t); }

            restoreStatusBar();

            backgroundDrawable = null;

        } catch (Throwable t) {
            Log.e(TAG, "detach failed", t);
        }
    }

    public void destroy() {
        if (destroyed) return;
        destroyed = true;
        try { detach(); } catch (Throwable ignored) {}
    }

    // ============================================================
    // Colour resolution (can be called on theme change)
    // ============================================================

    private void resolveColors() {
        boolean dark = safeIsNightMode();

        surfaceColor = safeThemeColor(
                com.google.android.material.R.attr.colorSurface,
                dark ? FALLBACK_SURFACE_DARK : FALLBACK_SURFACE_LIGHT);

        surfaceTintColor = safeThemeColor(
                com.google.android.material.R.attr.colorPrimary,
                dark ? FALLBACK_PRIMARY_DARK : FALLBACK_PRIMARY_LIGHT);

        try {
            elevatedSurfaceColor = surfaceColorAtElevation(
                    surfaceColor, surfaceTintColor, TONAL_ELEVATION_DP);
        } catch (Throwable t) {
            Log.e(TAG, "surfaceColorAtElevation failed", t);
            elevatedSurfaceColor = surfaceColor;
        }
    }

    private void resolveInitialScrollState() {
        try {
            isScrolled = tabLayout.canScrollHorizontally(-1);
        } catch (Throwable t) {
            isScrolled = false;
        }
        currentBackgroundColor = getTargetColor();
    }

    private void applyCurrentColorImmediately() {
        int target = getTargetColor();
        currentBackgroundColor = target;

        try {
            if (backgroundDrawable != null) {
                backgroundDrawable.setColor(target);
            }
        } catch (Throwable t) {
            Log.e(TAG, "applyCurrentColorImmediately failed", t);
        }

        applyStatusBar(target);
    }

    // ============================================================
    // Status bar
    // ============================================================

    /** Edge-to-edge is enforced from API 35, the status bar is transparent there. */
    private boolean needsStatusBarSync() {
        return Build.VERSION.SDK_INT < 35;
    }

    private void resolveWindow() {
        if (!needsStatusBarSync()) return;
        try {
            Context c = view.getContext();
            if (c == null) c = context;
            while (c instanceof ContextWrapper) {
                if (c instanceof Activity) {
                    Window w = ((Activity) c).getWindow();
                    if (w != null) windowRef = new WeakReference<>(w);
                    return;
                }
                c = ((ContextWrapper) c).getBaseContext();
            }
        } catch (Throwable t) {
            Log.e(TAG, "resolveWindow failed", t);
        }
    }

    @Nullable
    private Window getWindow() {
        return windowRef == null ? null : windowRef.get();
    }

    @SuppressWarnings("deprecation")
    private void captureOriginalStatusBar() {
        if (!needsStatusBarSync() || originalStatusBarCaptured) return;
        Window window = getWindow();
        if (window == null) return;

        try {
            originalStatusBarColor = window.getStatusBarColor();
            WindowInsetsControllerCompat controller =
                    WindowCompat.getInsetsController(window, window.getDecorView());
            originalLightStatusBars = controller != null
                    && controller.isAppearanceLightStatusBars();
            originalStatusBarCaptured = true;
        } catch (Throwable t) {
            Log.e(TAG, "captureOriginalStatusBar failed", t);
        }
    }

    @SuppressWarnings("deprecation")
    private void applyStatusBar(int color) {
        if (!needsStatusBarSync()) return;
        Window window = getWindow();
        if (window == null) return;

        try {
            // Make sure the window is allowed to paint the status bar colour.
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);

            // Opaque colour: the toolbar fill is always opaque.
            window.setStatusBarColor(color | 0xFF000000);
            statusBarManaged = true;

            boolean lightBackground = ColorUtils.calculateLuminance(color) > 0.5;
            int flag = lightBackground ? 1 : 0;
            if (flag != lastAppliedLightStatusBars) {
                lastAppliedLightStatusBars = flag;
                WindowInsetsControllerCompat controller =
                        WindowCompat.getInsetsController(window, window.getDecorView());
                if (controller != null) {
                    // Dark icons on light background, light icons on dark.
                    controller.setAppearanceLightStatusBars(lightBackground);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "applyStatusBar failed", t);
        }
    }

    @SuppressWarnings("deprecation")
    private void restoreStatusBar() {
        if (!needsStatusBarSync() || !statusBarManaged || !originalStatusBarCaptured) return;
        Window window = getWindow();
        if (window == null) return;

        try {
            window.setStatusBarColor(originalStatusBarColor);
            WindowInsetsControllerCompat controller =
                    WindowCompat.getInsetsController(window, window.getDecorView());
            if (controller != null) {
                controller.setAppearanceLightStatusBars(originalLightStatusBars);
            }
        } catch (Throwable t) {
            Log.e(TAG, "restoreStatusBar failed", t);
        } finally {
            statusBarManaged = false;
            lastAppliedLightStatusBars = -1;
        }
    }

    // ============================================================
    // Background visual
    // ============================================================

    private void createBackground() {
        try {
            backgroundDrawable = new GradientDrawable();
            backgroundDrawable.setShape(GradientDrawable.RECTANGLE);
            backgroundDrawable.setColor(currentBackgroundColor);
            view.setBackground(backgroundDrawable);
        } catch (Throwable t) {
            Log.e(TAG, "GradientDrawable creation failed", t);
            backgroundDrawable = null;
        }
    }

    private void installScrollListener() {
        try {
            ViewTreeObserver observer = tabLayout.getViewTreeObserver();
            if (observer == null) return;

            if (!observer.isAlive()) {
                tabLayout.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (!attached || destroyed) return;
                            ViewTreeObserver o = tabLayout.getViewTreeObserver();
                            if (o != null && o.isAlive()) {
                                o.addOnScrollChangedListener(scrollListener);
                            }
                        } catch (Throwable t) {
                            Log.e(TAG, "deferred scroll listener failed", t);
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
        final int startColor = currentBackgroundColor;
        final int targetColor = getTargetColor();

        cancelColorAnimation();

        try {
            final ValueAnimator animator = ValueAnimator.ofObject(
                    new ArgbEvaluator(), startColor, targetColor);
            animator.setDuration(COLOR_CHANGE_DURATION);
            animator.setInterpolator(new DecelerateInterpolator(DECELERATE_FACTOR));

            animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    try {
                        if (!attached || destroyed) return;
                        Object value = animation.getAnimatedValue();
                        if (!(value instanceof Integer)) return;

                        int color = (Integer) value;
                        currentBackgroundColor = color;

                        if (backgroundDrawable != null) {
                            backgroundDrawable.setColor(color);
                        }
                        applyStatusBar(color);
                    } catch (Throwable t) {
                        Log.e(TAG, "color animator frame failed", t);
                    }
                }
            });

            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator a) {
                    if (colorAnimator == animator) colorAnimator = null;
                }
                @Override
                public void onAnimationCancel(Animator a) {
                    if (colorAnimator == animator) colorAnimator = null;
                }
            });

            colorAnimator = animator;
            animator.start();

        } catch (Throwable t) {
            Log.e(TAG, "animateBackground failed, snapping to target", t);
            applyCurrentColorImmediately();
        }
    }

    private int getTargetColor() {
        return isScrolled ? elevatedSurfaceColor : surfaceColor;
    }

    // ============================================================
    // Utilities
    // ============================================================

    private static int surfaceColorAtElevation(
            int surface, int surfaceTint, float elevationDp) {
        if (elevationDp <= 0f) return surface;
        float alpha = ((4.5f * (float) Math.log(elevationDp + 1f)) + 2f) / 100f;
        float inv = 1f - alpha;
        float r = Color.red(surfaceTint)   * alpha + Color.red(surface)   * inv;
        float g = Color.green(surfaceTint) * alpha + Color.green(surface) * inv;
        float b = Color.blue(surfaceTint)  * alpha + Color.blue(surface)  * inv;
        return Color.rgb(
                clamp((int) (r + 0.5f)),
                clamp((int) (g + 0.5f)),
                clamp((int) (b + 0.5f)));
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private void cancelColorAnimation() {
        try {
            if (colorAnimator != null) {
                colorAnimator.cancel();
                colorAnimator = null;
            }
        } catch (Throwable ignored) {}
    }

    private int safeThemeColor(int attr, int fallback) {
        try {
            return MaterialColors.getColor(view, attr, fallback);
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

    // ============================================================
    // Static registry
    // ============================================================

    public static void attach(
            @NonNull Context context,
            @NonNull View view,
            @NonNull TabLayout tabLayout) {

        try {
            MaterialToolbarScrollHelper helper =
                    new MaterialToolbarScrollHelper(context, view, tabLayout);

            MaterialToolbarScrollHelper old;
            synchronized (LOCK) {
                old = INSTANCES.put(view, helper);
            }
            if (old != null) {
                try { old.destroy(); }
                catch (Throwable t) { Log.e(TAG, "old destroy failed", t); }
            }

            helper.attach();

        } catch (Throwable t) {
            Log.e(TAG, "attach failed", t);
        }
    }

    /**
     * Convenience for theme-change paths that keep the same view
     * instance. Calls refresh() on the live helper if one exists.
     */
    public static void refresh(@NonNull View view) {
        try {
            MaterialToolbarScrollHelper helper;
            synchronized (LOCK) {
                helper = INSTANCES.get(view);
            }
            if (helper != null) {
                helper.refresh();
            }
        } catch (Throwable t) {
            Log.e(TAG, "refresh failed", t);
        }
    }

    public static void detach(@NonNull View view) {
        try {
            MaterialToolbarScrollHelper helper;
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