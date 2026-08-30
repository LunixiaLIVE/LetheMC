package net.lunix.lethemc.mixin;

import net.lunix.lethemc.Pets;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Puts an animal out of reach while its owner is in Purgatory.
 *
 * <p>Closes the last way to carry belongings through a death. During the grace period the
 * owner is offline and their animals are still theirs -- so without this, anyone could open
 * their chest donkey and empty it, or simply ride it away, and the contents would survive a
 * death that was supposed to take everything.
 *
 * <p>Applies to everyone, including the owner. They are locked out and cannot interact anyway,
 * and a rule with no exceptions is easier to reason about than one with a special case that
 * can never fire.
 *
 * <p>{@code Mob.interact} is the public entry point that dispatches to {@code mobInteract};
 * hooking it rather than the protected method means the block still applies to horses, which
 * override the latter to open their inventory screen.
 */
@Mixin(Mob.class)
public class MobInteractWardMixin {

    @Inject(method = "interact", at = @At("HEAD"), cancellable = true)
    private void lethemc$wardInteraction(Player player, InteractionHand hand, Vec3 pos,
                                           CallbackInfoReturnable<InteractionResult> cir) {
        // Runs for every mob interaction on the server, so the cheap negative case matters:
        // isWarded rejects anything that is neither ownable nor a fox in two instanceof checks.
        if (Pets.isWarded((Mob) (Object) this)) {
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }
}
