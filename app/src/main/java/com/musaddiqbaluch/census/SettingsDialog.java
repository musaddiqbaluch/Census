package com.musaddiqbaluch.census;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.widget.TextViewCompat;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.divider.MaterialDivider;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.radiobutton.MaterialRadioButton;

/**
 * Application settings dialog (Material 3), built entirely in Java.
 * Sections: Appearance, General, About, separated by MaterialDividers.
 */
public final class SettingsDialog {

    private static final String TAG = "SettingsDialog";

    private static final String PREFS_NAME = "settings_preferences";
    private static final String KEY_THEME_MODE = "theme_mode";
    private static final String KEY_AUTO_UPDATES = "auto_updates";

    private static final int THEME_DEFAULT = 0;
    private static final int THEME_LIGHT = 1;
    private static final int THEME_DARK = 2;

    private static final String DEVELOPER_PROFILE_URL =
            "https://github.com/musaddiqbaluch";
    private static final String OPEN_SOURCE_URL =
            "https://github.com/musaddiqbaluch/Census";
    private static final String UPDATE_CHECK_URL = UpdateChecker.REPO_URL;

    private static final int FALLBACK_CONTAINER_COLOR = 0xFFE8DEF8;
    private static final int FALLBACK_ON_CONTAINER_COLOR = 0xFF21005D;

    private SettingsDialog() {
        // Utility class.
    }

    // ============================================================
    // Public API
    // ============================================================

    public static void show(@Nullable final Activity activity) {

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

        try {
            final MaterialAlertDialogBuilder builder =
                    new MaterialAlertDialogBuilder(
                            activity,
                            R.style.ThemeOverlay_App_SettingsDialog);

            final Context ctx = builder.getContext();
            final SharedPreferences preferences = openPrefs(ctx);

            // Listeners need the dialog, which is created after the
            // content, so it is passed through a holder.
            final AlertDialog[] dialogRef = new AlertDialog[1];

            final View content = buildContent(
                    ctx, activity, preferences, dialogRef);

            final AlertDialog dialog = builder
                    .setTitle("Settings")
                    .setView(content)
                    .setPositiveButton("Close", null)
                    .create();

            dialogRef[0] = dialog;

            try {
                dialog.show();
            } catch (Throwable t) {
                Log.e(TAG, "dialog.show failed", t);
            }

        } catch (Throwable t) {
            Log.e(TAG, "show failed", t);
        }
    }

    /**
     * Applies the persisted theme mode via AppCompatDelegate. Safe to
     * call from any lifecycle point; failures are logged and ignored.
     */
    public static void applySavedTheme(@Nullable Context context) {

        if (context == null) return;

        try {
            SharedPreferences prefs = openPrefs(context);
            int savedTheme = safeGetInt(prefs, KEY_THEME_MODE, THEME_DEFAULT);
            int desired = toNightMode(savedTheme);

            // Skip when already in the desired mode so attachBaseContext()
            // stays side-effect-free and never triggers a recreate.
            if (AppCompatDelegate.getDefaultNightMode() == desired) {
                return;
            }

            try {
                AppCompatDelegate.setDefaultNightMode(desired);
            } catch (Throwable t) {
                Log.e(TAG, "applySavedTheme: setDefaultNightMode failed", t);
            }

        } catch (Throwable t) {
            Log.e(TAG, "applySavedTheme failed", t);
        }
    }

    public static boolean isAutoUpdateChecksEnabled(@Nullable Context context) {

        if (context == null) return false;

        try {
            SharedPreferences prefs = openPrefs(context);
            return safeGetBoolean(prefs, KEY_AUTO_UPDATES, false);
        } catch (Throwable t) {
            Log.e(TAG, "isAutoUpdateChecksEnabled failed", t);
            return false;
        }
    }

    @NonNull
    public static String getUpdateCheckUrl() {
        return UPDATE_CHECK_URL;
    }

    // ============================================================
    // Content
    // ============================================================

    private static View buildContent(
            @NonNull final Context ctx,
            @NonNull final Activity activity,
            @Nullable final SharedPreferences preferences,
            @NonNull final AlertDialog[] dialogRef) {

        ScrollView scroll = new ScrollView(ctx);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFillViewport(false);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(ctx, 12), dp(ctx, 0), dp(ctx, 12), dp(ctx, 0));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---------------- APPEARANCE ----------------
        addSectionHeader(ctx, root, "APPEARANCE", 8);

