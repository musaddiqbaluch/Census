
package com.musaddiqbaluch.census;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.widget.TextViewCompat;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.divider.MaterialDivider;

/**
 * Third-party resources dialog for Census.
 *
 * Includes:
 * - Open-source libraries and frameworks.
 * - Vector animation assets.
 * - Native Android graphics APIs used by the glass backdrop.
 *
 * Built entirely in Java with a Material 3 dialog layout.
 */
public final class ResourcesDialog {

    private static final String TAG = "ResourcesDialog";

    // ============================================================
    // Open-source libraries and assets
    // ============================================================

    private static final String URL_LOTTIE =
            "https://github.com/airbnb/lottie-android";

    private static final String URL_MATERIAL_COMPONENTS =
            "https://github.com/material-components/material-components-android";

    private static final String URL_MATERIAL_COLOR_UTILS =
            "https://github.com/material-foundation/material-color-utilities";

    private static final String URL_PALETTE =
            "https://developer.android.com/jetpack/androidx/releases/palette";

    private static final String URL_GRAPHICS_SHAPES =
            "https://developer.android.com/jetpack/androidx/releases/graphics";

    private static final String URL_AI_ANIMATION =
            "https://iconscout.com/free-lottie-animation/"
                    + "free-ai-service-animation_11127035";

    // ============================================================
    // Native Android graphics APIs
    // ============================================================

    private static final String URL_RENDER_NODE =
            "https://developer.android.com/reference/android/graphics/RenderNode";

    private static final String URL_RENDER_EFFECT =
            "https://developer.android.com/reference/android/graphics/RenderEffect";

    private static final int FALLBACK_CONTAINER_COLOR = 0xFFE8DEF8;
    private static final int FALLBACK_ON_CONTAINER_COLOR = 0xFF21005D;
    private static final int FALLBACK_ON_SURFACE = 0xFF1D1B20;
    private static final int FALLBACK_ON_SURFACE_VARIANT = 0xFF49454F;

    private ResourcesDialog() {
        // Utility class.
    }

    // ============================================================
    // Public API
    // ============================================================

    public static void show(
            @Nullable final Activity activity,
            @Nullable final AlertDialog parentDialogToClose) {

        if (activity == null) return;

        try {
            if (activity.isFinishing()) return;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1
                    && activity.isDestroyed()) {
                return;
            }

        } catch (Throwable t) {
            Log.e(TAG, "show: lifecycle check failed", t);
            return;
        }

        if (parentDialogToClose != null) {
            try {
                if (parentDialogToClose.isShowing()) {
                    parentDialogToClose.dismiss();
                }
            } catch (Throwable t) {
                Log.e(TAG, "parent dialog dismiss failed", t);
            }
        }

