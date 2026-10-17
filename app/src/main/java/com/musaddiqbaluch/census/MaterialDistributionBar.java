package com.musaddiqbaluch.census;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import com.airbnb.lottie.LottieAnimationView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.color.utilities.DynamicScheme;
import com.google.android.material.color.utilities.Hct;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Material 3 Expressive wavy distribution progress bar + artwork-driven
 * color panel.
 *
 * Requires: com.google.android.material:material:1.14.0 or newer.
 *
 * Seed extraction: MCU Score with Palette fallback (via ArtworkColorHelper).
 * Scheme generation: SchemeVibrant (via ArtworkColorHelper).
 *
 * Total tab = distribution gradient
 * ---------------------------------
 *   When "Total user apps" is selected, the wavy bar is painted with a
 *   horizontal gradient made of EVERY installer's colour (its scheme
 *   primary, so it is the exact colour you see when that tab is selected,
 *   automatically light/dark aware).
 *
 *   - Slices follow the exact tab order supplied by the host
 *     (installerInfos order; a synthesised Unknown entry is last).
 *   - Slice sizes are compressed (power curve) and given a minimum width,
 *     so a huge installer cannot swallow tiny ones and tiny ones stay
 *     visible (Material data-visualisation guidance: minimum legible
 *     segment size, no segment lost to scale).
 *   - Inside a slice the colour is held solid for the middle half, and the
 *     rest blends smoothly into the neighbouring slice.
 *   - When the bar is full the wave stops moving.
 *   - Material's LinearProgressIndicator only supports a single indicator
 *     colour, so the gradient is drawn as a SRC_ATOP foreground overlay
 *     clipped to the current progress (it only tints indicator pixels,
 *     never the track). To keep the clip exact, progress changes are driven
 *     by this class instead of Material's internal animation.
 *   - Switching Total <-> installer cross-fades the gradient in/out.
 *
 * Glass fill (optional)
 * ---------------------
 *   Three independent alpha controls drive the glass effect, each feeding
 *   the matching RoundedShapeDrawable's fill through the same
 *   GlassBackdropDrawable.compensateFill() path:
 *
 *     setBodyFillAlpha(a)      -> bodyOutline    (fill; stroke unaffected)
 *     setLabelFillAlpha(a)     -> labelLayout    (Android 11 and below only, see below)
 *     setAppCountFillAlpha(a)  -> appCountLayout (fill only, no stroke)
 *
 *   Each alpha is independent. 255 restores the fully opaque original look.
 *
 * Label stroke
 * ------------
 *   labelLayout's stroke uses the SAME role as bodyOutline's stroke
 *   (outlineVariant), blended a fixed amount toward `outline` to give it a
 *   slightly darker tint.
 *
 * Cold-start timing
 * -----------------
 *   When a non-total installer is selected but its source colour is not yet
 *   cached, the constructor kicks off a best-effort background prewarm so
 *   the icon decode + palette extraction overlaps with whatever the host
 *   Activity is doing during startup. By the time setDistribution() arrives
 *   the colour is usually already in the cache, so the render path takes
 *   the fast synchronous branch and the pending state is never shown.
 */
public final class MaterialDistributionBar {

    private static final String TAG = "DistBar";

    private static final String TOTAL_KEY = "total_user_apps";

    private static final String UNKNOWN_KEY = "unknown";

    private static final int UNKNOWN_SEED_COLOR = 0xFFB3261E;

    private static final String PREFS_NAME = "m3_dist_bar_v6";
    private static final String KEY_SELECTED_INSTALLER = "last_selected_key";
    private static final String SOURCE_COLOR_PREFIX = "installer_source_color:";

    private static final float TRACK_THICKNESS_DP = 8f;
    private static final float WAVELENGTH_DP = 40f;
    private static final float WAVE_AMPLITUDE_DP = 3f;
    private static final float GAP_DP = 4f;
    private static final float STOP_DP = 4f;

    private static final int PROGRESS_MAX = 10000;

    private static final float MIN_VISIBLE_THICKNESS_MULTIPLIER = 1.0f;

    private static final float END_GAP_DP = 16f;

    private static final long COLOR_ANIM_MS = 300L;
    private static final long LAUNCH_ANIM_MS = 650L;
    private static final long PROGRESS_ANIM_MS = 450L;

    /** Fraction of each gradient slice that stays a solid colour. */
    private static final float GRADIENT_PLATEAU = 0.50f;

    /** Power curve applied to slice fractions (1 = proportional, <1 = flatter). */
    private static final float SLICE_COMPRESS_EXPONENT = 0.65f;

    /** Minimum share of the gradient any installer slice may occupy. */
    private static final float MIN_SLICE_FRACTION = 0.09f;

    private static final float BORDER_LIGHTEN_AMOUNT = 0.35f;

    private static final float BORDER_FADE_DP = 145f;

    private static final float LABEL_STROKE_DARKEN_AMOUNT = 0.30f;

    /**
     * labelLayout stroke opacity (0-255) on Android 12+ (API 31+). The stroke
     * keeps the width your host gave it, but is drawn translucent so the
     * glass blur shows through it, like the card fill does. 255 = fully
     * opaque (no glass), about 140 = soft glass rim (default), about 90 =
     * very faint. Android 11 and below keep their previous opaque stroke.
     */
    private static final int LABEL_STROKE_ALPHA = 140;

    /**
     * labelLayout and appCountLayout fills use EXACTLY the same tone
     * (perceptual lightness) as colorSurfaceContainer on every tab, so the
     * cards sit at one constant level, like bodyOutline does. Only the hue
     * follows the selected tab. This is how much colour (HCT chroma) that
     * hue gets: 0 = pure neutral grey, about 8 = very subtle, 12 = subtle
     * (default), 24 = clearly coloured. The tone never changes with this
     * value, so raising it cannot make some tabs lighter or darker than
     * others.
     */
    private static final float CARD_FILL_CHROMA = 12f;

    private static final int DEFAULT_PRIMARY_LIGHT = 0xFF6750A4;
    private static final int DEFAULT_PRIMARY_DARK  = 0xFFD0BCFF;

    private static final int DEFAULT_ON_PRIMARY_LIGHT = 0xFFFFFFFF;
    private static final int DEFAULT_ON_PRIMARY_DARK  = 0xFF381E72;

    private static final int DEFAULT_SURFACE_LIGHT = 0xFFE6E0E9;
    private static final int DEFAULT_SURFACE_DARK  = 0xFF36343B;

    private static final int DEFAULT_SURFACE_CONTAINER_LOW_LIGHT = 0xFFF7F2FA;
    private static final int DEFAULT_SURFACE_CONTAINER_LOW_DARK = 0xFF211F26;

    private static final int DEFAULT_OUTLINE_VARIANT_LIGHT = 0xFFCAC4D0;
    private static final int DEFAULT_OUTLINE_VARIANT_DARK = 0xFF49454F;

    private static final int DEFAULT_PRIMARY_CONTAINER_LIGHT = 0xFFEADDFF;
    private static final int DEFAULT_PRIMARY_CONTAINER_DARK = 0xFF4F378B;

    private static final int DEFAULT_SURFACE_CONTAINER_LIGHT = 0xFFF3EDF7;
    private static final int DEFAULT_SURFACE_CONTAINER_DARK = 0xFF211F26;

    private static final int DEFAULT_SURFACE_BG_LIGHT = 0xFFFFFBFE;
    private static final int DEFAULT_SURFACE_BG_DARK = 0xFF1C1B1F;

    private static final int DEFAULT_SECONDARY_LIGHT = 0xFF625B71;
    private static final int DEFAULT_SECONDARY_DARK = 0xFFCCC2DC;

    private static final int DEFAULT_SECONDARY_CONTAINER_LIGHT = 0xFFE8DEF8;
    private static final int DEFAULT_SECONDARY_CONTAINER_DARK  = 0xFF4A4458;

    private static final int DEFAULT_OUTLINE_LIGHT = 0xFF79747E;
    private static final int DEFAULT_OUTLINE_DARK = 0xFF938F99;

    private final LinearProgressIndicator indicator;

    @Nullable private final View appCountTextLayout;
    @Nullable private final android.widget.TextView appCountText;

    @Nullable private final Cookie12SidedDrawable appCountBackground;

    @Nullable private RoundedShapeDrawable bodyOutlineDrawable;

    @NonNull
    private final List<RoundedShapeDrawable> borderDrawables =
            new ArrayList<RoundedShapeDrawable>();

    @Nullable private RoundedShapeDrawable appCountPillDrawable;
    @Nullable private ImageView appCountIcon;

    @Nullable private RoundedShapeDrawable labelLayoutDrawable;
    @Nullable private ImageView labelIcon;
    @Nullable private android.widget.TextView labelText;

    @Nullable private LottieAnimationView emoDroid;

    @Nullable private CookieRippleView rippleView;

    private final Context context;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger generation = new AtomicInteger(0);
    private final AtomicInteger gradientGeneration = new AtomicInteger(0);
    private final AtomicBoolean destroyGuard = new AtomicBoolean(false);
    private final SharedPreferences prefs;

    private final Map<String, Integer> sourceColorCache = new BoundedColorCache();
    private final Map<String, String> packageCache = new BoundedPackageCache();
    private final Map<String, Boolean> failedCache = new BoundedFailedCache();

    /** seed colour -> scheme primary (main thread only, cleared on theme change). */
    private final Map<Integer, Integer> schemePrimaryCache =
            new HashMap<Integer, Integer>();

    @Nullable private volatile Map<String, String> installedAppIndex;
    @Nullable private Future<?> activeColorTask;
    @Nullable private Future<?> gradientColorTask;

    @Nullable private View.OnLayoutChangeListener indicatorLayoutListener;

    @Nullable private GradientOverlayDrawable gradientDrawable;

    private int currentTotal = 0;

    private int waveSpeedPx = 0;
    private boolean waveFrozen = false;
    private int waveAmplitudePx = 0;
    private int gapPx = 0;
    private int stopPx = 0;
    private boolean heightLocked = false;
    @Nullable private ValueAnimator waveAmpAnim;

    private final LinkedHashMap<String, InstallerAggregate> currentAggregates =
            new LinkedHashMap<>();

    private String selectedKey = TOTAL_KEY;
    private boolean hasData = false;
    private volatile boolean destroyed = false;
    private boolean launchStarted = false;
    private int launchToken = 0;

    @Nullable private ValueAnimator launchAnimator;
    @Nullable private ValueAnimator progressAnimator;
    @Nullable private ValueAnimator gradientMixAnim;
    @Nullable private ValueAnimator indicatorColorAnim;
    @Nullable private ValueAnimator trackColorAnim;
    @Nullable private ValueAnimator textColorAnim;
    @Nullable private ValueAnimator surfaceColorAnim;
    @Nullable private ValueAnimator outlineColorAnim;
    @Nullable private ValueAnimator pillColorAnim;
    @Nullable private ValueAnimator labelFillColorAnim;
    @Nullable private ValueAnimator labelStrokeColorAnim;
    @Nullable private ValueAnimator secondaryColorAnim;
    @Nullable private ValueAnimator lottieColorAnim;

    @Nullable private String resolvingKey;

    private int lastAppliedIndicatorColor = Color.TRANSPARENT;
    private int lastAppliedOnIndicatorColor = Color.TRANSPARENT;
    private int lastAppliedSurfaceColor = Color.TRANSPARENT;
    private int lastAppliedOutlineColor = Color.TRANSPARENT;
    private int lastAppliedPillColor = Color.TRANSPARENT;
    private int lastAppliedLabelFillColor = Color.TRANSPARENT;
    private int lastAppliedLabelStrokeColor = Color.TRANSPARENT;
    private int lastAppliedSecondaryColor = Color.TRANSPARENT;

    private volatile int bodyFillAlpha = 255;
    private volatile int labelFillAlpha = 255;
    private volatile int appCountFillAlpha = 255;

    @Nullable private LottieThemeEngine.Roles lastAppliedLottieRoles;

    public MaterialDistributionBar(
            @NonNull LinearProgressIndicator indicator) {
        this(indicator, null, null);
    }

