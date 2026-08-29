package net.lunix.nixreaper.mixin;

import net.lunix.nixreaper.Config;
import net.lunix.nixreaper.DirectlyKnown;
import net.lunix.nixreaper.LifeStamped;
import net.lunix.nixreaper.Stamps;
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
    private final Map<UUID, String> nixreaper$stamps = new HashMap<>();

    @Unique
    private final Set<UUID> nixreaper$direct = new HashSet<>();

    @Override
    public Map<UUID, String> nixreaper$stamps() {
        return nixreaper$stamps;
    }

    @Override
    public Set<UUID> nixreaper$direct() {
        return nixreaper$direct;
    }

    @Shadow public abstract void remove(UUID player, GossipType type);

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
    private void nixreaper$stampGossip(UUID player, GossipType type, int amount, CallbackInfo ci) {
        if (!Config.get().wipeVillagerReputation) return;

        if (Stamps.isStale(player, nixreaper$stamps)) {
            for (GossipType t : GossipType.values()) {
                remove(player, t);
            }
        }
        Stamps.mark(player, nixreaper$stamps);

        // Only reached on a real encounter. Gossip arriving from another villager merges
        // straight into the entry map without ever calling this method, which is precisely
        // what keeps hearsay from marking a villager for destruction.
        nixreaper$direct.add(player);
    }

    /** A past life's reputation reads as nothing at all. */
    @Inject(method = "getReputation", at = @At("HEAD"), cancellable = true)
    private void nixreaper$ignorePastLives(UUID player, Predicate<GossipType> filter,
                                           CallbackInfoReturnable<Integer> cir) {
        if (!Config.get().wipeVillagerReputation) return;
        if (Stamps.isStale(player, nixreaper$stamps)) {
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
    private void nixreaper$carryStamps(GossipContainer other, RandomSource random, int count,
                                       CallbackInfo ci) {
        if (!(other instanceof LifeStamped source)) return;
        for (Map.Entry<UUID, String> e : source.nixreaper$stamps().entrySet()) {
            nixreaper$stamps.putIfAbsent(e.getKey(), e.getValue());
        }
    }
}
