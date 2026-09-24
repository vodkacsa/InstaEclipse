package ps.reso.instaeclipse.mods.profile;

import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Temporary diagnostics for the native profile-header PFP click path.
 *
 * Working accounts enter X.F7m.onClick() and inflate
 * layout_expanded_profile_picture_view. Glitched accounts enter the same
 * handler and return immediately without touching the UI. This version records
 * the exact handler input view state and the stack that reaches the native
 * expanded-view inflate on a working account.
 */
public final class PfpClickDiagnostics {

    private static final String TAG = "(IE|PFPClickDiag) ";
    private static final String TARGET_ID = "row_profile_header_imageview_frame_layout";
    private static final String EXPANDED_LAYOUT = "layout_expanded_profile_picture_view";

    private static final ThreadLocal<Integer> HANDLER_DEPTH =
            ThreadLocal.withInitial(() -> 0);

    private static final Set<Class<?>> HOOKED_HANDLER_CLASSES =
            Collections.synchronizedSet(new HashSet<>());

    private static volatile boolean installed;

    private PfpClickDiagnostics() {}

    public static void observeAttached(View view) {
        if (view == null) return;

        installHooksOnce();

        if (!isTarget(view)) return;

        view.post(() -> {
            try {
                Object listener = clickListener(view);
                Object delegate = unwrapDelegate(listener);

                ModuleLog.line(TAG + "TARGET"
                        + " listener=" + className(listener)
                        + " delegate=" + className(delegate)
                        + " " + describe(view));

                hookHandler(delegate);
            } catch (Throwable ignored) {}
        });
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

                        Object listener = clickListener(clicked);
                        Object delegate = unwrapDelegate(listener);
                        hookHandler(delegate);

                        ModuleLog.line(TAG + "CLICK_BEFORE"
                                + " listener=" + className(listener)
                                + " delegate=" + className(delegate)
                                + " " + describe(clicked));
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!(param.thisObject instanceof View)) return;

                        View clicked = (View) param.thisObject;
                        if (!isTarget(clicked)) return;

                        ModuleLog.line(TAG + "CLICK_AFTER handled="
                                + String.valueOf(param.getResult()));
                    }
                });

        installInflaterTrace();

        installed = true;
    }

    private static void hookHandler(Object delegate) {
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
                    HANDLER_DEPTH.set(HANDLER_DEPTH.get() + 1);

                    View input = null;
                    if (param.args.length > 0 && param.args[0] instanceof View) {
                        input = (View) param.args[0];
                    }

                    ModuleLog.line(TAG + "HANDLER_BEGIN class="
                            + param.thisObject.getClass().getName()
                            + " input=" + describeNullable(input));

                    if (input != null) {
                        dumpInputTree(input);
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    ModuleLog.line(TAG + "HANDLER_END class="
                            + param.thisObject.getClass().getName()
                            + " threw=" + (param.hasThrowable()
                            ? param.getThrowable().getClass().getName()
                            : "none"));

                    int depth = HANDLER_DEPTH.get() - 1;
                    if (depth <= 0) {
                        HANDLER_DEPTH.remove();
                    } else {
                        HANDLER_DEPTH.set(depth);
                    }
                }
            });

            ModuleLog.line(TAG + "HANDLER_HOOKED class=" + cls.getName());
        } catch (Throwable t) {
            HOOKED_HANDLER_CLASSES.remove(cls);
        }
    }

    private static void installInflaterTrace() {
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
                            if (!handlerActive()) return;

                            int layoutId = (Integer) param.args[0];
                            String layoutName = resourceName(
                                    (LayoutInflater) param.thisObject,
                                    layoutId
                            );

                            if (!EXPANDED_LAYOUT.equals(layoutName)) return;

                            ModuleLog.line(TAG + "OPEN_INFLATE"
                                    + " layout=" + layoutName
                                    + " parent=" + describeNullable((View) param.args[1])
                                    + " attach=" + String.valueOf(param.args[2]));

                            dumpOpenStack();
                        }
                    });
        } catch (Throwable ignored) {}
    }

    private static void dumpOpenStack() {
        try {
            StringBuilder out = new StringBuilder(TAG + "OPEN_STACK");

            StackTraceElement[] stack = new Throwable().getStackTrace();
            int added = 0;

            for (StackTraceElement frame : stack) {
                String cls = frame.getClassName();

                if (cls.equals(PfpClickDiagnostics.class.getName())
                        || cls.startsWith("de.robv.android.xposed.")
                        || cls.startsWith("java.lang.reflect.")
                        || cls.startsWith("sun.reflect.")) {
                    continue;
                }

                out.append("\n  ")
                        .append(cls)
                        .append(".")
                        .append(frame.getMethodName())
                        .append(":")
                        .append(frame.getLineNumber());

                added++;
                if (added >= 30) break;
            }

            ModuleLog.line(out.toString());
        } catch (Throwable ignored) {}
    }

    private static void dumpInputTree(View root) {
        try {
            StringBuilder out = new StringBuilder(TAG + "INPUT_TREE");
            appendView(out, root, "root", 0);
            ModuleLog.line(out.toString());
        } catch (Throwable ignored) {}
    }

    private static void appendView(StringBuilder out, View view, String path, int depth) {
        if (view == null || depth > 4) return;

        out.append("\n  ").append(path)
                .append(" ").append(describeDetailed(view));

        if (!(view instanceof ViewGroup)) return;

        ViewGroup group = (ViewGroup) view;
        int count;
        try {
            count = group.getChildCount();
        } catch (Throwable t) {
            return;
        }

        int limit = Math.min(count, 24);
        for (int i = 0; i < limit; i++) {
            View child;
            try {
                child = group.getChildAt(i);
            } catch (Throwable t) {
                continue;
            }

            appendView(out, child, path + "[" + i + "]", depth + 1);
        }

        if (count > limit) {
            out.append("\n  ").append(path)
                    .append(" childrenTruncated=")
                    .append(count - limit);
        }
    }

    private static String describeDetailed(View view) {
        StringBuilder out = new StringBuilder(describe(view));

        try {
            Object tag = view.getTag();
            out.append(" tag=").append(className(tag));
        } catch (Throwable ignored) {
            out.append(" tag=<error>");
        }

        try {
            out.append(" selected=").append(view.isSelected());
            out.append(" activated=").append(view.isActivated());
            out.append(" pressed=").append(view.isPressed());
            out.append(" alpha=").append(view.getAlpha());
        } catch (Throwable ignored) {}

        try {
            Drawable background = view.getBackground();
            out.append(" background=").append(className(background));
        } catch (Throwable ignored) {}

        if (view instanceof ImageView) {
            try {
                Drawable drawable = ((ImageView) view).getDrawable();
                out.append(" drawable=").append(className(drawable));
            } catch (Throwable ignored) {}
        }

        return out.toString();
    }

    private static boolean handlerActive() {
        Integer depth = HANDLER_DEPTH.get();
        return depth != null && depth > 0;
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

    private static String describe(View view) {
        return "class=" + view.getClass().getName()
                + " id=" + resourceName(view)
                + " size=" + view.getWidth() + "x" + view.getHeight()
                + " visibility=" + view.getVisibility()
                + " enabled=" + view.isEnabled()
                + " clickable=" + view.isClickable();
    }

    private static String describeNullable(View view) {
        return view == null ? "null" : describe(view);
    }

    private static boolean isTarget(View view) {
        return TARGET_ID.equals(resourceName(view));
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

    private static String className(Object value) {
        return value == null ? "null" : value.getClass().getName();
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
