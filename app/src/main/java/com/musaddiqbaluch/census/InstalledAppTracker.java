package com.musaddiqbaluch.census;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks installed user apps and their installer/store associations.
 *
 * Design
 * ------
 *   - current installer    = authoritative when the store package is
 *                            currently installed
 *   - historical installer = permanent per-app memory, kept even when
 *                            the store is temporarily absent
 *   - Unknown              = visible fallback when no store can be
 *                            resolved right now
 *   - Shell                = never a category
 *
 * Robustness
 * ----------
 *   - Public methods are safe to call from any thread.
 *   - Every background and main-thread Runnable is wrapped in
 *     try/catch; a throw inside a Handler.post body would otherwise
 *     crash the process.
 *   - Corrupt SharedPreferences, a null PackageManager, a missing
 *     SHA-256 provider, and a null application context all degrade
 *     gracefully.
 *   - If executor.execute() fails (post-shutdown), the callback is
 *     delivered a Result.failure instead of silently being dropped.
 *   - A failed scan never overwrites historical installer data.
 */
public final class InstalledAppTracker {

    private static final String TAG = "InstalledAppTracker";

    private static final String PREFS_NAME = "installed_app_tracker";
    private static final String KEY_INITIALIZED = "snapshot_initialized";
    private static final String KEY_FINGERPRINT = "snapshot_fingerprint";
    private static final String KEY_COUNT = "snapshot_count";
    private static final String KEY_FIRST_ANIMATION_SHOWN = "first_animation_shown";
    private static final String KEY_INSTALLER_HISTORY = "installer_history";

    private static final String JSON_INSTALLER_KEY = "installer_key";
    private static final String JSON_INSTALLER_LABEL = "installer_label";

    public static final String UNKNOWN_INSTALLER_KEY = "__unknown_installer__";
    public static final String UNKNOWN_INSTALLER_LABEL = "Unknown";

    private static final String SHELL_PACKAGE = "com.android.shell";
    private static final String QUERY_ALL_PACKAGES =
            "android.permission.QUERY_ALL_PACKAGES";

    // Stable fallback for createFingerprint when SHA-256 is unavailable.
    // Never matches a real hash, so it always triggers a "changed" scan
    // the next time SHA-256 works, which is the safe direction.
    private static final String FALLBACK_FINGERPRINT =
            "fallback-no-sha256";

    @Nullable private final Context context;
    @Nullable private final SharedPreferences preferences;
    @Nullable private final PackageManager packageManager;

    private final ExecutorService executor;
    private final Handler mainHandler;

    private final AtomicInteger requestSerial;
    private final AtomicBoolean released;

    private final boolean includeSystemApps;

    public InstalledAppTracker(@Nullable Context context) {
        this(context, false);
    }

    public InstalledAppTracker(
            @Nullable Context context,
            boolean includeSystemApps) {

        if (context == null) {
            throw new IllegalArgumentException("Context must not be null");
        }

        Context appContext = null;
        try {
            appContext = context.getApplicationContext();
        } catch (Throwable t) {
            Log.e(TAG, "getApplicationContext failed", t);
        }
        if (appContext == null) {
            appContext = context;
        }
        this.context = appContext;

        SharedPreferences localPrefs = null;
        try {
            localPrefs = appContext.getSharedPreferences(
                    PREFS_NAME, Context.MODE_PRIVATE);
        } catch (Throwable t) {
            Log.e(TAG, "getSharedPreferences failed", t);
            localPrefs = null;
        }
        this.preferences = localPrefs;

        PackageManager localPm = null;
        try {
            localPm = appContext.getPackageManager();
        } catch (Throwable t) {
            Log.e(TAG, "getPackageManager failed", t);
        }
        this.packageManager = localPm;

        this.executor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.requestSerial = new AtomicInteger(0);
        this.released = new AtomicBoolean(false);
        this.includeSystemApps = includeSystemApps;
    }

    // ============================================================
    // Public API
    // ============================================================