        final int savedTheme = safeGetInt(
                preferences, KEY_THEME_MODE, THEME_DEFAULT);

        // Indexed by theme constant: 0 = default, 1 = light, 2 = dark.
        final MaterialRadioButton[] radios = new MaterialRadioButton[3];

        View.OnClickListener themeClickListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    if (v == null || !(v.getTag() instanceof Integer)) return;

                    int selectedTheme = (Integer) v.getTag();

                    setThemeRadioButtons(radios, selectedTheme);

                    int oldTheme = safeGetInt(
                            preferences, KEY_THEME_MODE, THEME_DEFAULT);

                    if (selectedTheme == oldTheme) return;

                    safePutInt(preferences, KEY_THEME_MODE, selectedTheme);

                    // Dismiss first, then apply. Independent so a failure
                    // in one does not skip the other.
                    safeDismiss(dialogRef[0]);

                    try {
                        AppCompatDelegate.setDefaultNightMode(
                                toNightMode(selectedTheme));
                    } catch (Throwable t) {
                        Log.e(TAG, "setDefaultNightMode failed", t);
                    }

                } catch (Throwable t) {
                    Log.e(TAG, "theme click failed", t);
                }
            }
        };

        radios[THEME_LIGHT] = addRadio(
                ctx, root, "Light theme", THEME_LIGHT, themeClickListener);
        radios[THEME_DARK] = addRadio(
                ctx, root, "Dark theme", THEME_DARK, themeClickListener);
        radios[THEME_DEFAULT] = addRadio(
                ctx, root, "System default", THEME_DEFAULT, themeClickListener);

        setThemeRadioButtons(radios, savedTheme);

        // ---------------- GENERAL ----------------
        addDivider(ctx, root, 12, 12, 8, 0);
        addSectionHeader(ctx, root, "GENERAL", 16);

        final MaterialSwitch updateSwitch = new MaterialSwitch(ctx);
        try {
            updateSwitch.setChecked(safeGetBoolean(
                    preferences, KEY_AUTO_UPDATES, false));
        } catch (Throwable t) {
            Log.e(TAG, "updateSwitch.setChecked failed", t);
        }
        updateSwitch.setOnCheckedChangeListener(
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(
                            CompoundButton buttonView, boolean isChecked) {
                        try {
                            safePutBoolean(
                                    preferences, KEY_AUTO_UPDATES, isChecked);
                        } catch (Throwable t) {
                            Log.e(TAG, "auto-update pref save failed", t);
                        }
                    }
                });

        addRow(ctx, root,
                "icon_update_rounded",
                "Update Check",
                "Look for new updates",
                updateSwitch,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        try {
                            updateSwitch.setChecked(!updateSwitch.isChecked());
                        } catch (Throwable t) {
                            Log.e(TAG, "updateRow click failed", t);
                        }
                    }
                });

        // ---------------- ABOUT ----------------
        addDivider(ctx, root, 12, 12, 8, 0);
        addSectionHeader(ctx, root, "ABOUT", 16);

        addRow(ctx, root,
                "icon_developer_rounded",
                "Musaddiq Baluch",
                "Crafted with care by an indie dev",
                null,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        try {
                            openConfiguredUrl(activity,
                                    DEVELOPER_PROFILE_URL,
                                    "Developer profile link is not available.");
                        } catch (Throwable t) {
                            Log.e(TAG, "developerRow click failed", t);
                        }
                    }
                });

        // 68 = 12 (row padding) + 40 (cookie) + 16 (gap): line starts
        // exactly under the text.
        addDivider(ctx, root, 68, 12, 0, 0);

        addRow(ctx, root,
                "icon_code_round",
                "Open Source",
                "Census is proudly open source",
                null,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        try {
                            openConfiguredUrl(activity,
                                    OPEN_SOURCE_URL,
                                    "Repository link is not available.");
                        } catch (Throwable t) {
                            Log.e(TAG, "openSourceRow click failed", t);
                        }
                    }
                });

        addDivider(ctx, root, 68, 12, 0, 0);

        addRow(ctx, root,
                "icon_privacy_rounded",
                "Privacy policy",
                "Read our privacy policy",
                null,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        try {
                            PrivacyDialog.show(activity, dialogRef[0]);
                        } catch (Throwable t) {
                            Log.e(TAG, "privacyRow click failed", t);
                        }
                    }
                });

        addDivider(ctx, root, 68, 12, 0, 0);

        addRow(ctx, root,
                "icon_resources_rounded",
                "Third-party resources",
                "External assets & attributions",
                null,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        try {
                            ResourcesDialog.show(activity, dialogRef[0]);
                        } catch (Throwable t) {
                            Log.e(TAG, "creditsRow click failed", t);
                        }
                    }
                });

        addDivider(ctx, root, 68, 12, 0, 0);

        // Version row is informational, so it has no click listener.
        addRow(ctx, root,
                "icon_version_rounded",
                "Version",
                getAppVersionFormatted(ctx),
                null,
                null);

        // Small gap above the dialog's action area.
        View spacer = new View(ctx);
        root.addView(spacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 8)));

        return scroll;
    }

    private static void addSectionHeader(
            @NonNull Context ctx,
            @NonNull LinearLayout parent,
            @NonNull String text,
            int topMarginDp) {

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
        lp.topMargin = dp(ctx, topMarginDp);
        lp.bottomMargin = dp(ctx, 4);
        parent.addView(header, lp);
    }

    private static MaterialRadioButton addRadio(
            @NonNull Context ctx,
            @NonNull LinearLayout parent,
            @NonNull String text,
            int themeValue,
            @NonNull View.OnClickListener listener) {

        MaterialRadioButton radio = new MaterialRadioButton(ctx);
        radio.setText(text);
        radio.setTag(Integer.valueOf(themeValue));
        try {
            TextViewCompat.setTextAppearance(
                    radio, R.style.TextAppearance_App_Settings_Radio);
        } catch (Throwable t) {
            Log.e(TAG, "radio style failed", t);
        }
        radio.setPadding(dp(ctx, 12), 0, dp(ctx, 12), 0);
        radio.setOnClickListener(listener);

        parent.addView(radio, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 48)));
        return radio;
    }

    /**
     * Adds a row: cookie icon, title + secondary text, optional trailing
     * view (e.g. a switch). A null click listener makes the row static.
     */
    private static void addRow(
            @NonNull Context ctx,
            @NonNull LinearLayout parent,
            @NonNull String iconName,
            @NonNull String titleText,
            @NonNull String secondaryText,
            @Nullable View trailing,
            @Nullable View.OnClickListener onClick) {

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8));

        if (onClick != null) {
            row.setClickable(true);
            row.setFocusable(true);
            try {
                row.setBackgroundResource(R.drawable.ripple_primary_rounded);
            } catch (Throwable t) {
                Log.e(TAG, "row background failed", t);
            }
            row.setOnClickListener(onClick);
        }

        // Cookie icon (40dp container, 24dp glyph).
        LinearLayout cookie = new LinearLayout(ctx);
        cookie.setGravity(Gravity.CENTER);
        ImageView icon = new ImageView(ctx);
        icon.setContentDescription(titleText);
        cookie.addView(icon, new LinearLayout.LayoutParams(
                dp(ctx, 24), dp(ctx, 24)));
        row.addView(cookie, new LinearLayout.LayoutParams(
                dp(ctx, 40), dp(ctx, 40)));

        setupCookieIcon(ctx, cookie, icon, iconName, "icon_link_rounded");

        // Text column.
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
        textCol.addView(secondary, matchWrap());

        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textLp.leftMargin = dp(ctx, 16);
        row.addView(textCol, textLp);

        if (trailing != null) {
            LinearLayout.LayoutParams trLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            trLp.leftMargin = dp(ctx, 16);
            row.addView(trailing, trLp);
        }

        parent.addView(row, matchWrap());
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
    // Preferences helpers
    // ============================================================

    @Nullable
    private static SharedPreferences openPrefs(@Nullable Context context) {
        if (context == null) return null;
        try {
            return context.getSharedPreferences(
                    PREFS_NAME, Context.MODE_PRIVATE);
        } catch (Throwable t) {
            Log.e(TAG, "openPrefs failed", t);
            return null;
        }
    }

    private static int safeGetInt(
            @Nullable SharedPreferences prefs,
            @NonNull String key,
            int fallback) {

        if (prefs == null) return fallback;

        try {
            return prefs.getInt(key, fallback);
        } catch (ClassCastException e) {
            Log.e(TAG, "pref " + key + " has wrong type, removing", e);
            try { prefs.edit().remove(key).apply(); } catch (Throwable ignored) {}
            return fallback;
        } catch (Throwable t) {
            Log.e(TAG, "pref read failed for " + key, t);
            return fallback;
        }
    }

    private static boolean safeGetBoolean(
            @Nullable SharedPreferences prefs,
            @NonNull String key,
            boolean fallback) {

        if (prefs == null) return fallback;

        try {
            return prefs.getBoolean(key, fallback);
        } catch (ClassCastException e) {
            Log.e(TAG, "pref " + key + " has wrong type, removing", e);
            try { prefs.edit().remove(key).apply(); } catch (Throwable ignored) {}
            return fallback;
        } catch (Throwable t) {
            Log.e(TAG, "pref read failed for " + key, t);
            return fallback;
        }
    }

    private static void safePutInt(
            @Nullable SharedPreferences prefs,
            @NonNull String key,
            int value) {

        if (prefs == null) return;
        try {
            prefs.edit().putInt(key, value).apply();
        } catch (Throwable t) {
            Log.e(TAG, "pref write failed for " + key, t);
        }
    }

    private static void safePutBoolean(
            @Nullable SharedPreferences prefs,
            @NonNull String key,
            boolean value) {

        if (prefs == null) return;
        try {
            prefs.edit().putBoolean(key, value).apply();
        } catch (Throwable t) {
            Log.e(TAG, "pref write failed for " + key, t);
        }
    }

    // ============================================================
    // Theme
    // ============================================================

    private static void setThemeRadioButtons(
            @NonNull MaterialRadioButton[] radios,
            int selectedTheme) {

        for (int i = 0; i < radios.length; i++) {
            try {
                if (radios[i] != null) {
                    radios[i].setChecked(i == selectedTheme);
                }
            } catch (Throwable t) {
                Log.e(TAG, "radio setChecked failed", t);
            }
        }
    }

    private static int toNightMode(int themeMode) {
        if (themeMode == THEME_LIGHT) return AppCompatDelegate.MODE_NIGHT_NO;
        if (themeMode == THEME_DARK) return AppCompatDelegate.MODE_NIGHT_YES;
        return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
    }

    // ============================================================
    // Version label
    // ============================================================

    @NonNull
    private static String getAppVersionFormatted(@Nullable Context context) {

        String versionName = "1.0";
        String versionCode = "1";

        if (context == null) {
            return versionName + " (" + versionCode + ")";
        }

        try {
            PackageManager pm = context.getPackageManager();
            if (pm == null) {
                return versionName + " (" + versionCode + ")";
            }

            PackageInfo info = pm.getPackageInfo(context.getPackageName(), 0);
            if (info == null) {
                return versionName + " (" + versionCode + ")";
            }

            if (info.versionName != null && !info.versionName.isEmpty()) {
                versionName = info.versionName;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                versionCode = String.valueOf(info.getLongVersionCode());
            } else {
                versionCode = String.valueOf(info.versionCode);
            }

        } catch (Throwable t) {
            Log.e(TAG, "getAppVersionFormatted failed", t);
        }

        return versionName + " (" + versionCode + ")";
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

        if (context == null || container == null || imageView == null) return;

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

    private static void safeDismiss(@Nullable AlertDialog dialog) {
        if (dialog == null) return;
        try {
            dialog.dismiss();
        } catch (Throwable t) {
            Log.e(TAG, "dialog.dismiss failed", t);
        }
    }

    // ============================================================
    // External link
    // ============================================================

    private static void openConfiguredUrl(
            @Nullable Activity activity,
            @Nullable String url,
            @Nullable String emptyMessage) {

        if (activity == null) return;

        if (url == null || url.trim().isEmpty()) {
            safeToast(activity, emptyMessage != null
                    ? emptyMessage
                    : "Link unavailable.");
            return;
        }

        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable t) {
            Log.e(TAG, "openConfiguredUrl failed", t);
            safeToast(activity, "Unable to open link.");
        }
    }

    private static void safeToast(
            @Nullable Activity activity,
            @Nullable String message) {

        if (activity == null) return;
        try {
            Toast.makeText(activity,
                    message != null ? message : "",
                    Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Log.e(TAG, "toast failed", t);
        }
    }
}
