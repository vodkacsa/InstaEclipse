package ps.reso.instaeclipse.mods.profile;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import java.lang.reflect.Method;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Native profile-picture click fallback.
 *
 * Some profiles install the same native X.F7m click handler as normal profiles,
 * but that handler returns immediately before reaching UserDetailFragment.GLY().
 * A known-good native open path is:
 *
 * X.F7m.onClick -> ... -> UserDetailFragment.GLY -> X.DUO.GLY ->
 * X.DUO.A00 -> layout_expanded_profile_picture_view
 *
 * Instead of trying to patch the profile's clickability state, this watches the
 * Activity-level touch stream. If a tap lands on the header PFP and Instagram
 * did not open the native expanded viewer itself, it invokes the current
 * UserDetailFragment.GLY() directly. This keeps Instagram's own viewer,
 * animations, image loading and PfpRendererBypass intact.
 */
public final class PfpClickDiagnostics {

    private static final String TAG = "(IE|PFPClickPatch) ";
    private static final String TARGET_ID = "row_profile_header_imageview_frame_layout";
    private static final String EXPANDED_ROOT_ID = "touch_interceptor_expanded_profile_pic";
    private static final String USER_DETAIL_FRAGMENT =
            "com.instagram.profile.fragment.UserDetailFragment";

    private static volatile boolean installed;
    private static volatile View latestTarget;
    private static volatile long lastHandledDownTime;

    private PfpClickDiagnostics() {}

    public static void observeAttached(View view) {
        if (view == null) return;

        installHooksOnce();

        if (TARGET_ID.equals(resourceName(view))) {
            latestTarget = view;
        }
    }

    private static synchronized void installHooksOnce() {
        if (installed) return;

        XposedHelpers.findAndHookMethod(
                Activity.class,
                "dispatchTouchEvent",
                MotionEvent.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!(param.thisObject instanceof Activity)) return;
                        if (!(param.args[0] instanceof MotionEvent)) return;

                        MotionEvent event = (MotionEvent) param.args[0];
                        if (event.getActionMasked() != MotionEvent.ACTION_UP) return;

                        View target = latestTarget;
                        if (target == null
                                || !target.isAttachedToWindow()
                                || target.getVisibility() != View.VISIBLE) {
                            return;
                        }

                        if (!pointInside(target, event.getRawX(), event.getRawY())) {
                            return;
                        }

                        long downTime = event.getDownTime();
                        if (downTime == lastHandledDownTime) return;
                        lastHandledDownTime = downTime;

                        Activity activity = (Activity) param.thisObject;

                        // The normal path inflates the viewer within a few ms. Give
                        // Instagram a short chance to handle the tap first so normal
                        // profiles never get a duplicate open.
                        target.postDelayed(() -> {
                            try {
                                if (isNativeViewerOpen(activity)) return;

                                boolean invoked = invokeCurrentUserDetailGly(activity);
                                ModuleLog.line(TAG + "fallback invoked=" + invoked);
                            } catch (Throwable t) {
                                ModuleLog.line(TAG + "fallback error="
                                        + t.getClass().getName());
                            }
                        }, 120L);
                    }
                }
        );

        installed = true;
    }

    private static boolean pointInside(View target, float rawX, float rawY) {
        try {
            Rect rect = new Rect();
            if (!target.getGlobalVisibleRect(rect)) return false;
            return rect.contains(Math.round(rawX), Math.round(rawY));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isNativeViewerOpen(Activity activity) {
        try {
            int id = activity.getResources().getIdentifier(
                    EXPANDED_ROOT_ID,
                    "id",
                    activity.getPackageName()
            );
            if (id == 0) return false;

            View root = activity.findViewById(id);
            return root != null
                    && root.isAttachedToWindow()
                    && root.getVisibility() == View.VISIBLE;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean invokeCurrentUserDetailGly(Activity activity) {
        Object fragment = findCurrentUserDetailFragment(activity);
        if (fragment == null) {
            ModuleLog.line(TAG + "UserDetailFragment not found");
            return false;
        }

        Method gly = findNoArgMethod(fragment.getClass(), "GLY");
        if (gly == null) {
            ModuleLog.line(TAG + "UserDetailFragment.GLY not found");
            return false;
        }

        try {
            gly.setAccessible(true);
            gly.invoke(fragment);
            return true;
        } catch (Throwable t) {
            ModuleLog.line(TAG + "GLY invoke failed="
                    + t.getClass().getName());
            return false;
        }
    }

    private static Object findCurrentUserDetailFragment(Activity activity) {
        try {
            Method getSupportFragmentManager =
                    findNoArgMethod(activity.getClass(), "getSupportFragmentManager");
            if (getSupportFragmentManager == null) return null;

            getSupportFragmentManager.setAccessible(true);
            Object manager = getSupportFragmentManager.invoke(activity);
            return findUserDetailInManager(manager);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object findUserDetailInManager(Object manager) {
        if (manager == null) return null;

        List<?> fragments;
        try {
            Method getFragments = findNoArgMethod(manager.getClass(), "getFragments");
            if (getFragments == null) return null;

            getFragments.setAccessible(true);
            Object value = getFragments.invoke(manager);
            if (!(value instanceof List)) return null;

            fragments = (List<?>) value;
        } catch (Throwable ignored) {
            return null;
        }

        Object fallback = null;

        // Top-most fragments are normally at the end of FragmentManager's list.
        for (int i = fragments.size() - 1; i >= 0; i--) {
            Object fragment = fragments.get(i);
            if (fragment == null) continue;

            if (USER_DETAIL_FRAGMENT.equals(fragment.getClass().getName())) {
                if (isVisibleFragment(fragment)) {
                    return fragment;
                }
                if (fallback == null) {
                    fallback = fragment;
                }
            }

            Object child = childFragmentManager(fragment);
            Object nested = findUserDetailInManager(child);
            if (nested != null) {
                return nested;
            }
        }

        return fallback;
    }

    private static Object childFragmentManager(Object fragment) {
        try {
            Method method = findNoArgMethod(
                    fragment.getClass(),
                    "getChildFragmentManager"
            );
            if (method == null) return null;

            method.setAccessible(true);
            return method.invoke(fragment);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isVisibleFragment(Object fragment) {
        try {
            Method isVisible = findNoArgMethod(fragment.getClass(), "isVisible");
            if (isVisible != null) {
                isVisible.setAccessible(true);
                Object value = isVisible.invoke(fragment);
                if (Boolean.TRUE.equals(value)) return true;
            }

            Method isResumed = findNoArgMethod(fragment.getClass(), "isResumed");
            if (isResumed != null) {
                isResumed.setAccessible(true);
                Object value = isResumed.invoke(fragment);
                return Boolean.TRUE.equals(value);
            }
        } catch (Throwable ignored) {}

        return false;
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

    private static String resourceName(View view) {
        if (view == null || view.getId() == View.NO_ID) return "NO_ID";

        try {
            return view.getResources().getResourceEntryName(view.getId());
        } catch (Throwable ignored) {
            return "0x" + Integer.toHexString(view.getId());
        }
    }
}
