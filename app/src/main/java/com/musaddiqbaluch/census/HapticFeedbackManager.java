package com.musaddiqbaluch.census;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.snackbar.Snackbar;

/**
 * Manages the application's haptic feedback preference.
 *
 * Haptic implementation:
 *
 * Android 13+ (API 33+)
 * ---------------------
 * Uses View.performHapticFeedback(), which remains the preferred
 * action-oriented Android haptic API.
 *
 * Android 10-12 (API 29-32)
 * -------------------------
 * Uses Android's predefined VibrationEffect constants:
 *
 *   LIGHT  -> EFFECT_TICK
 *   MEDIUM -> EFFECT_CLICK
 *   HEAVY  -> EFFECT_HEAVY_CLICK
 *
 * Android 8-9 (API 26-28)
 * -----------------------
 * If the vibrator supports amplitude control:
 *
 *   LIGHT  -> low amplitude
 *   MEDIUM -> medium amplitude
 *   HEAVY  -> maximum amplitude
 *
 * Otherwise a simple ON/OFF waveform is used.
 *
 * Android 7 and below
 * -------------------
 * Uses a simple ON/OFF waveform through the legacy vibrator API.
 *
 * The newer Android 13+ View-based implementation is intentionally
 * kept separate from the legacy implementation.
 *
 * Multi-instance synchronization
 * ------------------------------
 * SharedPreferences is treated as the source of truth. Every public
 * read operation refreshes the current intensity from preferences.
 *
 * Robustness
 * ----------
 * - Public UI methods are safe to call from any thread.
 * - Main-thread View/Snackbar operations are marshalled safely.
 * - Pending modern haptic callbacks are cancelled before a new trigger.
 * - Legacy vibrator requests cancel the previous vibration first.
 * - Preference failures fall back to the last known in-memory value.
 * - destroy() safely cancels pending callbacks and releases lifecycle
 *   work associated with this manager instance.
 */
public class HapticFeedbackManager {

    private static final String TAG = "HapticFeedbackManager";

    private static final String PREF_NAME = "haptic_settings";
    private static final String KEY_INTENSITY = "haptic_intensity";

    // ============================================================
    // Modern Android 13+ pulse timing
    // ============================================================

    private static final long MEDIUM_PULSE_GAP_MS = 32L;
    private static final long HEAVY_PULSE_GAP_MS = 30L;

    /**
     * Modern View-based haptic constant.
     *
     * Android decides the actual motor behavior for the device.
     */
    private static final int HAPTIC_CONSTANT =
            HapticFeedbackConstants.CLOCK_TICK;

    /**
     * Emergency Vibrator fallback for the modern path.
     */
    private static final long VIBRATOR_PULSE_MS = 20L;

    // ============================================================
    // Legacy Android 8-9 amplitude values
    // ============================================================

    /*
     * These values are used only when the hardware reports that
     * amplitude control is actually supported.
     */
    private static final int LEGACY_LIGHT_AMPLITUDE = 64;
    private static final int LEGACY_MEDIUM_AMPLITUDE = 140;
    private static final int LEGACY_HEAVY_AMPLITUDE = 255;

    private static final long LEGACY_LIGHT_DURATION_MS = 20L;
    private static final long LEGACY_MEDIUM_DURATION_MS = 22L;
    private static final long LEGACY_HEAVY_DURATION_MS = 24L;

    // ============================================================
    // Legacy ON/OFF waveform fallback
    // ============================================================

    /*
     * Waveform format:
     *
     *   OFF, ON, OFF, ON...
     *
     * This fallback is deliberately used only when amplitude control
     * is unavailable.
     *
     * LIGHT:
     *   one short pulse
     *
     * MEDIUM:
     *   two short pulses
     *
     * HEAVY:
     *   three short pulses
     */
    private static final long[] LEGACY_LIGHT_PATTERN = {
            0L, 20L
    };

    private static final long[] LEGACY_MEDIUM_PATTERN = {
            0L, 20L,
            45L, 20L
    };

    private static final long[] LEGACY_HEAVY_PATTERN = {
            0L, 22L,
            45L, 22L,
            45L, 22L
    };

    // ============================================================
    // Snackbar fallback colors
    // ============================================================

