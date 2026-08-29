package net.lunix.nixreaper;

import java.util.Map;
import java.util.UUID;

/**
 * Something out in the world that remembers players, and therefore has to remember which
 * <em>life</em> of theirs it was.
 *
 * <p>Implemented by mixin on a vault's reward list and a villager's gossip. Both store player
 * UUIDs outside the files a purge deletes, so without this a reincarnated player keeps a trade
 * discount they earned in a past life, and is still refused by a vault a past life emptied.
 *
 * @see Stamps
 */
public interface LifeStamped {

    /** Live map of player UUID to the incarnation that earned the entry. */
    Map<UUID, String> nixreaper$stamps();

    /** NBT key, shared by every implementor. */
    String KEY = "nixreaper:lifeStamps";
}
