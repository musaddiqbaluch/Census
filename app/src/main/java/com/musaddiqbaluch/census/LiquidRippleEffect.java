package com.musaddiqbaluch.census;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

/**
 * Liquid ripple: a radial, decaying wave that displaces the pixels of a
 * view (and everything inside it) outward from an origin point.
 *
 * Built on Android's official AGSL APIs (Android 13 / API 33+):
 *   RuntimeShader + RenderEffect.createRuntimeShaderEffect(...)
 *
 * On older Android versions, or when the target is not attached / not
 * laid out, play() is a silent no-op. Ripple is a cosmetic flourish --
 * it never blocks, never crashes, and never leaks.
 *
 * Threading
 * ---------
 *   Every public method is safe to call from any thread. Internally the
 *   work is marshalled to the main thread, because View mutation,
 *   getLocationInWindow, setRenderEffect, and OnAttachStateChangeListener
 *   are all main-thread-only APIs. If play() is called on a background
 *   thread it posts to the main Handler and returns immediately.
 *
 * Lifecycle
 * ---------
 *   - The target is held via WeakReference. If the caller keeps the
 *     effect in a field and forgets to cancel(), no leak occurs: once
 *     the View is no longer reachable elsewhere, the effect can be
 *     collected.
 *   - play() is ignored when the target is not attached to a window or
 *     not laid out. This avoids burning CPU on an animation that can
 *     never be visible.
 *   - The OnAttachStateChangeListener is guarded by a boolean flag, so
 *     rapid re-plays never stack listeners.
 *   - cancel() is idempotent and safe to call at any point.
 *   - destroy() permanently releases the effect; every subsequent public
 *     call becomes a no-op.
 *
 * Fallbacks
 * ---------
 *   - playFrom(anchor) falls back to play() (centred) if window
 *     coordinates cannot be computed.
 *   - play() falls back to a silent no-op if the target has no size.
 *   - Every shader or RenderEffect failure cancels the animation and
 *     clears the effect cleanly, rather than leaving a partially applied
 *     effect on screen.
 *
 * Usage (Sketchware Pro, e.g. at the end of onCreate):
 *
 *   if (_savedInstanceState == null) {
 *       mainLayout.post(new Runnable() {
 *           @Override public void run() {
 *               new LiquidRippleEffect(mainLayout).play();
 *           }
 *       });
 *   }
 *
 * Other ways to start it:
 *
 *   ripple.playFrom(someView);   // origin = centre of someView
 *   ripple.play(xPx, yPx);       // origin in the target's own pixels
 *   ripple.cancel();             // stop and remove the effect
 *   ripple.destroy();            // permanent teardown
 *
 * Requirements
 * ------------
 *   The android.jar / compileSdk used to build must be API 33 or newer,
 *   otherwise RuntimeShader and RenderEffect cannot be resolved.
 */
public final class LiquidRippleEffect {

    private static final String TAG = "LiquidRippleEffect";

    // ---- Tuning ----
    private static final long DURATION_MS = 3200L;
    private static final float AMPLITUDE_DP = 12f;
    private static final float FREQUENCY = 10f;
    private static final float DECAY = 2.2f;
    private static final float SPEED_DP = 800f;

    private static final String UNIFORM_INPUT = "inputShader";

    private static final String AGSL =
            "uniform shader inputShader;\n"
          + "uniform float2 uResolution;\n"
          + "uniform float2 uOrigin;\n"
          + "uniform float uTime;\n"
          + "uniform float uAmplitude;\n"
          + "uniform float uFrequency;\n"
          + "uniform float uDecay;\n"
          + "uniform float uSpeed;\n"
          + "\n"
          + "half4 main(float2 fragCoord) {\n"
          + "    float2 toPixel = fragCoord - uOrigin;\n"
          + "    float dist = length(toPixel);\n"
          + "\n"
          + "    float t = max(0.0, uTime - dist / uSpeed);\n"
          + "\n"
          + "    float wave1 = uAmplitude * sin(uFrequency * t)\n"
          + "                * exp(-uDecay * t);\n"
          + "    float t2 = max(0.0, t - 0.22);\n"
          + "    float wave2 = uAmplitude * 0.55\n"
          + "                * sin(uFrequency * 1.15 * t2)\n"
          + "                * exp(-uDecay * 0.8 * t2);\n"
          + "    float wave = wave1 + wave2;\n"
          + "\n"
          + "    float2 dir = toPixel / max(dist, 0.0001);\n"
          + "    float2 samplePos = fragCoord + wave * dir;\n"
          + "\n"
          + "    samplePos = clamp(samplePos, float2(0.5),\n"
          + "                      uResolution - float2(0.5));\n"
          + "\n"
          + "    half4 color = inputShader.eval(samplePos);\n"
          + "\n"
          + "    float highlight = 0.16 * (wave / max(1.0, uAmplitude));\n"
          + "    color.rgb += half3(highlight) * color.a;\n"
          + "    color.rgb = min(color.rgb, half3(color.a));\n"
          + "    return color;\n"
          + "}\n";

