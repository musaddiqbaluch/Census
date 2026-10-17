package com.musaddiqbaluch.census;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Soft glow + expanding 12-sided cookie rings drawn behind the cookie.
 * Place it as the first child of raderEffectLayout (160dp) so the
 * 100dp cookie sits on top of it.
 *
 * Ring color and glow color are set independently:
 *   setRingColor(int) -> rings (indicator color)
 *   setGlowColor(int) -> background glow (usually the same indicator color,
 *                       drawn as a low-alpha state layer so luminance is
 *                       set by alpha rather than by a per-hue tone)
 *
 * Thread-safe: both setters may be called from any thread.
 */
public class CookieRippleView extends View {

    private static final int RING_COUNT = 3;

    // Cookie size / this view's size (100dp / 160dp). Rings start at the
    // cookie's size and grow until they reach this view's edge.
    private static final float BASE_FRACTION = 100f / 160f;

    private static final long CYCLE_MS = 2800L;
    private static final float STROKE_DP = 2f;
    private static final float MAX_ALPHA = 0.55f;
    private static final float FADE_IN_END = 0.08f;

    // ---- Glow ----
    // M3 state-layer opacity: primary drawn over the surface at 16% at its
    // strongest (the spec's "dragged" layer), breathing down to about 10%
    // (the "focus/pressed" layer). Because lightness comes from the alpha,
    // every installer color gets a tint of the same strength.
    private static final float GLOW_ALPHA = 0.16f;
    private static final float GLOW_BREATHE = 0.40f;

    private final Cookie12SidedDrawable ring = new Cookie12SidedDrawable(Color.GRAY);
    private final Rect reusableBounds = new Rect();

    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    @Nullable private ValueAnimator animator;

    // Only ever touched on the main thread (the posted runnable and onDraw
    // both run on main), so no synchronization is required for these.
    private float progress = 0f;
    private boolean ringVisible = true;

    // Opaque RGB of the current glow color.
    private int glowRgb = Color.GRAY;
    // The gradient is rebuilt lazily in onDraw when the color or size changes.
    private boolean glowDirty = true;

    // Tracks whether this view is both attached AND visible so we don't
    // start two animators in a row when the framework calls both
    // onAttachedToWindow() and onWindowVisibilityChanged().
    private boolean shouldAnimate = false;

