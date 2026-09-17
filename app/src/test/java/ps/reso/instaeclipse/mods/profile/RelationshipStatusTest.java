package ps.reso.instaeclipse.mods.profile;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class RelationshipStatusTest {
    @Test public void mutualFollowIsFriends() {
        assertEquals(RelationshipStatus.FRIENDS, RelationshipStatus.from(true, true));
    }
    @Test public void distinguishesBothOneWayDirectionsAndNeither() {
        assertEquals(RelationshipStatus.FOLLOWS_YOU, RelationshipStatus.from(true, false));
        assertEquals(RelationshipStatus.FOLLOWING, RelationshipStatus.from(false, true));
        assertEquals(RelationshipStatus.NEITHER, RelationshipStatus.from(false, false));
    }
    @Test public void missingDataNeverClaimsNonFollowOrMutualFollow() {
        assertEquals(RelationshipStatus.UNKNOWN, RelationshipStatus.from(null, null));
        assertEquals(RelationshipStatus.UNKNOWN, RelationshipStatus.from(null, true));
        assertEquals(RelationshipStatus.UNKNOWN, RelationshipStatus.from(null, false));
        assertEquals(RelationshipStatus.UNKNOWN, RelationshipStatus.from(false, null));
        assertEquals(RelationshipStatus.FOLLOWS_YOU, RelationshipStatus.from(true, null));
    }
}
