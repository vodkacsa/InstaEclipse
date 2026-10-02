package ps.reso.instaeclipse.mods.profile;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Native profile-picture click repair.
 *
 * Normal profiles and broken profiles both use the same X.0ur -> X.F7m
 * listener. On normal profiles X.F7m reaches UserDetailFragment.GLY() and
 * inflates layout_expanded_profile_picture_view. On broken profiles X.F7m
 * simply returns before that point.
 *
 * This hook leaves normal clicks completely alone. It only falls back when the
 * exact native X.F7m handler has finished and the expanded layout was NOT
 * inflated during that click. The fallback resolves the UserDetailFragment
 * owning the clicked view and invokes its existing GLY() method, which is the
 * native entry point observed in the working call stack.
 */
public final class PfpClickDiagnostics {

    private static final String TAG = "(IE|PFPClickPatch) ";
    private static final String TARGET_ID = "row_profile_header_imageview_frame_layout";
    private static final String EXPANDED_LAYOUT = "layout_expanded_profile_picture_view";
    private static final String USER_DETAIL_FRAGMENT =
            "com.instagram.profile.fragment.UserDetailFragment";

    private static final ThreadLocal<Integer> HANDLER_DEPTH =
            ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Boolean> NATIVE_OPENED =
            ThreadLocal.withInitial(() -> false);

    private static final Set<Class<?>> HOOKED_HANDLER_CLASSES =
            Collections.synchronizedSet(new HashSet<>());

    private static volatile boolean installed;

    private PfpClickDiagnostics() {}

    public static void observeAttached(View view) {
        if (view == null) return;

        installInflaterTrace();

        if (!isTarget(view)) return;

        view.post(() -> {
            try {
                Object wrapper = clickListener(view);
                Object delegate = unwrapDelegate(wrapper);
                hookNativeHandler(delegate);
            } catch (Throwable ignored) {}
        });
    }

