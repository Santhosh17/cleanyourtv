package com.gathin.tvhome;

import android.service.dreams.DreamService;

/**
 * Screensaver: the launcher's thin clock and date on black, over a slow ambient glow (ClockScene), with Home's
 * second and third clocks under them when world_clock is on (their cities from Launcher settings > Clock).
 * No network, no images: cheap enough for a 1 GB TV.
 */
public class ClockDream extends DreamService {

    private ClockScene scene;

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // the theme's locale, as ClockActivity
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV back to where it was
        setFullscreen(true);
        setScreenBright(false);
        setContentView(R.layout.dream_clock);
        if (getResources().getBoolean(R.bool.world_clock)) showWorldClocks();
        ClockFormat.apply(getWindow().getDecorView(), Config.load(this).clockFormat);
        scene = new ClockScene(getWindow().getDecorView());
        scene.useHomeWallpaper(this);
    }

    private void showWorldClocks() {
        findViewById(R.id.dreamWorld).setVisibility(android.view.View.VISIBLE);
        int[][] ids = {{R.id.dreamWorld2City, R.id.dreamWorld2Time, R.id.dreamWorld2Ampm},
                {R.id.dreamWorld3City, R.id.dreamWorld3Time, R.id.dreamWorld3Ampm}};
        for (int slot = 2; slot <= 3; slot++) {
            int[] id = ids[slot - 2];
            String zone = WorldClock.zone(this, slot);
            ((android.widget.TextView) findViewById(id[0])).setText(WorldClock.cityName(this, slot));
            ((android.widget.TextClock) findViewById(id[1])).setTimeZone(zone);
            ((android.widget.TextClock) findViewById(id[2])).setTimeZone(zone);
        }
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        scene.start();
    }

    @Override
    public void onDreamingStopped() {
        scene.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (scene != null) scene.stop();
        super.onDetachedFromWindow();
    }
}
