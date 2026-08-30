package net.lunix.lethemc.mixin;

import net.lunix.lethemc.Incarnations;
import net.lunix.lethemc.TrustStamped;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.fox.Fox;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Records which life each of a fox's trusted players was living.
 *
 * <p>Unlike pets and livestock, a fox is never destroyed by a reincarnation. Trust is not
 * ownership -- a fox that tolerates you is a wild animal, not a possession, so there is nothing
 * to take. It simply forgets you, which is the whole of the loss.
 *
 * <p>Stamps are kept per trusted player rather than per slot, because vanilla shifts entries
 * between the two slots as trust is granted; a positional record would attach the wrong life to
 * the wrong player the first time that happened.
 */
@Mixin(Fox.class)
public abstract class FoxMixin implements TrustStamped {

    @Unique
    private final Map<UUID, String> lethemc$trustStamps = new HashMap<>();

    @Override
    public Map<UUID, String> lethemc$trustStamps() {
        return lethemc$trustStamps;
    }

    /**
     * Only players are stamped. A fox can trust another entity in principle, and something
     * that has no incarnation should not be recorded as belonging to one.
     */
    @Inject(method = "addTrustedEntity(Lnet/minecraft/world/entity/EntityReference;)V", at = @At("TAIL"))
    private void lethemc$stampTrust(EntityReference<LivingEntity> ref, CallbackInfo ci) {
        if (ref == null) return;
        UUID id = ref.getUUID();
        if (id == null) return;
        LivingEntity resolved = ref.getEntity(
                ((Fox) (Object) this).level(), LivingEntity.class);
        if (!(resolved instanceof Player)) return;
        lethemc$trustStamps.put(id, Incarnations.of(id));
    }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void lethemc$save(ValueOutput out, CallbackInfo ci) {
        if (!lethemc$trustStamps.isEmpty()) {
            out.putString(TrustStamped.KEY, TrustStamped.encode(lethemc$trustStamps));
        }
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void lethemc$load(ValueInput in, CallbackInfo ci) {
        TrustStamped.decode(in.getString(TrustStamped.KEY).orElse(null), lethemc$trustStamps);
    }
}
