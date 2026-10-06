package ps.reso.instaeclipse.mods.profile;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Temporary in-app PFP diagnostics.
 *
 * Always shows a compact floating panel inside Instagram. Tapping either the
 * normal profile-picture target or the coin-flip avatar automatically starts a
 * short capture. Only the native PFP-open checkpoints relevant to this bug are
 * shown. The completed capture is also copied to the clipboard.
 *
 * Diagnostic only: this class does not alter arguments, click results or the
 * native profile-picture viewer path.
 */
public final class PfpClickDiagnostics {

    private static final String TAG = "(IE|PFPDiag) ";
    private static final String NORMAL_TARGET_ID =
            "row_profile_header_imageview_frame_layout";
    private static final String COINFLIP_TARGET_ID =
            "avatar_on_profile_header_view";
    private static final String COINFLIP_CLASS =
            "com.instagram.avatars.coinflip.ProfileCoinFlipView";
    private static final String EXPANDED_LAYOUT =
            "layout_expanded_profile_picture_view";

    private static final AtomicInteger NEXT_CAPTURE = new AtomicInteger(1);

    private static final ThreadLocal<Integer> ACTIVE_CAPTURE = new ThreadLocal<>();
    private static final ThreadLocal<Activity> ACTIVE_ACTIVITY = new ThreadLocal<>();
    private static final ThreadLocal<View> ACTIVE_TARGET = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> OPENED =
            ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Map<String, String>> A0D_BEFORE_FRAGMENT_STATE =
            new ThreadLocal<>();
    private static final ThreadLocal<Boolean> A0D_DUO_READY_BEFORE =
            new ThreadLocal<>();

    private static final Object BUFFER_LOCK = new Object();
    private static final StringBuilder BUFFER = new StringBuilder();

    private static final Set<String> HOOKED_TRACE_METHODS =
            Collections.synchronizedSet(new HashSet<>());
    private static final Set<Class<?>> HOOKED_CALLBACK_CLASSES =
            Collections.synchronizedSet(new HashSet<>());
    private static final Set<Class<?>> HOOKED_DUO_TRACKER_CLASSES =
            Collections.synchronizedSet(new HashSet<>());
    private static final Set<Class<?>> HOOKED_USER_DETAIL_TRACKER_CLASSES =
            Collections.synchronizedSet(new HashSet<>());
    private static final Set<String> HOOKED_TARGET_LISTENER_METHODS =
            Collections.synchronizedSet(new HashSet<>());
    private static final Set<Class<?>> HOOKED_GATE_OBJECT_CLASSES =
            Collections.synchronizedSet(new HashSet<>());

    private static final WeakHashMap<Activity, View> NORMAL_TARGETS =
            new WeakHashMap<>();
    private static final WeakHashMap<Activity, View> COINFLIP_TARGETS =
            new WeakHashMap<>();
    private static final WeakHashMap<Activity, TextView> PANEL_LOGS =
            new WeakHashMap<>();
    private static final WeakHashMap<Object, String> DUO_LAST_STATE =
            new WeakHashMap<>();
    private static final WeakHashMap<Object, StringBuilder> DUO_HISTORY =
            new WeakHashMap<>();
    private static final WeakHashMap<Object, Map<String, String>> LAST_A0D_FRAGMENT_STATE =
            new WeakHashMap<>();
    private static final WeakHashMap<Object, String> FRAGMENT_GATE_LAST =
            new WeakHashMap<>();
    private static final WeakHashMap<Object, StringBuilder> FRAGMENT_GATE_HISTORY =
            new WeakHashMap<>();
    private static final WeakHashMap<Object, Object> GATE_OBJECT_OWNERS =
            new WeakHashMap<>();

    private static volatile Map<String, String> SUCCESS_A0D_FRAGMENT_STATE;
    private static volatile boolean installed;
    private static volatile Field userDetailDuoField;

    private PfpClickDiagnostics() {}

    public static void observeAttached(View view) {
        if (view == null) return;

        installHooksOnce();
        installKnownPathHooks(view.getClass().getClassLoader());

        Activity activity = activityFromContext(view.getContext());
        if (activity == null) return;

        ensurePanel(activity);

        String id = resourceName(view);
        String cls = view.getClass().getName();

        synchronized (NORMAL_TARGETS) {
            if (NORMAL_TARGET_ID.equals(id)) {
                NORMAL_TARGETS.put(activity, view);
            }
        }

        synchronized (COINFLIP_TARGETS) {
            if (COINFLIP_TARGET_ID.equals(id) || COINFLIP_CLASS.equals(cls)) {
                COINFLIP_TARGETS.put(activity, view);
            }
        }
    }

