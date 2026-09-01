package net.lunix.lethe.paper;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;

import java.util.UUID;

/** The door: who is turned away, who is given everything back, and who is greeted as a stranger. */
public final class ConnectionListener implements Listener {

    /**
     * Turns away anyone still in Purgatory.
     *
     * <p>{@code PlayerLoginEvent} rather than the async pre-login one, because the message needs
     * a live countdown and the ledger is not safe to read off the main thread.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onLogin(PlayerLoginEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Ledger.Entry e = Ledger.get(uuid);
        if (e == null) return;

        long now = System.currentTimeMillis();

        if (Ledger.STATE_PARDONED.equals(e.state)) {
            Pending.oweRestore(uuid);
            return;
        }

        // Their time is up. Retire it here rather than leaving it to the next tick: the debt has
        // to exist before PlayerJoinEvent fires, or the join hands them a world they can only
        // watch.
        if (e.expired(now)) {
            LethePaper.reincarnate(uuid, e);
            return;
        }

        event.disallow(PlayerLoginEvent.Result.KICK_OTHER,
                LethePaper.colour(Messages.render(PluginConfig.get().messageRejoin, e, null, now)));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        boolean restore = Pending.takeRestore(uuid);
        boolean greet = Pending.takeGreeting(uuid);
        if (!restore && !greet) return;

        // Deferred a tick: neither the inventory nor the game mode is reliably writable during
        // the join itself.
        LethePaper.get().getServer().getScheduler().runTask(LethePaper.get(), () -> {
            if (!player.isOnline()) return;

            leaveSpectator(player);

            if (restore) {
                if (Graveyard.restore(player)) {
                    player.sendMessage(LethePaper.colour(
                            "&aYou have been resurrected. &7Everything is as you left it."));
                    LethePaper.get().getLogger().info("Restored " + player.getName() + " on reconnect");
                } else {
                    player.sendMessage(LethePaper.colour(
                            "&aYou have been released early. &7Your belongings were already gone."));
                }
                Ledger.remove(uuid);
                return;
            }

            String taunt = Taunts.pick();
            player.sendMessage(LethePaper.colour(
                    PluginConfig.get().messageReincarnation + (taunt == null ? "" : "\n&8&o" + taunt)));
            LethePaper.get().getLogger().info(player.getName() + " reincarnated");
        });
    }

    /**
     * Undoes hardcore's respawn-to-spectator.
     *
     * <p>On a hardcore world vanilla turns a dead player into a spectator, and Lethe's players
     * are always dead when they come back -- so without this, reincarnation hands someone a
     * world they can see and never touch again. The Fabric build suppresses the conversion at
     * source with a mixin; a plugin has to undo it afterwards.
     *
     * <p>Only ever applied to a player Lethe is already acting on, and only when they are
     * actually spectating, so an admin who chose spectator is left alone.
     */
    private void leaveSpectator(Player player) {
        if (player.getGameMode() != GameMode.SPECTATOR) return;
        player.setGameMode(GameMode.SURVIVAL);
        // Hardcore also leaves them dead-but-spectating with no health; give them a body.
        if (player.getHealth() <= 0.0) {
            player.setHealth(player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH) != null
                    ? player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue()
                    : 20.0);
        }
        LethePaper.get().getLogger().info(
                "Returned " + player.getName() + " to survival (hardcore had made them a spectator)");
    }
}
