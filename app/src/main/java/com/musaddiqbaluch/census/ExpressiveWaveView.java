package com.musaddiqbaluch.census;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.graphics.ColorUtils;

import com.google.android.material.color.MaterialColors;

public class ExpressiveWaveView extends View {

    private final Paint paint;
    private final Path path;

    private final float density;

    // Material 3 color used by the wave.
    private int waveColor;

    // Wave geometry.
    private float amplitude;
    private float wavelength;
    private float step;

    public ExpressiveWaveView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        path = new Path();

        init();
    }

    public ExpressiveWaveView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        path = new Path();

        init();
    }

    public ExpressiveWaveView(
            Context context,
            AttributeSet attrs,
            int defStyleAttr) {

        super(context, attrs, defStyleAttr);

        density = getResources().getDisplayMetrics().density;
        paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        path = new Path();

        init();
    }

    private void init() {

        /*
         * Stroke configuration.
         */
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(1.5f * density);

        /*
         * Wave dimensions.
         */
        amplitude = 2.0f * density;
        wavelength = 48.0f * density;
        step = 2.0f * density;

        /*
         * Resolve the Material 3 colors from the current theme.
         */
        resolveMaterialColor();

        setWillNotDraw(false);
    }

    /**
     * Resolves the wave color from Material 3 semantic color roles.
     *
     * colorOutlineVariant
     *       ↓
     * blended with
     *       ↓
     * colorSurfaceContainer
     *       ↓
     * final wave color
     */
    private void resolveMaterialColor() {

        int outlineVariant =
                MaterialColors.getColor(
                        this,
                        com.google.android.material.R.attr.colorOutlineVariant
                );

        int surfaceContainer =
                MaterialColors.getColor(
                        this,
                        com.google.android.material.R.attr.colorSurfaceContainer
                );

        /*
         * Same blending used in your border implementation:
         *
         * 0.75 = 75% surfaceContainer
         * 0.25 = 25% outlineVariant
         */
        waveColor =
                ColorUtils.blendARGB(
                        outlineVariant,
                        surfaceContainer,
                        0.75f
                );

        paint.setColor(waveColor);
    }

    /**
     * Refreshes the Material 3 color from the current theme.
     *
     * Useful when the theme/color scheme changes without
     * recreating this View instance.
     */
    public void refreshMaterialColor() {
        resolveMaterialColor();
        invalidate();
    }

    /**
     * Returns the currently resolved wave color.
     */
    public int getWaveColor() {
        return waveColor;
    }

    @Override
    protected void onDraw(Canvas canvas) {

        super.onDraw(canvas);

        float width = getWidth();
        float height = getHeight();

        if (width <= 0.0f || height <= 0.0f) {
            return;
        }

        float centerY = height * 0.5f;

        path.reset();

        boolean firstPoint = true;

        for (float x = 0.0f; x <= width; x += step) {

            float angle =
                    (x / wavelength)
                    * (float) Math.PI
                    * 2.0f;

            float y =
                    centerY
                    + ((float) Math.sin(angle) * amplitude);

            if (firstPoint) {
                path.moveTo(x, y);
                firstPoint = false;
            } else {
                path.lineTo(x, y);
            }
        }

        /*
         * Make sure the final edge of the view is reached
         * even when width is not an exact multiple of step.
         */
        if (width > 0.0f) {

            float angle =
                    (width / wavelength)
                    * (float) Math.PI
                    * 2.0f;

            float y =
                    centerY
                    + ((float) Math.sin(angle) * amplitude);

            path.lineTo(width, y);
        }

        canvas.drawPath(path, paint);
    }
}