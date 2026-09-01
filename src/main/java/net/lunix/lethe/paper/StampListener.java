package net.lunix.lethe.paper;

import io.papermc.paper.event.player.PlayerTradeEvent;
import org.bukkit.block.Block;
import org.bukkit.block.Vault;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.Fox;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTameEvent;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Marks things with the life that claimed them.
 *
 * <p>The stamp is what separates "tamed before you died" from "tamed ten minutes ago" -- a
 * distinction a player's UUID cannot make, because it survives death.
 */
public final class StampListener implements Listener {

    /** Wolves, cats, parrots, horses, donkeys, mules, llamas, camels -- anything tameable. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTame(EntityTameEvent event) {
        if (!(event.getOwner() instanceof Player player)) return;
        LivingEntity animal = event.getEntity();
        Keys.set(animal, Keys.incarnation, Incarnations.of(player.getUniqueId()));
    }

    /**
     * Trading marks a real customer; so does curing.
     *
     * <p>Only positive dealings count. Vanilla also files hitting a villager and killing one as
     * reputation events, and counting those would invert the feature -- a stranger who punched
     * a villager and later died would take it with them.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrade(PlayerTradeEvent event) {
        AbstractVillager merchant = event.getVillager();
        if (!(merchant instanceof Villager villager)) return;
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();

        Map<UUID, String> stamps = Keys.stamps(villager);

        // A life that has ended has its opinions dropped before the new one is recorded.
        // Re-dating the old entry instead would hand back a whole past life's standing for the
        // price of one trade.
        if (Keys.isStale(id, stamps)) {
            villager.setReputation(id, new com.destroystokyo.paper.entity.villager.Reputation(
                    new java.util.EnumMap<>(com.destroystokyo.paper.entity.villager.ReputationType.class)));
        }

        stamps.put(id, Incarnations.of(id));
        Keys.stamps(villager, stamps);

        Set<UUID> direct = Keys.uuids(villager, Keys.direct);
        direct.add(id);
        Keys.uuids(villager, Keys.direct, direct);
    }

    /**
     * Records which life a vault rewarded.
     *
     * <p>Bukkit has no "vault opened" event, so the sweep notices instead: a vault whose
     * rewarded list has grown gets the newcomers stamped. That is a tick late at worst, and the
     * reward is not consulted again until someone next presents a key.
     */
    static void stampVault(Block block) {
        if (!(block.getState() instanceof Vault vault)) return;
        Map<UUID, String> stamps = Keys.stamps(vault);
        boolean changed = false;
        for (UUID id : vault.getRewardedPlayers()) {
            if (!stamps.containsKey(id) && Incarnations.peek(id) != null) {
                stamps.put(id, Incarnations.of(id));
                changed = true;
            }
        }
        if (changed) {
            Keys.stamps(vault, stamps);
            vault.update();
        }
    }

    /**
     * Foxes are stamped by the sweep as well.
     *
     * <p>Trust is granted when a kit is born to parents someone fed, and there is no event for
     * it, so the trust slots are read directly and any newly trusted player is dated then.
     */
    static void stampFox(Fox fox) {
        Map<UUID, String> stamps = Keys.stamps(fox);
        boolean changed = false;
        for (org.bukkit.entity.AnimalTamer t : new org.bukkit.entity.AnimalTamer[]{
                fox.getFirstTrustedPlayer(), fox.getSecondTrustedPlayer()}) {
            if (t == null) continue;
            UUID id = t.getUniqueId();
            if (!stamps.containsKey(id) && Incarnations.peek(id) != null) {
                stamps.put(id, Incarnations.of(id));
                changed = true;
            }
        }
        if (changed) Keys.stamps(fox, stamps);
    }
}
