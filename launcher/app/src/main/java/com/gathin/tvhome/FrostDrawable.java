package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.View;

import androidx.annotation.NonNull;

/**
 * Glass behind a popup panel, like tvOS menus. It is there from the menu's first frame: it starts from the blurred
 * wallpaper Home already has, then switches to a blurred copy of Home itself (tiles included) as soon as PixelCopy
 * hands one over. Without liquid_glass it is a frosted blur under a dark tint and a hairline edge; with it, the
 * same live liquid glass as the dock (a sharper picture bent along the rim, a darker shade and the specular light),
 * drawn with shaders lined up with the screen on every frame, so the menu's drop-in never waits on a render.
 */
final class FrostDrawable extends Drawable {
    private final View host;
    private Bitmap frost, sharp;
    private BitmapShader frostShader, sharpShader;
    private final float screenW, screenH, radius, density;
    private final boolean liquid;
    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint lensPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lightPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Matrix frostMatrix = new Matrix(), sharpMatrix = new Matrix(), lensMatrix = new Matrix(),
            maskMatrix = new Matrix();
    private final RectF rect = new RectF();
    private final int[] at = new int[2];
    private float tintTop = Float.NaN, tintBottom = Float.NaN;
    /** LiquidGlass's size-bound layers for the panel, made once for its size (a menu is small: a few ms). */
    private Bitmap lens, light;
    private BitmapShader lensShader;
    private float layersW, layersH;

    private FrostDrawable(View host, Bitmap frost, Bitmap sharp, float screenW, float screenH, float radius,
                          float density, boolean liquid) {
        this.host = host;
        this.screenW = screenW;
        this.screenH = screenH;
        this.radius = radius;
        this.density = density;
        this.liquid = liquid;
        setPictures(frost, sharp);
        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeWidth(density);
        edgePaint.setColor(0x40FFFFFF);
        ColorMatrix saturate = new ColorMatrix();
        saturate.setSaturation(1.18f);
        ColorMatrixColorFilter filter = new ColorMatrixColorFilter(saturate);
        basePaint.setColorFilter(filter);
        lensPaint.setColorFilter(filter);
        // Darker than the dock's glass: menu text is white and sits right on it.
        shadePaint.setColor(0x80000000);
    }

