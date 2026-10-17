
package com.musaddiqbaluch.census;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.WindowInsets;
import android.view.WindowMetrics;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.divider.MaterialDivider;
import com.google.android.material.shape.CornerFamily;
import com.google.android.material.shape.MaterialShapeDrawable;
import com.google.android.material.shape.ShapeAppearanceModel;

/**
 * Responsive Material 3 release notes for Census.
 *
 * Features:
 * - Fixed bottom-aligned dialog; no BottomSheetBehavior.
 * - Stable position and rounded corners during scrolling.
 * - Responsive window sizing and available-screen calculations.
 * - Independent scrolling with a permanently visible action button.
 * - Supports tablet, landscape and accessibility font scaling.
 * - Dynamic Material theme colors.
 * - Cannot be dismissed by Back or touching outside.
 * - Dismissal only through the Nice button.
 * - Optional once-per-version display.
 * - Java 7 compatible.
 */
public final class ReleaseNotesDialog {

    private static final String TAG = "ReleaseNotesDialog";

    private static final String PREFS_NAME =
            "census_release_notes";

    private static final String KEY_LAST_VERSION =
            "last_shown_version_code";

    private static final String RELEASE_ICON =
            "icon_new_releases_round";

    private static final String BUTTON_TEXT = "Nice";

    // Maximum width on devices whose smallest width is tablet-sized.
    private static final int TABLET_MAX_WIDTH_DP = 600;

    // Maximum scrollable content height on ordinary screens.
    private static final int MAX_CONTENT_HEIGHT_DP = 440;

    // Keep some of the underlying screen visible above the sheet.
    private static final float MAX_DIALOG_HEIGHT_FRACTION = 0.94f;

    private ReleaseNotesDialog() {
        // Utility class.
    }

    // ============================================================
    // Public API
    // ============================================================

    /**
     * Show the release notes every time this method is called.
     */
    public static void show(@Nullable Activity activity) {
        showInternal(activity);
    }

    /**
     * Show only once for each installed app versionCode.
     *
     * Call from MainActivity.onCreate().
     */
    public static void showForCurrentVersion(
            @Nullable Activity activity) {

        if (!isActivityUsable(activity)) {
            return;
        }

        final long versionCode;

        try {
            PackageInfo info = activity.getPackageManager()
                    .getPackageInfo(
                            activity.getPackageName(), 0);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                versionCode = info.getLongVersionCode();
            } else {
                versionCode = info.versionCode;
            }

        } catch (Throwable t) {
            Log.e(TAG, "Unable to read app version", t);
            showInternal(activity);
            return;
        }

        final SharedPreferences preferences;

        try {
            preferences = activity.getSharedPreferences(
                    PREFS_NAME,
                    Context.MODE_PRIVATE);

            if (preferences.getLong(
                    KEY_LAST_VERSION, -1L) == versionCode) {
                return;
            }

        } catch (Throwable t) {
            Log.e(TAG, "Unable to read preferences", t);
            showInternal(activity);
            return;
        }