    /**
     * Checks the installed application list.
     *
     * Callback is always delivered on the main thread, and always
     * invoked at least once for every accepted call to this method.
     */
    public void checkForChanges(@Nullable final Callback callback) {

        if (callback == null) return;
        if (released.get()) return;

        final int requestId = requestSerial.incrementAndGet();

        try {
            executor.execute(new Runnable() {
                @Override public void run() {
                    handleBackgroundCheck(requestId, callback);
                }
            });

        } catch (RejectedExecutionException e) {
            // Executor already shut down (race with shutdown()).
            Log.w(TAG, "executor rejected task", e);
            deliverFailure(callback, requestId, "Tracker released");

        } catch (Throwable t) {
            Log.e(TAG, "executor.execute failed", t);
            deliverFailure(callback, requestId, "executor failure");
        }
    }

    /**
     * Marks the first-run animation as shown. Safe to call from any
     * thread.
     */
    public void markFirstAnimationShown() {

        if (released.get()) return;

        try {
            SharedPreferences p = preferences;
            if (p == null) return;

            p.edit()
                    .putBoolean(KEY_FIRST_ANIMATION_SHOWN, true)
                    .commit();

        } catch (Throwable t) {
            Log.e(TAG, "markFirstAnimationShown failed", t);
        }
    }

    /**
     * Cancels outstanding work and shuts down the executor. Idempotent.
     */
    public void shutdown() {

        if (!released.compareAndSet(false, true)) return;

        // Invalidate outstanding requests so any in-flight callback is
        // skipped.
        try { requestSerial.incrementAndGet(); }
        catch (Throwable ignored) {}

        try { mainHandler.removeCallbacksAndMessages(null); }
        catch (Throwable t) { Log.e(TAG, "removeCallbacks failed", t); }

        try { executor.shutdownNow(); }
        catch (Throwable t) { Log.e(TAG, "shutdownNow failed", t); }
    }

    // ============================================================
    // Background pipeline
    // ============================================================

    private void handleBackgroundCheck(
            int requestId,
            @NonNull Callback callback) {

        Result result;

        try {
            if (released.get()) return;

            if (!hasCompletePackageVisibility()) {
                result = Result.failure("Package visibility is incomplete.");
            } else {
                Snapshot snapshot = buildCurrentSnapshot();
                if (snapshot == null) {
                    result = Result.failure(
                            "Unable to read installed applications.");
                } else {
                    result = compareAndSave(snapshot);
                }
            }

        } catch (Throwable t) {
            Log.e(TAG, "background scan failed", t);
            result = Result.failure(
                    t.getClass() != null
                            ? t.getClass().getSimpleName()
                            : "unknown error");
        }

        final Result finalResult = result;

        if (released.get()) return;

        try {
            boolean posted = mainHandler.post(new Runnable() {
                @Override public void run() {
                    try {
                        if (released.get()) return;
                        if (requestId != requestSerial.get()) return;

                        try {
                            callback.onResult(finalResult);
                        } catch (Throwable t) {
                            Log.e(TAG, "callback threw", t);
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "main delivery failed", t);
                    }
                }
            });

            if (!posted) {
                Log.w(TAG, "mainHandler.post returned false");
            }

        } catch (Throwable t) {
            Log.e(TAG, "mainHandler.post failed", t);
        }
    }