    public MaterialDistributionBar(
            @NonNull LinearProgressIndicator indicator,
            @Nullable View appCountTextLayout) {
        this(indicator, appCountTextLayout, null);
    }

    public MaterialDistributionBar(
            @NonNull LinearProgressIndicator indicator,
            @Nullable View appCountTextLayout,
            @Nullable android.widget.TextView appCountText) {

        this.indicator = indicator;
        this.appCountTextLayout = appCountTextLayout;
        this.appCountText = appCountText;
        this.context = indicator.getContext();

        SharedPreferences localPrefs = null;
        try {
            localPrefs = context.getSharedPreferences(
                    PREFS_NAME,
                    Context.MODE_PRIVATE
            );
        } catch (Throwable t) {
            Log.e(TAG, "Failed to open SharedPreferences", t);
        }
        this.prefs = localPrefs;

        if (appCountTextLayout != null) {
            int seedColor = safeThemeColor(
                    com.google.android.material.R.attr.colorPrimary,
                    DEFAULT_PRIMARY_LIGHT
            );

            Cookie12SidedDrawable background = null;
            try {
                background = new Cookie12SidedDrawable(seedColor);
                appCountTextLayout.setBackground(background);
            } catch (Throwable e) {
                Log.e(TAG, "Failed to set appCountTextLayout background", e);
                background = null;
            }

            this.appCountBackground = background;
        } else {
            this.appCountBackground = null;
        }

        if (appCountText != null) {
            try {
                int seedOnColor = safeThemeColor(
                        com.google.android.material.R.attr.colorOnPrimary,
                        DEFAULT_ON_PRIMARY_LIGHT
                );
                appCountText.setTextColor(seedOnColor);
                lastAppliedOnIndicatorColor = seedOnColor;
            } catch (Throwable e) {
                Log.e(TAG, "Failed to seed appCountText color", e);
            }
        }

        try {
            loadState();
        } catch (Throwable t) {
            Log.e(TAG, "loadState failed", t);
        }

        try {
            configureIndicator();
        } catch (Throwable t) {
            Log.e(TAG, "configureIndicator failed", t);
            safeApplyTotalColors();
        }

        try {
            installGradientOverlay();
        } catch (Throwable t) {
            Log.e(TAG, "installGradientOverlay failed", t);
        }
    }

    // ============================================================
    // Attach methods
    // ============================================================

    public void attachBodyOutline(@NonNull RoundedShapeDrawable drawable) {
        try {
            this.bodyOutlineDrawable = drawable;

            int initialFill = lastAppliedSurfaceColor != Color.TRANSPARENT
                    ? lastAppliedSurfaceColor
                    : getSurfaceContainerLowColor();
            applyBodySurfaceColor(initialFill);

            int initialStroke = lastAppliedOutlineColor != Color.TRANSPARENT
                    ? lastAppliedOutlineColor
                    : getOutlineVariantColor();
            applyOutlineVariantColor(initialStroke);
        } catch (Throwable t) {
            Log.e(TAG, "attachBodyOutline failed", t);
        }
    }

    public void attachBorderDrawable(@NonNull RoundedShapeDrawable drawable) {
        try {
            drawable.fadeEdges(BORDER_FADE_DP);

            borderDrawables.add(drawable);

            int initial = lastAppliedOutlineColor != Color.TRANSPARENT
                    ? lastAppliedOutlineColor
                    : getOutlineVariantColor();
            applyOutlineVariantColor(initial);
        } catch (Throwable t) {
            Log.e(TAG, "attachBorderDrawable failed", t);
        }
    }

    public void attachAppCountPill(
            @NonNull RoundedShapeDrawable pill,
            @NonNull ImageView icon) {

        try {
            this.appCountPillDrawable = pill;
            this.appCountIcon = icon;

            int initialPill = lastAppliedPillColor != Color.TRANSPARENT
                    ? lastAppliedPillColor
                    : getPillFillColor();
            applyAppCountPillColor(initialPill);

            int initialIcon = lastAppliedIndicatorColor != Color.TRANSPARENT
                    ? lastAppliedIndicatorColor
                    : getPrimaryColor();
            applyAppCountIconTint(initialIcon);
        } catch (Throwable t) {
            Log.e(TAG, "attachAppCountPill failed", t);
        }
    }

    public void attachLabelLayout(
            @NonNull RoundedShapeDrawable chip,
            @NonNull ImageView icon,
            @NonNull android.widget.TextView text) {

        try {
            this.labelLayoutDrawable = chip;
            this.labelIcon = icon;
            this.labelText = text;

            int initialFill = lastAppliedLabelFillColor != Color.TRANSPARENT
                    ? lastAppliedLabelFillColor
                    : getLabelFillColor();
            applyLabelFillColor(initialFill);

            int initialStroke = lastAppliedLabelStrokeColor != Color.TRANSPARENT
                    ? lastAppliedLabelStrokeColor
                    : labelStrokeFrom(
                            getOutlineVariantColor(), getOutlineColor());
            applyLabelStrokeColor(initialStroke);

            int initialSecondary = lastAppliedSecondaryColor != Color.TRANSPARENT
                    ? lastAppliedSecondaryColor
                    : getSecondaryColor();
            applySecondaryColor(initialSecondary);
        } catch (Throwable t) {
            Log.e(TAG, "attachLabelLayout failed", t);
        }
    }

    public void attachEmoDroid(@NonNull LottieAnimationView view) {
        try {
            this.emoDroid = view;
            LottieThemeEngine.Roles initial = lastAppliedLottieRoles != null
                    ? lastAppliedLottieRoles
                    : LottieThemeEngine.readStaticRoles(context);
            applyLottieRoles(initial);
        } catch (Throwable t) {
            Log.e(TAG, "attachEmoDroid failed", t);
        }
    }

    public void attachRippleView(@NonNull CookieRippleView view) {
        try {
            this.rippleView = view;

            int initial = lastAppliedIndicatorColor != Color.TRANSPARENT
                    ? lastAppliedIndicatorColor
                    : getPrimaryColor();
            view.setRingColor(initial);
            view.setGlowColor(initial);
        } catch (Throwable t) {
            Log.e(TAG, "attachRippleView failed", t);
        }
    }

    // ============================================================
    // Initial configuration
    // ============================================================

    private void configureIndicator() {

        float density = context.getResources().getDisplayMetrics().density;

        indicator.setIndeterminate(false);
        indicator.setMin(0);
        indicator.setMax(PROGRESS_MAX);

        int thicknessPx = Math.round(TRACK_THICKNESS_DP * density);
        indicator.setTrackThickness(thicknessPx);
        indicator.setTrackCornerRadius(thicknessPx / 2);
        gapPx = Math.round(GAP_DP * density);
        stopPx = Math.round(STOP_DP * density);
        indicator.setIndicatorTrackGapSize(gapPx);
        indicator.setTrackStopIndicatorSize(stopPx);

        int wavelengthPx = Math.round(WAVELENGTH_DP * density);
        indicator.setWavelength(wavelengthPx);
        indicator.setWaveAmplitude(Math.round(WAVE_AMPLITUDE_DP * density));
        indicator.setWaveSpeed(wavelengthPx);
        waveSpeedPx = wavelengthPx;
        waveAmplitudePx = Math.round(WAVE_AMPLITUDE_DP * density);
        waveFrozen = false;

        indicator.setWaveAmplitudeRampProgressMax(1.0f);

        indicator.setProgressCompat(0, false);

        indicatorLayoutListener = new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(
                    View v, int left, int top, int right, int bottom,
                    int oldLeft, int oldTop, int oldRight, int oldBottom) {
                try {
                    if (destroyed || !hasData) return;
                    int newWidth = right - left;
                    int oldWidth = oldRight - oldLeft;
                    if (newWidth == oldWidth || oldWidth == 0) return;
                    if (launchAnimator != null || progressAnimator != null) return;
                    indicator.setProgressCompat(
                            toIndicatorProgress(
                                    currentSelectionCount(), currentTotal),
                            false);
                } catch (Throwable ignored) {}
            }
        };
        indicator.addOnLayoutChangeListener(indicatorLayoutListener);

        InstallerColors colors;
        boolean haveInstallerColors = false;

        if (!TOTAL_KEY.equals(selectedKey)) {
            Integer cached = getCachedSourceColor(selectedKey);
            if (cached != null && cached != Color.TRANSPARENT) {
                colors = createInstallerColors(cached);
                haveInstallerColors = true;
            } else {
                colors = createTotalColors();
            }
        } else {
            colors = createTotalColors();
        }

