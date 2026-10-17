package com.musaddiqbaluch.census;

import android.animation.LayoutTransition;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.textview.MaterialTextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Owns the installer TabLayout, the persisted selection, the tab scroll
 * position, and the appCount NUMERIC VALUE via AppCountTextAnimator.
 *
 * Flicker fixes
 * -------------
 *   1. LayoutTransition timing -- installed only after the initial
 *      population is complete, so the first population never animates.
 *
 *   2. Two-phase population -- the tab list is cached in
 *      SharedPreferences and restored synchronously in the constructor,
 *      so the second and every subsequent launch's first frame already
 *      shows the full strip.
 *
 * Scroll restore fix
 * ------------------
 *   Two independent races caused the saved scroll position to be
 *   "sometimes lost" on relaunch:
 *
 *   Race 1 -- post() runs before layout.
 *     View.post(...) fires BEFORE the next measure/layout pass, so
 *     content.getWidth() was still 0 (or the pre-addTab width), and
 *     the scroll target clamped to zero. Fixed by waiting for
 *     OnGlobalLayoutListener, which fires AFTER layout.
 *
 *   Race 2 -- TabLayout's internal scrollToSelectedTab().
 *     TabLayout.selectTab(tab, false) unconditionally calls
 *     scrollToSelectedTab() internally. If the desired tab was not
 *     yet on screen, TabLayout started its own 300 ms scroll animation
 *     which overrode our restored position on the next frame.
 *     Fixed by scrolling to the saved position FIRST and updating
 *     the selection SECOND. By then the desired tab is already visible
 *     and TabLayout finds nothing to animate.
 *
 * Tab label typeface
 * ------------------
 *   All tab labels use SystemFont.medium() -- the platform's system
 *   sans-serif at weight 500. This produces a uniform Medium weight
 *   for both selected and unselected tabs, matching the M3 spec
 *   where the visual distinction between the two states comes from
 *   the text color, not the font weight.
 *
 *   Using a heavier weight on the selected tab made the label wider
 *   than the unselected state, forcing the whole strip to re-flow by
 *   a few pixels on every selection change. SystemFont.medium() for
 *   both states removes that re-flow entirely.
 *
 * Robustness
 * ----------
 *   - Public methods are safe to call from any thread.
 *   - Every listener and every Handler post body is wrapped in
 *     try/catch.
 *   - Corrupt SharedPreferences (wrong type, bad JSON) degrades to
 *     defaults.
 *   - A broken theme degrades to hardcoded M3 defaults for tab colors.
 *   - destroy() is idempotent.
 */
public final class InstallerTabController {

    private static final String TAG = "InstallerTabController";

    private static final String PREFS_NAME = "installer_tab_state";
    private static final String KEY_SELECTED_INSTALLER = "selected_installer";
    private static final String KEY_SELECTED_LABEL = "selected_installer_label";
    private static final String KEY_SCROLL_X = "scroll_x";

    private static final String KEY_CACHED_ENTRIES = "cached_entries";
    private static final String KEY_CACHED_TOTAL = "cached_total";

    private static final String JSON_KEY = "k";
    private static final String JSON_LABEL = "l";
    private static final String JSON_COUNT = "c";

    public static final String TOTAL_KEY = "__total_user_apps__";
    private static final String TOTAL_LABEL = "Total User Apps";

    private static final float TAB_TEXT_SIZE_SP = 16f;

    private static final long TAB_APPEAR_DURATION = 220L;
    private static final long TAB_DISAPPEAR_DURATION = 180L;
    private static final long SCROLL_SAVE_DELAY = 350L;

    private static final int FALLBACK_TAB_SELECTED_LIGHT    = 0xFF6750A4;
    private static final int FALLBACK_TAB_SELECTED_DARK     = 0xFFD0BCFF;
    private static final int FALLBACK_TAB_UNSELECTED_LIGHT  = 0xFF49454F;
    private static final int FALLBACK_TAB_UNSELECTED_DARK   = 0xFFCAC4D0;

    private final Context context;
    private final TabLayout tabLayout;
    private final AppCountTextAnimator appCountAnimator;
    private final MaterialDistributionBar materialDistributionBar;

    @Nullable
    private final SharedPreferences preferences;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Nullable
    private HapticFeedbackManager hapticManager;

    private boolean transitionInstalled = false;

    private final Runnable saveScrollRunnable = new Runnable() {
        @Override public void run() {
            try {
                saveScrollPosition();
            } catch (Throwable t) {
                Log.e(TAG, "saveScrollRunnable failed", t);
            }
        }
    };

