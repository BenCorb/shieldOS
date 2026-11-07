package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.View;

/**
 * Draws TileShadowDrawable for the tile it sits behind. The view is the tile plus the blur's spread on every side
 * (and its drop below), laid out round the card by AppAdapter, because whatever a view draws past its own bounds is
 * cut off: the shadow lives entirely outside the tile. It scales about the tile's centre, as the card does. Its
 * strength is the drawing's own alpha (setShadowAlpha), not the view's.
 */
public class TileShadowView extends View {
    private final TileShadowDrawable shadow;
    private final float density;
    private float shadowAlpha;

    public TileShadowView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        shadow = new TileShadowDrawable(density);
        setWillNotDraw(false);
        shadow.setAlpha(0);
    }

    float shadowAlpha() {
        return shadowAlpha;
    }

    void setShadowAlpha(float alpha) {
        alpha = Math.max(0f, Math.min(1f, alpha));
        if (alpha == shadowAlpha) return;
        shadowAlpha = alpha;
        shadow.setAlpha(Math.round(alpha * 255f));
        invalidate();
    }

    /** Sizes the view round a tileW x tileH card at the top of its item (call before layout). */
    void fitTile(int tileW, int tileH) {
        int pad = TileShadowDrawable.spread(density), drop = TileShadowDrawable.drop(density);
        android.widget.FrameLayout.LayoutParams lp = (android.widget.FrameLayout.LayoutParams) getLayoutParams();
        lp.width = tileW + 2 * pad;
        lp.height = tileH + 2 * pad + drop;
        lp.topMargin = -pad;
        // Negative margins as large as the overhang: in its item the shadow takes exactly the card's room, so the
        // grid's rows keep their spacing.
        lp.bottomMargin = -(pad + drop);
        setLayoutParams(lp);
        // Scaled about the card's centre, like the card itself.
        setPivotX(lp.width / 2f);
        setPivotY(pad + tileH / 2f);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (shadowAlpha <= 0f) return;
        int pad = TileShadowDrawable.spread(density);
        shadow.setBounds(pad, pad, getWidth() - pad, getHeight() - pad - TileShadowDrawable.drop(density));
        shadow.draw(canvas);
    }
}
