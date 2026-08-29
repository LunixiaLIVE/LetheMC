package net.lunix.nixreaper.mixin;

import net.lunix.nixreaper.PendingStamps;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gives a block entity a moment to finish loading itself.
 *
 * <p>{@code setLevel} runs when a block entity is attached to the world -- after its constructor
 * and after {@code loadAdditional}, and before it ever ticks. That makes it the first safe point
 * at which a vault can move its parked stamps into its server data, which is still null during
 * the load itself.
 *
 * <p>On the base class because {@code setLevel} is inherited: Mixin cannot inject into an
 * inherited method through a subclass, and targeting the vault directly fails outright rather
 * than silently, which is how this was found.
 *
 * <p>Runs once per block entity per load, not per tick, and does nothing at all for the ones
 * that have no stamps to place.
 */
@Mixin(BlockEntity.class)
public class BlockEntityAttachMixin {

    @Inject(method = "setLevel", at = @At("TAIL"))
    private void nixreaper$flushPendingStamps(Level level, CallbackInfo ci) {
        if ((Object) this instanceof PendingStamps pending) {
            pending.nixreaper$flushStamps();
        }
    }
}
