package com.gathin.tvhome;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;

public class AppStopperTest {
    private static AppStopper.Page appInfo(String... rows) {
        AppStopper.Page p = new AppStopper.Page();
        p.appInfo = true;
        p.rows = Arrays.asList(rows);
        return p;
    }

    private static AppStopper.Page confirm(String title, String app) {
        AppStopper.Page p = new AppStopper.Page();
        p.rows = Arrays.asList();
        p.confirmTitle = title;
        p.confirmApp = app;
        return p;
    }

    @Test public void stopsThenClearsCacheInOrder() {
        assertArrayEquals(new Object[]{AppStopper.FORCE_OK, "Force stop"},
                AppStopper.decide(AppStopper.FORCE, appInfo("Open", "Force stop", "Clear data", "Clear cache"), "YouTube"));
        assertArrayEquals(new Object[]{AppStopper.CACHE, "OK"},
                AppStopper.decide(AppStopper.FORCE_OK, confirm("Force stop", "YouTube"), "YouTube"));
        assertArrayEquals(new Object[]{AppStopper.CACHE_OK, "Clear cache"},
                AppStopper.decide(AppStopper.CACHE, appInfo("Open", "Clear data", "Clear cache"), "YouTube"));
        assertArrayEquals(new Object[]{AppStopper.DONE, "OK"},
                AppStopper.decide(AppStopper.CACHE_OK, confirm("Clear cache", "YouTube"), "YouTube"));
    }

    @Test public void neverConfirmsTheWrongPage() {
        // Clear data's confirmation, or another app's, is never answered.
        assertArrayEquals(new Object[]{AppStopper.CACHE_OK, ""},
                AppStopper.decide(AppStopper.CACHE_OK, confirm("Clear data", "YouTube"), "YouTube"));
        assertArrayEquals(new Object[]{AppStopper.FORCE_OK, ""},
                AppStopper.decide(AppStopper.FORCE_OK, confirm("Force stop", "Netflix"), "YouTube"));
        // Not yet App info for this app (still loading, or another page): wait.
        AppStopper.Page other = appInfo("Force stop");
        other.appInfo = false;
        assertArrayEquals(new Object[]{AppStopper.FORCE, ""}, AppStopper.decide(AppStopper.FORCE, other, "YouTube"));
    }

    @Test public void skipsWhatIsNotThere() {
        // Already stopped: straight to the cache.
        assertArrayEquals(new Object[]{AppStopper.CACHE_OK, "Clear cache"},
                AppStopper.decide(AppStopper.FORCE, appInfo("Open", "Clear cache"), "YouTube"));
        // Clear cache below the screen: scroll the list (AppStopper gives up after a few).
        assertArrayEquals(new Object[]{AppStopper.CACHE, AppStopper.SCROLL},
                AppStopper.decide(AppStopper.CACHE, appInfo("Open", "Clear data"), "YouTube"));
        // Force stop still listed after OK: wait for the app to stop before touching the cache.
        assertArrayEquals(new Object[]{AppStopper.CACHE, ""},
                AppStopper.decide(AppStopper.CACHE, appInfo("Open", "Force stop", "Clear cache"), "YouTube"));
    }
}
