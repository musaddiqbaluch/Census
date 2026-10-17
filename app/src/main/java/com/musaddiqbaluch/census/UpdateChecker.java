package com.musaddiqbaluch.census;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Checks GitHub Releases for a newer version of the app.
 *
 * Primary:  GitHub REST API  GET /repos/{owner}/{repo}/releases/latest
 *           -> version (tag_name), direct APK url (assets[].browser_download_url),
 *              release page url (html_url).
 * Fallback: HEAD https://github.com/{owner}/{repo}/releases/latest (no API
 *           quota) and read the redirect target to get the tag.
 *
 * Callbacks are delivered on the main thread, only while the Activity is
 * alive and the Request has not been cancelled.
 */
public final class UpdateChecker {

    private static final String TAG = "UpdateChecker";

    // ---- Change these two when the repository changes ----
    public static final String GITHUB_OWNER = "musaddiqbaluch";
    public static final String GITHUB_REPO = "Census";

    public static final String REPO_URL =
            "https://github.com/" + GITHUB_OWNER + "/" + GITHUB_REPO;
    public static final String RELEASES_LATEST_URL =
            REPO_URL + "/releases/latest";

    private static final String API_LATEST_URL =
            "https://api.github.com/repos/" + GITHUB_OWNER + "/"
                    + GITHUB_REPO + "/releases/latest";

    private static final String TAG_MARKER = "/releases/tag/";
    private static final String GITHUB_PREFIX = "https://github.com/";

    private static final int CONNECT_TIMEOUT_MS = 6_000;
    private static final int READ_TIMEOUT_MS = 6_000;
    private static final int MAX_RESPONSE_CHARS = 1024 * 1024;

    private static final Handler MAIN_HANDLER =
            new Handler(Looper.getMainLooper());

    private UpdateChecker() {
        // Utility class.
    }

    // ============================================================
    // Public types
    // ============================================================

    /** Result of a successful check. */
    public static final class UpdateInfo {
        /** Latest version, without a leading "v" (e.g. "1.2.0"). */
        @NonNull public final String version;
        /** Direct APK url when known, otherwise the release page url. */
        @NonNull public final String downloadUrl;
        /** Release page url (always a github.com url). */
        @NonNull public final String releaseUrl;
        /** True if downloadUrl points straight at the APK file. */
        public final boolean directDownload;

        UpdateInfo(@NonNull String version,
                   @NonNull String downloadUrl,
                   @NonNull String releaseUrl,
                   boolean directDownload) {
            this.version = version;
            this.downloadUrl = downloadUrl;
            this.releaseUrl = releaseUrl;
            this.directDownload = directDownload;
        }
    }

    public interface OnCheckCompleteListener {
        void onUpdateFound(UpdateInfo info);
        void onNoUpdateFound();
        void onError(Exception e);
    }

    public static final class Request {

        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        @NonNull
        public Request cancel() {
            cancelled.set(true);
            return this;
        }

        public boolean isCancelled() {
            return cancelled.get();
        }
    }

    /** Repo or release does not exist (HTTP 404). No fallback is tried. */
    private static final class NotFoundException extends Exception {
        NotFoundException(String message) {
            super(message);
        }
    }

    // ============================================================
    // Public API
    // ============================================================

    @NonNull
    public static String getInstalledVersionName(@Nullable Context context) {

        if (context == null) return "0";

        try {
            PackageManager pm = context.getPackageManager();
            if (pm == null) return "0";

            PackageInfo info = pm.getPackageInfo(context.getPackageName(), 0);
            if (info == null) return "0";

            String name = info.versionName;
            return (name == null || name.isEmpty()) ? "0" : name;

        } catch (Throwable t) {
            Log.e(TAG, "getInstalledVersionName failed", t);
            return "0";
        }
    }

