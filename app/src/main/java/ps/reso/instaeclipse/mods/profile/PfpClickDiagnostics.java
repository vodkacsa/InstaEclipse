package ps.reso.instaeclipse.mods.profile;

import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewPropertyAnimator;
import android.view.animation.Animation;
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
 * The listener/delegate graph is identical on working and glitched accounts,
 * so this traces what Instagram's actual delegate does during onClick().
 * Only UI mutations that happen synchronously while that handler is executing
 * are logged.
 */
public final class PfpClickDiagnostics {

    private static final String TAG = "(IE|PFPClickDiag) ";
    private static final String TARGET_ID = "row_profile_header_imageview_frame_layout";

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

        installUiMutationHooks();

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

                    ModuleLog.line(TAG + "HANDLER_BEGIN class="
                            + param.thisObject.getClass().getName());
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

    private static void installUiMutationHooks() {
        XposedHelpers.findAndHookMethod(View.class, "setVisibility",
                int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!handlerActive()) return;
                        View v = (View) param.thisObject;
                        logAction("setVisibility",
                                v,
                                "from=" + v.getVisibility()
                                        + " to=" + String.valueOf(param.args[0]));
                    }
                });

        hookFloatSetter(View.class, "setAlpha");
        hookFloatSetter(View.class, "setScaleX");
        hookFloatSetter(View.class, "setScaleY");
        hookFloatSetter(View.class, "setTranslationX");
        hookFloatSetter(View.class, "setTranslationY");

        XposedHelpers.findAndHookMethod(View.class, "startAnimation",
                Animation.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!handlerActive()) return;
                        View v = (View) param.thisObject;
                        Object animation = param.args[0];
                        logAction("startAnimation",
                                v,
                                "animation=" + className(animation));
                    }
                });

        XposedHelpers.findAndHookMethod(View.class, "post",
                Runnable.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!handlerActive()) return;
                        View v = (View) param.thisObject;
                        logAction("post",
                                v,
                                "runnable=" + className(param.args[0]));
                    }
                });

        XposedHelpers.findAndHookMethod(ImageView.class, "setImageDrawable",
                Drawable.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!handlerActive()) return;
                        ImageView v = (ImageView) param.thisObject;
                        logAction("setImageDrawable",
                                v,
                                "drawable=" + className(param.args[0]));
                    }
                });

        XposedHelpers.findAndHookMethod(ImageView.class, "setImageBitmap",
                Bitmap.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!handlerActive()) return;
                        ImageView v = (ImageView) param.thisObject;
                        Bitmap bitmap = (Bitmap) param.args[0];
                        String detail = bitmap == null
                                ? "bitmap=null"
                                : "bitmap=" + bitmap.getWidth() + "x" + bitmap.getHeight();
                        logAction("setImageBitmap", v, detail);
                    }
                });

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
                            String layoutName = "0x" + Integer.toHexString(layoutId);

                            try {
                                LayoutInflater inflater = (LayoutInflater) param.thisObject;
                                layoutName = inflater.getContext()
                                        .getResources()
                                        .getResourceEntryName(layoutId);
                            } catch (Throwable ignored) {}

                            ModuleLog.line(TAG + "ACTION inflate"
                                    + " layout=" + layoutName
                                    + " parent=" + describeNullable((View) param.args[1])
                                    + " attach=" + String.valueOf(param.args[2]));
                        }
                    });
        } catch (Throwable ignored) {}

        try {
            for (Method method : ViewGroup.class.getDeclaredMethods()) {
                if (!"addView".equals(method.getName())) continue;

                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!handlerActive()) return;

                        ViewGroup parent = (ViewGroup) param.thisObject;
                        View child = null;

                        for (Object arg : param.args) {
                            if (arg instanceof View) {
                                child = (View) arg;
                                break;
                            }
                        }

                        ModuleLog.line(TAG + "ACTION addView"
                                + " parent=" + describe(parent)
                                + " child=" + describeNullable(child));
                    }
                });
            }
        } catch (Throwable ignored) {}

        hookAnimatorSetter("alpha");
        hookAnimatorSetter("scaleX");
        hookAnimatorSetter("scaleY");
        hookAnimatorSetter("translationX");
        hookAnimatorSetter("translationY");

        try {
            XposedHelpers.findAndHookMethod(
                    ViewPropertyAnimator.class,
                    "setDuration",
                    long.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!handlerActive()) return;
                            ModuleLog.line(TAG + "ACTION animator.setDuration value="
                                    + String.valueOf(param.args[0]));
                        }
                    });
        } catch (Throwable ignored) {}

        try {
            XposedHelpers.findAndHookMethod(
                    ViewPropertyAnimator.class,
                    "start",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!handlerActive()) return;
                            ModuleLog.line(TAG + "ACTION animator.start");
                        }
                    });
        } catch (Throwable ignored) {}
    }

    private static void hookFloatSetter(Class<?> cls, String methodName) {
        try {
            XposedHelpers.findAndHookMethod(
                    cls,
                    methodName,
                    float.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!handlerActive()) return;
                            View v = (View) param.thisObject;
                            logAction(methodName,
                                    v,
                                    "value=" + String.valueOf(param.args[0]));
                        }
                    });
        } catch (Throwable ignored) {}
    }

    private static void hookAnimatorSetter(String methodName) {
        try {
            XposedHelpers.findAndHookMethod(
                    ViewPropertyAnimator.class,
                    methodName,
                    float.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!handlerActive()) return;
                            ModuleLog.line(TAG + "ACTION animator."
                                    + methodName
                                    + " value=" + String.valueOf(param.args[0]));
                        }
                    });
        } catch (Throwable ignored) {}
    }

    private static boolean handlerActive() {
        Integer depth = HANDLER_DEPTH.get();
        return depth != null && depth > 0;
    }

    private static void logAction(String action, View view, String detail) {
        ModuleLog.line(TAG + "ACTION " + action
                + " " + describe(view)
                + " " + detail);
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
                + " visibility=" + view.getVisibility();
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

    private static String resourceName(View view) {
        if (view == null || view.getId() == View.NO_ID) return "NO_ID";

        try {
            return view.getResources().getResourceEntryName(view.getId());
        } catch (Throwable ignored) {
            return "0x" + Integer.toHexString(view.getId());
        }
    }
}
