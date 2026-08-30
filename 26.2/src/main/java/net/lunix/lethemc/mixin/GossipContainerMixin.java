package net.lunix.lethemc.mixin;

import net.lunix.lethemc.Config;
import net.lunix.lethemc.DirectlyKnown;
import net.lunix.lethemc.LifeStamped;
import net.lunix.lethemc.Stamps;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.gossip.GossipContainer;
import net.minecraft.world.entity.ai.gossip.GossipType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * A villager forgets what it thought of a life that has ended.
 *
 * <p>Reputation is keyed by player UUID on each villager, out in the world, so it survives a
 * purge. A reincarnated player would otherwise keep the trade discounts of a past life -- the
 * mirror image of the vault problem, a benefit wrongly retained rather than a penalty.
 *
 * <p><b>Answered on read, not swept.</b> {@code getReputation} is the single path everything
 * else goes through, and it costs one map lookup. Sweeping villagers instead would mean calling
 * {@code getGossipEntries} on every villager every sweep, which builds a fresh map each time.
 *
 * <p>Reputation is not cleared destructively on read: the gossip entry is left alone and simply
 * reported as zero. Vanilla's own decay then retires it in the normal way. Deleting entries
 * mid-read would mutate a collection something else may be iterating.
 */
@Mixin(GossipContainer.class)
public abstract class GossipContainerMixin implements LifeStamped, DirectlyKnown {

    @Unique
    private final Map<UUID, String> lethemc$stamps = new HashMap<>();

    @Unique
    private final Set<UUID> lethemc$direct = new HashSet<>();

    @Override
    public Map<UUID, String> lethemc$stamps() {
        return lethemc$stamps;
    }

    @Override
    public Set<UUID> lethemc$direct() {
        return lethemc$direct;
    }

    @Shadow public abstract void remove(UUID player, GossipType type);

    @Shadow public abstract java.util.Map<UUID, it.unimi.dsi.fastutil.objects.Object2IntMap<GossipType>> getGossipEntries();

    /**
     * Any opinion formed about a player is dated to the life they are living now -- and a life
     * that has ended has its opinions dropped first.
     *
     * <p>That second half is not tidiness, it closes a laundering hole. Reputation is reported
     * as zero while the stamp is stale, but the entry itself is left in place for vanilla's
     * decay to retire. Re-dating it on the next trade would hand the whole of a past life's
     * standing back: one wheat sold, and twenty-three points earned before dying count again.
     *
     * <p>So the old entries go before the new one is recorded. Runs at HEAD, before vanilla
     * adds anything, so the value being added now is never caught by the sweep.
     */
    @Inject(method = "add(Ljava/util/UUID;Lnet/minecraft/world/entity/ai/gossip/GossipType;I)V",
            at = @At("HEAD"))
    private void lethemc$stampGossip(UUID player, GossipType type, int amount, CallbackInfo ci) {
        if (!Config.get().wipeVillagerReputation) return;

        if (Stamps.isStale(player, lethemc$stamps)) {
            for (GossipType t : GossipType.values()) {
                remove(player, t);
            }
        }
        // Every type is stamped, negatives included: a reincarnated player returns as no one,
        // so a villager should no more remember a grudge from an ended life than a discount.
        Stamps.mark(player, lethemc$stamps);

        // But only what a player BUILT counts as being their customer. Vanilla routes all five
        // reputation events through here -- trading and curing, but also hurting a villager,
        // killing one, and killing a golem. Counting the hostile ones would invert the feature:
        // a stranger who punched a villager in someone else's village and later died would take
        // that villager with them, having never traded with it once.
        //
        // Gossip arriving from another villager never reaches this method at all -- transferFrom
        // merges straight into the entry map -- so hearsay cannot mark a villager either.
        if (type == GossipType.TRADING
                || type == GossipType.MAJOR_POSITIVE
                || type == GossipType.MINOR_POSITIVE) {
            lethemc$direct.add(player);
        }
    }

    /** A past life's reputation reads as nothing at all. */
    @Inject(method = "getReputation", at = @At("HEAD"), cancellable = true)
    private void lethemc$ignorePastLives(UUID player, Predicate<GossipType> filter,
                                           CallbackInfoReturnable<Integer> cir) {
        if (!Config.get().wipeVillagerReputation) return;
        if (Stamps.isStale(player, lethemc$stamps)) {
            cir.setReturnValue(0);
        }
    }

    /**
     * Stamps travel with the gossip they describe.
     *
     * <p>Villagers spread opinions to each other. Without this the receiving villager would hold
     * an entry it has no stamp for, which counts as "recorded before the feature existed" and is
     * therefore honoured -- letting a dead life's reputation launder itself across the village
     * one conversation at a time.
     */
    @Inject(method = "transferFrom", at = @At("TAIL"))
    private void lethemc$carryStamps(GossipContainer other, RandomSource random, int count,
                                       CallbackInfo ci) {
        if (!(other instanceof LifeStamped source)) return;

        // Only for players this villager now actually holds an opinion about. Copying the
        // source's whole map instead left villagers carrying stamps for players whose gossip
        // never arrived, so every villager in a village slowly collected one entry per player.
        Map<UUID, String> theirs = source.lethemc$stamps();
        for (UUID heard : getGossipEntries().keySet()) {
            String stamp = theirs.get(heard);
            if (stamp != null) lethemc$stamps.putIfAbsent(heard, stamp);
        }
    }
}
