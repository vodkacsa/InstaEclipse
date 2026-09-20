package ps.reso.instaeclipse.mods.profile;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Diagnostic-only dump for Instagram's expanded profile picture.
 *
 * This never changes rendering. ProfilePicDownloadHook calls schedule() only
 * after it has positively identified the stable "expanded_profile_pic" view.
 *
 * The first pass showed:
 *  - the target is CircularImageView and is physically square
 *  - disabling clipToOutline does NOT remove the visible circle
 *  - the active image drawable is an obfuscated X.3cW instance
 *
 * This second pass therefore focuses on the drawable itself: complete class
 * hierarchy, method signatures, field TYPES + safe values, nested rendering
 * objects, and a one-shot trace when its draw(Canvas) actually executes.
 */
public final class PfpDiagnostics {

    private static final String TAG = "(IE|PFPDiag) ";

    private static final Set<View> DUMPED_VIEWS =
            Collections.newSetFromMap(new WeakHashMap<>());

    private static final Set<Class<?>> DUMPED_DRAWABLE_CLASSES =
            Collections.synchronizedSet(new HashSet<>());

    private static final Set<Class<?>> HOOKED_DRAWABLE_CLASSES =
            Collections.synchronizedSet(new HashSet<>());

    private static final Set<Drawable> TRACED_DRAWABLES =
            Collections.newSetFromMap(new WeakHashMap<>());

    private static final int MAX_PARENT_DEPTH = 10;
    private static final int MAX_CHILD_DEPTH = 5;
    private static final int MAX_CHILDREN = 50;
    private static final int MAX_NESTED_OBJECT_DEPTH = 2;
    private static final int MAX_OBJECT_FIELDS = 80;
    private static final int MAX_METHODS_PER_CLASS = 120;

    private PfpDiagnostics() {}

    public static void schedule(View target) {
        if (target == null) return;

        // onAttachedToWindow may happen before final measurement/drawable setup.
        target.post(() -> dumpOnce(target));
    }

    private static synchronized boolean markViewDumped(View target) {
        return DUMPED_VIEWS.add(target);
    }

    private static synchronized boolean markDrawableTraced(Drawable drawable) {
        return TRACED_DRAWABLES.add(drawable);
    }

    private static void dumpOnce(View target) {
        if (!markViewDumped(target)) return;

        try {
            ModuleLog.line(TAG + "========== TARGET BEGIN ==========");
            ModuleLog.line(TAG + "TARGET " + describeView(target));
            dumpViewClassHierarchy(target);
            dumpImageState("TARGET", target);
            dumpParents(target);
            dumpChildren(target);
            ModuleLog.line(TAG + "========== TARGET END ==========");
        } catch (Throwable t) {
            ModuleLog.line(TAG + "dump failed: " + t);
        }
    }

    private static void dumpParents(View target) {
        ViewParent parent = target.getParent();
        int depth = 1;

        while (parent instanceof View && depth <= MAX_PARENT_DEPTH) {
            View view = (View) parent;
            ModuleLog.line(TAG + "PARENT[" + depth + "] " + describeView(view));
            dumpImageState("PARENT[" + depth + "]", view);
            parent = view.getParent();
            depth++;
        }

        if (parent != null && !(parent instanceof View)) {
            ModuleLog.line(TAG + "PARENT_END type=" + parent.getClass().getName());
        }
    }

    private static void dumpChildren(View target) {
        if (!(target instanceof ViewGroup)) {
            ModuleLog.line(TAG + "CHILDREN none (target is not ViewGroup)");
            return;
        }

        int[] count = new int[]{0};
        dumpChildrenRecursive((ViewGroup) target, 0, count);
        ModuleLog.line(TAG + "CHILDREN dumped=" + count[0]);
    }

    private static void dumpChildrenRecursive(ViewGroup group, int depth, int[] count) {
        if (depth >= MAX_CHILD_DEPTH || count[0] >= MAX_CHILDREN) return;

        for (int i = 0; i < group.getChildCount() && count[0] < MAX_CHILDREN; i++) {
            View child;
            try {
                child = group.getChildAt(i);
            } catch (Throwable t) {
                continue;
            }
            if (child == null) continue;

            count[0]++;
            String prefix = "CHILD[" + depth + ":" + i + "]";
            ModuleLog.line(TAG + prefix + " " + describeView(child));
            dumpImageState(prefix, child);

            if (child instanceof ViewGroup) {
                dumpChildrenRecursive((ViewGroup) child, depth + 1, count);
            }
        }
    }

