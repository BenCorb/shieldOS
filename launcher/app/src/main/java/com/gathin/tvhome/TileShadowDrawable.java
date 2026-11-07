package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;

/**
 * The soft shadow a focused tile floats on, as on tvOS: a wide, dark blur a little below the tile, drawn by
 * TileShadowView behind the card and faded in by TvosFocusMotion. Android's elevation shadows are too faint for this
 * on a dark Home, so the blur is made once per tile size in software (a blur mask filter) and drawn as a bitmap past
 * the view's edges.
 */
final class TileShadowDrawable extends Drawable {
    /** One shadow per tile size: every tile of a grid shares it. */
    private static Bitmap cached;
    private static int cachedW, cachedH;

    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final float density;
    private final Rect dst = new Rect();

    TileShadowDrawable(float density) {
        this.density = density;
        // An ALPHA_8 bitmap draws in the paint's colour: black, at the blur's alpha.
        paint.setColor(0xFF000000);
    }

    /** How far the blur spreads past the tile, and how far below the tile it sits, in px. */
    static int spread(float density) {
        return Math.round(26f * density);
    }

    static int drop(float density) {
        return Math.round(16f * density);
    }

    private Bitmap shadow(int w, int h) {
        if (cached != null && cachedW == w && cachedH == h) return cached;
        int pad = spread(density);
        Bitmap bitmap = Bitmap.createBitmap(w + 2 * pad, h + 2 * pad, Bitmap.Config.ALPHA_8);
        Canvas canvas = new Canvas(bitmap);
        Paint shape = new Paint(Paint.ANTI_ALIAS_FLAG);
        shape.setColor(0xF2000000);
        shape.setMaskFilter(new BlurMaskFilter(pad * 0.6f, BlurMaskFilter.Blur.NORMAL));
        // A touch narrower than the tile, so the shadow gathers under it rather than haloing its sides.
        RectF rect = new RectF(pad + w * 0.05f, pad + h * 0.08f, pad + w * 0.95f, pad + h);
        float radius = AppAdapter.cornerRadius(w, density);
        canvas.drawRoundRect(rect, radius, radius, shape);
        cached = bitmap;
        cachedW = w;
        cachedH = h;
        return bitmap;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        int pad = spread(density), drop = drop(density);
        dst.set(b.left - pad, b.top - pad + drop, b.right + pad, b.bottom + pad + drop);
        canvas.drawBitmap(shadow(b.width(), b.height()), null, dst, paint);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