    /**
     * Delivers a failure result on the main thread. Used only when the
     * bg task could not be scheduled at all.
     */
    private void deliverFailure(
            @NonNull final Callback callback,
            final int requestId,
            @NonNull final String reason) {

        try {
            mainHandler.post(new Runnable() {
                @Override public void run() {
                    try {
                        if (released.get()) return;
                        if (requestId != requestSerial.get()) return;
                        callback.onResult(Result.failure(reason));
                    } catch (Throwable t) {
                        Log.e(TAG, "deliverFailure callback threw", t);
                    }
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "deliverFailure post failed", t);
        }
    }

    // ============================================================
    // Snapshot comparison
    // ============================================================

    private Result compareAndSave(@Nullable Snapshot currentSnapshot) {

        if (currentSnapshot == null) {
            return Result.failure("Current snapshot is null.");
        }

        try {
            boolean firstAnimationShown = safeGetBoolean(
                    KEY_FIRST_ANIMATION_SHOWN, false);

            // First ever scan.
            if (!firstAnimationShown) {
                saveSnapshot(currentSnapshot);
                return Result.success(
                        currentSnapshot.count,
                        currentSnapshot.installers,
                        true,
                        false);
            }

            boolean initialized = safeGetBoolean(KEY_INITIALIZED, false);

            if (!initialized) {
                saveSnapshot(currentSnapshot);
                return Result.success(
                        currentSnapshot.count,
                        currentSnapshot.installers,
                        false,
                        false);
            }

            String previousFingerprint = safeGetString(
                    KEY_FINGERPRINT, null);
            int previousCount = safeGetInt(KEY_COUNT, -1);

            if (previousFingerprint == null || previousCount < 0) {
                saveSnapshot(currentSnapshot);
                return Result.success(
                        currentSnapshot.count,
                        currentSnapshot.installers,
                        false,
                        false);
            }

            boolean packageSetChanged = !currentSnapshot.fingerprint.equals(
                    previousFingerprint);

            saveSnapshot(currentSnapshot);

            return Result.success(
                    currentSnapshot.count,
                    currentSnapshot.installers,
                    false,
                    packageSetChanged);

        } catch (Throwable t) {
            Log.e(TAG, "compareAndSave failed", t);
            // Fall back to reporting the current snapshot as a first
            // run so the UI at least has data. History was not
            // overwritten because saveSnapshot is idempotent and
            // saveInstallerHistory is only called inside
            // buildCurrentSnapshot on success.
            return Result.success(
                    currentSnapshot.count,
                    currentSnapshot.installers,
                    false,
                    false);
        }
    }

    // ============================================================
    // Snapshot building
    // ============================================================

    @Nullable
    private Snapshot buildCurrentSnapshot() {

        if (packageManager == null) {
            Log.e(TAG, "packageManager is null");
            return null;
        }

        Set<String> packageNames = new TreeSet<>();
        Set<String> installedPackageNames = new HashSet<>();
        Map<String, Integer> installerCounts = new HashMap<>();
        Map<String, String> installerLabels = new HashMap<>();
        Map<String, String> installerLabelCache = new HashMap<>();

        Map<String, InstallerRecord> previousHistory;
        try {
            previousHistory = loadInstallerHistory();
        } catch (Throwable t) {
            Log.e(TAG, "loadInstallerHistory failed", t);
            previousHistory = new HashMap<>();
        }

        Map<String, InstallerRecord> currentHistory = new HashMap<>();

        List<ApplicationInfo> applications;

        try {
            applications = packageManager.getInstalledApplications(
                    PackageManager.MATCH_ALL);
        } catch (Throwable t) {
            Log.e(TAG, "getInstalledApplications failed", t);
            throw new IllegalStateException(
                    "Unable to read installed applications.", t);
        }

        if (applications == null) {
            // PackageManager refused to return a list. Do NOT touch
            // history -- treat as an incomplete scan.
            throw new IllegalStateException(
                    "getInstalledApplications returned null");
        }

        try {
            // STEP 1: complete installed-package set.
            for (ApplicationInfo info : applications) {
                if (info == null) continue;
                String packageName = info.packageName;
                if (packageName == null || packageName.isEmpty()) continue;
                installedPackageNames.add(packageName);
            }

            // STEP 2: user applications.
            for (ApplicationInfo info : applications) {
                if (info == null) continue;
                if (!shouldCount(info)) continue;

                String packageName = info.packageName;
                if (packageName == null || packageName.isEmpty()) continue;

                packageNames.add(packageName);

                InstallerRecord historicalRecord =
                        previousHistory.get(packageName);

                InstallSourceData source;
                try {
                    source = readInstallSource(packageName);
                } catch (Throwable t) {
                    Log.e(TAG, "readInstallSource failed for "
                            + packageName, t);
                    source = new InstallSourceData(null, null);
                }

                // STEP 3: historical identity.
                InstallerRecord remembered;
                try {
                    remembered = determineHistoricalInstaller(
                            historicalRecord,
                            source.installingPackage,
                            source.initiatingPackage,
                            installerLabelCache);
                } catch (Throwable t) {
                    Log.e(TAG, "determineHistoricalInstaller failed for "
                            + packageName, t);
                    remembered = historicalRecord;
                }

                if (remembered != null) {
                    currentHistory.put(packageName, remembered);
                }

                // STEP 4: visible identity.
                InstallerRecord visible;
                try {
                    visible = determineVisibleInstaller(
                            historicalRecord,
                            source.installingPackage,
                            source.initiatingPackage,
                            installedPackageNames,
                            installerLabelCache);
                } catch (Throwable t) {
                    Log.e(TAG, "determineVisibleInstaller failed for "
                            + packageName, t);
                    visible = null;
                }

                if (visible == null) {
                    visible = new InstallerRecord(
                            UNKNOWN_INSTALLER_KEY,
                            UNKNOWN_INSTALLER_LABEL);
                }

                String visibleKey = visible.key;
                String visibleLabel = visible.label;

                Integer oldCount = installerCounts.get(visibleKey);
                if (oldCount == null) oldCount = 0;

                installerCounts.put(visibleKey, oldCount + 1);
                installerLabels.put(visibleKey, visibleLabel);
            }

        } catch (Throwable t) {
            // Do NOT save currentHistory on failure -- a partial scan
            // must not erase historical installer associations.
            Log.e(TAG, "scan loop failed", t);
            throw new IllegalStateException(
                    "Unable to read installed applications.", t);
        }

        // STEP 5: persist history only after a complete successful scan.
        try {
            saveInstallerHistory(currentHistory);
        } catch (Throwable t) {
            Log.e(TAG, "saveInstallerHistory failed", t);
        }

        // STEP 6: convert counts to results.
        List<InstallerInfo> installers = new ArrayList<>();

        for (Map.Entry<String, Integer> entry : installerCounts.entrySet()) {
            String key = entry.getKey();
            Integer count = entry.getValue();
            if (key == null || count == null) continue;

            String label = installerLabels.get(key);
            if (label == null || label.isEmpty()) {
                label = UNKNOWN_INSTALLER_KEY.equals(key)
                        ? UNKNOWN_INSTALLER_LABEL
                        : key;
            }

            installers.add(new InstallerInfo(
                    key, label, Math.max(0, count)));
        }

        Collections.sort(installers, new Comparator<InstallerInfo>() {
            @Override public int compare(InstallerInfo a, InstallerInfo b) {
                int c = a.getLabel().compareToIgnoreCase(b.getLabel());
                if (c != 0) return c;
                return a.getKey().compareToIgnoreCase(b.getKey());
            }
        });

        String fingerprint = createFingerprint(packageNames);

        return new Snapshot(
                packageNames.size(),
                fingerprint,
                Collections.unmodifiableList(installers));
    }

    // ============================================================
    // Installer resolution
    // ============================================================

    @Nullable
    private InstallerRecord determineHistoricalInstaller(
            @Nullable InstallerRecord historicalRecord,
            @Nullable String currentInstaller,
            @Nullable String currentInitiating,
            @NonNull Map<String, String> installerLabelCache) {

        if (isRealInstaller(currentInstaller)) {
            return createInstallerRecord(
                    currentInstaller, installerLabelCache);
        }

        if (isRealHistoricalRecord(historicalRecord)) {
            return historicalRecord;
        }

        if (isRealInstaller(currentInitiating)) {
            return createInstallerRecord(
                    currentInitiating, installerLabelCache);
        }

        return null;
    }

    @Nullable
    private InstallerRecord determineVisibleInstaller(
            @Nullable InstallerRecord historicalRecord,
            @Nullable String currentInstaller,
            @Nullable String currentInitiating,
            @NonNull Set<String> installedPackageNames,
            @NonNull Map<String, String> installerLabelCache) {

        if (isRealInstaller(currentInstaller)
                && installedPackageNames.contains(currentInstaller)) {
            return createInstallerRecord(
                    currentInstaller, installerLabelCache);
        }

        if (isRealHistoricalRecord(historicalRecord)
                && installedPackageNames.contains(historicalRecord.key)) {
            return historicalRecord;
        }

        if (isRealInstaller(currentInitiating)
                && installedPackageNames.contains(currentInitiating)) {
            return createInstallerRecord(
                    currentInitiating, installerLabelCache);
        }

        return null;
    }

    @NonNull
    private InstallSourceData readInstallSource(@Nullable String packageName) {

        if (packageName == null || packageName.isEmpty()) {
            return new InstallSourceData(null, null);
        }

        PackageManager pm = packageManager;
        if (pm == null) {
            return new InstallSourceData(null, null);
        }

        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {

                InstallSourceInfo sourceInfo =
                        pm.getInstallSourceInfo(packageName);

                if (sourceInfo == null) {
                    return new InstallSourceData(null, null);
                }

                return new InstallSourceData(
                        sourceInfo.getInstallingPackageName(),
                        sourceInfo.getInitiatingPackageName());
            }

            return new InstallSourceData(
                    pm.getInstallerPackageName(packageName),
                    null);

        } catch (PackageManager.NameNotFoundException e) {
            return new InstallSourceData(null, null);
        } catch (SecurityException e) {
            return new InstallSourceData(null, null);
        } catch (IllegalArgumentException e) {
            return new InstallSourceData(null, null);
        } catch (Throwable t) {
            Log.e(TAG, "readInstallSource failed", t);
            return new InstallSourceData(null, null);
        }
    }

    @Nullable
    private InstallerRecord createInstallerRecord(
            @Nullable String installerPackage,
            @NonNull Map<String, String> installerLabelCache) {

        if (!isRealInstaller(installerPackage)) return null;

        String label = installerLabelCache.get(installerPackage);

        if (label == null || label.isEmpty()) {
            try {
                label = resolveInstallerLabel(installerPackage);
            } catch (Throwable t) {
                Log.e(TAG, "resolveInstallerLabel failed", t);
                label = installerPackage;
            }
            installerLabelCache.put(installerPackage, label);
        }

        return new InstallerRecord(installerPackage, label);
    }

    private boolean isRealInstaller(@Nullable String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;
        if (UNKNOWN_INSTALLER_KEY.equals(packageName)) return false;
        if (SHELL_PACKAGE.equals(packageName)) return false;
        return true;
    }

    private boolean isRealHistoricalRecord(@Nullable InstallerRecord record) {
        if (record == null) return false;
        return isRealInstaller(record.key);
    }

    @NonNull
    private String resolveInstallerLabel(@Nullable String installerPackage) {

        if (installerPackage == null || installerPackage.isEmpty()) {
            return UNKNOWN_INSTALLER_LABEL;
        }

        PackageManager pm = packageManager;
        if (pm == null) return installerPackage;

        try {
            ApplicationInfo installerInfo =
                    pm.getApplicationInfo(installerPackage, 0);

            if (installerInfo != null) {
                CharSequence label = pm.getApplicationLabel(installerInfo);
                if (label != null) {
                    String text = label.toString().trim();
                    if (!text.isEmpty()) return text;
                }
            }

        } catch (Throwable t) {
            // Fall through to package-name fallback. Package may not be
            // installed anymore, which is a normal case.
        }

        return installerPackage;
    }

    // ============================================================
    // History persistence
    // ============================================================

    @NonNull
    private Map<String, InstallerRecord> loadInstallerHistory() {

        Map<String, InstallerRecord> history = new HashMap<>();

        String json = safeGetString(KEY_INSTALLER_HISTORY, null);
        if (json == null || json.isEmpty()) return history;

        try {
            JSONObject root = new JSONObject(json);
            Iterator<String> keys = root.keys();

            while (keys.hasNext()) {
                String packageName = keys.next();
                if (packageName == null || packageName.isEmpty()) continue;

                JSONObject record = root.optJSONObject(packageName);
                if (record == null) continue;

                String installerKey = record.optString(
                        JSON_INSTALLER_KEY, "");
                String installerLabel = record.optString(
                        JSON_INSTALLER_LABEL, "");

                if (!isRealInstaller(installerKey)) continue;

                if (installerLabel == null || installerLabel.isEmpty()) {
                    installerLabel = installerKey;
                }

                history.put(packageName, new InstallerRecord(
                        installerKey, installerLabel));
            }

        } catch (JSONException e) {
            Log.e(TAG, "installer history JSON corrupt, resetting", e);
            // Do NOT clear the stored value here -- a transient parse
            // error should not destroy the history. Return empty so the
            // caller proceeds with an empty map; the next successful
            // save will replace it.
        } catch (Throwable t) {
            Log.e(TAG, "loadInstallerHistory failed", t);
        }

        return history;
    }

    private void saveInstallerHistory(
            @Nullable Map<String, InstallerRecord> history) {

        if (history == null) return;

        SharedPreferences p = preferences;
        if (p == null) return;

        try {
            JSONObject root = new JSONObject();

            for (Map.Entry<String, InstallerRecord> entry : history.entrySet()) {
                String packageName = entry.getKey();
                InstallerRecord record = entry.getValue();

                if (packageName == null || packageName.isEmpty()
                        || record == null
                        || !isRealInstaller(record.key)) {
                    continue;
                }

                JSONObject object = new JSONObject();
                object.put(JSON_INSTALLER_KEY, record.key);
                object.put(JSON_INSTALLER_LABEL,
                        record.label == null || record.label.isEmpty()
                                ? record.key
                                : record.label);
                root.put(packageName, object);
            }

            p.edit()
                    .putString(KEY_INSTALLER_HISTORY, root.toString())
                    .commit();

        } catch (Throwable t) {
            Log.e(TAG, "saveInstallerHistory failed", t);
        }
    }

    private void saveSnapshot(@Nullable Snapshot snapshot) {

        if (snapshot == null) return;

        SharedPreferences p = preferences;
        if (p == null) return;

        try {
            p.edit()
                    .putBoolean(KEY_INITIALIZED, true)
                    .putString(KEY_FINGERPRINT, snapshot.fingerprint)
                    .putInt(KEY_COUNT, snapshot.count)
                    .commit();

        } catch (Throwable t) {
            Log.e(TAG, "saveSnapshot failed", t);
        }
    }

    // ============================================================
    // Static helpers
    // ============================================================

    private boolean shouldCount(@Nullable ApplicationInfo info) {

        if (info == null) return false;
        if (includeSystemApps) return true;

        int flags = info.flags;

        if ((flags & ApplicationInfo.FLAG_SYSTEM) != 0) return false;
        if ((flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) return false;

        return true;
    }

    private boolean hasCompletePackageVisibility() {

        if (android.os.Build.VERSION.SDK_INT < 30) return true;

        Context c = context;
        if (c == null) return false;

        try {
            int result = c.checkCallingOrSelfPermission(QUERY_ALL_PACKAGES);
            return result == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            Log.e(TAG, "checkCallingOrSelfPermission failed", t);
            return false;
        }
    }

    @NonNull
    private String createFingerprint(@Nullable Set<String> packageNames) {

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            Charset utf8 = Charset.forName("UTF-8");

            if (packageNames != null) {
                for (String packageName : packageNames) {
                    if (packageName == null) continue;
                    digest.update(packageName.getBytes(utf8));
                    // Terminator so "ab"+"c" != "a"+"bc".
                    digest.update((byte) 0);
                }
            }

            byte[] hash = digest.digest();

            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                int value = b & 0xFF;
                if (value < 0x10) builder.append('0');
                builder.append(Integer.toHexString(value));
            }

            return builder.toString();

        } catch (Throwable t) {
            // SHA-256 is a mandatory JCA algorithm; this branch should
            // never fire. A stable fallback keeps the tracker running
            // and guarantees the next scan reports "changed".
            Log.e(TAG, "createFingerprint failed", t);
            return FALLBACK_FINGERPRINT;
        }
    }