        if (showInternal(activity)) {
            try {
                preferences.edit()
                        .putLong(KEY_LAST_VERSION, versionCode)
                        .apply();

            } catch (Throwable t) {
                Log.e(TAG, "Unable to save version", t);
            }
        }
    }

    // ============================================================
    // Dialog construction
    // ============================================================

    private static boolean showInternal(
            @Nullable final Activity activity) {

        if (!isActivityUsable(activity)) {
            return false;
        }

        try {
            final Context ctx = activity;

            final int surfaceColor = getColor(
                    ctx,
                    com.google.android.material.R.attr.colorSurface,
                    Color.WHITE);

            final int titleColor = getColor(
                    ctx,
                    com.google.android.material.R.attr.colorOnSurface,
                    0xFF1D1B20);

            final int secondaryColor = getColor(
                    ctx,
                    com.google.android.material.R.attr.colorOnSurfaceVariant,
                    0xFF49454F);

            final int primaryColor = getColor(
                    ctx,
                    com.google.android.material.R.attr.colorPrimary,
                    0xFF6750A4);

            final int onPrimaryColor = getColor(
                    ctx,
                    com.google.android.material.R.attr.colorOnPrimary,
                    Color.WHITE);

            final int handleColor =
                    withAlpha(secondaryColor, 0.55f);

            // ----------------------------------------------------
            // Available window dimensions
            // ----------------------------------------------------

            int availableWidthPx =
                    getAvailableWidthPx(activity);

            int availableHeightPx =
                    getAvailableHeightPx(activity);

            float density = ctx.getResources()
                    .getDisplayMetrics().density;

            if (availableWidthPx <= 0) {
                availableWidthPx =
                        ctx.getResources()
                                .getDisplayMetrics().widthPixels;
            }

            if (availableHeightPx <= 0) {
                availableHeightPx =
                        ctx.getResources()
                                .getDisplayMetrics().heightPixels;
            }

            float availableHeightDp =
                    availableHeightPx / density;

            float fontScale = ctx.getResources()
                    .getConfiguration().fontScale;

            /*
             * Reduce decorative spacing on short screens and
             * when users enable larger accessibility text.
             */
            final boolean compact =
                    availableHeightDp < 620f || fontScale >= 1.30f;

            final int horizontalPadding =
                    dp(ctx, compact ? 20 : 24);

            final int verticalTopPadding =
                    dp(ctx, compact ? 8 : 12);

            final int verticalBottomPadding =
                    dp(ctx, compact ? 12 : 16);

            final int handleBottomMargin =
                    dp(ctx, compact ? 14 : 22);

            final int headerBottomMargin =
                    dp(ctx, compact ? 14 : 22);

            final int buttonTopMargin =
                    dp(ctx, compact ? 12 : 18);

            final int buttonMinHeight =
                    dp(ctx, compact ? 48 : 52);

            /*
             * Estimate the non-scrollable part of the sheet.
             * Reserve additional space for accessibility scaling.
             */
            float chromeDp = (compact ? 168f : 198f)
                    + Math.max(0f, fontScale - 1f) * 80f;

            int maxDialogHeightPx = Math.max(
                    dp(ctx, 1),
                    (int) (availableHeightPx
                            * MAX_DIALOG_HEIGHT_FRACTION));

            int reservedChromePx = dp(ctx, chromeDp);

            int remainingContentPx = Math.max(
                    dp(ctx, 1),
                    maxDialogHeightPx - reservedChromePx);

            int maxScrollHeightPx = Math.min(
                    dp(ctx, MAX_CONTENT_HEIGHT_DP),
                    remainingContentPx);

            // ----------------------------------------------------
            // Standard Dialog
            // ----------------------------------------------------

            /*
             * A regular Dialog has no BottomSheetBehavior.
             * Scrolling therefore cannot drag or resize the window.
             */
            final Dialog dialog = new Dialog(activity);

            dialog.setCancelable(false);
            dialog.setCanceledOnTouchOutside(false);

            // ----------------------------------------------------
            // Root sheet
            // ----------------------------------------------------

            LinearLayout root = new LinearLayout(ctx);
            root.setOrientation(LinearLayout.VERTICAL);

            root.setPadding(
                    horizontalPadding,
                    verticalTopPadding,
                    horizontalPadding,
                    verticalBottomPadding);

            root.setBackground(
                    createSheetBackground(
                            surfaceColor,
                            dp(ctx, 32)));

            root.setClipToOutline(true);

            // ----------------------------------------------------
            // Decorative handle
            // ----------------------------------------------------

            LinearLayout handleContainer =
                    new LinearLayout(ctx);

            handleContainer.setGravity(Gravity.CENTER);

            LinearLayout.LayoutParams handleContainerLp =
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dp(ctx, 8));

            handleContainerLp.topMargin = dp(ctx, 2);
            handleContainerLp.bottomMargin =
                    handleBottomMargin;

            View handle = new View(ctx);

            GradientDrawable handleBackground =
                    new GradientDrawable();

            handleBackground.setShape(
                    GradientDrawable.RECTANGLE);

            handleBackground.setColor(handleColor);
            handleBackground.setCornerRadius(dp(ctx, 20));

            handle.setBackground(handleBackground);

            handleContainer.addView(
                    handle,
                    new LinearLayout.LayoutParams(
                            dp(ctx, 36),
                            dp(ctx, 5)));

            root.addView(
                    handleContainer,
                    handleContainerLp);

            // ----------------------------------------------------
            // Header
            // ----------------------------------------------------

            LinearLayout header = new LinearLayout(ctx);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);

            ImageView icon = new ImageView(ctx);

            icon.setContentDescription("What's new");

            Drawable iconDrawable =
                    loadDrawable(ctx, RELEASE_ICON);

            if (iconDrawable != null) {
                icon.setImageDrawable(iconDrawable.mutate());

                icon.setImageTintList(
                        ColorStateList.valueOf(primaryColor));

            } else {
                Log.w(TAG, "Missing drawable: " + RELEASE_ICON);
            }

            header.addView(
                    icon,
                    new LinearLayout.LayoutParams(
                            dp(ctx, 36),
                            dp(ctx, 36)));

            TextView title = new TextView(ctx);

            title.setText("What's new");

            title.setTextSize(
                    TypedValue.COMPLEX_UNIT_SP, 25);

            title.setTypeface(
                    Typeface.DEFAULT,
                    Typeface.BOLD);

            title.setTextColor(titleColor);
            title.setGravity(Gravity.CENTER_VERTICAL);
            title.setIncludeFontPadding(true);

            LinearLayout.LayoutParams titleLp =
                    new LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            1f);

            titleLp.leftMargin = dp(ctx, 14);
            header.addView(title, titleLp);

            LinearLayout.LayoutParams headerLp =
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);

            headerLp.bottomMargin = headerBottomMargin;

            root.addView(header, headerLp);

            // ----------------------------------------------------
            // Divider
            // ----------------------------------------------------

            addDivider(ctx, root);

            // ----------------------------------------------------
            // Independently scrollable content
            // ----------------------------------------------------

            MaxHeightScrollView scroll =
                    new MaxHeightScrollView(
                            ctx,
                            maxScrollHeightPx);

            scroll.setFillViewport(false);
            scroll.setClipToPadding(false);

            scroll.setVerticalScrollBarEnabled(false);

            scroll.setOverScrollMode(
                    View.OVER_SCROLL_NEVER);

            // No scrolling parent or bottom-sheet gesture handling.
            scroll.setNestedScrollingEnabled(false);

            LinearLayout notes = new LinearLayout(ctx);
            notes.setOrientation(LinearLayout.VERTICAL);

            // 1. Settings button ripple fix

            addReleaseNote(
                    ctx,
                    notes,
                    "Fixed an intermittent settings-button ripple "
                            + "artifact that could appear while scrolling.",
                    secondaryColor,
                    primaryColor,
                    true);

            // 2. Toolbar improvements

            addReleaseNote(
                    ctx,
                    notes,
                    "Refined toolbar handling for more consistent "
                            + "behavior and smoother interactions.",
                    secondaryColor,
                    primaryColor,
                    true);

            // 3. Performance improvements

            addReleaseNote(
                    ctx,
                    notes,
                    "Optimized theme-color initialization and "
                            + "animation updates for a faster, "
                            + "more responsive experience.",
                    secondaryColor,
                    primaryColor,
                    true);

            // 4. Background animations and frosted glass

            addReleaseNote(
                    ctx,
                    notes,
                    "Introduced animated background circles and "
                            + "a frosted-glass treatment across "
                            + "key layouts.",
                    secondaryColor,
                    primaryColor,
                    true);

            // 5. Combined distribution bar

            addReleaseNote(
                    ctx,
                    notes,
                    "The main distribution bar now displays all "
                            + "installer-tab colors together in "
                            + "one segmented bar, with each "
                            + "colored segment representing that "
                            + "tab's share of installed apps.",
                    secondaryColor,
                    primaryColor,
                    true);

            // 6. Color refinement

            addReleaseNote(
                    ctx,
                    notes,
                    "Fine-tuned tonal brightness and color "
                            + "balance for a more cohesive, "
                            + "refined interface.",
                    secondaryColor,
                    primaryColor,
                    false);

            scroll.addView(
                    notes,
                    new NestedScrollView.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout.LayoutParams scrollLp =
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);

            scrollLp.topMargin = dp(ctx, 2);

            root.addView(scroll, scrollLp);

            // ----------------------------------------------------
            // Fixed acknowledgement button
            // ----------------------------------------------------

            MaterialButton button = new MaterialButton(ctx);

            button.setText(BUTTON_TEXT);
            button.setAllCaps(false);

            button.setTextSize(
                    TypedValue.COMPLEX_UNIT_SP, 16);

            button.setTypeface(
                    Typeface.DEFAULT,
                    Typeface.BOLD);

            button.setTextColor(onPrimaryColor);

            button.setBackgroundTintList(
                    ColorStateList.valueOf(primaryColor));

            button.setCornerRadius(dp(ctx, 40));

            button.setInsetTop(0);
            button.setInsetBottom(0);
            button.setInsetLeft(0);
            button.setInsetRight(0);

            button.setMinHeight(buttonMinHeight);
            button.setMinimumHeight(buttonMinHeight);
            button.setElevation(0f);

            button.setOnClickListener(
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            dialog.dismiss();
                        }
                    });

            LinearLayout.LayoutParams buttonLp =
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);

            buttonLp.topMargin = buttonTopMargin;

            root.addView(button, buttonLp);

            // ----------------------------------------------------
            // Dialog window
            // ----------------------------------------------------

            dialog.setContentView(root);

            dialog.setCancelable(false);
            dialog.setCanceledOnTouchOutside(false);

            Window window = dialog.getWindow();

            final boolean tablet =
                    ctx.getResources().getConfiguration()
                            .smallestScreenWidthDp >= 600;

            final int dialogWidth;

            if (tablet) {
                /*
                 * Center a readable-width sheet on tablets instead
                 * of stretching the release notes across the screen.
                 */
                dialogWidth = Math.min(
                        availableWidthPx,
                        dp(ctx, TABLET_MAX_WIDTH_DP));
            } else {
                /*
                 * Preserve the full-width bottom-sheet appearance
                 * on phones, including landscape phones.
                 */
                dialogWidth =
                        WindowManager.LayoutParams.MATCH_PARENT;
            }

            if (window != null) {
                window.setBackgroundDrawable(
                        new ColorDrawable(Color.TRANSPARENT));

                window.addFlags(
                        WindowManager.LayoutParams.FLAG_DIM_BEHIND);

                WindowManager.LayoutParams params =
                        window.getAttributes();

                params.gravity =
                        Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;

                params.width = dialogWidth;
                params.height =
                        WindowManager.LayoutParams.WRAP_CONTENT;

                params.dimAmount = 0.32f;

                window.setAttributes(params);

                if (Build.VERSION.SDK_INT >=
                        Build.VERSION_CODES.LOLLIPOP) {
                    window.setNavigationBarColor(surfaceColor);
                }
            }

            dialog.show();

            /*
             * Apply final geometry after show(), since Dialog may
             * recalculate its window dimensions during initialization.
             */
            window = dialog.getWindow();

            if (window != null) {
                window.setBackgroundDrawable(
                        new ColorDrawable(Color.TRANSPARENT));

                window.setGravity(
                        Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);

                window.setLayout(
                        dialogWidth,
                        WindowManager.LayoutParams.WRAP_CONTENT);

                if (Build.VERSION.SDK_INT >=
                        Build.VERSION_CODES.LOLLIPOP) {
                    window.setNavigationBarColor(surfaceColor);
                }
            }

            return true;

        } catch (Throwable t) {
            Log.e(TAG, "Unable to show release notes", t);
            return false;
        }
    }

    // ============================================================
    // Release-note row
    // ============================================================

    private static void addReleaseNote(
            @NonNull Context ctx,
            @NonNull LinearLayout parent,
            @NonNull String message,
            int textColor,
            int bulletColor,
            boolean addDividerBelow) {

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);

        row.setPadding(
                0,
                dp(ctx, 13),
                0,
                dp(ctx, 13));

        TextView bullet = new TextView(ctx);

        bullet.setText("\u2022");

        bullet.setTextSize(
                TypedValue.COMPLEX_UNIT_SP, 20);

        bullet.setTextColor(bulletColor);
        bullet.setGravity(Gravity.TOP);
        bullet.setIncludeFontPadding(false);

        LinearLayout.LayoutParams bulletLp =
                new LinearLayout.LayoutParams(
                        dp(ctx, 20),
                        ViewGroup.LayoutParams.WRAP_CONTENT);

        bulletLp.topMargin = dp(ctx, 1);

        row.addView(bullet, bulletLp);

        TextView description = new TextView(ctx);

        description.setText(message);

        description.setTextSize(
                TypedValue.COMPLEX_UNIT_SP, 16);

        description.setTextColor(textColor);
        description.setGravity(Gravity.TOP);

        description.setLineSpacing(
                dp(ctx, 3),
                1.0f);

        description.setIncludeFontPadding(true);

        LinearLayout.LayoutParams descriptionLp =
                new LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f);

        row.addView(description, descriptionLp);

        parent.addView(
                row,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        if (addDividerBelow) {
            addDivider(ctx, parent);
        }
    }

    // ============================================================
    // Divider
    // ============================================================

    private static void addDivider(
            @NonNull Context ctx,
            @NonNull LinearLayout parent) {

        try {
            MaterialDivider divider = new MaterialDivider(ctx);

            parent.addView(
                    divider,
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));

        } catch (Throwable t) {
            Log.e(TAG, "Divider creation failed", t);
        }
    }

    // ============================================================
    // Stable rounded sheet background
    // ============================================================

    private static MaterialShapeDrawable createSheetBackground(
            int color,
            float cornerRadiusPx) {

        ShapeAppearanceModel shape =
                ShapeAppearanceModel.builder()
                        .setTopLeftCorner(
                                CornerFamily.ROUNDED,
                                cornerRadiusPx)
                        .setTopRightCorner(
                                CornerFamily.ROUNDED,
                                cornerRadiusPx)
                        .setBottomLeftCorner(
                                CornerFamily.ROUNDED,
                                0f)
                        .setBottomRightCorner(
                                CornerFamily.ROUNDED,
                                0f)
                        .build();

        MaterialShapeDrawable drawable =
                new MaterialShapeDrawable(shape);

        drawable.setFillColor(ColorStateList.valueOf(color));

        return drawable;
    }

    // ============================================================
    // Available display dimensions
    // ============================================================

    private static int getAvailableHeightPx(
            @NonNull Activity activity) {

        try {
            if (Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.R) {

                WindowManager wm = (WindowManager)
                        activity.getSystemService(
                                Context.WINDOW_SERVICE);

                if (wm != null) {
                    WindowMetrics metrics =
                            wm.getCurrentWindowMetrics();

                    Rect bounds = metrics.getBounds();

                    WindowInsets windowInsets =
                            metrics.getWindowInsets();

                    int insetTypes =
                            WindowInsets.Type.statusBars()
                                    | WindowInsets.Type.navigationBars()
                                    | WindowInsets.Type.displayCutout();

                    Insets insets =
                            windowInsets.getInsetsIgnoringVisibility(
                                    insetTypes);

                    return Math.max(
                            1,
                            bounds.height()
                                    - insets.top
                                    - insets.bottom);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG,
                    "WindowMetrics height unavailable; using fallback",
                    t);
        }

        try {
            Rect visible = new Rect();

            activity.getWindow()
                    .getDecorView()
                    .getWindowVisibleDisplayFrame(visible);

            if (visible.height() > 0) {
                return visible.height();
            }
        } catch (Throwable t) {
            Log.w(TAG, "Visible-frame height unavailable", t);
        }

        DisplayMetrics metrics =
                activity.getResources().getDisplayMetrics();

        return Math.max(1, metrics.heightPixels);
    }

    private static int getAvailableWidthPx(
            @NonNull Activity activity) {

        try {
            if (Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.R) {

                WindowManager wm = (WindowManager)
                        activity.getSystemService(
                                Context.WINDOW_SERVICE);

                if (wm != null) {
                    WindowMetrics metrics =
                            wm.getCurrentWindowMetrics();

                    Rect bounds = metrics.getBounds();

                    WindowInsets windowInsets =
                            metrics.getWindowInsets();

                    int insetTypes =
                            WindowInsets.Type.statusBars()
                                    | WindowInsets.Type.navigationBars()
                                    | WindowInsets.Type.displayCutout();

                    Insets insets =
                            windowInsets.getInsetsIgnoringVisibility(
                                    insetTypes);

                    return Math.max(
                            1,
                            bounds.width()
                                    - insets.left
                                    - insets.right);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG,
                    "WindowMetrics width unavailable; using fallback",
                    t);
        }

        try {
            Rect visible = new Rect();

            activity.getWindow()
                    .getDecorView()
                    .getWindowVisibleDisplayFrame(visible);

            if (visible.width() > 0) {
                return visible.width();
            }
        } catch (Throwable t) {
            Log.w(TAG, "Visible-frame width unavailable", t);
        }

        DisplayMetrics metrics =
                activity.getResources().getDisplayMetrics();

        return Math.max(1, metrics.widthPixels);
    }

    // ============================================================
    // Drawable helper
    // ============================================================

    @Nullable
    private static Drawable loadDrawable(
            @NonNull Context ctx,
            @NonNull String name) {

        try {
            int id = ctx.getResources().getIdentifier(
                    name,
                    "drawable",
                    ctx.getPackageName());

            if (id == 0) {
                return null;
            }

            return AppCompatResources.getDrawable(ctx, id);

        } catch (Throwable t) {
            Log.e(TAG, "Drawable loading failed: " + name, t);
            return null;
        }
    }

    // ============================================================
    // Theme and dimension helpers
    // ============================================================

    private static int getColor(
            @NonNull Context ctx,
            int attr,
            int fallback) {

        try {
            return MaterialColors.getColor(
                    ctx,
                    attr,
                    fallback);

        } catch (Throwable t) {
            return fallback;
        }
    }

    private static int dp(
            @NonNull Context ctx,
            float value) {

        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                ctx.getResources().getDisplayMetrics());
    }

    private static int withAlpha(
            int color,
            float alpha) {

        int a = Math.max(
                0,
                Math.min(255, Math.round(alpha * 255f)));

        return Color.argb(
                a,
                Color.red(color),
                Color.green(color),
                Color.blue(color));
    }

    // ============================================================
    // Activity lifecycle
    // ============================================================

    private static boolean isActivityUsable(
            @Nullable Activity activity) {

        if (activity == null) {
            return false;
        }

        try {
            if (activity.isFinishing()) {
                return false;
            }

            if (Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.JELLY_BEAN_MR1
                    && activity.isDestroyed()) {
                return false;
            }

            return true;

        } catch (Throwable t) {
            Log.e(TAG, "Activity lifecycle check failed", t);
            return false;
        }
    }

    // ============================================================
    // Height-limited NestedScrollView
    // ============================================================

    private static final class MaxHeightScrollView
            extends NestedScrollView {

        private final int maxHeight;

        MaxHeightScrollView(
                @NonNull Context context,
                int maxHeight) {

            super(context);

            this.maxHeight = Math.max(1, maxHeight);
            setNestedScrollingEnabled(false);
        }

        @Override
        protected void onMeasure(
                int widthMeasureSpec,
                int heightMeasureSpec) {

            int mode = MeasureSpec.getMode(heightMeasureSpec);
            int size = MeasureSpec.getSize(heightMeasureSpec);

            if (mode == MeasureSpec.UNSPECIFIED
                    || size > maxHeight) {

                heightMeasureSpec = MeasureSpec.makeMeasureSpec(
                        maxHeight,
                        MeasureSpec.AT_MOST);
            }

            super.onMeasure(
                    widthMeasureSpec,
                    heightMeasureSpec);
        }
    }
}
