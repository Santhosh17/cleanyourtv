package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextClock;
import android.widget.TextView;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The second clock beside the main one (world_clock): a city's time, picked on the TV. Offline on purpose: the time
 * is the TV's own clock shifted by Android's timezone database, so the launcher keeps no network access.
 */
final class WorldClock {
    private static final String PREFS = "world_clock";
    /** The extra clocks' size against the main clock: three clocks and the pills share one row. */
    private static final float SIZE = 0.75f;
    private static final String[] REGIONS = {"Africa/", "America/", "Antarctica/", "Asia/", "Atlantic/",
            "Australia/", "Europe/", "Indian/", "Pacific/"};
    /** Big cities that are not timezone names themselves: the tz database names one city per zone. */
    private static final Map<String, String> ALIASES = new HashMap<>();
    static {
        for (String c : new String[]{"Mumbai", "Delhi", "New Delhi", "Bengaluru", "Bangalore", "Chennai",
                "Hyderabad", "Pune", "Mysuru", "Mysore", "Ahmedabad"}) ALIASES.put(c, "Asia/Kolkata");
        for (String c : new String[]{"Beijing", "Shenzhen", "Guangzhou"}) ALIASES.put(c, "Asia/Shanghai");
        for (String c : new String[]{"San Francisco", "Seattle", "San Diego", "Las Vegas"}) ALIASES.put(c, "America/Los_Angeles");
        for (String c : new String[]{"Washington", "Boston", "Miami", "Atlanta"}) ALIASES.put(c, "America/New_York");
        for (String c : new String[]{"Dallas", "Houston"}) ALIASES.put(c, "America/Chicago");
        for (String c : new String[]{"Munich", "Frankfurt", "Hamburg", "Cologne"}) ALIASES.put(c, "Europe/Berlin");
        for (String c : new String[]{"Osaka", "Kyoto"}) ALIASES.put(c, "Asia/Tokyo");
        ALIASES.put("Abu Dhabi", "Asia/Dubai");
        ALIASES.put("Canberra", "Australia/Sydney");
    }

    /** One pickable city: its display name and zone id. */
    static final class City {
        final String name;
        final String zone;
        City(String name, String zone) { this.name = name; this.zone = zone; }
    }

