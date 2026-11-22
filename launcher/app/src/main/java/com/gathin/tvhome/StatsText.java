package com.gathin.tvhome;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Isolate complete measurements, not individual digits, in the Hebrew stats paragraph. */
final class StatsText {
    private static final Pattern MEASUREMENT = Pattern.compile("[0-9]+(?:[.,][0-9]+)?(?:\\s*/\\s*[0-9]+(?:[.,][0-9]+)?)?(?:\\s+(?:MB|GB)|%)");
    /** Bytes per second in one letter: "0K", "85K", "2.4M", "24M", "1.1G" (M and G with one decimal under 10). */
    static String speed(double bytesPerSec) {
        double v = bytesPerSec / 1024;
        if (v < 999.5) return Math.round(v) + "K";   // 999.5 and up would print as 1000K
        v /= 1024;
        if (v >= 999.5) return number(v / 1024) + "G";
        return number(v) + "M";
    }

    private static String number(double v) {
        return v < 9.95 ? String.format(Locale.ROOT, "%.1f", v) : String.valueOf(Math.round(v));
    }

    static String forLocale(String text, Locale locale) {
        String language = locale.getLanguage();
        if (!language.equals("he") && !language.equals("iw")) return text;
        Matcher matcher = MEASUREMENT.matcher(text);
        StringBuffer isolated = new StringBuffer();
        while (matcher.find()) matcher.appendReplacement(isolated, Matcher.quoteReplacement("\u200E\u202A" + matcher.group() + "\u202C\u200E"));
        matcher.appendTail(isolated);
        // Embeddings plus directional marks also work on TV text renderers that mishandle isolates.
        return "\u200F" + isolated.toString().replace("  ·  ", "\u200F  ·  \u200F");
    }
}
