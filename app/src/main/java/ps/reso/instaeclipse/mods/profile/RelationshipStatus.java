package ps.reso.instaeclipse.mods.profile;

/** Unknown values must never be presented as a confirmed non-follow. */
public enum RelationshipStatus {
    UNKNOWN, FRIENDS, FOLLOWS_YOU, FOLLOWING, NEITHER;

    public static RelationshipStatus from(Boolean followedBy, Boolean following) {
        if (followedBy == null) return UNKNOWN;
        if (followedBy) return Boolean.TRUE.equals(following) ? FRIENDS : FOLLOWS_YOU;
        if (following == null) return UNKNOWN;
        return following ? FOLLOWING : NEITHER;
    }
}
