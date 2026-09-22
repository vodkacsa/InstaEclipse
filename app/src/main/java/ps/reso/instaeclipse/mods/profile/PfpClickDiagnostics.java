package ps.reso.instaeclipse.mods.profile;

import android.view.View;
import android.view.ViewParent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Temporary diagnostics for the native profile-header PFP click path.
 *
 * This intentionally targets only row_profile_header_imageview_frame_layout,
 * because that is the actual view receiving performClick() on the glitched
 * account. It does not modify click behavior.
 */
public final class PfpClickDiagnostics {

    private static final String TAG = "(IE|PFPClickDiag) ";
    private static final String TARGET_ID = "row_profile_header_imageview_frame_layout";

    private static volatile boolean installed;

    private PfpClickDiagnostics() {}

    public static void observeAttached(View view) {
        if (view == null) return;

        installHooksOnce();

        if (!isTarget(view)) return;

        view.post(() -> dumpTarget("attached", view));
    }

    private static synchronized void installHooksOnce() {
        if (installed) return;

        XposedHelpers.findAndHookMethod(View.class, "performClick",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!(param.thisObject instanceof View)) return;

                        View clicked = (View) param.thisObject;
                        if (!isTarget(clicked)) return;

                        ModuleLog.line(TAG + "CLICK_BEFORE " + describe(clicked));
                        dumpListener("clickListener", clickListener(clicked));
                        dumpListener("longClickListener", longClickListener(clicked));
                        dumpParentChain(clicked);
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!(param.thisObject instanceof View)) return;

                        View clicked = (View) param.thisObject;
                        if (!isTarget(clicked)) return;

                        ModuleLog.line(TAG + "CLICK_AFTER handled="
                                + String.valueOf(param.getResult())
                                + " " + describe(clicked));
                    }
                });

        XposedHelpers.findAndHookMethod(View.class, "setOnClickListener",
                View.OnClickListener.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!(param.thisObject instanceof View)) return;

                        View target = (View) param.thisObject;
                        if (!isTarget(target)) return;

                        ModuleLog.line(TAG + "CLICK_LISTENER_SET " + describe(target));
                        dumpListener("clickListener", clickListener(target));
                    }
                });

        XposedHelpers.findAndHookMethod(View.class, "setOnLongClickListener",
                View.OnLongClickListener.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!(param.thisObject instanceof View)) return;

                        View target = (View) param.thisObject;
                        if (!isTarget(target)) return;

                        ModuleLog.line(TAG + "LONG_CLICK_LISTENER_SET " + describe(target));
                        dumpListener("longClickListener", longClickListener(target));
                    }
                });

        installed = true;
    }

    private static void dumpTarget(String reason, View target) {
        try {
            ModuleLog.line(TAG + "TARGET reason=" + reason + " " + describe(target));
            dumpListener("clickListener", clickListener(target));
            dumpListener("longClickListener", longClickListener(target));
            dumpParentChain(target);
        } catch (Throwable ignored) {}
    }

    private static void dumpParentChain(View target) {
        try {
            StringBuilder out = new StringBuilder(TAG + "PARENTS");

            View current = target;
            for (int depth = 0; current != null && depth < 8; depth++) {
                out.append("\n  [").append(depth).append("] ")
                        .append(describe(current));

                ViewParent parent = current.getParent();
                current = parent instanceof View ? (View) parent : null;
            }

            ModuleLog.line(out.toString());
        } catch (Throwable ignored) {}
    }

    /**
     * Dump only structural listener state. Strings/content are not logged.
     * Primitive discriminator fields are important because Instagram commonly
     * reuses one synthetic listener class with an int switch field.
     */
    private static void dumpListener(String label, Object listener) {
        if (listener == null) {
            ModuleLog.line(TAG + label + "=null");
            return;
        }

        try {
            StringBuilder out = new StringBuilder();
            out.append(TAG).append(label)
                    .append(" class=").append(listener.getClass().getName());

            Class<?> cls = listener.getClass();
            int depth = 0;

            while (cls != null && cls != Object.class && depth < 6) {
                for (Field field : cls.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) continue;

                    Object value;
                    try {
                        field.setAccessible(true);
                        value = field.get(listener);
                    } catch (Throwable t) {
                        continue;
                    }

                    out.append("\n  field ")
                            .append(cls.getName()).append(".").append(field.getName())
                            .append(" type=").append(field.getType().getName())
                            .append(" value=").append(safeValue(value));
                }

                cls = cls.getSuperclass();
                depth++;
            }

            out.append("\n  methods=");
            Method[] methods = listener.getClass().getDeclaredMethods();
            boolean first = true;
            for (Method method : methods) {
                if (!first) out.append(",");
                first = false;
                out.append(method.getName()).append("(");
                Class<?>[] params = method.getParameterTypes();
                for (int i = 0; i < params.length; i++) {
                    if (i > 0) out.append(",");
                    out.append(params[i].getName());
                }
                out.append(")");
            }

            ModuleLog.line(out.toString());
        } catch (Throwable ignored) {}
    }

    private static String safeValue(Object value) {
        if (value == null) return "null";

        if (value instanceof Number
                || value instanceof Boolean
                || value instanceof Character
                || value.getClass().isEnum()) {
            return String.valueOf(value);
        }

        if (value instanceof CharSequence) {
            return value.getClass().getName() + "(length="
                    + ((CharSequence) value).length() + ")";
        }

        if (value instanceof View) {
            View v = (View) value;
            return "View(class=" + v.getClass().getName()
                    + ",id=" + resourceName(v) + ")";
        }

        if (value instanceof Collection) {
            return value.getClass().getName()
                    + "(size=" + ((Collection<?>) value).size() + ")";
        }

        if (value instanceof Map) {
            return value.getClass().getName()
                    + "(size=" + ((Map<?, ?>) value).size() + ")";
        }

        if (value.getClass().isArray()) {
            return value.getClass().getName()
                    + "(length=" + java.lang.reflect.Array.getLength(value) + ")";
        }

        return "object(" + value.getClass().getName() + ")";
    }

    private static String describe(View view) {
        return "class=" + view.getClass().getName()
                + " id=" + resourceName(view)
                + " size=" + view.getWidth() + "x" + view.getHeight()
                + " clickable=" + view.isClickable()
                + " hasClickListeners=" + safeHasClickListeners(view)
                + " listener=" + listenerClass(clickListener(view))
                + " longClickable=" + view.isLongClickable()
                + " longListener=" + listenerClass(longClickListener(view))
                + " enabled=" + view.isEnabled()
                + " focusable=" + view.isFocusable()
                + " visibility=" + view.getVisibility();
    }

    private static boolean isTarget(View view) {
        return TARGET_ID.equals(resourceName(view));
    }

    private static boolean safeHasClickListeners(View view) {
        try {
            return view.hasOnClickListeners();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Object clickListener(View view) {
        return listenerField(view, "mOnClickListener");
    }

    private static Object longClickListener(View view) {
        return listenerField(view, "mOnLongClickListener");
    }

    private static Object listenerField(View view, String fieldName) {
        try {
            Object info = XposedHelpers.getObjectField(view, "mListenerInfo");
            if (info == null) return null;
            return XposedHelpers.getObjectField(info, fieldName);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String listenerClass(Object listener) {
        return listener == null ? "null" : listener.getClass().getName();
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