    private static final int FALLBACK_SNACKBAR_BG_LIGHT = 0xFFECE6F0;
    private static final int FALLBACK_SNACKBAR_BG_DARK  = 0xFF322F35;
    private static final int FALLBACK_SNACKBAR_TEXT_LIGHT = 0xFF1D1B20;
    private static final int FALLBACK_SNACKBAR_TEXT_DARK  = 0xFFE6E0E9;

    // ============================================================
    // Fields
    // ============================================================

    @Nullable
    private final SharedPreferences prefs;

    @Nullable
    private final Vibrator vibrator;

    private final Handler mainHandler =
            new Handler(Looper.getMainLooper());

    private final Object pulseLock = new Object();

    @Nullable
    private Runnable pendingPulse1;

    @Nullable
    private Runnable pendingPulse2;

    /**
     * Fallback value only.
     *
     * SharedPreferences is the authoritative source.
     */
    @NonNull
    private volatile Intensity currentIntensity;

    private volatile boolean destroyed = false;

    // ============================================================
    // Intensity enum
    // ============================================================

    public enum Intensity {

        OFF(0, "Haptics Disabled"),
        LIGHT(1, "Light Haptics"),
        MEDIUM(2, "Medium Haptics"),
        HEAVY(3, "Heavy Haptics");

        private final int id;
        private final String label;

        Intensity(int id, String label) {
            this.id = id;
            this.label = label;
        }

        public int getId() {
            return id;
        }

        @NonNull
        public String getLabel() {
            return label;
        }

        @NonNull
        public static Intensity fromId(int id) {

            switch (id) {

                case 0:
                    return OFF;

                case 1:
                    return LIGHT;

                case 2:
                    return MEDIUM;

                case 3:
                    return HEAVY;

                default:
                    return MEDIUM;
            }
        }
    }

    // ============================================================
    // Construction
    // ============================================================

    public HapticFeedbackManager(@NonNull Context context) {

        Context appContext = context;

        try {

            Context ctx = context.getApplicationContext();

            if (ctx != null) {
                appContext = ctx;
            }

        } catch (Throwable t) {

            Log.e(TAG,
                    "getApplicationContext failed",
                    t);
        }

        // --------------------------------------------------------
        // SharedPreferences
        // --------------------------------------------------------

        SharedPreferences localPrefs = null;

        try {

            localPrefs = appContext.getSharedPreferences(
                    PREF_NAME,
                    Context.MODE_PRIVATE);

        } catch (Throwable t) {

            Log.e(TAG,
                    "SharedPreferences open failed",
                    t);

            localPrefs = null;
        }

        this.prefs = localPrefs;

        // --------------------------------------------------------
        // Vibrator
        // --------------------------------------------------------

        Vibrator localVibrator = null;

        try {

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

                VibratorManager vibratorManager =
                        (VibratorManager) appContext.getSystemService(
                                Context.VIBRATOR_MANAGER_SERVICE);

                if (vibratorManager != null) {
                    localVibrator =
                            vibratorManager.getDefaultVibrator();
                }
            }

            if (localVibrator == null) {

                localVibrator =
                        (Vibrator) appContext.getSystemService(
                                Context.VIBRATOR_SERVICE);
            }

        } catch (Throwable t) {

            Log.e(TAG,
                    "Failed to obtain Vibrator service",
                    t);

            localVibrator = null;
        }

        this.vibrator = localVibrator;

        // --------------------------------------------------------
        // Initial intensity
        // --------------------------------------------------------

        Intensity loaded = Intensity.MEDIUM;

        try {

            if (prefs != null) {

                loaded = Intensity.fromId(
                        prefs.getInt(
                                KEY_INTENSITY,
                                Intensity.MEDIUM.getId()));
            }

        } catch (Throwable t) {

            Log.e(TAG,
                    "Failed to read haptic preference",
                    t);

            loaded = Intensity.MEDIUM;
        }

