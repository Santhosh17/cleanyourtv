package com.gathin.tvhome;

import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.List;

/**
 * Recent apps' Stop button. A launcher may not force-stop another app or clear its cache, and Hisense firmware also
 * refuses killBackgroundProcesses, so the Home-key helper does what a person would: it opens the app's App info
 * page in TV Settings and presses Force stop, OK, Clear cache, OK, then goes back to Home.
 *
 * It presses only rows titled exactly "Force stop" or "Clear cache", and OK only on a confirmation page with that
 * same title naming that same app, after checking App info is showing this package; never Clear data, Uninstall or
 * Disable. Anything unexpected for TIMEOUT_MS ends it at Home. The button names are this TV's English ones.
 */
final class AppStopper {
    private static final String SETTINGS = "com.android.tv.settings";
    private static final String FORCE_STOP = "Force stop", CLEAR_CACHE = "Clear cache", OK = "OK";
    /** Not a title: move the App info list down (Clear cache sits below the screen on a long page). */
    static final String SCROLL = "scroll";
    private static final int MAX_SCROLLS = 4;
    private static final long POLL_MS = 200, TIMEOUT_MS = 10_000;

    /** What the screen shows: App info for the package, or a confirmation page; with the row titles it lists. */
    static final class Page {
        boolean appInfo;
        List<String> rows;
        String confirmTitle, confirmApp;
    }

    /** The steps, in order, and what to do next on a page. */
    static final int FORCE = 0, FORCE_OK = 1, CACHE = 2, CACHE_OK = 3, DONE = 4;

    /** {next step, or the same to wait}, plus the title to press ("" for none). */
    static Object[] decide(int step, Page page, String label) {
        switch (step) {
            case FORCE:
                if (!page.appInfo) return new Object[]{FORCE, ""};
                // No Force stop row: the app is not running, so on to its cache.
                return page.rows.contains(FORCE_STOP) ? new Object[]{FORCE_OK, FORCE_STOP} : decide(CACHE, page, label);
            case FORCE_OK:
                if (FORCE_STOP.equals(page.confirmTitle) && label.equals(page.confirmApp)) return new Object[]{CACHE, OK};
                return new Object[]{FORCE_OK, ""};
            case CACHE:
                // Back on App info with the Force stop row gone: the app has stopped.
                if (!page.appInfo || page.rows.contains(FORCE_STOP)) return new Object[]{CACHE, ""};
                return page.rows.contains(CLEAR_CACHE) ? new Object[]{CACHE_OK, CLEAR_CACHE} : new Object[]{CACHE, SCROLL};
            case CACHE_OK:
                if (CLEAR_CACHE.equals(page.confirmTitle) && label.equals(page.confirmApp)) return new Object[]{DONE, OK};
                return new Object[]{CACHE_OK, ""};
            default:
                return new Object[]{DONE, ""};
        }
    }

    private final HomeKeyService service;
    private final String pkg, label;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final long deadline = SystemClock.uptimeMillis() + TIMEOUT_MS;
    private int step = FORCE, scrolls;
    private static AppStopper current;

    private AppStopper(HomeKeyService service, String pkg, String label) {
        this.service = service;
        this.pkg = pkg;
        this.label = label;
    }

    /** Stops {@code pkg} and clears its cache through TV Settings; false without the helper (nothing happens). */
    static boolean stop(String pkg, String label) {
        HomeKeyService service = HomeKeyService.running;
        if (service == null) return false;
        if (current != null) current.handler.removeCallbacksAndMessages(null);
        current = new AppStopper(service, pkg, label);
        service.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        current.handler.postDelayed(current::tick, POLL_MS);
        return true;
    }

    private void tick() {
        if (current != this) return;
        if (SystemClock.uptimeMillis() > deadline) {
            finish(service.getString(R.string.recent_apps_stop_failed, label));
            return;
        }
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root != null && SETTINGS.contentEquals(root.getPackageName())) {
            Object[] next = decide(step, read(root), label);
            String press = (String) next[1];
            if (press.isEmpty() || press(root, press)) step = (Integer) next[0];
        }
        if (scrolls > MAX_SCROLLS) step = DONE;   // no Clear cache row after all: stopped, nothing more to do
        if (step == DONE) {
            LastOpened.forget(service, pkg);   // stopped: off the Recent apps list (a failed Stop leaves it there)
            finish(service.getString(R.string.recent_apps_stopped, label));
            return;
        }
        handler.postDelayed(this::tick, POLL_MS);
    }

    private Page read(AccessibilityNodeInfo root) {
        Page page = new Page();
        page.rows = new java.util.ArrayList<>();
        for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByViewId("android:id/title")) {
            if (n.getText() != null) page.rows.add(n.getText().toString());
        }
        for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByViewId("android:id/summary")) {
            if (n.getText() != null && pkg.contentEquals(n.getText())) page.appInfo = true;   // App info for this app
        }
        page.confirmTitle = text(root, "guidance_title");
        page.confirmApp = text(root, "guidance_breadcrumb");
        return page;
    }

    private static String text(AccessibilityNodeInfo root, String id) {
        List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(SETTINGS + ":id/" + id);
        return found.isEmpty() || found.get(0).getText() == null ? null : found.get(0).getText().toString();
    }

    /** Clicks the row (or confirmation action) whose title is exactly {@code title}, or scrolls the list. */
    private boolean press(AccessibilityNodeInfo root, String title) {
        if (SCROLL.equals(title)) {
            scrolls++;
            return scrollDown(root);
        }
        String id = OK.equals(title) ? SETTINGS + ":id/guidedactions_item_title" : "android:id/title";
        for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByViewId(id)) {
            if (n.getText() == null || !title.contentEquals(n.getText())) continue;
            for (AccessibilityNodeInfo row = n; row != null; row = row.getParent()) {
                if (row.isClickable()) return row.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
        }
        return false;
    }

    private static boolean scrollDown(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (node.isScrollable() && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            if (scrollDown(node.getChild(i))) return true;
        }
        return false;
    }

    private void finish(String message) {
        current = null;
        service.startActivity(new Intent(service, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Toast.makeText(service, message, Toast.LENGTH_SHORT).show();
    }
}