    public CookieRippleView(Context context) { super(context); init(); }
    public CookieRippleView(Context context, @Nullable AttributeSet attrs) { super(context, attrs); init(); }
    public CookieRippleView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr); init();
    }

    private void init() {
        try {
            float density = getResources().getDisplayMetrics().density;
            ring.setStrokeWidth(STROKE_DP * density);
            ring.setAlpha(0);
            glowPaint.setStyle(Paint.Style.FILL);
            setWillNotDraw(false);
        } catch (Throwable ignored) {
            // Never let construction throw -- a broken ring is worse than no ring.
        }
    }

    /**
     * Sets the ring color. Called by MaterialDistributionBar on every
     * color-crossfade frame. Safe to call from any thread.
     * A fully transparent color hides rings and glow (used while the
     * installer color is still being resolved).
     */
    public void setRingColor(@ColorInt final int color) {
        final Runnable apply = () -> {
            try {
                if (Color.alpha(color) == 0) {
                    ringVisible = false;
                } else {
                    ringVisible = true;
                    // Force fully-opaque fill; alpha is controlled per-ring in onDraw.
                    ring.setFillColor(color | 0xFF000000);
                }
                postInvalidateOnAnimation();
            } catch (Throwable ignored) { }
        };

        if (Looper.myLooper() == Looper.getMainLooper()) {
            apply.run();
        } else {
            post(apply);
        }
    }

    /**
     * Sets the glow color independently of the rings.
     * Safe to call from any thread.
     */
    public void setGlowColor(@ColorInt final int color) {
        final Runnable apply = () -> {
            try {
                glowRgb = color & 0x00FFFFFF;
                glowDirty = true;
                postInvalidateOnAnimation();
            } catch (Throwable ignored) { }
        };

        if (Looper.myLooper() == Looper.getMainLooper()) {
            apply.run();
        } else {
            post(apply);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        glowDirty = true;
        if (w <= 0 || h <= 0) return;

        int base = Math.round(Math.min(w, h) * BASE_FRACTION);
        if (base <= 0) return;

        int left = (w - base) / 2;
        int top  = (h - base) / 2;
        reusableBounds.set(left, top, left + base, top + base);
        ring.setBounds(reusableBounds);
    }

    /** Rebuilds the radial gradient for the current size and color. */
    private void rebuildGlow(int w, int h) {
        final float radius = Math.min(w, h) / 2f;
        if (radius <= 0f) return;

        final int a0 = Math.round(255f * GLOW_ALPHA);
        final int a1 = Math.round(255f * GLOW_ALPHA * 0.30f);

        final int strong = (a0 << 24) | glowRgb;
        final int mid    = (a1 << 24) | glowRgb;
        final int clear  = glowRgb;               // same RGB, alpha 0

        try {
            glowPaint.setShader(new RadialGradient(
                    w / 2f, h / 2f, radius,
                    new int[] { strong, strong, mid, clear },
                    new float[] { 0f, BASE_FRACTION * 0.95f, 0.82f, 1f },
                    Shader.TileMode.CLAMP));
            glowDirty = false;
        } catch (Throwable ignored) {
            glowPaint.setShader(null);
        }
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);

        if (!ringVisible) return;

        final int w = getWidth();
        final int h = getHeight();
        if (w <= 0 || h <= 0) return;

        final float p = progress;

        // ---- Glow (behind everything) ----
        if (glowDirty) rebuildGlow(w, h);
        if (glowPaint.getShader() != null) {
            float breathe = 0.5f + 0.5f * (float) Math.cos(2.0 * Math.PI * p);
            float glowScale = (1f - GLOW_BREATHE) + GLOW_BREATHE * breathe;
            glowPaint.setAlpha(Math.round(255f * glowScale));
            canvas.drawCircle(w / 2f, h / 2f, Math.min(w, h) / 2f, glowPaint);
        }

        // ---- Rings ----
        if (ring.getBounds().isEmpty()) return;

        final float cx = w / 2f;
        final float cy = h / 2f;
        final float maxScale = 1f / BASE_FRACTION;

        for (int i = 0; i < RING_COUNT; i++) {
            float t = (p + i / (float) RING_COUNT) % 1f;
            if (t < 0f) t += 1f; // defensive against float % sign issues

            float eased = 1f - (1f - t) * (1f - t);      // decelerate
            float scale = 1f + (maxScale - 1f) * eased;

            float fade = 1f - t;
            float a = MAX_ALPHA * fade * fade;
            if (t < FADE_IN_END) a *= t / FADE_IN_END;
            if (a <= 0f) continue;

            int alpha = Math.round(255f * a);
            // Clamp -- Drawable.setAlpha throws if outside 0..255.
            if (alpha < 0) alpha = 0;
            else if (alpha > 255) alpha = 255;

            int save = canvas.save();
            try {
                ring.setAlpha(alpha);
                canvas.scale(scale, scale, cx, cy);
                ring.draw(canvas);
            } finally {
                canvas.restoreToCount(save);
            }
        }
    }

    // ---------- lifecycle ----------

    private void startAnim() {
        if (animator != null) return;
        try {
            ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
            a.setDuration(CYCLE_MS);
            a.setInterpolator(new LinearInterpolator());
            a.setRepeatCount(ValueAnimator.INFINITE);
            a.addUpdateListener(anim -> {
                Object v = anim.getAnimatedValue();
                if (v instanceof Float) {
                    progress = (Float) v;
                    postInvalidateOnAnimation();
                }
            });
            animator = a;
            a.start();
        } catch (Throwable t) {
            animator = null; // never leave a half-built animator behind
        }
    }

    private void stopAnim() {
        ValueAnimator a = animator;
        animator = null;
        if (a != null) {
            try {
                // Break the strong ref animator -> listener -> View.
                a.removeAllUpdateListeners();
                a.cancel();
            } catch (Throwable ignored) { }
        }
    }

    /** Re-evaluate whether we should be animating right now. */
    private void updateAnimState() {
        boolean shouldRun = shouldAnimate
                && isAttachedToWindow()
                && getWindowVisibility() == View.VISIBLE
                && getVisibility() == View.VISIBLE
                && isShown();

        if (shouldRun) startAnim();
        else stopAnim();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        shouldAnimate = true;
        updateAnimState();
    }

    @Override
    protected void onDetachedFromWindow() {
        shouldAnimate = false;
        stopAnim();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        updateAnimState();
    }

    @Override
    protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        updateAnimState();
    }
}