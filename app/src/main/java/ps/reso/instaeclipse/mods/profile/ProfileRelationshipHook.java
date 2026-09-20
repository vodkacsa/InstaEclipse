package ps.reso.instaeclipse.mods.profile;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.tracker.FollowIndicatorTracker;
import ps.reso.instaeclipse.utils.users.UserUtils;

/** Binds the existing toast response to the current profile's real, scrolling header. */
public final class ProfileRelationshipHook {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ProfileHeaderLabel label = new ProfileHeaderLabel();
    private WeakReference<Object> active = new WeakReference<>(null);
    private WeakReference<View> observedRoot = new WeakReference<>(null);
    private boolean queued;
    private String boundUserId;
    private final ViewTreeObserver.OnGlobalLayoutListener layoutChanged = this::scheduleRefresh;
    private final Runnable refresh = () -> {
        queued = false;
        try { update(); }
        catch (Throwable ignored) { label.clear(); }
    };

    public void install(ClassLoader loader) throws ClassNotFoundException {
        Class<?> fragmentClass = loader.loadClass("com.instagram.profile.fragment.UserDetailFragment");
        XposedBridge.hookMethod(lifecycleMethod(fragmentClass, "onResume"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!fragmentClass.isInstance(p.thisObject)) return;
                detach();
                active = new WeakReference<>(p.thisObject);
                FollowIndicatorTracker.INSTANCE.setAccount(findSession(p.thisObject));
                scheduleRefresh();
            }
        });
        XC_MethodHook leave = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (active.get() == p.thisObject) detach();
            }
        };
        XposedBridge.hookMethod(lifecycleMethod(fragmentClass, "onPause"), leave);
        XposedBridge.hookMethod(lifecycleMethod(fragmentClass, "onDestroyView"), leave);
        FollowIndicatorTracker.INSTANCE.addListener(this::scheduleRefresh);
        ModuleLog.line("(IE|ProfileRelationship) Inline header enabled; using follower-toast responses");
    }

    private void scheduleRefresh() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(this::scheduleRefresh); return; }
        if (active.get() == null || queued) return;
        queued = true;
        main.postDelayed(refresh, 50); // Coalesce response/layout events, without polling.
    }

    private void update() {
        Object fragment = active.get();
        if (fragment == null) { label.clear(); return; }
        View root = (View) XposedHelpers.callMethod(fragment, "getView");
        if (root != observedRoot.get()) {
            unobserve();
            label.clear();
            boundUserId = null;
            if (root != null) {
                root.getViewTreeObserver().addOnGlobalLayoutListener(layoutChanged);
                observedRoot = new WeakReference<>(root);
            }
        }
        if (!FeatureFlags.showProfileRelationship || root == null || !root.isShown()) { label.clear(); return; }
        String userId = findDisplayedUserId(fragment, root);
        if (userId == null || !userId.equals(boundUserId)) label.clear();
        boundUserId = userId;
        if (userId == null) return;
        FollowIndicatorTracker.Result result = FollowIndicatorTracker.INSTANCE.get(userId, SystemClock.elapsedRealtime());
        if (result == null) { label.clear(); return; } // No floating/loading/unknown-status decoration.
        RelationshipStatus status = RelationshipStatus.from(result.status.followedBy, result.status.following);
        int text = switch (status) {
            case FRIENDS -> R.string.profile_relationship_friends;
            case FOLLOWS_YOU -> R.string.profile_relationship_follows_you;
            case FOLLOWING -> R.string.profile_relationship_following;
            case NEITHER, DOES_NOT_FOLLOW -> R.string.profile_relationship_neither;
            default -> 0;
        };
        if (text == 0) label.clear();
        else label.render(root, I18n.t(root.getContext(), text));
    }

    private static String findDisplayedUserId(Object fragment, View root) {
        // Profile navigation arguments identify the target without guessing from network order.
        try {
            Bundle arguments = (Bundle) XposedHelpers.callMethod(fragment, "getArguments");
            String target = null;
            if (arguments != null) for (String key : arguments.keySet()) {
                String k = key.toLowerCase(Locale.ROOT);
                if (!k.endsWith("user_id") || k.contains("viewer") || k.contains("logged") || k.contains("session")) continue;
                Object value = arguments.get(key);
                String id = value == null ? "" : value.toString();
                if (!id.matches("[0-9]+")) continue;
                if (target != null && !target.equals(id)) { target = null; break; }
                target = id;
            }
            if (target != null) return target;
        } catch (Throwable ignored) { }
        // Only read User identity. All relationship values come from the shared toast handler.
        Map<String, Object> users = new LinkedHashMap<>();
        collectUsers(fragment, users, 1);
        int titleId = root.getResources().getIdentifier("action_bar_title", "id", root.getContext().getPackageName());
        View title = titleId == 0 ? null : root.getRootView().findViewById(titleId);
        String titleName = title instanceof TextView ? ((TextView) title).getText().toString().trim() : null;
        String match = null;
        for (Map.Entry<String, Object> entry : users.entrySet()) {
            String username = username(entry.getValue());
            if (titleName == null || username == null || !titleName.equalsIgnoreCase(username)) continue;
            if (match != null && !match.equals(entry.getKey())) return null;
            match = entry.getKey();
        }
        return match;
    }

    private static String username(Object user) {
        try {
            Method getter = UserUtils.userUsernameGetter;
            if (getter != null && getter.getDeclaringClass().isInstance(user)) {
                Object value = getter.invoke(user);
                if (value instanceof String) return (String) value;
            }
            return (String) XposedHelpers.callMethod(user, "getUsername");
        } catch (Throwable ignored) { return null; }
    }

    private static Object findSession(Object fragment) {
        for (Class<?> c = fragment.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || !f.getType().getName().endsWith("UserSession")) continue;
                try {
                    f.setAccessible(true);
                    Object session = f.get(fragment);
                    if (session == null) continue;
                    try { return XposedHelpers.callMethod(session, "getUserId"); }
                    catch (Throwable ignored) { return session; }
                } catch (Throwable ignored) { }
            }
        }
        return null;
    }

    private static void collectUsers(Object object, Map<String, Object> users, int depth) {
        if (object == null) return;
        for (Class<?> c = object.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                try {
                    f.setAccessible(true);
                    Object value = f.get(object);
                    if (value == null || value == object) continue;
                    if (value.getClass().getName().equals("com.instagram.user.model.User")) {
                        Object id = XposedHelpers.callMethod(value, "getId");
                        if (id instanceof String) users.put((String) id, value);
                    } else if (depth > 0 && (f.getType().getName().startsWith("X.")
                            || f.getType().getName().startsWith("com.instagram.profile."))) collectUsers(value, users, depth - 1);
                } catch (Throwable ignored) { }
            }
        }
    }

    private void detach() {
        active.clear(); boundUserId = null;
        main.removeCallbacks(refresh); queued = false;
        unobserve(); label.clear();
    }
    private void unobserve() {
        View root = observedRoot.get();
        if (root != null && root.getViewTreeObserver().isAlive()) root.getViewTreeObserver().removeOnGlobalLayoutListener(layoutChanged);
        observedRoot.clear();
    }
    private static Method lifecycleMethod(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try { return c.getDeclaredMethod(name); } catch (NoSuchMethodException ignored) { }
        }
        throw new IllegalStateException("Missing lifecycle callback: " + name);
    }
}
