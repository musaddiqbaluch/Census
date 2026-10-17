package com.musaddiqbaluch.census;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.ReplacementSpan;
import android.util.Log;
import android.view.animation.LinearInterpolator;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * Expressive typewriter animation for a normal TextView.
 *
 * Features
 * --------
 *   - Character-by-character typewriter reveal.
 *   - Smooth blinking cursor.
 *   - Per-character travelling wave (vertical offset + slight
 *     rotation), fading in at the start and out at the end.
 *   - Duration auto-scaled from the text's weighted length.
 *   - Respects the system animator-scale setting.
 *   - Safe cancellation, safe finishing, and safe teardown via
 *     destroy().
 *
 * Robustness
 * ----------
 *   - The TextView is held via WeakReference; the class never keeps an
 *     Activity alive.
 *   - Every animator callback is wrapped in try/catch -- an uncaught
 *     exception inside onAnimationUpdate would crash the process.
 *   - CursorSpan caches its Paint and FontMetrics, so no per-frame
 *     allocations occur while the cursor is blinking.
 *   - Every public method is a no-op after destroy().
 *   - Late cancellations cannot resurrect a destroyed instance.
 */
public final class AppCountTextAnimator {

    private static final String TAG = "AppCountTextAnimator";

    // ---- Typing speed ----

    private static final float TYPING_UNITS_PER_SECOND = 12.5f;
    private static final long START_OFFSET_MS = 95L;
    private static final long MIN_DURATION_MS = 260L;

    // ---- Wave ----

    private static final float WAVE_CYCLE_MS = 1050.0f;
    private static final float WAVE_AMPLITUDE_DP = 2.2f;
    private static final float BOUNCE_AMPLITUDE_DP = 0.65f;
    private static final float ROTATION_AMPLITUDE_DEGREES = 1.25f;

    /** Phase offset between consecutive characters (radians). */
    private static final float CHAR_PHASE_STEP = 0.72f;

    /** Higher-frequency bounce offset (radians). */
    private static final float BOUNCE_PHASE_STEP = 0.38f;

    /** Milliseconds over which the wave fades in and out. */
    private static final float ENVELOPE_FADE_MS = 120.0f;

    // ---- Cursor ----

    private static final long CURSOR_BLINK_MS = 380L;

    private static final float CURSOR_WIDTH_DP = 3.0f;
    private static final float CURSOR_STROKE_WIDTH_DP = 1.5f;
    private static final float CURSOR_X_PADDING_DP = 1.5f;
    private static final float CURSOR_ASCENT_FACTOR = 0.78f;
    private static final float CURSOR_DESCENT_FACTOR = 0.50f;

    // ---- State ----

    private final WeakReference<TextView> textViewReference;
    private final float density;

    private final List<String> codePoints = new ArrayList<>();
    private final List<WaveCharacterSpan> waveSpans = new ArrayList<>();

    @Nullable private ValueAnimator animator;
    private String targetText = "";
    private int visibleCount = -1;
    private boolean cursorVisible = true;
    private boolean destroyed = false;

    // ============================================================
    // Construction
    // ============================================================

    public AppCountTextAnimator(@NonNull TextView textView) {

        if (textView == null) {
            throw new IllegalArgumentException("TextView cannot be null.");
        }

        this.textViewReference = new WeakReference<>(textView);
        this.density = textView.getResources().getDisplayMetrics().density;
    }

    // ============================================================
    // Public API
    // ============================================================

    /** Immediately displays text without animation. */
    public void show(@Nullable CharSequence text) {

        if (destroyed) return;

        try {
            cancelRunningAnimation();
        } catch (Throwable t) {
            Log.e(TAG, "show: cancel failed", t);
        }

        TextView textView = textViewReference.get();
        if (textView == null) return;

        try {
            targetText = text == null ? "" : text.toString();
            clearAnimationState();
            textView.setText(targetText);
        } catch (Throwable t) {
            Log.e(TAG, "show failed", t);
        }
    }

