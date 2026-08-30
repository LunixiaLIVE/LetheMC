package net.lunix.lethemc.mixin;

import net.lunix.lethemc.LetheMC;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Brackets a player's death.
 *
 * <p>This was two Fabric API events, {@code ALLOW_DEATH} and {@code AFTER_DEATH}. NeoForge has a
 * clean equivalent for the first and nothing that fits the second, so porting them would have
 * meant a shim per loader for the single most important hook in the mod.
 *
 * <p>A mixin on {@code die} is what both events were wrapping anyway, and it behaves identically
 * on Fabric and NeoForge because it targets vanilla. So the abstraction was removed rather than
 * duplicated -- one fewer moving part, and one fewer place for the two loaders to disagree.
 *
 * <p>The head marks the player as dying, which is what makes {@code keepInventory} read true for
 * the length of the death and stops anything dropping. The tail runs once vanilla is finished,
 * which is where the entombment can safely begin.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerDeathMixin {

    @Inject(method = "die", at = @At("HEAD"))
    private void lethemc$deathBegin(DamageSource source, CallbackInfo ci) {
        LetheMC.onDeathBegin((ServerPlayer) (Object) this);
    }

    @Inject(method = "die", at = @At("TAIL"))
    private void lethemc$deathEnd(DamageSource source, CallbackInfo ci) {
        LetheMC.onDeathEnd((ServerPlayer) (Object) this);
    }
}
