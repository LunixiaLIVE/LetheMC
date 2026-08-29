package net.lunix.nixreaper;

import java.util.Map;
import java.util.UUID;

/**
 * A fox's record of which life each of its trusted players was living.
 *
 * <p>Foxes do not fit the single stamp used for pets and livestock. Trust is not ownership: a
 * fox trusts up to <b>two</b> entities independently, so one stamp per animal cannot say which
 * of them has since died. And because trust is shared, one player's reincarnation must cost
 * only their own trust -- destroying the fox, or clearing it wholesale, would punish the other
 * player for a death that was not theirs.
 *
 * <p>Keyed by trusted UUID rather than by slot number. Vanilla shifts entries between the two
 * slots as trust is granted, and a positional record would silently attach the wrong life to
 * the wrong player the first time that happened.
 */
public interface TrustStamped {

    /** Live map of trusted player UUID to the incarnation that earned that trust. */
    Map<UUID, String> nixreaper$trustStamps();

    /** NBT key. Serialised as {@code uuid=incarnation} pairs separated by semicolons. */
    String KEY = "nixreaper:trustStamps";

    /** Serialised flat: this is written for every fox that has ever been trusted. */
    static String encode(Map<UUID, String> stamps) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<UUID, String> e : stamps.entrySet()) {
            if (sb.length() > 0) sb.append(';');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    static void decode(String raw, Map<UUID, String> into) {
        into.clear();
        if (raw == null || raw.isBlank()) return;
        for (String pair : raw.split(";")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            try {
                into.put(UUID.fromString(pair.substring(0, eq)), pair.substring(eq + 1));
            } catch (IllegalArgumentException ignored) {
                // Hand-edited or corrupted NBT. Skipping the entry means the fox keeps its
                // trust, which is the harmless direction to fail in.
            }
        }
    }
}
