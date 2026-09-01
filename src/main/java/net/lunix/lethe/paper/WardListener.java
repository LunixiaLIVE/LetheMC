package net.lunix.lethe.paper;

import org.bukkit.block.Vault;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fox;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Protects what is about to be taken, for as long as it could still be given back.
 *
 * <p>Without this the grace period is an open window. The owner is offline and cannot defend
 * anything, so a friend could empty their donkey, kill it for the drops, or buy one item from
 * each villager in the hall and make themselves the living customer that spares every one of
 * them. Either way the belongings survive a death that was meant to take them.
 *
 * <p>Everything here asks the <b>ledger</b>, never the stamps. During the grace period the dead
 * player's incarnation has not rotated yet -- that happens when their belongings are destroyed
 * -- so nothing is stale during the very window being protected. A staleness test here would
 * produce a ward that never once fired.
 */
public final class WardListener implements Listener {

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (isWarded(event.getRightClicked())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (isWarded(event.getEntity())) event.setCancelled(true);
    }

    /**
     * A vault forgets a dead life the moment somebody reaches for it.
     *
     * <p>Bukkit has no vault event, and walking every loaded block on a timer would cost far
     * more than it saves, so the question is answered where it is asked: at the click.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onUseVault(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        if (!(event.getClickedBlock().getState() instanceof Vault vault)) return;

        Reclaimer.checkVault(vault);

        // The reward lands during vanilla's handling, so the stamp is taken a tick later.
        var block = event.getClickedBlock();
        LethePaper.get().getServer().getScheduler().runTask(LethePaper.get(),
                () -> StampListener.stampVault(block));
    }

    // ------------------------------------------------------------------

    static boolean isWarded(Entity entity) {
        if (entity instanceof Fox fox) return foxWarded(fox);
        if (entity instanceof Villager villager) return villagerWarded(villager);
        if (entity instanceof Tameable || entity instanceof AbstractHorse) return petWarded(entity);
        return false;
    }

    private static boolean petWarded(Entity entity) {
        UUID owner = entity instanceof Tameable t ? t.getOwnerUniqueId()
                : entity instanceof AbstractHorse h ? h.getOwnerUniqueId() : null;
        return owner != null && doomed(owner);
    }

    /** Only while the whole fox is doomed -- a fox a living player trusts is left alone. */
    private static boolean foxWarded(Fox fox) {
        if (!PluginConfig.get().wipeFoxes) return false;
        Map<UUID, String> stamps = Keys.stamps(fox);
        if (stamps.isEmpty()) return false;

        boolean anyDoomed = false;
        for (var t : new org.bukkit.entity.AnimalTamer[]{
                fox.getFirstTrustedPlayer(), fox.getSecondTrustedPlayer()}) {
            if (t == null) continue;
            UUID id = t.getUniqueId();
            if (stamps.get(id) == null) return false;   // undated trust is never acted on
            if (!doomed(id)) return false;              // somebody living trusts it
            anyDoomed = true;
        }
        return anyDoomed;
    }

    /** Only while every customer is in Purgatory; a hall anyone living uses stays open. */
    private static boolean villagerWarded(Villager villager) {
        if (!PluginConfig.get().wipeVillagers) return false;
        Set<UUID> direct = Keys.uuids(villager, Keys.direct);
        if (direct.isEmpty()) return false;
        Map<UUID, String> stamps = Keys.stamps(villager);

        boolean anyDoomed = false;
        for (UUID customer : direct) {
            if (stamps.get(customer) == null) return false;
            if (!doomed(customer)) return false;
            anyDoomed = true;
        }
        return anyDoomed;
    }

    /** In Purgatory, with belongings a resurrection would still give back. */
    private static boolean doomed(UUID uuid) {
        Ledger.Entry e = Ledger.get(uuid);
        // PARDONED means resurrected and on the way back, so the ward lifts at once.
        return e != null && !Ledger.STATE_PARDONED.equals(e.state);
    }
}
