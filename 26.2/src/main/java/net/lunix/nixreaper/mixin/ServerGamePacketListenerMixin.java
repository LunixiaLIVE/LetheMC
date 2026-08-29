package net.lunix.nixreaper.mixin;

import net.lunix.nixreaper.NixReaper;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.GameType;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Holds the player on the death screen by swallowing their respawn request.
 *
 * <p>They stay there for lockout.deathScreenSeconds (default 15) so they can read what
 * killed them, then the tick loop disconnects them. Clicking Disconnect themselves is
 * equally fine -- the lockout clock starts at whichever happens first.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public class ServerGamePacketListenerMixin {

    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleClientCommand", at = @At("HEAD"), cancellable = true)
    private void nixreaper$blockRespawn(ServerboundClientCommandPacket packet, CallbackInfo ci) {
        if (packet.getAction() != ServerboundClientCommandPacket.Action.PERFORM_RESPAWN) return;
        if (player != null && NixReaper.shouldBlockRespawn(player)) {
            ci.cancel();
        }
    }

    /**
     * Keeps a pardoned player's inventory and XP through their respawn.
     *
     * <p>A pardon restores the files, but the restored data still has the player dead, so
     * they rejoin to a death screen. Vanilla's respawn discards inventory and experience
     * unless keepInventory reads true right here -- so without this, "purge cancelled, data
     * restored" produced a player with nothing. Found in testing 2026-08-28.
     *
     * <p>Redirecting the call rather than injecting at HEAD is deliberate: the packet handler
     * can execute twice (netty thread aborts through {@code ensureRunningOnSameThread}, then
     * the server thread runs it properly), and a flag raised on the aborted pass would leak.
     * The flag is global -- {@code GameRules#get} has no player context -- so it must not
     * outlive the one call it wraps, or an unrelated player dying in the same window would
     * wrongly keep their items.
     */
    @Redirect(
            method = "handleClientCommand",
            at = @At(value = "INVOKE", ordinal = 1,
                    target = "Lnet/minecraft/server/players/PlayerList;respawn(Lnet/minecraft/server/level/ServerPlayer;ZLnet/minecraft/world/entity/Entity$RemovalReason;)Lnet/minecraft/server/level/ServerPlayer;"))
    private ServerPlayer nixreaper$keepInventoryOnPardonedRespawn(
            PlayerList playerList, ServerPlayer dead, boolean keepEverything, Entity.RemovalReason reason) {
        // ordinal = 1 targets the DEATH respawn specifically. handleClientCommand calls
        // respawn twice: ordinal 0 is the End-portal return (guarded by `wonGame`), which is
        // not a death and must not consume a pardon.
        NixReaper.beginPardonedRespawn(dead);
        return playerList.respawn(dead, keepEverything, reason);
    }

    /**
     * Stops hardcore locking a pardoned player into spectator.
     *
     * <p>On a hardcore server the death respawn is followed by an unconditional
     * `setGameMode(SPECTATOR)` — the branch tests `isHardcore()` and nothing else. Vanilla
     * keeps no "has died" flag to clear: the spectator state is re-derived at every respawn,
     * and only persists afterwards because it is written to `playerGameType` in playerdata.
     * So this call is the single place to intervene, and suppressing it is sufficient — no
     * later code path reintroduces it.
     *
     * <p>Without this, a pardon on a hardcore server hands the player everything back and then
     * permanently prevents them from using any of it.
     *
     * <p>Only pardoned respawns are affected. A normal nixReaper death never reaches here at
     * all (the respawn packet is cancelled at HEAD while the player is DYING), and an exempt
     * player is left entirely to vanilla.
     */
    @Redirect(
            method = "handleClientCommand",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayer;setGameMode(Lnet/minecraft/world/level/GameType;)Z"))
    private boolean nixreaper$noSpectatorAfterPardon(ServerPlayer respawned, GameType mode) {
        if (mode == GameType.SPECTATOR && NixReaper.isPardonedRespawn(respawned)) {
            return false;
        }
        return respawned.setGameMode(mode);
    }

    /**
     * Closes the pardoned-respawn bracket.
     *
     * <p>At TAIL rather than in a finally around the respawn call, because hardcore's
     * spectator conversion happens *after* that call returns and still needs the flag. The
     * early `return` when health is above zero cannot reach here, but the flag is never set on
     * that path either.
     */
    @Inject(method = "handleClientCommand", at = @At("TAIL"))
    private void nixreaper$finishPardonedRespawn(ServerboundClientCommandPacket packet, CallbackInfo ci) {
        if (packet.getAction() != ServerboundClientCommandPacket.Action.PERFORM_RESPAWN) return;
        // `player` is reassigned to the new entity by the respawn; the UUID is unchanged.
        NixReaper.endPardonedRespawn(player);
    }
}