    private static String describeView(View view) {
        StringBuilder s = new StringBuilder();
        s.append("class=").append(view.getClass().getName());
        s.append(" id=").append(resourceName(view));
        s.append(" size=").append(view.getWidth()).append("x").append(view.getHeight());
        s.append(" measured=").append(view.getMeasuredWidth()).append("x").append(view.getMeasuredHeight());
        s.append(" pos=").append(view.getLeft()).append(",").append(view.getTop())
                .append("-").append(view.getRight()).append(",").append(view.getBottom());
        s.append(" padding=").append(view.getPaddingLeft()).append(",")
                .append(view.getPaddingTop()).append(",")
                .append(view.getPaddingRight()).append(",")
                .append(view.getPaddingBottom());
        s.append(" vis=").append(view.getVisibility());
        s.append(" alpha=").append(view.getAlpha());
        s.append(" clipToOutline=").append(safeClipToOutline(view));
        s.append(" outlineProvider=").append(typeName(safeOutlineProvider(view)));
        s.append(" bg=").append(drawableSummary(view.getBackground()));

        try {
            s.append(" fg=").append(drawableSummary(view.getForeground()));
        } catch (Throwable ignored) {}

        s.append(" layerType=").append(view.getLayerType());
        s.append(" hw=").append(view.isHardwareAccelerated());

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            s.append(" children=").append(group.getChildCount());
            s.append(" clipChildren=").append(group.getClipChildren());
            s.append(" clipToPadding=").append(group.getClipToPadding());
        }

        if (view instanceof ImageView) {
            ImageView iv = (ImageView) view;
            s.append(" imageView=true");
            s.append(" scaleType=").append(iv.getScaleType());
            s.append(" drawable=").append(drawableSummary(iv.getDrawable()));
            try {
                Matrix m = iv.getImageMatrix();
                s.append(" imageMatrix=").append(m == null ? "null" : m.toShortString());
            } catch (Throwable ignored) {}
        }

