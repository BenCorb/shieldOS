package com.gathin.tvhome;

import android.graphics.Bitmap;

/**
 * tvOS-style Liquid Glass for one rounded shape (config liquid_glass). Android 11 has no runtime shaders, so the glass
 * is split: the pictures are drawn live with bitmap shaders lined up with the screen (BlurBackdropView for the dock and
 * the Settings button, FrostDrawable for menus), and this makes the parts that depend only on the shape's size, once,
 * as half-size bitmaps. Together they give:
 * <ul>
 *   <li>the blurred wallpaper under the shape, bent inwards along the rim like the edge of a lens (refraction), and
 *   sharper there: the rim magnifies a barely blurred copy while the middle stays frosted;</li>
 *   <li>a little more saturation and a dark glass tint, so white icons and text stay readable;</li>
 *   <li>a thin specular rim, brightest where the light from the top left meets it, and a soft inner sheen.</li>
 * </ul>
 * The wallpaper blur is the launcher's own (BlurBackdropView); this only shapes and lights it.
 */
final class LiquidGlass {
    /** Glass pixels per screen pixel: half size is plenty for a blurred picture, and four times less work. */
    static final float SCALE = 0.5f;

    private LiquidGlass() {
    }

    /**
     * The parts of the glass that depend only on its size, for a glass drawn live over a moving picture (the dock):
     * lens is white whose alpha says how much of the bent, sharper picture shows (nothing in the middle, nearly all
     * at the rim), light is white whose alpha is the sheen and the specular rim. Both are half size and transparent
     * outside the corners. The picture itself is drawn by the caller with shaders, so a move needs no new render.
     */
    static Bitmap[] layers(float width, float height, float radius, float density) {
        int w = Math.max(1, (int) Math.ceil(width * SCALE));
        int h = Math.max(1, (int) Math.ceil(height * SCALE));
        int[] lens = new int[w * h], light = new int[w * h];
        float halfW = width / 2f, halfH = height / 2f;
        float r = Math.min(radius, Math.min(halfW, halfH));
        float bezel = Math.min(18f * density, Math.min(halfW, halfH));
        float rimWidth = 2.2f * density;
        float lx = -0.45f, ly = -0.89f;
        float step = 1f / SCALE;
        for (int j = 0; j < h; j++) {
            float y = (j + 0.5f) * step;
            float qy = y - halfH;
            float sheen = clamp(1f - y / (height * 0.55f), 0f, 1f);
            for (int i = 0; i < w; i++) {
                float qx = (i + 0.5f) * step - halfW;
                float kx = Math.abs(qx) - (halfW - r), ky = Math.abs(qy) - (halfH - r);
                float ox = Math.max(kx, 0f), oy = Math.max(ky, 0f);
                float d = (float) Math.sqrt(ox * ox + oy * oy) + Math.min(Math.max(kx, ky), 0f) - r;
                float coverage = clamp(0.5f - d / step, 0f, 1f);
                if (coverage <= 0f) continue;
                float nx, ny;
                if (kx > 0f && ky > 0f) {
                    float len = (float) Math.sqrt(kx * kx + ky * ky);
                    nx = kx / len;
                    ny = ky / len;
                } else if (kx > ky) {
                    nx = 1f;
                    ny = 0f;
                } else {
                    nx = 0f;
                    ny = 1f;
                }
                if (qx < 0f) nx = -nx;
                if (qy < 0f) ny = -ny;
                float depth = -d;
                float t = clamp(1f - depth / bezel, 0f, 1f);
                float rim = clamp(1f - depth / rimWidth, 0f, 1f);
                float facing = Math.max(0f, nx * lx + ny * ly);
                float glow = 14f * sheen * sheen + rim * rim * (0.22f + 0.78f * facing) * 230f
                        + t * t * t * t * facing * 36f;
                int lensAlpha = Math.round(coverage * 0.9f * t * (float) Math.sqrt(t) * 255f);
                int lightAlpha = Math.round(coverage * clamp(glow, 0f, 255f));
                lens[j * w + i] = lensAlpha << 24 | 0xFFFFFF;
                light[j * w + i] = lightAlpha << 24 | 0xFFFFFF;
            }
        }
        Bitmap lensBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        lensBitmap.setPixels(lens, 0, w, 0, 0, w, h);
        Bitmap lightBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        lightBitmap.setPixels(light, 0, w, 0, 0, w, h);
        return new Bitmap[]{lensBitmap, lightBitmap};
    }

    /** How far, in px, the rim pulls the picture in: the live glass bends the picture about its centre by this much. */
    static float bend(float density) {
        return 26f * density;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }
}
