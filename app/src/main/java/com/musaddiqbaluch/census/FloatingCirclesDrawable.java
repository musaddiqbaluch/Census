package com.musaddiqbaluch.census; // <- change to your app's package

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.animation.LinearInterpolator;

import com.google.android.material.color.MaterialColors;

import java.lang.ref.WeakReference;

/**
 * Soft, slowly drifting circles used as the background of ANY view
 * (a plain LinearLayout is fine, no FrameLayout/RelativeLayout needed).
 *
 * Usage:
 *   onCreate (after setContentView):
 *       circlesBg = FloatingCirclesDrawable.attachTo(binding.rootLayout);
 *
 *   onDestroy:
 *       if (circlesBg != null) { circlesBg.release(); circlesBg = null; }
 *
 * Design notes
 * - THREADING: every animator / view operation runs on the main thread. If
 *   attachTo / start / stop / release are called from a background thread
 *   they are marshalled to the main thread automatically. State flags are
 *   volatile, so they are safe to read from any thread.
 * - LIFECYCLE: the animation starts when the view is attached + visible and
 *   stops when it is detached or hidden (Drawable.setVisible is called by
 *   the View when the window / activity goes to background).
 * - MEMORY: release() cancels the animator, removes all listeners and
 *   pending posts, and clears the drawable callback, so nothing keeps the
 *   Activity alive. The view is only referenced through a WeakReference.
 * - SAFETY: every public entry point is wrapped in try/catch and never
 *   throws into your app. After release() the drawable is inert.
 * - Colors come from the Material theme: colorSurface (background) and
 *   colorOnSurface at low alpha (circles).
 * - Works on API 21+ (Android 10 included). Java 7 compatible, no lambdas.
 */
public class FloatingCirclesDrawable extends Drawable implements Animatable {

    private static final String TAG = "FloatingCircles";

    /** One full loop of the drift animation. Higher = calmer. */
    private static final long LOOP_DURATION_MS = 26000L;

    /** Circle alpha over the surface color (0-255). ~14 = very subtle. */
    private static final int CIRCLE_ALPHA = 30;

    private static final double TWO_PI = Math.PI * 2.0;
    private static final double HALF_PI = Math.PI / 2.0;

    /**
     * Each row: {x, y, radius, driftX, driftY, speed, phase}
     * x             -> fraction of view WIDTH
     * y             -> fraction of view HEIGHT
     * radius        -> fraction of view WIDTH
     * driftX/Y      -> drift distance as fraction of view WIDTH
     * speed         -> INTEGER multiplier (keeps the loop seamless)
     * phase         -> 0..1 start offset
     *
     * These values are checked so that NO two circles ever touch or overlap,
     * even at their closest point of the animation, on any portrait screen
     * (height >= 1.5 x width). If you edit them, keep a gap between circles:
     * distance between centers > radius1 + radius2 + both drifts + ~5% margin.
     */
    private static final float[][] CIRCLES = {
            {0.10f, 0.09f, 0.27f, 0.035f, 0.030f, 1f, 0.00f},
            {0.90f, 0.21f, 0.22f, 0.030f, 0.035f, 1f, 0.35f},
            {0.14f, 0.57f, 0.15f, 0.030f, 0.030f, 2f, 0.60f},
            {0.90f, 0.60f, 0.21f, 0.035f, 0.030f, 1f, 0.15f},
            {0.30f, 0.91f, 0.24f, 0.035f, 0.035f, 1f, 0.80f},
            {0.88f, 0.94f, 0.13f, 0.025f, 0.025f, 2f, 0.50f}
    };

    /** Shared main-thread handler (holds no Activity/View reference). */
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final Paint bgPaint = new Paint();
    private final Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int circleBaseAlpha;

    // ---- main-thread-only state ----
    private ValueAnimator animator;
    private WeakReference<View> hostRef;
    private View.OnAttachStateChangeListener attachListener;
    private float progress = 0f;

    // ---- cross-thread state ----
    private volatile boolean released = false;
    private volatile boolean running = false;

    // Reusable tasks so they can be removed from the handler on release().
    private final Runnable startTask = new Runnable() {
        @Override
        public void run() {
            startOnMain();
        }
    };
    private final Runnable stopTask = new Runnable() {
        @Override
        public void run() {
            stopOnMain();
        }
    };
    private final Runnable releaseTask = new Runnable() {
        @Override
        public void run() {
            releaseOnMain();
        }
    };

