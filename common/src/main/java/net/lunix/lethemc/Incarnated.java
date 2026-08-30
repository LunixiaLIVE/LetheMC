package net.lunix.lethemc;

/**
 * Something that remembers which life it belongs to.
 *
 * <p>Implemented by mixin on players (their current incarnation) and on ownable mobs (the
 * incarnation that claimed them). A mob is still yours only while the two match.
 *
 * <p>Why an ID rather than a flag on the player: a player's UUID does not change when they are
 * reincarnated, so "has this player been reincarnated?" cannot distinguish a pet tamed in a
 * previous life from one tamed five minutes ago in the current one. The pet has to carry its
 * own provenance. Persisted on both sides, so it survives restarts and works on mobs sitting
 * in chunks that have not been loaded for weeks.
 */
public interface Incarnated {

    /** The incarnation this belongs to, or null if never stamped. */
    String lethemc$getIncarnation();

    void lethemc$setIncarnation(String id);

    /** NBT key used on both players and mobs. */
    String KEY = "lethemc:incarnation";

    /**
     * Foxes trust up to two entities independently, so one stamp per mob does not fit -- each
     * trust slot needs its own, and the sweep clears them individually.
     */
    interface Trust {
        String lethemc$getTrustIncarnation(int slot);

        void lethemc$setTrustIncarnation(int slot, String id);

        String KEY_PREFIX = "lethemc:trust";
    }
}
