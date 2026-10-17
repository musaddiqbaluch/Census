package com.musaddiqbaluch.census;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Glue between UpdateChecker and NewUpdateDialog.
 *
 *  - Waits STARTUP_DELAY_MS after launch before doing anything.
 *  - Runs at most once per app launch.
 *  - "Later" (button, back, or tap outside) snoozes for SNOOZE_MS and
 *    no network request is made while snoozed.
 *  - Only runs if the "Update Check" switch in Settings is on.
 *  - Never downloads anything: it only opens a link.
 *
 * Usage (Activity.onCreate):  handle = UpdateManager.scheduleAutoCheck(this);
 * Usage (Activity.onDestroy): if (handle != null) handle.cancel();
 */
public final class UpdateManager {

    private static final String TAG = "UpdateManager";

    private static final String PREFS_NAME = "update_prefs";
    private static final String KEY_SNOOZE_UNTIL = "snooze_until";

    /** Wait before checking, so nothing pops up as the app opens. */
    private static final long STARTUP_DELAY_MS = 5_000L;

    /** How long "Later" silences the dialog (6 hours). */
    private static final long SNOOZE_MS = 6L * 60L * 60L * 1000L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // Main thread only. Guards against re-checking on every recreate.
    private static boolean checkedThisLaunch = false;

    private UpdateManager() {
        // Utility class.
    }

    /** Cancels the pending / running check. Safe to call repeatedly. */
    public static final class Handle {
        private Runnable pending;
        private UpdateChecker.Request request;
        private boolean cancelled;

        public void cancel() {
            cancelled = true;
            if (pending != null) MAIN.removeCallbacks(pending);
            if (request != null) request.cancel();
        }
    }

    // ============================================================
    // Public API
    // ============================================================

    @NonNull
    public static Handle scheduleAutoCheck(@Nullable final Activity activity) {

        final Handle handle = new Handle();

        if (activity == null) return handle;
        if (checkedThisLaunch) return handle;
        if (!SettingsDialog.isAutoUpdateChecksEnabled(activity)) return handle;
        if (isSnoozed(activity)) return handle;

        handle.pending = new Runnable() {
            @Override
            public void run() {
                try {
                    if (handle.cancelled || !isAlive(activity)) return;
                    if (checkedThisLaunch) return;
                    if (isSnoozed(activity)) return;

                    checkedThisLaunch = true;

                    handle.request = UpdateChecker.checkForUpdates(activity,
                            new UpdateChecker.OnCheckCompleteListener() {
                                @Override
                                public void onUpdateFound(
                                        UpdateChecker.UpdateInfo info) {
                                    showDialog(activity, info);
                                }

                                @Override
                                public void onNoUpdateFound() {
                                    // Silent.
                                }

                                @Override
                                public void onError(Exception e) {
                                    // Silent: an automatic check must never
                                    // bother the user.
                                    Log.w(TAG, "auto check failed: "
                                            + e.getMessage());
                                }
                            });
                } catch (Throwable t) {
                    Log.e(TAG, "auto check failed", t);
                }
            }
        };

        MAIN.postDelayed(handle.pending, STARTUP_DELAY_MS);
        return handle;
    }

    // ============================================================
    // Dialog
    // ============================================================

    private static void showDialog(
            @NonNull final Activity activity,
            @NonNull final UpdateChecker.UpdateInfo info) {

        try {
            if (!isAlive(activity)) return;

            new NewUpdateDialog(activity)
                    .setLatestVersion(info.version)
                    .setOnUpdateListener(new NewUpdateDialog.OnUpdateListener() {
                        @Override
                        public void onUpdateClicked() {
                            openDownload(activity, info);
                        }
                    })
                    .setOnLaterListener(new NewUpdateDialog.OnLaterListener() {
                        @Override
                        public void onLater() {
                            snooze(activity);
                        }
                    })
                    .show();

        } catch (Throwable t) {
            Log.e(TAG, "showDialog failed", t);
        }
    }

    // ============================================================
    // Opening the link (direct APK -> release page -> /releases/latest)
    // ============================================================

    private static void openDownload(
            @NonNull Activity activity,
            @NonNull UpdateChecker.UpdateInfo info) {

        String[] candidates = new String[] {
                info.downloadUrl,
                info.releaseUrl,
                UpdateChecker.RELEASES_LATEST_URL
        };

        String last = null;
        for (int i = 0; i < candidates.length; i++) {
            String url = candidates[i];
            if (url == null || url.isEmpty() || url.equals(last)) continue;
            last = url;

            try {
                activity.startActivity(
                        new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                return;
            } catch (Throwable t) {
                Log.w(TAG, "could not open " + url, t);
            }
        }

        try {
            Toast.makeText(activity, "Unable to open the download link.",
                    Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    // ============================================================
    // Snooze
    // ============================================================

    public static void snooze(@Nullable Context context) {
        if (context == null) return;
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putLong(KEY_SNOOZE_UNTIL,
                            System.currentTimeMillis() + SNOOZE_MS)
                    .apply();
        } catch (Throwable t) {
            Log.e(TAG, "snooze failed", t);
        }
    }

    private static boolean isSnoozed(@NonNull Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(
                    PREFS_NAME, Context.MODE_PRIVATE);
            long until = prefs.getLong(KEY_SNOOZE_UNTIL, 0L);
            long now = System.currentTimeMillis();

            // until > now + SNOOZE_MS means the clock was moved back:
            // ignore the stale value instead of snoozing for days.
            return until > now && until <= now + SNOOZE_MS;
        } catch (Throwable t) {
            return false;
        }
    }

    // ============================================================
    // Helpers
    // ============================================================

    private static boolean isAlive(@Nullable Activity activity) {
        if (activity == null || activity.isFinishing()) return false;
        return !(Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1
                && activity.isDestroyed());
    }
}