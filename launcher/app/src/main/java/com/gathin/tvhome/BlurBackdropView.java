package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicBlur;
import android.util.AttributeSet;
import android.view.View;

/**
 * A deliberately small, real backdrop blur for the Shield's Android 11 renderer. The view is placed below the
 * launcher content, so it can blur the local wallpaper without capturing or recursively drawing the UI itself.
 */
@SuppressWarnings("deprecation")
public class BlurBackdropView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Rect region = new Rect();
    /** The dock band's rounded shape; the blur is painted only inside it, with anti-aliased corners. */
    private final RectF roundRegion = new RectF();
    private float roundRadius;
    /** A round top-bar button (Settings) frosted like the dock, shown whatever the dock blur is doing. */
    private final RectF buttonRegion = new RectF();
    private float buttonRadius;
    private final Paint blurPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix shaderMatrix = new Matrix();
    private boolean enabled;
    private boolean fullScreen;
    private Bitmap sourceRef;
    private Bitmap blurred;
    private RenderScript renderScript;
    private Allocation input;
    private Allocation output;
    private ScriptIntrinsicBlur blur;
    /** Launcher settings' blur level (config dock_blur): 0 is clear glass, MAX_LEVEL the most frosted. */
    private int level = 3;
    static final int MAX_LEVEL = 10;
    /** Per level: how far the working bitmap is shrunk, and the blur radius run on it (0: no blur at all). */
    private static final int[] DOWNSCALE = {2, 2, 3, 3, 4, 4, 5, 6, 7, 8, 10};
    private static final float[] RADIUS = {0f, 3f, 5f, 8f, 10f, 13f, 16f, 19f, 22f, 25f, 25f};
    /** config liquid_glass: the dock band and the button get LiquidGlass's lens rim and light instead of a flat blur. */
    private boolean liquid;
    /**
     * A lightly blurred half-size wallpaper: what the glass shows through its middle and bends at its rim. The glass
     * is drawn live from it with shaders on every frame, so it follows any move with no delay; only its size-bound
     * layers (LiquidGlass.layers) are made off the UI thread, once per size.
     */
    private Bitmap sharp;
    private BitmapShader sharpShader;
    private final Matrix sharpMatrix = new Matrix(), lensMatrix = new Matrix(), maskMatrix = new Matrix(),
            baseMatrix = new Matrix();
    private BitmapShader baseShader;
    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint lensPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint shadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lightPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Glass dockGlass = new Glass(), buttonGlass = new Glass();
    /** Told when new glass layers are ready, so a view painting the dock glass (the grid) redraws too. */
    private Runnable onLayers;

    void setOnGlassLayers(Runnable listener) {
        onLayers = listener;
    }
    private static final java.util.concurrent.ExecutorService GLASS_WORKER =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    /** One shape's size-bound glass layers: what they were made for, and whether newer ones are being made. */
    private static final class Glass {
        Bitmap lens, light;
        BitmapShader lensShader;
        float madeW, madeH, madeRadius;
        float wantedW, wantedH, wantedRadius;
        boolean busy;
    }

    public BlurBackdropView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        setWillNotDraw(false);
        setAlpha(0f);
    }

    void setLiquidGlass(boolean on) {
        if (on == liquid) return;
        liquid = on;
        // A little more colour in the glass, as tvOS's glass has, and a dark shade so white icons stay readable.
        android.graphics.ColorMatrix saturate = new android.graphics.ColorMatrix();
        saturate.setSaturation(1.18f);
        android.graphics.ColorMatrixColorFilter filter = new android.graphics.ColorMatrixColorFilter(saturate);
        basePaint.setColorFilter(filter);
        lensPaint.setColorFilter(filter);
        shadePaint.setColor(0x29000000);
        if (on && sourceRef != null) rebuildBlur();   // makes the sharper picture the glass needs
        invalidate();
    }

    void setSource(Bitmap source) {
        if (source == sourceRef) return;
        sourceRef = source;
        rebuildBlur();
    }

    /** Sets the blur strength, from 0 (the glass shows the wallpaper unblurred) to MAX_LEVEL. */
    void setLevel(int value) {
        int clamped = Math.max(0, Math.min(MAX_LEVEL, value));
        if (clamped == level) return;
        level = clamped;
        rebuildBlur();
    }

    /** Working-bitmap downscale for a level: a smaller bitmap stretched back over the screen blurs further. */
    static int downscale(int level) {
        return DOWNSCALE[Math.max(0, Math.min(MAX_LEVEL, level))];
    }

    /** ScriptIntrinsicBlur radius for a level (RenderScript caps it at 25); 0 means the picture is not blurred. */
    static float radius(int level) {
        return RADIUS[Math.max(0, Math.min(MAX_LEVEL, level))];
    }

    /** Blur passes for a level; one is enough now that the strongest level was softened. */
    static int passes(int level) {
        return 1;
    }

    /** Frosts a top-bar button's rounded shape (this view's coordinates); null or empty removes it. */
    void setButtonRegion(RectF value, float radius) {
        RectF next = value == null ? new RectF() : value;
        if (next.equals(buttonRegion) && radius == buttonRadius) return;
        Rect dirty = new Rect();
        buttonRegion.roundOut(dirty);
        buttonRegion.set(next);
        buttonRadius = Math.max(0f, radius);
        Rect now = new Rect();
        buttonRegion.roundOut(now);
        dirty.union(now);
        if (!buttonRegion.isEmpty()) {
            setVisibility(VISIBLE);
            setAlpha(1f);
        } else if (!enabled) {
            setAlpha(0f);
        }
        invalidate();
        if (getParent() instanceof View && !dirty.isEmpty()) ((View) getParent()).invalidate(dirty);
    }

    void setRegion(Rect value) {
        if (value == null || value.isEmpty()) {
            clear();
            return;
        }
        animate().cancel();
        enabled = true;
        fullScreen = false;
        roundRegion.setEmpty();
        roundRadius = 0f;
        region.set(value);
        region.intersect(0, 0, getWidth(), getHeight());
        setVisibility(VISIBLE);
        setAlpha(1f);
        invalidate();
        if (getParent() instanceof View) ((View) getParent()).invalidate(region);
    }

    /** Blurs only inside the dock band's rounded rectangle (this view's coordinates). */
    void setRoundRegion(RectF value, float radius) {
        if (value == null || value.isEmpty()) {
            clear();
            return;
        }
        if (enabled && !fullScreen && value.equals(roundRegion) && radius == roundRadius) return;
        Rect old = enabled && !fullScreen ? new Rect(region) : null;
        animate().cancel();
        enabled = true;
        fullScreen = false;
        roundRegion.set(value);
        roundRadius = Math.max(0f, radius);
        roundRegion.roundOut(region);
        region.intersect(0, 0, getWidth(), getHeight());
        setVisibility(VISIBLE);
        setAlpha(1f);
        invalidate();
        if (getParent() instanceof View) {
            Rect dirty = new Rect(region);
            if (old != null) dirty.union(old);
            ((View) getParent()).invalidate(dirty);
        }
    }

    void showFullScreen() {
        enabled = true;
        fullScreen = true;
        region.setEmpty();
        roundRegion.setEmpty();
        setVisibility(VISIBLE);
        setAlpha(1f);
        invalidate();
        if (getParent() instanceof View) ((View) getParent()).invalidate();
    }

    void clear() {
        animate().cancel();
        Rect dirty = fullScreen ? new Rect(0, 0, getWidth(), getHeight()) : new Rect(region);
        enabled = false;
        fullScreen = false;
        region.setEmpty();
        roundRegion.setEmpty();
        // The Shield renderer can retain the last translucent frame when an empty display list replaces it.
        // Alpha is a compositor property, and invalidating the parent repaints the wallpaper under the old region.
        // A frosted button keeps the view visible; a redraw without the dock band replaces the old frame then.
        if (buttonRegion.isEmpty()) setAlpha(0f);
        else invalidate();
        if (getParent() instanceof View && !dirty.isEmpty()) ((View) getParent()).invalidate(dirty);
    }

    /** Fades the full-screen blur in, as tvOS does when focus leaves the dock for the app grid. */
    void fadeIn(long duration) {
        if (!enabled || !fullScreen) return;
        animate().cancel();
        setAlpha(0f);
        animate().alpha(1f).setDuration(duration).start();
    }

    /** Blurs a small bitmap once (popup frost), or null when RenderScript is unavailable. The source is left alone. */
    static Bitmap blur(android.content.Context context, Bitmap small, float radius) {
        RenderScript rs = null;
        try {
            rs = RenderScript.create(context.getApplicationContext());
            Allocation in = Allocation.createFromBitmap(rs, small);
            Allocation out = Allocation.createTyped(rs, in.getType());
            ScriptIntrinsicBlur script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs));
            script.setRadius(Math.max(1f, Math.min(25f, radius)));
            script.setInput(in);
            script.forEach(out);
            Bitmap result = Bitmap.createBitmap(small.getWidth(), small.getHeight(), Bitmap.Config.ARGB_8888);
            out.copyTo(result);
            script.destroy();
            in.destroy();
            out.destroy();
            return result;
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (rs != null) rs.destroy();
        }
    }

    /** The blurred wallpaper, or null; a copy, so Launcher settings can use it as its backdrop. */
    static Bitmap copyOfLatest() {
        Bitmap b = latest;
        return b == null || b.isRecycled() ? null : b.copy(Bitmap.Config.ARGB_8888, false);
    }

    private static Bitmap latest;
    /** The latest sharper half-size wallpaper (liquid_glass), never recycled: menus may draw it right away. */
    private static Bitmap latestSharp;

    /** The sharper wallpaper the liquid glass bends at its rim, or null without liquid_glass. */
    static Bitmap latestSharp() {
        return latestSharp;
    }

    private void rebuildBlur() {
        releaseBlur();
        if (sourceRef == null || getWidth() <= 0 || getHeight() <= 0) {
            invalidate();
            return;
        }
        try {
            // A small working bitmap (1/4 to 1/10 of the view, by level) keeps the expensive RenderScript pass small
            // while the final scaled image remains visibly soft on a 1080p/4K Shield output.
            int width = Math.max(1, getWidth() / downscale(level));
            int height = Math.max(1, getHeight() / downscale(level));
            Bitmap software = sourceRef.copy(Bitmap.Config.ARGB_8888, false);
            if (software == null) return;
            Bitmap small = Bitmap.createScaledBitmap(software, width, height, true);
            if (liquid) {
                Bitmap half = Bitmap.createScaledBitmap(software, Math.max(1, getWidth() / 2),
                        Math.max(1, getHeight() / 2), true);
                // Barely blurred: the detail the glass's rim bends, sharper than its middle at most levels.
                Bitmap soft = blur(getContext(), half, 2f);
                if (soft != null && half != software) half.recycle();
                // The previous one is left to the garbage collector: a queued frame may still draw it.
                sharp = soft != null ? soft : half;
                sharpShader = new BitmapShader(sharp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                latestSharp = sharp;
            }
            if (small != software) software.recycle();
            if (radius(level) <= 0f) {
                // Level 0: clear glass, the wallpaper itself without any blur.
                blurred = small;
                latest = blurred;
                updateShader();
                invalidate();
                return;
            }
            renderScript = RenderScript.create(getContext().getApplicationContext());
            input = Allocation.createFromBitmap(renderScript, small);
            output = Allocation.createTyped(renderScript, input.getType());
            blur = ScriptIntrinsicBlur.create(renderScript, Element.U8_4(renderScript));
            blur.setRadius(radius(level));
            // Further passes blur the previous result back into the other allocation (ping-pong).
            Allocation from = input, to = output;
            for (int pass = 0; pass < passes(level); pass++) {
                blur.setInput(from);
                blur.forEach(to);
                Allocation done = to;
                to = from;
                from = done;
            }
            blurred = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            from.copyTo(blurred);
            latest = blurred;
            small.recycle();
            updateShader();
        } catch (Throwable ignored) {
            // RenderScript is deprecated but still present on API 30. If a vendor removes it, the glass tint below
            // remains a safe fallback and the launcher continues without a crash.
            releaseBlur();
        }
        invalidate();
    }

    private void releaseBlur() {
        if (blur != null) blur.destroy();
        if (input != null) input.destroy();
        if (output != null) output.destroy();
        if (renderScript != null) renderScript.destroy();
        blur = null;
        input = null;
        output = null;
        renderScript = null;
        blurPaint.setShader(null);
        if (latest == blurred) latest = null;
        if (blurred != null) blurred.recycle();
        blurred = null;
    }

    /** Stretches the small blurred bitmap over the whole view, so a shape drawn with it lines up with the wallpaper. */
    private void updateShader() {
        if (blurred == null || getWidth() <= 0 || getHeight() <= 0) {
            blurPaint.setShader(null);
            return;
        }
        BitmapShader shader = new BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        shaderMatrix.setScale(getWidth() / (float) blurred.getWidth(), getHeight() / (float) blurred.getHeight());
        shader.setLocalMatrix(shaderMatrix);
        blurPaint.setShader(shader);
        baseShader = new BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (enabled && !fullScreen) region.intersect(0, 0, w, h);
        if (sourceRef != null && (w != oldw || h != oldh)) rebuildBlur();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!fullScreen && !buttonRegion.isEmpty()) drawShape(canvas, buttonRegion, buttonRadius, buttonGlass);
        if (!enabled) return;
        if (!fullScreen && !roundRegion.isEmpty()) {
            drawShape(canvas, roundRegion, roundRadius, dockGlass);
            return;
        }
        Rect target = fullScreen ? new Rect(0, 0, getWidth(), getHeight()) : region;
        if (target.isEmpty()) return;
        canvas.save();
        canvas.clipRect(target);
        drawBlur(canvas);
        canvas.restore();
    }

    /**
     * Liquid glass, drawn live: the wallpaper frosted at the chosen level, a sharper copy bent towards
     * the centre where the lens layer lets it through (the rim), a dark shade, then the sheen and specular rim. The
     * shaders line up with the screen wherever the shape is now, so moves never wait on a render.
     */
    private void drawShape(Canvas canvas, RectF shape, float radius, Glass glass) {
        if (!drawLiquid(canvas, shape, radius, glass, 0f, 0f)) drawRoundBlur(canvas, shape, radius);
    }

    /**
     * Paints the dock band's liquid glass into another view's canvas (the grid's, from DockDecoration), in step with
     * the band on every frame wherever it scrolls. originX/Y are that canvas's origin in this view's coordinates.
     * False when there is no liquid glass to paint yet: the caller draws its own band then.
     */
    boolean paintDockGlass(Canvas canvas, RectF shape, float radius, float originX, float originY) {
        return drawLiquid(canvas, shape, radius, dockGlass, originX, originY);
    }

    private boolean drawLiquid(Canvas canvas, RectF shape, float radius, Glass glass, float originX, float originY) {
        if (!liquid || sharpShader == null || baseShader == null || blurred == null || getWidth() <= 0) return false;
        if (glass.lens == null || glass.madeRadius != radius
                || Math.abs(glass.madeW - shape.width()) >= 1f || Math.abs(glass.madeH - shape.height()) >= 1f) {
            requestLayers(shape.width(), shape.height(), radius, glass);
        }
        // Layers made for a slightly different size are stretched by those few pixels until the new ones are ready.
        if (glass.lens == null || glass.madeRadius != radius) return false;
        baseMatrix.setScale(getWidth() / (float) blurred.getWidth(), getHeight() / (float) blurred.getHeight());
        baseMatrix.postTranslate(-originX, -originY);
        baseShader.setLocalMatrix(baseMatrix);
        basePaint.setShader(baseShader);
        canvas.drawRoundRect(shape, radius, radius, basePaint);
        sharpMatrix.setScale(getWidth() / (float) sharp.getWidth(), getHeight() / (float) sharp.getHeight());
        sharpMatrix.postTranslate(-originX, -originY);
        sharpShader.setLocalMatrix(sharpMatrix);
        // The rim's bend: the picture scaled down about the shape's centre, so a point at the rim shows what lies
        // LiquidGlass.bend further in, as a thick lens edge does. The lens layer keeps it to the rim.
        float bend = LiquidGlass.bend(getResources().getDisplayMetrics().density);
        float kx = Math.max(0.5f, 1f - bend / Math.max(1f, shape.width() / 2f));
        float ky = Math.max(0.5f, 1f - bend / Math.max(1f, shape.height() / 2f));
        lensMatrix.set(sharpMatrix);
        lensMatrix.postScale(1f / kx, 1f / ky, shape.centerX(), shape.centerY());
        BitmapShader bent = new BitmapShader(sharp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        bent.setLocalMatrix(lensMatrix);
        maskMatrix.setScale(shape.width() / glass.lens.getWidth(), shape.height() / glass.lens.getHeight());
        maskMatrix.postTranslate(shape.left, shape.top);
        glass.lensShader.setLocalMatrix(maskMatrix);
        lensPaint.setShader(new android.graphics.ComposeShader(bent, glass.lensShader,
                android.graphics.PorterDuff.Mode.DST_IN));
        canvas.drawRoundRect(shape, radius, radius, lensPaint);
        canvas.drawRoundRect(shape, radius, radius, shadePaint);
        canvas.drawBitmap(glass.light, null, shape, lightPaint);
        return true;
    }

    private void requestLayers(float width, float height, float radius, Glass glass) {
        glass.wantedW = width;
        glass.wantedH = height;
        glass.wantedRadius = radius;
        if (glass.busy) return;
        glass.busy = true;
        final float density = getResources().getDisplayMetrics().density;
        GLASS_WORKER.execute(() -> {
            Bitmap[] made = LiquidGlass.layers(width, height, radius, density);
            post(() -> {
                glass.busy = false;
                if (onLayers != null) onLayers.run();
                // The old layers are left to the garbage collector: a queued frame may still draw them.
                glass.lens = made[0];
                glass.light = made[1];
                glass.lensShader = new BitmapShader(made[0], Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                glass.madeW = width;
                glass.madeH = height;
                glass.madeRadius = radius;
                // The shape may have changed size again meanwhile: the next draw asks for that one.
                invalidate();
            });
        });
    }

    private void drawRoundBlur(Canvas canvas, RectF shape, float radius) {
        if (blurred != null && blurPaint.getShader() != null) {
            canvas.drawRoundRect(shape, radius, radius, blurPaint);
            tintPaint.setColor(0x18000000);
        } else {
            tintPaint.setColor(0x2E05070C);
        }
        canvas.drawRoundRect(shape, radius, radius, tintPaint);
    }

    private void drawBlur(Canvas canvas) {
        if (blurred != null) {
            canvas.drawBitmap(blurred, null, new Rect(0, 0, getWidth(), getHeight()), paint);
            canvas.drawColor(0x18000000);
        } else {
            canvas.drawColor(0x2E05070C);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        releaseBlur();
        super.onDetachedFromWindow();
    }
}
