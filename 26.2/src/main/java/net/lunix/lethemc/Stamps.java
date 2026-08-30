package net.lunix.lethemc;

import java.util.Map;
import java.util.UUID;

/**
 * Encoding and staleness for "which life was this player living when X happened".
 *
 * <p>Three things now need the same record: a fox's trust, a vault's reward list, and a
 * villager's opinion of you. All three are stamped per player rather than per object, because
 * all three can hold entries for more than one player at once and one player's death must not
 * disturb another's.
 *
 * <p>Serialised as {@code uuid=incarnation} pairs separated by semicolons. Plain text rather
 * than a compound so it can be read straight out of {@code /data get} while testing, which is
 * how every one of these features was actually verified.
 */
public final class Stamps {

    private Stamps() {}

    public static String encode(Map<UUID, String> stamps) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<UUID, String> e : stamps.entrySet()) {
            if (sb.length() > 0) sb.append(';');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    public static void decode(String raw, Map<UUID, String> into) {
        into.clear();
        if (raw == null || raw.isBlank()) return;
        for (String pair : raw.split(";")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            try {
                into.put(UUID.fromString(pair.substring(0, eq)), pair.substring(eq + 1));
            } catch (IllegalArgumentException ignored) {
                // Hand-edited or corrupted data. Dropping the entry leaves the record intact,
                // which is the harmless direction to fail in for all three callers.
            }
        }
    }

    /**
     * Whether this player's entry belongs to a life that has ended.
     *
     * <p>Two guards, both deliberately answering "no":
     * <ul>
     *   <li><b>No stamp</b> -- recorded before this feature existed. Acting on it would wipe
     *       every vault and every villager opinion on the server the first time it ran.</li>
     *   <li><b>Unknown player</b> -- never seen, so there is nothing to compare against.</li>
     * </ul>
     */
    public static boolean isStale(UUID player, Map<UUID, String> stamps) {
        String stamp = stamps.get(player);
        if (stamp == null) return false;
        String living = Incarnations.peek(player);
        if (living == null) return false;
        return !living.equals(stamp);
    }

    /** Records the life this player is living now. */
    public static void mark(UUID player, Map<UUID, String> stamps) {
        stamps.put(player, Incarnations.of(player));
    }
}
