package net.lunix.lethe.paper;

import com.destroystokyo.paper.entity.villager.Reputation;
import com.destroystokyo.paper.entity.villager.ReputationType;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Vault;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fox;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Villager;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Takes back what belonged to a life that has ended.
 *
 * <p>Lazy on purpose. Nothing is scanned on startup and no list of claims is kept -- the sweep
 * walks what is loaded, and anything in an unloaded chunk is dealt with the moment somebody
 * arrives to see it. An animal that never loads never mattered.
 */
public final class Reclaimer {

    private Reclaimer() {}

    static void sweep() {
        PluginConfig cfg = PluginConfig.get();
        if (!cfg.wipePets && !cfg.wipeLivestock && !cfg.wipeFoxes
                && !cfg.wipeVillagers && !cfg.wipeVillagerReputation) return;

        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Fox fox) {
                    StampListener.stampFox(fox);
                    checkFox(fox);
                } else if (entity instanceof Villager villager) {
                    checkVillager(villager);
                } else if (entity instanceof Tameable || entity instanceof AbstractHorse) {
                    checkPet(entity);
                }
            }
        }
    }

    // ------------------------------------------------------------------

    /** Destroys an animal whose owner is living a different life -- and its cargo with it. */
    private static void checkPet(Entity entity) {
        PluginConfig cfg = PluginConfig.get();
        boolean livestock = entity instanceof AbstractHorse;
        if (livestock ? !cfg.wipeLivestock : !cfg.wipePets) return;

        String stamp = Keys.get(entity, Keys.incarnation);
        if (stamp == null) return;   // tamed before Lethe existed; never acted on

        UUID owner = ownerOf(entity);
        if (owner == null) return;

        String living = Incarnations.peek(owner);
        if (living == null || living.equals(stamp)) return;

        LethePaper.get().getLogger().info("Removing " + entity.getType()
                + " at " + brief(entity) + " -- its owner is living a different life");
        // remove() takes the animal and anything it carried together. Dropping the cargo would
        // leave the contents of a chest donkey on the ground for anyone to collect, which is
        // the loophole this exists to close.
        entity.remove();
    }

    private static UUID ownerOf(Entity entity) {
        if (entity instanceof Tameable t) return t.getOwnerUniqueId();
        if (entity instanceof AbstractHorse h) return h.getOwnerUniqueId();
        return null;
    }

    // ------------------------------------------------------------------

    /**
     * A fox forgets the players whose lives have ended, and goes if that leaves nobody.
     *
     * <p>Each slot is cleared on its own. Two players can trust one fox, and one of them dying
     * must not cost the other anything -- which is why Bukkit exposing the slots separately
     * matters so much here.
     */
    private static void checkFox(Fox fox) {
        if (!PluginConfig.get().wipeFoxes) return;
        Map<UUID, String> stamps = Keys.stamps(fox);
        if (stamps.isEmpty()) return;

        var first = fox.getFirstTrustedPlayer();
        var second = fox.getSecondTrustedPlayer();
        boolean firstStale = first != null && Keys.isStale(first.getUniqueId(), stamps);
        boolean secondStale = second != null && Keys.isStale(second.getUniqueId(), stamps);
        if (!firstStale && !secondStale) return;

        boolean survivor = (first != null && !firstStale) || (second != null && !secondStale);

        if (survivor) {
            if (firstStale) {
                stamps.remove(first.getUniqueId());
                fox.setFirstTrustedPlayer(null);
            }
            if (secondStale) {
                stamps.remove(second.getUniqueId());
                fox.setSecondTrustedPlayer(null);
            }
            Keys.stamps(fox, stamps);
            LethePaper.get().getLogger().info("Fox at " + brief(fox) + " forgot a player whose life has ended");
            return;
        }

        // Nobody living trusts it. A fox nobody remembers is not a wild animal that tolerated
        // someone, it is a kept animal whose keeper no longer exists -- and left alive it would
        // be a fox farm surviving a death.
        LethePaper.get().getLogger().info("Removing fox at " + brief(fox) + " -- no living player trusts it");
        fox.remove();
    }

    // ------------------------------------------------------------------

    /** Villagers forget a dead life, and a hall built by the dead alone is destroyed. */
    private static void checkVillager(Villager villager) {
        PluginConfig cfg = PluginConfig.get();
        Map<UUID, String> stamps = Keys.stamps(villager);

        if (cfg.wipeVillagerReputation && !stamps.isEmpty()) {
            for (UUID id : villager.getReputations().keySet()) {
                if (Keys.isStale(id, stamps)) {
                    villager.setReputation(id, new Reputation(new EnumMap<>(ReputationType.class)));
                }
            }
        }

        if (!cfg.wipeVillagers) return;

        Set<UUID> direct = Keys.uuids(villager, Keys.direct);
        if (direct.isEmpty()) return;   // bred and left alone -- never dealt with anyone

        boolean anyEnded = false;
        for (UUID customer : direct) {
            if (stamps.get(customer) == null) return;   // dealings we cannot date
            if (Keys.isStale(customer, stamps)) {
                anyEnded = true;
            } else {
                return;                                  // somebody living still trades here
            }
        }
        if (!anyEnded) return;

        LethePaper.get().getLogger().info(
                "Removing villager at " + brief(villager) + " -- everyone who traded here is gone");
        villager.remove();
    }

    // ------------------------------------------------------------------

    /**
     * Lets a vault forget that a dead life looted it.
     *
     * <p>Called when a player is near, rather than from the entity sweep: a vault is a block,
     * and walking every loaded block would cost far more than it saves. The answer is only
     * needed when somebody is standing there anyway.
     */
    static void checkVault(Vault vault) {
        if (!PluginConfig.get().wipeVaultRewards) return;
        Map<UUID, String> stamps = Keys.stamps(vault);
        if (stamps.isEmpty()) return;

        boolean changed = false;
        for (UUID id : vault.getRewardedPlayers().toArray(new UUID[0])) {
            if (Keys.isStale(id, stamps)) {
                vault.removeRewardedPlayer(id);
                stamps.remove(id);
                changed = true;
            }
        }
        if (changed) {
            Keys.stamps(vault, stamps);
            vault.update();
            LethePaper.get().getLogger().info("Vault at " + vault.getLocation().getBlockX() + ", "
                    + vault.getLocation().getBlockY() + ", " + vault.getLocation().getBlockZ()
                    + " forgot a life that had already looted it");
        }
    }

    private static String brief(Entity e) {
        return e.getLocation().getBlockX() + ", " + e.getLocation().getBlockY()
                + ", " + e.getLocation().getBlockZ();
    }
}
