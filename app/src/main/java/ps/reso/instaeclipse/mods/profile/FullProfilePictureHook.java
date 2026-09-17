package ps.reso.instaeclipse.mods.profile;

import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;

/** Render the original image drawable fitted inside the view, without the circular mask. */
public final class FullProfilePictureHook {
    private static final Map<View, Boolean> originalOutline = new WeakHashMap<>();
    private static final Set<Integer> profileIds = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Set<Integer> otherIds = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public static void install(ClassLoader loader) throws ClassNotFoundException {
        Class<?> circular = loader.loadClass("com.instagram.common.ui.widget.imageview.CircularImageView");
        Method draw = XposedHelpers.findMethodExact(circular, "onDraw", Canvas.class);
        XposedBridge.hookMethod(draw, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!(p.thisObject instanceof ImageView)) return;
                ImageView view = (ImageView) p.thisObject;
                if (!isProfilePicture(view)) return;
                if (!FeatureFlags.fullProfilePictures) {
                    Boolean outline = originalOutline.remove(view);
                    if (outline != null) view.setClipToOutline(outline);
                    return;
                }
                Drawable image = view.getDrawable();
                if (image == null || image.getIntrinsicWidth() <= 0 || image.getIntrinsicHeight() <= 0) return;
                int width = view.getWidth() - view.getPaddingLeft() - view.getPaddingRight();
                int height = view.getHeight() - view.getPaddingTop() - view.getPaddingBottom();
                if (width <= 0 || height <= 0) return;
                Canvas canvas = (Canvas) p.args[0];
                Rect oldBounds = new Rect(image.getBounds());
                int saved = canvas.save();
                try {
                    float scale = Math.min((float) width / image.getIntrinsicWidth(), (float) height / image.getIntrinsicHeight());
                    float w = image.getIntrinsicWidth() * scale, h = image.getIntrinsicHeight() * scale;
                    canvas.translate(view.getPaddingLeft() + (width - w) / 2,
                            view.getPaddingTop() + (height - h) / 2);
                    canvas.scale(scale, scale);
                    image.setBounds(0, 0, image.getIntrinsicWidth(), image.getIntrinsicHeight());
                    image.draw(canvas);
                    if (!originalOutline.containsKey(view)) originalOutline.put(view, view.getClipToOutline());
                    if (view.getClipToOutline()) view.setClipToOutline(false);
                    p.setResult(null); // Skip only this avatar's circular onDraw, not other images.
                } catch (Throwable ignored) {
                    // Let Instagram draw normally if its drawable is incompatible.
                } finally {
                    image.setBounds(oldBounds);
                    canvas.restoreToCount(saved);
                }
            }
        });
        FeatureStatusTracker.setHooked("FullProfilePictures");
    }

    private static boolean isProfilePicture(View view) {
        int id = view.getId();
        if (id == View.NO_ID || otherIds.contains(id)) return false;
        if (profileIds.contains(id)) return true;
        try {
            String name = view.getResources().getResourceEntryName(id);
            boolean profile = name.equals("expanded_profile_pic") || name.equals("row_profile_header_imageview")
                    || name.equals("profile_header_imageview");
            (profile ? profileIds : otherIds).add(id);
            return profile;
        } catch (Throwable ignored) { return false; }
    }
}
