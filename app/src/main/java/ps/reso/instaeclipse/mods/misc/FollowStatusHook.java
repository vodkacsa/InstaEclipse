package ps.reso.instaeclipse.mods.misc;

import android.app.AndroidAppHelper;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.toast.CustomToast;
import ps.reso.instaeclipse.utils.tracker.FollowIndicatorTracker;
import ps.reso.instaeclipse.utils.users.FollowStatusResponse;

/** Existing toast response hook, now the single data source for both presentation options. */
public final class FollowStatusHook {
    private static final Map<Object, Pending> pending = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Set<Class<?>> hookedClasses = ConcurrentHashMap.newKeySet();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final class Pending {
        final FollowIndicatorTracker.Request request;
        final AtomicBoolean completed = new AtomicBoolean();
        final Map<Object, ByteArrayOutputStream> bodies = new IdentityHashMap<>();
        Pending(FollowIndicatorTracker.Request request) { this.request = request; }
        synchronized FollowStatusResponse append(Object callback, Object payload) {
            byte[] bytes = FollowStatusResponse.bodyBytes(payload);
            if (bytes == null || bytes.length == 0) return null;
            ByteArrayOutputStream body = bodies.computeIfAbsent(callback, ignored -> new ByteArrayOutputStream());
            if (body.size() + bytes.length > FollowStatusResponse.MAX_BYTES) { bodies.remove(callback); return null; }
            body.write(bytes, 0, bytes.length);
            return FollowStatusResponse.parse(new String(body.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    public static void handleRequest(URI uri, Object[] args) {
        if (uri == null || uri.getPath() == null) return;
        String path = uri.getPath();
        if (!path.matches("/api/v1/friendships/show/[0-9]+/?")) return;
        String userId = path.split("/")[5];
        long now = SystemClock.elapsedRealtime();
        Pending request = new Pending(FollowIndicatorTracker.INSTANCE.begin(userId, now));
        synchronized (pending) {
            Iterator<Map.Entry<Object, Pending>> it = pending.entrySet().iterator();
            while (it.hasNext()) {
                if (now - it.next().getValue().request.startedAt > 60_000) it.remove();
            }
            if (pending.size() >= 128) pending.clear();
        }
        if (args.length > 1) registerCallback(args[1], request);
        if (args.length > 2) registerCallback(args[2], request);
    }

    private static void registerCallback(Object callback, Pending request) {
        if (callback == null) return;
        pending.put(callback, request);
        // Hook inherited callback methods too; share one registration per declaring class.
        for (Class<?> c = callback.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            if (!hookedClasses.add(c)) continue;
            for (Method method : c.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers()) || Modifier.isAbstract(method.getModifiers())
                        || method.getParameterCount() == 0) continue;
                try {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            // Capture readable bytes before Instagram advances a buffer's position.
                            try { onCallback(p.thisObject, p.args, true); } catch (Throwable ignored) { }
                        }
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            // Some callbacks only populate their response-wrapper fields on return.
                            try { onCallback(p.thisObject, p.args, false); } catch (Throwable ignored) { }
                        }
                    });
                } catch (Throwable ignored) { }
            }
        }
    }

    private static void onCallback(Object callback, Object[] args, boolean before) {
        Pending request = pending.get(callback);
        if (request == null || request.completed.get()) return;
        FollowStatusResponse result = null;
        for (Object arg : args) {
            result = FollowStatusResponse.fromPayload(arg);
            if (result == null && before) result = request.append(callback, arg);
            if (result != null) break;
        }
        if (result == null && !before) result = FollowStatusResponse.fromPayload(callback);
        if (result == null || !request.completed.compareAndSet(false, true)) return;
        synchronized (pending) { pending.entrySet().removeIf(entry -> entry.getValue() == request); }
        if (!FollowIndicatorTracker.INSTANCE.publish(request.request, result, SystemClock.elapsedRealtime())) return;
        if (FeatureFlags.showProfileRelationship) FeatureStatusTracker.setHooked("ProfileRelationship");
        if (FeatureFlags.showFollowerToast) FeatureStatusTracker.setHooked("FollowerToast");
        final FollowStatusResponse status = result;
        MAIN.post(() -> {
            if (!FeatureFlags.showFollowerToast || !FollowIndicatorTracker.INSTANCE.consumeToast(request.request)) return;
            try {
                Context ctx = AndroidAppHelper.currentApplication().getApplicationContext();
                String text = I18n.t(ctx, status.followedBy ? R.string.ig_toast_follows_you : R.string.ig_toast_not_follows_you);
                CustomToast.showCustomToast(ctx, "(" + request.request.userId + ") " + text);
            } catch (Throwable ignored) { }
        });
    }
}