    /** Every city by name, from the region zones plus the aliases. */
    static List<City> allCities() {
        TreeMap<String, City> byName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String zone : ZoneId.getAvailableZoneIds()) {
            for (String region : REGIONS) {
                if (!zone.startsWith(region)) continue;
                String name = zone.substring(zone.lastIndexOf('/') + 1).replace('_', ' ');
                byName.putIfAbsent(name, new City(name, zone));
            }
        }
        for (Map.Entry<String, String> e : ALIASES.entrySet()) byName.put(e.getKey(), new City(e.getKey(), e.getValue()));
        return new ArrayList<>(byName.values());
    }

    /** Cities whose name starts with the query first, then those that contain it; at most {@code limit}. */
    static List<City> search(List<City> cities, String query, int limit) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<City> starts = new ArrayList<>(), contains = new ArrayList<>();
        for (City c : cities) {
            String n = c.name.toLowerCase(Locale.ROOT);
            if (n.startsWith(q)) starts.add(c);
            else if (!q.isEmpty() && n.contains(q)) contains.add(c);
        }
        starts.addAll(contains);
        return starts.size() > limit ? starts.subList(0, limit) : starts;
    }

    /** "GMT+5:30" for a zone right now. */
    static String offsetLabel(String zone) {
        ZoneOffset off = ZoneId.of(zone).getRules().getOffset(Instant.now());
        int total = off.getTotalSeconds() / 60;
        if (total == 0) return "GMT";
        int h = Math.abs(total) / 60, m = Math.abs(total) % 60;
        return "GMT" + (total < 0 ? "-" : "+") + h + (m == 0 ? "" : String.format(Locale.ROOT, ":%02d", m));
    }

    private final TextView city;
    private final TextClock time;
    private final TextClock ampm;

    /** Preference key for a clock slot: slot 2 keeps the original keys, slot 3 adds its number. */
    private static String key(String base, int slot) {
        return slot == 2 ? base : base + slot;
    }

    /** A slot's saved timezone, "UTC" until a city is picked. */
    static String zone(android.content.Context context, int slot) {
        return context.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).getString(key("zone", slot), "UTC");
    }

    /** A slot's saved city name, "UTC" until one is picked. */
    static String cityName(android.content.Context context, int slot) {
        return context.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).getString(key("name", slot), "UTC");
    }

    /**
     * Display only: shows the block, sizes it to 75% of the main clock and applies the saved city. The city is picked
     * in Launcher settings ({@link #showPicker}); Home rebuilds after a settings change and reads it again.
     */
    WorldClock(Activity activity, View block, int slot, TextClock mainClock, TextClock mainAmpm) {
        city = block.findViewById(R.id.worldCity);
        time = block.findViewById(R.id.worldTime);
        ampm = block.findViewById(R.id.worldAmpm);
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
        block.setVisibility(View.VISIBLE);
        // After the first layout, so any size the theme gave the main clock is already applied.
        block.post(() -> {
            time.setTextSize(TypedValue.COMPLEX_UNIT_PX, mainClock.getTextSize() * SIZE);
            ampm.setTextSize(TypedValue.COMPLEX_UNIT_PX, mainAmpm.getTextSize() * SIZE);
        });
        // The block (city line + time) is taller than the main clock. Let it reach up past the row's top instead of
        // making the row taller, so the date line stays right under the main clock: a negative top margin of the
        // difference, with the row no longer clipping it.
        ViewGroup row = (ViewGroup) block.getParent();
        row.setClipChildren(false);
        ((ViewGroup) row.getParent()).setClipChildren(false);   // the row's parent clips the row to its bounds too
        block.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int want = Math.min(0, mainClock.getHeight() - v.getHeight());
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
            if (lp.topMargin != want) {
                lp.topMargin = want;
                v.post(() -> v.setLayoutParams(lp));
            }
        });
        String zone = prefs.getString(key("zone", slot), "UTC");
        city.setText(prefs.getString(key("name", slot), "UTC"));
        time.setTimeZone(zone);
        ampm.setTimeZone(zone);
    }

    /** The city picker: type, Down or the keyboard's search key to the results, OK saves and calls {@code picked}. */
    static void showPicker(Activity activity, int slot, String title, Runnable picked) {
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
        float density = activity.getResources().getDisplayMetrics().density;
        View panel = activity.getLayoutInflater().inflate(R.layout.dialog_world_clock, null);
        ((TextView) panel.findViewById(R.id.pickerTitle)).setText(title);
        EditText input = panel.findViewById(R.id.cityInput);
        ListView list = panel.findViewById(R.id.cityList);
        List<City> cities = allCities();
        List<City> shown = new ArrayList<>();
        ArrayAdapter<City> adapter = new ArrayAdapter<City>(activity, R.layout.row_world_city, shown) {
            @Override public View getView(int position, View convert, ViewGroup parent) {
                TextView row = (TextView) super.getView(position, convert, parent);
                City c = getItem(position);
                String region = c.zone.substring(0, c.zone.indexOf('/'));
                row.setText(c.name + "   ·   " + region + "   ·   " + offsetLabel(c.zone));
                return row;
            }
        };
        list.setAdapter(adapter);
        Runnable refresh = () -> {
            shown.clear();
            shown.addAll(search(cities, input.getText().toString(), 40));
            adapter.notifyDataSetChanged();
        };
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { refresh.run(); }
        });
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(panel);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int) (560 * density), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.6f);
        }
        list.setOnItemClickListener((parent, view, position, id) -> {
            City c = shown.get(position);
            prefs.edit().putString(key("name", slot), c.name).putString(key("zone", slot), c.zone).apply();
            dialog.dismiss();
            picked.run();
        });
        // The keyboard's Done/Search key moves to the results, where OK picks one.
        input.setOnEditorActionListener((v, action, event) -> {
            boolean go = action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER);
            if (go && !shown.isEmpty()) list.requestFocus();
            return go;
        });
        refresh.run();
        dialog.show();
        input.requestFocus();
    }
}
