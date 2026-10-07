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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.UsingType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

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
 * Mostly diagnostic. It also contains one narrowly-scoped native repair:
 * while UserDetailFragment.A0d runs from A0U/A16 with an uninitialized DUO,
 * the X.116.A0U predicate is allowed to take the same true branch seen on
 * working profiles. No click routing, CoinFlip state, or viewer callbacks are
 * replaced.
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
    private static final AtomicBoolean WRITER_SCAN_STARTED = new AtomicBoolean(false);

    private static final ThreadLocal<Integer> ACTIVE_CAPTURE = new ThreadLocal<>();
    private static final ThreadLocal<Activity> ACTIVE_ACTIVITY = new ThreadLocal<>();
    private static final ThreadLocal<View> ACTIVE_TARGET = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> OPENED =
            ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Map<String, String>> A0D_BEFORE_FRAGMENT_STATE =
            new ThreadLocal<>();
    private static final ThreadLocal<Boolean> A0D_DUO_READY_BEFORE =
            new ThreadLocal<>();
    private static final ThreadLocal<Object> A0D_BRANCH_DUO =
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
    private static final Set<String> HOOKED_DUO_WRITER_METHODS =
            Collections.synchronizedSet(new HashSet<>());
    private static final Set<String> HOOKED_A0D_INVOKES =
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
    private static final WeakHashMap<Object, StringBuilder> DUO_WRITER_HISTORY =
            new WeakHashMap<>();
    private static final WeakHashMap<Object, StringBuilder> A0D_BRANCH_HISTORY =
            new WeakHashMap<>();
    private static final java.util.List<Field> A0D_READ_FIELDS =
            Collections.synchronizedList(new java.util.ArrayList<>());

    private static volatile Map<String, String> SUCCESS_A0D_FRAGMENT_STATE;
    private static volatile boolean installed;
    private static volatile boolean writerScanReady;
    private static volatile boolean a0dStaticReady;
    private static volatile String writerScanStatus = "not started";
    private static volatile Field userDetailDuoField;
    private static volatile Class<?> duoRuntimeClass;

    private PfpClickDiagnostics() {}

    public static void observeAttached(View view) {
        if (view == null) return;

        installHooksOnce();
        ClassLoader loader = view.getClass().getClassLoader();
        installKnownPathHooks(loader);
        installDuoWriterDiagnosticsAsync(loader);

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
        diag("writer-scan=" + writerScanStatus);

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
                            diag("DUO identity=" + duoIdentity(param.thisObject));
                            diag("DUO writer history:\n"
                                    + duoWriterHistory(param.thisObject));
                            diag("A0d branch history:\n"
                                    + a0dBranchHistory(param.thisObject));
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

                                String a0dCaller = currentA0dSetupCaller();
                                if (a0dCaller != null) {
                                    A0D_BRANCH_DUO.set(duo);
                                    appendA0dBranchHistory(
                                            duo,
                                            a0dCaller + " A0d START args="
                                                    + argsSummary(param.args, 4)
                                                    + "\n  reads="
                                                    + a0dReadSnapshot(
                                                            param.thisObject,
                                                            param.args
                                                    )
                                    );
                                }
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

                                if (A0D_BRANCH_DUO.get() == duo) {
                                    appendA0dBranchHistory(
                                            duo,
                                            "A0d END ready=" + readyAfter
                                                    + "\n  reads="
                                                    + a0dReadSnapshot(
                                                            param.thisObject,
                                                            param.args
                                                    )
                                    );
                                    A0D_BRANCH_DUO.remove();
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

    private static void installDuoWriterDiagnosticsAsync(
            ClassLoader fallbackLoader
    ) {
        if (WRITER_SCAN_STARTED.get()) return;

        Class<?> resolvedDuo = null;
        ClassLoader resolvedLoader = null;

        ClassLoader hostLoader =
                ps.reso.instaeclipse.Xposed.Module.hostClassLoader;

        if (hostLoader != null) {
            try {
                resolvedDuo = Class.forName(
                        "X.DUO",
                        false,
                        hostLoader
                );
                resolvedLoader = hostLoader;
            } catch (Throwable ignored) {}
        }

        if (resolvedDuo == null && fallbackLoader != null) {
            try {
                resolvedDuo = Class.forName(
                        "X.DUO",
                        false,
                        fallbackLoader
                );
                resolvedLoader = fallbackLoader;
            } catch (Throwable ignored) {}
        }

        // Early observeAttached() calls can come from framework Views. Do not
        // permanently latch a loader that cannot see Instagram classes.
        if (resolvedDuo == null || resolvedLoader == null) {
            writerScanStatus = "waiting for IG classloader";
            return;
        }

        if (!WRITER_SCAN_STARTED.compareAndSet(false, true)) return;

        final Class<?> duoClass = resolvedDuo;
        final ClassLoader loader = resolvedLoader;
        duoRuntimeClass = duoClass;
        writerScanStatus = "scanning " + loader.getClass().getSimpleName();

        Thread worker = new Thread(() -> {
            try {
                DexKitBridge bridge =
                        ps.reso.instaeclipse.Xposed.Module.dexKitBridge;
                if (bridge == null) {
                    writerScanStatus = "no DexKit bridge";
                    WRITER_SCAN_STARTED.set(false);
                    return;
                }

                try {
                    XposedBridge.hookAllConstructors(
                            duoClass,
                            new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(
                                        MethodHookParam param
                                ) {
                                    appendDuoWriterHistory(
                                            param.thisObject,
                                            "CTOR "
                                                    + duoIdentity(param.thisObject)
                                                    + " -> "
                                                    + duoTrackedState(param.thisObject)
                                    );
                                }
                            }
                    );
                } catch (Throwable ignored) {}

                int totalMatches = 0;
                int totalHooks = 0;
                StringBuilder summary = new StringBuilder();

                for (String fieldName : new String[]{"A01", "A02", "A05"}) {
                    Field field = findFieldInHierarchy(duoClass, fieldName);
                    if (field == null) {
                        if (summary.length() > 0) summary.append(' ');
                        summary.append(fieldName).append("=?");
                        continue;
                    }

                    String descriptor = fieldDescriptor(field);
                    int fieldMatches = 0;

                    try {
                        for (MethodData data : bridge.findMethod(
                                FindMethod.create().matcher(
                                        MethodMatcher.create().addUsingField(
                                                descriptor,
                                                UsingType.Write
                                        )
                                )
                        )) {
                            fieldMatches++;
                            totalMatches++;

                            try {
                                Method writer =
                                        data.getMethodInstance(loader);
                                if (writer == null) continue;

                                String key = writer.getDeclaringClass().getName()
                                        + "#" + signature(writer);
                                if (!HOOKED_DUO_WRITER_METHODS.add(key)) {
                                    continue;
                                }

                                writer.setAccessible(true);
                                hookDuoWriter(writer, duoClass);
                                totalHooks++;
                            } catch (Throwable t) {
                                ModuleLog.line(
                                        TAG + "writer hook resolve failed "
                                                + data + ": " + error(t)
                                );
                            }
                        }
                    } catch (Throwable t) {
                        ModuleLog.line(
                                TAG + "DexKit writer scan "
                                        + fieldName + " failed: " + error(t)
                        );
                    }

                    if (summary.length() > 0) summary.append(' ');
                    summary.append(fieldName).append('=').append(fieldMatches);
                }

                writerScanReady = true;
                installA0dBranchDiagnostics(bridge, loader);
                writerScanStatus = "ready hooks=" + totalHooks
                        + " matches=" + totalMatches
                        + " [" + summary + "]"
                        + (a0dStaticReady
                        ? " A0d-branch=ready"
                        : " A0d-branch=failed");
                ModuleLog.line(TAG + writerScanStatus);
            } catch (Throwable t) {
                writerScanStatus = "failed " + error(t);
                WRITER_SCAN_STARTED.set(false);
                ModuleLog.line(TAG + "writer scan failed: " + error(t));
            }
        }, "IE-PFP-DUO-writers");

        worker.setDaemon(true);
        worker.start();
    }

    private static void installA0dBranchDiagnostics(
            DexKitBridge bridge,
            ClassLoader loader
    ) {
        try {
            Class<?> fragmentClass = Class.forName(
                    "com.instagram.profile.fragment.UserDetailFragment",
                    false,
                    loader
            );

            Method a0d = null;
            for (Method method : fragmentClass.getDeclaredMethods()) {
                if (!"A0d".equals(method.getName())) continue;
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 2 && params[1] == boolean.class) {
                    method.setAccessible(true);
                    a0d = method;
                    break;
                }
            }

            if (a0d == null) return;

            Object methodData = bridge.getMethodData(a0d);
            if (methodData == null) return;

            A0D_READ_FIELDS.clear();

            try {
                Method getUsingFields =
                        methodData.getClass().getMethod("getUsingFields");
                Object value = getUsingFields.invoke(methodData);

                if (value instanceof Iterable) {
                    for (Object usingField : (Iterable<?>) value) {
                        if (usingField == null) continue;

                        Object usingType = invokeNoArg(
                                usingField,
                                "getUsingType"
                        );
                        if (usingType == null
                                || !"Read".equals(String.valueOf(usingType))) {
                            continue;
                        }

                        Object fieldData = invokeNoArg(
                                usingField,
                                "getField"
                        );
                        if (fieldData == null) continue;

                        try {
                            Method getFieldInstance =
                                    fieldData.getClass().getMethod(
                                            "getFieldInstance",
                                            ClassLoader.class
                                    );
                            Object reflected =
                                    getFieldInstance.invoke(fieldData, loader);
                            if (reflected instanceof Field) {
                                Field field = (Field) reflected;
                                field.setAccessible(true);
                                A0D_READ_FIELDS.add(field);
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable t) {
                ModuleLog.line(
                        TAG + "A0d read-field analysis failed: " + error(t)
                );
            }

            int invokeHooks = 0;

            try {
                Method getInvokes =
                        methodData.getClass().getMethod("getInvokes");
                Object value = getInvokes.invoke(methodData);

                if (value instanceof Iterable) {
                    for (Object invokeData : (Iterable<?>) value) {
                        if (invokeData == null || invokeHooks >= 40) break;

                        try {
                            Method getMethodInstance =
                                    invokeData.getClass().getMethod(
                                            "getMethodInstance",
                                            ClassLoader.class
                                    );
                            Object reflected =
                                    getMethodInstance.invoke(invokeData, loader);
                            if (!(reflected instanceof Method)) continue;

                            Method invoked = (Method) reflected;
                            String owner =
                                    invoked.getDeclaringClass().getName();

                            if (!(owner.startsWith("X.")
                                    || owner.startsWith("com.instagram."))) {
                                continue;
                            }

                            Class<?> returnType = invoked.getReturnType();
                            if (!(returnType.isPrimitive()
                                    || returnType.isEnum()
                                    || returnType == String.class)) {
                                continue;
                            }

                            String key = owner + "#" + signature(invoked);
                            if (!HOOKED_A0D_INVOKES.add(key)) continue;

                            invoked.setAccessible(true);
                            hookA0dInvoke(invoked);
                            invokeHooks++;
                        } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable t) {
                ModuleLog.line(
                        TAG + "A0d invoke analysis failed: " + error(t)
                );
            }

            a0dStaticReady = true;
            ModuleLog.line(
                    TAG + "A0d branch tracer ready fields="
                            + A0D_READ_FIELDS.size()
                            + " invokes=" + invokeHooks
            );
        } catch (Throwable t) {
            ModuleLog.line(
                    TAG + "A0d branch tracer failed: " + error(t)
            );
        }
    }

    private static Object invokeNoArg(Object target, String methodName) {
        if (target == null) return null;

        try {
            Method method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void hookA0dInvoke(Method invoked) {
        XposedBridge.hookMethod(invoked, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object duo = A0D_BRANCH_DUO.get();
                if (duo == null) return;

                Object originalResult = param.getResult();
                boolean repaired = false;

                // This is the first predicate we have observed that perfectly
                // separates the working and broken native viewer setup:
                // working A0d => true, broken A0d => false. Only override it
                // while A0d is being called from the normal A0U/A16 setup path
                // and this DUO has not been initialized yet.
                if (isNativeViewerSetupPredicate(invoked)
                        && Boolean.FALSE.equals(originalResult)
                        && readNamedField(duo, "A01") == null
                        && readNamedField(duo, "A02") == null
                        && readNamedField(duo, "A05") == null) {
                    param.setResult(Boolean.TRUE);
                    repaired = true;
                }

                appendA0dBranchHistory(
                        duo,
                        "  INVOKE "
                                + invoked.getDeclaringClass().getName()
                                + "." + signature(invoked)
                                + " args=" + argsSummary(param.args, 6)
                                + " -> " + compactValue(param.getResult())
                                + (repaired
                                ? " [REPAIR false→true]"
                                : "")
                                + " threw="
                                + (param.hasThrowable()
                                ? error(param.getThrowable())
                                : "none")
                );
            }
        });
    }

    private static boolean isNativeViewerSetupPredicate(Method method) {
        if (method == null) return false;

        Class<?>[] params = method.getParameterTypes();

        return "X.116".equals(method.getDeclaringClass().getName())
                && "A0U".equals(method.getName())
                && method.getReturnType() == boolean.class
                && params.length == 3
                && params[1] == int.class
                && params[2] == boolean.class;
    }

    private static String a0dReadSnapshot(
            Object fragment,
            Object[] args
    ) {
        if (!a0dStaticReady || A0D_READ_FIELDS.isEmpty()) {
            return "<static read map not ready>";
        }

        StringBuilder out = new StringBuilder();
        int shown = 0;

        synchronized (A0D_READ_FIELDS) {
            for (Field field : A0D_READ_FIELDS) {
                if (shown >= 48) {
                    out.append(" | …");
                    break;
                }

                Object owner = resolveFieldOwner(
                        field,
                        fragment,
                        args
                );
                Object value = null;
                boolean readable = false;

                try {
                    if (Modifier.isStatic(field.getModifiers())) {
                        value = field.get(null);
                        readable = true;
                    } else if (owner != null) {
                        value = field.get(owner);
                        readable = true;
                    }
                } catch (Throwable ignored) {}

                if (out.length() > 0) out.append(" | ");

                out.append(field.getDeclaringClass().getSimpleName())
                        .append('.')
                        .append(field.getName())
                        .append('=')
                        .append(readable
                                ? snapshotValue(value)
                                : "<owner?>");
                shown++;
            }
        }

        return out.length() == 0 ? "<no direct field reads>" : out.toString();
    }

    private static Object resolveFieldOwner(
            Field field,
            Object fragment,
            Object[] args
    ) {
        if (field == null) return null;

        Class<?> ownerType = field.getDeclaringClass();

        if (fragment != null && ownerType.isInstance(fragment)) {
            return fragment;
        }

        if (args != null) {
            for (Object arg : args) {
                if (arg != null && ownerType.isInstance(arg)) {
                    return arg;
                }
            }
        }

        Object found = findDirectObjectOfType(fragment, ownerType);
        if (found != null) return found;

        if (args != null) {
            for (Object arg : args) {
                found = findDirectObjectOfType(arg, ownerType);
                if (found != null) return found;
            }
        }

        Object duo = readUserDetailDuo(fragment);
        if (duo != null) {
            if (ownerType.isInstance(duo)) return duo;
            found = findDirectObjectOfType(duo, ownerType);
            if (found != null) return found;
        }

        return null;
    }

    private static Object findDirectObjectOfType(
            Object object,
            Class<?> wantedType
    ) {
        if (object == null || wantedType == null) return null;

        Class<?> cls = object.getClass();
        int checked = 0;

        while (cls != null && cls != Object.class && checked < 160) {
            Field[] fields;

            try {
                fields = cls.getDeclaredFields();
            } catch (Throwable t) {
                break;
            }

            for (Field field : fields) {
                if (checked++ >= 160) break;
                if (Modifier.isStatic(field.getModifiers())) continue;

                try {
                    field.setAccessible(true);
                    Object value = field.get(object);
                    if (value != null && wantedType.isInstance(value)) {
                        return value;
                    }
                } catch (Throwable ignored) {}
            }

            cls = cls.getSuperclass();
        }

        return null;
    }

    private static String currentA0dSetupCaller() {
        try {
            for (StackTraceElement frame
                    : Thread.currentThread().getStackTrace()) {
                if (!"com.instagram.profile.fragment.UserDetailFragment"
                        .equals(frame.getClassName())) {
                    continue;
                }

                String method = frame.getMethodName();
                if ("A0U".equals(method) || "A16".equals(method)) {
                    return method;
                }
            }
        } catch (Throwable ignored) {}

        return null;
    }

    private static void appendA0dBranchHistory(
            Object duo,
            String line
    ) {
        if (duo == null || line == null || line.isEmpty()) return;

        synchronized (A0D_BRANCH_HISTORY) {
            StringBuilder history = A0D_BRANCH_HISTORY.get(duo);
            if (history == null) {
                history = new StringBuilder();
                A0D_BRANCH_HISTORY.put(duo, history);
            }

            if (history.length() > 0) history.append('\n');
            history.append(line);

            if (history.length() > 7600) {
                history.delete(0, history.length() - 6600);
            }
        }
    }

    private static String a0dBranchHistory(Object duo) {
        if (duo == null) return "<null DUO>";

        synchronized (A0D_BRANCH_HISTORY) {
            StringBuilder history = A0D_BRANCH_HISTORY.get(duo);
            return history == null || history.length() == 0
                    ? "<no A16 A0d branch trace>"
                    : history.toString();
        }
    }

    private static void hookDuoWriter(
            Method writer,
            Class<?> duoClass
    ) {
        XposedBridge.hookMethod(writer, new XC_MethodHook() {
            private final ThreadLocal<String> beforeState = new ThreadLocal<>();
            private final ThreadLocal<Object> beforeDuo = new ThreadLocal<>();

            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object duo = findRelatedDuo(
                        param.thisObject,
                        param.args,
                        duoClass
                );
                beforeDuo.set(duo);
                beforeState.set(duo == null ? null : duoTrackedState(duo));
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object duo = beforeDuo.get();
                if (duo == null) {
                    duo = findRelatedDuo(
                            param.thisObject,
                            param.args,
                            duoClass
                    );
                }

                String before = beforeState.get();
                beforeDuo.remove();
                beforeState.remove();

                if (duo == null) return;

                String after = duoTrackedState(duo);
                if (before != null && before.equals(after)) return;

                appendDuoWriterHistory(
                        duo,
                        "WRITE " + writer.getDeclaringClass().getName()
                                + "." + signature(writer)
                                + "\n  " + duoIdentity(duo)
                                + " before=" + (before == null ? "?" : before)
                                + "\n  after=" + after
                                + "\n  callers=" + compactCallStack()
                );
            }
        });
    }

    private static Object findRelatedDuo(
            Object owner,
            Object[] args,
            Class<?> duoClass
    ) {
        Object direct = asDuo(owner, duoClass);
        if (direct != null) return direct;

        if (args != null) {
            for (Object arg : args) {
                direct = asDuo(arg, duoClass);
                if (direct != null) return direct;
            }
        }

        direct = directFieldOfType(owner, duoClass);
        if (direct != null) return direct;

        if (args != null) {
            for (Object arg : args) {
                direct = directFieldOfType(arg, duoClass);
                if (direct != null) return direct;
            }
        }

        return null;
    }

    private static Object asDuo(Object value, Class<?> duoClass) {
        return value != null && duoClass != null && duoClass.isInstance(value)
                ? value
                : null;
    }

    private static Object directFieldOfType(
            Object object,
            Class<?> wantedType
    ) {
        if (object == null || wantedType == null) return null;

        Class<?> cls = object.getClass();
        int inspected = 0;

        while (cls != null && cls != Object.class && inspected < 80) {
            Field[] fields;
            try {
                fields = cls.getDeclaredFields();
            } catch (Throwable t) {
                break;
            }

            for (Field field : fields) {
                if (inspected++ >= 80) break;
                if (Modifier.isStatic(field.getModifiers())) continue;
                if (!wantedType.isAssignableFrom(field.getType())) continue;

                try {
                    field.setAccessible(true);
                    Object value = field.get(object);
                    if (value != null) return value;
                } catch (Throwable ignored) {}
            }

            cls = cls.getSuperclass();
        }

        return null;
    }

    private static Field findFieldInHierarchy(
            Class<?> cls,
            String name
    ) {
        Class<?> current = cls;

        while (current != null && current != Object.class) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }

        return null;
    }

    private static String fieldDescriptor(Field field) {
        return classDescriptor(field.getDeclaringClass())
                + "->" + field.getName()
                + ":" + classDescriptor(field.getType());
    }

    private static String classDescriptor(Class<?> cls) {
        if (cls.isArray()) {
            return cls.getName().replace('.', '/');
        }
        if (cls.isPrimitive()) {
            if (cls == void.class) return "V";
            if (cls == boolean.class) return "Z";
            if (cls == byte.class) return "B";
            if (cls == char.class) return "C";
            if (cls == short.class) return "S";
            if (cls == int.class) return "I";
            if (cls == long.class) return "J";
            if (cls == float.class) return "F";
            if (cls == double.class) return "D";
        }
        return "L" + cls.getName().replace('.', '/') + ";";
    }

    private static void appendDuoWriterHistory(
            Object duo,
            String line
    ) {
        if (duo == null || line == null || line.isEmpty()) return;

        synchronized (DUO_WRITER_HISTORY) {
            StringBuilder history = DUO_WRITER_HISTORY.get(duo);
            if (history == null) {
                history = new StringBuilder();
                DUO_WRITER_HISTORY.put(duo, history);
            }

            if (history.length() > 0) history.append('\n');
            history.append(line);

            if (history.length() > 5200) {
                history.delete(0, history.length() - 4400);
            }
        }
    }

    private static String duoWriterHistory(Object duo) {
        if (duo == null) return "<null DUO>";

        synchronized (DUO_WRITER_HISTORY) {
            StringBuilder history = DUO_WRITER_HISTORY.get(duo);
            return history == null || history.length() == 0
                    ? "<no A01/A02/A05 writes observed>"
                    : history.toString();
        }
    }

    private static String duoIdentity(Object duo) {
        if (duo == null) return "null";
        return duo.getClass().getName()
                + "@"
                + Integer.toHexString(System.identityHashCode(duo));
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
