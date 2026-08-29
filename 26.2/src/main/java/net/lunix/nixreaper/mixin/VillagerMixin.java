package net.lunix.nixreaper.mixin;

import net.lunix.nixreaper.DirectlyKnown;
import net.lunix.nixreaper.LifeStamped;
import net.lunix.nixreaper.Stamps;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Persists a villager's gossip stamps.
 *
 * <p>{@link net.minecraft.world.entity.ai.gossip.GossipContainer} is written through a fixed
 * {@code Codec}, so the stamps ride along in the villager's own save data instead -- the same
 * arrangement as the vault.
 */
@Mixin(Villager.class)
public abstract class VillagerMixin {

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void nixreaper$save(ValueOutput out, CallbackInfo ci) {
        Map<UUID, String> stamps = nixreaper$stamps();
        if (stamps != null && !stamps.isEmpty()) {
            out.putString(LifeStamped.KEY, Stamps.encode(stamps));
        }
        Set<UUID> direct = nixreaper$direct();
        if (direct != null && !direct.isEmpty()) {
            out.putString(DirectlyKnown.KEY, DirectlyKnown.encode(direct));
        }
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void nixreaper$load(ValueInput in, CallbackInfo ci) {
        Map<UUID, String> stamps = nixreaper$stamps();
        if (stamps != null) {
            Stamps.decode(in.getString(LifeStamped.KEY).orElse(null), stamps);
        }
        Set<UUID> direct = nixreaper$direct();
        if (direct != null) {
            DirectlyKnown.decode(in.getString(DirectlyKnown.KEY).orElse(null), direct);
        }
    }

    private Map<UUID, String> nixreaper$stamps() {
        Object gossips = ((Villager) (Object) this).getGossips();
        return gossips instanceof LifeStamped stamped ? stamped.nixreaper$stamps() : null;
    }

    private Set<UUID> nixreaper$direct() {
        Object gossips = ((Villager) (Object) this).getGossips();
        return gossips instanceof DirectlyKnown known ? known.nixreaper$direct() : null;
    }
}