        this.currentIntensity = loaded;
    }

    // ============================================================
    // Public API
    // ============================================================

    /**
     * Cycles:
     *
     * OFF -> LIGHT -> MEDIUM -> HEAVY -> OFF
     *
     * Safe to call from any thread.
     */
    @NonNull
    public Intensity cycleNextLevel() {

        try {

            Intensity current =
                    readIntensityFromPrefs();

            Intensity next;

            switch (current) {

                case OFF:
                    next = Intensity.LIGHT;
                    break;

                case LIGHT:
                    next = Intensity.MEDIUM;
                    break;

                case MEDIUM:
                    next = Intensity.HEAVY;
                    break;

                case HEAVY:
                    next = Intensity.OFF;
                    break;

                default:
                    next = Intensity.MEDIUM;
                    break;
            }

            currentIntensity = next;

            try {

                if (prefs != null) {

                    prefs.edit()
                            .putInt(
                                    KEY_INTENSITY,
                                    next.getId())
                            .apply();
                }

            } catch (Throwable t) {

                Log.e(TAG,
                        "Failed to persist haptic preference",
                        t);
            }

        } catch (Throwable t) {

            Log.e(TAG,
                    "cycleNextLevel failed",
                    t);
        }

        return currentIntensity;
    }

    /**
     * Performs haptic feedback using the currently selected level.
     *
     * Android 13+:
     *     existing View.performHapticFeedback path
     *
     * Android 12 and below:
     *     dedicated Vibrator/VibrationEffect fallback
     */
    public void performFeedback(
            @NonNull final View view) {

        final Intensity level =
                readIntensityFromPrefs();

        if (level == Intensity.OFF) {
            return;
        }

        runOnMain(new Runnable() {

            @Override
            public void run() {

                try {

                    performFeedbackOnMain(
                            view,
                            level);

                } catch (Throwable t) {

                    Log.e(TAG,
                            "performFeedback failed",
                            t);
                }
            }
        });
    }

    /**
     * Shows a Material Snackbar with the current haptic level.
     */
    public void showStatusSnackbar(
            @NonNull final View view) {

        final String message =
                readIntensityFromPrefs()
                        .getLabel();

        runOnMain(new Runnable() {

            @Override
            public void run() {

                try {

                    showStatusSnackbarOnMain(
                            view,
                            message);

                } catch (Throwable t) {

                    Log.e(TAG,
                            "showStatusSnackbar failed",
                            t);
                }
            }
        });
    }

    /**
     * Returns the latest intensity.
     */
    @NonNull
    public Intensity getCurrentIntensity() {

        return readIntensityFromPrefs();
    }

    /**
     * Destroys this manager instance.
     */
    public void destroy() {

        if (destroyed) {
            return;
        }

        destroyed = true;

        try {

            cancelPendingPulses();

        } catch (Throwable t) {

            Log.e(TAG,
                    "destroy: cancel pending pulses failed",
                    t);
        }

        try {

            mainHandler.removeCallbacksAndMessages(null);

        } catch (Throwable t) {

            Log.e(TAG,
                    "destroy: removeCallbacks failed",
                    t);
        }

        /*
         * Only cancel the vibrator for the legacy path.
         *
         * This avoids accidentally interfering with unrelated modern
         * system haptics from another component.
         */
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {

            try {

                if (vibrator != null) {
                    vibrator.cancel();
                }

            } catch (Throwable t) {

                Log.e(TAG,
                        "destroy: vibrator cancel failed",
                        t);
            }
        }
    }

    // ============================================================
    // Preference read
    // ============================================================

    @NonNull
    private Intensity readIntensityFromPrefs() {

        try {

            if (prefs != null) {

                int id =
                        prefs.getInt(
                                KEY_INTENSITY,
                                Intensity.MEDIUM.getId());

                Intensity fresh =
                        Intensity.fromId(id);

                currentIntensity = fresh;

                return fresh;
            }

        } catch (Throwable t) {

            Log.e(TAG,
                    "readIntensityFromPrefs failed",
                    t);
        }

        return currentIntensity;
    }

    // ============================================================
    // Main-thread feedback dispatcher
    // ============================================================

    private void performFeedbackOnMain(
            @NonNull View view,
            @NonNull Intensity level) {

        if (destroyed) {
            return;
        }

        if (!safeIsShown(view)) {
            return;
        }

        /*
         * Android 13+:
         *
         * Keep the existing implementation.
         *
         * This is intentionally not replaced by the legacy
         * Vibrator implementation.
         */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

            performModernFeedback(
                    view,
                    level);

            return;
        }

        /*
         * Android 12 and below:
         *
         * Use the dedicated legacy implementation.
         */
        cancelPendingPulses();

        performLegacyFeedback(
                view.getContext(),
                level);
    }

    // ============================================================
    // Android 13+ implementation
    // ============================================================

    /**
     * Existing modern implementation.
     *
     * Kept separate so Android 13+ behavior remains unchanged.
     */
    private void performModernFeedback(
            @NonNull View view,
            @NonNull Intensity level) {

        if (destroyed) {
            return;
        }

        if (!safeIsShown(view)) {
            return;
        }

        cancelPendingPulses();

        switch (level) {

            case LIGHT:

                performPulse(view);

                break;

            case MEDIUM: {

                performPulse(view);

                final View v = view;

                Runnable pulse2 =
                        new Runnable() {

                            @Override
                            public void run() {

                                try {

                                    if (destroyed) {
                                        return;
                                    }

                                    performPulse(v);

                                } catch (Throwable t) {

                                    Log.e(TAG,
                                            "MEDIUM pulse 2 failed",
                                            t);

                                } finally {

                                    pendingPulse1 = null;
                                }
                            }
                        };

                pendingPulse1 = pulse2;

                safePostDelayed(
                        pulse2,
                        MEDIUM_PULSE_GAP_MS);

                break;
            }

            case HEAVY: {

                performPulse(view);

                final View v = view;

                Runnable pulse2 =
                        new Runnable() {

                            @Override
                            public void run() {

                                try {

                                    if (destroyed) {
                                        return;
                                    }

                                    performPulse(v);

                                } catch (Throwable t) {

                                    Log.e(TAG,
                                            "HEAVY pulse 2 failed",
                                            t);

                                } finally {

                                    pendingPulse1 = null;
                                }
                            }
                        };

                Runnable pulse3 =
                        new Runnable() {

                            @Override
                            public void run() {

                                try {

                                    if (destroyed) {
                                        return;
                                    }

                                    performPulse(v);

                                } catch (Throwable t) {

                                    Log.e(TAG,
                                            "HEAVY pulse 3 failed",
                                            t);

                                } finally {

                                    pendingPulse2 = null;
                                }
                            }
                        };

                pendingPulse1 = pulse2;
                pendingPulse2 = pulse3;

                safePostDelayed(
                        pulse2,
                        HEAVY_PULSE_GAP_MS);

                safePostDelayed(
                        pulse3,
                        HEAVY_PULSE_GAP_MS * 2L);

                break;
            }

            case OFF:
            default:
                break;
        }
    }

    // ============================================================
    // Android 10-12 / older implementation
    // ============================================================

    /**
     * Dedicated legacy implementation.
     *
     * API 29-32:
     *     Android predefined haptic effects.
     *
     * API 26-28:
     *     amplitude control when available, otherwise ON/OFF pattern.
     *
     * API 25 and below:
     *     ON/OFF legacy waveform.
     */
    @SuppressWarnings("deprecation")
    private boolean performLegacyFeedback(
            @NonNull Context context,
            @NonNull Intensity level) {

        if (destroyed) {
            return false;
        }

        if (level == Intensity.OFF) {
            return false;
        }

        if (vibrator == null) {
            return false;
        }

        // --------------------------------------------------------
        // Check vibrator availability
        // --------------------------------------------------------

        try {

            if (!vibrator.hasVibrator()) {
                return false;
            }

        } catch (Throwable t) {

            Log.e(TAG,
                    "hasVibrator failed",
                    t);

            return false;
        }

        /*
         * Cancel only our previous legacy vibration.
         *
         * This is important because Android may replace an existing
         * direct vibration when a new vibration is requested.
         */
        try {

            vibrator.cancel();

        } catch (Throwable t) {

            Log.w(TAG,
                    "Unable to cancel previous legacy vibration",
                    t);
        }

        // --------------------------------------------------------
        // Android 10-12
        // --------------------------------------------------------

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

            int effectId;

            switch (level) {

                case LIGHT:
                    effectId =
                            VibrationEffect.EFFECT_TICK;
                    break;

                case MEDIUM:
                    effectId =
                            VibrationEffect.EFFECT_CLICK;
                    break;

                case HEAVY:
                    effectId =
                            VibrationEffect.EFFECT_HEAVY_CLICK;
                    break;

                case OFF:
                default:
                    return false;
            }

            try {

                VibrationEffect effect =
                        VibrationEffect.createPredefined(
                                effectId);

                vibrator.vibrate(effect);

                return true;

            } catch (Throwable t) {

                /*
                 * Some OEM implementations can still fail or behave
                 * unexpectedly. Continue into the lower-level fallback
                 * instead of allowing the app to fail.
                 */
                Log.e(TAG,
                        "Predefined haptic failed",
                        t);
            }
        }

        // --------------------------------------------------------
        // Android 8-9
        // --------------------------------------------------------

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            try {

                /*
                 * First use true amplitude control when the hardware
                 * explicitly reports support.
                 */
                if (vibrator.hasAmplitudeControl()) {

                    int amplitude;
                    long duration;

                    switch (level) {

                        case LIGHT:

                            amplitude =
                                    LEGACY_LIGHT_AMPLITUDE;

                            duration =
                                    LEGACY_LIGHT_DURATION_MS;

                            break;

                        case MEDIUM:

                            amplitude =
                                    LEGACY_MEDIUM_AMPLITUDE;

                            duration =
                                    LEGACY_MEDIUM_DURATION_MS;

                            break;

                        case HEAVY:

                            amplitude =
                                    LEGACY_HEAVY_AMPLITUDE;

                            duration =
                                    LEGACY_HEAVY_DURATION_MS;

                            break;

                        case OFF:
                        default:
                            return false;
                    }

                    VibrationEffect effect =
                            VibrationEffect.createOneShot(
                                    duration,
                                    amplitude);

                    vibrator.vibrate(effect);

                    return true;
                }

            } catch (Throwable t) {

                Log.e(TAG,
                        "Amplitude-controlled haptic failed",
                        t);
            }

            /*
             * No amplitude control:
             *
             * Do NOT try different amplitudes here.
             *
             * Android documents that devices without amplitude control
             * can treat all non-zero amplitudes as full strength.
             *
             * Instead use a deliberate ON/OFF pattern.
             */
            try {

                long[] pattern =
                        getLegacyPattern(level);

                if (pattern != null) {

                    VibrationEffect effect =
                            VibrationEffect.createWaveform(
                                    pattern,
                                    -1);

                    vibrator.vibrate(effect);

                    return true;
                }

            } catch (Throwable t) {

                Log.e(TAG,
                        "Legacy waveform failed",
                        t);
            }

            return false;
        }

        // --------------------------------------------------------
        // Android 7 and below
        // --------------------------------------------------------

        try {

            long[] pattern =
                    getLegacyPattern(level);

            if (pattern != null) {

                vibrator.vibrate(
                        pattern,
                        -1);

                return true;
            }

        } catch (Throwable t) {

            Log.e(TAG,
                    "Legacy pre-26 vibration failed",
                    t);
        }

        return false;
    }

    // ============================================================
    // Legacy waveform selector
    // ============================================================

    @Nullable
    private static long[] getLegacyPattern(
            @NonNull Intensity level) {

        switch (level) {

            case LIGHT:
                return LEGACY_LIGHT_PATTERN;

            case MEDIUM:
                return LEGACY_MEDIUM_PATTERN;

            case HEAVY:
                return LEGACY_HEAVY_PATTERN;

            case OFF:
            default:
                return null;
        }
    }

    // ============================================================
    // Modern pulse primitive
    // ============================================================

    /**
     * Existing Android 13+ haptic implementation.
     *
     * The View path remains primary.
     *
     * The Vibrator fallback is retained only as an emergency
     * fallback and is not used for Android 12 and below because
     * those versions now have their own implementation above.
     */
    private boolean performPulse(
            @NonNull View view) {

        try {

            if (view.performHapticFeedback(
                    HAPTIC_CONSTANT)) {

                return true;
            }

        } catch (Throwable t) {

            Log.e(TAG,
                    "performHapticFeedback failed",
                    t);
        }

        return performVibratorPulse();
    }

    /**
     * Emergency Vibrator fallback used by the modern Android path.
     */
    @SuppressWarnings("deprecation")
    private boolean performVibratorPulse() {

        if (vibrator == null) {
            return false;
        }

        try {

            if (!vibrator.hasVibrator()) {
                return false;
            }

        } catch (Throwable t) {

            /*
             * If hasVibrator() itself fails, do not blindly continue.
             */
            Log.e(TAG,
                    "Vibrator availability check failed",
                    t);

            return false;
        }

        try {

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

                vibrator.vibrate(
                        VibrationEffect.createOneShot(
                                VIBRATOR_PULSE_MS,
                                VibrationEffect.DEFAULT_AMPLITUDE));

            } else {

                vibrator.vibrate(
                        VIBRATOR_PULSE_MS);
            }

            return true;

        } catch (Throwable t) {

            Log.e(TAG,
                    "Vibrator fallback failed",
                    t);

            return false;
        }
    }

    // ============================================================
    // Snackbar
    // ============================================================

    private void showStatusSnackbarOnMain(
            @NonNull View view,
            @NonNull String message) {

        if (destroyed) {
            return;
        }

        Snackbar snackbar = null;

        try {

            snackbar =
                    Snackbar.make(
                            view,
                            message,
                            Snackbar.LENGTH_SHORT);

        } catch (Throwable t) {

            Log.e(TAG,
                    "Snackbar.make failed",
                    t);
        }

        if (snackbar == null) {

            Log.w(TAG,
                    "Snackbar.make returned null");

            return;
        }

        boolean dark =
                safeIsNightMode(
                        view.getContext());

        int backgroundColor =
                safeThemeColor(
                        view,
                        com.google.android.material.R.attr
                                .colorSurfaceContainerHigh,
                        dark
                                ? FALLBACK_SNACKBAR_BG_DARK
                                : FALLBACK_SNACKBAR_BG_LIGHT);

        int textColor =
                safeThemeColor(
                        view,
                        com.google.android.material.R.attr
                                .colorOnSurface,
                        dark
                                ? FALLBACK_SNACKBAR_TEXT_DARK
                                : FALLBACK_SNACKBAR_TEXT_LIGHT);

        try {

            snackbar.setBackgroundTint(
                    backgroundColor);

        } catch (Throwable t) {

            Log.e(TAG,
                    "setBackgroundTint failed",
                    t);
        }

        try {

            View snackbarView =
                    snackbar.getView();

            if (snackbarView != null) {

                TextView textView =
                        snackbarView.findViewById(
                                com.google.android.material.R.id
                                        .snackbar_text);

                if (textView != null) {

                    textView.setTextColor(
                            textColor);
                }
            }

        } catch (Throwable t) {

            Log.e(TAG,
                    "Snackbar text color failed",
                    t);
        }

        try {

            snackbar.show();

        } catch (Throwable t) {

            Log.e(TAG,
                    "Snackbar.show failed",
                    t);
        }
    }

    // ============================================================
    // Pending modern callbacks
    // ============================================================

    private void cancelPendingPulses() {

        synchronized (pulseLock) {

            if (pendingPulse1 != null) {

                try {

                    mainHandler.removeCallbacks(
                            pendingPulse1);

                } catch (Throwable ignored) {
                }

                pendingPulse1 = null;
            }

            if (pendingPulse2 != null) {

                try {

                    mainHandler.removeCallbacks(
                            pendingPulse2);

                } catch (Throwable ignored) {
                }

                pendingPulse2 = null;
            }
        }
    }

    private void safePostDelayed(
            @NonNull Runnable runnable,
            long delayMs) {

        try {

            if (!mainHandler.postDelayed(
                    runnable,
                    delayMs)) {

                Log.w(TAG,
                        "postDelayed returned false");
            }

        } catch (Throwable t) {

            Log.e(TAG,
                    "postDelayed failed",
                    t);
        }
    }

    // ============================================================
    // General helpers
    // ============================================================

    private static boolean safeIsShown(
            @NonNull View view) {

        try {

            return view.isShown();

        } catch (Throwable t) {

            return false;
        }
    }

    private int safeThemeColor(
            @NonNull View view,
            int attrResId,
            int fallback) {

        try {

            return MaterialColors.getColor(
                    view,
                    attrResId,
                    fallback);

        } catch (Throwable t) {

            return fallback;
        }
    }

    private static boolean safeIsNightMode(
            @NonNull Context context) {

        try {

            int flags =
                    context.getResources()
                            .getConfiguration()
                            .uiMode
                            & android.content.res.Configuration
                                    .UI_MODE_NIGHT_MASK;

            return flags ==
                    android.content.res.Configuration
                            .UI_MODE_NIGHT_YES;

        } catch (Throwable t) {

            return false;
        }
    }

    private void runOnMain(
            @NonNull Runnable runnable) {

        try {

            if (Looper.myLooper() ==
                    Looper.getMainLooper()) {

                runnable.run();

            } else {

                if (!mainHandler.post(runnable)) {

                    Log.w(TAG,
                            "post returned false");
                }
            }

        } catch (Throwable t) {

            Log.e(TAG,
                    "runOnMain failed",
                    t);
        }
    }
}