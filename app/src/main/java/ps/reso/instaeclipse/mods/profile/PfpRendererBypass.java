package ps.reso.instaeclipse.mods.profile;

import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Expanded profile-picture renderer bypass.
 *
 * Important rule: do not touch Instagram's expanded-profile container,
 * background, translation, scale, or animation. Only replace what the exact
 * expanded_profile_pic CircularImageView paints inside its own onDraw(Canvas).
 *
 * Diagnostics showed the raw image drawable is already present below the
 * obfuscated root renderer. CircularImageView.onDraw() is therefore bypassed
 * before Instagram can apply its circular canvas treatment.
 */
public final class PfpRendererBypass {

    private static final String TAG = "(IE|PFPBypass) ";

    private static final Map<Drawable, WeakReference<Drawable>> INNER_CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static final Set<Class<?>> HOOKED_VIEW_CLASSES =
            Collections.synchronizedSet(new HashSet<>());

    private static final Set<View> LOGGED_VIEWS =
            Collections.newSetFromMap(new WeakHashMap<>());

    private static final Set<Drawable> LOGGED_INNERS =
            Collections.newSetFromMap(new WeakHashMap<>());

    private static volatile boolean installed;

    private PfpRendererBypass() {}

    public static synchronized void install() {
        if (installed) return;

        // View-outline clipping happens outside onDraw(), so keep it disabled
        // only for the exact expanded profile picture. No parent is touched.
        XposedHelpers.findAndHookMethod(View.class, "setClipToOutline",
                boolean.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!(param.thisObject instanceof View)) return;

                        View view = (View) param.thisObject;
                        if (!isExpandedProfilePicture(view)) return;

                        if (param.args.length > 0 && Boolean.TRUE.equals(param.args[0])) {
                            param.args[0] = false;
                        }
                    }
                });

        // Instagram can swap the root image drawable after attachment.
        XposedHelpers.findAndHookMethod(ImageView.class, "setImageDrawable",
                Drawable.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!(param.thisObject instanceof View)) return;

                        View view = (View) param.thisObject;
                        if (!isExpandedProfilePicture(view)) return;

                        view.post(() -> arm(view));
                    }
                });

        installed = true;
        ModuleLog.line(TAG + "installed");
    }

    /**
     * Called by ProfilePicDownloadHook after it has positively identified
     * resource name expanded_profile_pic.
     */
    public static void arm(View target) {
        if (!(target instanceof ImageView)) return;
        if (!isExpandedProfilePicture(target)) return;

        try {
            target.setClipToOutline(false);
            hookCircularOnDraw(target.getClass());

            Drawable root = ((ImageView) target).getDrawable();
            if (root != null) {
                cacheInner(root);
            }

            synchronized (LOGGED_VIEWS) {
                if (LOGGED_VIEWS.add(target)) {
                    ModuleLog.line(TAG + "armed view=" + target.getClass().getName()
                            + " size=" + target.getWidth() + "x" + target.getHeight()
                            + " root=" + (root == null ? "null" : root.getClass().getName()));
                }
            }

            target.invalidate();
        } catch (Throwable t) {
            ModuleLog.line(TAG + "arm failed: " + t);
        }
    }

    /**
     * Hook the concrete CircularImageView.onDraw(Canvas), not Drawable.draw().
     * This runs before Instagram's own onDraw code can clip/shape the canvas,
     * while still receiving the framework canvas with the view's existing
     * translation/scale/animation already applied.
     */
    private static void hookCircularOnDraw(Class<?> runtimeClass) {
        if (runtimeClass == null || !HOOKED_VIEW_CLASSES.add(runtimeClass)) return;

        Method onDraw = findOnDrawMethod(runtimeClass);
        if (onDraw == null) {
            HOOKED_VIEW_CLASSES.remove(runtimeClass);
            ModuleLog.line(TAG + "no onDraw(Canvas) found for " + runtimeClass.getName());
            return;
        }

        try {
            onDraw.setAccessible(true);

            XposedBridge.hookMethod(onDraw, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof ImageView)) return;
                    if (param.args.length == 0 || !(param.args[0] instanceof Canvas)) return;

                    ImageView view = (ImageView) param.thisObject;
                    if (!isExpandedProfilePicture(view)) return;

                    Drawable root = view.getDrawable();
                    if (root == null) return;

                    Drawable inner = cachedInner(root);
                    if (inner == null || inner == root) {
                        Candidate candidate = findBestNestedDrawable(root);
                        if (candidate != null) {
                            inner = candidate.drawable;
                            INNER_CACHE.put(root, new WeakReference<>(inner));
                            logInner(root, candidate);
                        }
                    }

                    if (inner == null || inner == root) {
                        // Leave Instagram's original onDraw untouched until the
                        // real image is loaded.
                        return;
                    }

                    try {
                        view.setClipToOutline(false);

                        drawInsideView(
                                (Canvas) param.args[0],
                                view,
                                inner
                        );

                        // Replacement draw succeeded, so skip CircularImageView's
                        // original onDraw and its circular canvas/render logic.
                        param.setResult(null);
                    } catch (Throwable t) {
                        ModuleLog.line(TAG + "onDraw bypass failed: " + t);
                        // Safe fallback: original Instagram onDraw still runs.
                    }
                }
            });

            ModuleLog.line(TAG + "hooked "
                    + onDraw.getDeclaringClass().getName()
                    + ".onDraw(Canvas) for runtime class "
                    + runtimeClass.getName());
        } catch (Throwable t) {
            HOOKED_VIEW_CLASSES.remove(runtimeClass);
            ModuleLog.line(TAG + "onDraw hook failed for "
                    + runtimeClass.getName() + ": " + t);
        }
    }

    /**
     * Draw only inside the ImageView's own content area. We deliberately do
     * not save/restore or alter parent/container transforms because the Canvas
     * supplied to onDraw already contains Instagram's current animation state.
     */
    private static void drawInsideView(Canvas canvas, ImageView view, Drawable inner) {
        int left = view.getPaddingLeft();
        int top = view.getPaddingTop();
        int right = view.getWidth() - view.getPaddingRight();
        int bottom = view.getHeight() - view.getPaddingBottom();

        if (right <= left || bottom <= top) return;

        Rect oldBounds = new Rect(inner.getBounds());

        int sourceW = inner.getIntrinsicWidth();
        int sourceH = inner.getIntrinsicHeight();

        Rect dst;
        if (sourceW > 0 && sourceH > 0) {
            int boxW = right - left;
            int boxH = bottom - top;

            // FIT_CENTER: reveal the whole square/raw profile image without
            // cropping while preserving its aspect ratio.
            float scale = Math.min(
                    boxW / (float) sourceW,
                    boxH / (float) sourceH
            );

            int width = Math.max(1, Math.round(sourceW * scale));
            int height = Math.max(1, Math.round(sourceH * scale));
            int x = left + (boxW - width) / 2;
            int y = top + (boxH - height) / 2;

            dst = new Rect(x, y, x + width, y + height);
        } else {
            dst = new Rect(left, top, right, bottom);
        }

        try {
            inner.setBounds(dst);
            inner.draw(canvas);
        } finally {
            inner.setBounds(oldBounds);
        }
    }

    private static void cacheInner(Drawable root) {
        Candidate candidate = findBestNestedDrawable(root);
        if (candidate == null || candidate.drawable == null) return;

        INNER_CACHE.put(root, new WeakReference<>(candidate.drawable));
        logInner(root, candidate);
    }

    private static void logInner(Drawable root, Candidate candidate) {
        synchronized (LOGGED_INNERS) {
            if (!LOGGED_INNERS.add(root)) return;
        }

        ModuleLog.line(TAG + "inner=" + candidate.drawable.getClass().getName()
                + " path=" + candidate.path
                + " intrinsic=" + candidate.drawable.getIntrinsicWidth()
                + "x" + candidate.drawable.getIntrinsicHeight());
    }

    private static Drawable cachedInner(Drawable root) {
        WeakReference<Drawable> ref = INNER_CACHE.get(root);
        return ref == null ? null : ref.get();
    }

    /**
     * Locate the already-loaded image drawable inside Instagram's obfuscated
     * renderer graph without relying on obfuscated class/field names.
     */
    private static Candidate findBestNestedDrawable(Drawable root) {
        Candidate best = new Candidate();
        IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
        scanObject(root, root, "root", 0, visited, best);
        return best.drawable == null ? null : best;
    }

    private static void scanObject(
            Object object,
            Drawable root,
            String path,
            int depth,
            IdentityHashMap<Object, Boolean> visited,
            Candidate best
    ) {
        if (object == null || depth > 6) return;
        if (visited.put(object, Boolean.TRUE) != null) return;

        Class<?> cls = object.getClass();

        while (cls != null && cls != Object.class) {
            if (cls == Drawable.class) break;

            Field[] fields;
            try {
                fields = cls.getDeclaredFields();
            } catch (Throwable t) {
                cls = cls.getSuperclass();
                continue;
            }

            for (Field field : fields) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                if (field.isSynthetic()) continue;

                Object value;
                try {
                    field.setAccessible(true);
                    value = field.get(object);
                } catch (Throwable ignored) {
                    continue;
                }

                if (value == null || value == object) continue;

                String childPath = path + "." + field.getName();

                if (value instanceof Drawable) {
                    Drawable drawable = (Drawable) value;

                    if (drawable != root) {
                        int score = scoreDrawable(drawable, depth + 1);
                        if (score > best.score) {
                            best.score = score;
                            best.drawable = drawable;
                            best.path = childPath;
                        }
                    }

                    continue;
                }

                if (shouldTraverse(value)) {
                    scanObject(value, root, childPath, depth + 1, visited, best);
                }
            }

            cls = cls.getSuperclass();
        }
    }

    private static int scoreDrawable(Drawable drawable, int depth) {
        int w;
        int h;

        try {
            w = drawable.getIntrinsicWidth();
            h = drawable.getIntrinsicHeight();
        } catch (Throwable t) {
            return 1;
        }

        int score = 100 - Math.min(depth, 20);

        if (w > 0 && h > 0) {
            score += 100000;
            score += Math.min(50000, Math.min(w, h));
        }

        Rect bounds = drawable.getBounds();
        if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
            score += 1000;
        }

        return score;
    }

    private static boolean shouldTraverse(Object value) {
        if (value == null) return false;

        if (value instanceof View
                || value instanceof Drawable
                || value instanceof CharSequence
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Character
                || value.getClass().isEnum()
                || value.getClass().isArray()) {
            return false;
        }

        String name = value.getClass().getName();
        return name.startsWith("X.")
                || name.startsWith("com.instagram.");
    }

    private static Method findOnDrawMethod(Class<?> start) {
        Class<?> cls = start;

        while (cls != null && cls != Object.class) {
            try {
                return cls.getDeclaredMethod("onDraw", Canvas.class);
            } catch (NoSuchMethodException ignored) {
                cls = cls.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }

        return null;
    }

    private static boolean isExpandedProfilePicture(View view) {
        if (view == null || view.getId() == View.NO_ID) return false;

        try {
            return "expanded_profile_pic".equals(
                    view.getResources().getResourceEntryName(view.getId()));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static final class Candidate {
        Drawable drawable;
        String path;
        int score = Integer.MIN_VALUE;
    }
}
