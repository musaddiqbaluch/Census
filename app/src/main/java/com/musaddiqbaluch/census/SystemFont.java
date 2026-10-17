package com.musaddiqbaluch.census;

import android.graphics.Typeface;
import android.os.Looper;
import android.util.Log;
import android.widget.TextView;

import androidx.annotation.Nullable;

/**
 * System font helper: applies a Material 3 weight (100..900) to a
 * TextView using the system's variable-weight sans-serif family.
 *
 * Weight constants map directly to Material 3's supported weights. Any
 * weight value is accepted; values outside 100..900 are clamped, and
 * values not on a 100-step boundary are rounded to the nearest hundred
 * (matching what Typeface.create itself does internally).
 *
 * Robustness
 * ----------
 *   - Typeface instances are CACHED. Typeface.create is expensive and
 *     was previously called once per tab per refresh -- up to ~60
 *     allocations per tap on a phone with 30 installers.
 *   - Every method is wrapped; a Typeface.create failure falls back to
 *     the system default. A setTypeface failure is logged, not thrown.
 *   - Null TextView is a safe no-op.
 *   - A debug-only warning is logged if a setter is called off the
 *     main thread; it does not throw.
 */
public final class SystemFont {

    private static final String TAG = "SystemFont";

    // Material 3 supported weights.
    public static final int THIN        = 100;
    public static final int EXTRA_LIGHT = 200;
    public static final int LIGHT       = 300;
    public static final int REGULAR     = 400;
    public static final int MEDIUM      = 500;
    public static final int SEMI_BOLD   = 600;
    public static final int BOLD        = 700;
    public static final int EXTRA_BOLD  = 800;
    public static final int BLACK       = 900;

    // Small, bounded cache: 9 weight steps x 2 italic states = 18 slots.
    // Indexed by (step - 1) * 2 + (italic ? 1 : 0), where step is 1..9.
    private static final int CACHE_SIZE = 18;
    @SuppressWarnings("unchecked")
    private static final Typeface[] CACHE = new Typeface[CACHE_SIZE];
    private static final Object CACHE_LOCK = new Object();

    private SystemFont() {
        // Utility class.
    }

    // ============================================================
    // Typeface factory
    // ============================================================

    @Nullable
    public static Typeface get(int weight) {
        return get(weight, false);
    }

    @Nullable
    public static Typeface get(int weight, boolean italic) {

        try {
            final int step = normalizeWeightToStep(weight);
            final int index = cacheIndex(step, italic);

            // Fast path: cached instance.
            Typeface cached = CACHE[index];
            if (cached != null) return cached;

            synchronized (CACHE_LOCK) {
                // Re-check under lock to avoid duplicate creation.
                cached = CACHE[index];
                if (cached != null) return cached;

                Typeface created;
                try {
                    created = Typeface.create(
                            Typeface.SANS_SERIF,
                            step * 100,
                            italic
                    );
                } catch (Throwable t) {
                    Log.e(TAG, "Typeface.create failed for weight "
                            + (step * 100) + " italic=" + italic, t);
                    created = null;
                }

                if (created == null) {
                    // Some OEMs return null from Typeface.create for
                    // unusual families/weights. Fall back to default.
                    try {
                        created = Typeface.DEFAULT;
                    } catch (Throwable t) {
                        Log.e(TAG, "Typeface.DEFAULT lookup failed", t);
                        return null;
                    }
                }

                CACHE[index] = created;
                return created;
            }

        } catch (Throwable t) {
            Log.e(TAG, "get(weight=" + weight + ", italic=" + italic
                    + ") failed", t);
            // Best-effort fallback; may return null if even this fails.
            try {
                return Typeface.DEFAULT;
            } catch (Throwable t2) {
                return null;
            }
        }
    }

    // ============================================================
    // Appliers
    // ============================================================

    public static void apply(@Nullable TextView textView, int weight) {
        apply(textView, weight, false);
    }

    public static void apply(
            @Nullable TextView textView,
            int weight,
            boolean italic) {

        if (textView == null) return;

        warnIfNotMainThread("apply");

        try {
            Typeface typeface = get(weight, italic);
            if (typeface != null) {
                textView.setTypeface(typeface);
            }
        } catch (Throwable t) {
            Log.e(TAG, "apply failed", t);
        }
    }

    // ============================================================
    // Convenience shortcuts
    // ============================================================

    public static void regular(@Nullable TextView textView) {
        apply(textView, REGULAR);
    }

    public static void medium(@Nullable TextView textView) {
        apply(textView, MEDIUM);
    }

    public static void semiBold(@Nullable TextView textView) {
        apply(textView, SEMI_BOLD);
    }

    public static void bold(@Nullable TextView textView) {
        apply(textView, BOLD);
    }

    public static void extraBold(@Nullable TextView textView) {
        apply(textView, EXTRA_BOLD);
    }

    public static void black(@Nullable TextView textView) {
        apply(textView, BLACK);
    }

    // ============================================================
    // Internal helpers
    // ============================================================

    /**
     * Converts an arbitrary weight (100..900 typically) into a step
     * value in 1..9. Values below 100 clamp to 1; above 900 clamp to 9.
     * Values not on a hundred boundary round to the nearest hundred,
     * matching Android's internal normalisation.
     */
    private static int normalizeWeightToStep(int weight) {

        if (weight <= 100) return 1;
        if (weight >= 900) return 9;

        // Round to nearest hundred.
        int step = (weight + 50) / 100;

        // Guard against any off-by-one from integer division.
        if (step < 1) step = 1;
        if (step > 9) step = 9;

        return step;
    }

    private static int cacheIndex(int step, boolean italic) {
        return (step - 1) * 2 + (italic ? 1 : 0);
    }

    private static void warnIfNotMainThread(String methodName) {
        try {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                Log.w(TAG, methodName + " called off the main thread. "
                        + "TextView.setTypeface is a UI operation.");
            }
        } catch (Throwable ignored) {
            // Logging must never break the pipeline.
        }
    }
}