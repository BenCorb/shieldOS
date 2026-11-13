package com.gathin.tvhome;

import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * One interruptible tvOS-style focus state owned by a single app tile holder: the focused tile grows, lifts on a soft
 * shadow (TileShadowDrawable, under the card) and leans briefly towards the D-pad direction with its gloss sliding the
 * same way, then settles and stays still: Home draws nothing more until the next move.
 */
final class TvosFocusMotion {
    static final float SCALE = 1.17f;
    static final float TILT_DEGREES = 5.5f;
    static final long DIRECTION_TTL_MS = 220L;
    /** The focused tile's elevation, in dp: it keeps the tile drawn over its neighbours as it grows. */
    static final float LIFT_DP = 14f;
    private static int pendingDirection;
    private static long pendingDirectionAt;

    private final View card;
    private final View shine;
    private final TileShadowView shadow;
    private final float density;

    TvosFocusMotion(View card, View shine, TileShadowView shadow, float density) {
        this.card = card;
        this.shine = shine;
        this.shadow = shadow;
        this.density = density;
        // Close enough for a few degrees of tilt to read as depth on a TV, not just as a slight shrink.
        card.setCameraDistance(7000f * density);
    }

    static void noteKey(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            noteDirection(event.getKeyCode(), event.getRepeatCount(), SystemClock.uptimeMillis());
        }
    }

    static void noteDirection(int keyCode, int repeatCount, long nowMs) {
        if (repeatCount != 0 || keyCode < KeyEvent.KEYCODE_DPAD_UP || keyCode > KeyEvent.KEYCODE_DPAD_RIGHT) return;
        pendingDirection = keyCode;
        pendingDirectionAt = nowMs;
    }

    static int consumeDirection(long nowMs) {
        int direction = pendingDirection;
        long age = nowMs - pendingDirectionAt;
        pendingDirection = 0;
        return direction != 0 && age >= 0L && age <= DIRECTION_TTL_MS ? direction : 0;
    }

    static float rotationXFor(int direction) {
        return direction == KeyEvent.KEYCODE_DPAD_DOWN ? TILT_DEGREES
                : direction == KeyEvent.KEYCODE_DPAD_UP ? -TILT_DEGREES : 0f;
    }

    static float rotationYFor(int direction) {
        return direction == KeyEvent.KEYCODE_DPAD_RIGHT ? -TILT_DEGREES
                : direction == KeyEvent.KEYCODE_DPAD_LEFT ? TILT_DEGREES : 0f;
    }

    static float edgeShift(float widthPx, float density) {
        return Math.max(0f, widthPx * (SCALE - 1f) / 2f - 8f * density);
    }

    void set(boolean focused, boolean animations, boolean reduced, float shiftX) {
        card.animate().cancel();
        shine.animate().cancel();
        float scale = focused ? !animations ? 1.10f : reduced ? 1.08f : SCALE : 1f;
        card.setScaleX(scale);
        card.setScaleY(scale);
        card.setTranslationX(focused ? shiftX : 0f);
        card.setTranslationY(focused ? -(animations ? reduced ? 2f : 5f : 3f) * density : 0f);
        card.setTranslationZ(focused ? (animations ? reduced ? 6f : LIFT_DP : 8f) * density : 0f);
        card.setRotationX(0f);
        card.setRotationY(0f);
        shine.setAlpha(focused && animations && !reduced ? 1f : 0f);
        shine.setTranslationX(0f);
        shine.setTranslationY(-card.getHeight() * 0.22f);
        shadow.animate().cancel();
        shadow.setScaleX(scale);
        shadow.setScaleY(scale);
        shadow.setTranslationX(card.getTranslationX());
        shadow.setTranslationY(card.getTranslationY());
        shadow.setShadowAlpha(focused ? 1f : 0f);
    }

    void animate(boolean focused, boolean animations, boolean reduced, float shiftX) {
        if (!animations) {
            set(focused, false, false, shiftX);
            return;
        }
        card.animate().cancel();
        shine.animate().cancel();
        int direction = focused && !reduced ? consumeDirection(SystemClock.uptimeMillis()) : 0;
        if (direction != 0) {
            card.setRotationX(rotationXFor(direction));
            card.setRotationY(rotationYFor(direction));
            float dx = direction == KeyEvent.KEYCODE_DPAD_RIGHT ? 1f : direction == KeyEvent.KEYCODE_DPAD_LEFT ? -1f : 0f;
            float dy = direction == KeyEvent.KEYCODE_DPAD_DOWN ? 1f : direction == KeyEvent.KEYCODE_DPAD_UP ? -1f : 0f;
            shine.setTranslationX(dx * card.getWidth() * 0.28f);
            shine.setTranslationY(dy == 0f ? -card.getHeight() * 0.18f : dy * card.getHeight() * 0.28f);
        }
        long duration = reduced ? focused ? 100L : 70L : focused ? 360L : 160L;
        DecelerateInterpolator ease = new DecelerateInterpolator(1.7f);
        card.animate()
                .scaleX(focused ? reduced ? 1.08f : SCALE : 1f)
                .scaleY(focused ? reduced ? 1.08f : SCALE : 1f)
                .translationX(focused ? shiftX : 0f)
                .translationY(focused ? -(reduced ? 2f : 5f) * density : 0f)
                .translationZ(focused ? (reduced ? 6f : LIFT_DP) * density : 0f)
                .rotationX(0f).rotationY(0f)
                .setDuration(duration).setInterpolator(ease)
                .start();
        shadow.animate().cancel();
        final float fromShadow = shadow.shadowAlpha(), toShadow = focused ? 1f : 0f;
        shadow.animate()
                .scaleX(focused ? reduced ? 1.08f : SCALE : 1f)
                .scaleY(focused ? reduced ? 1.08f : SCALE : 1f)
                .translationX(focused ? shiftX : 0f)
                .translationY(focused ? -(reduced ? 2f : 5f) * density : 0f)
                .setUpdateListener(a -> shadow.setShadowAlpha(fromShadow + (toShadow - fromShadow) * a.getAnimatedFraction()))
                .setDuration(duration).setInterpolator(ease).start();
        shine.animate().alpha(focused && !reduced ? 1f : 0f)
                .translationX(0f).translationY(-card.getHeight() * 0.22f)
                .setDuration(duration).setInterpolator(ease).start();
    }
}
