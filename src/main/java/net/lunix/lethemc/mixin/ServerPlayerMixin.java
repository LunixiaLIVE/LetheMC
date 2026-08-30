package net.lunix.lethemc.mixin;

import net.lunix.lethemc.LetheMC;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Rewrites the one line of the death screen a server is allowed to control.
 *
 * <p>The screen has three pieces of text and two buttons. "Game Over!", "Score:", "Respawn"
 * and "Title Screen" are all client-side translation keys ({@code deathScreen.title},
 * {@code deathScreen.score.value}, ...) baked into the jar, and a server-side mod never gets
 * a say in them. The score's value is an int the client stringifies itself, so no text can be
 * smuggled through it either.
 *
 * <p>What IS ours is the death message: {@code ServerPlayer.die} builds a
 * {@code ClientboundPlayerCombatKillPacket} around {@code CombatTracker.getDeathMessage()},
 * and the client renders that Component verbatim. This modifies that argument on its way into
 * the packet.
 *
 * <p><b>Keep it to one line.</b> {@code DeathScreen.visitText} places the message at y=85 and
 * the score at y=100 -- one line of headroom. There is no {@code font.split} on that path, so
 * anything long enough to wrap runs into "Score:".
 *
 * <p>Worth having because on a hardcore server the screen otherwise reads "Game Over!" above
 * a button marked "Spectate world", which states the opposite of what LetheMC actually did:
 * the player is locked out, not finished.
 */
@Mixin(ServerPlayer.class)
public class ServerPlayerMixin {

    @ModifyArg(
            method = "die",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/network/protocol/game/ClientboundPlayerCombatKillPacket;<init>(ILnet/minecraft/network/chat/Component;)V"),
            index = 1)
    private Component lethemc$deathScreenLine(Component vanillaMessage) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        return LetheMC.deathScreenMessage(self, vanillaMessage);
    }
}
