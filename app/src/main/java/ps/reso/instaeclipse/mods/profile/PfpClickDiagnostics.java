package ps.reso.instaeclipse.mods.profile;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Manually armed PFP click diagnostics.
 *
 * A small floating "PFP LOG" button is added to every Instagram Activity.
 * Pressing it arms exactly one capture. The next touch dispatched through the
 * current profile-picture frame starts logging before Instagram handles the
 * event, so the capture still works when performClick() never happens.
 *
 * On ACTION_UP the full capture is copied to the Android clipboard and remains
 * in Logcat under IE|PFPClickDiag. This class is diagnostics-only and never
 * changes Instagram's click result or native viewer behavior.
 */
public final class PfpClickDiagnostics {

    private static final String TAG = "(IE|PFPClickDiag) ";
    private static final String TARGET_ID = "row_profile_header_imageview_frame_layout";
    private static final String EXPANDED_LAYOUT = "layout_expanded_profile_picture_view";
    private static final String BUTTON_TAG = "ie_pfp_diag_floating_button";

    private static final AtomicInteger NEXT_CAPTURE = new AtomicInteger(1);

    private static final ThreadLocal<Integer> ACTIVE_CAPTURE = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> OPENED =
            ThreadLocal.withInitial(() -> false);

    private static final Object BUFFER_LOCK = new Object();
    private static final StringBuilder BUFFER = new StringBuilder();

    private static final Set<Class<?>> HOOKED_HANDLER_CLASSES =
            Collections.synchronizedSet(new HashSet<>());
    private static final Set<String> HOOKED_TRACE_METHODS =
            Collections.synchronizedSet(new HashSet<>());
    private static final WeakHashMap<Activity, TextView> BUTTONS =
            new WeakHashMap<>();

    private static volatile boolean installed;
    private static volatile boolean armed;
    private static volatile View latestTarget;
    private static volatile TextView latestButton;

    private PfpClickDiagnostics() {}

    public static void observeAttached(View view) {
        if (view == null) return;

        installBaseHooks();
        installKnownPathHooks(view.getClass().getClassLoader());

        Activity activity = activityFromContext(view.getContext());
        if (activity != null) {
            ensureFloatingButton(activity);
        }

        if (!isTarget(view)) return;

        latestTarget = view;

        view.post(() -> {
            try {
                Object wrapper = clickListener(view);
                Object delegate = unwrapDelegate(wrapper);
                hookNativeHandler(delegate);

                ModuleLog.line(TAG + "TARGET"
                        + " wrapper=" + className(wrapper)
                        + " delegate=" + className(delegate)
                        + " " + describe(view));
            } catch (Throwable t) {
                ModuleLog.line(TAG + "TARGET_ERROR " + error(t));
            }
        });
    }

