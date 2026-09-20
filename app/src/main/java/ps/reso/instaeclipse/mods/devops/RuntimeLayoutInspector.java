package ps.reso.instaeclipse.mods.devops;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;

import java.lang.reflect.Field;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Temporary in-process layout inspector for Instagram.
 * Long-press any view for ~900ms to dump the deepest view under the finger and its ancestry.
 * Output goes to the normal InstaEclipse module log.
 */
public final class RuntimeLayoutInspector {
    private static final String TAG = "(IE|LayoutInspector) ";
    private static boolean installed;
    private static float downX, downY;
    private static long downAt;

    private RuntimeLayoutInspector() {}

    public static synchronized void install(ClassLoader cl) {
        if (installed) return;
        installed = true;
        XposedBridge.hookAllMethods(Activity.class, "onResume", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Activity a = (Activity) param.thisObject;
                attach(a);
            }
        });
        ModuleLog.line(TAG + "installed; long-press (~0.9s) the profile picture to inspect it");
    }

    private static void attach(Activity a) {
        try {
            Window w = a.getWindow();
            if (w == null) return;
            View decor = w.getDecorView();
            final int key = 0x49E1C5E;
            if (decor.getTag(key) != null) return;
            decor.setTag(key, Boolean.TRUE);
            decor.setOnTouchListener((v, e) -> {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    downX = e.getRawX(); downY = e.getRawY(); downAt = System.currentTimeMillis();
                } else if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                    long held = System.currentTimeMillis() - downAt;
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    if (held >= 850 && dx * dx + dy * dy < 900) {
                        View hit = deepestAt(v, e.getRawX(), e.getRawY());
                        dump(hit, e.getRawX(), e.getRawY());
                    }
                }
                return false;
            });
        } catch (Throwable t) {
            ModuleLog.line(TAG + "attach failed: " + t);
        }
    }

    private static View deepestAt(View root, float rawX, float rawY) {
        if (!(root instanceof ViewGroup)) return root;
        ViewGroup g = (ViewGroup) root;
        for (int i = g.getChildCount() - 1; i >= 0; i--) {
            View c = g.getChildAt(i);
            if (c.getVisibility() != View.VISIBLE || !contains(c, rawX, rawY)) continue;
            return deepestAt(c, rawX, rawY);
        }
        return root;
    }

    private static boolean contains(View v, float x, float y) {
        int[] p = new int[2];
        v.getLocationOnScreen(p);
        return x >= p[0] && y >= p[1] && x < p[0] + v.getWidth() && y < p[1] + v.getHeight();
    }

    private static void dump(View hit, float x, float y) {
        ModuleLog.line(TAG + "========== HIT @ " + (int)x + "," + (int)y + " ==========");
        View cur = hit;
        for (int depth = 0; cur != null && depth < 12; depth++) {
            ModuleLog.line(TAG + "[" + depth + "] " + describe(cur));
            dumpFields(cur, depth);
            Object p = cur.getParent();
            cur = p instanceof View ? (View) p : null;
        }
        ModuleLog.line(TAG + "========== END ==========");
    }

    private static String describe(View v) {
        String id = "NO_ID";
        try {
            if (v.getId() != View.NO_ID) id = v.getResources().getResourceName(v.getId());
        } catch (Throwable ignored) { id = "0x" + Integer.toHexString(v.getId()); }

        StringBuilder s = new StringBuilder();
        s.append(v.getClass().getName()).append(" id=").append(id)
         .append(" size=").append(v.getWidth()).append("x").append(v.getHeight())
         .append(" clipToOutline=").append(v.getClipToOutline())
         .append(" outlineProvider=").append(v.getOutlineProvider() == null ? "null" : v.getOutlineProvider().getClass().getName())
         .append(" bg=").append(className(v.getBackground()));

        if (v instanceof ImageView) {
            ImageView iv = (ImageView) v;
            Drawable d = iv.getDrawable();
            s.append(" IMAGE scale=").append(iv.getScaleType())
             .append(" drawable=").append(className(d));
            if (d != null) s.append(" intrinsic=").append(d.getIntrinsicWidth()).append("x").append(d.getIntrinsicHeight());
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            s.append(" GROUP children=").append(vg.getChildCount())
             .append(" clipChildren=").append(vg.getClipChildren())
             .append(" clipPadding=").append(vg.getClipToPadding());
        }
        return s.toString();
    }

    private static String className(Object o) { return o == null ? "null" : o.getClass().getName(); }

    private static void dumpFields(View v, int depth) {
        // Circle masks in IG are often implemented by custom ImageView/Drawable fields rather than View outlines.
        Class<?> c = v.getClass();
        int emitted = 0;
        while (c != null && c != View.class && emitted < 20) {
            for (Field f : c.getDeclaredFields()) {
                if (emitted >= 20) break;
                String n = f.getName().toLowerCase();
                if (!(n.contains("radius") || n.contains("round") || n.contains("circle") ||
                      n.contains("mask") || n.contains("clip") || n.contains("drawable") ||
                      n.contains("shader") || n.contains("path") || n.contains("matrix"))) continue;
                try {
                    f.setAccessible(true);
                    Object value = f.get(v);
                    ModuleLog.line(TAG + "[" + depth + "] field " + c.getName() + "." + f.getName() +
                            "=" + String.valueOf(value) + " (" + className(value) + ")");
                    emitted++;
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
    }
}
