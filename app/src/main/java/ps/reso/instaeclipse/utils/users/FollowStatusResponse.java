package ps.reso.instaeclipse.utils.users;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Read-only decoding shared by the original follower toast and the inline profile label. */
public final class FollowStatusResponse {
    public static final int MAX_BYTES = 256 * 1024;
    public final boolean followedBy;
    public final Boolean following;

    private FollowStatusResponse(boolean followedBy, Boolean following) {
        this.followedBy = followedBy;
        this.following = following;
    }

    public static FollowStatusResponse parse(String body) {
        if (body == null || body.length() > MAX_BYTES) return null;
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject()) return null;
            JsonObject object = parsed.getAsJsonObject();
            if (object.has("status") && !"ok".equals(object.get("status").getAsString())) return null;
            if (object.has("friendship_status") && object.get("friendship_status").isJsonObject()) {
                object = object.getAsJsonObject("friendship_status");
            }
            Boolean followedBy = booleanField(object, "followed_by");
            if (followedBy == null) return null; // Missing data is never a negative relationship.
            return new FollowStatusResponse(followedBy, booleanField(object, "following"));
        } catch (RuntimeException ignored) { return null; }
    }

    private static Boolean booleanField(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
                ? value.getAsBoolean() : null;
    }

    public static byte[] bodyBytes(Object value) {
        if (value instanceof byte[]) {
            byte[] bytes = (byte[]) value;
            return bytes.length <= MAX_BYTES ? bytes : null;
        }
        if (value instanceof ByteBuffer) {
            ByteBuffer copy = ((ByteBuffer) value).duplicate();
            if (copy.remaining() > MAX_BYTES) return null;
            byte[] bytes = new byte[copy.remaining()];
            copy.get(bytes); // Supports direct and sliced buffers, without consuming IG's buffer.
            return bytes;
        }
        if (value instanceof String && ((String) value).length() <= MAX_BYTES) {
            return ((String) value).getBytes(StandardCharsets.UTF_8);
        }
        return null;
    }

    public static FollowStatusResponse fromPayload(Object payload) {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        return fromPayload(payload, 3, seen);
    }

    private static FollowStatusResponse fromPayload(Object payload, int depth, Set<Object> seen) {
        if (payload == null || depth < 0 || !seen.add(payload)) return null;
        byte[] bytes = bodyBytes(payload);
        if (bytes != null) return parse(new String(bytes, StandardCharsets.UTF_8));
        if (payload instanceof Number || payload instanceof Boolean || payload instanceof Enum
                || payload instanceof Class) return null;
        if (payload instanceof JsonObject) return parse(payload.toString());
        if (depth == 0) return null;
        for (Class<?> c = payload.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                try {
                    field.setAccessible(true);
                    FollowStatusResponse result = fromPayload(field.get(payload), depth - 1, seen);
                    if (result != null) return result;
                } catch (Throwable ignored) { }
            }
        }
        return null;
    }
}
