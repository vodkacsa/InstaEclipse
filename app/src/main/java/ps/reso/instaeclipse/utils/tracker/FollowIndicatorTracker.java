package ps.reso.instaeclipse.utils.tracker;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import ps.reso.instaeclipse.utils.users.FollowStatusResponse;

/** Bounded in-memory results from the existing /friendships/show/ toast hook. */
public final class FollowIndicatorTracker {
    public static final FollowIndicatorTracker INSTANCE = new FollowIndicatorTracker();
    private static final long MAX_AGE_MS = 60_000;
    private static final int MAX_USERS = 64;
    private final Map<String, Result> results = new LinkedHashMap<>();
    private final Map<String, Long> newestRequests = new LinkedHashMap<>();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private Object account;
    private long generation;
    private long sequence;
    private Request toastTarget;

    public static final class Request {
        public final String userId;
        public final long startedAt;
        private final long generation, sequence;
        private Request(String id, long time, long generation, long sequence) {
            userId = id; startedAt = time; this.generation = generation; this.sequence = sequence;
        }
    }

    public static final class Result {
        public final FollowStatusResponse status;
        public final long receivedAt;
        private Result(FollowStatusResponse status, long time) { this.status = status; receivedAt = time; }
    }

    /** Account identity comes from the visible fragment's UserSession, never a profile's User. */
    public synchronized void setAccount(Object identity) {
        if (identity == null || identity.equals(account)) return;
        if (account != null) {
            generation++;
            results.clear();
            newestRequests.clear();
            toastTarget = null;
        }
        account = identity;
    }

    public Request begin(String userId, long now) {
        Request request;
        synchronized (this) {
            request = new Request(userId, now, generation, ++sequence);
            toastTarget = request;
            newestRequests.put(userId, request.sequence);
            results.remove(userId); // Refresh removes stale labels while a request is in flight.
            trim(newestRequests);
        }
        notifyListeners();
        return request;
    }

    public boolean publish(Request request, FollowStatusResponse value, long now) {
        synchronized (this) {
            if (value == null || request.generation != generation || now - request.startedAt > MAX_AGE_MS
                    || !Long.valueOf(request.sequence).equals(newestRequests.get(request.userId))) return false;
            results.put(request.userId, new Result(value, now));
            trim(results);
        }
        notifyListeners();
        return true;
    }

    public synchronized Result get(String userId, long now) {
        Result result = results.get(userId);
        if (result != null && now - result.receivedAt > MAX_AGE_MS) { results.remove(userId); return null; }
        return result;
    }

    public synchronized boolean consumeToast(Request request) {
        if (toastTarget != request || request.generation != generation) return false;
        toastTarget = null;
        return true;
    }

    public void refreshPresentation() { notifyListeners(); }

    public void addListener(Runnable listener) { listeners.addIfAbsent(listener); }
    private void notifyListeners() {
        for (Runnable listener : listeners) {
            try { listener.run(); } catch (RuntimeException ignored) { }
        }
    }
    private static void trim(Map<?, ?> map) {
        while (map.size() > MAX_USERS) map.remove(map.keySet().iterator().next());
    }
}
