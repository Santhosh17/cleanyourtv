package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Remote-friendly settings owned by the launcher, laid out like Android TV's own Settings: grouped rows on the left
 * (icon, name, current value), and on the right the focused row's description and choices. OK or Right on a row
 * with choices moves into them; OK picks one and returns, Left or Back returns without a change.
 */
public final class LauncherSettingsActivity extends Activity {
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }

    static final String EXTRA_REORDER = "com.gathin.tvhome.extra.REORDER_FROM_SETTINGS";
    /** The weather cards' animated effects (rain, snow, clouds, stars...); on unless switched off here. */
    static final String WEATHER_ANIMATION_KEY = "weather_animation";
    private static final int[] DOCK_OPACITIES = {100, 70, 50, 30};
    private static final String[] CLOCK_FORMATS = {"system", "12", "24"};

    /** One row: either a list of choices (options, checked, pick) or an action. */
    private static final class Item {
        int icon;
        String title;
        Supplier<String> summary;
        String description = "";
        Supplier<List<String>> options;
        IntSupplier checked;
        IntConsumer pick;
        Runnable action;
        BooleanSupplier enabled = () -> true;
        View view;
    }

    private SharedPreferences choices;
    private final List<Item> items = new ArrayList<>();
    private LinearLayout list;
    private TextView paneTitle;
    private TextView paneDescription;
    private LinearLayout paneOptions;
    /** The row whose choices have focus, or null while the left list has it. */
    private Item active;
    private int columns;
    private int rowsBelowDock;
    private int dockOpacity;
    private String clockFormat;
    private boolean animations;
    private boolean advancedEffects;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_launcher_settings);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        Config config = Config.load(this);
        columns = config.columns;
        rowsBelowDock = config.rowsBelowDock;
        dockOpacity = config.dockOpacity;
        clockFormat = config.clockFormat;
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;
        list = findViewById(R.id.list);
        paneTitle = findViewById(R.id.paneTitle);
        paneDescription = findViewById(R.id.paneDescription);
        paneOptions = findViewById(R.id.paneOptions);
        // Mirror text alignment without globally opting the launcher into RTL.
        if (getResources().getBoolean(R.bool.rtl_menu_bar))
            findViewById(android.R.id.content).setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        header(R.string.settings_section_layout);
        Item first = choice(R.drawable.ic_apps, R.string.columns_label, labels("4", "5", "6", "7"),
                () -> columns - 4, i -> {
                    columns = i + 4;
                    choices.edit().putInt("columns", columns).apply();
                });
        choice(R.drawable.ic_swap_vert, R.string.rows_below_dock_label, labels("0", "1", "2", "3"),
                () -> rowsBelowDock, i -> {
                    rowsBelowDock = i;
                    choices.edit().putInt(Config.ROWS_BELOW_DOCK_KEY, i).apply();
                });
        choice(R.drawable.ic_opacity, R.string.dock_opacity_label, labels("100%", "70%", "50%", "30%"),
                () -> indexOf(DOCK_OPACITIES, dockOpacity), i -> {
                    dockOpacity = DOCK_OPACITIES[i];
                    choices.edit().putInt(Config.DOCK_OPACITY_KEY, dockOpacity).apply();
                });
        Item reorder = null;
        if (config.launcherSettingsButton) {
            reorder = action(R.drawable.ic_swap_vert, R.string.reorder, getString(R.string.reorder_summary), "", () -> {
                setResult(RESULT_OK, new Intent().putExtra(EXTRA_REORDER, true));
                finish();
            });
        }

        header(R.string.settings_section_clock);
        choice(R.drawable.ic_clock, R.string.clock_format_label, labels(getString(R.string.clock_format_system),
                        getString(R.string.clock_format_12), getString(R.string.clock_format_24)),
                () -> Math.max(0, java.util.Arrays.asList(CLOCK_FORMATS).indexOf(clockFormat)), i -> {
                    clockFormat = CLOCK_FORMATS[i];
                    choices.edit().putString(Config.CLOCK_FORMAT_KEY, clockFormat).apply();
                });
        if (getResources().getBoolean(R.bool.world_clock)) {
            cityItem(2, R.string.second_clock_label, R.string.second_clock_description, R.string.world_clock_title);
            cityItem(3, R.string.third_clock_label, R.string.third_clock_description, R.string.world_clock_title3);
        }

        if (WeatherNow.installed(this)) {
            header(R.string.settings_section_weather);
            weatherItem(1, R.string.weather_city_label, R.string.weather_city_description);
            weatherItem(2, R.string.weather2_city_label, R.string.weather2_city_description);
        }

        header(R.string.settings_section_look);
        wallpaperItem();
        choice(R.drawable.ic_motion, R.string.animations_label, labels(getString(R.string.on), getString(R.string.off)),
                () -> animations ? 0 : 1, i -> {
                    animations = i == 0;
                    if (!animations) advancedEffects = false;
                    choices.edit().putBoolean("animations", animations)
                            .putBoolean("advanced_effects", advancedEffects).apply();
                });
        if (WeatherNow.installed(this)) {
            Item weatherFx = choice(R.drawable.ic_globe, R.string.weather_animation_label,
                    labels(getString(R.string.on), getString(R.string.off)),
                    () -> choices.getBoolean(WEATHER_ANIMATION_KEY, true) ? 0 : 1,
                    i -> choices.edit().putBoolean(WEATHER_ANIMATION_KEY, i == 0).apply());
            weatherFx.description = getString(R.string.weather_animation_description);
        }
        Item advanced = choice(R.drawable.ic_motion, R.string.advanced_effects,
                labels(getString(R.string.on), getString(R.string.off)), () -> advancedEffects ? 0 : 1, null);
        advanced.description = getString(R.string.advanced_effects_warning);
        advanced.enabled = () -> animations;
        advanced.pick = i -> {
            if (i == 1) setAdvanced(false);
            else new AlertDialog.Builder(this)
                    .setTitle(R.string.advanced_effects)
                    .setMessage(R.string.advanced_effects_warning)
                    .setPositiveButton(R.string.enable, (d, w) -> setAdvanced(true))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        };

        if (config.openOnStartChoice) {
            header(R.string.settings_section_startup);
            openOnStartItem();
        }

        header(R.string.settings_section_theme);
        Item follow = action(R.drawable.ic_home_settings, R.string.follow_theme, null,
                getString(R.string.follow_theme_hint), this::followTheme);
        follow.summary = () -> getString(hasOnTvChoices() ? R.string.follow_theme_off : R.string.follow_theme_on);

        header(R.string.settings_section_system);
        action(R.drawable.ic_home_settings, R.string.home_settings, getString(R.string.home_settings_summary),
                getString(R.string.home_settings_help), () -> open(new Intent(this, HomeSettingsActivity.class)));
        action(R.drawable.ic_settings, R.string.tv_settings, getString(R.string.tv_settings_summary), "",
                () -> open(SystemSettings.intent(this, Settings.ACTION_SETTINGS)));

        refresh();
        (reorder != null ? reorder : first).view.requestFocus();
    }

    // ---- building rows ---------------------------------------------------------------------------

    private static Supplier<List<String>> labels(String... values) {
        List<String> l = java.util.Arrays.asList(values);
        return () -> l;
    }

    private void header(int text) {
        TextView h = (TextView) getLayoutInflater().inflate(R.layout.settings_header, list, false);
        h.setText(text);
        list.addView(h);
    }

    private Item add(Item item) {
        View row = getLayoutInflater().inflate(R.layout.settings_item, list, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(item.icon);
        ((TextView) row.findViewById(R.id.title)).setText(item.title);
        row.setOnFocusChangeListener((v, focused) -> {
            if (focused && active == null) showPane(item, false);
        });
        row.setOnClickListener(v -> {
            if (!item.enabled.getAsBoolean()) return;
            if (item.action != null) item.action.run();
            else showPane(item, true);
        });
        row.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN || keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
            if (item.options != null && item.enabled.getAsBoolean()) showPane(item, true);
            return true;   // never let Right wander into the pane on its own
        });
        item.view = row;
        list.addView(row);
        items.add(item);
        return item;
    }

    private Item choice(int icon, int title, Supplier<List<String>> options, IntSupplier checked, IntConsumer pick) {
        Item item = new Item();
        item.icon = icon;
        item.title = getString(title);
        item.options = options;
        item.checked = checked;
        item.pick = pick;
        item.summary = () -> {
            List<String> o = options.get();
            int c = checked.getAsInt();
            return c >= 0 && c < o.size() ? o.get(c) : "";
        };
        return add(item);
    }

    private Item action(int icon, int title, String summary, String description, Runnable action) {
        Item item = new Item();
        item.icon = icon;
        item.title = getString(title);
        item.summary = () -> summary == null ? "" : summary;
        item.description = description;
        item.action = action;
        return add(item);
    }

    /** A weather city row: shows the city, OK opens the Weather app's city search for that slot. */
    private void weatherItem(int slot, int label, int description) {
        Item weather = action(R.drawable.ic_globe, label, null, getString(description), null);
        weather.summary = () -> {
            WeatherNow w = WeatherNow.read(this, slot, true);
            return w == null ? getString(R.string.weather_city_none) : w.city;
        };
        weather.action = () -> {
            changed();   // Home asks the add-on again when it comes back
            WeatherNow.pickCity(this, slot);
        };
    }

    /** A clock slot's city row: shows the city, OK opens the picker. */
    private void cityItem(int slot, int label, int description, int pickerTitle) {
        Item city = action(R.drawable.ic_globe, label, null, getString(description), null);
        city.summary = () -> WorldClock.cityName(this, slot);
        city.action = () -> WorldClock.showPicker(this, slot, getString(pickerTitle), () -> {
            changed();
            refresh();
        });
    }

    private void wallpaperItem() {
        List<File> images = new ArrayList<>();
        File dir = Config.dir(this);
        images.addAll(WallpaperRotation.images(dir == null ? null : new File(dir, "wallpapers")));
        // A theme with a single wallpaper.jpg offers that picture too (Config maps the choice back to it).
        File single = dir == null ? null : new File(dir, Config.WALLPAPER_NAME);
        if (single != null && single.isFile()) images.add(0, single);
        List<String> labels = new ArrayList<>();
        labels.add(getString(R.string.wallpaper_theme_default));
        for (File f : images) labels.add(f.getName());
        labels.add(getString(R.string.wallpaper_none));
        choice(R.drawable.ic_picture, R.string.wallpaper_label, () -> labels, () -> {
            String current = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
            if (current == null) return 0;
            if ("none".equals(current)) return labels.size() - 1;
            for (int i = 0; i < images.size(); i++) if (images.get(i).getName().equals(current)) return i + 1;
            return 0;
        }, i -> {
            if (i == 0) choices.edit().remove(Config.WALLPAPER_CHOICE_KEY).apply();
            else if (i == labels.size() - 1) choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, "none").apply();
            else choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, images.get(i - 1).getName()).apply();
            Toast.makeText(this, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
        });
    }

    /** Off, then every app with a TV entry point by name. Saved at once; it is read on the next boot. */
    private void openOnStartItem() {
        List<String[]> apps = new ArrayList<>();
        PackageManager pm = getPackageManager();
        Intent tv = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo r : pm.queryIntentActivities(tv, 0)) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            boolean seen = false;
            for (String[] a : apps) seen |= a[1].equals(pkg);
            if (!seen) apps.add(new String[]{r.loadLabel(pm).toString(), pkg});
        }
        Collections.sort(apps, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        List<String> labels = new ArrayList<>();
        labels.add(getString(R.string.off));
        for (String[] a : apps) labels.add(a[0]);
        Item item = choice(R.drawable.ic_play_start, R.string.open_on_start_label, () -> labels, () -> {
            String current = choices.getString(OpenOnStart.KEY, "");
            for (int i = 0; i < apps.size(); i++) if (apps.get(i)[1].equals(current)) return i + 1;
            return 0;
        }, i -> {
            if (i == 0) choices.edit().remove(OpenOnStart.KEY).apply();
            else {
                OpenOnStart.skipThisBoot(this);
                choices.edit().putString(OpenOnStart.KEY, apps.get(i - 1)[1]).apply();
            }
        });
        // A picked app that has since been removed still shows by its package name, as before.
        item.summary = () -> {
            String pkg = choices.getString(OpenOnStart.KEY, "");
            if (pkg.isEmpty()) return getString(R.string.off);
            try {
                return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
            } catch (PackageManager.NameNotFoundException e) {
                return pkg;
            }
        };
    }

    // ---- the right pane --------------------------------------------------------------------------

    /** The row's title, description and choices; {@code enter} moves focus into the choices. */
    private void showPane(Item item, boolean enter) {
        paneTitle.setText(item.title);
        paneTitle.setTextColor(enter ? 0xFFE8EAED : 0xFF9AA0A6);
        String description = item.description;
        if (!item.enabled.getAsBoolean()) description = getString(R.string.advanced_needs_animations)
                + (description.isEmpty() ? "" : "\n\n" + description);
        paneDescription.setText(description);
        paneDescription.setVisibility(description.isEmpty() ? View.GONE : View.VISIBLE);
        paneOptions.removeAllViews();
        active = enter && item.options != null ? item : null;
        if (item.options == null) return;
        List<String> options = item.options.get();
        int checked = item.checked.getAsInt();
        TextView focusMe = null;
        for (int i = 0; i < options.size(); i++) {
            TextView row = (TextView) getLayoutInflater().inflate(R.layout.settings_option, paneOptions, false);
            row.setText(options.get(i));
            row.setActivated(i == checked);
            row.setFocusable(active != null);
            row.setAlpha(active != null ? 1f : 0.55f);
            final int index = i;
            row.setOnClickListener(v -> {
                if (index != item.checked.getAsInt()) {
                    item.pick.accept(index);
                    changed();
                }
                leavePane();
            });
            row.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    leavePane();
                    return true;
                }
                // The list's ends hold focus instead of jumping back to the rows on the left.
                return (keyCode == KeyEvent.KEYCODE_DPAD_UP && index == 0)
                        || (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && index == options.size() - 1)
                        || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
            });
            paneOptions.addView(row);
            if (i == checked || focusMe == null) focusMe = row;   // the current choice, else the first
        }
        if (active != null && focusMe != null) focusMe.requestFocus();
    }

    /** Back to the left list, on the row that was open, with the values brought up to date. */
    private void leavePane() {
        Item item = active;
        active = null;
        refresh();
        if (item != null) {
            item.view.requestFocus();
            showPane(item, false);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!items.isEmpty()) refresh();   // back from the Weather app's city search
    }

    @Override
    public void onBackPressed() {
        if (active != null) leavePane();
        else super.onBackPressed();
    }

    // ---- state -----------------------------------------------------------------------------------

    private void followTheme() {
        if (!hasOnTvChoices()) return;
        choices.edit().remove("columns").remove(Config.ROWS_BELOW_DOCK_KEY).remove("animations")
                .remove("advanced_effects").remove(Config.WALLPAPER_CHOICE_KEY)
                .remove(Config.DOCK_OPACITY_KEY).remove(Config.CLOCK_FORMAT_KEY).remove(OpenOnStart.KEY).commit();
        Config current = Config.load(this);
        columns = current.columns;
        rowsBelowDock = current.rowsBelowDock;
        dockOpacity = current.dockOpacity;
        clockFormat = current.clockFormat;
        animations = current.animations;
        advancedEffects = current.animations && current.advancedEffects;
        changed();
        refresh();
    }

    /**
     * A choice was saved. Home rebuilds when it next resumes because the change count moved, whether these
     * settings are left with Back or with the remote's Home button (which cancels the activity result).
     */
    private void changed() {
        setResult(RESULT_OK);
        choices.edit().putLong(Config.SETTINGS_REVISION_KEY,
                choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
    }

    private void setAdvanced(boolean enabled) {
        advancedEffects = enabled;
        choices.edit().putBoolean("advanced_effects", enabled).apply();
        changed();
        refresh();
    }

    private void refresh() {
        for (Item item : items) {
            boolean enabled = item.enabled.getAsBoolean();
            String summary = enabled ? item.summary.get() : getString(R.string.advanced_needs_animations);
            TextView s = item.view.findViewById(R.id.summary);
            s.setText(summary);
            s.setVisibility(summary.isEmpty() ? View.GONE : View.VISIBLE);
            item.view.setAlpha(enabled ? 1f : 0.5f);
        }
    }

    private boolean hasOnTvChoices() {
        return choices.contains("columns") || choices.contains(Config.ROWS_BELOW_DOCK_KEY)
                || choices.contains("animations") || choices.contains("advanced_effects")
                || choices.contains(Config.WALLPAPER_CHOICE_KEY) || choices.contains(Config.DOCK_OPACITY_KEY)
                || choices.contains(Config.CLOCK_FORMAT_KEY) || choices.contains(OpenOnStart.KEY);
    }

    private static int indexOf(int[] values, int value) {
        for (int i = 0; i < values.length; i++) if (values[i] == value) return i;
        return -1;
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
