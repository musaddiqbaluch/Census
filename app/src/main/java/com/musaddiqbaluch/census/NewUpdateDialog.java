package com.musaddiqbaluch.census;

import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.widget.TextViewCompat;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public final class NewUpdateDialog {

    private static final String TAG = "NewUpdateDialog";

    private static final int FALLBACK_CONTAINER_COLOR = 0xFFE8DEF8;
    private static final int FALLBACK_ON_CONTAINER_COLOR = 0xFF21005D;
    private static final int FALLBACK_ON_SURFACE = 0xFF1D1B20;
    private static final int FALLBACK_ON_SURFACE_VARIANT = 0xFF49454F;

    public interface OnUpdateListener {
        void onUpdateClicked();
    }

    public interface OnLaterListener {
        void onLater();
    }

    private final Context context;
    private String latestVersion;
    private OnUpdateListener updateListener;
    private OnLaterListener laterListener;
    private boolean cancelable = true;

    public NewUpdateDialog(@NonNull Context context) {
        this.context = context;
    }

    public NewUpdateDialog setLatestVersion(@Nullable String latestVersion) {
        this.latestVersion = latestVersion;
        return this;
    }

    public NewUpdateDialog setOnUpdateListener(OnUpdateListener listener) {
        this.updateListener = listener;
        return this;
    }

    /** Called for the "Later" button, back press and tap outside. */
    public NewUpdateDialog setOnLaterListener(OnLaterListener listener) {
        this.laterListener = listener;
        return this;
    }

    /** false = forced update (no "Later" button, can't dismiss) */
    public NewUpdateDialog setCancelable(boolean cancelable) {
        this.cancelable = cancelable;
        return this;
    }

    public AlertDialog show() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(
                context, R.style.ThemeOverlay_App_SettingsDialog);

        builder.setView(buildContent());
        builder.setCancelable(cancelable);

        builder.setPositiveButton("Update",
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (updateListener != null) {
                            updateListener.onUpdateClicked();
                        }
                    }
                });

        if (cancelable) {
            builder.setNegativeButton("Later",
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            notifyLater();
                        }
                    });

            // Back press / tap outside. (The button above dismisses
            // without cancelling, so this never fires twice.)
            builder.setOnCancelListener(
                    new DialogInterface.OnCancelListener() {
                        @Override
                        public void onCancel(DialogInterface dialog) {
                            notifyLater();
                        }
                    });
        }

        AlertDialog dialog = builder.create();
        dialog.show();
        return dialog;
    }

    private void notifyLater() {
        try {
            if (laterListener != null) laterListener.onLater();
        } catch (Throwable t) {
            Log.e(TAG, "later listener failed", t);
        }
    }

    // ============================================================
    // Content
    // ============================================================

    private View buildContent() {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(24), dp(24), dp(0));

        // Cookie + icon
        LinearLayout cookie = new LinearLayout(context);
        cookie.setGravity(Gravity.CENTER);
        ImageView icon = new ImageView(context);
        icon.setContentDescription("Update available");
        cookie.addView(icon, new LinearLayout.LayoutParams(dp(32), dp(32)));
        root.addView(cookie, new LinearLayout.LayoutParams(dp(64), dp(64)));

        int containerColor = color(
                com.google.android.material.R.attr.colorPrimaryContainer,
                FALLBACK_CONTAINER_COLOR);
        int onContainerColor = color(
                com.google.android.material.R.attr.colorOnPrimaryContainer,
                FALLBACK_ON_CONTAINER_COLOR);

        try {
            cookie.setBackground(new Cookie4SidedDrawable(containerColor));
        } catch (Throwable t) {
            Log.e(TAG, "cookie background failed", t);
        }

        try {
            Drawable d = AppCompatResources.getDrawable(
                    context, R.drawable.icon_update_round);
            if (d != null) {
                icon.setImageDrawable(d.mutate());
                icon.setImageTintList(ColorStateList.valueOf(onContainerColor));
            }
        } catch (Throwable t) {
            Log.e(TAG, "icon failed", t);
        }

        // Headline
        TextView title = new TextView(context);
        title.setText("Update available");
        title.setGravity(Gravity.CENTER);
        applyTextAppearance(title,
                com.google.android.material.R.attr.textAppearanceHeadlineSmall);
        title.setTextColor(color(
                com.google.android.material.R.attr.colorOnSurface,
                FALLBACK_ON_SURFACE));
        LinearLayout.LayoutParams titleLp = matchWrap();
        titleLp.topMargin = dp(16);
        root.addView(title, titleLp);

        // Supporting text
        TextView message = new TextView(context);
        message.setText(buildMessage());
        message.setGravity(Gravity.CENTER);
        applyTextAppearance(message,
                com.google.android.material.R.attr.textAppearanceBodyMedium);
        message.setTextColor(color(
                com.google.android.material.R.attr.colorOnSurfaceVariant,
                FALLBACK_ON_SURFACE_VARIANT));
        LinearLayout.LayoutParams msgLp = matchWrap();
        msgLp.topMargin = dp(16);
        root.addView(message, msgLp);

        return root;
    }

    @NonNull
    private String buildMessage() {
        String name = getAppLabel();
        String subject = (latestVersion == null || latestVersion.isEmpty())
                ? "A new version of " + name + " is"
                : name + " " + latestVersion + " is now";

        return subject + " available. Update now to get the latest "
                + "improvements, bug fixes, and security enhancements.";
    }

    // ============================================================
    // Helpers
    // ============================================================

    @NonNull
    private String getAppLabel() {
        try {
            return context.getApplicationInfo()
                    .loadLabel(context.getPackageManager()).toString();
        } catch (Throwable t) {
            return "this app";
        }
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }

    private int color(int attr, int fallback) {
        try {
            return MaterialColors.getColor(context, attr, fallback);
        } catch (Throwable t) {
            return fallback;
        }
    }

    private void applyTextAppearance(@NonNull TextView tv, int styleAttr) {
        try {
            TypedValue out = new TypedValue();
            if (context.getTheme().resolveAttribute(styleAttr, out, true)
                    && out.resourceId != 0) {
                TextViewCompat.setTextAppearance(tv, out.resourceId);
            }
        } catch (Throwable t) {
            Log.e(TAG, "text appearance failed", t);
        }
    }
}