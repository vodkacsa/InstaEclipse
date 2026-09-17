package ps.reso.instaeclipse.mods.profile;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.luckypray.dexkit.DexKitBridge;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/** A profile-only badge reading the displayed fragment's own model, with no network requests. */
public final class ProfileRelationshipHook {
    private final Handler main = new Handler(Looper.getMainLooper());
    private WeakReference<Object> active = new WeakReference<>(null);
    private TextView badge;
    private ProfileModelReader reader;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            Object fragment = active.get();
            if (fragment == null) { removeBadge(); return; }
            try { update(fragment); }
            catch (Throwable ignored) { removeBadge(); }
            main.postDelayed(this, 1000);
        }
    };

    public void install(DexKitBridge bridge, ClassLoader loader) throws ClassNotFoundException {
        reader = new ProfileModelReader(bridge, loader);
        Class<?> fragmentClass = loader.loadClass("com.instagram.profile.fragment.UserDetailFragment");
        // Hook the actual declaring method as some versions inherit lifecycle callbacks.
        XposedBridge.hookMethod(lifecycleMethod(fragmentClass, "onResume"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!fragmentClass.isInstance(p.thisObject)) return;
                main.removeCallbacks(refresh);
                removeBadge();
                active = new WeakReference<>(p.thisObject);
                main.post(refresh);
            }
        });
        XposedBridge.hookMethod(lifecycleMethod(fragmentClass, "onPause"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (active.get() != p.thisObject) return;
                active.clear();
                main.removeCallbacks(refresh);
                removeBadge();
            }
        });
        FeatureStatusTracker.setHooked("ProfileRelationship");
        ModuleLog.line("(IE|ProfileRelationship) Profile lifecycle hooks installed");
    }

    private static java.lang.reflect.Method lifecycleMethod(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try { return c.getDeclaredMethod(name); } catch (NoSuchMethodException ignored) { }
        }
        throw new IllegalStateException("Missing lifecycle callback: " + name);
    }

    private void update(Object fragment) {
        if (!FeatureFlags.showProfileRelationship) { removeBadge(); return; }
        Activity activity = (Activity) XposedHelpers.callMethod(fragment, "getActivity");
        View root = (View) XposedHelpers.callMethod(fragment, "getView");
        if (activity == null || root == null || !root.isShown()) { removeBadge(); return; }
        Object user = findDisplayedUser(fragment, root);
        // A missing or ambiguous model is not evidence that the account does not follow you.
        RelationshipStatus status = user == null ? RelationshipStatus.UNKNOWN : reader.status(user);
        if (badge == null) {
            View content = activity.findViewById(android.R.id.content);
            if (!(content instanceof FrameLayout)) return;
            badge = new TextView(activity);
            badge.setTextColor(Color.WHITE);
            badge.setTextSize(12);
            badge.setPadding(dp(root, 12), dp(root, 6), dp(root, 12), dp(root, 6));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xEE262626);
            bg.setCornerRadius(dp(root, 12));
            badge.setBackground(bg);
            badge.setElevation(dp(root, 8));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            lp.topMargin = dp(root, 56);
            lp.rightMargin = dp(root, 12);
            ((FrameLayout) content).addView(badge, lp);
        }
        int text = switch (status) {
            case FRIENDS -> R.string.profile_relationship_friends;
            case FOLLOWS_YOU -> R.string.profile_relationship_follows_you;
            case FOLLOWING -> R.string.profile_relationship_following;
            case NEITHER -> R.string.profile_relationship_neither;
            default -> R.string.profile_relationship_unknown;
        };
        badge.setText(I18n.t(activity, text));
    }

    private Object findDisplayedUser(Object fragment, View root) {
        Map<String, Object> users = new LinkedHashMap<>();
        collectUsers(fragment, users, 1);
        // Bind by the title as well as the fragment, so logged-in User fields cannot leak
        // into another person's profile badge. Multiple candidates fail closed.
        int titleId = root.getResources().getIdentifier("action_bar_title", "id", root.getContext().getPackageName());
        View title = titleId == 0 ? null : root.getRootView().findViewById(titleId);
        String username = title instanceof TextView ? ((TextView) title).getText().toString().trim() : null;
        Object match = null;
        for (Object user : users.values()) {
            Object name = reader.read(user, "username", "getUsername");
            if (username == null || !(name instanceof String) || !username.equalsIgnoreCase((String) name)) continue;
            if (match != null) return null;
            match = user;
        }
        return match;
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
                            || f.getType().getName().startsWith("com.instagram.profile."))) {
                        collectUsers(value, users, depth - 1);
                    }
                } catch (Throwable ignored) { }
            }
        }
    }

    private void removeBadge() {
        if (badge != null && badge.getParent() instanceof ViewGroup) ((ViewGroup) badge.getParent()).removeView(badge);
        badge = null;
    }
    private static int dp(View view, int value) { return Math.round(view.getResources().getDisplayMetrics().density * value); }
}