    // ============================================================
    // Safe preference reads
    // ============================================================

    @Nullable
    private String safeGetString(@NonNull String key, @Nullable String fallback) {
        SharedPreferences p = preferences;
        if (p == null) return fallback;
        try {
            return p.getString(key, fallback);
        } catch (ClassCastException e) {
            Log.e(TAG, "pref " + key + " has wrong type, removing", e);
            try { p.edit().remove(key).apply(); } catch (Throwable ignored) {}
            return fallback;
        } catch (Throwable t) {
            Log.e(TAG, "pref read failed for " + key, t);
            return fallback;
        }
    }

    private boolean safeGetBoolean(@NonNull String key, boolean fallback) {
        SharedPreferences p = preferences;
        if (p == null) return fallback;
        try {
            return p.getBoolean(key, fallback);
        } catch (ClassCastException e) {
            Log.e(TAG, "pref " + key + " has wrong type, removing", e);
            try { p.edit().remove(key).apply(); } catch (Throwable ignored) {}
            return fallback;
        } catch (Throwable t) {
            Log.e(TAG, "pref read failed for " + key, t);
            return fallback;
        }
    }

    private int safeGetInt(@NonNull String key, int fallback) {
        SharedPreferences p = preferences;
        if (p == null) return fallback;
        try {
            return p.getInt(key, fallback);
        } catch (ClassCastException e) {
            Log.e(TAG, "pref " + key + " has wrong type, removing", e);
            try { p.edit().remove(key).apply(); } catch (Throwable ignored) {}
            return fallback;
        } catch (Throwable t) {
            Log.e(TAG, "pref read failed for " + key, t);
            return fallback;
        }
    }

