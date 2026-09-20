package ps.reso.instaeclipse.utils.tracker;

import org.junit.Test;
import ps.reso.instaeclipse.utils.users.FollowStatusResponse;
import static org.junit.Assert.*;

public class FollowIndicatorTrackerTest {
    private final FollowStatusResponse follows = FollowStatusResponse.parse("{\"followed_by\":true,\"following\":true}");
    @Test public void lateResponseCannotOverwriteNewerRelationship() {
        FollowIndicatorTracker tracker = new FollowIndicatorTracker();
        FollowIndicatorTracker.Request old = tracker.begin("1", 10), recent = tracker.begin("1", 20);
        assertTrue(tracker.publish(recent, follows, 30));
        assertFalse(tracker.publish(old, FollowStatusResponse.parse("{\"followed_by\":false}"), 40));
        assertTrue(tracker.get("1", 50).status.followedBy);
    }
    @Test public void userResultsAreSeparateAndConsumingToastDoesNotEraseInlineData() {
        FollowIndicatorTracker tracker = new FollowIndicatorTracker();
        FollowIndicatorTracker.Request a = tracker.begin("1", 10), b = tracker.begin("2", 20);
        tracker.publish(a, follows, 30);
        assertNull(tracker.get("2", 30));
        assertFalse(tracker.consumeToast(a));
        assertTrue(tracker.consumeToast(b));
        assertFalse(tracker.consumeToast(b));
        assertNotNull(tracker.get("1", 40));
    }
    @Test public void accountSwitchRejectsOutstandingCallbacksAndClearsCache() {
        FollowIndicatorTracker tracker = new FollowIndicatorTracker();
        tracker.setAccount("account-a");
        FollowIndicatorTracker.Request request = tracker.begin("1", 10);
        tracker.publish(request, follows, 20);
        tracker.setAccount("account-b");
        assertNull(tracker.get("1", 30));
        assertFalse(tracker.publish(request, follows, 40));
    }
    @Test public void freshRequestsClearOldDataAndResultsExpire() {
        FollowIndicatorTracker tracker = new FollowIndicatorTracker();
        FollowIndicatorTracker.Request first = tracker.begin("1", 10);
        tracker.publish(first, follows, 20);
        assertNull(tracker.get("1", 60_021));
        FollowIndicatorTracker.Request next = tracker.begin("1", 70_000);
        tracker.publish(next, follows, 70_001);
        tracker.begin("1", 70_002);
        assertNull(tracker.get("1", 70_003));
    }
}
