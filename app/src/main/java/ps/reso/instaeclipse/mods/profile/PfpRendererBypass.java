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
 * Runtime diagnostics established that:
 *  - expanded_profile_pic is the real square CircularImageView;
 *  - disabling View outline clipping alone does not remove the circle;
 *  - its active drawable is an obfuscated wrapper (currently X.3cW);
 *  - that wrapper's draw(Canvas) is what ImageView.onDraw() calls;
 *  - inside that wrapper is Instagram's already-loaded image drawable
 *    (observed as X.2sN, intrinsic 1080x1080).
 *
 * For the exact expanded_profile_pic only, this class:
 *  1. disables outline clipping;
 *  2. remembers the active root drawable;
 *  3. hooks that root drawable's draw(Canvas);
 *  4. finds the best nested, already-loaded Drawable;
 *  5. draws that nested drawable directly with FIT_CENTER bounds;
 *  6. skips the wrapper's original circular rendering.
 *
 * No overlay, duplicate ImageView, network request, or bitmap re-download.
 */
public final class PfpRendererBypass {

    private static final String TAG = "(IE|PFPBypass) ";

    private static final Map<Drawable, WeakReference<View>> ROOT_VIEWS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static final Map<Drawable, WeakReference<Drawable>> INNER_CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static final Set<Class<?>> HOOKED_DRAW_CLASSES =
            Collections.synchronizedSet(new HashSet<>());

    private static final Set<Drawable> LOGGED_ROOTS =
            Collections.newSetFromMap(new WeakHashMap<>());

    private static final Set<Drawable> LOGGED_MISSES =
            Collections.newSetFromMap(new WeakHashMap<>());

    private static volatile boolean installed;

    private PfpRendererBypass() {}