    private static synchronized void installInflaterTrace() {
        if (installed) return;

        try {
            XposedHelpers.findAndHookMethod(
                    LayoutInflater.class,
                    "inflate",
                    int.class,
                    ViewGroup.class,
                    boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (HANDLER_DEPTH.get() <= 0) return;

                            int layoutId = (Integer) param.args[0];
                            String layoutName = resourceName(
                                    (LayoutInflater) param.thisObject,
                                    layoutId
                            );

                            if (EXPANDED_LAYOUT.equals(layoutName)) {
                                NATIVE_OPENED.set(true);
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            ModuleLog.line(TAG + "inflate hook failed=" + t.getClass().getName());
        }

        installed = true;
    }

    private static void hookNativeHandler(Object delegate) {
        if (!(delegate instanceof View.OnClickListener)) return;

        Class<?> cls = delegate.getClass();
        if (!HOOKED_HANDLER_CLASSES.add(cls)) return;

        Method onClick;
        try {
            onClick = cls.getDeclaredMethod("onClick", View.class);
            onClick.setAccessible(true);
        } catch (Throwable t) {
            HOOKED_HANDLER_CLASSES.remove(cls);
            return;
        }

        try {
            XposedBridge.hookMethod(onClick, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    int depth = HANDLER_DEPTH.get();
                    HANDLER_DEPTH.set(depth + 1);

                    if (depth == 0) {
                        NATIVE_OPENED.set(false);
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    int depth = HANDLER_DEPTH.get();

                    try {
                        if (depth == 1
                                && !param.hasThrowable()
                                && !Boolean.TRUE.equals(NATIVE_OPENED.get())
                                && param.args.length > 0
                                && param.args[0] instanceof View) {

                            View clicked = (View) param.args[0];

                            if (isTarget(clicked)) {
                                Object fragment = findOwningUserDetailFragment(clicked);

                                if (fragment != null) {
                                    Method gly = findNoArgMethod(fragment.getClass(), "GLY");

                                    if (gly != null) {
                                        gly.setAccessible(true);
                                        gly.invoke(fragment);

                                        ModuleLog.line(TAG
                                                + "recovered broken native PFP click");
                                    } else {
                                        ModuleLog.line(TAG
                                                + "fallback GLY not found");
                                    }
                                } else {
                                    ModuleLog.line(TAG
                                            + "fallback UserDetailFragment not found");
                                }
                            }
                        }
                    } catch (Throwable t) {
                        ModuleLog.line(TAG + "fallback failed="
                                + t.getClass().getName());
                    } finally {
                        int newDepth = depth - 1;
                        if (newDepth <= 0) {
                            HANDLER_DEPTH.remove();
                            NATIVE_OPENED.remove();
                        } else {
                            HANDLER_DEPTH.set(newDepth);
                        }
                    }
                }
            });

            ModuleLog.line(TAG + "hooked native handler=" + cls.getName());
        } catch (Throwable t) {
            HOOKED_HANDLER_CLASSES.remove(cls);
        }
    }

    private static Object findOwningUserDetailFragment(View view) {
        Object fragment = findFragmentForView(view);

        while (fragment != null) {
            if (USER_DETAIL_FRAGMENT.equals(fragment.getClass().getName())) {
                return fragment;
            }

            fragment = parentFragment(fragment);
        }

        return null;
    }

    private static Object findFragmentForView(View view) {
        if (view == null) return null;

        ClassLoader loader = view.getClass().getClassLoader();
        if (loader == null) {
            loader = view.getContext().getClassLoader();
        }

        try {
            Class<?> manager = Class.forName(
                    "androidx.fragment.app.FragmentManager",
                    false,
                    loader
            );

            for (Method method : manager.getDeclaredMethods()) {
                if (!Modifier.isStatic(method.getModifiers())) continue;
                if (!"findFragment".equals(method.getName())) continue;

                Class<?>[] params = method.getParameterTypes();
                if (params.length != 1 || !View.class.isAssignableFrom(params[0])) {
                    continue;
                }

                method.setAccessible(true);
                return method.invoke(null, view);
            }
        } catch (Throwable ignored) {}

        // Some Fragment versions expose the lookup under a different internal
        // method name. As a fallback, scan static one-View methods whose return
        // type is a Fragment.
        try {
            Class<?> manager = Class.forName(
                    "androidx.fragment.app.FragmentManager",
                    false,
                    loader
            );
            Class<?> fragmentClass = Class.forName(
                    "androidx.fragment.app.Fragment",
                    false,
                    loader
            );

            for (Method method : manager.getDeclaredMethods()) {
                if (!Modifier.isStatic(method.getModifiers())) continue;
                if (!fragmentClass.isAssignableFrom(method.getReturnType())) continue;

                Class<?>[] params = method.getParameterTypes();
                if (params.length != 1 || !View.class.isAssignableFrom(params[0])) {
                    continue;
                }

                try {
                    method.setAccessible(true);
                    Object result = method.invoke(null, view);
                    if (result != null) return result;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}

        return null;
    }

    private static Object parentFragment(Object fragment) {
        if (fragment == null) return null;

        try {
            Method method = findNoArgMethod(
                    fragment.getClass(),
                    "getParentFragment"
            );
            if (method == null) return null;

            method.setAccessible(true);
            return method.invoke(fragment);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method findNoArgMethod(Class<?> start, String name) {
        Class<?> cls = start;

        while (cls != null && cls != Object.class) {
            try {
                return cls.getDeclaredMethod(name);
            } catch (NoSuchMethodException ignored) {
                cls = cls.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }

        try {
            return start.getMethod(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object unwrapDelegate(Object listener) {
        if (listener == null) return null;

        try {
            Field field = listener.getClass().getDeclaredField("A00");
            field.setAccessible(true);
            return field.get(listener);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object clickListener(View view) {
        try {
            Object info = XposedHelpers.getObjectField(view, "mListenerInfo");
            if (info == null) return null;
            return XposedHelpers.getObjectField(info, "mOnClickListener");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isTarget(View view) {
        return TARGET_ID.equals(resourceName(view));
    }

    private static String resourceName(LayoutInflater inflater, int id) {
        try {
            return inflater.getContext().getResources().getResourceEntryName(id);
        } catch (Throwable ignored) {
            return "0x" + Integer.toHexString(id);
        }
    }

    private static String resourceName(View view) {
        if (view == null || view.getId() == View.NO_ID) return "NO_ID";

        try {
            return view.getResources().getResourceEntryName(view.getId());
        } catch (Throwable ignored) {
            return "0x" + Integer.toHexString(view.getId());
        }
    }
}
