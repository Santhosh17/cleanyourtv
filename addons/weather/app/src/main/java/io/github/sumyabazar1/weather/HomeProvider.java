package io.github.sumyabazar1.weather;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/**
 * The current reading for a home screen to show (content://io.github.sumyabazar1.weather.home/now, and /now2 for the
 * launcher's second weather city): one row of city, temperature, feels-like, unit, sky words, the WMO code and day
 * flag (for an icon), today's high and low, the next five days as "weekday,code,high,low;..." (weekday 1 =
 * Monday), the US AQI (-1 unknown), today's sunrise and sunset ("HH:mm", local to the place) and today's WMO code -
 * or no row before a city is picked. Read-only.
 *
 * It fetches only when asked and at most every 30 minutes (Forecast.REFRESH_MS), from the asking app's background
 * thread, so the add-on still runs no service. The query "cached" never goes online (for a settings summary).
 */
public final class HomeProvider extends ContentProvider {
    private static final String[] COLUMNS = {"city", "temp", "feels", "unit", "sky", "code", "day", "high", "low", "days", "aqi", "sunrise", "sunset", "todaycode"};
    /** Bumped when the cached fields change, so an older cache is fetched again. */
    private static final int CACHE_VERSION = 4;

    @Override public boolean onCreate() {
        return true;
    }

    @Override
    public synchronized Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        MatrixCursor out = new MatrixCursor(COLUMNS);
        boolean second = "now2".equals(uri.getLastPathSegment());
        SharedPreferences place = getContext().getSharedPreferences(second ? "place2" : "place", 0);
        if (!place.contains("name")) return out;
        String city = place.getString("name", "");
        double lat = Double.longBitsToDouble(place.getLong("lat", 0));
        double lon = Double.longBitsToDouble(place.getLong("lon", 0));
        String key = lat + "," + lon;
        SharedPreferences cache = getContext().getSharedPreferences(second ? "home2" : "home", 0);
        boolean fresh = key.equals(cache.getString("for", "")) && cache.getInt("v", 0) == CACHE_VERSION
                && System.currentTimeMillis() - cache.getLong("at", 0) < Forecast.REFRESH_MS;
        if (!fresh && !"cached".equals(selection)) {
            try {
                Forecast.Report r = Forecast.fetch(lat, lon, Forecast.forecastDays(5));
                StringBuilder days = new StringBuilder();
                for (int i = 0; i < Math.min(5, r.days.size()); i++) {
                    Forecast.Day d = r.days.get(i);
                    if (days.length() > 0) days.append(';');
                    days.append(d.date.getDayOfWeek().getValue()).append(',').append(d.code).append(',')
                            .append(Double.doubleToRawLongBits(d.maxC)).append(',')
                            .append(Double.doubleToRawLongBits(d.minC));
                }
                cache.edit().putString("for", key).putInt("v", CACHE_VERSION).putLong("at", System.currentTimeMillis())
                        .putLong("high", Double.doubleToRawLongBits(r.todayMaxC))
                        .putLong("low", Double.doubleToRawLongBits(r.todayMinC))
                        .putString("days", days.toString())
                        .putInt("aqi", r.aqi).putString("sunrise", r.sunrise).putString("sunset", r.sunset)
                        .putInt("todaycode", r.todayCode)
                        .putLong("temp", Double.doubleToRawLongBits(r.tempC))
                        .putLong("feels", Double.doubleToRawLongBits(r.feelsC))
                        .putInt("code", r.code).putBoolean("day", r.day).commit();
            } catch (Exception e) {
                // Offline: fall back to the last reading for this city below, however old.
            }
        }
        if (!key.equals(cache.getString("for", ""))) {
            out.addRow(new Object[]{city, null, null, null, null, null, null, null, null, null, null, null, null, null});
            return out;
        }
        boolean f = getContext().getSharedPreferences("options", 0).getBoolean("fahrenheit",
                "f".equalsIgnoreCase(getContext().getString(R.string.temperature_unit).trim()));
        double temp = Double.longBitsToDouble(cache.getLong("temp", 0));
        double feels = Double.longBitsToDouble(cache.getLong("feels", 0));
        int code = cache.getInt("code", 3);
        boolean day = cache.getBoolean("day", true);
        double high = Double.longBitsToDouble(cache.getLong("high", Double.doubleToRawLongBits(Double.NaN)));
        double low = Double.longBitsToDouble(cache.getLong("low", Double.doubleToRawLongBits(Double.NaN)));
        StringBuilder days = new StringBuilder();
        for (String d : cache.getString("days", "").split(";")) {
            String[] p = d.split(",");
            if (p.length != 4) continue;
            if (days.length() > 0) days.append(';');
            days.append(p[0]).append(',').append(p[1]).append(',')
                    .append(Forecast.degrees(Double.longBitsToDouble(Long.parseLong(p[2])), f)).append(',')
                    .append(Forecast.degrees(Double.longBitsToDouble(Long.parseLong(p[3])), f));
        }
        out.addRow(new Object[]{city, Forecast.degrees(temp, f), Forecast.degrees(feels, f), f ? "F" : "C",
                getContext().getString(Forecast.sky(code, day)), code, day ? 1 : 0,
                Double.isNaN(high) ? null : Forecast.degrees(high, f), Double.isNaN(low) ? null : Forecast.degrees(low, f),
                days.toString(), cache.getInt("aqi", -1), cache.getString("sunrise", ""),
                cache.getString("sunset", ""), cache.getInt("todaycode", -1)});
        return out;
    }

    @Override public String getType(Uri uri) {
        return "vnd.android.cursor.item/vnd.io.github.sumyabazar1.weather.now";
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException("read-only");
    }
}