    private final ViewTreeObserver.OnScrollChangedListener scrollChangedListener =
            new ViewTreeObserver.OnScrollChangedListener() {
                @Override
                public void onScrollChanged() {
                    try {
                        if (destroyed) return;

                        int currentScrollX = tabLayout.getScrollX();
                        if (currentScrollX == lastScrollX) return;

                        lastScrollX = currentScrollX;
                        scheduleScrollSave();

                    } catch (Throwable t) {
                        Log.e(TAG, "scrollChangedListener failed", t);
                    }
                }
            };

    private final TabLayout.OnTabSelectedListener tabSelectedListener =
            new TabLayout.OnTabSelectedListener() {

                @Override
                public void onTabSelected(TabLayout.Tab tab) {
                    try {
                        if (destroyed || internalSelection || tab == null) return;

                        String key = getTabKey(tab);
                        if (key == null) return;

                        selectedKey = key;

                        String label = getTabLabel(tab);
                        if (label != null && !label.isEmpty()) {
                            selectedLabel = label;
                        } else {
                            selectedLabel = TOTAL_KEY.equals(key)
                                    ? TOTAL_LABEL
                                    : key;
                        }

                        saveSelectedState();
                        refreshAllTabStyles();
                        updateCount();

                        materialDistributionBar.setSelectedInstaller(key);

                        if (!firstUpdate) {
                            performSelectionHaptic();
                        }

                    } catch (Throwable t) {
                        Log.e(TAG, "onTabSelected failed", t);
                    }
                }

                @Override public void onTabUnselected(TabLayout.Tab tab) {}
                @Override public void onTabReselected(TabLayout.Tab tab) {}
            };

    private final List<InstallerEntry> entries = new ArrayList<>();

    private String selectedKey = TOTAL_KEY;
    private String selectedLabel = TOTAL_LABEL;

    private int totalUserApps = 0;
    private int savedScrollX = 0;
    private int lastScrollX = 0;

    private boolean internalSelection = false;
    private boolean firstUpdate = true;
    private boolean destroyed = false;

    public InstallerTabController(
            @NonNull Context context,
            @NonNull TabLayout tabLayout,
            @NonNull AppCountTextAnimator appCountAnimator,
            @NonNull MaterialDistributionBar materialDistributionBar) {

        this.context = context;
        this.tabLayout = tabLayout;
        this.appCountAnimator = appCountAnimator;
        this.materialDistributionBar = materialDistributionBar;

        SharedPreferences localPrefs = null;
        try {
            localPrefs = context.getSharedPreferences(
                    PREFS_NAME, Context.MODE_PRIVATE);
        } catch (Throwable t) {
            Log.e(TAG, "getSharedPreferences failed", t);
        }
        this.preferences = localPrefs;

        try { loadState(); }
        catch (Throwable t) { Log.e(TAG, "loadState failed", t); }

        try { configureTabLayout(); }
        catch (Throwable t) { Log.e(TAG, "configureTabLayout failed", t); }

        try { MaterialTabScrollBar.attach(tabLayout); }
        catch (Throwable t) { Log.e(TAG, "MaterialTabScrollBar.attach failed", t); }

        try { installScrollListener(); }
        catch (Throwable t) { Log.e(TAG, "installScrollListener failed", t); }

        try { tabLayout.addOnTabSelectedListener(tabSelectedListener); }
        catch (Throwable t) { Log.e(TAG, "addOnTabSelectedListener failed", t); }

        // Restore the cached tab list synchronously so the very first
        // frame already shows the full strip. On the very first launch
        // (no cache), this adds only the Total tab.
        try { restoreCachedTabs(); }
        catch (Throwable t) { Log.e(TAG, "restoreCachedTabs failed", t); }

        // Fallback: make sure Total is present even if the cache was
        // corrupt or missing.
        try { ensureInitialTotalTab(); }
        catch (Throwable t) { Log.e(TAG, "ensureInitialTotalTab failed", t); }

        // The LayoutTransition is intentionally NOT installed here.
        // It is installed on the first updateOnMain() call, after the
        // initial list is already in place, so the first population
        // never animates.

        // Also schedule a scroll restore after the first layout pass,
        // because restoreCachedTabs() populated the strip but could not
        // scroll it (the strip had not been laid out yet). This runs in
        // parallel with the cache-based restore: whichever path finds
        // a valid layout first wins, and the other becomes a no-op.
        try { scheduleScrollThenSelect(); }
        catch (Throwable t) { Log.e(TAG, "initial scheduleScrollThenSelect failed", t); }

        try { materialDistributionBar.setSelectedInstaller(selectedKey); }
        catch (Throwable t) { Log.e(TAG, "initial setSelectedInstaller failed", t); }
    }

