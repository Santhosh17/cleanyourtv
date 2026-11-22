package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;

/**
 * The Weather add-on's current reading (addons/weather, HomeProvider). The launcher itself stays offline: the add-on
 * does the fetching, at most every 30 minutes, when Home asks.
 */
final class WeatherNow {
    private static final ComponentName PAGE =
            new ComponentName("io.github.sumyabazar1.weather", "io.github.sumyabazar1.weather.WeatherActivity");

    final String city;
    /** Null until the add-on has a reading for the city; high and low also with an older add-on. */
    final Integer temp, feels, high, low;
    final String sky;
    /** WMO weather code and day (false at night), for the icon. */
    final int code;
    final boolean day;
    /** The next days, tomorrow first: {weekday 1 = Monday, WMO code, high, low}. */
    final java.util.List<int[]> days = new java.util.ArrayList<>();

    private WeatherNow(Cursor c) {
        city = string(c, "city");
        temp = integer(c, "temp");
        feels = integer(c, "feels");
        high = integer(c, "high");
        low = integer(c, "low");
        sky = string(c, "sky");
        Integer codeOrNull = integer(c, "code"), dayOrNull = integer(c, "day");
        code = codeOrNull == null ? 3 : codeOrNull;
        day = dayOrNull == null || dayOrNull == 1;
        String d = string(c, "days");
        if (d != null) {
            for (String part : d.split(";")) {
                String[] p = part.split(",");
                if (p.length != 4) continue;
                try {
                    days.add(new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]),
                            Integer.parseInt(p[3])});
                } catch (NumberFormatException ignored) {
                    // a malformed day is left out
                }
            }
        }
    }

    private static String string(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        return i < 0 || c.isNull(i) ? null : c.getString(i);
    }

    private static Integer integer(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        return i < 0 || c.isNull(i) ? null : c.getInt(i);
    }

    /** A colour emoji for a WMO weather code, by day or night (the TV's emoji font draws it). */
    static String icon(int code, boolean day) {
        if (code == 0) return day ? "\u2600\uFE0F" : "\uD83C\uDF19";            // sun, crescent moon
        if (code == 1) return day ? "\uD83C\uDF24\uFE0F" : "\uD83C\uDF19";      // sun behind small cloud
        if (code == 2) return day ? "\u26C5" : "\u2601\uFE0F";                   // sun behind cloud, cloud
        if (code == 3) return "\u2601\uFE0F";                                    // cloud
        if (code == 45 || code == 48) return "\uD83C\uDF2B\uFE0F";                // fog
        if (code >= 95) return "\u26C8\uFE0F";                                    // thunder
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "\uD83C\uDF28\uFE0F";   // snow
        if (code >= 51 && code <= 57) return day ? "\uD83C\uDF26\uFE0F" : "\uD83C\uDF27\uFE0F"; // drizzle
        return "\uD83C\uDF27\uFE0F";                                             // rain, showers
    }

    /**
     * The reading for weather city {@code slot} (1, or 2 for the second weather), or null without the add-on or a
     * city. {@code cachedOnly} never lets the add-on go online. Blocks while the add-on fetches: call it off the main
     * thread unless cachedOnly.
     */
    static WeatherNow read(Context context, int slot, boolean cachedOnly) {
        Uri now = Uri.parse("content://io.github.sumyabazar1.weather.home/" + (slot == 2 ? "now2" : "now"));
        try (Cursor c = context.getContentResolver().query(now, null, cachedOnly ? "cached" : null, null, null)) {
            if (c == null || !c.moveToFirst()) return null;
            return new WeatherNow(c);
        } catch (RuntimeException e) {
            return null;   // add-on missing, disabled or failing
        }
    }

    /** Whether the add-on is installed (its reading may still be empty). */
    static boolean installed(Context context) {
        try {
            context.getPackageManager().getPackageInfo(PAGE.getPackageName(), 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** The add-on's city picker for weather city {@code slot}; for slot 2 it closes again once a city is picked. */
    static void pickCity(Context context, int slot) {
        Intent i = new Intent().setComponent(PAGE).putExtra("io.github.sumyabazar1.weather.PICK_CITY", true)
                .putExtra("io.github.sumyabazar1.weather.SLOT", slot).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(i);
        } catch (ActivityNotFoundException | SecurityException ignored) {
            // uninstalled since the settings row was built
        }
    }
}
