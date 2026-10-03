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
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Temporary PFP click diagnostics.
 *
 * Pure logging only. This class must not change Instagram's click result,
 * fragment state, view hierarchy or native expanded-PFP flow.
 */
public final class PfpClickDiagnostics {

    private static final String TAG = "(IE|PFPClickDiag) ";
    private static final String TARGET_ID = "row_profile_header_imageview_frame_layout";
    private static final String EXPANDED_LAYOUT = "layout_expanded_profile_picture_view";

    private static final AtomicInteger NEXT_CLICK = new AtomicInteger(1);
    private static final ThreadLocal<Integer> ACTIVE_CLICK = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> OPENED =
            ThreadLocal.withInitial(() -> false);

    private static final Set<Class<?>> HOOKED_HANDLER_CLASSES =
            Collections.synchronizedSet(new HashSet<>());
    private static final Set<String> HOOKED_TRACE_METHODS =
            Collections.synchronizedSet(new HashSet<>());

    private static volatile boolean installed;

    private PfpClickDiagnostics() {}

    public static void observeAttached(View view) {
        if (view == null) return;

        installBaseHooks();
        installKnownPathHooks(view.getClass().getClassLoader());

        if (!isTarget(view)) return;

        view.post(() -> {
            try {
                Object wrapper = clickListener(view);
                Object delegate = unwrapDelegate(wrapper);

                ModuleLog.line(TAG + "TARGET"
                        + " wrapper=" + className(wrapper)
                        + " delegate=" + className(delegate)
                        + " " + describe(view));

                hookNativeHandler(delegate);
            } catch (Throwable t) {
                ModuleLog.line(TAG + "TARGET_ERROR " + error(t));
            }
        });
    }

