package ps.reso.instaeclipse.mods.profile;

import android.view.View;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Single-variable test for the expanded profile picture.
 *
 * Diagnostics showed that Instagram's real expanded_profile_pic is already a
 * square CircularImageView, but clipToOutline=true with a custom outline
 * provider. This hook changes only that one property for that one view.
 *
 * It does NOT replace the drawable, change scale type, clear the outline
 * provider, add overlays, or otherwise alter rendering.
 */
public final class PfpOutlineClipTest {

    private static final String TAG = "(IE|PFPClipTest) ";

    private static final Set<View> LOGGED =
            Collections.newSetFromMap(new WeakHashMap<>());

    private static volatile boolean installed;

    private PfpOutlineClipTest() {}

    public static synchronized void install() {
        if (installed) return;

        // Ensure the target starts unclipped once it is attached.
        XposedHelpers.findAndHookMethod(View.class, "onAttachedToWindow",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!(param.thisObject instanceof View)) return;

                        View view = (View) param.thisObject;
                        if (!isExpandedProfilePic(view)) return;

                        try {
                            view.setClipToOutline(false);
                            view.invalidate();

                            synchronized (LOGGED) {
                                if (LOGGED.add(view)) {
                                    ModuleLog.line(TAG
                                            + "target attached; forced clipToOutline=false"
                                            + " class=" + view.getClass().getName()
                                            + " size=" + view.getWidth() + "x" + view.getHeight());
                                }
                            }
                        } catch (Throwable t) {
                            ModuleLog.line(TAG + "attach test failed: " + t);
                        }
                    }
                });

        // If Instagram tries to turn outline clipping back on later, keep this
        // exact target false. Calls for every other View pass through untouched.
        XposedHelpers.findAndHookMethod(View.class, "setClipToOutline",
                boolean.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!(param.thisObject instanceof View)) return;

                        View view = (View) param.thisObject;
                        if (!isExpandedProfilePic(view)) return;

                        if (param.args.length > 0 && Boolean.TRUE.equals(param.args[0])) {
                            param.args[0] = false;
                            ModuleLog.line(TAG
                                    + "blocked setClipToOutline(true) on expanded_profile_pic");
                        }
                    }
                });

        installed = true;
        ModuleLog.line(TAG + "installed");
    }

    private static boolean isExpandedProfilePic(View view) {
        if (view == null || view.getId() == View.NO_ID) return false;

        try {
            return "expanded_profile_pic".equals(
                    view.getResources().getResourceEntryName(view.getId()));
        } catch (Throwable ignored) {
            return false;
        }
    }
}