    // ============================================================
    // Public value types (unchanged API)
    // ============================================================

    public interface Callback {
        void onResult(Result result);
    }

    public static final class InstallerInfo {

        private final String key;
        private final String label;
        private final int count;

        public InstallerInfo(
                @Nullable String key,
                @Nullable String label,
                int count) {
            this.key = key == null ? "" : key;
            this.label = label == null ? "" : label;
            this.count = Math.max(0, count);
        }

        public String getKey() { return key; }
        public String getLabel() { return label; }
        public int getCount() { return count; }
    }

    public static final class Result {

        private final boolean success;
        private final boolean firstRun;
        private final boolean installOrDeleteDetected;
        private final int count;
        private final List<InstallerInfo> installers;
        @Nullable private final String errorMessage;

        private Result(
                boolean success,
                int count,
                @Nullable List<InstallerInfo> installers,
                boolean firstRun,
                boolean installOrDeleteDetected,
                @Nullable String errorMessage) {

            this.success = success;
            this.count = Math.max(0, count);

            if (installers == null) {
                this.installers = Collections.emptyList();
            } else {
                this.installers = Collections.unmodifiableList(
                        new ArrayList<>(installers));
            }

            this.firstRun = firstRun;
            this.installOrDeleteDetected = installOrDeleteDetected;
            this.errorMessage = errorMessage;
        }

