package net.lunix.lethemc.mixin;

import net.lunix.lethemc.Gear;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Freezes a dead player's gear where it lies for the length of the grace period.
 *
 * <p>Their chests are still full and they are offline and unable to object, so without this
 * anyone could walk in, empty the lot, and hold it until the reincarnation had passed. It is
 * the chest-donkey loophole again, worked through a chest instead of an animal.
 *
 * <p><b>{@code mayPickup} rather than the click handler.</b> Vanilla routes every way of moving
 * an item -- a plain click, shift-click, dragging across slots, swapping to a hotbar key, double
 * clicking to gather a stack -- through this one question. Answering it once covers all of them,
 * and covers container types this mod has never heard of. Hooking {@code doClick} instead would
 * mean matching on click type and getting one wrong.
 *
 * <p>Hoppers are left alone deliberately: they move items without a player, so nothing gets
 * re-stamped and nothing is laundered. The item stays marked, and is destroyed wherever it
 * ended up once the grace period runs out.
 */
@Mixin(Slot.class)
public class SlotWardMixin {

    @Inject(method = "mayPickup", at = @At("HEAD"), cancellable = true)
    private void lethemc$wardSlot(Player player, CallbackInfoReturnable<Boolean> cir) {
        if (!(player instanceof ServerPlayer)) return;
        if (Gear.isWardEnforced(((Slot) (Object) this).getItem())) {
            cir.setReturnValue(false);
        }
    }
}
