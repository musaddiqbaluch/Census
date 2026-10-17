package com.musaddiqbaluch.census;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.widget.TextViewCompat;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.divider.MaterialDivider;

/**
 * Privacy policy dialog (Material 3), built entirely in Java.
 * Layout: cookie icon, headline, supporting text, then sections
 * separated by MaterialDividers.
 */
public final class PrivacyDialog {

    private static final String TAG = "PrivacyDialog";

    private static final int FALLBACK_CONTAINER_COLOR = 0xFFE8DEF8;
    private static final int FALLBACK_ON_CONTAINER_COLOR = 0xFF21005D;
    private static final int FALLBACK_ON_SURFACE = 0xFF1D1B20;
    private static final int FALLBACK_ON_SURFACE_VARIANT = 0xFF49454F;

    private PrivacyDialog() {
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
            final View content = buildContent(ctx);

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

    private static View buildContent(@NonNull Context ctx) {

        ScrollView scroll = new ScrollView(ctx);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFillViewport(false);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(ctx, 12), dp(ctx, 24), dp(ctx, 12), dp(ctx, 0));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- Cookie icon ----
        LinearLayout cookie = new LinearLayout(ctx);
        cookie.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams cookieLp =
                new LinearLayout.LayoutParams(dp(ctx, 64), dp(ctx, 64));
        cookieLp.gravity = Gravity.CENTER_HORIZONTAL;

        ImageView icon = new ImageView(ctx);
        icon.setContentDescription("Privacy Policy");
        cookie.addView(icon, new LinearLayout.LayoutParams(
                dp(ctx, 32), dp(ctx, 32)));
        root.addView(cookie, cookieLp);

        setupCookieIcon(
                ctx, cookie, icon,
                "icon_privacy_rounded",
                "icon_link_rounded");

        // ---- Headline ----
        TextView title = new TextView(ctx);
        title.setText("Privacy Policy");
        title.setGravity(Gravity.CENTER);
        applyTextAppearance(ctx, title,
                com.google.android.material.R.attr.textAppearanceHeadlineSmall);
        title.setTextColor(color(ctx,
                com.google.android.material.R.attr.colorOnSurface,
                FALLBACK_ON_SURFACE));
        LinearLayout.LayoutParams titleLp = matchWrap();
        titleLp.topMargin = dp(ctx, 16);
        titleLp.leftMargin = dp(ctx, 12);
        titleLp.rightMargin = dp(ctx, 12);
        root.addView(title, titleLp);

        // ---- Supporting text ----
        TextView message = new TextView(ctx);
        message.setText("Your privacy and data security are fundamental defaults.");
        message.setGravity(Gravity.CENTER);
        applyTextAppearance(ctx, message,
                com.google.android.material.R.attr.textAppearanceBodyMedium);
        message.setTextColor(color(ctx,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
                FALLBACK_ON_SURFACE_VARIANT));
        LinearLayout.LayoutParams msgLp = matchWrap();
        msgLp.topMargin = dp(ctx, 12);
        msgLp.leftMargin = dp(ctx, 12);
        msgLp.rightMargin = dp(ctx, 12);
        root.addView(message, msgLp);

        // ---- Section: Data collection ----
        addSection(ctx, root, "DATA COLLECTION");

        addInfoRow(ctx, root, null,
                "Zero Data Collection",
                "This application is designed privacy-first. We do not collect, store, track, or transmit any personal data, usage analytics, or device identifiers to external servers.",
                false);

        // ---- Section: Permissions & usage ----
        addSection(ctx, root, "PERMISSIONS & USAGE");

        addInfoRow(ctx, root, "icon_wifi_round",
                "Internet",
                "Network access is used strictly for checking voluntary app updates via public GitHub releases and launching external web links. No private data is ever uploaded.",
                true);

        addInfoRow(ctx, root, "icon_unarchive_outline",
                "Query All Packages",
                "Package queries and system information are processed locally on your hardware solely for rendering dynamic app parameters and user interfaces.",
                true);

        addInfoRow(ctx, root, "icon_vibration_round",
                "Vibrate",
                "Vibration permission is utilized strictly to provide tactile haptic responses during user interactions. No physical or motion data is stored.",
                false);

        // ---- Section: External services ----
        addSection(ctx, root, "EXTERNAL SERVICES");

        addInfoRow(ctx, root, null,
                "Third-Party Links",
                "Clicking external developer or asset links will redirect you to third-party web platforms governed by their own individual privacy policies.",
                false);

        return scroll;
    }

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
                    header, R.style.TextAppearance_App_Settings_SectionHeader);
        } catch (Throwable t) {
            Log.e(TAG, "section header style failed", t);
        }
        LinearLayout.LayoutParams lp = matchWrap();
        lp.leftMargin = dp(ctx, 12);
        lp.topMargin = dp(ctx, 16);
        lp.bottomMargin = dp(ctx, 4);
        parent.addView(header, lp);
    }

    /**
     * Adds a static (non-clickable) info row. If iconName is non-null,
     * a 24dp tinted leading icon is shown.
     */
    private static void addInfoRow(
            @NonNull Context ctx,
            @NonNull LinearLayout parent,
            @Nullable String iconName,
            @NonNull String titleText,
            @NonNull String secondaryText,
            boolean addDividerBelow) {

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP | Gravity.START);
        row.setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 12), dp(ctx, 12));

        if (iconName != null) {
            ImageView leading = new ImageView(ctx);
            leading.setContentDescription(titleText);
            Drawable d = loadDrawable(ctx, iconName);
            if (d != null) {
                try {
                    leading.setImageDrawable(d.mutate());
                    leading.setImageTintList(ColorStateList.valueOf(color(ctx,
                            com.google.android.material.R.attr.colorOnSurfaceVariant,
                            FALLBACK_ON_SURFACE_VARIANT)));
                } catch (Throwable t) {
                    Log.e(TAG, "leading icon failed", t);
                }
            }
            LinearLayout.LayoutParams iconLp =
                    new LinearLayout.LayoutParams(dp(ctx, 24), dp(ctx, 24));
            iconLp.topMargin = dp(ctx, 2);
            iconLp.rightMargin = dp(ctx, 16);
            row.addView(leading, iconLp);
        }

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(ctx);
        title.setText(titleText);
        try {
            TextViewCompat.setTextAppearance(
                    title, R.style.TextAppearance_App_Settings_RowTitle);
        } catch (Throwable t) {
            Log.e(TAG, "row title style failed", t);
        }
        textCol.addView(title, matchWrap());

        TextView secondary = new TextView(ctx);
        secondary.setText(secondaryText);
        try {
            TextViewCompat.setTextAppearance(
                    secondary, R.style.TextAppearance_App_Settings_RowSecondary);
        } catch (Throwable t) {
            Log.e(TAG, "row secondary style failed", t);
        }
        LinearLayout.LayoutParams secLp = matchWrap();
        secLp.topMargin = dp(ctx, 4);
        textCol.addView(secondary, secLp);

        row.addView(textCol, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        parent.addView(row, matchWrap());

        if (addDividerBelow) {
            // 52 = 12 (row padding) + 24 (icon) + 16 (gap): the line
            // starts under the text when a leading icon is present.
            addDivider(ctx, parent, iconName != null ? 52 : 12, 12, 0, 0);
        }
    }

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

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
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
    // Icon setup
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

        int backgroundColor = color(context,
                com.google.android.material.R.attr.colorPrimaryContainer,
                FALLBACK_CONTAINER_COLOR);
        int iconColor = color(context,
                com.google.android.material.R.attr.colorOnPrimaryContainer,
                FALLBACK_ON_CONTAINER_COLOR);

        try {
            container.setBackground(new Cookie4SidedDrawable(backgroundColor));
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
            imageView.setImageTintList(ColorStateList.valueOf(iconColor));
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
                    name, "drawable", ctx.getPackageName());
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

    private static int dp(@NonNull Context ctx, int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                ctx.getResources().getDisplayMetrics());
    }

    private static int color(@NonNull Context ctx, int attr, int fallback) {
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
}