        private static Result success(
                int count,
                @Nullable List<InstallerInfo> installers,
                boolean firstRun,
                boolean installOrDeleteDetected) {
            return new Result(true, count, installers,
                    firstRun, installOrDeleteDetected, null);
        }

        private static Result failure(@NonNull String errorMessage) {
            return new Result(false, 0,
                    Collections.<InstallerInfo>emptyList(),
                    false, false, errorMessage);
        }

        public boolean isSuccess() { return success; }
        public boolean isFirstRun() { return firstRun; }
        public boolean isInstallOrDeleteDetected() {
            return installOrDeleteDetected;
        }
        public int getCount() { return count; }
        public List<InstallerInfo> getInstallerInfos() { return installers; }
        @Nullable public String getErrorMessage() { return errorMessage; }
    }

    // ============================================================
    // Internal value types
    // ============================================================

    private static final class Snapshot {

        final int count;
        final String fingerprint;
        final List<InstallerInfo> installers;

        Snapshot(
                int count,
                @Nullable String fingerprint,
                @Nullable List<InstallerInfo> installers) {

            this.count = Math.max(0, count);
            this.fingerprint = fingerprint == null ? "" : fingerprint;

            if (installers == null) {
                this.installers = Collections.emptyList();
            } else {
                this.installers = Collections.unmodifiableList(
                        new ArrayList<>(installers));
            }
        }
    }

    private static final class InstallerRecord {

        final String key;
        final String label;

        InstallerRecord(
                @Nullable String key,
                @Nullable String label) {

            this.key = key == null || key.isEmpty()
                    ? UNKNOWN_INSTALLER_KEY
                    : key;

            this.label = label == null || label.isEmpty()
                    ? (UNKNOWN_INSTALLER_KEY.equals(this.key)
                            ? UNKNOWN_INSTALLER_LABEL
                            : this.key)
                    : label;
        }
    }

    private static final class InstallSourceData {

        @Nullable final String installingPackage;
        @Nullable final String initiatingPackage;

        InstallSourceData(
                @Nullable String installingPackage,
                @Nullable String initiatingPackage) {
            this.installingPackage = installingPackage;
            this.initiatingPackage = initiatingPackage;
        }
    }
}