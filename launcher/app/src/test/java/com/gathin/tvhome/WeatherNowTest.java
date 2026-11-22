package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class WeatherNowTest {
    @Test public void compassHasEightPoints() {
        assertEquals("N", WeatherNow.compass(0));
        assertEquals("N", WeatherNow.compass(350));
        assertEquals("NE", WeatherNow.compass(40));
        assertEquals("S", WeatherNow.compass(180));
        assertEquals("NW", WeatherNow.compass(315));
        assertEquals("N", WeatherNow.compass(360));
    }

    @Test public void wearFollowsFeelsLikeRainAndSun() {
        assertTrue(WeatherNow.wear(-3, false, false).endsWith("Coat"));
        assertTrue(WeatherNow.wear(5, false, false).endsWith("Coat"));
        assertTrue(WeatherNow.wear(12, false, false).endsWith(" Jacket"));
        assertTrue(WeatherNow.wear(18, false, false).endsWith("Light jacket"));
        assertTrue(WeatherNow.wear(27, false, false).endsWith("T-shirt"));
        assertTrue(WeatherNow.wear(33, false, false).endsWith("Shorts"));
        assertTrue(WeatherNow.wear(10, true, false).endsWith("☂️"));
        assertTrue(WeatherNow.wear(30, true, true).endsWith("☂️ 🧴"));
    }
}