        try {
            final MaterialAlertDialogBuilder builder =
                    new MaterialAlertDialogBuilder(
                            activity,
                            R.style.ThemeOverlay_App_SettingsDialog);

            final Context ctx = builder.getContext();
            final View content = buildContent(ctx, activity);

            final AlertDialog dialog = builder
                    .setView(content)
                    .setPositiveButton("Close", null)
                    .create();

            try {
                dialog.show();
            } catch (Throwable t) {
                Log.e(TAG, "dialog.show failed", t);
            }

        } catch (Throwable t) {
            Log.e(TAG, "show failed", t);
        }
    }

    // ============================================================
    // Content
    // ============================================================

    private static View buildContent(
            @NonNull Context ctx,
            @NonNull Activity activity) {

        ScrollView scroll = new ScrollView(ctx);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFillViewport(false);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(
                dp(ctx, 12),
                dp(ctx, 24),
                dp(ctx, 12),
                dp(ctx, 0));

        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // --------------------------------------------------------
        // Cookie icon
        // --------------------------------------------------------

        LinearLayout cookie = new LinearLayout(ctx);
        cookie.setGravity(Gravity.CENTER);

        LinearLayout.LayoutParams cookieLp =
                new LinearLayout.LayoutParams(
                        dp(ctx, 64),
                        dp(ctx, 64));

        cookieLp.gravity = Gravity.CENTER_HORIZONTAL;

        ImageView icon = new ImageView(ctx);
        icon.setContentDescription("Third-party resources");

        cookie.addView(icon, new LinearLayout.LayoutParams(
                dp(ctx, 32),
                dp(ctx, 32)));

        root.addView(cookie, cookieLp);

        setupCookieIcon(
                ctx,
                cookie,
                icon,
                "icon_resources_rounded",
                "icon_link_rounded");

        // --------------------------------------------------------
        // Headline
        // --------------------------------------------------------

        TextView title = new TextView(ctx);
        title.setText("Third-party resources");
        title.setGravity(Gravity.CENTER);

        applyTextAppearance(
                ctx,
                title,
                com.google.android.material.R.attr.textAppearanceHeadlineSmall);

        title.setTextColor(color(
                ctx,
                com.google.android.material.R.attr.colorOnSurface,
                FALLBACK_ON_SURFACE));

        LinearLayout.LayoutParams titleLp = matchWrap();
        titleLp.topMargin = dp(ctx, 16);
        titleLp.leftMargin = dp(ctx, 12);
        titleLp.rightMargin = dp(ctx, 12);

        root.addView(title, titleLp);

        // --------------------------------------------------------
        // Supporting text
        // --------------------------------------------------------

        TextView message = new TextView(ctx);
        message.setText(
                "Open-source libraries, visual assets, and native "
                        + "Android APIs used in Census. Tap an item to "
                        + "view its source, documentation, or license.");

        message.setGravity(Gravity.CENTER);

        applyTextAppearance(
                ctx,
                message,
                com.google.android.material.R.attr.textAppearanceBodyMedium);

        message.setTextColor(color(
                ctx,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
                FALLBACK_ON_SURFACE_VARIANT));

        LinearLayout.LayoutParams msgLp = matchWrap();
        msgLp.topMargin = dp(ctx, 12);
        msgLp.leftMargin = dp(ctx, 12);
        msgLp.rightMargin = dp(ctx, 12);

        root.addView(message, msgLp);

        // ========================================================
        // Section: Libraries and frameworks
        // ========================================================

        addSection(ctx, root, "LIBRARIES & FRAMEWORKS");

        addRow(
                ctx,
                activity,
                root,
                "Lottie Animation Framework",
                "Vector animation rendering engine by Airbnb "
                        + "(Apache License 2.0).",
                URL_LOTTIE,
                true);

        addRow(
                ctx,
                activity,
                root,
                "Material Components v1.14.0",
                "Official Material Design components and "
                        + "Expressive layout system by Google.",
                URL_MATERIAL_COMPONENTS,
                true);

        addRow(
                ctx,
                activity,
                root,
                "Material Color Utilities v3.0.1",
                "Dynamic color seed algorithms and palette matching "
                        + "tools used for calculating theme colors from icons.",
                URL_MATERIAL_COLOR_UTILS,
                true);

        addRow(
                ctx,
                activity,
                root,
                "AndroidX Palette v1.0.0",
                "Extracts dominant vibrant and muted color swatches "
                        + "directly from app and resource icons.",
                URL_PALETTE,
                true);

        addRow(
                ctx,
                activity,
                root,
                "AndroidX Graphics Shapes v1.1.0",
                "Renders custom rounded shapes, multi-sided polygon "
                        + "contours, and smooth geometric paths.",
                URL_GRAPHICS_SHAPES,
                false);

        // ========================================================
        // Section: Vector animations
        // ========================================================

        addSection(ctx, root, "VECTOR ANIMATIONS");

        addRow(
                ctx,
                activity,
                root,
                "Free AI Service Animation",
                "Lottie JSON vector animation by IconScout Design "
                        + "Ecosystem under IconScout License terms.",
                URL_AI_ANIMATION,
                false);

        // ========================================================
        // Section: Native Android graphics
        // ========================================================

        addSection(ctx, root, "NATIVE ANDROID GRAPHICS");

        addRow(
                ctx,
                activity,
                root,
                "Android RenderNode (API 29+)",
                "Native Android rendering API used by Census to "
                        + "record the backdrop content for its custom "
                        + "glass effect. Part of the Android platform; "
                        + "no additional library required.",
                URL_RENDER_NODE,
                true);

        addRow(
                ctx,
                activity,
                root,
                "Android RenderEffect (API 31+)",
                "Native graphics effect used with RenderNode to apply "
                        + "the hardware-accelerated background blur "
                        + "behind Census cards on Android 12 and newer. "
                        + "Older Android versions fall back to a "
                        + "translucent card appearance.",
                URL_RENDER_EFFECT,
                false);

        return scroll;
    }

    // ============================================================
    // Section header
    // ============================================================

    /** Full-width divider followed by a section header. */
    private static void addSection(
            @NonNull Context ctx,
            @NonNull LinearLayout parent,
            @NonNull String text) {

        addDivider(ctx, parent, 12, 12, 16, 0);

        TextView header = new TextView(ctx);
        header.setText(text);

        try {
            TextViewCompat.setTextAppearance(
                    header,
                    R.style.TextAppearance_App_Settings_SectionHeader);
        } catch (Throwable t) {
            Log.e(TAG, "section header style failed", t);
        }

        LinearLayout.LayoutParams lp = matchWrap();
        lp.leftMargin = dp(ctx, 12);
        lp.topMargin = dp(ctx, 16);
        lp.bottomMargin = dp(ctx, 4);

        parent.addView(header, lp);
    }

    // ============================================================
    // Resource row
    // ============================================================

    private static void addRow(
            @NonNull Context ctx,
            @NonNull final Activity activity,
            @NonNull LinearLayout parent,
            @NonNull String titleText,
            @NonNull String secondaryText,
            @NonNull final String url,
            boolean addDividerBelow) {

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(
                dp(ctx, 12),
                dp(ctx, 12),
                dp(ctx, 12),
                dp(ctx, 12));

        row.setClickable(true);
        row.setFocusable(true);

        try {
            row.setBackgroundResource(R.drawable.ripple_primary_rounded);
        } catch (Throwable t) {
            Log.e(TAG, "row background failed", t);
        }

        TextView title = new TextView(ctx);
        title.setText(titleText);

        try {
            TextViewCompat.setTextAppearance(
                    title,
                    R.style.TextAppearance_App_Settings_RowTitle);
        } catch (Throwable t) {
            Log.e(TAG, "row title style failed", t);
        }

        row.addView(title, matchWrap());

        TextView secondary = new TextView(ctx);
        secondary.setText(secondaryText);

        try {
            TextViewCompat.setTextAppearance(
                    secondary,
                    R.style.TextAppearance_App_Settings_RowSecondary);
        } catch (Throwable t) {
            Log.e(TAG, "row secondary style failed", t);
        }

        LinearLayout.LayoutParams secLp = matchWrap();
        secLp.topMargin = dp(ctx, 4);

        row.addView(secondary, secLp);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    openUrl(activity, url);
                } catch (Throwable t) {
                    Log.e(TAG, "row click failed", t);
                }
            }
        });

        parent.addView(row, matchWrap());

        if (addDividerBelow) {
            addDivider(ctx, parent, 12, 12, 0, 0);
        }
    }

    // ============================================================
    // Material divider
    // ============================================================

    private static void addDivider(
            @NonNull Context ctx,
            @NonNull LinearLayout parent,
            int insetStartDp,
            int insetEndDp,
            int topMarginDp,
            int bottomMarginDp) {

        try {
            MaterialDivider divider = new MaterialDivider(ctx);

            divider.setDividerInsetStart(dp(ctx, insetStartDp));
            divider.setDividerInsetEnd(dp(ctx, insetEndDp));

            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);

            lp.topMargin = dp(ctx, topMarginDp);
            lp.bottomMargin = dp(ctx, bottomMarginDp);

            parent.addView(divider, lp);

        } catch (Throwable t) {
            Log.e(TAG, "addDivider failed", t);
        }
    }

    // ============================================================
    // Cookie icon setup
    // ============================================================

    private static void setupCookieIcon(
            @Nullable Context context,
            @Nullable LinearLayout container,
            @Nullable ImageView imageView,
            @Nullable String primaryDrawable,
            @Nullable String fallbackDrawable) {

        if (context == null || container == null || imageView == null) {
            return;
        }

        int backgroundColor = color(
                context,
                com.google.android.material.R.attr.colorPrimaryContainer,
                FALLBACK_CONTAINER_COLOR);

        int iconColor = color(
                context,
                com.google.android.material.R.attr.colorOnPrimaryContainer,
                FALLBACK_ON_CONTAINER_COLOR);

        try {
            container.setBackground(
                    new Cookie4SidedDrawable(backgroundColor));
        } catch (Throwable t) {
            Log.e(TAG, "container background failed", t);
        }

        Drawable drawable = null;

        if (primaryDrawable != null) {
            drawable = loadDrawable(context, primaryDrawable);
        }

        if (drawable == null && fallbackDrawable != null) {
            drawable = loadDrawable(context, fallbackDrawable);
        }

        if (drawable == null) {
            safeSetImage(imageView, null);
            return;
        }

        try {
            safeSetImage(imageView, drawable.mutate());

            imageView.setImageTintList(
                    ColorStateList.valueOf(iconColor));

        } catch (Throwable t) {
            Log.e(TAG, "icon setup failed", t);
            safeSetImage(imageView, null);
        }
    }

    @Nullable
    private static Drawable loadDrawable(
            @NonNull Context ctx,
            @NonNull String name) {

        try {
            if (name.trim().isEmpty()) return null;

            int id = ctx.getResources().getIdentifier(
                    name,
                    "drawable",
                    ctx.getPackageName());

            if (id == 0) return null;

            return AppCompatResources.getDrawable(ctx, id);

        } catch (Throwable t) {
            Log.e(TAG, "loadDrawable failed: " + name, t);
            return null;
        }
    }

    private static void safeSetImage(
            @Nullable ImageView imageView,
            @Nullable Drawable drawable) {

        if (imageView == null) return;

        try {
            imageView.setImageDrawable(drawable);
        } catch (Throwable t) {
            Log.e(TAG, "setImageDrawable failed", t);
        }
    }

    // ============================================================
    // Helpers
    // ============================================================

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static int dp(
            @NonNull Context ctx,
            int value) {

        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                ctx.getResources().getDisplayMetrics());
    }

    private static int color(
            @NonNull Context ctx,
            int attr,
            int fallback) {

        try {
            return MaterialColors.getColor(ctx, attr, fallback);
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static void applyTextAppearance(
            @NonNull Context ctx,
            @NonNull TextView tv,
            int styleAttr) {

        try {
            TypedValue out = new TypedValue();

            if (ctx.getTheme().resolveAttribute(styleAttr, out, true)
                    && out.resourceId != 0) {
                TextViewCompat.setTextAppearance(tv, out.resourceId);
            }

        } catch (Throwable t) {
            Log.e(TAG, "text appearance failed", t);
        }
    }

    // ============================================================
    // External links
    // ============================================================

    private static void openUrl(
            @Nullable Activity activity,
            @Nullable String url) {

        if (activity == null) return;

        if (url == null || url.trim().isEmpty()) {
            safeToast(activity, "Link unavailable.");
            return;
        }

        try {
            activity.startActivity(
                    new Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(url)));

        } catch (Throwable t) {
            Log.e(TAG, "openUrl failed", t);
            safeToast(activity, "Unable to open link.");
        }
    }

    private static void safeToast(
            @Nullable Activity activity,
            @Nullable String message) {

        if (activity == null) return;

        try {
            Toast.makeText(
                    activity,
                    message != null ? message : "",
                    Toast.LENGTH_SHORT).show();

        } catch (Throwable t) {
            Log.e(TAG, "toast failed", t);
        }
    }
}
