package com.musaddiqbaluch.census;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewParent;

import androidx.annotation.RequiresApi;

import java.lang.ref.WeakReference;

/**
 * Subtle "frosted glass" layer for a card (for example bodyOutline).
 *
 * How it works
 * - Android 12+ (API 31): the content BEHIND the card (the background of the
 *   root layout, i.e. your FloatingCirclesDrawable) is recorded into a
 *   RenderNode, blurred on the GPU with RenderEffect, and drawn clipped to
 *   the card's rounded shape. This is the RenderNode + RenderEffect technique
 *   from the Android Developers blog ("RenderNode for Bigger, Better Blurs").
 *   It is real time: it follows the animated circles every frame.
 * - Below Android 12, or if the canvas is not hardware accelerated, or if the
 *   blur ever fails: nothing is blurred and the card is simply translucent
 *   (the original circles show through the translucent fill).
 *
 * Colors stay exact
 * - The card's fill is drawn with a small transparency. Use
 *   {@link #compensateFill(int, int, int)} (the bar does this for you via
 *   MaterialDistributionBar.setBodyFillAlpha) so the fill, composited over the
 *   surface color, ends up as the exact original color.
 *
 * Usage (after the root background and the card background exist):
 *     glassBg = GlassBackdropDrawable.attachTo(binding.bodyOutline, binding.rootLayout, 28f);
 *     materialDistributionBar.setBodyFillAlpha(GlassBackdropDrawable.DEFAULT_FILL_ALPHA);
 *   onDestroy:
 *     if (glassBg != null) { glassBg.release(); glassBg = null; }
 *
 * Notes
 * - Only the root's BACKGROUND is blurred, not other views drawn behind the card.
 * - The card redraws every frame while the circles animate, and stops when the
 *   circles stop or the card is not visible (no timers, no listeners).
 * - Main-thread safe: attachTo/release may be called from any thread.
 * - Every entry point is wrapped in try/catch and never throws into the app.
 * - Java 7 compatible, no lambdas.
 */
public final class GlassBackdropDrawable extends Drawable {

    private static final String TAG = "GlassBackdrop";

    /** Blur strength in dp. 10-18 is subtle, 24+ is strong. */
    public static final float BLUR_DP = 14f;

    /**
     * Opacity (0-255) of the card fill over the blur. 255 = fully opaque (no
     * visible glass), 210 (~82%) = subtle, 180 (~70%) = obvious.
     */
    public static final int DEFAULT_FILL_ALPHA = 210;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final WeakReference<View> hostRef;
    private final WeakReference<View> rootRef;
    private final float cornerRadiusPx;

    private final Path clipPath = new Path();
    private final RectF clipRect = new RectF();
    private final int[] offset = new int[2];

    private RenderNode node;
    private float appliedSigma = -1f;
    private boolean blurFailed = false;

    private volatile boolean released = false;