    private static synchronized void installBaseHooks() {
        if (installed) return;

        try {
            XposedHelpers.findAndHookMethod(
                    Activity.class,
                    "dispatchTouchEvent",
                    MotionEvent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!(param.thisObject instanceof Activity)
                                    || !(param.args[0] instanceof MotionEvent)) return;

                            MotionEvent event = (MotionEvent) param.args[0];

                            if (armed
                                    && ACTIVE_CAPTURE.get() == null
                                    && event.getActionMasked() == MotionEvent.ACTION_DOWN
                                    && pointInside(latestTarget, event.getRawX(), event.getRawY())) {
                                beginCapture(latestTarget, event);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (ACTIVE_CAPTURE.get() == null
                                    || !(param.args[0] instanceof MotionEvent)) return;

                            MotionEvent event = (MotionEvent) param.args[0];
                            int action = event.getActionMasked();

                            if (action == MotionEvent.ACTION_UP
                                    || action == MotionEvent.ACTION_CANCEL) {
                                captureLog("ACTIVITY_TOUCH_END #"
                                        + ACTIVE_CAPTURE.get()
                                        + " action=" + actionName(action)
                                        + " result=" + safeValue(param.getResult())
                                        + " opened=" + OPENED.get());

                                finishCapture(((Activity) param.thisObject));
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            ModuleLog.line(TAG + "activity dispatch hook failed=" + error(t));
        }

        try {
            XposedHelpers.findAndHookMethod(
                    View.class,
                    "dispatchTouchEvent",
                    MotionEvent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!(param.thisObject instanceof View)
                                    || !(param.args[0] instanceof MotionEvent)) {
                                return;
                            }

                            View view = (View) param.thisObject;
                            MotionEvent event = (MotionEvent) param.args[0];

                            if (ACTIVE_CAPTURE.get() == null) return;

                            captureLog("VIEW_TOUCH_BEGIN #"
                                    + ACTIVE_CAPTURE.get()
                                    + " action=" + actionName(event.getActionMasked())
                                    + " view=" + describe(view));
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!(param.thisObject instanceof View)
                                    || !(param.args[0] instanceof MotionEvent)) {
                                return;
                            }

                            View view = (View) param.thisObject;
                            MotionEvent event = (MotionEvent) param.args[0];

                            if (ACTIVE_CAPTURE.get() == null) return;

                            captureLog("VIEW_TOUCH_END #"
                                    + ACTIVE_CAPTURE.get()
                                    + " action=" + actionName(event.getActionMasked())
                                    + " view=" + describe(view)
                                    + " result=" + safeValue(param.getResult()));
                        }
                    }
            );
        } catch (Throwable t) {
            ModuleLog.line(TAG + "dispatchTouchEvent hook failed=" + error(t));
        }

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
                            if (ACTIVE_CAPTURE.get() == null) return;

                            Object wrapper = clickListener(clicked);
                            Object delegate = unwrapDelegate(wrapper);
                            hookNativeHandler(delegate);

                            captureLog("PERFORM_CLICK_BEGIN #"
                                    + ACTIVE_CAPTURE.get()
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
                            if (ACTIVE_CAPTURE.get() == null) return;

                            captureLog("PERFORM_CLICK_END #"
                                    + ACTIVE_CAPTURE.get()
                                    + " handled=" + safeValue(param.getResult())
                                    + " opened=" + OPENED.get()
                                    + " threw=" + (param.hasThrowable()
                                    ? error(param.getThrowable())
                                    : "none"));
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
                            if (ACTIVE_CAPTURE.get() == null) return;

                            int layoutId = (Integer) param.args[0];
                            String layoutName = resourceName(
                                    (LayoutInflater) param.thisObject,
                                    layoutId
                            );

                            if (!EXPANDED_LAYOUT.equals(layoutName)) return;

                            OPENED.set(true);

                            captureLog("OPEN_INFLATE #"
                                    + ACTIVE_CAPTURE.get()
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

    private static void beginCapture(View target, MotionEvent event) {
        int capture = NEXT_CAPTURE.getAndIncrement();

        synchronized (BUFFER_LOCK) {
            BUFFER.setLength(0);
        }

        ACTIVE_CAPTURE.set(capture);
        OPENED.set(false);
        armed = false;
        updateButton(false);

        Object wrapper = clickListener(target);
        Object delegate = unwrapDelegate(wrapper);
        hookNativeHandler(delegate);

        captureLog("CAPTURE_BEGIN #" + capture);
        captureLog("TOUCH_DOWN #" + capture
                + " rawX=" + event.getRawX()
                + " rawY=" + event.getRawY()
                + " localX=" + event.getX()
                + " localY=" + event.getY()
                + " target=" + describe(target)
                + " wrapper=" + className(wrapper)
                + " delegate=" + className(delegate));

        dumpTargetTree(target);
        dumpDirectFields("F7M_FIELDS_AT_DOWN", delegate);
    }

    private static void finishCapture(Context context) {
        Integer capture = ACTIVE_CAPTURE.get();

        captureLog("CAPTURE_END #" + String.valueOf(capture)
                + " opened=" + OPENED.get());

        String text;
        synchronized (BUFFER_LOCK) {
            text = BUFFER.toString();
        }

        try {
            ClipboardManager clipboard =
                    (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);

            if (clipboard != null) {
                clipboard.setPrimaryClip(
                        ClipData.newPlainText("InstaEclipse PFP diagnostics", text)
                );

                Toast.makeText(
                        context,
                        "PFP log copied to clipboard",
                        Toast.LENGTH_SHORT
                ).show();
            } else {
                Toast.makeText(
                        context,
                        "PFP log finished, clipboard unavailable",
                        Toast.LENGTH_SHORT
                ).show();
            }
        } catch (Throwable t) {
            ModuleLog.line(TAG + "clipboard failed=" + error(t));

            try {
                Toast.makeText(
                        context,
                        "PFP log finished, copy failed",
                        Toast.LENGTH_SHORT
                ).show();
            } catch (Throwable ignored) {}
        } finally {
            ACTIVE_CAPTURE.remove();
            OPENED.remove();
        }
    }

    private static void ensureFloatingButton(Activity activity) {
        if (activity == null) return;

        synchronized (BUTTONS) {
            TextView existing = BUTTONS.get(activity);

            if (existing != null && existing.getParent() != null) {
                latestButton = existing;
                return;
            }

            View content = activity.findViewById(android.R.id.content);
            if (!(content instanceof ViewGroup)) return;

            TextView button = new TextView(activity);
            button.setTag(BUTTON_TAG);
            button.setText(armed ? "ARMED" : "PFP LOG");
            button.setTextColor(0xFFFFFFFF);
            button.setTextSize(12f);
            button.setGravity(Gravity.CENTER);
            button.setPadding(dp(activity, 12), dp(activity, 8),
                    dp(activity, 12), dp(activity, 8));
            button.setElevation(dp(activity, 10));

            GradientDrawable background = new GradientDrawable();
            background.setColor(0xDD151515);
            background.setCornerRadius(dp(activity, 18));
            button.setBackground(background);

            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.END
            );
            params.topMargin = dp(activity, 84);
            params.rightMargin = dp(activity, 14);

            button.setOnClickListener(v -> {
                armed = true;
                latestButton = button;
                updateButton(true);

                Toast.makeText(
                        activity,
                        "Armed. Tap the profile picture.",
                        Toast.LENGTH_SHORT
                ).show();
            });

            try {
                ((ViewGroup) content).addView(button, params);
                BUTTONS.put(activity, button);
                latestButton = button;
            } catch (Throwable t) {
                ModuleLog.line(TAG + "floating button failed=" + error(t));
            }
        }
    }

    private static void updateButton(boolean isArmed) {
        TextView button = latestButton;
        if (button == null) return;

        try {
            button.setText(isArmed ? "ARMED" : "PFP LOG");
        } catch (Throwable ignored) {}
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
                    if (ACTIVE_CAPTURE.get() == null) return;

                    View input = param.args.length > 0 && param.args[0] instanceof View
                            ? (View) param.args[0]
                            : null;

                    captureLog("HANDLER_BEGIN #"
                            + ACTIVE_CAPTURE.get()
                            + " class=" + param.thisObject.getClass().getName()
                            + " input=" + describeNullable(input));
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (ACTIVE_CAPTURE.get() == null) return;

                    captureLog("HANDLER_END #"
                            + ACTIVE_CAPTURE.get()
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
                        if (ACTIVE_CAPTURE.get() == null) return;

                        if (className.equals("X.EeU")
                                && methodName.equals("E0p")
                                && param.args.length >= 3
                                && param.args[1] == null
                                && param.args[2] instanceof View
                                && "com.instagram.avatars.coinflip.ProfileCoinFlipView".equals(
                                param.args[2].getClass().getName())) {
                            RectF bounds = globalBounds((View) param.args[2]);
                            if (bounds != null) {
                                param.args[1] = bounds;
                                captureLog("COINFLIP_RECT_FIX #"
                                        + ACTIVE_CAPTURE.get()
                                        + " rect=" + bounds.toShortString()
                                        + " view=" + describe((View) param.args[2]));
                            }
                        }

                        captureLog("TRACE_ENTER #"
                                + ACTIVE_CAPTURE.get()
                                + " " + key
                                + " this=" + className(param.thisObject)
                                + " args=" + safeArgs(param.args));
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (ACTIVE_CAPTURE.get() == null) return;

                        captureLog("TRACE_EXIT #"
                                + ACTIVE_CAPTURE.get()
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

    private static void dumpTargetTree(View target) {
        if (target == null || ACTIVE_CAPTURE.get() == null) return;

        StringBuilder out = new StringBuilder();
        out.append("TARGET_TREE #")
                .append(ACTIVE_CAPTURE.get())
                .append("\n  root ")
                .append(describeDetailed(target));

        if (target instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) target;

            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                out.append("\n  child[")
                        .append(i)
                        .append("] ")
                        .append(describeDetailed(child));
            }
        }

        captureLog(out.toString());
    }

    private static void dumpDirectFields(String label, Object object) {
        if (object == null || ACTIVE_CAPTURE.get() == null) return;

        StringBuilder out = new StringBuilder();
        out.append(label)
                .append(" #")
                .append(ACTIVE_CAPTURE.get())
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

        captureLog(out.toString());
    }

    private static void dumpStack(String label) {
        try {
            StringBuilder out = new StringBuilder();
            out.append(label)
                    .append(" #")
                    .append(ACTIVE_CAPTURE.get());

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

            captureLog(out.toString());
        } catch (Throwable ignored) {}
    }

    private static void captureLog(String message) {
        String line = message.startsWith(TAG) ? message : TAG + message;
        ModuleLog.line(line);

        if (ACTIVE_CAPTURE.get() == null) return;

        synchronized (BUFFER_LOCK) {
            BUFFER.append(line).append("\n");
        }
    }

    private static String describeDetailed(View view) {
        if (view == null) return "null";

        StringBuilder out = new StringBuilder(describe(view));

        try {
            Rect rect = new Rect();
            boolean visible = view.getGlobalVisibleRect(rect);
            out.append(" globalVisible=").append(visible)
                    .append(" rect=")
                    .append(rect.left).append(",")
                    .append(rect.top).append(",")
                    .append(rect.right).append(",")
                    .append(rect.bottom);
        } catch (Throwable ignored) {}

        try {
            out.append(" pressed=").append(view.isPressed())
                    .append(" selected=").append(view.isSelected())
                    .append(" activated=").append(view.isActivated())
                    .append(" alpha=").append(view.getAlpha());
        } catch (Throwable ignored) {}

        return out.toString();
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

    private static int dp(Context context, int value) {
        return Math.round(
                value * context.getResources().getDisplayMetrics().density
        );
    }

    private static Activity activityFromContext(Context context) {
        Context current = context;

        while (current instanceof ContextWrapper) {
            if (current instanceof Activity) {
                return (Activity) current;
            }

            current = ((ContextWrapper) current).getBaseContext();
        }

        return current instanceof Activity ? (Activity) current : null;
    }

    private static RectF globalBounds(View view) {
        if (view == null || !view.isAttachedToWindow()) return null;

        try {
            Rect rect = new Rect();
            if (!view.getGlobalVisibleRect(rect) || rect.isEmpty()) return null;
            return new RectF(rect);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean pointInside(View view, float x, float y) {
        if (view == null || !view.isAttachedToWindow()) return false;

        try {
            Rect rect = new Rect();
            return view.getGlobalVisibleRect(rect)
                    && rect.contains(Math.round(x), Math.round(y));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String actionName(int action) {
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                return "DOWN";
            case MotionEvent.ACTION_UP:
                return "UP";
            case MotionEvent.ACTION_CANCEL:
                return "CANCEL";
            case MotionEvent.ACTION_MOVE:
                return "MOVE";
            default:
                return String.valueOf(action);
        }
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
