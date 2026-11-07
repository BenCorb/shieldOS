package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import android.view.KeyEvent;

import org.junit.After;
import org.junit.Test;

public class TvosFocusMotionTest {
    @After
    public void clearPendingDirection() {
        TvosFocusMotion.consumeDirection(Long.MAX_VALUE);
    }

    @Test
    public void directionsMapToExpectedAxes() {
        assertEquals(-TvosFocusMotion.TILT_DEGREES,
                TvosFocusMotion.rotationYFor(KeyEvent.KEYCODE_DPAD_RIGHT), 0f);
        assertEquals(TvosFocusMotion.TILT_DEGREES,
                TvosFocusMotion.rotationYFor(KeyEvent.KEYCODE_DPAD_LEFT), 0f);
        assertEquals(-TvosFocusMotion.TILT_DEGREES,
                TvosFocusMotion.rotationXFor(KeyEvent.KEYCODE_DPAD_UP), 0f);
        assertEquals(TvosFocusMotion.TILT_DEGREES,
                TvosFocusMotion.rotationXFor(KeyEvent.KEYCODE_DPAD_DOWN), 0f);
    }

    @Test
    public void directionIsConsumedOnlyOnce() {
        TvosFocusMotion.noteDirection(KeyEvent.KEYCODE_DPAD_RIGHT, 0, 1_000L);
        assertEquals(KeyEvent.KEYCODE_DPAD_RIGHT, TvosFocusMotion.consumeDirection(1_050L));
        assertEquals(0, TvosFocusMotion.consumeDirection(1_060L));
    }

    @Test
    public void repeatedAndExpiredDirectionsAreIgnored() {
        TvosFocusMotion.noteDirection(KeyEvent.KEYCODE_DPAD_LEFT, 1, 1_000L);
        assertEquals(0, TvosFocusMotion.consumeDirection(1_010L));

        TvosFocusMotion.noteDirection(KeyEvent.KEYCODE_DPAD_DOWN, 0, 2_000L);
        assertEquals(0, TvosFocusMotion.consumeDirection(2_000L + TvosFocusMotion.DIRECTION_TTL_MS + 1L));
    }

    @Test
    public void dockEdgeShiftOnlyUsesGrowthBeyondClearance() {
        assertEquals(0f, TvosFocusMotion.edgeShift(90f, 1f), 0f);
        assertEquals(7.3f, TvosFocusMotion.edgeShift(180f, 1f), 0.001f);
    }
}
