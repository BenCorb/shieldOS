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
    /** Counts rebuildBlur() calls: a blur made on the worker for an older one is dropped instead of shown. */
    private int generation;
    /** The view size the latest blur was asked for; onSizeChanged rebuilds only for another one. */
    private int builtW, builtH;
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
    /**
     * The glass's one background thread: blurs (backdrop and menus) and LiquidGlass layers, in the order asked. Its
     * RenderScript context is made once, on first use, and only ever used from this thread.
     */
    static final java.util.concurrent.ExecutorService GLASS_WORKER =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private static RenderScript workerRs;
    private static ScriptIntrinsicBlur workerBlur;
    /** RenderScript could not start on this TV: blur() returns null at once, and the glass tint is the fallback. */
    private static boolean workerRsFailed;

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
        // Already clear: nothing to repaint. Liquid glass asks for this at every frame the dock band moves, and the
        // invalidate below then redrew this full-screen view each time for the Settings button's glass.
        if (!enabled && !fullScreen && region.isEmpty()) return;
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

    /**
     * Blurs a small bitmap (backdrop, popup frost), or null when RenderScript is unavailable. The source is left
     * alone. GLASS_WORKER only: the RenderScript context it keeps is not shared with any other thread.
     */
    static Bitmap blur(android.content.Context context, Bitmap small, float radius) {
        if (workerRsFailed) return null;
        Allocation in = null, out = null;
        try {
            if (workerRs == null) {
                RenderScript rs = RenderScript.create(context.getApplicationContext());
                try {
                    workerBlur = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs));
                } catch (Throwable t) {
                    rs.destroy();
                    throw t;
                }
                workerRs = rs;
            }
            in = Allocation.createFromBitmap(workerRs, small);
            out = Allocation.createTyped(workerRs, in.getType());
            workerBlur.setRadius(Math.max(1f, Math.min(25f, radius)));
            workerBlur.setInput(in);
            workerBlur.forEach(out);
            Bitmap result = Bitmap.createBitmap(small.getWidth(), small.getHeight(), Bitmap.Config.ARGB_8888);
            out.copyTo(result);
            return result;
        } catch (Throwable ignored) {
            // RenderScript is deprecated but still present on API 30. If a vendor removes it, the glass tint
            // remains a safe fallback and the launcher continues without a crash.
            if (workerRs == null) workerRsFailed = true;
            return null;
        } finally {
            if (in != null) in.destroy();
            if (out != null) out.destroy();
        }
    }

    /**
     * The blurred wallpaper, or null. It is never recycled or changed once made (a newer blur replaces it), so
     * Launcher settings and the menus can draw it as it is.
     */
    static Bitmap latestBlur() {
        Bitmap b = latest;
        return b == null || b.isRecycled() ? null : b;
    }

    private static Bitmap latest;
    /** The latest sharper half-size wallpaper (liquid_glass), never recycled: menus may draw it right away. */
    private static Bitmap latestSharp;

    /** The sharper wallpaper the liquid glass bends at its rim, or null without liquid_glass. */
    static Bitmap latestSharp() {
        return latestSharp;
    }

    /**
     * Makes the blurred (and, for liquid_glass, the sharper) wallpaper on GLASS_WORKER and shows it once ready, so
     * neither Home's first frame nor a wallpaper change waits on it. The glass shown meanwhile stays: the previous
     * blur, or the plain band and tint before the first one.
     */
    private void rebuildBlur() {
        final int gen = ++generation;
        final Bitmap source = sourceRef;
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        // Before the first layout the view's size is not known yet: it fills the screen, so start with that.
        final int w = getWidth() > 0 ? getWidth() : dm.widthPixels;
        final int h = getHeight() > 0 ? getHeight() : dm.heightPixels;
        builtW = w;
        builtH = h;
        if (source == null || w <= 0 || h <= 0) {
            releaseBlur();
            invalidate();
            return;
        }
        final int lvl = level;
        final boolean wantSharp = liquid;
        final android.content.Context app = getContext().getApplicationContext();
        GLASS_WORKER.execute(() -> {
            Bitmap[] made = makeBlur(app, source, w, h, lvl, wantSharp);
            post(() -> {
                if (gen != generation || made == null) return;
                installBlur(made[0], made[1]);
            });
        });
    }

    /** GLASS_WORKER: the blurred picture at the level's size, and the sharper half-size one (or null); null on failure. */
    private static Bitmap[] makeBlur(android.content.Context context, Bitmap source, int viewW, int viewH, int level,
                                     boolean wantSharp) {
        try {
            if (source.isRecycled()) return null;
            // A small working bitmap (1/4 to 1/10 of the view, by level) keeps the expensive RenderScript pass small
            // while the final scaled image remains visibly soft on a 1080p/4K Shield output. The wallpaper is a
            // hardware bitmap: read it back once, here, off the UI thread.
            int width = Math.max(1, viewW / downscale(level));
            int height = Math.max(1, viewH / downscale(level));
            Bitmap software = source.getConfig() == Bitmap.Config.HARDWARE
                    ? source.copy(Bitmap.Config.ARGB_8888, false) : source;
            if (software == null) return null;
            Bitmap small = Bitmap.createScaledBitmap(software, width, height, true);
            Bitmap sharp = null;
            if (wantSharp) {
                Bitmap half = Bitmap.createScaledBitmap(software, Math.max(1, viewW / 2), Math.max(1, viewH / 2), true);
                // Barely blurred: the detail the glass's rim bends, sharper than its middle at most levels.
                Bitmap soft = blur(context, half, 2f);
                if (soft != null && half != software) half.recycle();
                sharp = soft != null ? soft : half;
            }
            if (software != source && small != software && sharp != software) software.recycle();
            if (radius(level) <= 0f) return new Bitmap[]{small, sharp};   // level 0: clear glass, no blur at all
            Bitmap result = blur(context, small, radius(level));
            if (result == null) return null;
            if (small != software && small != source) small.recycle();
            return new Bitmap[]{result, sharp};
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** UI thread: shows a blur made by makeBlur. The previous bitmaps are left to the GC: a queued frame may draw them. */
    private void installBlur(Bitmap newBlurred, Bitmap newSharp) {
        blurred = newBlurred;
        latest = newBlurred;
        if (newSharp != null) {
            sharp = newSharp;
            sharpShader = new BitmapShader(sharp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            latestSharp = sharp;
        }
        updateShader();
        invalidate();
        // The dock's liquid glass is painted by the grid: it redraws with the new pictures too.
        if (onLayers != null) onLayers.run();
    }

    private void releaseBlur() {
        blurPaint.setShader(null);
        if (latest == blurred) latest = null;
        // Not recycled: Launcher settings or a menu may still draw it (latestBlur), and a queued frame too.
        blurred = null;
        baseShader = null;
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
        if (sourceRef == null) return;
        // The blur started before the first layout was made for this size already: it only needs lining up.
        if (w != builtW || h != builtH) rebuildBlur();
        else updateShader();
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
        generation++;   // a blur still being made is not shown
        releaseBlur();
        super.onDetachedFromWindow();
    }
}
