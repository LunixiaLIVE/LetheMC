package net.lunix.lethemc.mixin;

import net.lunix.lethemc.Gear;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops anyone pocketing gear that belongs to a player in Purgatory.
 *
 * <p>The sweep alone is not enough here, because picking an item up is what would re-stamp it.
 * A friend who breaks the dead player's chest and grabs what falls out would be holding items
 * marked with a life that is still running, and could hand them back after the reincarnation --
 * the belongings would have survived a death that was meant to take them.
 *
 * <p>Instant on purpose. Everything else in this mod can afford to be a second late; this
 * cannot, because the window it covers is the moment the item touches somebody.
 */
@Mixin(ItemEntity.class)
public class ItemPickupWardMixin {

    @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
    private void lethemc$wardPickup(Player player, CallbackInfo ci) {
        // Server-authoritative only. The client runs this too and has no ledger, so letting it
        // decide would mean a warded item looked collectable right up until the server refused.
        if (!(player instanceof ServerPlayer)) return;
        if (Gear.isWardEnforced(((ItemEntity) (Object) this).getItem())) {
            ci.cancel();
        }
    }
}
