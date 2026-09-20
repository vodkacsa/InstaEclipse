package ps.reso.instaeclipse.mods.profile;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Expanded profile-picture renderer bypass.
 *
 * The expanded-profile container/background/translation/scale/animation are
 * intentionally left completely alone. Only the exact expanded_profile_pic
 * CircularImageView's pixels are replaced inside its own onDraw(Canvas).
 *
 * The previous build proved that X.2sN is still a circular image renderer.
 * This version therefore walks through nested drawables as well and prefers
 * the raw Bitmap backing the renderer. Drawing the Bitmap directly bypasses
 * any Drawable-level circular mask.
 */
public final class PfpRendererBypass {

    private static final String TAG = "(IE|PFPBypass) ";

    private static final Map<Drawable, RenderSource> SOURCE_CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static final Set<Class<?>> HOOKED_VIEW_CLASSES =
            Collections.synchronizedSet(new HashSet<>());

    private static final Set<View> LOGGED_VIEWS =
            Collections.newSetFromMap(new WeakHashMap<>());

    private static final Set<Drawable> LOGGED_SOURCES =
            Collections.newSetFromMap(new WeakHashMap<>());

    private static final Paint BITMAP_PAINT =
            new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);

    private static volatile boolean installed;

    private PfpRendererBypass() {}

    public static synchronized void install() {
        if (installed) return;

        XposedHelpers.findAndHookMethod(View.class, "setClipToOutline",
                boolean.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!(param.thisObject instanceof View)) return;

                        View view = (View) param.thisObject;
                        if (!isExpandedProfilePicture(view)) return;

                        if (param.args.length > 0 && Boolean.TRUE.equals(param.args[0])) {
                            param.args[0] = false;
                        }
                    }
                });

        XposedHelpers.findAndHookMethod(ImageView.class, "setImageDrawable",
                Drawable.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!(param.thisObject instanceof View)) return;

                        View view = (View) param.thisObject;
                        if (!isExpandedProfilePicture(view)) return;

                        view.post(() -> arm(view));
                    }
                });

        installed = true;
        ModuleLog.line(TAG + "installed");
    }

    public static void arm(View target) {
        if (!(target instanceof ImageView)) return;
        if (!isExpandedProfilePicture(target)) return;

        try {
            target.setClipToOutline(false);
            hookCircularOnDraw(target.getClass());

            Drawable root = ((ImageView) target).getDrawable();
            if (root != null) {
                resolveAndCache(root);
            }

            synchronized (LOGGED_VIEWS) {
                if (LOGGED_VIEWS.add(target)) {
                    ModuleLog.line(TAG + "armed view=" + target.getClass().getName()
                            + " size=" + target.getWidth() + "x" + target.getHeight()
                            + " root=" + (root == null ? "null" : root.getClass().getName()));
                }
            }

            target.invalidate();
        } catch (Throwable t) {
            ModuleLog.line(TAG + "arm failed: " + t);
        }
    }

    private static void hookCircularOnDraw(Class<?> runtimeClass) {
        if (runtimeClass == null || !HOOKED_VIEW_CLASSES.add(runtimeClass)) return;

        Method onDraw = findOnDrawMethod(runtimeClass);
        if (onDraw == null) {
            HOOKED_VIEW_CLASSES.remove(runtimeClass);
            ModuleLog.line(TAG + "no onDraw(Canvas) found for " + runtimeClass.getName());
            return;
        }

        try {
            onDraw.setAccessible(true);

            XposedBridge.hookMethod(onDraw, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof ImageView)) return;
                    if (param.args.length == 0 || !(param.args[0] instanceof Canvas)) return;

                    ImageView view = (ImageView) param.thisObject;
                    if (!isExpandedProfilePicture(view)) return;

                    Drawable root = view.getDrawable();
                    if (root == null) return;

                    RenderSource source = SOURCE_CACHE.get(root);
                    if (source == null || !source.isUsable()) {
                        source = resolveAndCache(root);
                    }

                    if (source == null || !source.isUsable()) {
                        // Keep Instagram's renderer until the real image exists.
                        return;
                    }

                    try {
                        view.setClipToOutline(false);
                        drawSource((Canvas) param.args[0], view, source);

                        // We successfully rendered the source ourselves, so skip
                        // CircularImageView.onDraw() and its circular mask.
                        param.setResult(null);
                    } catch (Throwable t) {
                        ModuleLog.line(TAG + "onDraw bypass failed: " + t);
                        // Safe fallback: do not set a result, original onDraw runs.
                    }
                }
            });

            ModuleLog.line(TAG + "hooked "
                    + onDraw.getDeclaringClass().getName()
                    + ".onDraw(Canvas) for runtime class "
                    + runtimeClass.getName());
        } catch (Throwable t) {
            HOOKED_VIEW_CLASSES.remove(runtimeClass);
            ModuleLog.line(TAG + "onDraw hook failed for "
                    + runtimeClass.getName() + ": " + t);
        }
    }

    private static RenderSource resolveAndCache(Drawable root) {
        RenderSource source = findBestRenderSource(root);
        if (source == null || !source.isUsable()) return null;

        SOURCE_CACHE.put(root, source);
        logSource(root, source);
        return source;
    }

    /**
     * Search through the full renderer graph, including nested Drawable objects.
     * Raw Bitmap candidates always outrank Drawable candidates because drawing
     * the bitmap directly cannot execute another custom circular draw() method.
     */
    private static RenderSource findBestRenderSource(Drawable root) {
        RenderSource best = new RenderSource();
        IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
        scanObject(root, root, "root", 0, visited, best);
        return best.isUsable() ? best : null;
    }

    private static void scanObject(
            Object object,
            Drawable root,
            String path,
            int depth,
            IdentityHashMap<Object, Boolean> visited,
            RenderSource best
    ) {
        if (object == null || depth > 10) return;
        if (visited.put(object, Boolean.TRUE) != null) return;

        if (object instanceof Bitmap) {
            considerBitmap((Bitmap) object, path, depth, best);
            return;
        }

        if (object instanceof BitmapDrawable) {
            try {
                Bitmap bitmap = ((BitmapDrawable) object).getBitmap();
                if (bitmap != null) {
                    considerBitmap(bitmap, path + ".getBitmap()", depth + 1, best);
                }
            } catch (Throwable ignored) {}
        }

        if (object instanceof Drawable && object != root) {
            considerDrawable((Drawable) object, path, depth, best);
        }

        if (object instanceof Paint) {
            try {
                Shader shader = ((Paint) object).getShader();
                if (shader != null) {
                    scanObject(shader, root, path + ".shader", depth + 1, visited, best);
                }
            } catch (Throwable ignored) {}
        }

        Class<?> cls = object.getClass();

        while (cls != null && cls != Object.class) {
            // For Drawable subclasses we want subclass fields, but not the
            // framework Drawable base callbacks/state.
            if (cls == Drawable.class) break;

            Field[] fields;
            try {
                fields = cls.getDeclaredFields();
            } catch (Throwable t) {
                cls = cls.getSuperclass();
                continue;
            }

            for (Field field : fields) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                if (field.isSynthetic()) continue;

                Object value;
                try {
                    field.setAccessible(true);
                    value = field.get(object);
                } catch (Throwable ignored) {
                    continue;
                }

                if (value == null || value == object) continue;

                String childPath = path + "." + field.getName();

                if (value instanceof Bitmap) {
                    considerBitmap((Bitmap) value, childPath, depth + 1, best);
                    continue;
                }

                if (value instanceof Drawable) {
                    Drawable drawable = (Drawable) value;

                    if (drawable != root) {
                        considerDrawable(drawable, childPath, depth + 1, best);
                    }

                    // Critical difference from the previous build: keep walking
                    // inside nested Drawables instead of stopping at X.2sN.
                    scanObject(drawable, root, childPath, depth + 1, visited, best);
                    continue;
                }

                if (shouldTraverse(value)) {
                    scanObject(value, root, childPath, depth + 1, visited, best);
                }
            }

            cls = cls.getSuperclass();
        }
    }

    private static void considerBitmap(
            Bitmap bitmap,
            String path,
            int depth,
            RenderSource best
    ) {
        if (bitmap == null || bitmap.isRecycled()) return;

        int w;
        int h;
        try {
            w = bitmap.getWidth();
            h = bitmap.getHeight();
        } catch (Throwable t) {
            return;
        }

        if (w <= 1 || h <= 1) return;

        long area = (long) w * (long) h;
        int score = 1_000_000
                + (int) Math.min(700_000L, area / 2L)
                + Math.min(depth, 20) * 1000;

        // Profile images are normally near-square. Prefer them over tiny helper
        // bitmaps or unrelated rectangular assets in the renderer graph.
        float ratio = w / (float) h;
        if (ratio > 0.75f && ratio < 1.3334f) {
            score += 100_000;
        }

        if (score <= best.score) return;

        best.score = score;
        best.bitmap = bitmap;
        best.drawable = null;
        best.path = path;
        best.kind = "bitmap";
        best.width = w;
        best.height = h;
    }

    private static void considerDrawable(
            Drawable drawable,
            String path,
            int depth,
            RenderSource best
    ) {
        if (drawable == null) return;

        int w;
        int h;
        try {
            w = drawable.getIntrinsicWidth();
            h = drawable.getIntrinsicHeight();
        } catch (Throwable t) {
            return;
        }

        if (w <= 0 || h <= 0) return;

        // Always rank below any raw Bitmap candidate. If no Bitmap is exposed,
        // prefer deeper leaf renderers over the outer X.2sN wrapper.
        int score = 100_000
                + Math.min(50_000, Math.min(w, h))
                + Math.min(depth, 20) * 5000;

        if (score <= best.score) return;

        best.score = score;
        best.bitmap = null;
        best.drawable = drawable;
        best.path = path;
        best.kind = "drawable";
        best.width = w;
        best.height = h;
    }

    private static void drawSource(Canvas canvas, ImageView view, RenderSource source) {
        int left = view.getPaddingLeft();
        int top = view.getPaddingTop();
        int right = view.getWidth() - view.getPaddingRight();
        int bottom = view.getHeight() - view.getPaddingBottom();

        if (right <= left || bottom <= top) return;

        Rect dst = fitCenter(
                left,
                top,
                right,
                bottom,
                source.width,
                source.height
        );

        if (source.bitmap != null && !source.bitmap.isRecycled()) {
            canvas.drawBitmap(
                    source.bitmap,
                    null,
                    new RectF(dst),
                    BITMAP_PAINT
            );
            return;
        }

        if (source.drawable != null) {
            Rect oldBounds = new Rect(source.drawable.getBounds());
            try {
                source.drawable.setBounds(dst);
                source.drawable.draw(canvas);
            } finally {
                source.drawable.setBounds(oldBounds);
            }
        }
    }

    private static Rect fitCenter(
            int left,
            int top,
            int right,
            int bottom,
            int sourceW,
            int sourceH
    ) {
        int boxW = right - left;
        int boxH = bottom - top;

        if (sourceW <= 0 || sourceH <= 0) {
            return new Rect(left, top, right, bottom);
        }

        float scale = Math.min(
                boxW / (float) sourceW,
                boxH / (float) sourceH
        );

        int width = Math.max(1, Math.round(sourceW * scale));
        int height = Math.max(1, Math.round(sourceH * scale));
        int x = left + (boxW - width) / 2;
        int y = top + (boxH - height) / 2;

        return new Rect(x, y, x + width, y + height);
    }

    private static void logSource(Drawable root, RenderSource source) {
        synchronized (LOGGED_SOURCES) {
            if (!LOGGED_SOURCES.add(root)) return;
        }

        String extra = "";
        if (source.bitmap != null) {
            extra = " config=" + source.bitmap.getConfig()
                    + " cornerAlpha=" + bitmapCornerAlpha(source.bitmap);
        }

        ModuleLog.line(TAG + "source=" + source.kind
                + " type=" + source.typeName()
                + " path=" + source.path
                + " size=" + source.width + "x" + source.height
                + extra);
    }

    private static String bitmapCornerAlpha(Bitmap bitmap) {
        try {
            int w = bitmap.getWidth();
            int h = bitmap.getHeight();

            int a1 = Color.alpha(bitmap.getPixel(0, 0));
            int a2 = Color.alpha(bitmap.getPixel(w - 1, 0));
            int a3 = Color.alpha(bitmap.getPixel(0, h - 1));
            int a4 = Color.alpha(bitmap.getPixel(w - 1, h - 1));

            return a1 + "," + a2 + "," + a3 + "," + a4;
        } catch (Throwable t) {
            return "unavailable";
        }
    }

    private static boolean shouldTraverse(Object value) {
        if (value == null) return false;

        if (value instanceof View
                || value instanceof CharSequence
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Character
                || value.getClass().isEnum()
                || value.getClass().isArray()) {
            return false;
        }

        String name = value.getClass().getName();

        return name.startsWith("X.")
                || name.startsWith("com.instagram.")
                || name.startsWith("android.graphics.drawable.")
                || name.startsWith("android.graphics.BitmapShader")
                || name.startsWith("android.graphics.Shader")
                || name.startsWith("android.graphics.Paint");
    }

    private static Method findOnDrawMethod(Class<?> start) {
        Class<?> cls = start;

        while (cls != null && cls != Object.class) {
            try {
                return cls.getDeclaredMethod("onDraw", Canvas.class);
            } catch (NoSuchMethodException ignored) {
                cls = cls.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }

        return null;
    }

    private static boolean isExpandedProfilePicture(View view) {
        if (view == null || view.getId() == View.NO_ID) return false;

        try {
            return "expanded_profile_pic".equals(
                    view.getResources().getResourceEntryName(view.getId()));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static final class RenderSource {
        Bitmap bitmap;
        Drawable drawable;
        String path;
        String kind;
        int width;
        int height;
        int score = Integer.MIN_VALUE;

        boolean isUsable() {
            if (bitmap != null) {
                try {
                    return !bitmap.isRecycled() && bitmap.getWidth() > 0 && bitmap.getHeight() > 0;
                } catch (Throwable ignored) {
                    return false;
                }
            }

            return drawable != null;
        }

        String typeName() {
            if (bitmap != null) return bitmap.getClass().getName();
            if (drawable != null) return drawable.getClass().getName();
            return "null";
        }
    }
}