    private static synchronized void installBaseHooks() {
        if (installed) return;

        try {
            XposedHelpers.findAndHookMethod(
                    View.class,
                    "performClick",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!(param.thisObject instanceof View)) return;

                            View clicked = (View) param.thisObject;
                            if (!isTarget(clicked)) return;

                            int click = NEXT_CLICK.getAndIncrement();
                            ACTIVE_CLICK.set(click);
                            OPENED.set(false);

                            Object wrapper = clickListener(clicked);
                            Object delegate = unwrapDelegate(wrapper);
                            hookNativeHandler(delegate);

                            ModuleLog.line(TAG + "CLICK_BEGIN #" + click
                                    + " wrapper=" + className(wrapper)
                                    + " delegate=" + className(delegate)
                                    + " " + describe(clicked));

                            dumpDirectFields("F7M_FIELDS", delegate);
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!(param.thisObject instanceof View)) return;

                            View clicked = (View) param.thisObject;
                            if (!isTarget(clicked)) return;

                            Integer click = ACTIVE_CLICK.get();

                            ModuleLog.line(TAG + "CLICK_END #"
                                    + String.valueOf(click)
                                    + " handled=" + String.valueOf(param.getResult())
                                    + " opened=" + String.valueOf(OPENED.get())
                                    + " threw=" + (param.hasThrowable()
                                    ? error(param.getThrowable())
                                    : "none"));

                            ACTIVE_CLICK.remove();
                            OPENED.remove();
                        }
                    }
            );
        } catch (Throwable t) {
            ModuleLog.line(TAG + "performClick hook failed=" + error(t));
        }

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
                            if (ACTIVE_CLICK.get() == null) return;

                            int layoutId = (Integer) param.args[0];
                            String layoutName = resourceName(
                                    (LayoutInflater) param.thisObject,
                                    layoutId
                            );

                            if (!EXPANDED_LAYOUT.equals(layoutName)) return;

                            OPENED.set(true);

                            ModuleLog.line(TAG + "OPEN_INFLATE #"
                                    + ACTIVE_CLICK.get()
                                    + " layout=" + layoutName
                                    + " parent=" + describeNullable((View) param.args[1])
                                    + " attach=" + String.valueOf(param.args[2]));

                            dumpStack("OPEN_STACK");
                        }
                    }
            );
        } catch (Throwable t) {
            ModuleLog.line(TAG + "inflate hook failed=" + error(t));
        }

        installed = true;
    }

    private static void hookNativeHandler(Object delegate) {
        if (!(delegate instanceof View.OnClickListener)) return;

        Class<?> cls = delegate.getClass();
        if (!HOOKED_HANDLER_CLASSES.add(cls)) return;

        try {
            Method onClick = cls.getDeclaredMethod("onClick", View.class);
            onClick.setAccessible(true);

            XposedBridge.hookMethod(onClick, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (ACTIVE_CLICK.get() == null) return;

                    View input = param.args.length > 0 && param.args[0] instanceof View
                            ? (View) param.args[0]
                            : null;

                    ModuleLog.line(TAG + "HANDLER_BEGIN #"
                            + ACTIVE_CLICK.get()
                            + " class=" + param.thisObject.getClass().getName()
                            + " input=" + describeNullable(input));
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (ACTIVE_CLICK.get() == null) return;

                    ModuleLog.line(TAG + "HANDLER_END #"
                            + ACTIVE_CLICK.get()
                            + " class=" + param.thisObject.getClass().getName()
                            + " opened=" + OPENED.get()
                            + " threw=" + (param.hasThrowable()
                            ? error(param.getThrowable())
                            : "none"));
                }
            });

            ModuleLog.line(TAG + "HANDLER_HOOKED class=" + cls.getName());
        } catch (Throwable t) {
            HOOKED_HANDLER_CLASSES.remove(cls);
            ModuleLog.line(TAG + "HANDLER_HOOK_FAILED class="
                    + cls.getName() + " error=" + error(t));
        }
    }

    private static void installKnownPathHooks(ClassLoader loader) {
        if (loader == null) return;

        hookNamedMethods(loader, "X.EeU", "E0p");
        hookNamedMethods(loader, "X.EdZ", "E0p");
        hookNamedMethods(loader, "X.EdT", "E0p");
        hookNamedMethods(loader, "X.DiK", "E0p");
        hookNamedMethods(loader, "X.C7D", "invoke");
        hookNamedMethods(
                loader,
                "com.instagram.profile.fragment.UserDetailFragment",
                "GLY"
        );
        hookNamedMethods(loader, "X.DUO", "GLY");
        hookNamedMethods(loader, "X.DUO", "A00");
    }

    private static void hookNamedMethods(
            ClassLoader loader,
            String className,
            String methodName
    ) {
        Class<?> cls;
        try {
            cls = Class.forName(className, false, loader);
        } catch (Throwable ignored) {
            return;
        }

        Method[] methods;
        try {
            methods = cls.getDeclaredMethods();
        } catch (Throwable ignored) {
            return;
        }

        for (Method method : methods) {
            if (!methodName.equals(method.getName())) continue;

            String key = className + "#" + signature(method);
            if (!HOOKED_TRACE_METHODS.add(key)) continue;

            try {
                method.setAccessible(true);

                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (ACTIVE_CLICK.get() == null) return;

                        ModuleLog.line(TAG + "TRACE_ENTER #"
                                + ACTIVE_CLICK.get()
                                + " " + key
                                + " this=" + className(param.thisObject)
                                + " args=" + safeArgs(param.args));
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (ACTIVE_CLICK.get() == null) return;

                        ModuleLog.line(TAG + "TRACE_EXIT #"
                                + ACTIVE_CLICK.get()
                                + " " + key
                                + " result=" + safeValue(param.getResult())
                                + " threw=" + (param.hasThrowable()
                                ? error(param.getThrowable())
                                : "none"));
                    }
                });

                ModuleLog.line(TAG + "TRACE_HOOKED " + key);
            } catch (Throwable t) {
                HOOKED_TRACE_METHODS.remove(key);
                ModuleLog.line(TAG + "TRACE_HOOK_FAILED "
                        + key + " error=" + error(t));
            }
        }
    }

    private static void dumpDirectFields(String label, Object object) {
        if (object == null || ACTIVE_CLICK.get() == null) return;

        StringBuilder out = new StringBuilder();
        out.append(TAG)
                .append(label)
                .append(" #")
                .append(ACTIVE_CLICK.get())
                .append(" class=")
                .append(object.getClass().getName());

        Class<?> cls = object.getClass();

        while (cls != null && cls != Object.class) {
            Field[] fields;

            try {
                fields = cls.getDeclaredFields();
            } catch (Throwable t) {
                break;
            }

            for (Field field : fields) {
                if (Modifier.isStatic(field.getModifiers())) continue;

                Object value;
                try {
                    field.setAccessible(true);
                    value = field.get(object);
                } catch (Throwable t) {
                    continue;
                }

                out.append("\n  ")
                        .append(cls.getName())
                        .append(".")
                        .append(field.getName())
                        .append("=")
                        .append(safeValue(value));
            }

            cls = cls.getSuperclass();
        }

        ModuleLog.line(out.toString());
    }

    private static void dumpStack(String label) {
        try {
            StringBuilder out = new StringBuilder();
            out.append(TAG)
                    .append(label)
                    .append(" #")
                    .append(ACTIVE_CLICK.get());

            int added = 0;
            for (StackTraceElement frame : new Throwable().getStackTrace()) {
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

                if (++added >= 35) break;
            }

            ModuleLog.line(out.toString());
        } catch (Throwable ignored) {}
    }

    private static String safeArgs(Object[] args) {
        if (args == null || args.length == 0) return "[]";

        StringBuilder out = new StringBuilder("[");

        for (int i = 0; i < args.length; i++) {
            if (i > 0) out.append(", ");
            out.append(safeValue(args[i]));
        }

        return out.append("]").toString();
    }

    private static String safeValue(Object value) {
        if (value == null) return "null";

        if (value instanceof Boolean
                || value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long
                || value instanceof Float
                || value instanceof Double
                || value instanceof Character
                || value.getClass().isEnum()) {
            return value.getClass().getSimpleName() + "(" + value + ")";
        }

        if (value instanceof CharSequence) {
            return value.getClass().getName()
                    + "(length=" + ((CharSequence) value).length() + ")";
        }

        if (value instanceof View) {
            return "View(" + describe((View) value) + ")";
        }

        if (value.getClass().isArray()) {
            try {
                return value.getClass().getName()
                        + "(length=" + java.lang.reflect.Array.getLength(value) + ")";
            } catch (Throwable ignored) {}
        }

        return "object(" + value.getClass().getName() + ")";
    }

    private static String signature(Method method) {
        StringBuilder out = new StringBuilder(method.getName()).append("(");

        Class<?>[] params = method.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) out.append(",");
            out.append(params[i].getName());
        }

        return out.append(")").toString();
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

    private static String className(Object object) {
        return object == null ? "null" : object.getClass().getName();
    }

    private static String error(Throwable t) {
        if (t == null) return "null";

        String message = t.getMessage();
        return t.getClass().getName()
                + (message == null ? "" : "(" + message + ")");
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