        return s.toString();
    }

    private static void dumpImageState(String owner, View view) {
        if (!(view instanceof ImageView)) return;

        ImageView iv = (ImageView) view;
        Drawable drawable = iv.getDrawable();

        ModuleLog.line(TAG + owner + " IMAGE drawable=" + drawableSummary(drawable));

        if (drawable != null) {
            dumpDrawableInstance(drawable, owner + ".drawable", 0);
            dumpDrawableClassDeep(drawable.getClass());
            installDrawTrace(drawable.getClass());
        }
    }

    /**
     * Dump one drawable instance's fields. Unlike the first diagnostic pass,
     * every non-static field gets its declared TYPE printed. Unknown object
     * values are represented only by runtime class name, never toString(), so
     * this cannot accidentally dump URLs or account data.
     */
    private static void dumpDrawableInstance(Object object, String label, int depth) {
        if (object == null || depth > MAX_NESTED_OBJECT_DEPTH) return;

        Class<?> rootClass = object.getClass();
        ModuleLog.line(TAG + label + " INSTANCE class=" + rootClass.getName());

        int emitted = 0;
        Class<?> cls = rootClass;

        while (cls != null && cls != Object.class && emitted < MAX_OBJECT_FIELDS) {
            Field[] declared;
            try {
                declared = cls.getDeclaredFields();
            } catch (Throwable t) {
                cls = cls.getSuperclass();
                continue;
            }

            for (Field field : declared) {
                if (emitted >= MAX_OBJECT_FIELDS) break;
                if (Modifier.isStatic(field.getModifiers())) continue;

                Object value = null;
                boolean readable = false;
                try {
                    field.setAccessible(true);
                    value = field.get(object);
                    readable = true;
                } catch (Throwable ignored) {}

                ModuleLog.line(TAG + label
                        + " FIELD owner=" + cls.getName()
                        + " name=" + field.getName()
                        + " declaredType=" + field.getType().getName()
                        + " value=" + (readable ? safeValue(value) : "<unreadable>"));

                emitted++;

                if (readable && shouldRecurseInto(value)) {
                    dumpDrawableInstance(value, label + "." + field.getName(), depth + 1);
                }
            }

            cls = cls.getSuperclass();
        }
    }

    /**
     * Dump reflection metadata for the drawable class and all superclasses:
     * interfaces, constructors, methods and field declarations.
     */
    private static void dumpDrawableClassDeep(Class<?> drawableClass) {
        if (drawableClass == null || !DUMPED_DRAWABLE_CLASSES.add(drawableClass)) return;

        ModuleLog.line(TAG + "========== DRAWABLE CLASS BEGIN "
                + drawableClass.getName() + " ==========");

        Class<?> cls = drawableClass;
        int depth = 0;

        while (cls != null && cls != Object.class && depth < 12) {
            StringBuilder interfaces = new StringBuilder();
            try {
                Class<?>[] ifaces = cls.getInterfaces();
                for (int i = 0; i < ifaces.length; i++) {
                    if (i > 0) interfaces.append(",");
                    interfaces.append(ifaces[i].getName());
                }
            } catch (Throwable ignored) {}

            ModuleLog.line(TAG + "DRAWCLASS[" + depth + "] class=" + cls.getName()
                    + " superclass=" + (cls.getSuperclass() == null
                    ? "null" : cls.getSuperclass().getName())
                    + " interfaces={" + interfaces + "}"
                    + " modifiers=" + Modifier.toString(cls.getModifiers()));

            dumpConstructors(cls, depth);
            dumpDeclaredFields(cls, depth);
            dumpDeclaredMethods(cls, depth);

            cls = cls.getSuperclass();
            depth++;
        }

        ModuleLog.line(TAG + "========== DRAWABLE CLASS END "
                + drawableClass.getName() + " ==========");
    }

    private static void dumpConstructors(Class<?> cls, int depth) {
        try {
            Constructor<?>[] constructors = cls.getDeclaredConstructors();
            for (Constructor<?> constructor : constructors) {
                ModuleLog.line(TAG + "DRAWCLASS[" + depth + "] CTOR "
                        + Modifier.toString(constructor.getModifiers()) + " "
                        + cls.getName() + "(" + parameterTypes(constructor.getParameterTypes()) + ")");
            }
        } catch (Throwable t) {
            ModuleLog.line(TAG + "DRAWCLASS[" + depth + "] CTOR <unavailable>");
        }
    }

    private static void dumpDeclaredFields(Class<?> cls, int depth) {
        try {
            for (Field field : cls.getDeclaredFields()) {
                ModuleLog.line(TAG + "DRAWCLASS[" + depth + "] FIELDDECL "
                        + Modifier.toString(field.getModifiers()) + " "
                        + field.getType().getName() + " "
                        + cls.getName() + "." + field.getName());
            }
        } catch (Throwable t) {
            ModuleLog.line(TAG + "DRAWCLASS[" + depth + "] FIELDDECL <unavailable>");
        }
    }

    private static void dumpDeclaredMethods(Class<?> cls, int depth) {
        try {
            Method[] methods = cls.getDeclaredMethods();
            int emitted = 0;

            for (Method method : methods) {
                if (emitted >= MAX_METHODS_PER_CLASS) {
                    ModuleLog.line(TAG + "DRAWCLASS[" + depth
                            + "] METHOD ... truncated after " + emitted);
                    break;
                }

                ModuleLog.line(TAG + "DRAWCLASS[" + depth + "] METHOD "
                        + Modifier.toString(method.getModifiers()) + " "
                        + method.getReturnType().getName() + " "
                        + cls.getName() + "." + method.getName()
                        + "(" + parameterTypes(method.getParameterTypes()) + ")");

                emitted++;
            }
        } catch (Throwable t) {
            ModuleLog.line(TAG + "DRAWCLASS[" + depth + "] METHOD <unavailable>");
        }
    }

    /**
     * One-shot trace of the drawable's real draw(Canvas) execution. This does
     * not alter arguments/results. It records canvas state and a small stack
     * trace so we can see exactly who invokes the circular renderer.
     */
    private static void installDrawTrace(Class<?> drawableClass) {
        if (drawableClass == null || !HOOKED_DRAWABLE_CLASSES.add(drawableClass)) return;

        Method draw = findMethod(drawableClass, "draw", Canvas.class);
        if (draw == null) {
            ModuleLog.line(TAG + "DRAWTRACE no draw(Canvas) found for "
                    + drawableClass.getName());
            return;
        }

        try {
            draw.setAccessible(true);
            XposedBridge.hookMethod(draw, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof Drawable)) return;
                    if (param.args.length == 0 || !(param.args[0] instanceof Canvas)) return;

                    Drawable drawable = (Drawable) param.thisObject;
                    if (!markDrawableTraced(drawable)) return;

                    Canvas canvas = (Canvas) param.args[0];
                    Rect clip = new Rect();
                    boolean hasClip = false;
                    try {
                        hasClip = canvas.getClipBounds(clip);
                    } catch (Throwable ignored) {}

                    Matrix matrix = new Matrix();
                    String matrixString = "?";
                    try {
                        canvas.getMatrix(matrix);
                        matrixString = matrix.toShortString();
                    } catch (Throwable ignored) {}

                    ModuleLog.line(TAG + "========== DRAWTRACE BEGIN ==========");
                    ModuleLog.line(TAG + "DRAWTRACE drawable="
                            + drawable.getClass().getName()
                            + " bounds=" + drawable.getBounds()
                            + " canvas=" + canvas.getWidth() + "x" + canvas.getHeight()
                            + " hardware=" + canvas.isHardwareAccelerated()
                            + " hasClip=" + hasClip
                            + " clip=" + clip
                            + " matrix=" + matrixString);

                    StackTraceElement[] stack = Thread.currentThread().getStackTrace();
                    int emitted = 0;
                    for (StackTraceElement frame : stack) {
                        String className = frame.getClassName();
                        if (className.startsWith("java.lang.Thread")
                                || className.contains("PfpDiagnostics")
                                || className.startsWith("de.robv.android.xposed")) {
                            continue;
                        }

                        ModuleLog.line(TAG + "DRAWTRACE STACK[" + emitted + "] "
                                + className + "." + frame.getMethodName()
                                + "(" + frame.getFileName() + ":" + frame.getLineNumber() + ")");

                        emitted++;
                        if (emitted >= 18) break;
                    }

                    ModuleLog.line(TAG + "========== DRAWTRACE END ==========");
                }
            });

            ModuleLog.line(TAG + "DRAWTRACE hooked " + draw.getDeclaringClass().getName()
                    + ".draw(Canvas) for runtime class " + drawableClass.getName());
        } catch (Throwable t) {
            HOOKED_DRAWABLE_CLASSES.remove(drawableClass);
            ModuleLog.line(TAG + "DRAWTRACE hook failed for "
                    + drawableClass.getName() + ": " + t);
        }
    }

    private static Method findMethod(Class<?> start, String name, Class<?>... params) {
        Class<?> cls = start;

        while (cls != null && cls != Object.class) {
            try {
                return cls.getDeclaredMethod(name, params);
            } catch (NoSuchMethodException ignored) {
                cls = cls.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }

        return null;
    }

    private static boolean shouldRecurseInto(Object value) {
        if (value == null) return false;

        if (value instanceof Drawable
                || value instanceof Paint
                || value instanceof Shader
                || value instanceof Matrix
                || value instanceof Path
                || value instanceof Outline) {
            return true;
        }

        String name = value.getClass().getName();
        return name.startsWith("X.")
                && !(value instanceof CharSequence)
                && !(value instanceof Number)
                && !(value instanceof Boolean);
    }

    private static String safeValue(Object value) {
        if (value == null) return "null";

        if (value instanceof Bitmap) return bitmapSummary((Bitmap) value);
        if (value instanceof Drawable) return drawableSummary((Drawable) value);

        if (value instanceof Paint) {
            Paint p = (Paint) value;
            return "Paint(style=" + p.getStyle()
                    + ",alpha=" + p.getAlpha()
                    + ",shader=" + typeName(p.getShader())
                    + ",colorFilter=" + typeName(p.getColorFilter()) + ")";
        }

        if (value instanceof Shader) return "Shader(" + value.getClass().getName() + ")";
        if (value instanceof Matrix) return "Matrix(" + ((Matrix) value).toShortString() + ")";
        if (value instanceof Path) return "Path(" + value.getClass().getName() + ")";
        if (value instanceof Rect || value instanceof RectF) return String.valueOf(value);
        if (value instanceof Number || value instanceof Boolean
                || value instanceof Character || value.getClass().isEnum()) {
            return String.valueOf(value);
        }

        if (value.getClass().isArray()) {
            return value.getClass().getName() + "(length=" + java.lang.reflect.Array.getLength(value) + ")";
        }

        // Deliberately do not call arbitrary toString(): it can expose URLs,
        // usernames or model data. Runtime type is enough for reverse engineering.
        return "object(" + value.getClass().getName() + ")";
    }

    private static void dumpViewClassHierarchy(View target) {
        Class<?> cls = target.getClass();
        int depth = 0;

        while (cls != null && View.class.isAssignableFrom(cls) && depth < 12) {
            StringBuilder methods = new StringBuilder();

            for (String name : new String[]{"onDraw", "draw", "dispatchDraw",
                    "setImageBitmap", "setImageDrawable", "setImageResource"}) {
                if (declaresMethodNamed(cls, name)) {
                    if (methods.length() > 0) methods.append(",");
                    methods.append(name);
                }
            }

            ModuleLog.line(TAG + "CLASS[" + depth + "] " + cls.getName()
                    + " declares={" + methods + "}");

            dumpInterestingViewFields(target, cls, depth);

            cls = cls.getSuperclass();
            depth++;
        }
    }

    private static void dumpInterestingViewFields(View target, Class<?> cls, int depth) {
        Field[] fields;
        try {
            fields = cls.getDeclaredFields();
        } catch (Throwable t) {
            return;
        }

        int emitted = 0;
        for (Field field : fields) {
            if (emitted >= MAX_OBJECT_FIELDS) break;
            if (Modifier.isStatic(field.getModifiers())) continue;

            String n = field.getName().toLowerCase();
            String type = field.getType().getName().toLowerCase();

            boolean interesting = n.contains("radius") || n.contains("round")
                    || n.contains("circle") || n.contains("mask")
                    || n.contains("clip") || n.contains("drawable")
                    || n.contains("shader") || n.contains("path")
                    || n.contains("matrix") || n.contains("paint")
                    || type.contains("drawable") || type.contains("bitmap")
                    || type.contains("shader") || type.contains("matrix")
                    || type.contains("path") || type.contains("paint");

            if (!interesting) continue;

            try {
                field.setAccessible(true);
                Object value = field.get(target);
                ModuleLog.line(TAG + "CLASS[" + depth + "] field "
                        + cls.getName() + "." + field.getName()
                        + " type=" + field.getType().getName()
                        + " value=" + safeValue(value));
                emitted++;
            } catch (Throwable ignored) {}
        }
    }

    private static boolean declaresMethodNamed(Class<?> cls, String name) {
        try {
            Method[] methods = cls.getDeclaredMethods();
            for (Method method : methods) {
                if (name.equals(method.getName())) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static String parameterTypes(Class<?>[] types) {
        if (types == null || types.length == 0) return "";

        StringBuilder out = new StringBuilder();
        for (int i = 0; i < types.length; i++) {
            if (i > 0) out.append(",");
            out.append(types[i].getName());
        }
        return out.toString();
    }

    private static String bitmapSummary(Bitmap bitmap) {
        if (bitmap == null) return "null";
        try {
            return "Bitmap(" + bitmap.getWidth() + "x" + bitmap.getHeight()
                    + ",config=" + bitmap.getConfig()
                    + ",alpha=" + bitmap.hasAlpha()
                    + ",recycled=" + bitmap.isRecycled() + ")";
        } catch (Throwable t) {
            return "Bitmap(?)";
        }
    }

    private static String drawableSummary(Drawable drawable) {
        if (drawable == null) return "null";
        try {
            return drawable.getClass().getName()
                    + "(bounds=" + drawable.getBounds()
                    + ",intrinsic=" + drawable.getIntrinsicWidth()
                    + "x" + drawable.getIntrinsicHeight() + ")";
        } catch (Throwable t) {
            return drawable.getClass().getName();
        }
    }

    private static String resourceName(View view) {
        int id = view.getId();
        if (id == View.NO_ID) return "NO_ID";

        try {
            return view.getResources().getResourceName(id)
                    + "(0x" + Integer.toHexString(id) + ")";
        } catch (Throwable ignored) {
            return "0x" + Integer.toHexString(id);
        }
    }

    private static boolean safeClipToOutline(View view) {
        try {
            return view.getClipToOutline();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Object safeOutlineProvider(View view) {
        try {
            return view.getOutlineProvider();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String typeName(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }
}