    public FloatingCirclesDrawable(int backgroundColor, int circleColor) {
        bgPaint.setColor(backgroundColor);
        circlePaint.setColor(circleColor);
        circleBaseAlpha = Color.alpha(circleColor);
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Creates the drawable, sets it as the background of {@code root} and
     * manages its lifecycle. Safe to call from any thread.
     *
     * @return the drawable (keep it to call {@link #release()} in onDestroy),
     *         or null if it could not be created.
     */
    public static FloatingCirclesDrawable attachTo(final View root) {
        if (root == null) {
            Log.w(TAG, "attachTo called with a null view");
            return null;
        }
        try {
            Context ctx = root.getContext();
            int surface = MaterialColors.getColor(ctx,
                    com.google.android.material.R.attr.colorSurface, 0xFFFFFFFF);
            int onSurface = MaterialColors.getColor(ctx,
                    com.google.android.material.R.attr.colorOnSurface, 0xFF000000);
            int circle = (onSurface & 0x00FFFFFF) | (CIRCLE_ALPHA << 24);

            final FloatingCirclesDrawable d = new FloatingCirclesDrawable(surface, circle);
            runOnMain(new Runnable() {
                @Override
                public void run() {
                    d.bindOnMain(root);
                }
            });
            return d;
        } catch (Throwable t) {
            Log.e(TAG, "attachTo failed", t);
            return null;
        }
    }

    /**
     * Call from Activity/Fragment onDestroy. Stops the animation, removes every
     * listener and pending callback and drops references. Idempotent and safe
     * to call from any thread.
     */
    public void release() {
        try {
            released = true;
            running = false;
            MAIN.removeCallbacks(startTask);
            MAIN.removeCallbacks(stopTask);
            if (isMainThread()) {
                releaseOnMain();
            } else {
                MAIN.post(releaseTask);
            }
        } catch (Throwable t) {
            Log.e(TAG, "release failed", t);
        }
    }

    @Override
    public void start() {
        if (released) return;
        try {
            if (isMainThread()) {
                startOnMain();
            } else {
                MAIN.post(startTask);
            }
        } catch (Throwable t) {
            Log.e(TAG, "start failed", t);
        }
    }

    @Override
    public void stop() {
        try {
            if (isMainThread()) {
                stopOnMain();
            } else {
                MAIN.post(stopTask);
            }
        } catch (Throwable t) {
            Log.e(TAG, "stop failed", t);
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Called by the View when it (or its window) becomes visible / hidden. */
    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        boolean changed = super.setVisible(visible, restart);
        if (released) return changed;
        if (visible) {
            start();
        } else {
            stop();
        }
        return changed;
    }

    // ------------------------------------------------------------------
    // Main-thread internals
    // ------------------------------------------------------------------

    private void bindOnMain(View root) {
        try {
            if (released) return;

            // Replace a previous instance on the same view cleanly.
            Drawable old = root.getBackground();
            if (old instanceof FloatingCirclesDrawable && old != this) {
                ((FloatingCirclesDrawable) old).release();
            }

            hostRef = new WeakReference<View>(root);
            attachListener = new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                    start();
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    stop();
                }
            };
            root.addOnAttachStateChangeListener(attachListener);
            root.setBackground(this);

            if (root.isAttachedToWindow()) {
                startOnMain();
            }
        } catch (Throwable t) {
            Log.e(TAG, "bind failed", t);
        }
    }

    private void startOnMain() {
        try {
            if (released || running) return;
            if (Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled()) {
                return; // user disabled animations: keep the static look
            }
            if (animator == null) {
                animator = createAnimator();
            }
            animator.start();
            running = true;
        } catch (Throwable t) {
            running = false;
            Log.e(TAG, "startOnMain failed", t);
        }
    }

    private void stopOnMain() {
        try {
            running = false;
            if (animator != null) {
                animator.cancel();
            }
        } catch (Throwable t) {
            Log.e(TAG, "stopOnMain failed", t);
        }
    }

    private void releaseOnMain() {
        try {
            running = false;

            if (animator != null) {
                animator.removeAllUpdateListeners();
                animator.cancel();
                animator = null;
            }

            View host = (hostRef != null) ? hostRef.get() : null;
            if (host != null && attachListener != null) {
                host.removeOnAttachStateChangeListener(attachListener);
            }
            hostRef = null;
            attachListener = null;

            setCallback(null);
        } catch (Throwable t) {
            Log.e(TAG, "releaseOnMain failed", t);
        }
    }

    private ValueAnimator createAnimator() {
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(LOOP_DURATION_MS);
        a.setInterpolator(new LinearInterpolator());
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator va) {
                if (released) return;
                try {
                    progress = (Float) va.getAnimatedValue();
                    invalidateSelf();
                } catch (Throwable t) {
                    Log.e(TAG, "frame update failed", t);
                }
            }
        });
        return a;
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
    // Drawing
    // ------------------------------------------------------------------

    @Override
    public void draw(Canvas canvas) {
        try {
            Rect b = getBounds();
            if (b.isEmpty()) return;

            canvas.drawRect(b, bgPaint);

            float w = b.width();
            float h = b.height();

            for (int i = 0; i < CIRCLES.length; i++) {
                float[] c = CIRCLES[i];
                double angle = TWO_PI * (progress * Math.round(c[5]) + c[6]);

                float cx = b.left + w * c[0] + w * c[3] * (float) Math.sin(angle);
                float cy = b.top + h * c[1] + w * c[4] * (float) Math.cos(angle);
                // gentle "breathing" of the radius
                float r = w * c[2] * (1f + 0.04f * (float) Math.sin(angle + HALF_PI));

                canvas.drawCircle(cx, cy, r, circlePaint);
            }
        } catch (Throwable t) {
            Log.e(TAG, "draw failed", t);
        }
    }

    // ------------------------------------------------------------------
    // Drawable boilerplate
    // ------------------------------------------------------------------

    @Override
    public void setAlpha(int alpha) {
        bgPaint.setAlpha(alpha);
        circlePaint.setAlpha((alpha * circleBaseAlpha) / 255);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        bgPaint.setColorFilter(colorFilter);
        circlePaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