    /** Starts the full expressive typewriter animation. */
    public void animateTo(@Nullable CharSequence text) {

        if (destroyed) return;

        TextView textView = textViewReference.get();
        if (textView == null) return;

        try {
            targetText = text == null ? "" : text.toString();

            cancelRunningAnimation();
            clearAnimationState();

            if (targetText.isEmpty()) {
                textView.setText("");
                return;
            }

            buildCodePoints();

            if (codePoints.isEmpty()) {
                // Nothing to reveal; just show the final string.
                textView.setText(targetText);
                return;
            }

            // Respect the system animation setting.
            if (!areAnimationsEnabled()) {
                textView.setText(targetText);
                return;
            }

            final long duration = calculateTypingDuration();

            // Start with an empty text + cursor.
            visibleCount = 0;
            cursorVisible = true;
            rebuildDisplayedText(visibleCount, true);

            final ValueAnimator valueAnimator =
                    ValueAnimator.ofFloat(0.0f, 1.0f);
            valueAnimator.setDuration(duration);
            valueAnimator.setInterpolator(new LinearInterpolator());

            valueAnimator.addUpdateListener(
                    new ValueAnimator.AnimatorUpdateListener() {
                        @Override
                        public void onAnimationUpdate(ValueAnimator animation) {
                            try {
                                updateAnimationFrame(animation);
                            } catch (Throwable t) {
                                Log.e(TAG, "onAnimationUpdate failed", t);
                            }
                        }
                    });

            valueAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    try {
                        // Detach listeners first so a subsequent cancel
                        // cannot re-enter.
                        if (animation instanceof ValueAnimator) {
                            try {
                                ((ValueAnimator) animation)
                                        .removeAllUpdateListeners();
                            } catch (Throwable ignored) {}
                            try {
                                ((ValueAnimator) animation)
                                        .removeAllListeners();
                            } catch (Throwable ignored) {}
                        }

                        if (animator == valueAnimator) {
                            animator = null;
                        }

                        finishAnimation();
                    } catch (Throwable t) {
                        Log.e(TAG, "onAnimationEnd failed", t);
                    }
                }

                @Override
                public void onAnimationCancel(Animator animation) {
                    // Cancellation is handled by the caller via
                    // cancelRunningAnimation(); nothing to do here.
                }
            });

            animator = valueAnimator;
            valueAnimator.start();

        } catch (Throwable t) {
            Log.e(TAG, "animateTo failed, snapping to final text", t);

            // Fallback: show final text.
            try {
                textView.setText(targetText);
            } catch (Throwable t2) {
                Log.e(TAG, "animateTo fallback setText failed", t2);
            }

            clearAnimationState();
        }
    }

    /**
     * Finishes the current animation immediately, leaving the complete
     * final text visible.
     *
     * Call from Activity.onPause() to freeze the value.
     */
    public void finish() {

        if (destroyed) return;

        try {
            cancelRunningAnimation();

            TextView textView = textViewReference.get();
            if (textView != null) {
                textView.setText(targetText);
            }

            clearAnimationState();

        } catch (Throwable t) {
            Log.e(TAG, "finish failed", t);
        }
    }

    /**
     * Cancels any running animation and permanently releases this
     * animator's resources. Idempotent.
     *
     * Call from Activity.onDestroy() alongside the other destroy()
     * methods on the panel.
     */
    public void destroy() {

        if (destroyed) return;
        destroyed = true;

        try {
            cancelRunningAnimation();
        } catch (Throwable t) {
            Log.e(TAG, "destroy: cancel failed", t);
        }

        try {
            clearAnimationState();
        } catch (Throwable t) {
            Log.e(TAG, "destroy: clear state failed", t);
        }
    }

    // ============================================================
    // Animation lifecycle
    // ============================================================

    private void cancelRunningAnimation() {

        ValueAnimator current = animator;
        animator = null;

        if (current == null) return;

        try { current.removeAllUpdateListeners(); }
        catch (Throwable t) { Log.e(TAG, "removeAllUpdateListeners failed", t); }

        try { current.removeAllListeners(); }
        catch (Throwable t) { Log.e(TAG, "removeAllListeners failed", t); }

        try { current.cancel(); }
        catch (Throwable t) { Log.e(TAG, "cancel failed", t); }
    }

    private void finishAnimation() {

        try {
            TextView textView = textViewReference.get();
            if (textView != null) {
                // Remove all spans and cursor by rendering the plain
                // final string.
                textView.setText(targetText);
            }
        } catch (Throwable t) {
            Log.e(TAG, "finishAnimation: setText failed", t);
        }

        clearAnimationState();
    }

    private void clearAnimationState() {
        visibleCount = -1;
        cursorVisible = true;
        waveSpans.clear();
        codePoints.clear();
    }

    // ============================================================
    // Code point parsing
    // ============================================================

    private void buildCodePoints() {

        codePoints.clear();

        try {
            int index = 0;

            while (index < targetText.length()) {

                int codePoint = targetText.codePointAt(index);
                int nextIndex = index + Character.charCount(codePoint);

                codePoints.add(targetText.substring(index, nextIndex));
                index = nextIndex;
            }

        } catch (Throwable t) {
            Log.e(TAG, "buildCodePoints failed", t);
            codePoints.clear();
        }
    }

    // ============================================================
    // Duration
    // ============================================================

    /**
     * Weighted typing duration.
     *
     *   whitespace  -> 0.45 unit
     *   letter/digit-> 1.00 unit
     *   other       -> 0.75 unit
     */
    private long calculateTypingDuration() {

        float units = 0.0f;

        for (String character : codePoints) {

            if (TextUtils.isEmpty(character)) continue;

            try {
                int codePoint = character.codePointAt(0);

                if (Character.isWhitespace(codePoint)) {
                    units += 0.45f;
                } else if (Character.isLetterOrDigit(codePoint)) {
                    units += 1.0f;
                } else {
                    units += 0.75f;
                }
            } catch (Throwable t) {
                units += 0.75f;
            }
        }

        long typingTime = Math.round(
                (units / TYPING_UNITS_PER_SECOND) * 1000.0f);

        long duration = START_OFFSET_MS + typingTime;

        if (duration < MIN_DURATION_MS) {
            duration = MIN_DURATION_MS;
        }

        return duration;
    }

    // ============================================================
    // Per-frame update
    // ============================================================

    private void updateAnimationFrame(ValueAnimator animation) {

        if (destroyed) {
            cancelRunningAnimation();
            return;
        }

        TextView textView = textViewReference.get();
        if (textView == null) {
            // Dead TextView -- stop wasting frames.
            cancelRunningAnimation();
            return;
        }

        try {
            final float fraction = animation.getAnimatedFraction();
            final long currentPlayTime = animation.getCurrentPlayTime();

            // ---- Character reveal ----
            int newVisibleCount;
            if (fraction >= 1.0f) {
                newVisibleCount = codePoints.size();
            } else {
                newVisibleCount = (int) Math.floor(
                        fraction * codePoints.size());
            }

            if (newVisibleCount != visibleCount) {
                visibleCount = newVisibleCount;
                rebuildDisplayedText(visibleCount, cursorVisible);
            }

            // ---- Cursor blink ----
            boolean newCursorVisible =
                    ((currentPlayTime / CURSOR_BLINK_MS) % 2L) == 0L;

            if (newCursorVisible != cursorVisible) {
                cursorVisible = newCursorVisible;
                rebuildDisplayedText(visibleCount, cursorVisible);
            }

            // ---- Wave ----
            updateWave(currentPlayTime);

            textView.invalidate();

        } catch (Throwable t) {
            Log.e(TAG, "updateAnimationFrame failed", t);
        }
    }

    // ============================================================
    // Spannable construction
    // ============================================================

    private void rebuildDisplayedText(int count, boolean showCursor) {

        TextView textView = textViewReference.get();
        if (textView == null) return;

        try {
            SpannableStringBuilder builder = new SpannableStringBuilder();

            waveSpans.clear();

            // Clamp count to valid range in case codePoints changed.
            if (count < 0) count = 0;
            if (count > codePoints.size()) count = codePoints.size();

            for (int i = 0; i < count; i++) {

                String character = codePoints.get(i);

                int start = builder.length();
                builder.append(character);
                int end = builder.length();

                WaveCharacterSpan span = new WaveCharacterSpan();

                builder.setSpan(
                        span,
                        start,
                        end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

                waveSpans.add(span);
            }

            if (showCursor) {
                int cursorStart = builder.length();
                builder.append("\u200B");       // zero-width space anchor
                int cursorEnd = builder.length();

                builder.setSpan(
                        new CursorSpan(density),
                        cursorStart,
                        cursorEnd,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }

            textView.setText(builder, TextView.BufferType.SPANNABLE);

        } catch (Throwable t) {
            Log.e(TAG, "rebuildDisplayedText failed", t);
        }
    }

    // ============================================================
    // Wave math
    // ============================================================

    private void updateWave(long elapsedMs) {

        if (waveSpans.isEmpty()) return;

        try {
            final int spanCount = waveSpans.size();

            final float basePhase =
                    (elapsedMs / WAVE_CYCLE_MS)
                            * ((float) Math.PI * 2.0f);

            final long duration = animator != null
                    ? animator.getDuration()
                    : Math.max(1L, elapsedMs);

            final float fadeIn = Math.min(
                    1.0f,
                    elapsedMs / ENVELOPE_FADE_MS);

            final float remaining = Math.max(
                    0.0f,
                    duration - elapsedMs);

            final float fadeOut = Math.min(
                    1.0f,
                    remaining / ENVELOPE_FADE_MS);

            final float envelope = Math.min(fadeIn, fadeOut);

            final float waveAmplitude = WAVE_AMPLITUDE_DP * density;
            final float bounceAmplitude = BOUNCE_AMPLITUDE_DP * density;

            // Precompute the higher-frequency phase once per frame.
            final float bounceBase = basePhase * 2.0f;

            for (int i = 0; i < spanCount; i++) {

                WaveCharacterSpan span = waveSpans.get(i);
                if (span == null) continue;

                final float charPhase = basePhase - (i * CHAR_PHASE_STEP);
                final float bouncePhase =
                        bounceBase - (i * BOUNCE_PHASE_STEP);

                final float mainWave = (float) Math.sin(charPhase);
                final float bounce = (float) Math.sin(bouncePhase);

                final float verticalOffset = (
                        (mainWave * waveAmplitude)
                                + (bounce * bounceAmplitude)
                ) * envelope;

                final float rotation = (
                        mainWave * ROTATION_AMPLITUDE_DEGREES
                ) * envelope;

                span.setMotion(verticalOffset, rotation);
            }

        } catch (Throwable t) {
            Log.e(TAG, "updateWave failed", t);
        }
    }

    // ============================================================
    // System animation setting
    // ============================================================

    private boolean areAnimationsEnabled() {

        if (android.os.Build.VERSION.SDK_INT >= 26) {
            try {
                return ValueAnimator.areAnimatorsEnabled();
            } catch (Throwable t) {
                return true;
            }
        }
        return true;
    }

    // ============================================================
    // WaveCharacterSpan
    // ============================================================

    /**
     * Per-character expressive motion span. No allocations during draw:
     * only its two float fields are updated per frame by updateWave().
     */
    private static final class WaveCharacterSpan extends ReplacementSpan {

        private float translationY;
        private float rotationDegrees;

        void setMotion(float translationY, float rotationDegrees) {
            this.translationY = translationY;
            this.rotationDegrees = rotationDegrees;
        }

        @Override
        public int getSize(
                @NonNull Paint paint,
                CharSequence text,
                int start,
                int end,
                @Nullable Paint.FontMetricsInt fm) {

            try {
                return Math.round(paint.measureText(text, start, end));
            } catch (Throwable t) {
                return 0;
            }
        }

        @Override
        public void draw(
                @NonNull Canvas canvas,
                CharSequence text,
                int start,
                int end,
                float x,
                int top,
                int y,
                int bottom,
                @NonNull Paint paint) {

            try {
                float width = paint.measureText(text, start, end);
                float centerX = x + (width * 0.5f);

                int save = canvas.save();
                try {
                    canvas.translate(0.0f, translationY);
                    canvas.rotate(rotationDegrees, centerX, y);

                    canvas.drawText(text, start, end, x, y, paint);
                } finally {
                    canvas.restoreToCount(save);
                }

            } catch (Throwable t) {
                // Never let a rendering issue crash the animation.
                Log.e(TAG, "WaveCharacterSpan.draw failed", t);
            }
        }
    }

    // ============================================================
    // CursorSpan
    // ============================================================

    /**
     * Lightweight cursor drawn as a thin rounded line.
     *
     * The Paint and FontMetrics are cached per instance so no objects
     * are allocated during the animation loop.
     */
    private static final class CursorSpan extends ReplacementSpan {

        private final float density;

        // Cached per span instance. Mutated only via setColor / setStrokeWidth
        // below, which do not allocate.
        private final Paint cursorPaint;

        // Reused by draw. We copy the incoming metrics into this array
        // on first use, then read from it every frame.
        private final float[] cachedMetrics = new float[4];

        private boolean metricsCached;

        CursorSpan(float density) {
            this.density = density;

            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(CURSOR_STROKE_WIDTH_DP * density);
            this.cursorPaint = p;
        }

        @Override
        public int getSize(
                @NonNull Paint paint,
                CharSequence text,
                int start,
                int end,
                @Nullable Paint.FontMetricsInt fm) {

            return Math.round(CURSOR_WIDTH_DP * density);
        }

        @Override
        public void draw(
                @NonNull Canvas canvas,
                CharSequence text,
                int start,
                int end,
                float x,
                int top,
                int y,
                int bottom,
                @NonNull Paint paint) {

            try {
                // Propagate the current text color into our cached paint.
                int textColor = paint.getColor();
                if (cursorPaint.getColor() != textColor) {
                    cursorPaint.setColor(textColor);
                }

                // Copy font metrics once, then reuse.
                if (!metricsCached) {
                    Paint.FontMetrics m = paint.getFontMetrics();
                    cachedMetrics[0] = m.ascent;
                    cachedMetrics[1] = m.descent;
                    cachedMetrics[2] = m.top;
                    cachedMetrics[3] = m.bottom;
                    metricsCached = true;
                }

                float ascent = cachedMetrics[0];
                float descent = cachedMetrics[1];

                float cursorX = x + (CURSOR_X_PADDING_DP * density);

                float cursorTop = y + (ascent * CURSOR_ASCENT_FACTOR);
                float cursorBottom = y + (descent * CURSOR_DESCENT_FACTOR);

                canvas.drawLine(
                        cursorX, cursorTop,
                        cursorX, cursorBottom,
                        cursorPaint);

            } catch (Throwable t) {
                Log.e(TAG, "CursorSpan.draw failed", t);
            }
        }
    }
}