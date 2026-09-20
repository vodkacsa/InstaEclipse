package ps.reso.instaeclipse.utils.users;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class FollowStatusResponseTest {
    @Test public void readsBothDirectionsFromExistingToastResponse() {
        FollowStatusResponse mutual = FollowStatusResponse.parse("{\"status\":\"ok\",\"followed_by\":true,\"following\":true}");
        assertTrue(mutual.followedBy);
        assertEquals(Boolean.TRUE, mutual.following);
        FollowStatusResponse oneWay = FollowStatusResponse.parse("{\"followed_by\":true,\"following\":false}");
        assertTrue(oneWay.followedBy);
        assertEquals(Boolean.FALSE, oneWay.following);
    }
    @Test public void understandsNestedResponseAndPreservesMissingFollowing() {
        FollowStatusResponse value = FollowStatusResponse.parse("{\"friendship_status\":{\"followed_by\":false}}");
        assertFalse(value.followedBy);
        assertNull(value.following);
    }
    @Test public void malformedMissingAndErrorDataAreNotNegativeFollows() {
        for (String json : new String[]{"{}", "{\"followed_by\":", "{\"followed_by\":null}",
                "{\"followed_by\":\"false\"}", "{\"status\":\"fail\",\"followed_by\":false}"}) {
            assertNull(json, FollowStatusResponse.parse(json));
        }
    }
    @Test public void directSlicedAndReadOnlyBuffersKeepTheirPositionsAndLimits() {
        byte[] json = "{\"followed_by\":true,\"following\":false}".getBytes(StandardCharsets.UTF_8);
        ByteBuffer direct = ByteBuffer.allocateDirect(json.length + 8);
        direct.putInt(99).put(json).putInt(99).flip();
        direct.position(4).limit(4 + json.length);
        for (ByteBuffer view : new ByteBuffer[]{direct, direct.slice(), direct.asReadOnlyBuffer()}) {
            int position = view.position(), limit = view.limit();
            assertTrue(FollowStatusResponse.fromPayload(view).followedBy);
            assertEquals(position, view.position());
            assertEquals(limit, view.limit());
        }
    }
    @Test public void readsStringAndWrapperPayloadsWithoutTraversingCyclesForever() {
        class Wrapper { Object body; Object self = this; Wrapper(Object value) { body = value; } }
        String json = "{\"followed_by\":true}";
        assertTrue(FollowStatusResponse.fromPayload(json).followedBy);
        assertTrue(FollowStatusResponse.fromPayload(new Wrapper(new Wrapper(json))).followedBy);
        assertNull(FollowStatusResponse.fromPayload(new Wrapper(null)));
        assertNull(FollowStatusResponse.bodyBytes(new byte[FollowStatusResponse.MAX_BYTES + 1]));
    }
}
