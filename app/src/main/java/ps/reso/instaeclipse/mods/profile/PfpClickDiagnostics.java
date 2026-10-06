package ps.reso.instaeclipse.mods.profile;

import android.view.View;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Narrow native-viewer repair experiment.
 *
 * The working/broken traces differ before DUO initialization:
 * working profiles enter UserDetailFragment.A0d(..., false) from A0K,
 * while affected profiles enter the same call as A0d(..., true).
 *
 * This hook changes only that one A0K-originated true value to false and leaves
 * coin-flip views, click routing, callbacks and the native viewer untouched.
 */
public final class PfpClickDiagnostics {

    private static final String TAG = "(IE|PFPRepair) ";
    private static final String USER_DETAIL =
            "com.instagram.profile.fragment.UserDetailFragment";

    private static volatile boolean installed;

    private PfpClickDiagnostics() {}

    public static void observeAttached(View view) {
        if (view == null || installed) return;

        ClassLoader loader = view.getClass().getClassLoader();
        if (loader == null) return;

        install(loader);
    }

    private static synchronized void install(ClassLoader loader) {
        if (installed) return;

        final Class<?> fragmentClass;
        try {
            fragmentClass = Class.forName(USER_DETAIL, false, loader);
        } catch (Throwable ignored) {
            return;
        }

        boolean hooked = false;

        for (Method method : fragmentClass.getDeclaredMethods()) {
            if (!"A0d".equals(method.getName())) continue;

            Class<?>[] params = method.getParameterTypes();
            if (params.length != 2 || params[1] != boolean.class) continue;

            try {
                method.setAccessible(true);

                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args == null
                                || param.args.length < 2
                                || !Boolean.TRUE.equals(param.args[1])) {
                            return;
                        }

                        if (!calledFromA0K()) return;

                        param.args[1] = false;
                        ModuleLog.line(
                                TAG + "A0K A0d(true) -> A0d(false)"
                        );
                    }
                });

                hooked = true;
            } catch (Throwable t) {
                ModuleLog.line(
                        TAG + "failed to hook A0d: "
                                + t.getClass().getSimpleName()
                );
            }
        }

        if (hooked) {
            installed = true;
            ModuleLog.line(TAG + "native viewer repair installed");
        }
    }

    private static boolean calledFromA0K() {
        try {
            for (StackTraceElement frame
                    : Thread.currentThread().getStackTrace()) {
                if (USER_DETAIL.equals(frame.getClassName())
                        && "A0K".equals(frame.getMethodName())) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}

        return false;
    }
}
