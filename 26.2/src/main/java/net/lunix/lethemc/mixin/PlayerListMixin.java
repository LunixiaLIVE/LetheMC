package net.lunix.lethemc.mixin;

import net.lunix.lethemc.LetheMC;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.SocketAddress;

/**
 * The join gate, and the moment a dead player's files become safe to take.
 */
@Mixin(PlayerList.class)
public class PlayerListMixin {

    /**
     * <p>This is the same seam vanilla uses for its own ban and whitelist checks -- it either
     * returns a disconnect reason or null to admit -- which makes it the natural place to
     * reject a locked-out player. Computing the countdown here rather than storing it means
     * the number is correct on every single attempt.
     */
    @Inject(method = "canPlayerLogin", at = @At("HEAD"), cancellable = true)
    private void lethemc$checkLockout(SocketAddress address, NameAndId profile,
                                        CallbackInfoReturnable<Component> cir) {
        Component reason = LetheMC.checkLogin(profile.id(), profile.name());
        if (reason != null) {
            cir.setReturnValue(reason);
        }
    }

    /**
     * Entombment point.
     *
     * <p>{@code remove} calls {@code save(player)} near the top, which runs
     * {@code PlayerDataStorage.save} then {@code ServerStatsCounter.save} then
     * {@code PlayerAdvancements.save}. All three are synchronous in 26.2 -- plain
     * {@code NbtIo.writeCompressed} / {@code Files.newBufferedWriter}, no IO pool and no
     * future -- so at TAIL every one of the player's files exists in its final state, and
     * the player is already out of the list so nothing will rewrite them.
     *
     * <p>That ordering is what lets the graveyard move happen at once instead of behind a
     * timer. The old design waited five minutes purely to be sure this had finished; this
     * hook knows it has.
     */
    @Inject(method = "remove", at = @At("TAIL"))
    private void lethemc$entombOnRemove(ServerPlayer player, CallbackInfo ci) {
        LetheMC.onPlayerRemoved(player);
    }
}
