package ps.reso.instaeclipse.mods.profile;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.mods.media.ProfilePicDownloadHook;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Personal-fork profile picture uncrop.
 *
 * The existing downloader already gives us a stable target:
 * resource name "expanded_profile_pic". That view's hit box is square even though
 * Instagram renders the image as a circle, so the crop is happening inside the
 * view/drawable rather than in the surrounding layout.
 *
 * For that exact view only, capture the original bitmap and replace its onDraw()
 * with a normal FIT_CENTER bitmap draw. No overlay, duplicate ImageView, or
 * network request is involved.
 */
public final class ProfilePicUncropHook {

    private static final String TAG = "(IE|ProfileUncrop) ";

    private static final Map<View, Bitmap> BITMAPS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static final Set<Method> HOOKED_ON_DRAW =
            Collections.synchronizedSet(new HashSet<>());

    private static final Set<Class<?>> LOGGED_TARGET_CLASSES =
            Collections.synchronizedSet(new HashSet<>());

    private static volatile boolean installed;

    private ProfilePicUncropHook() {}

    public static synchronized void install(ClassLoader classLoader) {
        if (installed) return;

        // Catch the exact profile view whenever it enters the hierarchy. This also
        // handles images that were populated before our ImageView setter hooks saw them.
        XposedHelpers.findAndHookMethod(View.class, "onAttachedToWindow", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!(param.thisObject instanceof View)) return;
                View view = (View) param.thisObject;
                if (!ProfilePicDownloadHook.isExpandedProfilePicture(view)) return;
                arm(view);
            }
        });

        // Capture the uncropped source before/after Instagram replaces it with its
        // circular drawable. Hook ImageView itself so this survives changes to IG's
        // CircularImageView implementation.
        XposedHelpers.findAndHookMethod(ImageView.class, "setImageBitmap", Bitmap.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!(param.thisObject instanceof View)) return;
                        View view = (View) param.thisObject;
                        if (!ProfilePicDownloadHook.isExpandedProfilePicture(view)) return;
                        if (param.args[0] instanceof Bitmap) {
                            remember(view, (Bitmap) param.args[0], "setImageBitmap");
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.thisObject instanceof View) {
                            View view = (View) param.thisObject;
                            if (ProfilePicDownloadHook.isExpandedProfilePicture(view)) arm(view);
                        }
                    }
                });

        XposedHelpers.findAndHookMethod(ImageView.class, "setImageDrawable", Drawable.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!(param.thisObject instanceof View)) return;
                        View view = (View) param.thisObject;
                        if (!ProfilePicDownloadHook.isExpandedProfilePicture(view)) return;

                        if (param.args[0] instanceof Drawable) {
                            Bitmap bitmap = bitmapFromDrawable((Drawable) param.args[0], 0);
                            if (bitmap != null) remember(view, bitmap, "setImageDrawable");
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.thisObject instanceof View) {
                            View view = (View) param.thisObject;
                            if (ProfilePicDownloadHook.isExpandedProfilePicture(view)) arm(view);
                        }
                    }
                });

        installed = true;
        ModuleLog.line(TAG + "installed using existing expanded_profile_pic target");
    }

    private static void arm(View view) {
        try {
            view.setClipToOutline(false);

            if (view instanceof ImageView) {
                Drawable current = ((ImageView) view).getDrawable();
                Bitmap bitmap = bitmapFromDrawable(current, 0);
                if (bitmap != null) remember(view, bitmap, "current drawable");
            }

            hookActualOnDraw(view.getClass());
            view.invalidate();

            if (LOGGED_TARGET_CLASSES.add(view.getClass())) {
                ModuleLog.line(TAG + "target=" + view.getClass().getName()
                        + " size=" + view.getWidth() + "x" + view.getHeight()
                        + " drawable=" + (view instanceof ImageView
                        && ((ImageView) view).getDrawable() != null
                        ? ((ImageView) view).getDrawable().getClass().getName()
                        : "null"));
            }
        } catch (Throwable t) {
            ModuleLog.line(TAG + "arm failed: " + t);
        }
    }

    /**
     * Hook the first concrete onDraw(Canvas) implementation in the target view's
     * inheritance chain. This avoids assuming CircularImageView still owns onDraw
     * in every Instagram version.
     */
    private static void hookActualOnDraw(Class<?> start) {
        Class<?> cls = start;
        while (cls != null && View.class.isAssignableFrom(cls)) {
            try {
                Method method = cls.getDeclaredMethod("onDraw", Canvas.class);
                if (Modifier.isAbstract(method.getModifiers())) {
                    cls = cls.getSuperclass();
                    continue;
                }

                if (!HOOKED_ON_DRAW.add(method)) return;
                method.setAccessible(true);

                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!(param.thisObject instanceof View)) return;
                        if (param.args.length == 0 || !(param.args[0] instanceof Canvas)) return;

                        View view = (View) param.thisObject;
                        if (!ProfilePicDownloadHook.isExpandedProfilePicture(view)) return;

                        Bitmap bitmap = BITMAPS.get(view);
                        if ((bitmap == null || bitmap.isRecycled()) && view instanceof ImageView) {
                            bitmap = bitmapFromDrawable(((ImageView) view).getDrawable(), 0);
                            if (bitmap != null) remember(view, bitmap, "onDraw fallback");
                        }

                        if (bitmap == null || bitmap.isRecycled()) return;

                        drawFullBitmap(view, (Canvas) param.args[0], bitmap);
                        param.setResult(null);
                    }
                });

                ModuleLog.line(TAG + "hooked onDraw at " + cls.getName());
                return;
            } catch (NoSuchMethodException ignored) {
                cls = cls.getSuperclass();
            } catch (Throwable t) {
                ModuleLog.line(TAG + "onDraw hook failed at " + cls.getName() + ": " + t);
                return;
            }
        }

        ModuleLog.line(TAG + "no concrete onDraw found for " + start.getName());
    }

    private static void remember(View view, Bitmap bitmap, String source) {
        if (bitmap == null || bitmap.isRecycled()) return;
        Bitmap old = BITMAPS.put(view, bitmap);
        if (old != bitmap) {
            ModuleLog.line(TAG + "bitmap " + bitmap.getWidth() + "x" + bitmap.getHeight()
                    + " from " + source);
        }
    }

    private static void drawFullBitmap(View view, Canvas canvas, Bitmap bitmap) {
        float left = view.getPaddingLeft();
        float top = view.getPaddingTop();
        float right = view.getWidth() - view.getPaddingRight();
        float bottom = view.getHeight() - view.getPaddingBottom();

        float availableW = Math.max(0f, right - left);
        float availableH = Math.max(0f, bottom - top);
        if (availableW <= 0f || availableH <= 0f
                || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) return;

        float scale = Math.min(
                availableW / bitmap.getWidth(),
                availableH / bitmap.getHeight()
        );

        float width = bitmap.getWidth() * scale;
        float height = bitmap.getHeight() * scale;
        float x = left + (availableW - width) / 2f;
        float y = top + (availableH - height) / 2f;

        RectF dst = new RectF(x, y, x + width, y + height);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(bitmap, null, dst, paint);
    }

    /**
     * Pull the underlying source bitmap out of Instagram's drawable wrappers.
     * Circular drawables normally retain the original bitmap and only mask it
     * while drawing, which is exactly what we want to bypass.
     */
    private static Bitmap bitmapFromDrawable(Drawable drawable, int depth) {
        if (drawable == null || depth > 6) return null;

        if (drawable instanceof BitmapDrawable) {
            Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
            if (bitmap != null && !bitmap.isRecycled()) return bitmap;
        }

        try {
            Drawable current = drawable.getCurrent();
            if (current != null && current != drawable) {
                Bitmap bitmap = bitmapFromDrawable(current, depth + 1);
                if (bitmap != null) return bitmap;
            }
        } catch (Throwable ignored) {}

        Class<?> cls = drawable.getClass();
        while (cls != null && Drawable.class.isAssignableFrom(cls)) {
            Field[] fields;
            try {
                fields = cls.getDeclaredFields();
            } catch (Throwable t) {
                cls = cls.getSuperclass();
                continue;
            }

            for (Field field : fields) {
                if (Modifier.isStatic(field.getModifiers())) continue;

                try {
                    field.setAccessible(true);
                    Object value = field.get(drawable);

                    if (value instanceof Bitmap) {
                        Bitmap bitmap = (Bitmap) value;
                        if (!bitmap.isRecycled()) return bitmap;
                    }

                    if (value instanceof Drawable && value != drawable) {
                        Bitmap bitmap = bitmapFromDrawable((Drawable) value, depth + 1);
                        if (bitmap != null) return bitmap;
                    }
                } catch (Throwable ignored) {}
            }

            cls = cls.getSuperclass();
        }

        return null;
    }
}
