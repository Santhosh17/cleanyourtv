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
import android.text.TextUtils;
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
    /** The weather cards above the dock: all of them, then each one; all on unless switched off here. */
    static final String WEATHER_CARDS_KEY = "weather_cards", WEATHER_CARD1_KEY = "weather_card1",
            WEATHER_CARD2_KEY = "weather_card2";
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
        /** Why the row is greyed out while it is not enabled. */
        int disabledNote = R.string.advanced_needs_animations;
        View view;
    }

    /** A page of rows under one heading; the first page lists these, OK opens one. */
    private static final class Section {
        String title;
        int icon;
        final List<Item> items = new ArrayList<>();
        View row;
    }

    private SharedPreferences choices;
    private final List<Item> items = new ArrayList<>();
    private final List<Section> sections = new ArrayList<>();
    /** The section page on screen, or null on the first page. */
    private Section open;
    private TextView pageTitle;
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
        pageTitle = findViewById(R.id.pageTitle);
        paneTitle = findViewById(R.id.paneTitle);
        paneDescription = findViewById(R.id.paneDescription);
        paneOptions = findViewById(R.id.paneOptions);
        // Mirror text alignment without globally opting the launcher into RTL.
        if (getResources().getBoolean(R.bool.rtl_menu_bar))
            findViewById(android.R.id.content).setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        header(R.string.settings_section_layout, R.drawable.ic_apps);
        choice(R.drawable.ic_apps, R.string.columns_label, labels("4", "5", "6", "7"),
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
        if (config.launcherSettingsButton) {
            action(R.drawable.ic_swap_vert, R.string.reorder, getString(R.string.reorder_summary), "", () -> {
                setResult(RESULT_OK, new Intent().putExtra(EXTRA_REORDER, true));
                finish();
            });
        }

        header(R.string.settings_section_clock, R.drawable.ic_clock);
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

        screensaverSection();

        if (WeatherNow.installed(this)) {
            header(R.string.settings_section_weather, R.drawable.ic_weather);
            onOff(R.drawable.ic_weather, R.string.weather_cards_label, R.string.weather_cards_description, WEATHER_CARDS_KEY);
            BooleanSupplier cardsOn = () -> choices.getBoolean(WEATHER_CARDS_KEY, true);
            for (int slot = 1; slot <= 2; slot++) {
                String key = slot == 1 ? WEATHER_CARD1_KEY : WEATHER_CARD2_KEY;
                Item card = onOff(R.drawable.ic_weather, slot == 1 ? R.string.weather_card1_label : R.string.weather_card2_label,
                        slot == 1 ? R.string.weather_card1_description : R.string.weather_card2_description, key);
                needs(card, cardsOn, R.string.weather_needs_cards);
                Item city = weatherItem(slot, slot == 1 ? R.string.weather_city_label : R.string.weather2_city_label,
                        slot == 1 ? R.string.weather_city_description : R.string.weather2_city_description);
                needs(city, () -> cardsOn.getAsBoolean() && choices.getBoolean(key, true), R.string.weather_needs_card);
            }
            Item weatherFx = onOff(R.drawable.ic_motion, R.string.weather_animation_label, R.string.weather_animation_description,
                    WEATHER_ANIMATION_KEY);
            needs(weatherFx, cardsOn, R.string.weather_needs_cards);
        }

        header(R.string.settings_section_look, R.drawable.ic_picture);
        wallpaperItem();
        choice(R.drawable.ic_motion, R.string.animations_label, labels(getString(R.string.on), getString(R.string.off)),
                () -> animations ? 0 : 1, i -> {
                    animations = i == 0;
                    if (!animations) advancedEffects = false;
                    choices.edit().putBoolean("animations", animations)
                            .putBoolean("advanced_effects", advancedEffects).apply();
                });
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
            header(R.string.settings_section_startup, R.drawable.ic_play_start);
            openOnStartItem();
        }

        header(R.string.settings_section_theme, R.drawable.ic_opacity);
        Item follow = action(R.drawable.ic_home_settings, R.string.follow_theme, null,
                getString(R.string.follow_theme_hint), this::followTheme);
        follow.summary = () -> getString(hasOnTvChoices() ? R.string.follow_theme_off : R.string.follow_theme_on);

        header(R.string.settings_section_system, R.drawable.ic_settings);
        action(R.drawable.ic_home_settings, R.string.home_settings, getString(R.string.home_settings_summary),
                getString(R.string.home_settings_help), () -> open(new Intent(this, HomeSettingsActivity.class)));
        action(R.drawable.ic_settings, R.string.tv_settings, getString(R.string.tv_settings_summary), "",
                () -> open(SystemSettings.intent(this, Settings.ACTION_SETTINGS)));

        refresh();
        showTop(null);
    }

    // ---- screensaver -----------------------------------------------------------------------------

    private static final String CLOCK_DREAM = "com.gathin.tvhome/.ClockDream";
    /** "Start after" choices, in minutes: the TV's own screensaver delay (Settings.System.SCREEN_OFF_TIMEOUT). */
    private static final int[] DREAM_MINUTES = {5, 10, 15, 30, 60};

    /**
     * The TV's screensaver as the launcher's clock (ClockDream): on or off, and after how long. These are the TV's own
     * settings, written with WRITE_SECURE_SETTINGS (granted by build-and-install); without it a choice says so and
     * changes nothing.
     */
    private void screensaverSection() {
        header(R.string.settings_section_screensaver, R.drawable.ic_clock);
        Item onOff = choice(R.drawable.ic_clock, R.string.screensaver_label, labels(getString(R.string.on), getString(R.string.off)),
                () -> clockDreamOn() ? 0 : 1, i -> {
                    try {
                        if (i == 0) Settings.Secure.putString(getContentResolver(), "screensaver_components", CLOCK_DREAM);
                        Settings.Secure.putInt(getContentResolver(), "screensaver_enabled", i == 0 ? 1 : 0);
                    } catch (SecurityException e) {
                        Toast.makeText(this, R.string.screensaver_no_permission, Toast.LENGTH_LONG).show();
                    }
                    refresh();
                });
        onOff.description = getString(R.string.screensaver_description);
        List<String> after = new ArrayList<>();
        for (int m : DREAM_MINUTES) after.add(m < 60 ? getString(R.string.minutes_short, m) : getString(R.string.one_hour));
        Item delay = choice(R.drawable.ic_motion, R.string.screensaver_after_label, () -> after, () -> {
            int ms = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, 600_000);
            for (int i = 0; i < DREAM_MINUTES.length; i++) if (DREAM_MINUTES[i] * 60_000 == ms) return i;
            return -1;   // a time set elsewhere: no choice ticked until one is picked here
        }, i -> {
            try {
                Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, DREAM_MINUTES[i] * 60_000);
            } catch (SecurityException e) {
                Toast.makeText(this, R.string.screensaver_no_permission, Toast.LENGTH_LONG).show();
            }
        });
        delay.description = getString(R.string.screensaver_after_description);
        needs(delay, this::clockDreamOn, R.string.screensaver_needs_on);
        // The TV's sleep time (Settings.Secure sleep_timeout), counted from the last key press like Start after.
        List<String> hours = new ArrayList<>();
        for (int h = 1; h <= 24; h++) hours.add(getResources().getQuantityString(R.plurals.hours, h, h));
        Item off = choice(R.drawable.ic_settings, R.string.screensaver_off_label, () -> hours, () -> {
            long ms = Settings.Secure.getLong(getContentResolver(), "sleep_timeout", -1);
            return ms > 0 && ms % 3_600_000L == 0 && ms <= 24 * 3_600_000L ? (int) (ms / 3_600_000L) - 1 : -1;
        }, i -> {
            try {
                Settings.Secure.putLong(getContentResolver(), "sleep_timeout", (i + 1) * 3_600_000L);
            } catch (SecurityException e) {
                Toast.makeText(this, R.string.screensaver_no_permission, Toast.LENGTH_LONG).show();
            }
        });
        off.description = getString(R.string.screensaver_off_description);
        needs(off, this::clockDreamOn, R.string.screensaver_needs_on);
        Item preview = action(R.drawable.ic_play_start, R.string.screensaver_preview_label, null,
                getString(R.string.screensaver_preview_description), () -> {
                    try {
                        startActivity(new Intent().setClassName("com.android.systemui", "com.android.systemui.Somnambulator"));
                    } catch (ActivityNotFoundException | SecurityException e) {
                        Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                    }
                });
        needs(preview, this::clockDreamOn, R.string.screensaver_needs_on);
    }

    /** The TV's screensaver is on and is this launcher's clock. */
    private boolean clockDreamOn() {
        String dream = Settings.Secure.getString(getContentResolver(), "screensaver_components");
        return Settings.Secure.getInt(getContentResolver(), "screensaver_enabled", 0) == 1
                && dream != null && (dream.contains(CLOCK_DREAM) || dream.contains("com.gathin.tvhome/com.gathin.tvhome.ClockDream"));
    }

    // ---- pages -----------------------------------------------------------------------------------

    /** The first page: one row per section, focus on {@code focus} (the section just left) or the first. */
    private void showTop(Section focus) {
        open = null;
        active = null;
        pageTitle.setText(R.string.launcher_settings);
        list.removeViews(1, list.getChildCount() - 1);
        for (Section s : sections) {
            if (s.items.isEmpty()) continue;
            if (s.row == null) {
                View row = getLayoutInflater().inflate(R.layout.settings_item, list, false);
                ((ImageView) row.findViewById(R.id.icon)).setImageResource(s.icon);
                ((TextView) row.findViewById(R.id.title)).setText(s.title);
                List<String> titles = new ArrayList<>();
                for (Item item : s.items) titles.add(item.title);
                ((TextView) row.findViewById(R.id.summary)).setText(TextUtils.join(" · ", titles));
                row.setOnFocusChangeListener((v, focused) -> {
                    if (focused) showSectionPane(s);
                });
                row.setOnClickListener(v -> showSection(s));
                row.setOnKeyListener((v, keyCode, event) -> {
                    if (event.getAction() != KeyEvent.ACTION_DOWN || keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                    showSection(s);
                    return true;
                });
                s.row = row;
            }
            list.addView(s.row);
        }
        View first = list.getChildCount() > 1 ? list.getChildAt(1) : null;
        View target = focus != null && focus.row != null ? focus.row : first;
        if (target != null) target.requestFocus();
    }

    /** One section's rows, the page titled with its name. */
    private void showSection(Section s) {
        open = s;
        active = null;
        pageTitle.setText(s.title);
        list.removeViews(1, list.getChildCount() - 1);
        for (Item item : s.items) list.addView(item.view);
        refresh();
        findViewById(R.id.listScroll).scrollTo(0, 0);
        for (Item item : s.items) {
            if (item.enabled.getAsBoolean()) {
                item.view.requestFocus();
                return;
            }
        }
        s.items.get(0).view.requestFocus();
    }

    /** On the first page, the right pane lists what a section holds and how each is set now. */
    private void showSectionPane(Section s) {
        paneTitle.setText(s.title);
        paneTitle.setTextColor(0xFF9AA0A6);
        StringBuilder text = new StringBuilder();
        for (Item item : s.items) {
            String value = item.enabled.getAsBoolean() ? item.summary.get() : getString(item.disabledNote);
            if (text.length() > 0) text.append('\n');
            text.append(item.title).append(value.isEmpty() ? "" : ": " + value);
        }
        paneDescription.setText(text);
        paneDescription.setVisibility(View.VISIBLE);
        paneOptions.removeAllViews();
    }

    // ---- building rows ---------------------------------------------------------------------------

    private static Supplier<List<String>> labels(String... values) {
        List<String> l = java.util.Arrays.asList(values);
        return () -> l;
    }

    /** Starts a section: the rows added after this belong to it. */
    private void header(int text, int icon) {
        Section s = new Section();
        s.title = getString(text);
        s.icon = icon;
        sections.add(s);
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
        sections.get(sections.size() - 1).items.add(item);
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

    /** An On/Off row for a boolean choice that is on unless switched off. */
    private Item onOff(int icon, int title, int description, String key) {
        Item item = choice(icon, title, labels(getString(R.string.on), getString(R.string.off)),
                () -> choices.getBoolean(key, true) ? 0 : 1, i -> {
                    choices.edit().putBoolean(key, i == 0).apply();
                    refresh();   // rows that depend on this one grey out or come back
                });
        item.description = getString(description);
        return item;
    }

    /** Greys {@code item} out, with {@code note} as the reason, while {@code enabled} is false. */
    private static void needs(Item item, BooleanSupplier enabled, int note) {
        item.enabled = enabled;
        item.disabledNote = note;
    }

    /** A weather city row: shows the city, OK opens the Weather app's city search for that slot. */
    private Item weatherItem(int slot, int label, int description) {
        Item weather = action(R.drawable.ic_globe, label, null, getString(description), null);
        weather.summary = () -> {
            WeatherNow w = WeatherNow.read(this, slot, true);
            return w == null ? getString(R.string.weather_city_none) : w.city;
        };
        weather.action = () -> {
            changed();   // Home asks the add-on again when it comes back
            WeatherNow.pickCity(this, slot);
        };
        return weather;
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
        if (!item.enabled.getAsBoolean()) description = getString(item.disabledNote)
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
        else if (open != null) showTop(open);   // a section page: back to the list of sections
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
            String summary = enabled ? item.summary.get() : getString(item.disabledNote);
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
