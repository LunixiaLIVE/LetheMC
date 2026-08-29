package net.lunix.nixreaper;

import net.lunix.nixreaper.mixin.FoxAccessor;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.animal.fox.Fox;
import net.minecraft.world.entity.npc.villager.Villager;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * <p><b>Foxes follow a different rule</b> -- see {@code checkFox}. Trust is shared and not
 * ownership, so a fox is destroyed only when no living player trusts it any more.
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
        if (!(entity instanceof OwnableEntity ownable)) {
            // Foxes are not ownable, and are warded only when the whole fox is doomed.
            return entity instanceof Fox fox && isFoxWarded(fox);
        }
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

    /** A trust slot that is empty, that dooms the fox, or that keeps it safe. */
    private static final int SLOT_EMPTY = 0, SLOT_DOOMED = 1, SLOT_SAFE = -1;

    /**
     * True while every player a fox trusts is in Purgatory with their remains intact.
     *
     * <p>The ward protects what is about to be destroyed. A fox whose only trust belongs to a
     * dead player is discarded when their remains are, so without this anyone could kill it
     * during the grace period and pocket whatever it was carrying -- salvaging an item the
     * death was meant to take. It is the chest-donkey loophole in miniature.
     *
     * <p>A fox that any living player still trusts is left alone completely, even while one of
     * its two trustees is in Purgatory. It survives the reincarnation, so there is nothing to
     * protect, and freezing it would penalise a co-owner who did not die.
     *
     * <p><b>This asks the ledger, not the stamps.</b> During the grace period the dead player's
     * incarnation has not rotated yet -- that happens only when the remains are destroyed -- so
     * nothing is stale during the very window the ward covers. Testing staleness here would
     * produce a ward that never once fired.
     */
    private static boolean isFoxWarded(Fox fox) {
        if (!Config.get().wipeFoxes) return false;
        if (!(fox instanceof TrustStamped stamped)) return false;
        Map<UUID, String> stamps = stamped.nixreaper$trustStamps();
        if (stamps.isEmpty()) return false;

        int s0 = slotState(fox, FoxAccessor.nixreaper$trusted0(), stamps);
        int s1 = slotState(fox, FoxAccessor.nixreaper$trusted1(), stamps);

        if (s0 == SLOT_SAFE || s1 == SLOT_SAFE) return false;
        return s0 == SLOT_DOOMED || s1 == SLOT_DOOMED;
    }

    private static int slotState(Fox fox,
                                 EntityDataAccessor<Optional<EntityReference<LivingEntity>>> slot,
                                 Map<UUID, String> stamps) {
        Optional<EntityReference<LivingEntity>> held = fox.getEntityData().get(slot);
        if (held.isEmpty()) return SLOT_EMPTY;

        UUID trusted = held.get().getUUID();
        // Unreadable or unstamped trust is trust this mod will never act on, so the fox is not
        // headed for destruction and has nothing to be protected from.
        if (trusted == null || stamps.get(trusted) == null) return SLOT_SAFE;

        Ledger.Entry e = Ledger.get(trusted);
        if (e == null || Ledger.STATE_PARDONED.equals(e.state)) return SLOT_SAFE;
        return SLOT_DOOMED;
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
     * A fox forgets the players whose lives have ended, and is destroyed if that leaves nobody.
     *
     * <p>Trust is shared, so a fox cannot simply be treated as one player's property. Each slot
     * is cleared independently: one trustee dying must not cost the other anything, and vanilla's
     * own {@code clearTrusted} empties both at once, which is exactly the behaviour to avoid.
     *
     * <p>But a fox that no living player trusts is not a wild animal that merely tolerated
     * someone -- it is a kept animal whose keeper no longer exists. Left alive it would be a farm
     * that survives death, the same loophole as a chest donkey parked somewhere safe. So once the
     * last surviving trust is gone the fox is destroyed, along with whatever it was carrying.
     *
     * <p>A trust with no stamp -- granted before this feature existed -- counts as surviving.
     * Acting on what we cannot date would cull foxes the mod never saw being trusted.
     */
    private static void checkFox(Fox fox) {
        if (!Config.get().wipeFoxes) return;
        if (!(fox instanceof TrustStamped stamped)) return;
        Map<UUID, String> stamps = stamped.nixreaper$trustStamps();
        if (stamps.isEmpty()) return;

        EntityDataAccessor<Optional<EntityReference<LivingEntity>>> slot0 = FoxAccessor.nixreaper$trusted0();
        EntityDataAccessor<Optional<EntityReference<LivingEntity>>> slot1 = FoxAccessor.nixreaper$trusted1();

        boolean held0 = fox.getEntityData().get(slot0).isPresent();
        boolean held1 = fox.getEntityData().get(slot1).isPresent();
        boolean stale0 = isStale(fox, slot0, stamps);
        boolean stale1 = isStale(fox, slot1, stamps);

        if (!stale0 && !stale1) return;

        // Someone whose life is still running trusts this fox, so it is still somebody's.
        if ((held0 && !stale0) || (held1 && !stale1)) {
            if (stale0) forget(fox, slot0, stamps);
            if (stale1) forget(fox, slot1, stamps);
            return;
        }

        // Nobody living trusts it. discard() takes the fox and anything in its mouth together,
        // with no drops -- an item on the ground would just be picked back up.
        NixReaper.LOGGER.info("Removing fox at {} -- no living player trusts it",
                fox.blockPosition().toShortString());
        fox.discard();
    }

    /** Whether this slot holds a player whose life has since ended. */
    private static boolean isStale(Fox fox,
                                   EntityDataAccessor<Optional<EntityReference<LivingEntity>>> slot,
                                   Map<UUID, String> stamps) {
        Optional<EntityReference<LivingEntity>> held = fox.getEntityData().get(slot);
        if (held.isEmpty()) return false;

        UUID trusted = held.get().getUUID();
        if (trusted == null) return false;

        String stamp = stamps.get(trusted);
        if (stamp == null) return false; // trusted before the feature existed

        String living = Incarnations.peek(trusted);
        if (living == null) return false; // a player we have never seen
        return !living.equals(stamp);
    }

    private static void forget(Fox fox,
                               EntityDataAccessor<Optional<EntityReference<LivingEntity>>> slot,
                               Map<UUID, String> stamps) {
        Optional<EntityReference<LivingEntity>> held = fox.getEntityData().get(slot);
        if (held.isEmpty()) return;
        UUID trusted = held.get().getUUID();

        fox.getEntityData().set(slot, Optional.empty());
        if (trusted != null) stamps.remove(trusted);
        NixReaper.LOGGER.info("Fox at {} forgot a player whose life has ended",
                fox.blockPosition().toShortString());
    }

    /**
     * A villager whose only customers are dead is destroyed.
     *
     * <p>The case this exists for is a trading hall far from anywhere: one player cured, bred
     * and levelled every villager in it, and nobody else has ever opened a trade. Those
     * villagers are that player's work as surely as a chest donkey is their stash, and letting
     * them stand through a death leaves the hall waiting, fully levelled, for the next life.
     *
     * <p><b>Only villagers dealt with face to face are eligible</b> -- see {@link DirectlyKnown}.
     * Gossip spreads on its own, so villagers hear about players they never met; destroying on
     * reputation alone would ripple outwards through a village as the news travelled and take
     * bystanders with it. One that was merely bred and left alone has dealt with nobody, so
     * nothing here can touch it.
     *
     * <p>Any customer still living spares the villager entirely, exactly as a fox is spared by a
     * surviving trustee. A shared village keeps working for the people still alive to use it.
     */
    private static void checkVillager(Villager villager) {
        if (!Config.get().wipeVillagers) return;
        Object gossips = villager.getGossips();
        if (!(gossips instanceof DirectlyKnown known) || !(gossips instanceof LifeStamped stamped)) return;

        Set<UUID> direct = known.nixreaper$direct();
        if (direct.isEmpty()) return; // never dealt with anyone -- bred and left alone

        Map<UUID, String> stamps = stamped.nixreaper$stamps();
        boolean anyEnded = false;
        for (UUID customer : direct) {
            // No stamp means the dealing predates this feature. Never act on what we cannot date.
            if (stamps.get(customer) == null) return;
            if (Stamps.isStale(customer, stamps)) {
                anyEnded = true;
            } else {
                return; // somebody living still trades here
            }
        }
        if (!anyEnded) return;

        NixReaper.LOGGER.info("Removing villager at {} -- everyone who traded here is gone",
                villager.blockPosition().toShortString());
        villager.discard();
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
        if (!c.wipePets && !c.wipeLivestock && !c.wipeFoxes && !c.wipeVillagers) return;
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity e : level.getAllEntities()) {
                if (e instanceof Fox fox) {
                    checkFox(fox);
                    continue;
                }
                if (e instanceof Villager villager) {
                    checkVillager(villager);
                    continue;
                }
                if (isWarded(e)) ejectRiders(e);
                check(e);
            }
        }
    }
}
