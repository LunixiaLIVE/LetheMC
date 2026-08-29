package net.lunix.nixreaper.mixin;

import net.lunix.nixreaper.Pets;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes an animal unkillable while its owner is in Purgatory.
 *
 * <p>Blocking interaction alone is not enough. A chest animal drops its cargo when it dies, so
 * killing someone's donkey during the grace period spills exactly what emptying it by hand
 * would have -- and unlike opening a chest, anything can do the killing.
 *
 * <p>Hence <em>all</em> damage, not merely player attacks: a wandering zombie killing an
 * unattended donkey would scatter the same items, and would destroy something a resurrection
 * is supposed to hand back intact. An animal in limbo should not be able to die of bad luck
 * while its owner has no way to protect it.
 *
 * <p>{@code AbstractHorse} overrides {@code hurtServer}, so the horse family is warded in
 * {@link AbstractHorseMixin} at its own entry point rather than relying on this one.
 */
@Mixin(LivingEntity.class)
public class LivingEntityWardMixin {

    @Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
    private void nixreaper$wardDamage(ServerLevel level, DamageSource source, float amount,
                                      CallbackInfoReturnable<Boolean> cir) {
        // The hottest path this mod touches -- every damage event for every living entity.
        // isWarded's first line is an instanceof that rejects players, monsters and villagers
        // before anything else happens.
        if (Pets.isWarded((LivingEntity) (Object) this)) {
            cir.setReturnValue(false);
        }
    }
}
