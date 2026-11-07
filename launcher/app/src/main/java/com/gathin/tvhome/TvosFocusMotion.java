package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

/**
 * One interruptible tvOS-style focus state owned by a single app tile holder: the focused tile grows, lifts on a soft
 * shadow (TileShadowDrawable, under the card), leans briefly towards the D-pad direction with its gloss
 * sliding the same way, then keeps a slow, faint sway of tilt and gloss while it stays focused, as tvOS's icons do.
 */
final class TvosFocusMotion {
    static final float SCALE = 1.17f;
    static final float TILT_DEGREES = 5.5f;
    static final long DIRECTION_TTL_MS = 220L;
    /** The idle sway: how far the tile tilts (degrees), and how long one full loop takes. */
    static final float SWAY_DEGREES = 3.5f;
    static final long SWAY_MS = 4200L;
    /** The focused tile's elevation, in dp: it keeps the tile drawn over its neighbours as it grows. */
    static final float LIFT_DP = 14f;
    private static int pendingDirection;
    private static long pendingDirectionAt;

    private final View card;
    private final View shine;
    private final TileShadowView shadow;
    private final float density;
    private ValueAnimator sway;
    /** Home is stopped (pause()): a sway asked for meanwhile, by a focus move's end action, waits for resume(). */
    private static boolean homeHidden;
    private boolean swayPending;

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
        stopSway();
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
        if (focused && animations && !reduced) startSway();
    }

    void animate(boolean focused, boolean animations, boolean reduced, float shiftX) {
        if (!animations) {
            set(focused, false, false, shiftX);
            return;
        }
        stopSway();
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
                // Once the tile has settled, it keeps breathing gently while it stays focused.
                .withEndAction(focused && !reduced ? this::startSway : null)
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

    /** A slow loop of tilt and gloss: a circle traced by the tilt, the gloss following it the opposite way. */
    private void startSway() {
        stopSway();
        if (!card.isFocused()) return;
        if (homeHidden) {
            swayPending = true;
            return;
        }
        final float baseX = shine.getTranslationX(), baseY = shine.getTranslationY();
        sway = ValueAnimator.ofFloat(0f, (float) (2 * Math.PI));
        sway.setDuration(SWAY_MS);
        sway.setRepeatCount(ValueAnimator.INFINITE);
        sway.setInterpolator(new LinearInterpolator());
        sway.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            // Eased in over the first quarter turn, so the sway starts from the settled tile without a jump.
            float ramp = Math.min(1f, a.getCurrentPlayTime() / (SWAY_MS / 4f));
            float sx = (float) Math.sin(t) * ramp, sy = ((float) Math.cos(t) - 1f) * 0.6f * ramp;
            card.setRotationY(sx * SWAY_DEGREES);
            card.setRotationX(sy * SWAY_DEGREES);
            shine.setTranslationX(baseX - sx * card.getWidth() * 0.14f);
            shine.setTranslationY(baseY + sy * card.getHeight() * 0.14f);
        });
        sway.start();
    }

    /**
     * Home is no longer visible (onStop): the endless sway would keep waking the launcher at every frame behind the
     * app on screen. It holds its place and picks up from there in resume(). Home-wide, through setHomeHidden: a holder
     * resting in the pool while Home was stopped sways again once it is bound and focused.
     */
    void pause() {
        if (sway != null && sway.isRunning()) sway.pause();
    }

    /** Home is visible again (onStart): the sway goes on where pause() left it. */
    void resume() {
        boolean pending = swayPending;
        swayPending = false;
        if (sway != null) {
            if (card.isFocused()) sway.resume();
            else stopSway();
        } else if (pending) {
            startSway();
        }
    }

    /** Home's visibility, for every holder, also the ones not attached when it changed. */
    static void setHomeHidden(boolean hidden) {
        homeHidden = hidden;
    }

    private void stopSway() {
        swayPending = false;
        if (sway == null) return;
        sway.cancel();
        sway = null;
    }
}
