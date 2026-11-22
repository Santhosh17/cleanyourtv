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
 * flag (for an icon), today's high and low, the next seven days as "weekday,code,high,low;..." (weekday 1 =
 * Monday), the US AQI (-1 unknown), today's sunrise and sunset ("HH:mm", local to the place) and today's WMO code -
 * today's rain chance (%) and amount (mm), the next 2 hours as eight 15-minute amounts "0.0,0.4,...", and the time
 * of the reading (ms), and the temperature in both °C and °F (tempc, tempf), the UV index now (uv, -1 unknown), humidity (%),
 * wind (km/h) and the direction it blows from (degrees, -1 unknown), and feels-like in °C (feelsc) - or no row before a city is picked. The seven days carry ",chance,mm" too. Read-only.
 *
 * It fetches only when asked and at most every 30 minutes (Forecast.REFRESH_MS), from the asking app's background
 * thread, so the add-on still runs no service. The query "cached" never goes online (for a settings summary).
 */
public final class HomeProvider extends ContentProvider {
    private static final String[] COLUMNS = {"city", "temp", "feels", "unit", "sky", "code", "day", "high", "low", "days", "aqi", "sunrise", "sunset", "todaycode", "rainchance", "rainmm", "next2h", "updated", "tempc", "tempf", "uv", "humidity", "wind", "winddir", "feelsc"};
    /** Bumped when the cached fields change, so an older cache is fetched again. */
    private static final int CACHE_VERSION = 8;

    @Override public boolean onCreate() {
        return true;
    }

    @Override
    public synchronized Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        if ("country".equals(uri.getLastPathSegment())) return country(selection);
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
                Forecast.Report r = Forecast.fetch(lat, lon, Forecast.forecastDays(7));
                StringBuilder days = new StringBuilder();
                for (int i = 0; i < Math.min(7, r.days.size()); i++) {
                    Forecast.Day d = r.days.get(i);
                    if (days.length() > 0) days.append(';');
                    days.append(d.date.getDayOfWeek().getValue()).append(',').append(d.code).append(',')
                            .append(Double.doubleToRawLongBits(d.maxC)).append(',')
                            .append(Double.doubleToRawLongBits(d.minC)).append(',')
                            .append(i + 1 < r.rainChance.size() ? r.rainChance.get(i + 1) : -1).append(',')
                            .append(i + 1 < r.rainMm.size() ? r.rainMm.get(i + 1) : 0.0);
                }
                cache.edit().putString("for", key).putInt("v", CACHE_VERSION).putLong("at", System.currentTimeMillis())
                        .putLong("high", Double.doubleToRawLongBits(r.todayMaxC))
                        .putLong("low", Double.doubleToRawLongBits(r.todayMinC))
                        .putString("days", days.toString())
                        .putInt("aqi", r.aqi).putString("sunrise", r.sunrise).putString("sunset", r.sunset)
                        .putInt("todaycode", r.todayCode).putInt("uv", r.uv).putInt("humidity", r.humidity)
                        .putInt("wind", r.windKmh).putInt("winddir", r.windDir)
                        .putInt("rainchance", r.rainChance.isEmpty() ? -1 : r.rainChance.get(0))
                        .putFloat("rainmm", r.rainMm.isEmpty() ? 0f : r.rainMm.get(0).floatValue())
                        .putString("next2h", android.text.TextUtils.join(",", r.next2h))
                        .putLong("temp", Double.doubleToRawLongBits(r.tempC))
                        .putLong("feels", Double.doubleToRawLongBits(r.feelsC))
                        .putInt("code", r.code).putBoolean("day", r.day).commit();
            } catch (Exception e) {
                // Offline: fall back to the last reading for this city below, however old.
            }
        }
        if (!key.equals(cache.getString("for", ""))) {
            out.addRow(new Object[]{city, null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null});
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
            if (p.length < 4) continue;
            if (days.length() > 0) days.append(';');
            days.append(p[0]).append(',').append(p[1]).append(',')
                    .append(Forecast.degrees(Double.longBitsToDouble(Long.parseLong(p[2])), f)).append(',')
                    .append(Forecast.degrees(Double.longBitsToDouble(Long.parseLong(p[3])), f));
            if (p.length >= 6) days.append(',').append(p[4]).append(',').append(p[5]);
        }
        out.addRow(new Object[]{city, Forecast.degrees(temp, f), Forecast.degrees(feels, f), f ? "F" : "C",
                getContext().getString(Forecast.sky(code, day)), code, day ? 1 : 0,
                Double.isNaN(high) ? null : Forecast.degrees(high, f), Double.isNaN(low) ? null : Forecast.degrees(low, f),
                days.toString(), cache.getInt("aqi", -1), cache.getString("sunrise", ""),
                cache.getString("sunset", ""), cache.getInt("todaycode", -1), cache.getInt("rainchance", -1),
                cache.getFloat("rainmm", 0f), cache.getString("next2h", ""), cache.getLong("at", 0),
                Forecast.degrees(temp, false), Forecast.degrees(temp, true), cache.getInt("uv", -1),
                cache.getInt("humidity", -1), cache.getInt("wind", -1), cache.getInt("winddir", -1),
                Forecast.degrees(feels, false)});
        return out;
    }

    /**
     * content://io.github.sumyabazar1.weather.home/country: the two-letter country of the TV's public IP (a VPN's
     * exit country when one is on), from Cloudflare's plain-text trace ("loc=DE"), which needs no account. Read at
     * most every 5 minutes, or at once with the query "fresh" (the launcher sends it after a network change); ""
     * when offline or unknown. A failed read is not kept, so the next query tries again.
     */
    private Cursor country(String selection) {
        MatrixCursor out = new MatrixCursor(new String[]{"country"});
        android.content.SharedPreferences net = getContext().getSharedPreferences("net", 0);
        String code = net.getString("country", "");
        if ("fresh".equals(selection) || System.currentTimeMillis() - net.getLong("at", 0) > 5 * 60_000L) {
            code = "";
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                        new java.net.URL("https://www.cloudflare.com/cdn-cgi/trace").openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(5000);
                try (java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(c.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    for (String line; (line = r.readLine()) != null; ) {
                        if (line.startsWith("loc=")) code = line.substring(4).trim();
                    }
                } finally {
                    c.disconnect();
                }
            } catch (Exception e) {
                // offline: no flag rather than a stale one
            }
            if (code.matches("[A-Z]{2}")) {
                net.edit().putString("country", code).putLong("at", System.currentTimeMillis()).apply();
            } else {
                code = "";
            }
        }
        out.addRow(new Object[]{code});
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