        if (TOTAL_KEY.equals(selectedKey) || haveInstallerColors) {
            applyAllRoles(colors);
        } else {
            // Installer selected but its colour is not cached yet: apply the
            // total palette as a neutral pending state, and — critically —
            // kick off the icon decode + palette extraction now, in parallel
            // with whatever the host Activity is doing during startup. By
            // the time setDistribution() arrives the cache is usually warm
            // and this pending state is never actually seen.
            applyIndicatorColor(Color.TRANSPARENT);
            applyTrackColor(colors.trackColor);
            applyOnIndicatorColor(colors.onIndicatorColor);
            applyBodySurfaceColor(colors.surfaceContainerLowColor);
            applyOutlineVariantColor(colors.outlineVariantColor);
            applyAppCountPillColor(colors.appCountPillColor);
            applyLabelFillColor(colors.labelFillColor);
            applyLabelStrokeColor(colors.labelStrokeColor);
            applySecondaryColor(colors.secondaryColor);
            applyLottieRoles(colors.lottieRoles);

            prewarmSourceColor(selectedKey);
        }
    }

    private void loadState() {
        if (prefs == null) return;

        try {
            String key = prefs.getString(KEY_SELECTED_INSTALLER, TOTAL_KEY);
            selectedKey = (key == null || key.isEmpty()) ? TOTAL_KEY : key;
        } catch (Throwable t) {
            Log.e(TAG, "Failed to read selected installer", t);
            selectedKey = TOTAL_KEY;
        }

        try {
            for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                String preferenceKey = entry.getKey();
                if (preferenceKey == null) continue;
                if (!preferenceKey.startsWith(SOURCE_COLOR_PREFIX)) continue;
                Object value = entry.getValue();
                if (!(value instanceof Integer)) continue;

                String installerKey =
                        preferenceKey.substring(SOURCE_COLOR_PREFIX.length());
                synchronized (sourceColorCache) {
                    sourceColorCache.put(installerKey, (Integer) value);
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "Error loading source-color cache", e);
        }
    }

    // ============================================================
    // Public entry points
    // ============================================================

    public void setDistribution(
            int totalUserApps,
            @Nullable List<InstalledAppTracker.InstallerInfo> installerInfos) {

        final List<InstallerSnapshot> snapshots = new ArrayList<>();
        try {
            if (installerInfos != null) {
                for (InstalledAppTracker.InstallerInfo info : installerInfos) {
                    if (info == null) continue;
                    String key = info.getKey();
                    if (key == null) continue;
                    snapshots.add(new InstallerSnapshot(
                            key, info.getLabel(), info.getCount()));
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to snapshot installer infos", t);
            snapshots.clear();
        }

        final int total = Math.max(0, totalUserApps);

        try {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        try {
                            if (!destroyed) setDistributionOnMain(total, snapshots);
                        } catch (Throwable t) {
                            Log.e(TAG, "setDistributionOnMain post failed", t);
                            safeApplyTotalColors();
                        }
                    }
                });
            } else {
                setDistributionOnMain(total, snapshots);
            }
        } catch (Throwable t) {
            Log.e(TAG, "setDistribution failed", t);
        }
    }

    private void setDistributionOnMain(
            int totalUserApps,
            @NonNull List<InstallerSnapshot> snapshots) {

        try {
            currentTotal = Math.max(0, totalUserApps);
            hasData = true;

            int gen = generation.incrementAndGet();

            cancelBackgroundColorTask();
            resolvingKey = null;

            installedAppIndex = null;
            synchronized (failedCache) { failedCache.clear(); }
            currentAggregates.clear();

            for (InstallerSnapshot snapshot : snapshots) {
                if (snapshot == null) continue;
                String key = snapshot.key;
                if (key == null || key.isEmpty() || TOTAL_KEY.equals(key)) continue;
                int count = Math.max(0, snapshot.count);
                if (count <= 0) continue;

                String label = (snapshot.label == null || snapshot.label.isEmpty())
                        ? key : snapshot.label;

                InstallerAggregate aggregate = currentAggregates.get(key);
                if (aggregate == null) {
                    aggregate = new InstallerAggregate(key, label);
                    currentAggregates.put(key, aggregate);
                }
                aggregate.count += count;
            }

            long knownSum = 0;
            for (InstallerAggregate aggregate : currentAggregates.values()) {
                knownSum += aggregate.count;
            }

            if (currentTotal > knownSum) {
                int missing = currentTotal - (int) knownSum;
                InstallerAggregate unknownAggregate =
                        currentAggregates.get(UNKNOWN_KEY);
                if (unknownAggregate == null) {
                    unknownAggregate =
                            new InstallerAggregate(UNKNOWN_KEY, "Unknown");
                    currentAggregates.put(UNKNOWN_KEY, unknownAggregate);
                }
                unknownAggregate.count += missing;
            }

            if (!TOTAL_KEY.equals(selectedKey)
                    && !currentAggregates.containsKey(selectedKey)) {
                selectedKey = TOTAL_KEY;
                persistSelectedKey();
                cancelBackgroundColorTask();
                resolvingKey = null;
            }

            // Build the Total-tab gradient from every installer's colour.
            gradientGeneration.incrementAndGet();
            rebuildGradientStops();

            renderCurrentSelection(gen, false);

        } catch (Throwable t) {
            Log.e(TAG, "setDistributionOnMain failed", t);
            try {
                selectedKey = TOTAL_KEY;
                safeApplyTotalColors();
            } catch (Throwable ignored) {}
        }
    }

    public void setSelectedInstaller(@Nullable String key) {
        try {
            final String safeKey =
                    (key == null || key.trim().isEmpty()) ? TOTAL_KEY : key;

            if (Looper.myLooper() != Looper.getMainLooper()) {
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        try {
                            if (!destroyed) setSelectedInstallerOnMain(safeKey);
                        } catch (Throwable t) {
                            Log.e(TAG, "setSelectedInstallerOnMain post failed", t);
                        }
                    }
                });
            } else {
                setSelectedInstallerOnMain(safeKey);
            }
        } catch (Throwable t) {
            Log.e(TAG, "setSelectedInstaller failed", t);
        }
    }

    private void setSelectedInstallerOnMain(@NonNull String newKey) {
        try {
            if (newKey.equals(selectedKey)) return;

            selectedKey = newKey;
            persistSelectedKey();

            int gen = generation.incrementAndGet();

            cancelBackgroundColorTask();
            resolvingKey = null;
            cancelLaunchAnimation();

            if (!hasData) return;
            renderCurrentSelection(gen, true);
        } catch (Throwable t) {
            Log.e(TAG, "setSelectedInstallerOnMain failed", t);
        }
    }

    public void refreshThemeColors() {
        try {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        refreshThemeColors();
                    }
                });
                return;
            }
            if (destroyed) return;
            int gen = generation.incrementAndGet();
            cancelBackgroundColorTask();
            resolvingKey = null;

            // Light/dark or wallpaper colours changed: scheme primaries differ.
            schemePrimaryCache.clear();
            rebuildGradientStops();

            renderCurrentSelection(gen, true);
        } catch (Throwable t) {
            Log.e(TAG, "refreshThemeColors failed", t);
        }
    }

    public void setBodyFillAlpha(final int alpha) {
        try {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        try {
                            if (!destroyed) setBodyFillAlpha(alpha);
                        } catch (Throwable t) {
                            Log.e(TAG, "setBodyFillAlpha post failed", t);
                        }
                    }
                });
                return;
            }
            if (destroyed) return;

            bodyFillAlpha = Math.max(0, Math.min(255, alpha));
            applyBodySurfaceColor(getCurrentSurfaceColor());
        } catch (Throwable t) {
            Log.e(TAG, "setBodyFillAlpha failed", t);
        }
    }

    public void setLabelFillAlpha(final int alpha) {
        try {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        try {
                            if (!destroyed) setLabelFillAlpha(alpha);
                        } catch (Throwable t) {
                            Log.e(TAG, "setLabelFillAlpha post failed", t);
                        }
                    }
                });
                return;
            }
            if (destroyed) return;

            labelFillAlpha = Math.max(0, Math.min(255, alpha));
            applyLabelFillColor(getCurrentLabelFillColor());
        } catch (Throwable t) {
            Log.e(TAG, "setLabelFillAlpha failed", t);
        }
    }

    public void setAppCountFillAlpha(final int alpha) {
        try {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        try {
                            if (!destroyed) setAppCountFillAlpha(alpha);
                        } catch (Throwable t) {
                            Log.e(TAG, "setAppCountFillAlpha post failed", t);
                        }
                    }
                });
                return;
            }
            if (destroyed) return;

            appCountFillAlpha = Math.max(0, Math.min(255, alpha));
            applyAppCountPillColor(getCurrentPillColor());
            if (useUnifiedCardStyle()) {
                // labelLayout shares this alpha on Android 12+.
                applyLabelFillColor(getCurrentLabelFillColor());
            }
        } catch (Throwable t) {
            Log.e(TAG, "setAppCountFillAlpha failed", t);
        }
    }

    public void clear() {
        try {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        try {
                            if (!destroyed) clearOnMain();
                        } catch (Throwable t) {
                            Log.e(TAG, "clearOnMain post failed", t);
                        }
                    }
                });
            } else {
                clearOnMain();
            }
        } catch (Throwable t) {
            Log.e(TAG, "clear failed", t);
        }
    }

    private void clearOnMain() {
        try {
            generation.incrementAndGet();
            gradientGeneration.incrementAndGet();
            cancelBackgroundColorTask();
            cancelGradientColorTask();
            cancelLaunchAnimation();
            cancelColorAnims();

            currentTotal = 0;
            currentAggregates.clear();
            hasData = false;
            launchStarted = false;
            resolvingKey = null;
            selectedKey = TOTAL_KEY;
            persistSelectedKey();

            if (gradientDrawable != null) {
                gradientDrawable.setStops(null, null);
            }
            setGradientMix(1f, false);
            setWaveFrozen(false);

            applyAllRoles(createTotalColors());

            try { indicator.setProgressCompat(0, false); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            Log.e(TAG, "clearOnMain failed", t);
        }
    }

    // ============================================================
    // Rendering
    // ============================================================

    private void renderCurrentSelection(int gen, boolean userInitiated) {

        try {
            // Total tab shows the all-installer gradient; installer tabs show
            // their own solid colour. Cross-fade only on user interaction.
            setGradientMix(TOTAL_KEY.equals(selectedKey) ? 1f : 0f,
                    userInitiated);

            if (TOTAL_KEY.equals(selectedKey)) {
                cancelBackgroundColorTask();
                resolvingKey = null;
                InstallerColors colors = createTotalColors();
                int totalProgress =
                        computeVisibleProgress(currentTotal, currentTotal);

                if (!launchStarted && !userInitiated) {
                    applyAllRoles(colors);
                    startLaunchAnimation(totalProgress);
                    return;
                }
                animateToState(totalProgress, colors, userInitiated);
                return;
            }

            InstallerAggregate aggregate = currentAggregates.get(selectedKey);
            if (aggregate == null) {
                if (!hasData) return;
                selectedKey = TOTAL_KEY;
                persistSelectedKey();
                renderCurrentSelection(gen, userInitiated);
                return;
            }

            int progress = computeVisibleProgress(aggregate.count, currentTotal);

            if (isUnknownInstaller(aggregate)) {
                cancelBackgroundColorTask();
                resolvingKey = null;
                InstallerColors colors = createInstallerColors(UNKNOWN_SEED_COLOR);

                if (!launchStarted && !userInitiated) {
                    applyAllRoles(colors);
                    startLaunchAnimation(progress);
                    return;
                }
                animateToState(progress, colors, userInitiated);
                return;
            }

            Integer sourceColor = getCachedSourceColor(aggregate.key);
            if (sourceColor != null && sourceColor != Color.TRANSPARENT) {
                cancelBackgroundColorTask();
                resolvingKey = null;
                InstallerColors colors = createInstallerColors(sourceColor);

                if (!launchStarted && !userInitiated) {
                    applyAllRoles(colors);
                    startLaunchAnimation(progress);
                    return;
                }
                animateToState(progress, colors, userInitiated);
                return;
            }

            // ── Pending state ───────────────────────────────────────────
            // Colours for this installer are not cached yet. Keep whatever
            // is currently on screen, animate only the progress bar IF the
            // launch animation has already played (otherwise leave the bar
            // at 0 — startLaunchAnimation will run once colours arrive),
            // and let the background task's callback perform the crossfade.
            if (launchStarted) {
                animateProgressTo(progress);
            }

            if (!aggregate.key.equals(resolvingKey)) {
                startBackgroundColorResolution(
                        gen, aggregate, progress, !launchStarted);
            }

        } catch (Throwable t) {
            Log.e(TAG, "renderCurrentSelection failed", t);
            try {
                selectedKey = TOTAL_KEY;
                safeApplyTotalColors();
            } catch (Throwable ignored) {}
        }
    }

    private int computeVisibleProgress(int count, int total) {
        return toIndicatorProgress(count, total);
    }

    private int toIndicatorProgress(int count, int total) {
        if (total <= 0 || count <= 0) return 0;
        int minVisible = minVisibleProgress();
        int maxVisible = Math.max(minVisible, maxVisibleProgress());

        // Complete bar: fill the whole track, no reserved leftover at the end.
        if (count >= total) return PROGRESS_MAX;

        int scaled = Math.round((count / (float) total) * PROGRESS_MAX);
        int result = Math.max(scaled, minVisible);
        return Math.min(maxVisible, result);
    }

    private int trackWidthPx() {
        int width = indicator.getWidth()
                - indicator.getPaddingLeft()
                - indicator.getPaddingRight();
        if (width <= 0) {
            width = Math.round(
                    context.getResources().getDisplayMetrics().widthPixels
                            * 0.8f);
        }
        return Math.max(1, width);
    }

    private int minVisibleProgress() {
        float minPx = indicator.getTrackThickness()
                * MIN_VISIBLE_THICKNESS_MULTIPLIER;
        return Math.round((minPx / trackWidthPx()) * PROGRESS_MAX);
    }

    private int maxVisibleProgress() {
        float density = context.getResources().getDisplayMetrics().density;
        float reservePx = END_GAP_DP * density;
        return PROGRESS_MAX
                - Math.round((reservePx / trackWidthPx()) * PROGRESS_MAX);
    }

    private int currentSelectionCount() {
        if (TOTAL_KEY.equals(selectedKey)) return currentTotal;
        InstallerAggregate aggregate = currentAggregates.get(selectedKey);
        return aggregate == null ? 0 : aggregate.count;
    }

    /**
     * Best-effort background resolution of the installer source colour so
     * that by the time setDistribution() is called the cache is warm and
     * the render path takes the fast synchronous branch. Runs only the two
     * slow operations — icon decode and palette extraction. Does NOT touch
     * failedCache, so a prewarm miss does not stop the full resolution
     * later from trying the label-based / package-index fallbacks.
     */
    private void prewarmSourceColor(@NonNull final String key) {
        if (destroyed) return;
        if (getCachedSourceColor(key) != null) return;

        try {
            bgExecutor.submit(new Runnable() {
                @Override public void run() {
                    try {
                        if (destroyed) return;
                        if (getCachedSourceColor(key) != null) return;

                        Drawable icon = null;
                        try {
                            icon = context.getPackageManager()
                                    .getApplicationIcon(key);
                        } catch (Throwable ignored) {}

                        if (icon == null) return;
                        if (destroyed) return;

                        int sourceColor =
                                ArtworkColorHelper.extractSourceColor(icon);
                        if (sourceColor != Color.TRANSPARENT) {
                            cacheSourceColor(key, sourceColor);
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "prewarm failed", t);
                    }
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "prewarm submit failed", t);
        }
    }

    private void startBackgroundColorResolution(
            final int expectedGeneration,
            @NonNull final InstallerAggregate aggregate,
            final int progress,
            final boolean startLaunchWhenReady) {

        cancelBackgroundColorTask();
        resolvingKey = aggregate.key;

        final String key = aggregate.key;
        final String label = aggregate.label;

        try {
            activeColorTask = bgExecutor.submit(new Runnable() {
                @Override public void run() {

                    int sourceColor = Color.TRANSPARENT;

                    try {
                        if (destroyed
                                || generation.get() != expectedGeneration) {
                            return;
                        }

                        // Prewarm (or an earlier task) may already have
                        // populated the cache — in that case skip the whole
                        // icon decode + palette extraction.
                        Integer cached = getCachedSourceColor(key);
                        if (cached != null && cached != Color.TRANSPARENT) {
                            sourceColor = cached;
                        } else {
                            PackageManager packageManager =
                                    context.getPackageManager();
                            Drawable icon = resolveInstallerIcon(
                                    key, label, packageManager);

                            if (destroyed
                                    || generation.get() != expectedGeneration) {
                                return;
                            }

                            if (icon != null) {
                                sourceColor =
                                        ArtworkColorHelper.extractSourceColor(icon);
                            }
                        }

                    } catch (Throwable t) {
                        Log.e(TAG, "bg color resolution failed", t);
                        sourceColor = Color.TRANSPARENT;
                    }

                    final int result = sourceColor;

                    try {
                        mainHandler.post(new Runnable() {
                            @Override public void run() {
                                try {
                                    if (destroyed) return;

                                    if (generation.get() != expectedGeneration
                                            || !key.equals(selectedKey)) {
                                        if (key.equals(resolvingKey)) {
                                            resolvingKey = null;
                                        }
                                        return;
                                    }

                                    resolvingKey = null;

                                    InstallerColors colors =
                                            (result == Color.TRANSPARENT)
                                                    ? createTotalColors()
                                                    : createInstallerColors(result);

                                    if (result != Color.TRANSPARENT) {
                                        cacheSourceColor(key, result);
                                        rebuildGradientStops();
                                    }

                                    int actualProgress =
                                            computeVisibleProgress(
                                                    aggregate.count, currentTotal);

                                    if (!launchStarted && startLaunchWhenReady) {
                                        // Crossfade into the resolved palette
                                        // while the launch animation grows the
                                        // bar — never a hard snap.
                                        animateColorsOnly(colors);
                                        startLaunchAnimation(actualProgress);
                                    } else {
                                        animateColorsOnly(colors);
                                        animateProgressTo(actualProgress);
                                    }
                                } catch (Throwable t) {
                                    Log.e(TAG,
                                            "main color update failed", t);
                                    safeApplyTotalColors();
                                }
                            }
                        });
                    } catch (Throwable t) {
                        Log.e(TAG, "failed to post color update", t);
                    }
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "failed to submit color task", t);
            safeApplyTotalColors();
        }
    }

    // ============================================================
    // Total-tab distribution gradient
    // ============================================================

    private void installGradientOverlay() {
        GradientOverlayDrawable overlay = new GradientOverlayDrawable();
        gradientDrawable = overlay;

        // SRC_ATOP needs an offscreen layer so it only tints the indicator
        // pixels (never the window behind the bar).
        indicator.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        indicator.setForeground(overlay);

        overlay.setMix(TOTAL_KEY.equals(selectedKey) ? 1f : 0f);
    }

    /**
     * Rebuilds gradient stops from the current aggregates. Each installer's
     * colour is its scheme primary (identical to the colour shown when that
     * tab is selected). Slices are proportional to app counts; the middle
     * GRADIENT_PLATEAU of every slice is solid, the rest blends into the
     * neighbours.
     */
    private void rebuildGradientStops() {
        final GradientOverlayDrawable overlay = gradientDrawable;
        if (overlay == null || destroyed) return;

        try {
            if (currentAggregates.isEmpty()) {
                overlay.setStops(null, null);
                return;
            }

            List<InstallerAggregate> list =
                    new ArrayList<InstallerAggregate>(currentAggregates.values());

            // Keep the host's tab order (LinkedHashMap insertion order).

            long sum = 0;
            for (InstallerAggregate aggregate : list) sum += aggregate.count;
            if (sum <= 0) {
                overlay.setStops(null, null);
                return;
            }

            final List<InstallerAggregate> missing =
                    new ArrayList<InstallerAggregate>();

            int n = list.size();
            int[] sliceColors = new int[n];
            for (int i = 0; i < n; i++) {
                InstallerAggregate aggregate = list.get(i);
                int color = gradientColorFor(aggregate);
                if (color == Color.TRANSPARENT) {
                    color = getPrimaryColor();
                    missing.add(aggregate);
                }
                sliceColors[i] = color;
            }

            if (n == 1) {
                overlay.setStops(
                        new int[] { sliceColors[0], sliceColors[0] },
                        new float[] { 0f, 1f });
            } else {
                int[] colors = new int[n * 2];
                float[] positions = new float[n * 2];

                float[] weights = computeSliceWeights(list, sum);
                float start = 0f;
                for (int i = 0; i < n; i++) {
                    float fraction = weights[i];
                    float center = start + fraction / 2f;
                    float half = fraction * GRADIENT_PLATEAU / 2f;

                    float lo = center - half;
                    float hi = center + half;
                    if (i == 0) lo = 0f;
                    if (i == n - 1) hi = 1f;

                    colors[i * 2] = sliceColors[i];
                    colors[i * 2 + 1] = sliceColors[i];
                    positions[i * 2] = Math.max(0f, Math.min(1f, lo));
                    positions[i * 2 + 1] = Math.max(0f, Math.min(1f, hi));

                    start += fraction;
                }

                // Guarantee non-decreasing positions (float rounding).
                for (int i = 1; i < positions.length; i++) {
                    if (positions[i] < positions[i - 1]) {
                        positions[i] = positions[i - 1];
                    }
                }
                overlay.setStops(colors, positions);
            }

            if (!missing.isEmpty()) {
                startGradientColorResolution(missing);
            }
        } catch (Throwable t) {
            Log.e(TAG, "rebuildGradientStops failed", t);
        }
    }

    /**
     * Visual slice sizes (sum = 1). Raw fractions are flattened with a power
     * curve, then every slice is guaranteed MIN_SLICE_FRACTION; the space
     * needed for that is taken proportionally from the larger slices.
     */
    @NonNull
    private float[] computeSliceWeights(
            @NonNull List<InstallerAggregate> list, long sum) {

        final int n = list.size();
        final float[] w = new float[n];
        final float[] result = new float[n];

        float total = 0f;
        for (int i = 0; i < n; i++) {
            float fraction = list.get(i).count / (float) sum;
            w[i] = (float) Math.pow(fraction, SLICE_COMPRESS_EXPONENT);
            total += w[i];
        }
        for (int i = 0; i < n; i++) {
            w[i] = total > 0f ? w[i] / total : 1f / n;
        }

        final float floor = Math.min(MIN_SLICE_FRACTION, 0.9f / n);
        final boolean[] pinned = new boolean[n];

        for (int pass = 0; pass <= n; pass++) {
            float pinnedTotal = 0f;
            float freeTotal = 0f;
            for (int i = 0; i < n; i++) {
                if (pinned[i]) pinnedTotal += floor;
                else freeTotal += w[i];
            }
            float freeSpace = Math.max(0f, 1f - pinnedTotal);

            boolean changed = false;
            for (int i = 0; i < n; i++) {
                if (pinned[i] || freeTotal <= 0f) continue;
                if (w[i] / freeTotal * freeSpace < floor) {
                    pinned[i] = true;
                    changed = true;
                }
            }

            if (!changed) {
                for (int i = 0; i < n; i++) {
                    if (pinned[i]) result[i] = floor;
                    else result[i] = freeTotal > 0f
                            ? w[i] / freeTotal * freeSpace : floor;
                }
                return result;
            }
        }

        for (int i = 0; i < n; i++) result[i] = 1f / n;
        return result;
    }

    /** Returns TRANSPARENT when the installer's colour is not known yet. */
    private int gradientColorFor(@NonNull InstallerAggregate aggregate) {
        if (isUnknownInstaller(aggregate)) {
            return schemePrimaryFor(UNKNOWN_SEED_COLOR);
        }
        Integer source = getCachedSourceColor(aggregate.key);
        if (source != null && source != Color.TRANSPARENT) {
            return schemePrimaryFor(source);
        }
        return Color.TRANSPARENT;
    }

    private int schemePrimaryFor(int seed) {
        Integer cached = schemePrimaryCache.get(seed);
        if (cached != null) return cached;

        int primary = Color.TRANSPARENT;
        try {
            DynamicScheme scheme = ArtworkColorHelper.createScheme(context, seed);
            if (scheme != null) primary = scheme.getPrimary();
        } catch (Throwable t) {
            Log.e(TAG, "gradient createScheme failed", t);
        }
        if (primary == Color.TRANSPARENT) primary = getPrimaryColor();

        schemePrimaryCache.put(seed, primary);
        return primary;
    }

    /**
     * Resolves colours for installers whose artwork has not been analysed
     * yet (first launch / new installer), then rebuilds the gradient.
     */
    private void startGradientColorResolution(
            @NonNull List<InstallerAggregate> missing) {

        cancelGradientColorTask();

        final int expected = gradientGeneration.get();
        final List<String[]> work = new ArrayList<String[]>();
        for (InstallerAggregate aggregate : missing) {
            work.add(new String[] { aggregate.key, aggregate.label });
        }

        try {
            gradientColorTask = bgExecutor.submit(new Runnable() {
                @Override public void run() {
                    boolean changed = false;
                    try {
                        PackageManager packageManager =
                                context.getPackageManager();

                        for (String[] item : work) {
                            if (destroyed
                                    || gradientGeneration.get() != expected) {
                                return;
                            }
                            String key = item[0];
                            String label = item[1];

                            Integer cached = getCachedSourceColor(key);
                            if (cached != null && cached != Color.TRANSPARENT) {
                                continue;
                            }

                            Drawable icon = resolveInstallerIcon(
                                    key, label, packageManager);
                            if (icon == null) continue;

                            int source = ArtworkColorHelper.extractSourceColor(icon);
                            if (source != Color.TRANSPARENT) {
                                cacheSourceColor(key, source);
                                changed = true;
                            }
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "gradient color resolution failed", t);
                    }

                    if (!changed) return;

                    try {
                        mainHandler.post(new Runnable() {
                            @Override public void run() {
                                try {
                                    if (destroyed) return;
                                    if (gradientGeneration.get() != expected) {
                                        return;
                                    }
                                    rebuildGradientStops();
                                } catch (Throwable t) {
                                    Log.e(TAG, "gradient rebuild failed", t);
                                }
                            }
                        });
                    } catch (Throwable t) {
                        Log.e(TAG, "failed to post gradient rebuild", t);
                    }
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "failed to submit gradient task", t);
        }
    }

    private void cancelGradientColorTask() {
        try {
            if (gradientColorTask != null) {
                gradientColorTask.cancel(true);
                gradientColorTask = null;
            }
        } catch (Throwable ignored) {}
    }

    /** Cross-fades the gradient overlay in (Total) or out (installer tabs). */
    private void setGradientMix(float target, boolean animate) {
        final GradientOverlayDrawable overlay = gradientDrawable;
        if (overlay == null) return;

        try {
            if (gradientMixAnim != null) {
                gradientMixAnim.cancel();
                gradientMixAnim = null;
            }

            if (!animate) {
                overlay.setMix(target);
                return;
            }

            float from = overlay.getMix();
            if (from == target) return;

            ValueAnimator animator = ValueAnimator.ofFloat(from, target);
            animator.setDuration(COLOR_ANIM_MS);
            animator.setInterpolator(new DecelerateInterpolator(1.2f));
            animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(ValueAnimator a) {
                    try {
                        if (destroyed) return;
                        overlay.setMix((Float) a.getAnimatedValue());
                    } catch (Throwable ignored) {}
                }
            });
            gradientMixAnim = animator;
            animator.start();
        } catch (Throwable t) {
            Log.e(TAG, "setGradientMix failed", t);
            try { overlay.setMix(target); } catch (Throwable ignored) {}
        }
    }

    /**
     * Foreground overlay that paints the distribution gradient onto the
     * indicator pixels only (SRC_ATOP), clipped to the current progress so
     * the track is never tinted. The gradient is laid out over the bar's
     * full (total) length, so growing the bar reveals it like a wipe.
     */
    private final class GradientOverlayDrawable extends Drawable {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        @Nullable private int[] colors;
        @Nullable private float[] positions;
        @Nullable private Shader shader;

        private float shaderX0 = Float.NaN;
        private float shaderX1 = Float.NaN;
        private float mix = 0f;

        GradientOverlayDrawable() {
            paint.setStyle(Paint.Style.FILL);
            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP));
        }

        void setStops(@Nullable int[] newColors, @Nullable float[] newPositions) {
            colors = newColors;
            positions = newPositions;
            shader = null;
            invalidateSelf();
        }

        void setMix(float value) {
            mix = Math.max(0f, Math.min(1f, value));
            invalidateSelf();
        }

        float getMix() {
            return mix;
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            final int[] c = colors;
            final float[] p = positions;
            if (mix <= 0.001f || c == null || p == null) return;

            final int w = indicator.getWidth();
            final int h = indicator.getHeight();
            if (w <= 0 || h <= 0) return;

            final int padLeft = indicator.getPaddingLeft();
            final int padRight = indicator.getPaddingRight();
            final float trackW = w - padLeft - padRight;
            if (trackW <= 0f) return;

            final float progressFraction =
                    indicator.getProgress() / (float) PROGRESS_MAX;
            if (progressFraction <= 0f) return;

            // The gradient is only shown on the Total tab, which is full-length.
            final float fullFraction = 1f;

            final boolean rtl = indicator.getLayoutDirection()
                    == View.LAYOUT_DIRECTION_RTL;
            final float startX = rtl ? (w - padRight) : padLeft;
            final float dir = rtl ? -1f : 1f;

            final float x0 = startX;
            final float x1 = startX + dir * trackW * fullFraction;

            if (shader == null || x0 != shaderX0 || x1 != shaderX1) {
                shader = new LinearGradient(
                        x0, 0f, x1, 0f, c, p, Shader.TileMode.CLAMP);
                shaderX0 = x0;
                shaderX1 = x1;
            }

            final float density =
                    context.getResources().getDisplayMetrics().density;
            final float clipEnd = startX
                    + dir * (trackW * progressFraction + density);

            paint.setShader(shader);
            paint.setAlpha(Math.round(255f * mix));

            canvas.save();
            canvas.clipRect(
                    Math.min(startX, clipEnd), 0f,
                    Math.max(startX, clipEnd), (float) h);
            canvas.drawRect(0f, 0f, (float) w, (float) h, paint);
            canvas.restore();
        }

        @Override public void setAlpha(int alpha) { }

        @Override public void setColorFilter(@Nullable ColorFilter colorFilter) { }

        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    // ============================================================
    // Colour computation
    // ============================================================

    /**
     * Card fill at a constant level.
     *
     * Blending a container colour with a surface colour in RGB gives a
     * different perceived lightness for every hue (a yellow container comes
     * out lighter than a blue one). Instead this works in HCT: the TONE is
     * taken from the surface container role (identical for every tab, as
     * with bodyOutline), only the HUE comes from the tab's container, and
     * the chroma is a fixed, small amount. HCT keeps tone exact and lowers
     * chroma where a hue cannot reach it inside sRGB.
     */
    private int cardFillFrom(int surfaceContainer, int container) {
        try {
            Hct base = Hct.fromInt(surfaceContainer);
            Hct tint = Hct.fromInt(container);

            double chroma = Math.min(CARD_FILL_CHROMA, tint.getChroma());
            return Hct.from(tint.getHue(), chroma, base.getTone()).toInt();
        } catch (Throwable t) {
            Log.e(TAG, "cardFillFrom failed", t);
            return surfaceContainer;
        }
    }

    private int getPillFillColor() {
        return cardFillFrom(
                getSurfaceContainerColor(), getPrimaryContainerColor());
    }

    private int getLabelFillColor() {
        return cardFillFrom(
                getSurfaceContainerColor(), getSecondaryContainerColor());
    }

    private int labelStrokeFrom(int outlineVariant, int outline) {
        try {
            return ColorUtils.blendARGB(
                    outlineVariant, outline, LABEL_STROKE_DARKEN_AMOUNT);
        } catch (Throwable t) {
            return outlineVariant;
        }
    }

    @NonNull
    private InstallerColors createTotalColors() {
        try {
            int pillColor = getPillFillColor();
            int labelFill = getLabelFillColor();
            int labelStroke = labelStrokeFrom(
                    getOutlineVariantColor(), getOutlineColor());

            LottieThemeEngine.Roles lottie = safeStaticLottieRoles();

            return new InstallerColors(
                    getPrimaryColor(),
                    getTotalTrackColor(),
                    getOnPrimaryColor(),
                    getSurfaceContainerLowColor(),
                    getOutlineVariantColor(),
                    pillColor,
                    labelFill,
                    labelStroke,
                    getSecondaryColor(),
                    lottie
            );
        } catch (Throwable t) {
            Log.e(TAG, "createTotalColors failed", t);
            return createLastResortColors();
        }
    }

    @NonNull
    private InstallerColors createLastResortColors() {
        boolean dark = safeIsNightMode();

        LottieThemeEngine.Roles lottie;
        try {
            lottie = LottieThemeEngine.readStaticRoles(context);
        } catch (Throwable t) {
            lottie = new LottieThemeEngine.Roles(
                    dark ? DEFAULT_PRIMARY_DARK : DEFAULT_PRIMARY_LIGHT,
                    dark ? DEFAULT_PRIMARY_CONTAINER_DARK
                         : DEFAULT_PRIMARY_CONTAINER_LIGHT,
                    dark ? DEFAULT_SECONDARY_DARK : DEFAULT_SECONDARY_LIGHT,
                    dark ? DEFAULT_ON_PRIMARY_DARK : DEFAULT_ON_PRIMARY_LIGHT,
                    dark ? DEFAULT_OUTLINE_DARK : DEFAULT_OUTLINE_LIGHT
            );
        }

        int outlineVariant = dark
                ? DEFAULT_OUTLINE_VARIANT_DARK
                : DEFAULT_OUTLINE_VARIANT_LIGHT;
        int outline = dark
                ? DEFAULT_OUTLINE_DARK
                : DEFAULT_OUTLINE_LIGHT;

        return new InstallerColors(
                dark ? DEFAULT_PRIMARY_DARK : DEFAULT_PRIMARY_LIGHT,
                dark ? DEFAULT_SURFACE_DARK : DEFAULT_SURFACE_LIGHT,
                dark ? DEFAULT_ON_PRIMARY_DARK : DEFAULT_ON_PRIMARY_LIGHT,
                dark ? DEFAULT_SURFACE_CONTAINER_LOW_DARK
                     : DEFAULT_SURFACE_CONTAINER_LOW_LIGHT,
                outlineVariant,
                cardFillFrom(
                        dark ? DEFAULT_SURFACE_CONTAINER_DARK
                             : DEFAULT_SURFACE_CONTAINER_LIGHT,
                        dark ? DEFAULT_PRIMARY_CONTAINER_DARK
                             : DEFAULT_PRIMARY_CONTAINER_LIGHT),
                cardFillFrom(
                        dark ? DEFAULT_SURFACE_CONTAINER_DARK
                             : DEFAULT_SURFACE_CONTAINER_LIGHT,
                        dark ? DEFAULT_SECONDARY_CONTAINER_DARK
                             : DEFAULT_SECONDARY_CONTAINER_LIGHT),
                labelStrokeFrom(outlineVariant, outline),
                dark ? DEFAULT_SECONDARY_DARK : DEFAULT_SECONDARY_LIGHT,
                lottie
        );
    }

    @NonNull
    private InstallerColors createInstallerColors(int sourceColor) {
        try {
            if (sourceColor == Color.TRANSPARENT) return createTotalColors();

            DynamicScheme scheme = null;
            try {
                scheme = ArtworkColorHelper.createScheme(context, sourceColor);
            } catch (Throwable t) {
                Log.e(TAG, "createScheme failed", t);
            }

            if (scheme == null) return createTotalColors();

            int indicatorColor = scheme.getPrimary();
            int onIndicatorColor = scheme.getOnPrimary();
            int secondaryContainer = scheme.getSecondaryContainer();
            int surfaceContainerLow = scheme.getSurfaceContainerLow();
            int surfaceContainer = scheme.getSurfaceContainer();
            int outlineVariant = scheme.getOutlineVariant();
            int primaryContainer = scheme.getPrimaryContainer();
            int secondary = scheme.getSecondary();
            int outline = scheme.getOutline();

            LottieThemeEngine.Roles lottie;
            try {
                lottie = LottieThemeEngine.readSchemeRoles(context, scheme);
            } catch (Throwable t) {
                Log.e(TAG, "readSchemeRoles failed", t);
                lottie = safeStaticLottieRoles();
            }

            int trackColor =
                    ArtworkColorHelper.createTrackColor(context, secondaryContainer);

            if (indicatorColor == Color.TRANSPARENT) {
                throw new IllegalStateException("Scheme returned transparent primary");
            }
            if (trackColor == Color.TRANSPARENT) {
                throw new IllegalStateException("Scheme returned transparent container");
            }

            if (onIndicatorColor == Color.TRANSPARENT) onIndicatorColor = getOnPrimaryColor();
            if (surfaceContainerLow == Color.TRANSPARENT) surfaceContainerLow = getSurfaceContainerLowColor();
            if (surfaceContainer == Color.TRANSPARENT) surfaceContainer = getSurfaceContainerColor();
            if (outlineVariant == Color.TRANSPARENT) outlineVariant = getOutlineVariantColor();
            if (secondary == Color.TRANSPARENT) secondary = getSecondaryColor();
            if (primaryContainer == Color.TRANSPARENT) primaryContainer = getPrimaryContainerColor();
            if (outline == Color.TRANSPARENT) outline = getOutlineColor();

            return new InstallerColors(
                    indicatorColor,
                    trackColor,
                    onIndicatorColor,
                    surfaceContainerLow,
                    outlineVariant,
                    cardFillFrom(surfaceContainer, primaryContainer),
                    cardFillFrom(surfaceContainer, secondaryContainer),
                    labelStrokeFrom(outlineVariant, outline),
                    secondary,
                    lottie
            );

        } catch (Throwable e) {
            Log.e(TAG, "Scheme color generation failed", e);

            try {
                int fallbackSC = safeThemeColor(
                        com.google.android.material.R.attr.colorSecondaryContainer,
                        getTotalTrackColor());

                int fallbackOutlineVariant = getOutlineVariantColor();
                int fallbackOutline = getOutlineColor();

                return new InstallerColors(
                        getPrimaryColor(),
                        ArtworkColorHelper.createTrackColor(context, fallbackSC),
                        getOnPrimaryColor(),
                        getSurfaceContainerLowColor(),
                        fallbackOutlineVariant,
                        getPillFillColor(),
                        cardFillFrom(getSurfaceContainerColor(), fallbackSC),
                        labelStrokeFrom(fallbackOutlineVariant, fallbackOutline),
                        getSecondaryColor(),
                        safeStaticLottieRoles()
                );
            } catch (Throwable fallbackError) {
                Log.e(TAG, "Theme fallback failed", fallbackError);
                return createTotalColors();
            }
        }
    }

    // ============================================================
    // Icon resolution
    // ============================================================

    @Nullable
    private Drawable resolveInstallerIcon(
            @NonNull String key,
            @NonNull String label,
            @NonNull PackageManager packageManager) {

        try {
            if (isUnknownInstallerKey(key)) return null;

            String normalizedLabel = normalizeKey(label);
            if ("unknown".equals(normalizedLabel)
                    || normalizedLabel.contains("sideload")) {
                return null;
            }

            synchronized (failedCache) {
                if (Boolean.TRUE.equals(failedCache.get(key))) return null;
            }

            synchronized (packageCache) {
                String packageName = packageCache.get(key);
                if (packageName != null) {
                    try {
                        return safeCopyDrawable(
                                packageManager.getApplicationIcon(packageName));
                    } catch (Throwable ignored) {}
                }
            }

            try {
                Drawable icon = packageManager.getApplicationIcon(key);
                synchronized (packageCache) { packageCache.put(key, key); }
                return safeCopyDrawable(icon);
            } catch (Throwable ignored) {}

            Map<String, String> index = installedAppIndex;
            if (index == null) {
                synchronized (this) {
                    index = installedAppIndex;
                    if (index == null) {
                        index = new HashMap<>();
                        try {
                            List<ApplicationInfo> applications =
                                    packageManager.getInstalledApplications(0);
                            for (ApplicationInfo appInfo : applications) {
                                if (appInfo == null || appInfo.packageName == null) continue;
                                try {
                                    CharSequence appLabel =
                                            packageManager.getApplicationLabel(appInfo);
                                    if (appLabel == null) continue;
                                    String normalized = normalizeKey(appLabel.toString());
                                    if (!normalized.isEmpty()
                                            && !index.containsKey(normalized)) {
                                        index.put(normalized, appInfo.packageName);
                                    }
                                } catch (Throwable ignored) {}
                            }
                        } catch (Throwable e) {
                            Log.e(TAG, "Failed to index applications", e);
                        }
                        installedAppIndex = index;
                    }
                }
            }

            String candidate = index.get(normalizeKey(key));
            if (candidate == null) candidate = index.get(normalizeKey(label));

            if (candidate != null) {
                try {
                    Drawable icon = packageManager.getApplicationIcon(candidate);
                    synchronized (packageCache) { packageCache.put(key, candidate); }
                    return safeCopyDrawable(icon);
                } catch (Throwable ignored) {}
            }

            synchronized (failedCache) { failedCache.put(key, true); }
            return null;

        } catch (Throwable t) {
            Log.e(TAG, "resolveInstallerIcon failed", t);
            return null;
        }
    }

    @NonNull
    private Drawable safeCopyDrawable(@NonNull Drawable original) {
        try {
            Drawable.ConstantState state = original.getConstantState();
            if (state != null) {
                Drawable copy = state.newDrawable().mutate();
                if (copy != null) return copy;
            }
        } catch (Throwable ignored) {}
        try {
            return original.mutate();
        } catch (Throwable ignored) {
            return original;
        }
    }

    // ============================================================
    // Animation
    // ============================================================

    private void animateToState(
            int targetProgress,
            @NonNull InstallerColors target,
            boolean animate) {

        try {
            cancelLaunchAnimation();

            if (!animate) {
                applyAllRoles(target);
                try { indicator.setProgressCompat(targetProgress, false); }
                catch (Throwable ignored) {}
                setWaveFrozen(isFullProgress(targetProgress));
                return;
            }

            animateColorsOnly(target);
            animateProgressTo(targetProgress);

        } catch (Throwable t) {
            Log.e(TAG, "animateToState failed", t);
            safeApplyTotalColors();
        }
    }

    /**
     * Progress animation driven by this class (instead of Material's
     * internal one) so indicator.getProgress() always equals what is drawn.
     * The gradient overlay relies on that to clip exactly to the bar.
     */
    private void animateProgressTo(final int target) {
        try {
            cancelProgressAnimatorOnly();

            if (!isFullProgress(target)) setWaveFrozen(false);

            final int from = indicator.getProgress();
            if (from == target) {
                setWaveFrozen(isFullProgress(target));
                return;
            }

            final ValueAnimator animator = ValueAnimator.ofInt(from, target);
            animator.setDuration(PROGRESS_ANIM_MS);
            animator.setInterpolator(new DecelerateInterpolator(1.35f));

            animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(ValueAnimator a) {
                    if (destroyed) return;
                    try {
                        indicator.setProgressCompat(
                                (Integer) a.getAnimatedValue(), false);
                    } catch (Throwable ignored) {}
                }
            });

            animator.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator a) {
                    if (progressAnimator == animator) progressAnimator = null;
                    if (!destroyed) {
                        try { indicator.setProgressCompat(target, false); }
                        catch (Throwable ignored) {}
                        setWaveFrozen(isFullProgress(target));
                    }
                }
                @Override public void onAnimationCancel(Animator a) {
                    if (progressAnimator == animator) progressAnimator = null;
                }
            });

            progressAnimator = animator;
            animator.start();
        } catch (Throwable t) {
            Log.e(TAG, "animateProgressTo failed", t);
            try { indicator.setProgressCompat(target, false); }
            catch (Throwable ignored) {}
        }
    }

    /** True when the bar is at (or within a pixel of) its complete length. */
    private boolean isFullProgress(int progress) {
        return progress >= PROGRESS_MAX;
    }

    /**
     * Full bar -> flat and still (amplitude 0, speed 0). Otherwise the
     * wave is restored. The view height is pinned the first time so the
     * layout below does not shift when the amplitude changes.
     */
    private void setWaveFrozen(boolean frozen) {
        try {
            if (waveFrozen == frozen) return;
            waveFrozen = frozen;

            if (waveAmpAnim != null) {
                waveAmpAnim.cancel();
                waveAmpAnim = null;
            }

            if (!heightLocked && indicator.getHeight() > 0) {
                android.view.ViewGroup.LayoutParams lp =
                        indicator.getLayoutParams();
                if (lp != null) {
                    lp.height = indicator.getHeight();
                    indicator.setLayoutParams(lp);
                    heightLocked = true;
                }
            }

            if (!frozen) indicator.setWaveSpeed(waveSpeedPx);

            // Full bar: no track remains, so drop the gap and the end stop
            // dot (the leftover "extra" piece at the end of the bar).
            indicator.setIndicatorTrackGapSize(frozen ? 0 : gapPx);
            indicator.setTrackStopIndicatorSize(frozen ? 0 : stopPx);

            final int from = indicator.getWaveAmplitude();
            final int to = frozen ? 0 : waveAmplitudePx;
            if (from == to) {
                if (frozen) indicator.setWaveSpeed(0);
                return;
            }

            final boolean endFrozen = frozen;
            ValueAnimator animator = ValueAnimator.ofInt(from, to);
            animator.setDuration(COLOR_ANIM_MS);
            animator.setInterpolator(new DecelerateInterpolator(1.2f));
            animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(ValueAnimator a) {
                    try {
                        if (destroyed) return;
                        indicator.setWaveAmplitude((Integer) a.getAnimatedValue());
                    } catch (Throwable ignored) {}
                }
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator a) {
                    if (!destroyed && endFrozen && waveFrozen) {
                        try { indicator.setWaveSpeed(0); }
                        catch (Throwable ignored) {}
                    }
                }
            });
            waveAmpAnim = animator;
            animator.start();
        } catch (Throwable t) {
            Log.e(TAG, "setWaveFrozen failed", t);
        }
    }

    private void cancelProgressAnimatorOnly() {
        try {
            if (progressAnimator != null) {
                ValueAnimator a = progressAnimator;
                progressAnimator = null;
                a.cancel();
            }
        } catch (Throwable ignored) {}
    }

    private void animateColorsOnly(@NonNull InstallerColors target) {
        try {
            cancelColorAnims();

            indicatorColorAnim = buildAnimator(
                    getCurrentIndicatorColor(), target.indicatorColor,
                    new ColorSink() { @Override public void accept(int c) {
                        applyIndicatorColor(c); } });

            trackColorAnim = buildAnimator(
                    getCurrentTrackColor(), target.trackColor,
                    new ColorSink() { @Override public void accept(int c) {
                        applyTrackColor(c); } });

            textColorAnim = buildAnimator(
                    getCurrentOnIndicatorColor(), target.onIndicatorColor,
                    new ColorSink() { @Override public void accept(int c) {
                        applyOnIndicatorColor(c); } });

            surfaceColorAnim = buildAnimator(
                    getCurrentSurfaceColor(), target.surfaceContainerLowColor,
                    new ColorSink() { @Override public void accept(int c) {
                        applyBodySurfaceColor(c); } });

            outlineColorAnim = buildAnimator(
                    getCurrentOutlineColor(), target.outlineVariantColor,
                    new ColorSink() { @Override public void accept(int c) {
                        applyOutlineVariantColor(c); } });

            pillColorAnim = buildAnimator(
                    getCurrentPillColor(), target.appCountPillColor,
                    new ColorSink() { @Override public void accept(int c) {
                        applyAppCountPillColor(c); } });

            labelFillColorAnim = buildAnimator(
                    getCurrentLabelFillColor(), target.labelFillColor,
                    new ColorSink() { @Override public void accept(int c) {
                        applyLabelFillColor(c); } });

            labelStrokeColorAnim = buildAnimator(
                    getCurrentLabelStrokeColor(), target.labelStrokeColor,
                    new ColorSink() { @Override public void accept(int c) {
                        applyLabelStrokeColor(c); } });

            secondaryColorAnim = buildAnimator(
                    getCurrentSecondaryColor(), target.secondaryColor,
                    new ColorSink() { @Override public void accept(int c) {
                        applySecondaryColor(c); } });

            lottieColorAnim = buildLottieAnimator(
                    getCurrentLottieRoles(), target.lottieRoles);

        } catch (Throwable t) {
            Log.e(TAG, "animateColorsOnly failed", t);
        }
    }

    private interface ColorSink {
        void accept(int color);
    }

    @Nullable
    private ValueAnimator buildAnimator(
            int from, int to, @NonNull final ColorSink sink) {

        try {
            final ValueAnimator animator =
                    ValueAnimator.ofObject(new ArgbEvaluator(), from, to);
            animator.setDuration(COLOR_ANIM_MS);
            animator.setInterpolator(new DecelerateInterpolator(1.2f));

            animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(ValueAnimator a) {
                    try {
                        if (destroyed) return;
                        sink.accept((Integer) a.getAnimatedValue());
                    } catch (Throwable ignored) {}
                }
            });

            animator.start();
            return animator;
        } catch (Throwable t) {
            Log.e(TAG, "buildAnimator failed", t);
            return null;
        }
    }

    @Nullable
    private ValueAnimator buildLottieAnimator(
            @NonNull final LottieThemeEngine.Roles from,
            @NonNull final LottieThemeEngine.Roles to) {

        try {
            ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(COLOR_ANIM_MS);
            animator.setInterpolator(new DecelerateInterpolator(1.2f));

            animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(ValueAnimator a) {
                    try {
                        if (destroyed) return;
                        float t = (Float) a.getAnimatedValue();
                        applyLottieRoles(new LottieThemeEngine.Roles(
                                ColorUtils.blendARGB(from.head, to.head, t),
                                ColorUtils.blendARGB(from.headAccent, to.headAccent, t),
                                ColorUtils.blendARGB(from.secondary, to.secondary, t),
                                ColorUtils.blendARGB(from.eyes, to.eyes, t),
                                ColorUtils.blendARGB(from.outline, to.outline, t)
                        ));
                    } catch (Throwable ignored) {}
                }
            });

            animator.start();
            return animator;
        } catch (Throwable t) {
            Log.e(TAG, "buildLottieAnimator failed", t);
            return null;
        }
    }

    // ============================================================
    // Apply methods
    // ============================================================

    private void applyAllRoles(@NonNull InstallerColors colors) {
        applyIndicatorColor(colors.indicatorColor);
        applyTrackColor(colors.trackColor);
        applyOnIndicatorColor(colors.onIndicatorColor);
        applyBodySurfaceColor(colors.surfaceContainerLowColor);
        applyOutlineVariantColor(colors.outlineVariantColor);
        applyAppCountPillColor(colors.appCountPillColor);
        applyLabelFillColor(colors.labelFillColor);
        applyLabelStrokeColor(colors.labelStrokeColor);
        applySecondaryColor(colors.secondaryColor);
        applyLottieRoles(colors.lottieRoles);
    }

    private void safeApplyTotalColors() {
        try {
            applyAllRoles(createTotalColors());
        } catch (Throwable t) {
            Log.e(TAG, "safeApplyTotalColors failed", t);
            try {
                applyAllRoles(createLastResortColors());
            } catch (Throwable ignored) {}
        }
    }

    private void applyIndicatorColor(int color) {
        lastAppliedIndicatorColor = color;
        try { indicator.setIndicatorColor(color); }
        catch (Throwable e) { Log.e(TAG, "Failed indicator color", e); }
        applyAppCountFillColor(color);
        applyAppCountIconTint(color);
        applyRippleColor(color);
        applyRippleGlowColor(color);
    }

    private void applyRippleColor(int color) {
        if (rippleView == null) return;
        try { rippleView.setRingColor(color); }
        catch (Throwable e) { Log.e(TAG, "Failed ripple color", e); }
    }

    private void applyRippleGlowColor(int color) {
        if (rippleView == null) return;
        try { rippleView.setGlowColor(color); }
        catch (Throwable e) { Log.e(TAG, "Failed ripple glow color", e); }
    }

    private void applyAppCountFillColor(int color) {
        if (appCountBackground == null) return;
        try { appCountBackground.setFillColor(color); }
        catch (Throwable e) { Log.e(TAG, "Failed appCountTextLayout fill", e); }
    }

    private void applyAppCountIconTint(int color) {
        if (appCountIcon == null) return;
        try { appCountIcon.setImageTintList(ColorStateList.valueOf(color)); }
        catch (Throwable e) { Log.e(TAG, "Failed appCountIcon tint", e); }
    }

    private void applyTrackColor(int color) {
        try { indicator.setTrackColor(color); }
        catch (Throwable e) { Log.e(TAG, "Failed track color", e); }
    }

    private void applyOnIndicatorColor(int color) {
        lastAppliedOnIndicatorColor = color;
        if (appCountText == null) return;
        try { appCountText.setTextColor(color); }
        catch (Throwable e) { Log.e(TAG, "Failed appCountText color", e); }
    }

    private void applyBodySurfaceColor(int color) {
        lastAppliedSurfaceColor = color;
        if (bodyOutlineDrawable == null) return;
        try {
            int paint = color;
            int alpha = bodyFillAlpha;
            if (alpha < 255) {
                paint = GlassBackdropDrawable.compensateFill(
                        color, getSurfaceColor(), alpha);
            }
            bodyOutlineDrawable.setFillColor(ColorStateList.valueOf(paint));
        } catch (Throwable e) { Log.e(TAG, "Failed bodyOutline fill", e); }
    }

    private void applyOutlineVariantColor(int color) {
        lastAppliedOutlineColor = color;

        if (bodyOutlineDrawable != null) {
            try {
                bodyOutlineDrawable.setStrokeColor(
                        ColorStateList.valueOf(color)
                );
            } catch (Throwable e) {
                Log.e(TAG, "Failed bodyOutline stroke", e);
            }
        }

        if (borderDrawables.isEmpty()) return;

        int lightBorderColor;
        try {
            lightBorderColor = ColorUtils.blendARGB(
                    color,
                    getSurfaceContainerLowColor(),
                    BORDER_LIGHTEN_AMOUNT
            );
        } catch (Throwable t) {
            lightBorderColor = color;
        }

        final ColorStateList borderCsl =
                ColorStateList.valueOf(lightBorderColor);

        for (int i = 0; i < borderDrawables.size(); i++) {
            RoundedShapeDrawable drawable = borderDrawables.get(i);
            if (drawable == null) continue;
            try {
                drawable.setFillColor(borderCsl);
            } catch (Throwable e) {
                Log.e(TAG, "Failed border fill", e);
            }
        }
    }

    private void applyAppCountPillColor(int color) {
        lastAppliedPillColor = color;
        if (appCountPillDrawable == null) return;
        try {
            int paint = color;
            int alpha = appCountFillAlpha;
            if (alpha < 255) {
                paint = GlassBackdropDrawable.compensateFill(
                        color, getSurfaceColor(), alpha);
            }
            appCountPillDrawable.setFillColor(ColorStateList.valueOf(paint));
        } catch (Throwable e) { Log.e(TAG, "Failed pill fill", e); }
    }

    /**
     * Android 12+ (where the glass blur exists): labelLayout is styled
     * exactly like appCountLayout (same fill alpha), and its stroke is
     * drawn translucent so the blur shows through it too. Android 11 and
     * below keep the previous label look.
     */
    private static boolean useUnifiedCardStyle() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }

    private void applyLabelFillColor(int color) {
        lastAppliedLabelFillColor = color;
        if (labelLayoutDrawable == null) return;
        try {
            int paint = color;
            int alpha = useUnifiedCardStyle() ? appCountFillAlpha : labelFillAlpha;
            if (alpha < 255) {
                paint = GlassBackdropDrawable.compensateFill(
                        color, getSurfaceColor(), alpha);
            }
            labelLayoutDrawable.setFillColor(ColorStateList.valueOf(paint));
        } catch (Throwable e) { Log.e(TAG, "Failed label fill", e); }
    }

    private void applyLabelStrokeColor(int color) {
        lastAppliedLabelStrokeColor = color;
        if (labelLayoutDrawable == null) return;
        try {
            int paint = color;
            if (useUnifiedCardStyle()) {
                // Glass stroke: same colour, translucent, so the blurred
                // backdrop shows through the outline as well.
                paint = ColorUtils.setAlphaComponent(color, LABEL_STROKE_ALPHA);
            }
            labelLayoutDrawable.setStrokeColor(ColorStateList.valueOf(paint));
        } catch (Throwable e) { Log.e(TAG, "Failed label stroke", e); }
    }

    private void applySecondaryColor(int color) {
        lastAppliedSecondaryColor = color;
        if (labelText != null) {
            try { labelText.setTextColor(color); }
            catch (Throwable e) { Log.e(TAG, "Failed label text", e); }
        }
        if (labelIcon != null) {
            try { labelIcon.setImageTintList(ColorStateList.valueOf(color)); }
            catch (Throwable e) { Log.e(TAG, "Failed label icon tint", e); }
        }
    }

    private void applyLottieRoles(@NonNull LottieThemeEngine.Roles roles) {
        lastAppliedLottieRoles = roles;
        if (emoDroid == null) return;
        try { LottieThemeEngine.applyRoles(emoDroid, roles); }
        catch (Throwable e) { Log.e(TAG, "Failed Lottie roles", e); }
    }

    // ============================================================
    // Current-value getters
    // ============================================================

    private int getCurrentIndicatorColor() {
        try {
            int[] colors = indicator.getIndicatorColor();
            if (colors != null && colors.length > 0) return colors[0];
        } catch (Throwable ignored) {}
        return lastAppliedIndicatorColor != Color.TRANSPARENT
                ? lastAppliedIndicatorColor : getPrimaryColor();
    }

    private int getCurrentTrackColor() {
        try { return indicator.getTrackColor(); }
        catch (Throwable e) { return getTotalTrackColor(); }
    }

    private int getCurrentOnIndicatorColor() {
        return lastAppliedOnIndicatorColor != Color.TRANSPARENT
                ? lastAppliedOnIndicatorColor : getOnPrimaryColor();
    }

    private int getCurrentSurfaceColor() {
        return lastAppliedSurfaceColor != Color.TRANSPARENT
                ? lastAppliedSurfaceColor : getSurfaceContainerLowColor();
    }

    private int getCurrentOutlineColor() {
        return lastAppliedOutlineColor != Color.TRANSPARENT
                ? lastAppliedOutlineColor : getOutlineVariantColor();
    }

    private int getCurrentPillColor() {
        return lastAppliedPillColor != Color.TRANSPARENT
                ? lastAppliedPillColor : getPillFillColor();
    }

    private int getCurrentLabelFillColor() {
        return lastAppliedLabelFillColor != Color.TRANSPARENT
                ? lastAppliedLabelFillColor : getLabelFillColor();
    }

    private int getCurrentLabelStrokeColor() {
        return lastAppliedLabelStrokeColor != Color.TRANSPARENT
                ? lastAppliedLabelStrokeColor
                : labelStrokeFrom(getOutlineVariantColor(), getOutlineColor());
    }

    private int getCurrentSecondaryColor() {
        return lastAppliedSecondaryColor != Color.TRANSPARENT
                ? lastAppliedSecondaryColor : getSecondaryColor();
    }

    @NonNull
    private LottieThemeEngine.Roles getCurrentLottieRoles() {
        return lastAppliedLottieRoles != null
                ? lastAppliedLottieRoles
                : safeStaticLottieRoles();
    }

    @NonNull
    private LottieThemeEngine.Roles safeStaticLottieRoles() {
        try {
            return LottieThemeEngine.readStaticRoles(context);
        } catch (Throwable t) {
            Log.e(TAG, "readStaticRoles failed", t);
            boolean dark = safeIsNightMode();
            return new LottieThemeEngine.Roles(
                    dark ? DEFAULT_PRIMARY_DARK : DEFAULT_PRIMARY_LIGHT,
                    dark ? DEFAULT_PRIMARY_CONTAINER_DARK
                         : DEFAULT_PRIMARY_CONTAINER_LIGHT,
                    dark ? DEFAULT_SECONDARY_DARK : DEFAULT_SECONDARY_LIGHT,
                    dark ? DEFAULT_ON_PRIMARY_DARK : DEFAULT_ON_PRIMARY_LIGHT,
                    dark ? DEFAULT_OUTLINE_DARK : DEFAULT_OUTLINE_LIGHT
            );
        }
    }

    // ============================================================
    // Launch animation
    // ============================================================

    private void startLaunchAnimation(int targetProgress) {

        if (destroyed || launchStarted) return;

        launchStarted = true;
        launchToken++;
        final int token = launchToken;
        cancelLaunchAnimatorOnly();
        setWaveFrozen(false);

        try { indicator.setProgressCompat(0, false); } catch (Throwable ignored) {}

        try {
            indicator.post(new Runnable() {
                @Override public void run() {
                    try {
                        if (destroyed || token != launchToken) return;
                        if (!indicator.isAttachedToWindow()) return;

                        final int target = toIndicatorProgress(
                                currentSelectionCount(), currentTotal);

                        ValueAnimator animator =
                                ValueAnimator.ofInt(0, target);
                        animator.setDuration(LAUNCH_ANIM_MS);
                        animator.setInterpolator(
                                new DecelerateInterpolator(1.35f));

                        animator.addUpdateListener(
                                new ValueAnimator.AnimatorUpdateListener() {
                                    @Override public void onAnimationUpdate(
                                            ValueAnimator a) {
                                        if (destroyed) return;
                                        try {
                                            indicator.setProgressCompat(
                                                    (Integer) a.getAnimatedValue(),
                                                    false);
                                        } catch (Throwable ignored) {}
                                    }
                                });

                        animator.addListener(new AnimatorListenerAdapter() {
                            @Override public void onAnimationEnd(Animator a) {
                                launchAnimator = null;
                                if (!destroyed) {
                                    try {
                                        indicator.setProgressCompat(
                                                target, false);
                                    } catch (Throwable ignored) {}
                                    setWaveFrozen(isFullProgress(target));
                                }
                            }
                            @Override public void onAnimationCancel(Animator a) {
                                launchAnimator = null;
                            }
                        });

                        launchAnimator = animator;
                        animator.start();

                    } catch (Throwable t) {
                        Log.e(TAG, "launch animation failed", t);
                    }
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "failed to post launch animation", t);
        }
    }

    private void cancelLaunchAnimation() {
        launchToken++;
        cancelLaunchAnimatorOnly();
    }

    private void cancelLaunchAnimatorOnly() {
        try {
            if (launchAnimator != null) {
                launchAnimator.cancel();
                launchAnimator = null;
            }
        } catch (Throwable ignored) {}
        cancelProgressAnimatorOnly();
    }

    private void cancelColorAnims() {
        try { if (indicatorColorAnim != null) { indicatorColorAnim.cancel(); indicatorColorAnim = null; } } catch (Throwable ignored) {}
        try { if (trackColorAnim != null) { trackColorAnim.cancel(); trackColorAnim = null; } } catch (Throwable ignored) {}
        try { if (textColorAnim != null) { textColorAnim.cancel(); textColorAnim = null; } } catch (Throwable ignored) {}
        try { if (surfaceColorAnim != null) { surfaceColorAnim.cancel(); surfaceColorAnim = null; } } catch (Throwable ignored) {}
        try { if (outlineColorAnim != null) { outlineColorAnim.cancel(); outlineColorAnim = null; } } catch (Throwable ignored) {}
        try { if (pillColorAnim != null) { pillColorAnim.cancel(); pillColorAnim = null; } } catch (Throwable ignored) {}
        try { if (labelFillColorAnim != null) { labelFillColorAnim.cancel(); labelFillColorAnim = null; } } catch (Throwable ignored) {}
        try { if (labelStrokeColorAnim != null) { labelStrokeColorAnim.cancel(); labelStrokeColorAnim = null; } } catch (Throwable ignored) {}
        try { if (secondaryColorAnim != null) { secondaryColorAnim.cancel(); secondaryColorAnim = null; } } catch (Throwable ignored) {}
        try { if (lottieColorAnim != null) { lottieColorAnim.cancel(); lottieColorAnim = null; } } catch (Throwable ignored) {}
    }

    private void cancelBackgroundColorTask() {
        try {
            if (activeColorTask != null) {
                activeColorTask.cancel(true);
                activeColorTask = null;
            }
        } catch (Throwable ignored) {}
    }

    // ============================================================
    // Static theme readers
    // ============================================================

    private int safeThemeColor(int attrResId, int fallback) {
        try {
            return MaterialColors.getColor(context, attrResId, fallback);
        } catch (Throwable t) {
            return fallback;
        }
    }

    private int getPrimaryColor() {
        int fallback = safeIsNightMode()
                ? DEFAULT_PRIMARY_DARK : DEFAULT_PRIMARY_LIGHT;
        return safeThemeColor(
                com.google.android.material.R.attr.colorPrimary, fallback);
    }

    private int getOnPrimaryColor() {
        int fallback = safeIsNightMode()
                ? DEFAULT_ON_PRIMARY_DARK : DEFAULT_ON_PRIMARY_LIGHT;
        return safeThemeColor(
                com.google.android.material.R.attr.colorOnPrimary, fallback);
    }

    private int getTotalTrackColor() {
        int fallback = safeIsNightMode()
                ? DEFAULT_SURFACE_DARK : DEFAULT_SURFACE_LIGHT;
        return safeThemeColor(
                com.google.android.material.R.attr.colorSurfaceContainerHighest,
                fallback);
    }

    private int getSurfaceContainerLowColor() {
        int fallback = safeIsNightMode()
                ? DEFAULT_SURFACE_CONTAINER_LOW_DARK
                : DEFAULT_SURFACE_CONTAINER_LOW_LIGHT;
        return safeThemeColor(
                com.google.android.material.R.attr.colorSurfaceContainerLow,
                fallback);
    }

    private int getOutlineVariantColor() {
        int fallback = safeIsNightMode()
                ? DEFAULT_OUTLINE_VARIANT_DARK : DEFAULT_OUTLINE_VARIANT_LIGHT;
        return safeThemeColor(
                com.google.android.material.R.attr.colorOutlineVariant,
                fallback);
    }

    private int getPrimaryContainerColor() {
        int fallback = safeIsNightMode()
                ? DEFAULT_PRIMARY_CONTAINER_DARK
                : DEFAULT_PRIMARY_CONTAINER_LIGHT;
        return safeThemeColor(
                com.google.android.material.R.attr.colorPrimaryContainer,
                fallback);
    }

    private int getSurfaceContainerColor() {
        int fallback = safeIsNightMode()
                ? DEFAULT_SURFACE_CONTAINER_DARK
                : DEFAULT_SURFACE_CONTAINER_LIGHT;
        return safeThemeColor(
                com.google.android.material.R.attr.colorSurfaceContainer,
                fallback);
    }

    private int getSurfaceColor() {
        int fallback = safeIsNightMode()
                ? DEFAULT_SURFACE_BG_DARK : DEFAULT_SURFACE_BG_LIGHT;
        return safeThemeColor(
                com.google.android.material.R.attr.colorSurface, fallback);
    }

    private int getSecondaryContainerColor() {
        return safeThemeColor(
                com.google.android.material.R.attr.colorSecondaryContainer,
                getTotalTrackColor());
    }

    private int getSecondaryColor() {
        int fallback = safeIsNightMode()
                ? DEFAULT_SECONDARY_DARK : DEFAULT_SECONDARY_LIGHT;
        return safeThemeColor(
                com.google.android.material.R.attr.colorSecondary, fallback);
    }

    private int getOutlineColor() {
        int fallback = safeIsNightMode()
                ? DEFAULT_OUTLINE_DARK : DEFAULT_OUTLINE_LIGHT;
        return safeThemeColor(
                com.google.android.material.R.attr.colorOutline, fallback);
    }

    // ============================================================
    // Cache / persistence / teardown
    // ============================================================

    @Nullable
    private Integer getCachedSourceColor(@NonNull String key) {
        try {
            synchronized (sourceColorCache) { return sourceColorCache.get(key); }
        } catch (Throwable t) {
            return null;
        }
    }

    private void cacheSourceColor(@NonNull String key, int color) {
        if (color == Color.TRANSPARENT) return;
        try {
            synchronized (sourceColorCache) { sourceColorCache.put(key, color); }
        } catch (Throwable ignored) {}
        try {
            if (prefs != null) {
                prefs.edit().putInt(SOURCE_COLOR_PREFIX + key, color).apply();
            }
        } catch (Throwable e) {
            Log.e(TAG, "Failed to persist source color", e);
        }
    }

    private void persistSelectedKey() {
        try {
            if (prefs != null) {
                prefs.edit()
                        .putString(KEY_SELECTED_INSTALLER, selectedKey)
                        .apply();
            }
        } catch (Throwable e) {
            Log.e(TAG, "Failed to persist selected key", e);
        }
    }

    public void destroy() {
        if (!destroyGuard.compareAndSet(false, true)) return;

        try {
            destroyed = true;
            generation.incrementAndGet();
            gradientGeneration.incrementAndGet();
            cancelBackgroundColorTask();
            cancelGradientColorTask();
            bgExecutor.shutdownNow();
            mainHandler.removeCallbacksAndMessages(null);

            if (Looper.myLooper() == Looper.getMainLooper()) {
                cleanup();
            } else {
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        try {
                            cleanup();
                        } catch (Throwable t) {
                            Log.e(TAG, "cleanup post failed", t);
                        }
                    }
                });
            }
        } catch (Throwable t) {
            Log.e(TAG, "destroy failed", t);
        }
    }

    private void cleanup() {
        try { cancelLaunchAnimation(); } catch (Throwable ignored) {}
        try { cancelColorAnims(); } catch (Throwable ignored) {}
        try {
            if (gradientMixAnim != null) {
                gradientMixAnim.cancel();
                gradientMixAnim = null;
            }
        } catch (Throwable ignored) {}
        try {
            if (waveAmpAnim != null) {
                waveAmpAnim.cancel();
                waveAmpAnim = null;
            }
        } catch (Throwable ignored) {}

        try {
            if (indicatorLayoutListener != null) {
                indicator.removeOnLayoutChangeListener(indicatorLayoutListener);
                indicatorLayoutListener = null;
            }
        } catch (Throwable ignored) {}

        try {
            if (gradientDrawable != null) {
                indicator.setForeground(null);
                gradientDrawable = null;
            }
        } catch (Throwable ignored) {}

        activeColorTask = null;
        gradientColorTask = null;
        resolvingKey = null;

        try { currentAggregates.clear(); } catch (Throwable ignored) {}
        try { schemePrimaryCache.clear(); } catch (Throwable ignored) {}

        try { synchronized (sourceColorCache) { sourceColorCache.clear(); } }
        catch (Throwable ignored) {}
        try { synchronized (packageCache) { packageCache.clear(); } }
        catch (Throwable ignored) {}
        try { synchronized (failedCache) { failedCache.clear(); } }
        catch (Throwable ignored) {}

        installedAppIndex = null;

        bodyOutlineDrawable = null;
        try { borderDrawables.clear(); } catch (Throwable ignored) {}

        appCountPillDrawable = null;
        appCountIcon = null;
        labelLayoutDrawable = null;
        labelIcon = null;
        labelText = null;
        emoDroid = null;
        rippleView = null;
        lastAppliedLottieRoles = null;
    }

    // ============================================================
    // Helpers
    // ============================================================

    private boolean isUnknownInstaller(@NonNull InstallerAggregate aggregate) {
        if (isUnknownInstallerKey(aggregate.key)) return true;
        String normalized = normalizeKey(aggregate.label);
        return "unknown".equals(normalized) || normalized.contains("sideload");
    }

    private boolean isUnknownInstallerKey(@NonNull String key) {
        String normalized = normalizeKey(key);
        return UNKNOWN_KEY.equals(normalized)
                || "__unknown_installer__".equals(normalized)
                || "null".equals(normalized)
                || "android".equals(normalized)
                || "com.android.shell".equals(normalized);
    }

    @NonNull
    private String normalizeKey(@Nullable String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isNightMode() {
        return safeIsNightMode();
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
    // Bounded LRU caches
    // ============================================================

    private static final class BoundedColorCache
            extends LinkedHashMap<String, Integer> {
        BoundedColorCache() { super(96, 0.75f, true); }
        @Override protected boolean removeEldestEntry(Map.Entry entry) {
            return size() > 64;
        }
    }

    private static final class BoundedPackageCache
            extends LinkedHashMap<String, String> {
        BoundedPackageCache() { super(96, 0.75f, true); }
        @Override protected boolean removeEldestEntry(Map.Entry entry) {
            return size() > 64;
        }
    }

    private static final class BoundedFailedCache
            extends LinkedHashMap<String, Boolean> {
        BoundedFailedCache() { super(96, 0.75f, true); }
        @Override protected boolean removeEldestEntry(Map.Entry entry) {
            return size() > 64;
        }
    }

    // ============================================================
    // Value holders
    // ============================================================

    private static final class InstallerColors {

        final int indicatorColor;
        final int trackColor;
        final int onIndicatorColor;
        final int surfaceContainerLowColor;
        final int outlineVariantColor;
        final int appCountPillColor;
        final int labelFillColor;
        final int labelStrokeColor;
        final int secondaryColor;
        @NonNull final LottieThemeEngine.Roles lottieRoles;

        InstallerColors(
                int indicatorColor,
                int trackColor,
                int onIndicatorColor,
                int surfaceContainerLowColor,
                int outlineVariantColor,
                int appCountPillColor,
                int labelFillColor,
                int labelStrokeColor,
                int secondaryColor,
                @NonNull LottieThemeEngine.Roles lottieRoles) {

            this.indicatorColor = indicatorColor;
            this.trackColor = trackColor;
            this.onIndicatorColor = onIndicatorColor;
            this.surfaceContainerLowColor = surfaceContainerLowColor;
            this.outlineVariantColor = outlineVariantColor;
            this.appCountPillColor = appCountPillColor;
            this.labelFillColor = labelFillColor;
            this.labelStrokeColor = labelStrokeColor;
            this.secondaryColor = secondaryColor;
            this.lottieRoles = lottieRoles;
        }
    }

    private static final class InstallerSnapshot {
        final String key;
        final String label;
        final int count;

        InstallerSnapshot(@NonNull String key, @Nullable String label, int count) {
            this.key = key;
            this.label = label == null ? key : label;
            this.count = count;
        }
    }

    private static final class InstallerAggregate {
        final String key;
        final String label;
        int count;

        InstallerAggregate(@NonNull String key, @NonNull String label) {
            this.key = key;
            this.label = label;
            this.count = 0;
        }
    }
}
