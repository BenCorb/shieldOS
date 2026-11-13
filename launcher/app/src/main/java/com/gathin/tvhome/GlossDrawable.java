package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;

/**
 * tvOS focus gloss: a narrow diagonal sheen from the top left, behind which the tile keeps its own colours. The view
 * it fills is drawn at twice the tile's size (item_app.xml), so TvosFocusMotion can slide it without its edges
 * showing. A shape gradient has only three stops, so its sheen rose and fell across the whole tile and greyed dark
 * tiles; here it rises and falls within a band.
 */
final class GlossDrawable extends Drawable {
    /** The sheen's white at its brightest, and where along the top-left to bottom-right diagonal it rises and falls. */
    static final int PEAK = 0x30FFFFFF;
    static final float[] STOPS = {0f, 0.30f, 0.42f, 0.54f, 1f};
    private static final int[] COLORS = {0x00FFFFFF, 0x00FFFFFF, PEAK, 0x00FFFFFF, 0x00FFFFFF};

    private final Paint paint = new Paint();

    @Override
    protected void onBoundsChange(@NonNull Rect bounds) {
        paint.setShader(bounds.isEmpty() ? null : new LinearGradient(bounds.left, bounds.top, bounds.right,
                bounds.bottom, COLORS, STOPS, Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (paint.getShader() != null) canvas.drawRect(getBounds(), paint);
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
