package ps.reso.instaeclipse.mods.profile;

import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Temporary diagnostics for the native profile-header PFP click path.
 *
 * Targets only row_profile_header_imageview_frame_layout. The important click
 * listener is X.0ur, whose A00 field points at the actual Instagram handler
 * (observed as X.F7m). This version recursively inspects that handler graph
 * instead of spamming listener-set and parent-chain logs.
 */
public final class PfpClickDiagnostics {

    private static final String TAG = "(IE|PFPClickDiag) ";
    private static final String TARGET_ID = "row_profile_header_imageview_frame_layout";

    private static final int MAX_GRAPH_DEPTH = 4;
    private static final int MAX_GRAPH_NODES = 48;

    private static volatile boolean installed;

    private PfpClickDiagnostics() {}

    public static void observeAttached(View view) {
        if (view == null) return;

        installHooksOnce();

        if (!isTarget(view)) return;

        view.post(() -> {
            try {
                ModuleLog.line(TAG + "TARGET " + describe(view));
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

                        ModuleLog.line(TAG + "CLICK_BEFORE " + describe(clicked));
                        dumpObjectGraph("CLICK_GRAPH", listener);
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

        installed = true;
    }

    private static void dumpObjectGraph(String label, Object root) {
        if (root == null) {
            ModuleLog.line(TAG + label + " root=null");
            return;
        }

        try {
            StringBuilder out = new StringBuilder(TAG)
                    .append(label)
                    .append(" root=")
                    .append(root.getClass().getName());

            IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
            int[] nodeCount = new int[]{0};

            appendNode(out, "root", root, 0, visited, nodeCount);

            ModuleLog.line(out.toString());
        } catch (Throwable ignored) {}
    }

    private static void appendNode(
            StringBuilder out,
            String path,
            Object object,
            int depth,
            IdentityHashMap<Object, Boolean> visited,
            int[] nodeCount
    ) {
        if (object == null || depth > MAX_GRAPH_DEPTH) return;
        if (nodeCount[0] >= MAX_GRAPH_NODES) return;
        if (visited.put(object, Boolean.TRUE) != null) {
            out.append("\n  ").append(path)
                    .append(" -> <visited ")
                    .append(object.getClass().getName())
                    .append(">");
            return;
        }

        nodeCount[0]++;

        Class<?> runtimeClass = object.getClass();

        out.append("\n  ").append(path)
                .append(" class=").append(runtimeClass.getName())
                .append(" methods=").append(methodSummary(runtimeClass));

        Class<?> cls = runtimeClass;
        int hierarchyDepth = 0;

        while (cls != null && cls != Object.class && hierarchyDepth < 6) {
            Field[] fields;
            try {
                fields = cls.getDeclaredFields();
            } catch (Throwable t) {
                cls = cls.getSuperclass();
                hierarchyDepth++;
                continue;
            }

            for (Field field : fields) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                if (field.isSynthetic()) continue;

                Object value;
                try {
                    field.setAccessible(true);
                    value = field.get(object);
                } catch (Throwable t) {
                    continue;
                }

                String fieldPath = path + "." + field.getName();

                out.append("\n    field ")
                        .append(fieldPath)
                        .append(" type=").append(field.getType().getName())
                        .append(" value=").append(safeValue(value));

                if (shouldRecurse(value)) {
                    appendNode(
                            out,
                            fieldPath,
                            value,
                            depth + 1,
                            visited,
                            nodeCount
                    );
                }
            }

            cls = cls.getSuperclass();
            hierarchyDepth++;
        }
    }

    private static String methodSummary(Class<?> cls) {
        try {
            Method[] methods = cls.getDeclaredMethods();
            if (methods.length == 0) return "[]";

            StringBuilder out = new StringBuilder("[");
            int added = 0;

            for (Method method : methods) {
                if (added >= 16) {
                    out.append(",...");
                    break;
                }

                if (added > 0) out.append(",");

                out.append(method.getName()).append("(");
                Class<?>[] params = method.getParameterTypes();
                for (int i = 0; i < params.length; i++) {
                    if (i > 0) out.append(",");
                    out.append(params[i].getName());
                }
                out.append(")");

                added++;
            }

            return out.append("]").toString();
        } catch (Throwable ignored) {
            return "[unavailable]";
        }
    }

    private static boolean shouldRecurse(Object value) {
        if (value == null) return false;

        if (value instanceof View
                || value instanceof CharSequence
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Character
                || value instanceof Collection
                || value instanceof Map
                || value.getClass().isEnum()
                || value.getClass().isArray()) {
            return false;
        }

        String name = value.getClass().getName();

        return name.startsWith("X.")
                || name.startsWith("com.instagram.");
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
                + " enabled=" + view.isEnabled()
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
        try {
            Object info = XposedHelpers.getObjectField(view, "mListenerInfo");
            if (info == null) return null;
            return XposedHelpers.getObjectField(info, "mOnClickListener");
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