    // ============================================================
    // Configuration
    // ============================================================

    private void configureTabLayout() {
        tabLayout.setTabMode(TabLayout.MODE_SCROLLABLE);
    }

    private void ensureInitialTotalTab() {

        if (findTabByKey(TOTAL_KEY) != null) return;

        TabLayout.Tab totalTab = tabLayout.newTab();
        totalTab.setTag(TOTAL_KEY);
        totalTab.setText(TOTAL_LABEL);
        totalTab.setCustomView(createTabTextView(TOTAL_LABEL, false));

        tabLayout.addTab(totalTab, 0, false);
    }

    private void installTabLayoutTransition() {

        View child = tabLayout.getChildAt(0);
        if (!(child instanceof ViewGroup)) return;

        ViewGroup tabStrip = (ViewGroup) child;

        LayoutTransition transition = new LayoutTransition();

        transition.setDuration(LayoutTransition.APPEARING, TAB_APPEAR_DURATION);
        transition.setDuration(LayoutTransition.DISAPPEARING, TAB_DISAPPEAR_DURATION);

        transition.setInterpolator(
                LayoutTransition.APPEARING, new DecelerateInterpolator(1.4f));
        transition.setInterpolator(
                LayoutTransition.DISAPPEARING, new AccelerateInterpolator(1.2f));

        try { transition.setAnimator(LayoutTransition.CHANGE_APPEARING, null); }
        catch (Throwable t) { Log.e(TAG, "disable CHANGE_APPEARING failed", t); }
        try { transition.setAnimator(LayoutTransition.CHANGE_DISAPPEARING, null); }
        catch (Throwable t) { Log.e(TAG, "disable CHANGE_DISAPPEARING failed", t); }

        transition.setAnimateParentHierarchy(false);

        tabStrip.setLayoutTransition(transition);
    }

    private void installScrollListener() {

        ViewTreeObserver observer = tabLayout.getViewTreeObserver();
        if (observer == null || !observer.isAlive()) {
            tabLayout.post(new Runnable() {
                @Override public void run() {
                    try {
                        if (destroyed) return;
                        ViewTreeObserver o = tabLayout.getViewTreeObserver();
                        if (o != null && o.isAlive()) {
                            o.addOnScrollChangedListener(scrollChangedListener);
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "deferred scroll listener failed", t);
                    }
                }
            });
            return;
        }

        observer.addOnScrollChangedListener(scrollChangedListener);
    }

    // ============================================================
    // Tab list cache
    // ============================================================

    private void restoreCachedTabs() {

        if (preferences == null) return;

        // 1. Total tab is always at index 0.
        TabLayout.Tab total = tabLayout.newTab();
        total.setTag(TOTAL_KEY);
        total.setText(TOTAL_LABEL);
        total.setCustomView(createTabTextView(TOTAL_LABEL, false));
        tabLayout.addTab(total, 0, false);

        // 2. Cached total count.
        try {
            totalUserApps = Math.max(0,
                    preferences.getInt(KEY_CACHED_TOTAL, 0));
        } catch (Throwable t) {
            totalUserApps = 0;
        }

        // 3. Cached installer entries.
        String json;
        try {
            json = preferences.getString(KEY_CACHED_ENTRIES, null);
        } catch (Throwable t) {
            json = null;
        }
        if (json == null || json.isEmpty()) return;

        try {
            JSONArray arr = new JSONArray(json);

            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;

                String key = o.optString(JSON_KEY, "");
                String label = o.optString(JSON_LABEL, "");
                int count = o.optInt(JSON_COUNT, 0);

                if (key.isEmpty() || TOTAL_KEY.equals(key)) continue;
                if (label.isEmpty()) label = key;
                if (count < 0) count = 0;

                entries.add(new InstallerEntry(key, label, count));

                TabLayout.Tab tab = tabLayout.newTab();
                tab.setTag(key);
                tab.setText(label);
                tab.setCustomView(createTabTextView(label, false));
                tabLayout.addTab(tab, false);
            }

            refreshAllTabStyles();

        } catch (Throwable t) {
            Log.e(TAG, "restoreCachedTabs: parse failed", t);
        }