    private void setPictures(Bitmap frost, Bitmap sharp) {
        this.frost = frost;
        this.sharp = sharp;
        frostShader = new BitmapShader(frost, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        sharpShader = sharp == null ? null : new BitmapShader(sharp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
    }

    /**
     * Puts glass behind panel at once, from the blurred wallpaper, then refines it with a blurred copy of the window
     * at the given dock_blur level (0: clear glass). Without a blurred wallpaper yet, the copy alone is used; if that
     * fails too, the panel keeps its own glass background.
     */
    static void apply(Activity activity, View panel, int level, float radiusPx, boolean liquid) {
        if (activity.isFinishing()) return;
        View decor = activity.getWindow().getDecorView();
        int w = decor.getWidth(), h = decor.getHeight();
        if (w <= 0 || h <= 0) return;
        float density = activity.getResources().getDisplayMetrics().density;
        Bitmap wallpaper = BlurBackdropView.latestBlur();
        Bitmap wallpaperSharp = liquid ? BlurBackdropView.latestSharp() : null;
        final FrostDrawable[] glass = new FrostDrawable[1];
        if (wallpaper != null) {
            glass[0] = new FrostDrawable(panel, wallpaper, wallpaperSharp, w, h, radiusPx, density, liquid);
            panel.setBackground(glass[0]);
        }
        int ds = BlurBackdropView.downscale(level);
        // Liquid glass wants a sharper copy for its rim: take the window at half size, and shrink it for the frost.
        int copyDs = liquid ? 2 : ds;
        Bitmap copy = Bitmap.createBitmap(Math.max(1, w / copyDs), Math.max(1, h / copyDs), Bitmap.Config.ARGB_8888);
        android.content.Context app = activity.getApplicationContext();
        try {
            PixelCopy.request(activity.getWindow(), copy, result -> {
                if (result != PixelCopy.SUCCESS || activity.isFinishing()) return;
                // The blurs run on the glass's worker, not here on the UI thread, where they held up the drop-in.
                BlurBackdropView.GLASS_WORKER.execute(() -> {
                    Bitmap small = copy;
                    if (liquid) small = Bitmap.createScaledBitmap(copy, Math.max(1, w / ds), Math.max(1, h / ds), true);
                    float r = BlurBackdropView.radius(level);
                    // Level 0 is clear glass: the copy itself, unblurred.
                    Bitmap frost = r > 0f ? BlurBackdropView.blur(app, small, r)
                            : small.copy(Bitmap.Config.ARGB_8888, false);
                    Bitmap sharp = liquid ? BlurBackdropView.blur(app, copy, 2f) : null;
                    if (liquid && sharp == null) sharp = copy;
                    if (frost == null) return;
                    final Bitmap madeSharp = sharp;
                    panel.post(() -> {
                        if (activity.isFinishing() || !panel.isAttachedToWindow()) return;
                        // Bitmaps a frame may still draw are left to the garbage collector rather than recycled.
                        if (glass[0] != null) {
                            glass[0].setPictures(frost, madeSharp);
                            glass[0].invalidateSelf();
                        } else {
                            panel.setBackground(new FrostDrawable(panel, frost, madeSharp, w, h, radiusPx, density,
                                    liquid));
                        }
                    });
                });
            }, new Handler(Looper.getMainLooper()));
        } catch (IllegalArgumentException e) {
            // The window has no surface yet: the wallpaper glass, if any, stays.
        }
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        rect.set(getBounds());
        if (rect.isEmpty()) return;
        // The panel's place on screen, so the pictures line up with what is behind it.
        host.getLocationOnScreen(at);
        frostMatrix.setScale(screenW / frost.getWidth(), screenH / frost.getHeight());
        frostMatrix.postTranslate(-at[0], -at[1]);
        frostShader.setLocalMatrix(frostMatrix);
        basePaint.setShader(frostShader);
        canvas.drawRoundRect(rect, radius, radius, basePaint);
        if (liquid && sharpShader != null) {
            drawLiquid(canvas);
            return;
        }
        if (rect.top != tintTop || rect.bottom != tintBottom) {
            // Dark glass, a little lighter at the top like tvOS's Liquid Glass panels, so white text stays readable.
            tintPaint.setShader(new LinearGradient(0, rect.top, 0, rect.bottom, 0x9E2C2C30, 0xB8141416,
                    Shader.TileMode.CLAMP));
            tintTop = rect.top;
            tintBottom = rect.bottom;
        }
        canvas.drawRoundRect(rect, radius, radius, tintPaint);
        float half = density / 2f;
        rect.inset(half, half);
        canvas.drawRoundRect(rect, radius, radius, edgePaint);
    }

    /** The rim's bent, sharper picture, the shade and the light, as BlurBackdropView draws the dock's glass. */
    private void drawLiquid(Canvas canvas) {
        if (lens == null || Math.abs(layersW - rect.width()) >= 1f || Math.abs(layersH - rect.height()) >= 1f) {
            Bitmap[] made = LiquidGlass.layers(rect.width(), rect.height(), radius, density);
            lens = made[0];
            light = made[1];
            lensShader = new BitmapShader(lens, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            layersW = rect.width();
            layersH = rect.height();
        }
        sharpMatrix.setScale(screenW / sharp.getWidth(), screenH / sharp.getHeight());
        sharpMatrix.postTranslate(-at[0], -at[1]);
        float bend = LiquidGlass.bend(density);
        float kx = Math.max(0.5f, 1f - bend / Math.max(1f, rect.width() / 2f));
        float ky = Math.max(0.5f, 1f - bend / Math.max(1f, rect.height() / 2f));
        lensMatrix.set(sharpMatrix);
        lensMatrix.postScale(1f / kx, 1f / ky, rect.centerX(), rect.centerY());
        sharpShader.setLocalMatrix(lensMatrix);
        maskMatrix.setScale(rect.width() / lens.getWidth(), rect.height() / lens.getHeight());
        maskMatrix.postTranslate(rect.left, rect.top);
        lensShader.setLocalMatrix(maskMatrix);
        lensPaint.setShader(new ComposeShader(sharpShader, lensShader, PorterDuff.Mode.DST_IN));
        canvas.drawRoundRect(rect, radius, radius, lensPaint);
        canvas.drawRoundRect(rect, radius, radius, shadePaint);
        canvas.drawBitmap(light, null, rect, lightPaint);
    }

    @Override
    public void setAlpha(int alpha) {
        basePaint.setAlpha(alpha);
        lensPaint.setAlpha(alpha);
        tintPaint.setAlpha(alpha);
        shadePaint.setAlpha(alpha * 0x80 / 255);
        edgePaint.setAlpha(alpha * 0x40 / 255);
        lightPaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        basePaint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
