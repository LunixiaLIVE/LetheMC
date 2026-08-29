package net.lunix.nixreaper.mixin;

import net.lunix.nixreaper.Config;
import net.lunix.nixreaper.LifeStamped;
import net.lunix.nixreaper.Stamps;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.vault.VaultServerData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A trial vault forgets that a past life already looted it.
 *
 * <p>{@code rewardedPlayers} is a bare {@code Set<UUID>} living on the vault out in the world,
 * so it outlives everything a purge deletes. This is the only entry on the survivors list that
 * costs the player something rather than granting it: a reincarnated player walks up to a vault
 * a previous life emptied and is silently refused, with nothing on screen to explain why. That
 * makes it the sharpest of the lot, and the first to fix.
 *
 * <p><b>Checked on access, not swept.</b> Unlike tamed animals there is nothing to destroy and
 * no exploit window to close, so the question only has to be answered when someone actually
 * presents a key. That removes the need to walk loaded block entities on a timer.
 *
 * <p>Vanilla caps the set at 128 and evicts an arbitrary element -- {@code iterator().next()},
 * so HashSet order, not the oldest entry. A player's record can therefore vanish by luck on a
 * busy server. The stamp map is cleaned in step with the set so the two cannot drift.
 */
@Mixin(VaultServerData.class)
public abstract class VaultServerDataMixin implements LifeStamped {

    @Shadow @Final private Set<UUID> rewardedPlayers;

    @Shadow private void markChanged() { throw new AssertionError("mixin shadow not applied"); }

    @Unique
    private final Map<UUID, String> nixreaper$stamps = new HashMap<>();

    @Override
    public Map<UUID, String> nixreaper$stamps() {
        return nixreaper$stamps;
    }

    /**
     * Records which life looted this vault.
     *
     * <p>Runs at TAIL so the stamp is only written if vanilla accepted the reward. Re-looting in
     * a later life re-stamps: {@code Set.add} is a no-op for a UUID already present, but the
     * stamp must move forward or the vault would free itself again on the next death.
     */
    @Inject(method = "addToRewardedPlayers", at = @At("TAIL"))
    private void nixreaper$stampReward(Player player, CallbackInfo ci) {
        if (!Config.get().wipeVaultRewards) return;
        Stamps.mark(player.getUUID(), nixreaper$stamps);
    }

    /**
     * Drops every entry belonging to a life that has ended.
     *
     * <p><b>The purge happens in the set itself rather than in an answer</b>, because the vault
     * asks the question two different ways and only one of them can be intercepted usefully:
     *
     * <ul>
     *   <li>{@code tryInsertKey} calls {@code hasRewardedPlayer}, which reads the field directly.</li>
     *   <li>{@code VaultSharedData.updateConnectedPlayersWithinRange} calls
     *       {@code getRewardedPlayers().contains(uuid)} to decide who counts as a nearby player
     *       at all.</li>
     * </ul>
     *
     * <p>The second one is what actually matters, and it runs first. A rewarded player is not
     * "connected", so the vault stays {@code inactive}, and {@code tryInsertKey} bails at its
     * very first check -- {@code canEjectReward(config, state)} -- long before the reward check
     * is reached. Overriding only the answer to {@code hasRewardedPlayer} therefore changed
     * nothing observable: found by testing, not by reading.
     *
     * <p>Removing the UUID outright also keeps vanilla's 128-entry cap honest. Dead entries left
     * in place would push live ones out, letting someone who really did loot a vault back in.
     */
    @Unique
    private void nixreaper$dropEndedLives() {
        if (!Config.get().wipeVaultRewards) return;
        if (nixreaper$stamps.isEmpty()) return;

        boolean changed = false;
        Iterator<UUID> it = rewardedPlayers.iterator();
        while (it.hasNext()) {
            UUID id = it.next();
            if (Stamps.isStale(id, nixreaper$stamps)) {
                it.remove();
                nixreaper$stamps.remove(id);
                changed = true;
            }
        }
        // Without this the vault reopens now but is rewarded again after a restart.
        if (changed) markChanged();
    }

    /** The path the state machine uses -- the one that decides whether the vault reactivates. */
    @Inject(method = "getRewardedPlayers", at = @At("HEAD"))
    private void nixreaper$purgeBeforeRead(CallbackInfoReturnable<Set<UUID>> cir) {
        nixreaper$dropEndedLives();
    }

    /** The path the key insert uses. Reads the field directly, so the set must already be clean. */
    @Inject(method = "hasRewardedPlayer", at = @At("HEAD"))
    private void nixreaper$purgeBeforeCheck(Player player, CallbackInfoReturnable<Boolean> cir) {
        nixreaper$dropEndedLives();
    }
}