        // NOTE: selection is deliberately NOT restored here.
        // It is applied by doScrollThenSelect() after the first layout
        // pass, so the scroll happens first and TabLayout's internal
        // scrollToSelectedTab() cannot race with our restore.
    }

    private void saveCachedTabs() {

        if (preferences == null) return;

        try {
            JSONArray arr = new JSONArray();

            for (InstallerEntry entry : entries) {
                if (entry == null) continue;

                JSONObject o = new JSONObject();
                o.put(JSON_KEY, entry.key);
                o.put(JSON_LABEL, entry.label);
                o.put(JSON_COUNT, entry.count);
                arr.put(o);
            }

            preferences.edit()
                    .putString(KEY_CACHED_ENTRIES, arr.toString())
                    .putInt(KEY_CACHED_TOTAL, totalUserApps)
                    .apply();

        } catch (Throwable t) {
            Log.e(TAG, "saveCachedTabs failed", t);
        }
    }

    // ============================================================
    // Public API
    // ============================================================

    public void update(
            int newTotalUserApps,
            @Nullable final List<InstalledAppTracker.InstallerInfo> installerInfos,
            final boolean ignored) {

        if (destroyed) return;

        final List<InstalledAppTracker.InstallerInfo> snapshot;
        try {
            snapshot = installerInfos == null
                    ? null
                    : new ArrayList<>(installerInfos);
        } catch (Throwable t) {
            Log.e(TAG, "snapshot copy failed", t);
            return;
        }

        final int total = Math.max(0, newTotalUserApps);

        try {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                boolean posted = mainHandler.post(new Runnable() {
                    @Override public void run() {
                        try {
                            if (destroyed) return;
                            updateOnMain(total, snapshot, ignored);
                        } catch (Throwable t) {
                            Log.e(TAG, "updateOnMain post failed", t);
                        }
                    }
                });
                if (!posted) {
                    Log.w(TAG, "update: mainHandler.post returned false");
                }
                return;
            }

            updateOnMain(total, snapshot, ignored);

        } catch (Throwable t) {
            Log.e(TAG, "update failed", t);
        }
    }

    public void destroy() {

        if (destroyed) return;
        destroyed = true;

        try { mainHandler.removeCallbacks(saveScrollRunnable); }
        catch (Throwable t) { Log.e(TAG, "removeCallbacks failed", t); }

        try {
            ViewTreeObserver observer = tabLayout.getViewTreeObserver();
            if (observer != null && observer.isAlive()) {
                observer.removeOnScrollChangedListener(scrollChangedListener);
            }
        } catch (Throwable t) { Log.e(TAG, "remove scroll listener failed", t); }

        try { tabLayout.removeOnTabSelectedListener(tabSelectedListener); }
        catch (Throwable t) { Log.e(TAG, "remove tab listener failed", t); }

        try { saveSelectedState(); }
        catch (Throwable t) { Log.e(TAG, "saveSelectedState failed", t); }

        try { saveScrollPosition(); }
        catch (Throwable t) { Log.e(TAG, "saveScrollPosition failed", t); }

        try {
            if (hapticManager != null) {
                hapticManager.destroy();
                hapticManager = null;
            }
        } catch (Throwable t) { Log.e(TAG, "haptic destroy failed", t); }

        try { materialDistributionBar.destroy(); }
        catch (Throwable t) { Log.e(TAG, "bar destroy failed", t); }
    }

    // ============================================================
    // Main-thread update
    // ============================================================

    private void updateOnMain(
            int newTotalUserApps,
            @Nullable List<InstalledAppTracker.InstallerInfo> installerInfos,
            boolean ignored) {

        try {
            totalUserApps = Math.max(0, newTotalUserApps);

            buildCurrentEntries(installerInfos);

            materialDistributionBar.setDistribution(
                    totalUserApps, installerInfos);

            if (!TOTAL_KEY.equals(selectedKey)
                    && findEntry(selectedKey) == null) {

                selectedKey = TOTAL_KEY;
                selectedLabel = TOTAL_LABEL;
                saveSelectedState();
                materialDistributionBar.setSelectedInstaller(TOTAL_KEY);
            }

            reconcileTabs();

            if (!transitionInstalled) {
                try { installTabLayoutTransition(); }
                catch (Throwable t) {
                    Log.e(TAG, "installTabLayoutTransition failed", t);
                }
                transitionInstalled = true;
            }

            saveCachedTabs();

            // Restore the scroll position, THEN update the selection.
            // Doing this in one deferred pass (after layout) prevents
            // TabLayout's internal scrollToSelectedTab() from racing
            // with our restore.
            scheduleScrollThenSelect();

            firstUpdate = false;

        } catch (Throwable t) {
            Log.e(TAG, "updateOnMain failed", t);
        }
    }

    private void buildCurrentEntries(
            @Nullable List<InstalledAppTracker.InstallerInfo> installerInfos) {

        entries.clear();

        if (installerInfos == null || installerInfos.isEmpty()) return;

        for (InstalledAppTracker.InstallerInfo info : installerInfos) {
            if (info == null) continue;

            try {
                String key = info.getKey();
                String label = info.getLabel();
                int count = info.getCount();

                if (key == null || key.isEmpty()) continue;
                if (count <= 0) continue;
                if (label == null || label.isEmpty()) label = key;

                entries.add(new InstallerEntry(key, label, count));

            } catch (Throwable t) {
                Log.e(TAG, "bad InstallerInfo entry, skipped", t);
            }
        }
    }

    private void reconcileTabs() {

        try {
            for (int i = tabLayout.getTabCount() - 1; i >= 1; i--) {
                TabLayout.Tab tab = tabLayout.getTabAt(i);
                if (tab == null) continue;

                String key = getTabKey(tab);
                if (key == null) continue;
                if (findEntry(key) == null) tabLayout.removeTab(tab);
            }

            for (int entryIndex = 0; entryIndex < entries.size(); entryIndex++) {
                InstallerEntry entry = entries.get(entryIndex);
                TabLayout.Tab tab = findTabByKey(entry.key);

                if (tab == null) {
                    addInstallerTab(entry, entryIndex + 1);
                } else {
                    updateTab(tab, entry);
                }
            }

            if (findTabByKey(TOTAL_KEY) == null) {
                TabLayout.Tab newTotal = tabLayout.newTab();
                newTotal.setTag(TOTAL_KEY);
                newTotal.setText(TOTAL_LABEL);
                newTotal.setCustomView(createTabTextView(TOTAL_LABEL, false));
                tabLayout.addTab(newTotal, 0, false);
            }

            refreshAllTabStyles();

        } catch (Throwable t) {
            Log.e(TAG, "reconcileTabs failed", t);
        }
    }

    private void addInstallerTab(
            @NonNull InstallerEntry entry,
            int desiredPosition) {

        try {
            if (findTabByKey(entry.key) != null) return;

            TabLayout.Tab tab = tabLayout.newTab();
            tab.setTag(entry.key);
            tab.setText(entry.label);
            tab.setCustomView(createTabTextView(entry.label, false));

            int position = Math.max(1, desiredPosition);
            position = Math.min(position, tabLayout.getTabCount());

            tabLayout.addTab(tab, position, false);

        } catch (Throwable t) {
            Log.e(TAG, "addInstallerTab failed", t);
        }
    }

    private void updateTab(
            @NonNull TabLayout.Tab tab,
            @NonNull InstallerEntry entry) {

        try {
            tab.setText(entry.label);

            View customView = tab.getCustomView();
            if (customView instanceof TextView) {
                ((TextView) customView).setText(entry.label);
            }
        } catch (Throwable t) {
            Log.e(TAG, "updateTab failed", t);
        }
    }

    private void updateSelection() {

        try {
            TabLayout.Tab desiredTab = findTabByKey(selectedKey);

            if (desiredTab == null) {
                selectedKey = TOTAL_KEY;
                selectedLabel = TOTAL_LABEL;
                desiredTab = findTabByKey(TOTAL_KEY);
                saveSelectedState();
                materialDistributionBar.setSelectedInstaller(TOTAL_KEY);
            }

            if (desiredTab == null) return;

            internalSelection = true;
            try {
                if (tabLayout.getSelectedTabPosition() != desiredTab.getPosition()) {
                    tabLayout.selectTab(desiredTab, false);
                }
            } finally {
                internalSelection = false;
            }

            String actualLabel = getTabLabel(desiredTab);
            if (actualLabel != null && !actualLabel.isEmpty()) {
                selectedLabel = actualLabel;
            }

            saveSelectedState();
            refreshAllTabStyles();

        } catch (Throwable t) {
            Log.e(TAG, "updateSelection failed", t);
            internalSelection = false;
        }
    }

    private void updateCount() {

        try {
            int count = getCountForSelectedTab();
            appCountAnimator.animateTo(String.valueOf(count));
        } catch (Throwable t) {
            Log.e(TAG, "updateCount failed", t);
        }
    }

    private int getCountForSelectedTab() {

        if (TOTAL_KEY.equals(selectedKey)) return totalUserApps;

        InstallerEntry entry = findEntry(selectedKey);
        if (entry == null) return 0;

        return Math.max(0, entry.count);
    }

    // ============================================================
    // Scroll restore — runs AFTER layout, and BEFORE selection
    // ============================================================

    /**
     * Schedules a scroll-to-saved-position, followed by the selection
     * update, in that exact order.
     *
     *   1. Wait for the layout pass via OnGlobalLayoutListener, so the
     *      tab strip's width reflects the newly-added tabs. A plain
     *      view.post(...) fires BEFORE layout and would read stale
     *      widths, clamping the scroll target to zero.
     *
     *   2. Scroll first, select second. By the time selectTab() runs,
     *      the desired tab is already on screen, so TabLayout's
     *      internal scrollToSelectedTab() finds nothing to animate.
     */
    private void scheduleScrollThenSelect() {

        try {
            // Force a layout pass so the listener fires promptly.
            try { tabLayout.requestLayout(); } catch (Throwable ignored) {}

            ViewTreeObserver observer = tabLayout.getViewTreeObserver();
            if (observer == null || !observer.isAlive()) {
                postScrollThenSelectFallback(0);
                return;
            }

            observer.addOnGlobalLayoutListener(
                    new ViewTreeObserver.OnGlobalLayoutListener() {

                        @Override
                        public void onGlobalLayout() {

                            try {
                                ViewTreeObserver v =
                                        tabLayout.getViewTreeObserver();
                                if (v != null && v.isAlive()) {
                                    v.removeOnGlobalLayoutListener(this);
                                }
                            } catch (Throwable ignored) {}

                            doScrollThenSelect();
                        }
                    });

        } catch (Throwable t) {
            Log.e(TAG, "scheduleScrollThenSelect failed", t);
            postScrollThenSelectFallback(0);
        }
    }

    /**
     * Fallback for when the ViewTreeObserver is unavailable.
     * Double-posts so the runnable lands after a layout pass.
     */
    private void postScrollThenSelectFallback(final int attempt) {

        try {
            tabLayout.post(new Runnable() {
                @Override public void run() {
                    if (destroyed) return;

                    if (attempt < 1) {
                        postScrollThenSelectFallback(attempt + 1);
                    } else {
                        doScrollThenSelect();
                    }
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "postScrollThenSelectFallback failed", t);
            try { updateSelection(); updateCount(); }
            catch (Throwable ignored) {}
        }
    }

    /**
     * The actual work: scroll to the saved position, then update the
     * selection. Safe to call multiple times; if the scroll target is
     * already satisfied it is a no-op.
     */
    private void doScrollThenSelect() {

        if (destroyed) return;

        // ---- 1. Scroll to the saved position ----
        try {
            View content = tabLayout.getChildAt(0);

            if (content != null) {
                int viewportWidth = tabLayout.getWidth()
                        - tabLayout.getPaddingLeft()
                        - tabLayout.getPaddingRight();

                if (viewportWidth > 0) {
                    int contentWidth = content.getWidth();

                    // Only scroll if the content is wider than the
                    // viewport. On an un-laid-out TabLayout,
                    // contentWidth is 0 -- skip in that case and rely
                    // on the selection update below.
                    if (contentWidth > viewportWidth) {
                        int maxScroll = contentWidth - viewportWidth;
                        int target = Math.min(
                                Math.max(0, savedScrollX),
                                maxScroll);

                        tabLayout.scrollTo(target, 0);
                        lastScrollX = tabLayout.getScrollX();
                    }
                }
            }

        } catch (Throwable t) {
            Log.e(TAG, "doScrollThenSelect: scroll failed", t);
        }

        // ---- 2. Now update the selection ----
        //
        // By this point the desired tab is (usually) already visible
        // at our restored scroll position, so TabLayout's internal
        // scrollToSelectedTab() finds nothing to do and does not
        // animate.
        //
        // If the desired tab is NOT visible -- because the user
        // manually scrolled the strip after selecting it -- TabLayout
        // will do its normal minimal scroll to reveal the selected
        // tab. That is the correct behaviour in that edge case:
        // showing the selected tab matters more than preserving an
        // arbitrary scroll offset.
        try { updateSelection(); }
        catch (Throwable t) { Log.e(TAG, "doScrollThenSelect: updateSelection failed", t); }

        try { updateCount(); }
        catch (Throwable t) { Log.e(TAG, "doScrollThenSelect: updateCount failed", t); }
    }

    // ============================================================
    // Tab appearance
    // ============================================================

    @NonNull
    private TextView createTabTextView(
            @NonNull String text,
            boolean selected) {

        MaterialTextView textView = new MaterialTextView(context);

        try {
            textView.setText(text);
            textView.setGravity(Gravity.CENTER);
            textView.setMaxLines(1);
            textView.setEllipsize(TextUtils.TruncateAt.END);
            textView.setIncludeFontPadding(true);

            textView.setTextAppearance(
                    context,
                    selected
                            ? R.style.TextAppearance_App_Tab_Selected
                            : R.style.TextAppearance_App_Tab
            );

            // Single typeface authority for all tab labels: the system
            // sans-serif at weight 500 (Medium). Same for both selected
            // and unselected states -- the visual distinction comes from
            // the text color applied by applyTabColor(), not from the
            // font weight. Using a heavier weight for the selected state
            // made the label wider, which re-flowed the whole strip on
            // every selection change.
            SystemFont.medium(textView);

            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, TAB_TEXT_SIZE_SP);

            applyTabColor(textView, selected);

        } catch (Throwable t) {
            Log.e(TAG, "createTabTextView failed", t);
        }

        return textView;
    }

    private void refreshAllTabStyles() {

        try {
            for (int i = 0; i < tabLayout.getTabCount(); i++) {
                TabLayout.Tab tab = tabLayout.getTabAt(i);
                if (tab == null) continue;
                refreshTabStyle(tab);
            }
        } catch (Throwable t) {
            Log.e(TAG, "refreshAllTabStyles failed", t);
        }
    }

    private void refreshTabStyle(@NonNull TabLayout.Tab tab) {

        try {
            boolean selected = tab.isSelected();

            String label = getTabLabel(tab);
            if (label == null || label.isEmpty()) {
                String key = getTabKey(tab);
                label = TOTAL_KEY.equals(key) ? TOTAL_LABEL : "Unknown";
            }

            View customView = tab.getCustomView();
            if (!(customView instanceof TextView)) {
                customView = createTabTextView(label, selected);
                tab.setCustomView(customView);
            }

            TextView textView = (TextView) customView;
            textView.setText(label);

            textView.setTextAppearance(
                    context,
                    selected
                            ? R.style.TextAppearance_App_Tab_Selected
                            : R.style.TextAppearance_App_Tab
            );

            // Same single typeface authority as createTabTextView.
            SystemFont.medium(textView);

            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, TAB_TEXT_SIZE_SP);

            applyTabColor(textView, selected);

        } catch (Throwable t) {
            Log.e(TAG, "refreshTabStyle failed", t);
        }
    }

    private void applyTabColor(
            @NonNull TextView textView,
            boolean selected) {

        boolean dark = safeIsNightMode();

        int color;
        if (selected) {
            color = safeThemeColor(
                    com.google.android.material.R.attr.colorPrimary,
                    dark ? FALLBACK_TAB_SELECTED_DARK : FALLBACK_TAB_SELECTED_LIGHT);
        } else {
            color = safeThemeColor(
                    com.google.android.material.R.attr.colorOnSurfaceVariant,
                    dark ? FALLBACK_TAB_UNSELECTED_DARK : FALLBACK_TAB_UNSELECTED_LIGHT);
        }

        try {
            textView.setTextColor(color);
        } catch (Throwable t) {
            Log.e(TAG, "setTextColor failed", t);
        }
    }

    // ============================================================
    // Haptics
    // ============================================================

    private void performSelectionHaptic() {

        try {
            if (hapticManager == null) {
                hapticManager = new HapticFeedbackManager(context);
            }
            hapticManager.performFeedback(tabLayout);
        } catch (Throwable t) {
            Log.e(TAG, "performSelectionHaptic failed", t);
        }
    }

    // ============================================================
    // Lookups
    // ============================================================

    @Nullable
    private TabLayout.Tab findTabByKey(@Nullable String key) {

        if (key == null) return null;

        try {
            for (int i = 0; i < tabLayout.getTabCount(); i++) {
                TabLayout.Tab tab = tabLayout.getTabAt(i);
                if (tab == null) continue;
                if (key.equals(getTabKey(tab))) return tab;
            }
        } catch (Throwable t) {
            Log.e(TAG, "findTabByKey failed", t);
        }

        return null;
    }

    @Nullable
    private String getTabKey(@NonNull TabLayout.Tab tab) {

        try {
            Object tag = tab.getTag();
            if (tag == null) return null;
            return String.valueOf(tag);
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    private String getTabLabel(@NonNull TabLayout.Tab tab) {

        try {
            View customView = tab.getCustomView();
            if (customView instanceof TextView) {
                CharSequence text = ((TextView) customView).getText();
                if (text != null && text.length() > 0) return text.toString();
            }

            CharSequence text = tab.getText();
            if (text != null && text.length() > 0) return text.toString();

        } catch (Throwable t) {
            Log.e(TAG, "getTabLabel failed", t);
        }

        return null;
    }

    @Nullable
    private InstallerEntry findEntry(@Nullable String key) {

        if (key == null) return null;

        for (InstallerEntry entry : entries) {
            if (key.equals(entry.key)) return entry;
        }

        return null;
    }

    // ============================================================
    // Persistence
    // ============================================================

    private void loadState() {

        try {
            SharedPreferences p = preferences;

            if (p == null) {
                selectedKey = TOTAL_KEY;
                selectedLabel = TOTAL_LABEL;
                savedScrollX = 0;
                lastScrollX = 0;
                return;
            }

            selectedKey = p.getString(KEY_SELECTED_INSTALLER, TOTAL_KEY);
            if (selectedKey == null || selectedKey.isEmpty()) {
                selectedKey = TOTAL_KEY;
            }

            selectedLabel = p.getString(KEY_SELECTED_LABEL, TOTAL_LABEL);
            if (selectedLabel == null || selectedLabel.isEmpty()) {
                selectedLabel = TOTAL_LABEL;
            }

            savedScrollX = Math.max(0, p.getInt(KEY_SCROLL_X, 0));
            lastScrollX = savedScrollX;

        } catch (ClassCastException e) {
            Log.e(TAG, "prefs have wrong type, resetting", e);
            selectedKey = TOTAL_KEY;
            selectedLabel = TOTAL_LABEL;
            savedScrollX = 0;
            lastScrollX = 0;
            try {
                if (preferences != null) {
                    preferences.edit()
                            .remove(KEY_SELECTED_INSTALLER)
                            .remove(KEY_SELECTED_LABEL)
                            .remove(KEY_SCROLL_X)
                            .apply();
                }
            } catch (Throwable ignored) {}

        } catch (Throwable t) {
            Log.e(TAG, "loadState failed", t);
            selectedKey = TOTAL_KEY;
            selectedLabel = TOTAL_LABEL;
            savedScrollX = 0;
            lastScrollX = 0;
        }
    }

    private void saveSelectedState() {

        try {
            SharedPreferences p = preferences;
            if (p == null) return;

            p.edit()
                    .putString(KEY_SELECTED_INSTALLER, selectedKey)
                    .putString(KEY_SELECTED_LABEL, selectedLabel)
                    .apply();

        } catch (Throwable t) {
            Log.e(TAG, "saveSelectedState failed", t);
        }
    }

    private void scheduleScrollSave() {

        try {
            mainHandler.removeCallbacks(saveScrollRunnable);
            boolean posted = mainHandler.postDelayed(
                    saveScrollRunnable, SCROLL_SAVE_DELAY);
            if (!posted) {
                Log.w(TAG, "scheduleScrollSave: postDelayed returned false");
            }
        } catch (Throwable t) {
            Log.e(TAG, "scheduleScrollSave failed", t);
        }
    }

    private void saveScrollPosition() {

        try {
            savedScrollX = Math.max(0, tabLayout.getScrollX());

            SharedPreferences p = preferences;
            if (p == null) return;

            p.edit()
                    .putInt(KEY_SCROLL_X, savedScrollX)
                    .apply();

        } catch (Throwable t) {
            Log.e(TAG, "saveScrollPosition failed", t);
        }
    }

    // ============================================================
    // Safe helpers
    // ============================================================

    private int safeThemeColor(int attrResId, int fallback) {
        try {
            return MaterialColors.getColor(tabLayout, attrResId, fallback);
        } catch (Throwable t) {
            return fallback;
        }
    }

    private boolean safeIsNightMode() {
        try {
            int flags = context.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return flags == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return false;
        }
    }

    // ============================================================
    // Value holder
    // ============================================================

    private static final class InstallerEntry {

        final String key;
        final String label;
        final int count;

        InstallerEntry(
                @NonNull String key,
                @NonNull String label,
                int count) {
            this.key = key;
            this.label = label;
            this.count = Math.max(0, count);
        }
    }
}