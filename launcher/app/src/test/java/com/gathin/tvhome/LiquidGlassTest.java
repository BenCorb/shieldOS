package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LiquidGlassTest {
    private static final float BEND = 52f;   // LiquidGlass.bend at density 2, the SHIELD's 1080p UI

    @Test
    public void aLongAxisKeepsItsFullBend() {
        // The dock's width (about 1,700 px): the rim pulls the picture in by the whole bend.
        float k = LiquidGlass.lensScale(BEND, 850f);
        assertEquals(850f * (1f - k), BEND, 0.01f);
    }

    @Test
    public void aShortAxisNeverZoomsPastTheCap() {
        // The dock's height (about 210 px) asked for a 2x zoom, which smeared the picture into streaks.
        float k = LiquidGlass.lensScale(BEND, 105f);
        assertEquals(1f / LiquidGlass.MAX_ZOOM, k, 0.0001f);
    }

    @Test
    public void bothAxesOfTheDockStayClose() {
        float zoomX = 1f / LiquidGlass.lensScale(BEND, 850f);
        float zoomY = 1f / LiquidGlass.lensScale(BEND, 105f);
        assertTrue(zoomY / zoomX < 1.2f);
    }

    @Test
    public void aDegenerateShapeStaysFinite() {
        assertEquals(1f / LiquidGlass.MAX_ZOOM, LiquidGlass.lensScale(BEND, 0f), 0.0001f);
    }
}
