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
    /** The temperature in °C and °F, each rounded from the exact reading; null with an older add-on. */
    final Integer tempC, tempF;
    /** The UV index now, null when unknown. */
    final Integer uv;
    /** Humidity %, wind km/h, the direction the wind comes from (degrees) and feels-like °C; each null when unknown. */
    final Integer humidity, wind, windDir, feelsC;
    final String sky;
    /** WMO weather code and day (false at night), for the icon. */
    final int code;
    final boolean day;
    /** US AQI, null when unknown; today's sunrise and sunset, local "HH:mm" or null; today's WMO code, -1 unknown. */
    final Integer aqi;
    final String sunrise, sunset;
    final int todayCode;
    /** Today's rain chance (%, -1 unknown) and amount (mm); the next 2 hours in 15-minute mm; reading time (ms, 0 unknown). */
    final int rainChance;
    final double rainMm;
    final double[] next2h;
    final long updated;
    /** The next days, tomorrow first: {weekday 1 = Monday, WMO code, high, low, rain chance % or -1}. */
    final java.util.List<int[]> days = new java.util.ArrayList<>();

    private WeatherNow(Cursor c) {
        city = string(c, "city");
        temp = integer(c, "temp");
        tempC = integer(c, "tempc");
        tempF = integer(c, "tempf");
        Integer uvOrNull = integer(c, "uv");
        uv = uvOrNull == null || uvOrNull < 0 ? null : uvOrNull;
        humidity = known(integer(c, "humidity"));
        wind = known(integer(c, "wind"));
        windDir = known(integer(c, "winddir"));
        feelsC = integer(c, "feelsc");
        feels = integer(c, "feels");
        high = integer(c, "high");
        low = integer(c, "low");
        sky = string(c, "sky");
        Integer aqiOrNull = integer(c, "aqi"), today = integer(c, "todaycode");
        aqi = aqiOrNull == null || aqiOrNull < 0 ? null : aqiOrNull;
        sunrise = empty(string(c, "sunrise"));
        sunset = empty(string(c, "sunset"));
        todayCode = today == null ? -1 : today;
        Integer codeOrNull = integer(c, "code"), dayOrNull = integer(c, "day");
        code = codeOrNull == null ? 3 : codeOrNull;
        day = dayOrNull == null || dayOrNull == 1;
        Integer chance = integer(c, "rainchance");
        rainChance = chance == null ? -1 : chance;
        int mm = c.getColumnIndex("rainmm");
        rainMm = mm < 0 || c.isNull(mm) ? 0 : c.getDouble(mm);
        int at = c.getColumnIndex("updated");
        updated = at < 0 || c.isNull(at) ? 0 : c.getLong(at);
        String q = empty(string(c, "next2h"));
        String[] qs = q == null ? new String[0] : q.split(",");
        next2h = new double[qs.length];
        for (int i = 0; i < qs.length; i++) {
            try {
                next2h[i] = Double.parseDouble(qs[i]);
            } catch (NumberFormatException ignored) {
                // a malformed slot counts as dry
            }
        }
        String d = string(c, "days");
        if (d != null) {
            for (String part : d.split(";")) {
                String[] p = part.split(",");
                if (p.length < 4) continue;
                try {
                    days.add(new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]),
                            Integer.parseInt(p[3]), p.length > 4 ? Integer.parseInt(p[4]) : -1});
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

    private static Integer known(Integer i) {
        return i == null || i < 0 ? null : i;
    }

    /** "NW" for a wind from 315°: eight compass points. */
    static String compass(int degrees) {
        return new String[]{"N", "NE", "E", "SE", "S", "SW", "W", "NW"}[Math.round(((degrees % 360) + 360) % 360 / 45f) % 8];
    }

    /**
     * What to wear, from feels-like °C, then ☂️ when rain is likely and 🧴 for a strong sun:
     * "🧣 Coat" (5° and below), "🧥 Jacket" (to 12°), "🧥 Light jacket" (to 18°), "👕 T-shirt" (to 27°), "🩳 Shorts".
     */
    static String wear(int feelsC, boolean rainy, boolean strongSun) {
        String s = feelsC <= 5 ? "\uD83E\uDDE3 Coat" : feelsC <= 12 ? "\uD83E\uDDE5 Jacket"
                : feelsC <= 18 ? "\uD83E\uDDE5 Light jacket" : feelsC <= 27 ? "\uD83D\uDC55 T-shirt" : "\uD83E\uDE73 Shorts";
        if (rainy) s += " \u2602\uFE0F";
        if (strongSun) s += " \uD83E\uDDF4";
        return s;
    }

    /** The card's advice: rain at 50% today or on the 2-hour timeline, sunscreen at UV 6 and up by day. Null unknown. */
    String wear() {
        if (feelsC == null) return null;
        boolean rainy = rainChance >= 50;
        for (double mm : next2h) rainy |= wet(mm);
        return wear(feelsC, rainy, day && uv != null && uv >= 6);
    }

    private static String empty(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static boolean rain(int code) {
        return (code >= 51 && code <= 67) || (code >= 80 && code <= 82);
    }

    /** "☔ Rain today", "⛈ Storms tomorrow"..., or null: storms first, then rain, today before tomorrow. */
    String warning() {
        int tomorrow = days.isEmpty() ? -1 : days.get(0)[1];
        if (todayCode >= 95) return "\u26C8\uFE0F Storms today";
        if (rain(todayCode)) return "\u2614 Rain today";
        if (tomorrow >= 95) return "\u26C8\uFE0F Storms tomorrow";
        if (rain(tomorrow)) return "\u2614 Rain tomorrow";
        return null;
    }

    /** 0.1 mm in 15 minutes: the least that counts as rain on the timeline. */
    static boolean wet(double mm) {
        return mm >= 0.1;
    }

    /**
     * The next 2 hours in words from the 15-minute amounts: "Rain in 30 min", "Rain stops in 45 min", "Rain for the
     * next 2 h"; when dry, the today/tomorrow warning, else "No rain next 2 h". Null without a timeline.
     */
    String timeline() {
        if (next2h.length == 0) return warning();
        int change = 1;
        while (change < next2h.length && wet(next2h[change]) == wet(next2h[0])) change++;
        if (wet(next2h[0])) return change == next2h.length ? "\u2614 Rain for the next 2 h"
                : "\u2614 Rain stops in " + change * 15 + " min";
        if (change < next2h.length) return "\u2614 Rain in " + change * 15 + " min";
        String w = warning();
        return w != null ? w : "No rain next 2 h";
    }

    /** WHO UV band 0-4: low (0-2), moderate (3-5), high (6-7), very high (8-10), extreme (11+). */
    static int uvBand(int uv) {
        return uv <= 2 ? 0 : uv <= 5 ? 1 : uv <= 7 ? 2 : uv <= 10 ? 3 : 4;
    }

    /** US AQI band 0-5: good, moderate, unhealthy for sensitive groups, unhealthy, very unhealthy, hazardous. */
    static int aqiBand(int aqi) {
        int[] max = {50, 100, 150, 200, 300};
        int band = 0;
        while (band < max.length && aqi > max[band]) band++;
        return band;
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

    /**
     * The two-letter country of the TV's public IP from the add-on (it looks it up), or "" when unknown.
     * {@code fresh} makes it look up again now instead of answering from its 5-minute memory.
     */
    static String country(Context context, boolean fresh) {
        try (Cursor c = context.getContentResolver().query(Uri.parse("content://io.github.sumyabazar1.weather.home/country"),
                null, fresh ? "fresh" : null, null, null)) {
            return c != null && c.moveToFirst() && !c.isNull(0) ? c.getString(0) : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** "DE" as its flag emoji (two regional indicator letters), "" for anything else. */
    static String flag(String code) {
        if (code == null || !code.matches("[A-Z]{2}")) return "";
        return new String(Character.toChars(0x1F1E6 + code.charAt(0) - 'A'))
                + new String(Character.toChars(0x1F1E6 + code.charAt(1) - 'A'));
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
