package ps.reso.instaeclipse.mods.profile;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Resolve property accessors by their JSON keys, never by obfuscated method order. */
final class ProfileModelReader {
    private final Map<String, List<Method>> getters = new HashMap<>();

    ProfileModelReader(DexKitBridge bridge, ClassLoader loader) {
        for (String key : new String[]{"followed_by", "following", "friendship_status", "username"}) {
            List<Method> methods = new ArrayList<>();
            try {
                for (MethodData data : bridge.findMethod(FindMethod.create().matcher(
                        MethodMatcher.create().usingStrings(key).paramCount(0)))) {
                    Method method = data.getMethodInstance(loader);
                    if (!Modifier.isStatic(method.getModifiers())) methods.add(method);
                }
            } catch (Throwable ignored) { }
            getters.put(key, methods);
        }
    }

    Object read(Object model, String key, String... namedGetters) {
        if (model == null) return null;
        for (String name : namedGetters) {
            Object value = invoke(model, name);
            if (value != null) return value;
        }
        for (Method candidate : getters.getOrDefault(key, java.util.Collections.emptyList())) {
            // Pando and POJO implementations share an interface, but not a superclass.
            // Only transfer a getter name when both implement that exact interface.
            if (candidate.getDeclaringClass().isInstance(model) || sharesAccessor(model, candidate)) {
                Object value = invoke(model, candidate.getName());
                if (value != null) return value;
            }
        }
        return null;
    }

    private static boolean sharesAccessor(Object model, Method candidate) {
        for (Class<?> type = candidate.getDeclaringClass(); type != null; type = type.getSuperclass()) {
            for (Class<?> iface : type.getInterfaces()) {
                if (!iface.isInstance(model)) continue;
                try {
                    iface.getMethod(candidate.getName());
                    return true;
                } catch (NoSuchMethodException ignored) { }
            }
        }
        return false;
    }

    private static Object invoke(Object model, String name) {
        try {
            Method m = model.getClass().getMethod(name);
            if (Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 0) return null;
            m.setAccessible(true);
            return m.invoke(model);
        } catch (Throwable ignored) { return null; }
    }

    RelationshipStatus status(Object user) {
        Object friendship = read(user, "friendship_status", "getFriendshipStatus");
        if (friendship == null) {
            for (Method m : user.getClass().getMethods()) {
                if (m.getParameterCount() != 0 || Modifier.isStatic(m.getModifiers())
                        || !m.getReturnType().getName().contains("FriendshipStatus")) continue;
                friendship = invoke(user, m.getName());
                if (friendship != null) break;
            }
        }
        Object source = friendship != null ? friendship : user;
        Object followedBy = read(source, "followed_by", "getFollowedBy", "isFollowedBy");
        Object following = read(source, "following", "getFollowing", "isFollowing");
        return RelationshipStatus.from(followedBy instanceof Boolean ? (Boolean) followedBy : null,
                following instanceof Boolean ? (Boolean) following : null);
    }
}
