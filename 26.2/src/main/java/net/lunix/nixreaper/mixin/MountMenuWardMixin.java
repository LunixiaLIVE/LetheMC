package net.lunix.nixreaper.mixin;

import net.lunix.nixreaper.Pets;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractMountInventoryMenu;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Closes a chest animal's inventory screen the moment its owner dies.
 *
 * <p>Blocking interaction only stops someone <em>opening</em> the screen. Anyone who already
 * had a donkey's chest open when the owner died would keep it open and could carry on taking
 * items out for the whole grace period -- the exact loophole the ward exists to shut, with one
 * extra step of forethought.
 *
 * <p>No manual hunting for who has what open: {@code ServerPlayer} calls
 * {@code containerMenu.stillValid(player)} every tick and closes the screen itself when it
 * returns false. Answering the question honestly is enough, and vanilla does the rest -- which
 * is also why this cannot drift out of sync with the ward.
 */
@Mixin(AbstractMountInventoryMenu.class)
public class MountMenuWardMixin {

    @Shadow
    @Final
    protected LivingEntity mount;

    @Inject(method = "stillValid", at = @At("HEAD"), cancellable = true)
    private void nixreaper$closeWhenWarded(Player player, CallbackInfoReturnable<Boolean> cir) {
        if (Pets.isWarded(mount)) {
            cir.setReturnValue(false);
        }
    }
}
