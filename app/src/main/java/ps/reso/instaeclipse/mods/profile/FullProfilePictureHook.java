package ps.reso.instaeclipse.mods.profile;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/** Bypass the circular drawable as well as the circular view's onDraw. */
public final class FullProfilePictureHook {
    private static final Set<Method> hooked = ConcurrentHashMap.newKeySet();
    private static final Set<String> reported = ConcurrentHashMap.newKeySet();

    public static void install(ClassLoader loader) {
        int found = 0;
        for (String name : new String[]{"com.instagram.common.ui.widget.imageview.CircularImageView",
                "com.instagram.common.ui.widget.imageview.IgImageView"}) {
            try { installView(loader.loadClass(name)); found++; }
            catch (Throwable t) { ModuleLog.line("(IE|FullProfilePictures) " + name + ": " + t.getMessage()); }
        }
        if (found == 0) throw new IllegalStateException("No supported Instagram image view class found");
        XposedHelpers.findAndHookMethod(View.class, "onAttachedToWindow", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                View view = (View) p.thisObject;
                if (!ProfilePictureRenderer.isProfilePicture(view)) return;
                if (FeatureFlags.fullProfilePictures) ProfilePictureRenderer.prepare(view);
                // Named wrappers are containers, not image renderers.
                try { view.getClass().getMethod("getDrawable"); }
                catch (NoSuchMethodException ignored) { return; }
                // Discover overrides on concrete image-view subclasses rather than assuming one class.
                try { installView(view.getClass()); }
                catch (Throwable t) { report(view, "Cannot hook image view: " + t.getMessage()); }
            }
        });
    }

    private static void installView(Class<?> type) throws NoSuchMethodException {
        Method draw = method(type, "onDraw", Canvas.class);
        if (hooked.add(draw)) XposedBridge.hookMethod(draw, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                View view = (View) p.thisObject;
                if (!FeatureFlags.fullProfilePictures || !ProfilePictureRenderer.isProfilePicture(view)) return;
                try {
                    ProfilePictureRenderer.prepare(view);
                    if (ProfilePictureRenderer.draw(view, (Canvas) p.args[0])) {
                        p.setResult(null);
                        FeatureStatusTracker.setHooked("FullProfilePictures");
                    } else {
                        report(view, "Waiting for a supported bitmap; drawable=" +
                                (ProfilePictureRenderer.drawable(view) == null ? "null" : ProfilePictureRenderer.drawable(view).getClass().getName()));
                    }
                } catch (Throwable t) { report(view, "Bitmap draw failed: " + t.getMessage()); }
            }
        });
        try {
            Method setter = method(type, "setImageBitmap", Bitmap.class);
            if (hooked.add(setter)) XposedBridge.hookMethod(setter, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    View view = (View) p.thisObject;
                    if (ProfilePictureRenderer.isProfilePicture(view)) ProfilePictureRenderer.capture(view, (Bitmap) p.args[0]);
                }
            });
        } catch (NoSuchMethodException ignored) { }
    }

    private static Method method(Class<?> type, String name, Class<?> argument) throws NoSuchMethodException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try { return c.getDeclaredMethod(name, argument); }
            catch (NoSuchMethodException ignored) { }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    private static void report(View view, String message) {
        if (reported.add(view.getClass().getName() + message)) ModuleLog.line("(IE|FullProfilePictures) " + view.getClass().getName() + ": " + message);
    }
}
