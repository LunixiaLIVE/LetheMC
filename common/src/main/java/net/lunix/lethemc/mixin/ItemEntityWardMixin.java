package net.lunix.lethemc.mixin;

import net.lunix.lethemc.Gear;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Protects a dead player's gear once it is lying on the ground.
 *
 * <p>Gear does not stay in a chest just because the chest is where it was left. Anyone can
 * break the container during the grace period, and then everything inside is a loose item --
 * outside every slot the {@link SlotWardMixin} guards, and no longer covered by anything.
 *
 * <p>So the ward follows the item rather than the container. Two things have to be stopped:
 *
 * <ul>
 *   <li><b>Picking it up.</b> Otherwise a friend breaks the chest, collects the lot, and hands
 *       it back after the reincarnation -- and because collecting an item is exactly what
 *       re-stamps it, it would come back marked with a life that is still running. That is the
 *       whole laundering route, reopened by one pickaxe.</li>
 *   <li><b>Destroying it.</b> A loose item burns in fire, boils away in lava and is blown up by
 *       creepers like anything else. Blocking only pickup would leave a griefer able to break
 *       someone's chest and torch the contents -- unable to steal the gear, but perfectly able
 *       to erase what a resurrection is supposed to hand back intact.</li>
 * </ul>
 *
 * <p>All damage, not merely a player's, for the same reason the animal ward takes that line: a
 * stray creeper should not be able to cost someone their belongings while they have no way to
 * be there and defend them. The sweep separately keeps warded items from despawning, so the
 * three ways an item can be lost -- taken, destroyed, timed out -- are all covered for exactly
 * as long as the remains are restorable.
 */
@Mixin(ItemEntity.class)
public class ItemEntityWardMixin {

    @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
    private void lethemc$wardPickup(Player player, CallbackInfo ci) {
        // Server-authoritative only. The client runs this too and has no ledger, so letting it
        // decide would mean a warded item looked collectable right up until the server refused.
        if (!(player instanceof net.minecraft.server.level.ServerPlayer)) return;
        if (Gear.isWardEnforced(((ItemEntity) (Object) this).getItem())) {
            ci.cancel();
        }
    }

    @Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
    private void lethemc$wardDamage(ServerLevel level, DamageSource source, float amount,
                                    CallbackInfoReturnable<Boolean> cir) {
        if (Gear.isWardEnforced(((ItemEntity) (Object) this).getItem())) {
            cir.setReturnValue(false);
        }
    }
}