    /**
     * Weak reference to the target view. Prevents the effect from keeping
     * an Activity alive if the caller keeps a reference to the effect.
     */
    @NonNull
    private final WeakReference<View> targetRef;

    /** Main thread handler, used to marshal public calls to the UI thread. */
    @NonNull
    private final Handler mainHandler =
            new Handler(Looper.getMainLooper());

    @Nullable private RuntimeShader shader;
    @Nullable private ValueAnimator animator;

    /** Permanent-release flag. Set by destroy(); short-circuits every public call. */
    private boolean destroyed = false;

    /** True when detachListener is currently registered on the target. */
    private boolean listenerAttached = false;

    private final View.OnAttachStateChangeListener detachListener =
            new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View v) { }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    try {
                        // Called on the main thread by the framework.
                        cancelInternal();
                    } catch (Throwable t) {
                        Log.e(TAG, "detachListener failed", t);
                    }
                }
            };

    public LiquidRippleEffect(@NonNull View target) {
        if (target == null) {
            // Caller error -- fail fast with a clear message.
            throw new IllegalArgumentException("target must not be null");
        }
        this.targetRef = new WeakReference<>(target);
    }

    // ============================================================
    // Public API -- thread safe
    // ============================================================

    /** Ripple from the centre of the target view. */
    public void play() {
        runOnMain(new Runnable() {
            @Override public void run() {
                try {
                    View t = targetRef.get();
                    if (t == null) return;
                    playInternal(t, t.getWidth() / 2f, t.getHeight() / 2f);
                } catch (Throwable e) {
                    Log.e(TAG, "play failed", e);
                }
            }
        });
    }

    /**
     * Ripple from the centre of another view (for example the app icon or
     * the count badge). The anchor must be inside the target.
     *
     * Falls back to play() (centred) if window coordinates cannot be
     * computed for the anchor.
     */
    public void playFrom(@NonNull final View anchor) {
        if (anchor == null) {
            play();
            return;
        }
        runOnMain(new Runnable() {
            @Override public void run() {
                try {
                    View t = targetRef.get();
                    if (t == null) return;

                    int[] a = new int[2];
                    int[] w = new int[2];
                    anchor.getLocationInWindow(a);
                    t.getLocationInWindow(w);

                    float x = (a[0] - w[0]) + anchor.getWidth() / 2f;
                    float y = (a[1] - w[1]) + anchor.getHeight() / 2f;

                    playInternal(t, x, y);
                } catch (Throwable e) {
                    Log.e(TAG, "playFrom failed, falling back to centre", e);
                    try {
                        View t = targetRef.get();
                        if (t != null) {
                            playInternal(t, t.getWidth() / 2f, t.getHeight() / 2f);
                        }
                    } catch (Throwable ignored) {}
                }
            }
        });
    }

    /** Ripple from a point given in the target's own pixel coordinates. */
    public void play(final float originX, final float originY) {
        runOnMain(new Runnable() {
            @Override public void run() {
                try {
                    View t = targetRef.get();
                    if (t == null) return;
                    playInternal(t, originX, originY);
                } catch (Throwable e) {
                    Log.e(TAG, "play(x, y) failed", e);
                }
            }
        });
    }

    /** Stops the ripple and removes the effect immediately. Idempotent. */
    public void cancel() {
        runOnMain(new Runnable() {
            @Override public void run() {
                try {
                    cancelInternal();
                } catch (Throwable e) {
                    Log.e(TAG, "cancel failed", e);
                }
            }
        });
    }

    /**
     * Permanently releases this effect. Idempotent. After destroy(),
     * every subsequent public method is a no-op. Call from the host's
     * onDestroy if you keep a reference to the effect.
     */
    public void destroy() {
        runOnMain(new Runnable() {
            @Override public void run() {
                try {
                    destroyed = true;
                    cancelInternal();
                } catch (Throwable e) {
                    Log.e(TAG, "destroy failed", e);
                }
            }
        });
    }

    // ============================================================
    // Internal -- main thread only
    // ============================================================

    private void playInternal(@NonNull View t, float originX, float originY) {

        if (destroyed) return;
        if (Build.VERSION.SDK_INT < 33) return;

        try {
            // Do not burn CPU on an animation that can never be seen.
            if (!t.isAttachedToWindow()) {
                Log.w(TAG, "play ignored: view is not attached to a window");
                return;
            }
            if (!t.isLaidOut()) {
                Log.w(TAG, "play ignored: view is not laid out yet");
                return;
            }

            int width = t.getWidth();
            int height = t.getHeight();
            if (width <= 0 || height <= 0) {
                Log.w(TAG, "play ignored: view has zero size");
                return;
            }

            // Cancel any in-flight run before starting a new one.
            cancelInternal();

            float density;
            try {
                density = t.getResources().getDisplayMetrics().density;
                if (density <= 0f) density = 1.0f;
            } catch (Throwable e) {
                density = 1.0f;
            }

            // Shader construction can fail on a broken GPU driver.
            final RuntimeShader s;
            try {
                s = new RuntimeShader(AGSL);
            } catch (Throwable e) {
                Log.e(TAG, "RuntimeShader construction failed", e);
                return;
            }

            try {
                s.setFloatUniform("uResolution", (float) width, (float) height);
                s.setFloatUniform("uOrigin", originX, originY);
                s.setFloatUniform("uAmplitude", AMPLITUDE_DP * density);
                s.setFloatUniform("uFrequency", FREQUENCY);
                s.setFloatUniform("uDecay", DECAY);
                s.setFloatUniform("uSpeed", SPEED_DP * density);
                s.setFloatUniform("uTime", 0f);
            } catch (Throwable e) {
                Log.e(TAG, "Shader uniform initialisation failed", e);
                return;
            }

            shader = s;

            final float totalSeconds = DURATION_MS / 1000f;

            ValueAnimator a;
            try {
                a = ValueAnimator.ofFloat(0f, totalSeconds);
                a.setDuration(DURATION_MS);
                a.setInterpolator(new LinearInterpolator());
            } catch (Throwable e) {
                Log.e(TAG, "ValueAnimator creation failed", e);
                shader = null;
                return;
            }

            a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(ValueAnimator va) {
                    try {
                        if (destroyed) {
                            cancelInternal();
                            return;
                        }

                        View t2 = targetRef.get();
                        if (t2 == null) {
                            // Target was collected -- stop wasting frames.
                            cancelInternal();
                            return;
                        }

                        float time = (Float) va.getAnimatedValue();

                        // Update the shader uniform, then re-apply the
                        // effect so the new uniform is picked up. This
                        // re-application is the documented pattern for
                        // AGSL animation on API 33.
                        s.setFloatUniform("uTime", time);

                        RenderEffect effect =
                                RenderEffect.createRuntimeShaderEffect(
                                        s, UNIFORM_INPUT);

                        if (effect != null) {
                            t2.setRenderEffect(effect);
                        }
                    } catch (Throwable e) {
                        Log.e(TAG, "frame update failed, cancelling", e);
                        cancelInternal();
                    }
                }
            });

            a.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    try {
                        clearEffect();
                    } catch (Throwable e) {
                        Log.e(TAG, "onAnimationEnd cleanup failed", e);
                    }
                }
            });

            attachDetachListener(t);

            animator = a;

            try {
                a.start();
            } catch (Throwable e) {
                Log.e(TAG, "ValueAnimator.start failed", e);
                animator = null;
                clearEffect();
            }

        } catch (Throwable e) {
            // Last-resort guard around the entire setup path.
            Log.e(TAG, "playInternal failed", e);
            try {
                clearEffect();
            } catch (Throwable ignored) {}
        }
    }

    private void cancelInternal() {

        ValueAnimator a = animator;
        animator = null;

        if (a != null) {
            try { a.cancel(); }
            catch (Throwable e) { Log.e(TAG, "animator.cancel failed", e); }
        }

        // clearEffect is idempotent and safe even if the animator just
        // fired onAnimationEnd.
        clearEffect();
    }

    private void clearEffect() {

        // Remove the detach listener if it is still attached.
        detachDetachListener();

        // Clear the RenderEffect from the view so we do not leave a
        // stale shader applied after the animation ends.
        View t = targetRef.get();
        if (t != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    t.setRenderEffect(null);
                }
            } catch (Throwable e) {
                Log.e(TAG, "setRenderEffect(null) failed", e);
            }
        }

        shader = null;
    }

    private void attachDetachListener(@NonNull View t) {
        if (listenerAttached) return;
        try {
            t.addOnAttachStateChangeListener(detachListener);
            listenerAttached = true;
        } catch (Throwable e) {
            Log.e(TAG, "addOnAttachStateChangeListener failed", e);
        }
    }

    private void detachDetachListener() {
        if (!listenerAttached) return;
        listenerAttached = false;
        View t = targetRef.get();
        if (t == null) return;
        try {
            t.removeOnAttachStateChangeListener(detachListener);
        } catch (Throwable ignored) {
            // Safe to ignore: removing a listener that is already gone
            // does not throw on any known Android version, but the
            // framework does not guarantee it under all OEM overlays.
        }
    }

    // ============================================================
    // Thread helpers
    // ============================================================

    private void runOnMain(@NonNull Runnable r) {
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                r.run();
            } else {
                boolean posted = mainHandler.post(r);
                if (!posted) {
                    Log.w(TAG, "runOnMain: handler.post returned false");
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "runOnMain failed", e);
        }
    }
}