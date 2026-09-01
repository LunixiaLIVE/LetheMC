package net.lunix.lethe.paper;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

/**
 * The moment everything is taken.
 *
 * <p>Runs at {@code MONITOR} so any plugin that wanted to cancel drops, change the death
 * message or keep the inventory has already had its say -- and then overrides it, because a
 * death penalty that another plugin can quietly opt out of is not a penalty.
 */
public final class DeathListener implements Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (LethePaper.isExempt(player)) return;

        LethePaper.DYING.add(player.getUniqueId());

        // Nothing on the ground. In the mod this is a forced keepInventory read for the length
        // of the death; here it is the same idea with a supported name.
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.getDrops().clear();
        event.setDroppedExp(0);
        event.setNewExp(0);
        event.setNewLevel(0);
        event.setNewTotalExp(0);

        PluginConfig cfg = PluginConfig.get();
        long now = System.currentTimeMillis();

        Ledger.Entry e = new Ledger.Entry();
        e.name = player.getName();
        e.state = Ledger.STATE_LOCKED;
        e.deathAt = now;
        // No death screen to wait through, so the clock starts at once.
        e.purgatoryStartsAt = now;
        e.durationMillis = cfg.purgatoryMinutes * 60_000L;
        e.graceMillis = cfg.wipeGraceMinutes * 60_000L;
        e.restorable = true;
        Ledger.put(player.getUniqueId(), e);

        if (!Graveyard.entomb(player)) {
            // The snapshot failed, so the belongings were left untouched. Better an unpunished
            // death than one that destroys what it cannot first record.
            e.restorable = false;
            Ledger.remove(player.getUniqueId());
            LethePaper.DYING.remove(player.getUniqueId());
            LethePaper.get().getLogger().severe(
                    "Entombment failed for " + player.getName() + "; leaving this death vanilla.");
            return;
        }

        LethePaper.get().getLogger().info(
                player.getName() + " died -- Purgatory " + cfg.purgatoryMinutes + " min pending");

        String reason = event.getDeathMessage() == null ? "You died." : event.getDeathMessage();
        String kick = Messages.render(cfg.messageDeath, e, reason, now);

        // Removed on the next tick: kicking inside the death event itself fights the server's
        // own respawn handling, and the snapshot above must be allowed to finish first.
        LethePaper.get().getServer().getScheduler().runTask(LethePaper.get(), () -> {
            LethePaper.DYING.remove(player.getUniqueId());
            if (player.isOnline()) player.kickPlayer(LethePaper.colour(kick));
        });
    }
}
