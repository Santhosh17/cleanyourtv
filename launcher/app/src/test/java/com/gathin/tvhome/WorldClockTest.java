package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class WorldClockTest {
    private final List<WorldClock.City> cities = WorldClock.allCities();

    @Test public void tzCityIsFound() {
        List<WorldClock.City> r = WorldClock.search(cities, "lond", 10);
        assertEquals("London", r.get(0).name);
        assertEquals("Europe/London", r.get(0).zone);
    }

    @Test public void aliasCityIsFound() {
        assertEquals("Asia/Kolkata", WorldClock.search(cities, "Bangalore", 5).get(0).zone);
        assertEquals("America/Los_Angeles", WorldClock.search(cities, "san fran", 5).get(0).zone);
    }

    @Test public void prefixMatchesComeBeforeContains() {
        List<WorldClock.City> r = WorldClock.search(cities, "york", 10);
        assertTrue(r.stream().anyMatch(c -> c.name.equals("New York")));
    }

    @Test public void limitAndUnderscores() {
        assertEquals(5, WorldClock.search(cities, "a", 5).size());
        assertTrue(cities.stream().noneMatch(c -> c.name.contains("_")));
    }

    @Test public void offsets() {
        assertEquals("GMT+5:30", WorldClock.offsetLabel("Asia/Kolkata"));
        assertEquals("GMT", WorldClock.offsetLabel("UTC"));
    }
}