    @NonNull
    public static Request checkForUpdates(
            @Nullable final Activity activity,
            @Nullable final OnCheckCompleteListener listener) {

        final Request request = new Request();

        if (activity == null) {
            Log.w(TAG, "checkForUpdates: null activity");
            return request;
        }

        final String currentVersion = getInstalledVersionName(activity);
        final WeakReference<Activity> activityRef =
                new WeakReference<>(activity);

        try {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (request.isCancelled()) return;

                        UpdateInfo info = fetchLatest();

                        if (request.isCancelled()) return;

                        if (isVersionHigher(info.version, currentVersion)) {
                            dispatchFound(activityRef, listener, request, info);
                        } else {
                            dispatchNone(activityRef, listener, request);
                        }

                    } catch (final Exception e) {
                        Log.e(TAG, "Update check failed", e);
                        dispatchError(activityRef, listener, request, e);
                    } catch (Throwable t) {
                        Log.e(TAG, "Update check crashed", t);
                        dispatchError(activityRef, listener, request,
                                new Exception("Update check crashed", t));
                    }
                }
            }, "UpdateChecker").start();

        } catch (Throwable t) {
            Log.e(TAG, "Failed to spawn update check thread", t);
            dispatchError(activityRef, listener, request,
                    new Exception("Could not start update check", t));
        }

        return request;
    }

    // ============================================================
    // Fetching (API first, redirect fallback)
    // ============================================================

    @NonNull
    private static UpdateInfo fetchLatest() throws Exception {
        try {
            return fetchFromApi();
        } catch (NotFoundException e) {
            throw e;
        } catch (Exception apiError) {
            Log.w(TAG, "API failed, trying redirect fallback", apiError);
            try {
                UpdateInfo info = fetchFromRedirect();
                if (info != null) return info;
            } catch (Exception fallbackError) {
                Log.w(TAG, "Redirect fallback failed", fallbackError);
            }
            throw apiError;
        }
    }

    @NonNull
    private static UpdateInfo fetchFromApi() throws Exception {

        HttpURLConnection connection = null;
        try {
            connection = open(API_LATEST_URL);
            connection.setRequestMethod("GET");
            connection.setRequestProperty(
                    "Accept", "application/vnd.github+json");
            connection.setRequestProperty(
                    "X-GitHub-Api-Version", "2022-11-28");
            connection.setInstanceFollowRedirects(true);

            int code = connection.getResponseCode();

            if (code == HttpURLConnection.HTTP_OK) {
                return parseApiResponse(readBody(connection.getInputStream()));
            }
            if (code == 404) {
                throw new NotFoundException(
                        "No release found for " + GITHUB_OWNER + "/"
                                + GITHUB_REPO + " (HTTP 404).");
            }
            if (code == 403 || code == 429) {
                throw new Exception(
                        "GitHub API rate limit reached. Try again later.");
            }
            throw new Exception("GitHub returned HTTP " + code);

        } finally {
            disconnect(connection);
        }
    }

    @NonNull
    private static UpdateInfo parseApiResponse(@NonNull String json)
            throws Exception {

        JSONObject root = new JSONObject(json);

        String tag = root.optString("tag_name", "");
        if (tag.isEmpty()) tag = root.optString("name", "");

        String version = stripVersionPrefix(tag);
        if (version.isEmpty()) {
            throw new Exception("Could not read version from GitHub response");
        }

        String releaseUrl = root.optString("html_url", "");
        if (!isGithubUrl(releaseUrl)) {
            releaseUrl = RELEASES_LATEST_URL;
        }

        // Find the first .apk asset: its browser_download_url is the
        // direct download link.
        String apkUrl = null;
        JSONArray assets = root.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.optJSONObject(i);
                if (asset == null) continue;

                String name = asset.optString("name", "");
                String url = asset.optString("browser_download_url", "");

                if (name.toLowerCase(Locale.ROOT).endsWith(".apk")
                        && isGithubUrl(url)) {
                    apkUrl = url;
                    break;
                }
            }
        }

        if (apkUrl != null) {
            return new UpdateInfo(version, apkUrl, releaseUrl, true);
        }
        return new UpdateInfo(version, releaseUrl, releaseUrl, false);
    }

    /**
     * Fallback that does not use the API quota: /releases/latest answers
     * with a redirect to /releases/tag/{tag}. Returns null if the repo has
     * no release (redirects to /releases instead).
     */
    @Nullable
    private static UpdateInfo fetchFromRedirect() throws Exception {

        HttpURLConnection connection = null;
        try {
            connection = open(RELEASES_LATEST_URL);
            connection.setRequestMethod("HEAD");
            connection.setInstanceFollowRedirects(false);

            int code = connection.getResponseCode();
            if (code < 300 || code > 399) return null;

            String location = connection.getHeaderField("Location");
            if (location == null) return null;

            int idx = location.indexOf(TAG_MARKER);
            if (idx < 0) return null;

            String tag = Uri.decode(
                    location.substring(idx + TAG_MARKER.length()));
            int cut = tag.indexOf('?');
            if (cut >= 0) tag = tag.substring(0, cut);

            String version = stripVersionPrefix(tag);
            if (version.isEmpty()) return null;

            String releaseUrl = isGithubUrl(location)
                    ? location
                    : RELEASES_LATEST_URL;

            return new UpdateInfo(version, releaseUrl, releaseUrl, false);

        } finally {
            disconnect(connection);
        }
    }

    @NonNull
    private static HttpURLConnection open(@NonNull String url)
            throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", "Census-Android-App");
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        return c;
    }

    private static void disconnect(@Nullable HttpURLConnection c) {
        if (c == null) return;
        try {
            c.disconnect();
        } catch (Throwable ignored) {
        }
    }

    /**
     * Reads the body in fixed-size chunks so the size limit is enforced
     * even when the server sends one very long line.
     */
    @NonNull
    private static String readBody(@Nullable InputStream in) throws Exception {

        if (in == null) return "";

        Reader reader = null;
        try {
            reader = new InputStreamReader(in, StandardCharsets.UTF_8);

            StringBuilder sb = new StringBuilder(8_192);
            char[] buffer = new char[4_096];
            int total = 0;
            int read;

            while ((read = reader.read(buffer)) != -1) {
                total += read;
                if (total > MAX_RESPONSE_CHARS) {
                    throw new Exception("GitHub response too large");
                }
                sb.append(buffer, 0, read);
            }
            return sb.toString();

        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static boolean isGithubUrl(@Nullable String url) {
        return url != null && url.startsWith(GITHUB_PREFIX);
    }

    // ============================================================
    // Version handling
    // ============================================================

    @NonNull
    private static String stripVersionPrefix(@NonNull String version) {

        String trimmed = version.trim();
        if (trimmed.isEmpty()) return trimmed;

        char first = trimmed.charAt(0);
        if (first == 'v' || first == 'V') {
            return trimmed.substring(1).trim();
        }
        return trimmed;
    }

    private static boolean isVersionHigher(
            @Nullable String latest,
            @Nullable String current) {

        if (latest == null || latest.isEmpty()) return false;
        if (current == null || current.isEmpty()) return true;

        String[] latestParts = latest.split("\\.");
        String[] currentParts = current.split("\\.");

        int length = Math.max(latestParts.length, currentParts.length);

        for (int i = 0; i < length; i++) {
            int a = i < latestParts.length
                    ? parseVersionComponent(latestParts[i]) : 0;
            int b = i < currentParts.length
                    ? parseVersionComponent(currentParts[i]) : 0;

            if (a > b) return true;
            if (a < b) return false;
        }
        return false;
    }

    /**
     * Reads only the leading digits of a component, so "0-beta" is 0
     * (not 0 followed by other digits glued together) and "2rc1" is 2.
     */
    private static int parseVersionComponent(@Nullable String str) {

        if (str == null) return 0;

        String s = str.trim();
        int end = 0;
        while (end < s.length()) {
            char c = s.charAt(end);
            if (c < '0' || c > '9') break;
            end++;
        }

        if (end == 0) return 0;
        if (end > 9) return Integer.MAX_VALUE;

        try {
            return Integer.parseInt(s.substring(0, end));
        } catch (Throwable t) {
            return 0;
        }
    }

    // ============================================================
    // Main-thread dispatch
    // ============================================================

    private static void dispatchFound(
            @NonNull WeakReference<Activity> activityRef,
            @Nullable final OnCheckCompleteListener listener,
            @NonNull Request request,
            @NonNull final UpdateInfo info) {

        postToMain(activityRef, request, new Runnable() {
            @Override public void run() {
                if (listener != null) listener.onUpdateFound(info);
            }
        });
    }

    private static void dispatchNone(
            @NonNull WeakReference<Activity> activityRef,
            @Nullable final OnCheckCompleteListener listener,
            @NonNull Request request) {

        postToMain(activityRef, request, new Runnable() {
            @Override public void run() {
                if (listener != null) listener.onNoUpdateFound();
            }
        });
    }

    private static void dispatchError(
            @NonNull WeakReference<Activity> activityRef,
            @Nullable final OnCheckCompleteListener listener,
            @NonNull Request request,
            @NonNull final Exception e) {

        postToMain(activityRef, request, new Runnable() {
            @Override public void run() {
                if (listener != null) listener.onError(e);
            }
        });
    }

    private static void postToMain(
            @NonNull final WeakReference<Activity> activityRef,
            @NonNull final Request request,
            @NonNull final Runnable r) {

        try {
            MAIN_HANDLER.post(new Runnable() {
                @Override public void run() {
                    if (request.isCancelled()) return;

                    Activity activity = activityRef.get();
                    if (activity == null) return;
                    if (activity.isFinishing()) return;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1
                            && activity.isDestroyed()) {
                        return;
                    }

                    try {
                        r.run();
                    } catch (Throwable t) {
                        Log.e(TAG, "callback threw", t);
                    }
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "postToMain failed", t);
        }
    }
}