    private static synchronized void installHooksOnce() {
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

                            Activity activity = (Activity) param.thisObject;
                            MotionEvent event = (MotionEvent) param.args[0];

                            if (event.getActionMasked() != MotionEvent.ACTION_DOWN
                                    || ACTIVE_CAPTURE.get() != null) {
                                return;
                            }

                            View target = targetAt(
                                    activity,
                                    event.getRawX(),
                                    event.getRawY()
                            );

                            if (target != null) {
                                beginCapture(activity, target, event);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!(param.thisObject instanceof Activity)
                                    || !(param.args[0] instanceof MotionEvent)) return;
                            if (ACTIVE_CAPTURE.get() == null) return;

                            MotionEvent event = (MotionEvent) param.args[0];
                            int action = event.getActionMasked();

                            if (action == MotionEvent.ACTION_UP
                                    || action == MotionEvent.ACTION_CANCEL) {
                                scheduleFinish((Activity) param.thisObject);
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            ModuleLog.line(TAG + "activity touch hook failed: " + error(t));
        }

        try {
            XposedHelpers.findAndHookMethod(
                    View.class,
                    "dispatchTouchEvent",
                    MotionEvent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (ACTIVE_CAPTURE.get() == null) return;
                            if (!(param.thisObject instanceof View)
                                    || !(param.args[0] instanceof MotionEvent)) return;

                            View view = (View) param.thisObject;
                            MotionEvent event = (MotionEvent) param.args[0];
                            int action = event.getActionMasked();

                            if ((action == MotionEvent.ACTION_DOWN
                                    || action == MotionEvent.ACTION_UP)
                                    && Boolean.TRUE.equals(param.getResult())
                                    && isAvatarRelated(view)) {
                                diag("touch-consumer " + actionName(action)
                                        + " " + shortView(view));
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            ModuleLog.line(TAG + "view touch hook failed: " + error(t));
        }

        try {
            XposedHelpers.findAndHookMethod(
                    View.class,
                    "performClick",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (ACTIVE_CAPTURE.get() == null) return;
                            if (!(param.thisObject instanceof View)) return;

                            View view = (View) param.thisObject;
                            if (!isAvatarRelated(view)) return;

                            Object listener = clickListener(view);
                            diag("performClick " + shortView(view)
                                    + " listener=" + className(listener)
                                    + " delegate=" + className(unwrapA00(listener)));
                        }
                    }
            );
        } catch (Throwable t) {
            ModuleLog.line(TAG + "performClick hook failed: " + error(t));
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
                            diag("OPEN_INFLATE ✅");
                        }
                    }
            );
        } catch (Throwable t) {
            ModuleLog.line(TAG + "inflate hook failed: " + error(t));
        }

        installed = true;
    }

    private static void beginCapture(
            Activity activity,
            View target,
            MotionEvent event
    ) {
        int capture = NEXT_CAPTURE.getAndIncrement();

        synchronized (BUFFER_LOCK) {
            BUFFER.setLength(0);
        }

        ACTIVE_CAPTURE.set(capture);
        ACTIVE_ACTIVITY.set(activity);
        ACTIVE_TARGET.set(target);
        OPENED.set(false);

        String path = isCoinFlip(target) ? "COINFLIP" : "NORMAL";
        Rect rect = visibleRect(target);

        diag("CAPTURE #" + capture + " " + path);
        diag("target=" + shortView(target));
        diag("bounds=" + (rect == null
                ? "null"
                : rect.left + "," + rect.top + "," + rect.right + "," + rect.bottom));
        diag("tap=" + Math.round(event.getRawX())
                + "," + Math.round(event.getRawY()));

        logTargetListeners(target);
    }

    private static void scheduleFinish(Activity activity) {
        Integer capture = ACTIVE_CAPTURE.get();
        if (capture == null || activity == null) return;

        View root = activity.findViewById(android.R.id.content);

        if (root == null) {
            finishCapture(activity);
            return;
        }

        root.postDelayed(() -> {
            Integer active = ACTIVE_CAPTURE.get();
            if (active != null && active.equals(capture)) {
                finishCapture(activity);
            }
        }, 500);
    }

    private static void finishCapture(Activity activity) {
        diag(Boolean.TRUE.equals(OPENED.get())
                ? "RESULT: VIEWER OPENED ✅"
                : "RESULT: NO INFLATE ❌");

        copyLatest(activity, false);

        ACTIVE_CAPTURE.remove();
        ACTIVE_ACTIVITY.remove();
        ACTIVE_TARGET.remove();
        OPENED.remove();
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
        installDuoInitTracker(loader);
        installUserDetailInitTracker(loader);
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

                        if ("X.EeU".equals(className)
                                && "E0p".equals(methodName)) {
                            Object rect = param.args.length > 1
                                    ? param.args[1]
                                    : null;
                            Object source = param.args.length > 2
                                    ? param.args[2]
                                    : null;
                            Object callback = param.args.length > 6
                                    ? param.args[6]
                                    : null;

                            hookCallback(callback);
                            diag("EeU rect=" + compactRect(rect)
                                    + " source=" + shortObject(source)
                                    + " cb=" + className(callback));
                            return;
                        }

                        if ("X.EdZ".equals(className)) {
                            diag("→ EdZ");
                            return;
                        }

                        if ("X.EdT".equals(className)) {
                            diag("→ EdT");
                            return;
                        }

                        if ("X.DiK".equals(className)) {
                            diag("→ DiK");
                            return;
                        }

                        if ("X.C7D".equals(className)) {
                            Object callback = arg(param.args, 2);
                            hookCallback(callback);
                            diag("→ C7D type=" + safeEnumish(arg(param.args, 0))
                                    + " model=" + fieldSummary(arg(param.args, 1), 8)
                                    + " cb=" + className(callback));
                            return;
                        }

                        if ("com.instagram.profile.fragment.UserDetailFragment"
                                .equals(className)) {
                            Object callback = arg(param.args, 2);
                            hookCallback(callback);
                            diag("→ UserDetail.GLY type="
                                    + safeEnumish(arg(param.args, 0))
                                    + " model=" + fieldSummary(arg(param.args, 1), 8)
                                    + " cb=" + className(callback));
                            return;
                        }

                        if ("X.DUO".equals(className)
                                && "GLY".equals(methodName)) {
                            Object callback = arg(param.args, 2);
                            hookCallback(callback);
                            diag("→ DUO.GLY model="
                                    + fieldSummary(arg(param.args, 1), 8)
                                    + " cb=" + className(callback));
                            return;
                        }

                        if ("X.DUO".equals(className)
                                && "A00".equals(methodName)) {
                            Object callback = arg(param.args, 2);
                            hookCallback(callback);
                            diag("→ DUO.A00 type="
                                    + safeEnumish(arg(param.args, 0))
                                    + " model=" + className(arg(param.args, 1))
                                    + " cb=" + className(callback));
                            diag("EMz state: "
                                    + fieldSummary(arg(param.args, 1), 12));
                            diag("DUO state: "
                                    + fieldSummary(param.thisObject, 12));
                            diag("DUO init history:\n"
                                    + duoHistory(param.thisObject));
                            Object fragment = readNamedField(param.thisObject, "A06");
                            diag("Profile gate history:\n"
                                    + fragmentGateHistory(fragment));
                            if (readNamedField(param.thisObject, "A01") == null) {
                                diag("A0d success-state diff:\n"
                                        + failedA0dDiff(param.thisObject));
                            }
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (ACTIVE_CAPTURE.get() == null) return;

                        if ("X.DUO".equals(className)
                                && "A00".equals(methodName)) {
                            diag("← DUO.A00 threw="
                                    + (param.hasThrowable()
                                    ? error(param.getThrowable())
                                    : "none"));
                        }
                    }
                });
            } catch (Throwable t) {
                HOOKED_TRACE_METHODS.remove(key);
                ModuleLog.line(TAG + "hook failed " + key + ": " + error(t));
            }
        }
    }

    private static View targetAt(Activity activity, float x, float y) {
        View coin;
        synchronized (COINFLIP_TARGETS) {
            coin = COINFLIP_TARGETS.get(activity);
        }

        if (pointInside(coin, x, y)) {
            return coin;
        }

        View normal;
        synchronized (NORMAL_TARGETS) {
            normal = NORMAL_TARGETS.get(activity);
        }

        if (pointInside(normal, x, y)) {
            return normal;
        }

        return null;
    }

    private static boolean isCoinFlip(View view) {
        if (view == null) return false;

        return COINFLIP_CLASS.equals(view.getClass().getName())
                || COINFLIP_TARGET_ID.equals(resourceName(view));
    }

    private static void ensurePanel(Activity activity) {
        synchronized (PANEL_LOGS) {
            TextView existing = PANEL_LOGS.get(activity);

            if (existing != null && existing.getParent() != null) {
                return;
            }

            View root = activity.findViewById(android.R.id.content);
            if (!(root instanceof ViewGroup)) return;

            LinearLayout panel = new LinearLayout(activity);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setPadding(
                    dp(activity, 10),
                    dp(activity, 8),
                    dp(activity, 10),
                    dp(activity, 8)
            );
            panel.setElevation(dp(activity, 12));

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xEE111111);
            bg.setCornerRadius(dp(activity, 12));
            bg.setStroke(dp(activity, 1), 0x55FFFFFF);
            panel.setBackground(bg);

            TextView title = new TextView(activity);
            title.setText("PFP DIAG  •  auto");
            title.setTextColor(0xFFFFFFFF);
            title.setTextSize(12f);
            title.setTypeface(Typeface.DEFAULT_BOLD);

            TextView body = new TextView(activity);
            body.setText("Tap a profile picture.\nLatest capture appears here.");
            body.setTextColor(0xFFE5E5E5);
            body.setTextSize(10f);
            body.setTypeface(Typeface.MONOSPACE);
            body.setTextIsSelectable(true);
            body.setMaxLines(14);

            LinearLayout buttons = new LinearLayout(activity);
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            buttons.setGravity(Gravity.END);

            TextView copy = actionText(activity, "COPY");
            TextView clear = actionText(activity, "CLEAR");

            copy.setOnClickListener(v -> copyLatest(activity, true));
            clear.setOnClickListener(v -> {
                synchronized (BUFFER_LOCK) {
                    BUFFER.setLength(0);
                }
                body.setText("Cleared. Tap a profile picture.");
            });

            buttons.addView(copy);
            buttons.addView(clear);

            panel.addView(title);
            panel.addView(body);
            panel.addView(buttons);

            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    dp(activity, 330),
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.END
            );
            params.topMargin = dp(activity, 78);
            params.rightMargin = dp(activity, 10);

            try {
                ((ViewGroup) root).addView(panel, params);
                PANEL_LOGS.put(activity, body);
            } catch (Throwable t) {
                ModuleLog.line(TAG + "panel failed: " + error(t));
            }
        }
    }

    private static TextView actionText(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(0xFF9ED0FF);
        view.setTextSize(11f);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setGravity(Gravity.CENTER);
        view.setPadding(
                dp(context, 12),
                dp(context, 6),
                dp(context, 4),
                0
        );
        return view;
    }

    private static void diag(String message) {
        String line = TAG + message;
        ModuleLog.line(line);

        if (ACTIVE_CAPTURE.get() == null) return;

        String text;
        synchronized (BUFFER_LOCK) {
            if (BUFFER.length() > 0) BUFFER.append('\n');
            BUFFER.append(message);
            text = BUFFER.toString();
        }

        Activity activity = ACTIVE_ACTIVITY.get();
        if (activity == null) return;

        TextView body;
        synchronized (PANEL_LOGS) {
            body = PANEL_LOGS.get(activity);
        }

        if (body != null) {
            final String finalText = text;
            body.post(() -> body.setText(finalText));
        }
    }

    private static void copyLatest(Context context, boolean toast) {
        String text;

        synchronized (BUFFER_LOCK) {
            text = BUFFER.toString();
        }

        if (text.isEmpty()) {
            if (toast) {
                Toast.makeText(
                        context,
                        "No PFP diagnostics yet",
                        Toast.LENGTH_SHORT
                ).show();
            }
            return;
        }

        try {
            ClipboardManager clipboard =
                    (ClipboardManager) context.getSystemService(
                            Context.CLIPBOARD_SERVICE
                    );

            if (clipboard != null) {
                clipboard.setPrimaryClip(
                        ClipData.newPlainText(
                                "InstaEclipse PFP diagnostics",
                                text
                        )
                );

                if (toast) {
                    Toast.makeText(
                            context,
                            "PFP diagnostics copied",
                            Toast.LENGTH_SHORT
                    ).show();
                }
            }
        } catch (Throwable t) {
            ModuleLog.line(TAG + "clipboard failed: " + error(t));
        }
    }

    private static void logTargetListeners(View target) {
        Object click = listenerInfoField(target, "mOnClickListener");
        Object touch = listenerInfoField(target, "mOnTouchListener");
        Object longClick = listenerInfoField(target, "mOnLongClickListener");

        diag("listeners click=" + className(click)
                + " delegate=" + className(unwrapA00(click))
                + " touch=" + className(touch)
                + " long=" + className(longClick)
                + " clickable=" + target.isClickable()
                + " longClickable=" + target.isLongClickable());

        hookTargetListener(click, "onClick");
        hookTargetListener(touch, "onTouch");
    }

    private static Object listenerInfoField(View view, String name) {
        if (view == null) return null;

        try {
            Object info = XposedHelpers.getObjectField(view, "mListenerInfo");
            return info == null ? null : XposedHelpers.getObjectField(info, name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void hookTargetListener(Object listener, String methodName) {
        if (listener == null) return;

        Class<?> cls = listener.getClass();
        String hookKey = cls.getName() + "#" + methodName;
        if (!HOOKED_TARGET_LISTENER_METHODS.add(hookKey)) return;

        boolean hookedAny = false;
        Class<?> current = cls;

        while (current != null && current != Object.class) {
            Method[] methods;

            try {
                methods = current.getDeclaredMethods();
            } catch (Throwable t) {
                break;
            }

            for (Method method : methods) {
                if (!methodName.equals(method.getName())) continue;

                Class<?>[] params = method.getParameterTypes();

                if ("onClick".equals(methodName)) {
                    if (params.length != 1
                            || !View.class.isAssignableFrom(params[0])) {
                        continue;
                    }
                } else if ("onTouch".equals(methodName)) {
                    if (params.length != 2
                            || !View.class.isAssignableFrom(params[0])
                            || !MotionEvent.class.isAssignableFrom(params[1])) {
                        continue;
                    }
                } else {
                    continue;
                }

                try {
                    method.setAccessible(true);
                    hookedAny = true;

                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (ACTIVE_CAPTURE.get() == null) return;
                            if (param.args.length == 0
                                    || param.args[0] != ACTIVE_TARGET.get()) return;

                            if ("onClick".equals(methodName)) {
                                diag("listener.onClick " + cls.getName()
                                        + " delegate="
                                        + className(unwrapA00(param.thisObject)));
                            } else if ("onTouch".equals(methodName)
                                    && param.args.length > 1
                                    && param.args[1] instanceof MotionEvent) {
                                MotionEvent event = (MotionEvent) param.args[1];
                                diag("listener.onTouch " + cls.getName()
                                        + " " + actionName(event.getActionMasked()));
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (ACTIVE_CAPTURE.get() == null) return;
                            if (!"onTouch".equals(methodName)) return;
                            if (param.args.length == 0
                                    || param.args[0] != ACTIVE_TARGET.get()) return;

                            diag("listener.onTouch-result "
                                    + cls.getName()
                                    + " -> "
                                    + compactValue(param.getResult())
                                    + " threw="
                                    + (param.hasThrowable()
                                    ? error(param.getThrowable())
                                    : "none"));
                        }
                    });
                } catch (Throwable ignored) {}
            }

            current = current.getSuperclass();
        }

        if (!hookedAny) {
            HOOKED_TARGET_LISTENER_METHODS.remove(hookKey);
        }
    }

    private static void installUserDetailInitTracker(ClassLoader loader) {
        Class<?> fragmentClass;
        Class<?> duoClass;

        try {
            fragmentClass = Class.forName(
                    "com.instagram.profile.fragment.UserDetailFragment",
                    false,
                    loader
            );
            duoClass = Class.forName("X.DUO", false, loader);
        } catch (Throwable ignored) {
            return;
        }

        if (!HOOKED_USER_DETAIL_TRACKER_CLASSES.add(fragmentClass)) return;

        userDetailDuoField = findFieldByType(fragmentClass, duoClass);

        if (userDetailDuoField == null) {
            ModuleLog.line(TAG + "UserDetail DUO field not found");
            return;
        }

        for (Method method : fragmentClass.getDeclaredMethods()) {
            try {
                method.setAccessible(true);
                String key = "UserDetail." + signature(method);


                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        recordFragmentGateState(
                                param.thisObject,
                                "before " + key
                        );

                        if (isGateBranchMethod(method.getName())) {
                            appendFragmentGateHistory(
                                    param.thisObject,
                                    "CALL " + key
                                            + " args=" + argsSummary(param.args, 6)
                                            + "\ncallers: " + compactCallStack()
                            );
                        }

                        Object duo = readUserDetailDuo(param.thisObject);
                        if (duo != null) {
                            if ("A0d".equals(method.getName())) {
                                Map<String, String> beforeState =
                                        fragmentStateSnapshot(param.thisObject);
                                A0D_BEFORE_FRAGMENT_STATE.set(beforeState);
                                A0D_DUO_READY_BEFORE.set(
                                        readNamedField(duo, "A01") != null
                                );
                                synchronized (LAST_A0D_FRAGMENT_STATE) {
                                    LAST_A0D_FRAGMENT_STATE.put(duo, beforeState);
                                }

                                appendDuoHistory(
                                        duo,
                                        "CALL " + key
                                                + " args=" + argsSummary(param.args, 8)
                                                + "\nA0d callers: " + compactCallStack()
                                );
                            }
                            recordDuoState(duo, "before " + key);
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object duo = readUserDetailDuo(param.thisObject);
                        if (duo != null) {
                            if ("A0d".equals(method.getName())) {
                                Map<String, String> beforeState =
                                        A0D_BEFORE_FRAGMENT_STATE.get();
                                boolean readyBefore =
                                        Boolean.TRUE.equals(A0D_DUO_READY_BEFORE.get());
                                boolean readyAfter =
                                        readNamedField(duo, "A01") != null;

                                if (!readyBefore && readyAfter && beforeState != null) {
                                    SUCCESS_A0D_FRAGMENT_STATE =
                                            new LinkedHashMap<>(beforeState);
                                    appendDuoHistory(
                                            duo,
                                            "A0d SUCCESS baseline learned"
                                    );
                                }

                                A0D_BEFORE_FRAGMENT_STATE.remove();
                                A0D_DUO_READY_BEFORE.remove();
                            }

                            recordDuoState(duo, "after " + key);
                        }

                        recordFragmentGateState(
                                param.thisObject,
                                "after " + key
                        );

                        if (isGateBranchMethod(method.getName())) {
                            appendFragmentGateHistory(
                                    param.thisObject,
                                    "RETURN " + key
                                            + " result=" + compactValue(param.getResult())
                                            + " threw="
                                            + (param.hasThrowable()
                                            ? error(param.getThrowable())
                                            : "none")
                            );
                        }
                    }
                });
            } catch (Throwable ignored) {}
        }
    }

    private static Field findFieldByType(Class<?> owner, Class<?> wantedType) {
        Class<?> cls = owner;

        while (cls != null && cls != Object.class) {
            for (Field field : cls.getDeclaredFields()) {
                try {
                    if (wantedType.isAssignableFrom(field.getType())) {
                        field.setAccessible(true);
                        return field;
                    }
                } catch (Throwable ignored) {}
            }

            cls = cls.getSuperclass();
        }

        return null;
    }

    private static Object readUserDetailDuo(Object fragment) {
        Field field = userDetailDuoField;
        if (field == null || fragment == null) return null;

        try {
            return field.get(fragment);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void installDuoInitTracker(ClassLoader loader) {
        Class<?> cls;

        try {
            cls = Class.forName("X.DUO", false, loader);
        } catch (Throwable ignored) {
            return;
        }

        if (!HOOKED_DUO_TRACKER_CLASSES.add(cls)) return;

        for (Method method : cls.getDeclaredMethods()) {
            try {
                method.setAccessible(true);
                String key = "DUO." + signature(method);

                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        recordDuoState(param.thisObject, "before " + key);
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        recordDuoState(param.thisObject, "after " + key);
                    }
                });
            } catch (Throwable ignored) {}
        }
    }

    private static boolean isGateBranchMethod(String name) {
        return "A0m".equals(name)
                || "A1D".equals(name)
                || "A1E".equals(name);
    }

    private static void appendFragmentGateHistory(
            Object fragment,
            String line
    ) {
        if (fragment == null || line == null || line.isEmpty()) return;

        synchronized (FRAGMENT_GATE_LAST) {
            StringBuilder history = FRAGMENT_GATE_HISTORY.get(fragment);
            if (history == null) {
                history = new StringBuilder();
                FRAGMENT_GATE_HISTORY.put(fragment, history);
            }

            if (history.length() > 0) history.append('\n');
            history.append(line);

            if (history.length() > 5200) {
                history.delete(0, history.length() - 4400);
            }
        }
    }

    private static void recordFragmentGateState(
            Object fragment,
            String where
    ) {
        if (fragment == null) return;

        trackGateObject(fragment, readNamedField(fragment, "A0z"));
        trackGateObject(fragment, readNamedField(fragment, "A1j"));

        String state = fragmentGateState(fragment);

        synchronized (FRAGMENT_GATE_LAST) {
            String previous = FRAGMENT_GATE_LAST.get(fragment);
            if (state.equals(previous)) return;

            FRAGMENT_GATE_LAST.put(fragment, state);

            StringBuilder history = FRAGMENT_GATE_HISTORY.get(fragment);
            if (history == null) {
                history = new StringBuilder();
                FRAGMENT_GATE_HISTORY.put(fragment, history);
            }

            if (history.length() > 0) history.append('\n');
            history.append(where).append(" -> ").append(state);

            if (history.length() > 3600) {
                history.delete(0, history.length() - 3000);
            }
        }
    }

    private static void trackGateObject(Object fragment, Object gateObject) {
        if (fragment == null || gateObject == null) return;

        synchronized (GATE_OBJECT_OWNERS) {
            GATE_OBJECT_OWNERS.put(gateObject, fragment);
        }

        hookGateObjectClass(gateObject.getClass());
    }

    private static void hookGateObjectClass(Class<?> cls) {
        if (cls == null || !HOOKED_GATE_OBJECT_CLASSES.add(cls)) return;

        for (Method method : cls.getDeclaredMethods()) {
            try {
                method.setAccessible(true);
                String key = cls.getName() + "." + signature(method);

                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        recordGateObjectMutation(
                                param.thisObject,
                                "before " + key
                        );
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        recordGateObjectMutation(
                                param.thisObject,
                                "after " + key
                                        + " args=" + argsSummary(param.args, 6)
                                        + "\ncallers: " + compactCallStack()
                        );
                    }
                });
            } catch (Throwable ignored) {}
        }
    }

    private static void recordGateObjectMutation(
            Object gateObject,
            String where
    ) {
        if (gateObject == null) return;

        Object fragment;
        synchronized (GATE_OBJECT_OWNERS) {
            fragment = GATE_OBJECT_OWNERS.get(gateObject);
        }
        if (fragment == null) return;

        String state = fragmentGateState(fragment);

        synchronized (FRAGMENT_GATE_LAST) {
            String previous = FRAGMENT_GATE_LAST.get(fragment);
            if (state.equals(previous)) return;

            FRAGMENT_GATE_LAST.put(fragment, state);

            StringBuilder history = FRAGMENT_GATE_HISTORY.get(fragment);
            if (history == null) {
                history = new StringBuilder();
                FRAGMENT_GATE_HISTORY.put(fragment, history);
            }

            if (history.length() > 0) history.append('\n');
            history.append("GATE MUTATION ")
                    .append(where)
                    .append(" -> ")
                    .append(state);

            if (history.length() > 6200) {
                history.delete(0, history.length() - 5200);
            }
        }
    }

    private static String fragmentGateState(Object fragment) {
        Object a0z = readNamedField(fragment, "A0z");
        Object a1j = readNamedField(fragment, "A1j");

        return "A0z.A00=" + nestedFieldValue(a0z, "A00")
                + " | A0z.A01=" + nestedFieldValue(a0z, "A01")
                + " | A1D=" + compactValue(readNamedField(fragment, "A1D"))
                + " | A1j.A00=" + nestedFieldValue(a1j, "A00")
                + " | A1j.A01=" + nestedFieldValue(a1j, "A01")
                + " | A1j.A02=" + nestedFieldValue(a1j, "A02")
                + " | A1j.A03=" + nestedFieldValue(a1j, "A03");
    }

    private static String nestedFieldValue(Object object, String fieldName) {
        return compactValue(readNamedField(object, fieldName));
    }

    private static String fragmentGateHistory(Object fragment) {
        if (fragment == null) return "<null fragment>";

        synchronized (FRAGMENT_GATE_LAST) {
            StringBuilder history = FRAGMENT_GATE_HISTORY.get(fragment);
            return history == null || history.length() == 0
                    ? "<no recorded gate transitions>"
                    : history.toString();
        }
    }

    private static Object readNamedField(Object object, String name) {
        if (object == null || name == null) return null;

        Class<?> cls = object.getClass();
        while (cls != null && cls != Object.class) {
            try {
                Field field = cls.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(object);
            } catch (NoSuchFieldException ignored) {
                cls = cls.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }

        return null;
    }

    private static Map<String, String> fragmentStateSnapshot(Object fragment) {
        Map<String, String> out = new LinkedHashMap<>();
        if (fragment == null) return out;

        Class<?> cls = fragment.getClass();
        int added = 0;

        while (cls != null && cls != Object.class && added < 220) {
            Field[] fields;
            try {
                fields = cls.getDeclaredFields();
            } catch (Throwable t) {
                break;
            }

            for (Field field : fields) {
                if (added >= 220) break;
                if (Modifier.isStatic(field.getModifiers())) continue;

                try {
                    field.setAccessible(true);
                    Object value = field.get(fragment);
                    out.put(
                            cls.getSimpleName() + "." + field.getName(),
                            snapshotValue(value)
                    );
                    added++;
                } catch (Throwable ignored) {}
            }

            cls = cls.getSuperclass();
        }

        return out;
    }

    private static String snapshotValue(Object value) {
        if (value == null) return "null";

        if (value instanceof String) {
            String text = (String) value;
            if (text.length() > 80) text = text.substring(0, 80) + "…";
            return "\"" + text + "\"";
        }

        if (value instanceof Boolean
                || value instanceof Number
                || value instanceof Character
                || value.getClass().isEnum()) {
            return String.valueOf(value);
        }

        if (value instanceof View) {
            return shortView((View) value);
        }

        return value.getClass().getSimpleName()
                + "{" + fieldSummary(value, 4) + "}";
    }

    private static String failedA0dDiff(Object duo) {
        Map<String, String> success = SUCCESS_A0D_FRAGMENT_STATE;
        if (success == null) {
            return "<no successful baseline yet; open a working profile first>";
        }

        Map<String, String> failed;
        synchronized (LAST_A0D_FRAGMENT_STATE) {
            failed = LAST_A0D_FRAGMENT_STATE.get(duo);
            if (failed != null) {
                failed = new LinkedHashMap<>(failed);
            }
        }

        if (failed == null) {
            return "<no A0d fragment snapshot for this DUO>";
        }

        StringBuilder out = new StringBuilder();
        int shown = 0;

        for (Map.Entry<String, String> entry : success.entrySet()) {
            String key = entry.getKey();
            String good = entry.getValue();
            String bad = failed.get(key);

            if (bad == null && !failed.containsKey(key)) continue;
            if (good == null ? bad == null : good.equals(bad)) continue;

            if (shown > 0) out.append('\n');
            out.append(key)
                    .append(": good=")
                    .append(good)
                    .append(" | bad=")
                    .append(bad);

            shown++;
            if (shown >= 36) {
                out.append("\n… diff truncated");
                break;
            }
        }

        return shown == 0
                ? "<no differing captured fragment fields>"
                : out.toString();
    }

    private static void appendDuoHistory(Object duo, String line) {
        if (duo == null || line == null || line.isEmpty()) return;

        synchronized (DUO_LAST_STATE) {
            StringBuilder history = DUO_HISTORY.get(duo);
            if (history == null) {
                history = new StringBuilder();
                DUO_HISTORY.put(duo, history);
            }

            if (history.length() > 0) history.append('\n');
            history.append(line);

            if (history.length() > 3600) {
                history.delete(0, history.length() - 3000);
            }
        }
    }

    private static String argsSummary(Object[] args, int limit) {
        if (args == null || args.length == 0) return "[]";

        StringBuilder out = new StringBuilder("[");
        int count = Math.min(args.length, Math.max(0, limit));

        for (int i = 0; i < count; i++) {
            if (i > 0) out.append(", ");
            Object value = args[i];

            out.append(i).append('=');
            if (value == null) {
                out.append("null");
            } else if (value instanceof Boolean
                    || value instanceof Number
                    || value instanceof Character
                    || value.getClass().isEnum()) {
                out.append(String.valueOf(value));
            } else {
                out.append(value.getClass().getName())
                        .append('{')
                        .append(fieldSummary(value, 6))
                        .append('}');
            }
        }

        if (args.length > count) {
            out.append(", …+").append(args.length - count);
        }

        return out.append(']').toString();
    }

    private static String compactCallStack() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        StringBuilder out = new StringBuilder();
        int added = 0;

        for (StackTraceElement frame : stack) {
            String cls = frame.getClassName();

            if (cls.equals(Thread.class.getName())
                    || cls.equals(PfpClickDiagnostics.class.getName())
                    || cls.startsWith("de.robv.android.xposed.")
                    || cls.startsWith("java.lang.reflect.")
                    || cls.startsWith("jdk.internal.reflect.")) {
                continue;
            }

            if (added > 0) out.append(" <- ");
            out.append(cls)
                    .append('.')
                    .append(frame.getMethodName());

            added++;
            if (added >= 10) break;
        }

        return out.length() == 0 ? "<no useful frames>" : out.toString();
    }

    private static void recordDuoState(Object duo, String where) {
        if (duo == null) return;

        String state = duoTrackedState(duo);

        synchronized (DUO_LAST_STATE) {
            String previous = DUO_LAST_STATE.get(duo);
            if (state.equals(previous)) return;

            DUO_LAST_STATE.put(duo, state);

            StringBuilder history = DUO_HISTORY.get(duo);
            if (history == null) {
                history = new StringBuilder();
                DUO_HISTORY.put(duo, history);
            }

            if (history.length() > 0) history.append('\n');
            history.append(where).append(" -> ").append(state);

            if (history.length() > 2200) {
                history.delete(0, history.length() - 1800);
            }
        }
    }

    private static String duoTrackedState(Object duo) {
        return "A01=" + trackedField(duo, "A01")
                + " | A02=" + trackedField(duo, "A02")
                + " | A05=" + trackedField(duo, "A05")
                + " | A0A=" + trackedField(duo, "A0A");
    }

    private static String duoHistory(Object duo) {
        if (duo == null) return "<null DUO>";

        synchronized (DUO_LAST_STATE) {
            StringBuilder history = DUO_HISTORY.get(duo);
            return history == null || history.length() == 0
                    ? "<no recorded transitions>"
                    : history.toString();
        }
    }

    private static String trackedField(Object object, String name) {
        if (object == null) return "null";

        Class<?> cls = object.getClass();

        while (cls != null && cls != Object.class) {
            try {
                Field field = cls.getDeclaredField(name);
                field.setAccessible(true);
                Object value = field.get(object);

                if (value instanceof View) {
                    return shortView((View) value);
                }

                return compactValue(value);
            } catch (NoSuchFieldException ignored) {
                cls = cls.getSuperclass();
            } catch (Throwable ignored) {
                return "?";
            }
        }

        return "<missing>";
    }

    private static void hookCallback(Object callback) {
        if (callback == null) return;

        Class<?> cls = callback.getClass();
        if (!HOOKED_CALLBACK_CLASSES.add(cls)) return;

        boolean hookedAny = false;

        for (Method method : cls.getDeclaredMethods()) {
            if (!"invoke".equals(method.getName())
                    || method.getParameterTypes().length != 0) {
                continue;
            }

            try {
                method.setAccessible(true);
                hookedAny = true;

                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (ACTIVE_CAPTURE.get() != null) {
                            diag("callback " + cls.getName() + ".invoke()");
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (ACTIVE_CAPTURE.get() != null) {
                            diag("callback-result "
                                    + cls.getName()
                                    + " -> "
                                    + compactValue(param.getResult())
                                    + " threw="
                                    + (param.hasThrowable()
                                    ? error(param.getThrowable())
                                    : "none"));
                        }
                    }
                });
            } catch (Throwable ignored) {}
        }

        if (!hookedAny) {
            HOOKED_CALLBACK_CLASSES.remove(cls);
        }
    }

    private static boolean isAvatarRelated(View view) {
        if (view == null) return false;

        String id = resourceName(view);
        String cls = view.getClass().getName();

        return NORMAL_TARGET_ID.equals(id)
                || COINFLIP_TARGET_ID.equals(id)
                || "profile_header_avatar_container_top_left_stub".equals(id)
                || "row_profile_header_imageview".equals(id)
                || "profilePic".equals(id)
                || cls.contains("ProfileCoinFlip")
                || cls.contains("CircularImageView");
    }

    private static Object clickListener(View view) {
        if (view == null) return null;

        try {
            Object info = XposedHelpers.getObjectField(view, "mListenerInfo");
            return info == null
                    ? null
                    : XposedHelpers.getObjectField(info, "mOnClickListener");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object unwrapA00(Object object) {
        if (object == null) return null;

        try {
            Field field = object.getClass().getDeclaredField("A00");
            field.setAccessible(true);
            return field.get(object);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String actionName(int action) {
        if (action == MotionEvent.ACTION_DOWN) return "DOWN";
        if (action == MotionEvent.ACTION_UP) return "UP";
        if (action == MotionEvent.ACTION_CANCEL) return "CANCEL";
        return String.valueOf(action);
    }

    private static String fieldSummary(Object object, int limit) {
        if (object == null) return "null";

        StringBuilder out = new StringBuilder();
        int added = 0;
        Class<?> cls = object.getClass();

        while (cls != null && cls != Object.class && added < limit) {
            Field[] fields;

            try {
                fields = cls.getDeclaredFields();
            } catch (Throwable t) {
                break;
            }

            for (Field field : fields) {
                if (added >= limit) break;
                if (Modifier.isStatic(field.getModifiers())) continue;

                Object value;
                try {
                    field.setAccessible(true);
                    value = field.get(object);
                } catch (Throwable ignored) {
                    continue;
                }

                if (added > 0) out.append(" | ");

                out.append(field.getName())
                        .append("=")
                        .append(compactValue(value));
                added++;
            }

            cls = cls.getSuperclass();
        }

        return out.length() == 0 ? "<no fields>" : out.toString();
    }

    private static String compactValue(Object value) {
        if (value == null) return "null";

        if (value instanceof Boolean
                || value instanceof Number
                || value instanceof Character
                || value.getClass().isEnum()) {
            return String.valueOf(value);
        }

        return value.getClass().getSimpleName();
    }

    private static Object arg(Object[] args, int index) {
        return args != null && index >= 0 && index < args.length
                ? args[index]
                : null;
    }

    private static String compactRect(Object value) {
        if (value == null) return "null";

        if (value instanceof RectF) {
            RectF rect = (RectF) value;
            return Math.round(rect.left) + ","
                    + Math.round(rect.top) + ","
                    + Math.round(rect.right) + ","
                    + Math.round(rect.bottom);
        }

        return className(value);
    }

    private static String safeEnumish(Object value) {
        if (value == null) return "null";

        try {
            if (value.getClass().isEnum()) {
                return String.valueOf(value);
            }

            String text = String.valueOf(value);
            if (text.length() <= 64) return text;
        } catch (Throwable ignored) {}

        return className(value);
    }

    private static String shortObject(Object value) {
        if (value instanceof View) {
            return shortView((View) value);
        }

        return className(value);
    }

    private static String shortView(View view) {
        if (view == null) return "null";

        return view.getClass().getSimpleName()
                + "#" + resourceName(view)
                + " vis=" + view.getVisibility()
                + " " + view.getWidth() + "x" + view.getHeight();
    }

    private static Rect visibleRect(View view) {
        if (view == null || !view.isAttachedToWindow()) return null;

        try {
            Rect rect = new Rect();
            return view.getGlobalVisibleRect(rect) && !rect.isEmpty()
                    ? rect
                    : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean pointInside(View view, float x, float y) {
        Rect rect = visibleRect(view);
        return rect != null
                && rect.contains(Math.round(x), Math.round(y));
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

    private static String signature(Method method) {
        StringBuilder out = new StringBuilder(method.getName()).append("(");

        Class<?>[] params = method.getParameterTypes();

        for (int i = 0; i < params.length; i++) {
            if (i > 0) out.append(",");
            out.append(params[i].getName());
        }

        return out.append(")").toString();
    }

    private static String className(Object object) {
        return object == null ? "null" : object.getClass().getName();
    }

    private static String error(Throwable t) {
        if (t == null) return "null";

        String message = t.getMessage();
        return t.getClass().getSimpleName()
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
