package net.lunix.nixreaper;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.equine.AbstractHorse;

import java.util.UUID;

/**
 * Destroys animals belonging to a life their owner no longer lives.
 *
 * <p>Taming stamps the animal with the owner's current incarnation ID (see {@link
 * Incarnations}). A sweep compares that stamp against the life the owner is living now, and
 * anything stale is removed. This is what separates "tamed before you died" from "tamed ten
 * minutes ago" -- a distinction the owner's UUID cannot make, because it survives death.
 *
 * <h2>Why destroyed rather than released</h2>
 * Releasing leaves the animal standing there, wild and re-tameable, which for a wolf is barely
 * a penalty -- one bone and you have it back. Worse, a released chest animal is a loophole: park
 * a loaded donkey at spawn, die, walk back, and nothing of the old life was really lost.
 * Dropping the cargo instead has the same hole with extra steps, since anything on the ground
 * can be picked up.
 *
 * <p>So the animal and everything on it are destroyed together. No stripping of saddles, no
 * emptying of chests, no items on the floor. It also means this class never has to reach into
 * a horse's inventory, which removes a whole accessor mixin.
 *
 * <p><b>Foxes are deliberately excluded</b> -- see {@code checkFox}. A fox that trusts you was
 * never yours to lose.
 */
public final class Pets {

    private Pets() {}

    /** Whether this entity is one the config says to reclaim. */
    private static boolean handled(Entity e) {
        Config c = Config.get();
        if (e instanceof TamableAnimal) return c.wipePets;
        if (e instanceof AbstractHorse) return c.wipeLivestock;
        return false;
    }

    /**
     * True while this animal's owner is in Purgatory with their remains still intact.
     *
     * <p>Such an animal is in limbo alongside them: it cannot be interacted with, ridden,
     * opened, or harmed by anything. The window is exactly the grace period.
     *
     * <p>Without this there is one loophole left. A chest animal is still its owner's during
     * the grace period, and its owner is offline and unable to defend it -- so a friend could
     * simply open the donkey and empty it, or kill it and collect what drops. Either way the
     * belongings survive a death that was supposed to take them.
     *
     * <p>Blocking damage matters as much as blocking interaction, and not only against
     * players: a wandering zombie killing an unattended donkey would spill the same cargo, and
     * would destroy something a resurrection is meant to give back intact.
     */
    public static boolean isWarded(Entity entity) {
        if (!(entity instanceof OwnableEntity ownable)) return false;
        EntityReference<LivingEntity> ref = ownable.getOwnerReference();
        if (ref == null) return false;
        UUID ownerId = ref.getUUID();
        if (ownerId == null) return false;

        Ledger.Entry e = Ledger.get(ownerId);
        if (e == null) return false;

        // From the instant of death, not from entombment. Keying this on wipePending left the
        // animals open for the fifteen seconds of the death screen -- a small window, but the
        // owner is already beyond defending them, and "you had to be quick" is not a property
        // worth designing in.
        //
        // PARDONED means resurrected and on their way back, so the ward lifts immediately.
        // After the remains are destroyed the animal is deleted within a tick anyway, so
        // warding it in the meantime costs nothing.
        return !Ledger.STATE_PARDONED.equals(e.state);
    }

    /**
     * Throws riders off warded animals.
     *
     * <p>The interaction block only stops someone <em>starting</em> to ride. Anyone already
     * mounted when their owner died would stay mounted and could simply ride the animal away
     * for the whole grace period. Called from the sweep, so it lands within a second of death.
     */
    private static void ejectRiders(Entity entity) {
        if (!entity.getPassengers().isEmpty()) {
            entity.ejectPassengers();
        }
    }

    /** Stamps a freshly tamed animal with the taming player's current life. */
    public static void stamp(Entity animal, LivingEntity owner) {
        if (owner == null || !(animal instanceof Incarnated stamped)) return;
        stamped.nixreaper$setIncarnation(Incarnations.of(owner.getUUID()));
    }

    /**
     * Removes one animal if its stamp belongs to a life that has ended.
     *
     * <p>Cheap in the common case: a type check, a null check and a string comparison.
     */
    public static void check(Entity entity) {
        if (!handled(entity)) return;
        if (!(entity instanceof Incarnated stamped)) return;
        if (!(entity instanceof OwnableEntity ownable)) return;

        String stamp = stamped.nixreaper$getIncarnation();
        // No stamp means it was tamed before this feature existed. Acting on that would
        // delete every pet on the server the first time the sweep ran.
        if (stamp == null) return;

        EntityReference<LivingEntity> ref = ownable.getOwnerReference();
        if (ref == null) return;

        // Reads the UUID without resolving the entity, so this works while the owner is
        // offline, in another dimension, or has no playerdata at all after a purge.
        UUID ownerId = ref.getUUID();
        if (ownerId == null) return;

        String living = Incarnations.peek(ownerId);
        // null = a player we have never seen. Never act on unknown: otherwise every animal
        // belonging to someone who has not logged in since the feature landed would die the
        // moment its chunk loaded.
        if (living == null || living.equals(stamp)) return;

        NixReaper.LOGGER.info("Removing {} at {} -- its owner is living a different life",
                entity.getType().getDescriptionId(), entity.blockPosition().toShortString());
        entity.discard();
    }

    /**
     * The periodic sweep.
     *
     * <p>Runs often on purpose. The loophole it closes is short-range -- park a loaded animal
     * near spawn, die, and collect it on the walk back -- so a slow sweep would leave a window
     * in which exactly that works.
     */
    public static void sweep(MinecraftServer server) {
        Config c = Config.get();
        if (!c.wipePets && !c.wipeLivestock) return;
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity e : level.getAllEntities()) {
                if (isWarded(e)) ejectRiders(e);
                check(e);
            }
        }
    }
}
