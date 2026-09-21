package ps.reso.instaeclipse.mods.profile;

import android.graphics.Bitmap;
import android.graphics.Canvas;
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

/**
 * Expanded profile-picture renderer bypass.
 *
 * Leaves Instagram's expanded-profile container, background, positioning and
 * animation untouched. Only the exact expanded_profile_pic CircularImageView
 * is changed: its raw backing bitmap is drawn directly inside onDraw(Canvas),
 * bypassing Instagram's circular drawable/view masking.
 */
public final class PfpRendererBypass {

    private static final Map<Drawable, RenderSource> SOURCE_CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static final Set<Class<?>> HOOKED_VIEW_CLASSES =
            Collections.synchronizedSet(new HashSet<>());

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

            target.invalidate();
        } catch (Throwable ignored) {}
    }

    private static void hookCircularOnDraw(Class<?> runtimeClass) {
        if (runtimeClass == null || !HOOKED_VIEW_CLASSES.add(runtimeClass)) return;

        Method onDraw = findOnDrawMethod(runtimeClass);
        if (onDraw == null) {
            HOOKED_VIEW_CLASSES.remove(runtimeClass);
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
                        return;
                    }

                    try {
                        view.setClipToOutline(false);
                        drawSource((Canvas) param.args[0], view, source);
                        param.setResult(null);
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable ignored) {
            HOOKED_VIEW_CLASSES.remove(runtimeClass);
        }
    }

    private static RenderSource resolveAndCache(Drawable root) {
        RenderSource source = findBestRenderSource(root);
        if (source == null || !source.isUsable()) return null;

        SOURCE_CACHE.put(root, source);
        return source;
    }

    private static RenderSource findBestRenderSource(Drawable root) {
        RenderSource best = new RenderSource();
        IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
        scanObject(root, root, 0, visited, best);
        return best.isUsable() ? best : null;
    }

    private static void scanObject(
            Object object,
            Drawable root,
            int depth,
            IdentityHashMap<Object, Boolean> visited,
            RenderSource best
    ) {
        if (object == null || depth > 10) return;
        if (visited.put(object, Boolean.TRUE) != null) return;

        if (object instanceof Bitmap) {
            considerBitmap((Bitmap) object, depth, best);
            return;
        }

        if (object instanceof BitmapDrawable) {
            try {
                Bitmap bitmap = ((BitmapDrawable) object).getBitmap();
                if (bitmap != null) {
                    considerBitmap(bitmap, depth + 1, best);
                }
            } catch (Throwable ignored) {}
        }

        if (object instanceof Drawable && object != root) {
            considerDrawable((Drawable) object, depth, best);
        }

        if (object instanceof Paint) {
            try {
                Shader shader = ((Paint) object).getShader();
                if (shader != null) {
                    scanObject(shader, root, depth + 1, visited, best);
                }
            } catch (Throwable ignored) {}
        }

        Class<?> cls = object.getClass();

        while (cls != null && cls != Object.class) {
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

                if (value instanceof Bitmap) {
                    considerBitmap((Bitmap) value, depth + 1, best);
                    continue;
                }

                if (value instanceof Drawable) {
                    Drawable drawable = (Drawable) value;

                    if (drawable != root) {
                        considerDrawable(drawable, depth + 1, best);
                    }

                    scanObject(drawable, root, depth + 1, visited, best);
                    continue;
                }

                if (shouldTraverse(value)) {
                    scanObject(value, root, depth + 1, visited, best);
                }
            }

            cls = cls.getSuperclass();
        }
    }

    private static void considerBitmap(
            Bitmap bitmap,
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

        float ratio = w / (float) h;
        if (ratio > 0.75f && ratio < 1.3334f) {
            score += 100_000;
        }

        if (score <= best.score) return;

        best.score = score;
        best.bitmap = bitmap;
        best.drawable = null;
        best.width = w;
        best.height = h;
    }

    private static void considerDrawable(
            Drawable drawable,
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

        int score = 100_000
                + Math.min(50_000, Math.min(w, h))
                + Math.min(depth, 20) * 5000;

        if (score <= best.score) return;

        best.score = score;
        best.bitmap = null;
        best.drawable = drawable;
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
        int width;
        int height;
        int score = Integer.MIN_VALUE;

        boolean isUsable() {
            if (bitmap != null) {
                try {
                    return !bitmap.isRecycled()
                            && bitmap.getWidth() > 0
                            && bitmap.getHeight() > 0;
                } catch (Throwable ignored) {
                    return false;
                }
            }

            return drawable != null;
        }
    }
}