    public static synchronized void install() {
        if (installed) return;

        // Keep the exact expanded profile picture from being clipped back to the
        // circular outline after we bypass the circular drawable renderer.
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

        // Re-arm if Instagram swaps the image drawable after the view has already
        // attached. The target check keeps this a no-op for every other ImageView.
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
     * Called by the already-working ProfilePicDownloadHook after it has positively
     * identified resource name expanded_profile_pic.
     */
    public static void arm(View target) {
        if (!(target instanceof ImageView)) return;
        if (!isExpandedProfilePicture(target)) return;

        try {
            target.setClipToOutline(false);

            Drawable root = ((ImageView) target).getDrawable();
            if (root == null) {
                target.post(() -> registerCurrentDrawable(target));
                return;
            }

            registerRoot(target, root);
            target.invalidate();
        } catch (Throwable t) {
            ModuleLog.line(TAG + "arm failed: " + t);
        }
    }

    private static void registerCurrentDrawable(View target) {
        if (!(target instanceof ImageView)) return;
        if (!isExpandedProfilePicture(target)) return;

        try {
            Drawable root = ((ImageView) target).getDrawable();
            if (root != null) registerRoot(target, root);
        } catch (Throwable t) {
            ModuleLog.line(TAG + "late register failed: " + t);
        }
    }

    private static void registerRoot(View target, Drawable root) {
        ROOT_VIEWS.put(root, new WeakReference<>(target));
        hookDrawClass(root.getClass());

        Candidate candidate = findBestNestedDrawable(root);
        if (candidate != null && candidate.drawable != null) {
            INNER_CACHE.put(root, new WeakReference<>(candidate.drawable));

            synchronized (LOGGED_ROOTS) {
                if (LOGGED_ROOTS.add(root)) {
                    ModuleLog.line(TAG + "armed root=" + root.getClass().getName()
                            + " inner=" + candidate.drawable.getClass().getName()
                            + " path=" + candidate.path
                            + " intrinsic=" + candidate.drawable.getIntrinsicWidth()
                            + "x" + candidate.drawable.getIntrinsicHeight());
                }
            }
        } else {
            synchronized (LOGGED_MISSES) {
                if (LOGGED_MISSES.add(root)) {
                    ModuleLog.line(TAG + "root registered but nested image drawable not found yet: "
                            + root.getClass().getName());
                }
            }
        }
    }

    private static void hookDrawClass(Class<?> runtimeClass) {
        if (runtimeClass == null || !HOOKED_DRAW_CLASSES.add(runtimeClass)) return;

        Method draw = findDrawMethod(runtimeClass);
        if (draw == null) {
            ModuleLog.line(TAG + "no draw(Canvas) found for " + runtimeClass.getName());
            return;
        }

        try {
            draw.setAccessible(true);
            XposedBridge.hookMethod(draw, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof Drawable)) return;
                    if (param.args.length == 0 || !(param.args[0] instanceof Canvas)) return;

                    Drawable root = (Drawable) param.thisObject;
                    View target = targetForRoot(root);
                    if (!(target instanceof ImageView)) return;
                    if (((ImageView) target).getDrawable() != root) return;
                    if (!isExpandedProfilePicture(target)) return;

                    try {
                        target.setClipToOutline(false);

                        Drawable inner = cachedInner(root);
                        if (inner == null) {
                            Candidate candidate = findBestNestedDrawable(root);
                            if (candidate != null) {
                                inner = candidate.drawable;
                                INNER_CACHE.put(root, new WeakReference<>(inner));
                                ModuleLog.line(TAG + "late inner="
                                        + inner.getClass().getName()
                                        + " path=" + candidate.path
                                        + " intrinsic=" + inner.getIntrinsicWidth()
                                        + "x" + inner.getIntrinsicHeight());
                            }
                        }

                        if (inner == null || inner == root) {
                            // Safe fallback: leave Instagram's original draw untouched.
                            return;
                        }

                        drawNestedFitCenter(
                                (Canvas) param.args[0],
                                root.getBounds(),
                                inner
                        );

                        // Only suppress Instagram's circular wrapper after our
                        // nested image has been drawn successfully.
                        param.setResult(null);
                    } catch (Throwable t) {
                        ModuleLog.line(TAG + "draw bypass failed: " + t);
                        // Do not set a result here, so Instagram falls back to
                        // its original renderer rather than showing a blank view.
                    }
                }
            });

            ModuleLog.line(TAG + "hooked " + draw.getDeclaringClass().getName()
                    + ".draw(Canvas) for " + runtimeClass.getName());
        } catch (Throwable t) {
            HOOKED_DRAW_CLASSES.remove(runtimeClass);
            ModuleLog.line(TAG + "draw hook failed for " + runtimeClass.getName() + ": " + t);
        }
    }

    private static View targetForRoot(Drawable root) {
        WeakReference<View> ref = ROOT_VIEWS.get(root);
        return ref == null ? null : ref.get();
    }

    private static Drawable cachedInner(Drawable root) {
        WeakReference<Drawable> ref = INNER_CACHE.get(root);
        return ref == null ? null : ref.get();
    }

    private static void drawNestedFitCenter(Canvas canvas, Rect rootBounds, Drawable inner) {
        if (rootBounds == null || rootBounds.isEmpty()) return;

        Rect oldBounds = new Rect(inner.getBounds());

        int sourceW = inner.getIntrinsicWidth();
        int sourceH = inner.getIntrinsicHeight();

        Rect dst;
        if (sourceW > 0 && sourceH > 0) {
            float scale = Math.min(
                    rootBounds.width() / (float) sourceW,
                    rootBounds.height() / (float) sourceH
            );

            int width = Math.max(1, Math.round(sourceW * scale));
            int height = Math.max(1, Math.round(sourceH * scale));
            int left = rootBounds.left + (rootBounds.width() - width) / 2;
            int top = rootBounds.top + (rootBounds.height() - height) / 2;

            dst = new Rect(left, top, left + width, top + height);
        } else {
            dst = new Rect(rootBounds);
        }

        int save = canvas.save();
        try {
            inner.setBounds(dst);
            inner.draw(canvas);
        } finally {
            inner.setBounds(oldBounds);
            canvas.restoreToCount(save);
        }
    }

    /**
     * Finds Instagram's already-loaded image drawable inside the root rendering
     * wrapper. The current app version exposes it a few levels down (observed as
     * X.3cW -> X.3dD -> X.3dO -> X.2sN), but this search intentionally keys off
     * runtime types/Drawable semantics instead of those obfuscated names.
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
            // Do not walk framework Drawable internals such as callback/state.
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
                    // A drawable candidate is already a renderable endpoint.
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

        // Loaded image drawables have real intrinsic dimensions. Decorative
        // masks/placeholders in this renderer generally do not.
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

        // The renderer graph observed in Instagram is composed of obfuscated X.*
        // classes. Also permit Instagram-owned helper classes, while avoiding
        // Resources/Context/collections and other huge framework object graphs.
        return name.startsWith("X.")
                || name.startsWith("com.instagram.");
    }

    private static Method findDrawMethod(Class<?> start) {
        Class<?> cls = start;

        while (cls != null && cls != Object.class) {
            try {
                return cls.getDeclaredMethod("draw", Canvas.class);
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
