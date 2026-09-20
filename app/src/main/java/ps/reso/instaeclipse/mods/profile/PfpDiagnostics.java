package ps.reso.instaeclipse.mods.profile;

import android.graphics.Bitmap;
import android.graphics.Matrix;
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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Diagnostic-only dump for Instagram's expanded profile picture.
 *
 * This never changes rendering. It is invoked by ProfilePicDownloadHook after
 * that existing hook has positively identified the stable "expanded_profile_pic"
 * resource. The goal is to discover which runtime class/drawable actually
 * applies the circular crop before we attempt another rendering hook.
 */
public final class PfpDiagnostics {

    private static final String TAG = "(IE|PFPDiag) ";
    private static final Set<View> DUMPED =
            Collections.newSetFromMap(new WeakHashMap<>());

    private static final int MAX_PARENT_DEPTH = 10;
    private static final int MAX_CHILD_DEPTH = 5;
    private static final int MAX_CHILDREN = 50;
    private static final int MAX_FIELDS = 45;

    private PfpDiagnostics() {}

    public static void schedule(View target) {
        if (target == null) return;

        // onAttachedToWindow can run before final measurement. Posting gives us
        // real dimensions, children and drawable state without touching the UI.
        target.post(() -> dumpOnce(target));
    }

    private static synchronized boolean markDumped(View target) {
        return DUMPED.add(target);
    }

    private static void dumpOnce(View target) {
        if (!markDumped(target)) return;

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
            dumpDrawable(drawable, owner + ".drawable", 0);
        }
    }

    private static void dumpDrawable(Drawable drawable, String label, int depth) {
        if (drawable == null || depth > 3) return;

        ModuleLog.line(TAG + label + " class=" + drawable.getClass().getName()
                + " bounds=" + drawable.getBounds()
                + " intrinsic=" + drawable.getIntrinsicWidth() + "x" + drawable.getIntrinsicHeight()
                + " alpha=" + drawable.getAlpha()
                + " colorFilter=" + typeName(drawable.getColorFilter()));

        if (drawable instanceof BitmapDrawable) {
            Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
            ModuleLog.line(TAG + label + " BitmapDrawable.bitmap=" + bitmapSummary(bitmap));
        }

        int fields = 0;
        Class<?> cls = drawable.getClass();

        while (cls != null && Drawable.class.isAssignableFrom(cls) && fields < MAX_FIELDS) {
            Field[] declared;
            try {
                declared = cls.getDeclaredFields();
            } catch (Throwable t) {
                cls = cls.getSuperclass();
                continue;
            }

            for (Field field : declared) {
                if (fields >= MAX_FIELDS) break;
                if (Modifier.isStatic(field.getModifiers())) continue;

                Object value;
                try {
                    field.setAccessible(true);
                    value = field.get(drawable);
                } catch (Throwable ignored) {
                    continue;
                }

                String summary = graphicValueSummary(value);
                if (summary == null) continue;

                ModuleLog.line(TAG + label + " field "
                        + cls.getName() + "." + field.getName()
                        + "=" + summary);
                fields++;

                if (value instanceof Drawable && value != drawable) {
                    dumpDrawable((Drawable) value, label + "." + field.getName(), depth + 1);
                }
            }

            cls = cls.getSuperclass();
        }
    }

    private static String graphicValueSummary(Object value) {
        if (value == null) return "null";

        if (value instanceof Bitmap) return bitmapSummary((Bitmap) value);
        if (value instanceof Drawable) return drawableSummary((Drawable) value);
        if (value instanceof Shader) return "Shader(" + value.getClass().getName() + ")";
        if (value instanceof Matrix) return "Matrix(" + ((Matrix) value).toShortString() + ")";
        if (value instanceof Path) return "Path(" + value.getClass().getName() + ")";
        if (value instanceof Paint) {
            Paint p = (Paint) value;
            return "Paint(style=" + p.getStyle()
                    + ",shader=" + typeName(p.getShader())
                    + ",alpha=" + p.getAlpha() + ")";
        }
        if (value instanceof Rect || value instanceof RectF) return String.valueOf(value);

        Class<?> c = value.getClass();
        String name = c.getName().toLowerCase();
        if (name.contains("shader") || name.contains("mask") || name.contains("round")
                || name.contains("circle") || name.contains("clip")
                || name.contains("matrix") || name.contains("path")) {
            return c.getName();
        }

        // Primitive wrapper values are useful when the field name itself hints at
        // a radius/rounding flag, but avoid dumping strings/URLs or arbitrary data.
        if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);

        return null;
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
            if (emitted >= MAX_FIELDS) break;
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
                String summary = graphicValueSummary(value);
                if (summary == null) summary = typeName(value);
                ModuleLog.line(TAG + "CLASS[" + depth + "] field "
                        + cls.getName() + "." + field.getName()
                        + " type=" + field.getType().getName()
                        + " value=" + summary);
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