    private GlassBackdropDrawable(View host, View root, float cornerRadiusPx) {
        this.hostRef = new WeakReference<View>(host);
        this.rootRef = new WeakReference<View>(root);
        this.cornerRadiusPx = cornerRadiusPx;
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Puts the glass layer UNDER the host's current background (your existing
     * RoundedShapeDrawable keeps working and keeps being animated by reference).
     *
     * @param host           the card, e.g. binding.bodyOutline
     * @param backdropRoot   the view whose background is behind the card,
     *                       e.g. binding.rootLayout
     * @param cornerRadiusDp must match the card's corner radius (28 for bodyOutline)
     * @return the drawable (keep it to call release()), or null on failure
     *         or on Android 11 and below, where glass is not applied
     */
    public static GlassBackdropDrawable attachTo(
            final View host, final View backdropRoot, float cornerRadiusDp) {
        // Below Android 12 there is no blur, so the glass layer draws nothing.
        // Do not wrap the card background in a LayerDrawable at all: the card
        // keeps exactly the background it had before glass was added.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return null;
        }
        if (host == null || backdropRoot == null) {
            Log.w(TAG, "attachTo called with a null view");
            return null;
        }
        try {
            float density = host.getResources().getDisplayMetrics().density;
            final GlassBackdropDrawable glass = new GlassBackdropDrawable(
                    host, backdropRoot, cornerRadiusDp * density);
            runOnMain(new Runnable() {
                @Override
                public void run() {
                    glass.installOnMain();
                }
            });
            return glass;
        } catch (Throwable t) {
            Log.e(TAG, "attachTo failed", t);
            return null;
        }
    }

    /** Call from onDestroy. Idempotent, safe from any thread. */
    public void release() {
        try {
            released = true;
            if (isMainThread()) {
                releaseOnMain();
            } else {
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        releaseOnMain();
                    }
                });
            }
        } catch (Throwable t) {
            Log.e(TAG, "release failed", t);
        }
    }

    /**
     * Returns {@code fill} adjusted so that, drawn with {@code alpha255} over
     * {@code backdrop}, the visible result equals {@code fill} (exact color
     * over a flat backdrop). Result is clamped per channel.
     */
    public static int compensateFill(int fill, int backdrop, int alpha255) {
        if (alpha255 >= 255) return fill | 0xFF000000;
        if (alpha255 <= 0) return fill;
        float a = alpha255 / 255f;
        float inv = 1f - a;
        int r = clamp(Math.round((Color.red(fill) - inv * Color.red(backdrop)) / a));
        int g = clamp(Math.round((Color.green(fill) - inv * Color.green(backdrop)) / a));
        int b = clamp(Math.round((Color.blue(fill) - inv * Color.blue(backdrop)) / a));
        return Color.argb(alpha255, r, g, b);
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    // ------------------------------------------------------------------
    // Main-thread internals
    // ------------------------------------------------------------------

    private void installOnMain() {
        try {
            if (released) return;
            View host = hostRef.get();
            if (host == null) return;

            Drawable current = host.getBackground();

            // Attached twice? Release the old glass and re-wrap the real background.
            if (current instanceof LayerDrawable) {
                LayerDrawable existing = (LayerDrawable) current;
                if (existing.getNumberOfLayers() > 0
                        && existing.getDrawable(0) instanceof GlassBackdropDrawable) {
                    ((GlassBackdropDrawable) existing.getDrawable(0)).release();
                    current = existing.getNumberOfLayers() > 1
                            ? existing.getDrawable(1) : null;
                }
            }

            Drawable[] layers = (current != null)
                    ? new Drawable[]{this, current}
                    : new Drawable[]{this};
            host.setBackground(new LayerDrawable(layers));
        } catch (Throwable t) {
            Log.e(TAG, "install failed", t);
        }
    }

    private void releaseOnMain() {
        try {
            if (node != null) {
                node.discardDisplayList();
                node = null;
            }
            setCallback(null);
        } catch (Throwable t) {
            Log.e(TAG, "releaseOnMain failed", t);
        }
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        clipRect.set(bounds);
        clipPath.reset();
        clipPath.addRoundRect(clipRect, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW);
    }

    @Override
    public void draw(Canvas canvas) {
        // Below Android 12 / not accelerated / failed earlier: translucent only.
        if (released || blurFailed
                || Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || !canvas.isHardwareAccelerated()) {
            return;
        }
        try {
            drawBlurred(canvas);
        } catch (Throwable t) {
            blurFailed = true; // never retry a broken path every frame
            Log.e(TAG, "blur draw failed, falling back to translucent", t);
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private void drawBlurred(Canvas canvas) {
        Rect b = getBounds();
        int w = b.width();
        int h = b.height();
        if (w <= 0 || h <= 0) return;

        View host = hostRef.get();
        View root = rootRef.get();
        if (host == null || root == null) return;

        Drawable source = root.getBackground();
        if (source == null || source.getBounds().isEmpty()) return;

        if (!computeOffsetInRoot(host, root, offset)) return;

        if (node == null) {
            node = new RenderNode("glass-backdrop");
            appliedSigma = -1f;
        }

        // Blur strength (re-applied only when it changes, e.g. density change).
        float sigma = BLUR_DP * host.getResources().getDisplayMetrics().density;
        if (sigma != appliedSigma) {
            node.setRenderEffect(RenderEffect.createBlurEffect(
                    sigma, sigma, Shader.TileMode.CLAMP));
            appliedSigma = sigma;
        }

        node.setPosition(b.left, b.top, b.right, b.bottom);

        // Record the part of the root background that lies behind the card.
        android.graphics.Canvas rc = node.beginRecording(w, h);
        try {
            rc.translate(-(offset[0] + b.left), -(offset[1] + b.top));
            source.draw(rc);
        } finally {
            node.endRecording();
        }

        int save = canvas.save();
        try {
            canvas.clipPath(clipPath);
            canvas.drawRenderNode(node);
        } finally {
            canvas.restoreToCount(save);
        }

        // Keep following the animated backdrop only while it is animating.
        if (source instanceof Animatable && ((Animatable) source).isRunning()) {
            invalidateSelf();
        }
    }

    /** Position of host inside root, in root coordinates. */
    private static boolean computeOffsetInRoot(View host, View root, int[] out) {
        int x = 0;
        int y = 0;
        View v = host;
        int guard = 0;
        while (v != root) {
            ViewParent p = v.getParent();
            if (!(p instanceof View) || ++guard > 64) return false;
            View parent = (View) p;
            x += v.getLeft() + Math.round(v.getTranslationX()) - parent.getScrollX();
            y += v.getTop() + Math.round(v.getTranslationY()) - parent.getScrollY();
            v = parent;
        }
        out[0] = x;
        out[1] = y;
        return true;
    }

    private static boolean isMainThread() {
        return Looper.myLooper() == Looper.getMainLooper();
    }

    private static void runOnMain(Runnable r) {
        if (isMainThread()) {
            r.run();
        } else {
            MAIN.post(r);
        }
    }

    // ------------------------------------------------------------------
    // Drawable boilerplate
    // ------------------------------------------------------------------

    @Override
    public void setAlpha(int alpha) {
        // The glass layer is always drawn at full strength; the card fill
        // above it controls the visible amount.
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        // Not used.
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
