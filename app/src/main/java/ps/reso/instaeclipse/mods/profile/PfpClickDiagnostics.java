package ps.reso.instaeclipse.mods.profile;

import android.view.View;
import android.view.ViewParent;
import android.widget.ImageView;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Temporary diagnostics for Instagram's profile-page picture click behavior.
 *
 * The expanded viewer cannot be used to diagnose a profile whose picture never
 * opens, so this observes likely profile-picture views on the profile page
 * itself. It only logs structural UI information: class/resource names,
 * dimensions, click state and click-listener classes.
 */
public final class PfpClickDiagnostics {

    private static final String TAG = "(IE|PFPClickDiag) ";

    private static final Set<View> CANDIDATES =
            Collections.newSetFromMap(new WeakHashMap<>());

    private static final Set<View> DUMPED =
            Collections.newSetFromMap(new WeakHashMap<>());

    private static volatile boolean installed;

    private PfpClickDiagnostics() {}

    public static void observeAttached(View view) {
        if (view == null) return;

        installHooksOnce();

        if (!isInterestingPictureView(view)) return;

        synchronized (CANDIDATES) {
            CANDIDATES.add(view);
        }

        // Wait until layout/listeners have settled so width, height and the
        // parent click target are meaningful.
        view.post(() -> dumpOnce(view, "attached"));
    }

    private static synchronized void installHooksOnce() {
        if (installed) return;

        // Log actual click dispatch for a candidate or one of its parents.
        XposedHelpers.findAndHookMethod(View.class, "performClick",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!(param.thisObject instanceof View)) return;

                        View clicked = (View) param.thisObject;
                        if (!isCandidateOrParent(clicked)) return;

                        ModuleLog.line(TAG + "performClick"
                                + " handled=" + String.valueOf(param.getResult())
                                + " " + describe(clicked));
                    }
                });

        // If Instagram installs/removes the listener after attachment, capture
        // that too for already-known candidate trees.
        XposedHelpers.findAndHookMethod(View.class, "setOnClickListener",
                View.OnClickListener.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!(param.thisObject instanceof View)) return;

                        View target = (View) param.thisObject;
                        if (!isCandidateOrParent(target)) return;

                        ModuleLog.line(TAG + "listenerChanged"
                                + " listener=" + listenerClass(target)
                                + " " + describe(target));
                    }
                });

        installed = true;
    }

    private static void dumpOnce(View candidate, String reason) {
        synchronized (DUMPED) {
            if (!DUMPED.add(candidate)) return;
        }

        try {
            StringBuilder out = new StringBuilder();
            out.append(TAG).append("candidate reason=").append(reason);

            View current = candidate;
            for (int depth = 0; current != null && depth < 8; depth++) {
                out.append("\n  [").append(depth).append("] ")
                        .append(describe(current));

                ViewParent parent = current.getParent();
                current = parent instanceof View ? (View) parent : null;
            }

            ModuleLog.line(out.toString());
        } catch (Throwable ignored) {}
    }

    private static String describe(View view) {
        return "class=" + view.getClass().getName()
                + " id=" + resourceName(view)
                + " size=" + view.getWidth() + "x" + view.getHeight()
                + " clickable=" + view.isClickable()
                + " hasClickListeners=" + safeHasClickListeners(view)
                + " listener=" + listenerClass(view)
                + " longClickable=" + view.isLongClickable()
                + " enabled=" + view.isEnabled()
                + " focusable=" + view.isFocusable()
                + " visibility=" + view.getVisibility();
    }

    private static boolean isInterestingPictureView(View view) {
        if (!(view instanceof ImageView)) return false;

        if (hasClassInHierarchy(
                view.getClass(),
                "com.instagram.common.ui.widget.imageview.CircularImageView")) {
            return true;
        }

        String id = resourceName(view).toLowerCase();
        return id.contains("profile")
                || id.contains("avatar")
                || id.contains("user_pic")
                || id.contains("user_image");
    }

    private static boolean hasClassInHierarchy(Class<?> cls, String name) {
        while (cls != null && cls != Object.class) {
            if (name.equals(cls.getName())) return true;
            cls = cls.getSuperclass();
        }
        return false;
    }

    private static boolean isCandidateOrParent(View view) {
        synchronized (CANDIDATES) {
            for (View candidate : CANDIDATES) {
                if (candidate == null) continue;

                View current = candidate;
                for (int depth = 0; current != null && depth < 8; depth++) {
                    if (current == view) return true;

                    ViewParent parent = current.getParent();
                    current = parent instanceof View ? (View) parent : null;
                }
            }
        }

        return false;
    }

    private static boolean safeHasClickListeners(View view) {
        try {
            return view.hasOnClickListeners();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String listenerClass(View view) {
        try {
            Object info = XposedHelpers.getObjectField(view, "mListenerInfo");
            if (info == null) return "null";

            Object listener = XposedHelpers.getObjectField(info, "mOnClickListener");
            return listener == null ? "null" : listener.getClass().getName();
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static String resourceName(View view) {
        if (view.getId() == View.NO_ID) return "NO_ID";

        try {
            return view.getResources().getResourceEntryName(view.getId());
        } catch (Throwable ignored) {
            return "0x" + Integer.toHexString(view.getId());
        }
    }
}
