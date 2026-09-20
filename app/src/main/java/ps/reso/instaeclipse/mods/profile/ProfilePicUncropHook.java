package ps.reso.instaeclipse.mods.profile;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.mods.media.ProfilePicDownloadHook;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Personal-fork profile picture uncrop.
 *
 * Uses the exact same "expanded_profile_pic" view that the existing profile-picture
 * downloader already targets. Instagram's CircularImageView wraps the original bitmap in
 * a circular drawable; we remember that original bitmap and replace only that drawable's
 * draw() call for the expanded profile picture.
 *
 * No extra overlay, no duplicate ImageView, no network request.
 */
public final class ProfilePicUncropHook {

    private static final String TAG = "(IE|ProfileUncrop) ";

    private static final Map<Drawable, Bitmap> TARGETS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static final Set<Class<?>> HOOKED_DRAWABLE_CLASSES =
            Collections.synchronizedSet(new HashSet<>());

    private static volatile boolean installed;

    private ProfilePicUncropHook() {}

    public static synchronized void install(ClassLoader classLoader) {
        if (installed) return;

        Class<?> circular = XposedHelpers.findClassIfExists(
                "com.instagram.common.ui.widget.imageview.CircularImageView",
                classLoader
        );

        if (circular == null) {
            ModuleLog.line(TAG + "CircularImageView class not found");
            return;
        }

        XposedBridge.hookAllMethods(circular, "setImageBitmap", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!(param.thisObject instanceof View)) return;
                if (param.args.length == 0 || !(param.args[0] instanceof Bitmap)) return;

                View view = (View) param.thisObject;
                if (!ProfilePicDownloadHook.isExpandedProfilePicture(view)) return;
                if (!(view instanceof ImageView)) return;

                Bitmap bitmap = (Bitmap) param.args[0];
                Drawable drawable = ((ImageView) view).getDrawable();
                if (drawable == null || bitmap.isRecycled()) return;

                TARGETS.put(drawable, bitmap);
                hookDrawableClass(drawable.getClass());

                // Keep Android from applying an outline clip on top of the drawable.
                try {
                    view.setClipToOutline(false);
                } catch (Throwable ignored) {}

                view.invalidate();
            }
        });

        installed = true;
        ModuleLog.line(TAG + "installed using existing expanded_profile_pic target");
    }

    private static void hookDrawableClass(Class<?> drawableClass) {
        if (drawableClass == null || !HOOKED_DRAWABLE_CLASSES.add(drawableClass)) return;

        try {
            XposedBridge.hookAllMethods(drawableClass, "draw", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof Drawable)) return;
                    if (param.args.length == 0 || !(param.args[0] instanceof Canvas)) return;

                    Drawable drawable = (Drawable) param.thisObject;
                    Bitmap bitmap = TARGETS.get(drawable);
                    if (bitmap == null || bitmap.isRecycled()) return;

                    Canvas canvas = (Canvas) param.args[0];
                    Rect bounds = drawable.getBounds();
                    if (bounds.isEmpty()) return;

                    RectF dst = fitCenter(bounds, bitmap.getWidth(), bitmap.getHeight());

                    Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                    paint.setAlpha(drawable.getAlpha());
                    paint.setColorFilter(drawable.getColorFilter());

                    canvas.drawBitmap(bitmap, null, dst, paint);
                    param.setResult(null);
                }
            });

            ModuleLog.line(TAG + "hooked drawable " + drawableClass.getName());
        } catch (Throwable t) {
            HOOKED_DRAWABLE_CLASSES.remove(drawableClass);
            ModuleLog.line(TAG + "drawable hook failed for " + drawableClass.getName() + ": " + t);
        }
    }

    private static RectF fitCenter(Rect bounds, int bitmapWidth, int bitmapHeight) {
        if (bitmapWidth <= 0 || bitmapHeight <= 0) {
            return new RectF(bounds);
        }

        float scale = Math.min(
                bounds.width() / (float) bitmapWidth,
                bounds.height() / (float) bitmapHeight
        );

        float width = bitmapWidth * scale;
        float height = bitmapHeight * scale;

        float left = bounds.left + (bounds.width() - width) / 2f;
        float top = bounds.top + (bounds.height() - height) / 2f;

        return new RectF(left, top, left + width, top + height);
    }
}
