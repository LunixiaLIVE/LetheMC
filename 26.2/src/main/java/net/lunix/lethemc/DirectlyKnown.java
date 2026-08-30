package net.lunix.lethemc;

import java.util.Set;
import java.util.UUID;

/**
 * Players a villager has actually dealt with, as opposed to merely heard about.
 *
 * <p>The distinction decides whether a villager is destroyed when a life ends, and it exists
 * because gossip spreads. {@code GossipContainer.transferFrom} merges entries straight into the
 * map -- it never calls {@code add} -- so villagers gossip about players they have never met.
 * Without this separation, one death would cascade outwards through a village as the news
 * travelled, taking villagers the player never once traded with.
 *
 * <p>So {@code add} is the mark of a real encounter: a trade, a cure, a thrown punch. Anything
 * arriving by hearsay leaves this set alone.
 *
 * <p>Kept apart from {@link LifeStamped} on purpose. Stamps still travel with transferred gossip,
 * because a dead life's reputation must read as nothing wherever it ends up -- it is only the
 * <em>destruction</em> that needs to stay local to villagers the player really used.
 */
public interface DirectlyKnown {

    /** Live set of players this villager has dealt with face to face. */
    Set<UUID> lethemc$direct();

    /** NBT key. Semicolon-separated UUIDs. */
    String KEY = "lethemc:direct";

    static String encode(Set<UUID> direct) {
        StringBuilder sb = new StringBuilder();
        for (UUID id : direct) {
            if (sb.length() > 0) sb.append(';');
            sb.append(id);
        }
        return sb.toString();
    }

    static void decode(String raw, Set<UUID> into) {
        into.clear();
        if (raw == null || raw.isBlank()) return;
        for (String part : raw.split(";")) {
            try {
                into.add(UUID.fromString(part));
            } catch (IllegalArgumentException ignored) {
                // Hand-edited or corrupted. Dropping the entry spares the villager, which is
                // the harmless direction when the alternative is deleting one by mistake.
            }
        }
    }
}
