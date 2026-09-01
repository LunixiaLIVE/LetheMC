package net.lunix.lethe.paper;

import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Where a dead player's belongings wait out the grace period.
 *
 * <h2>Why a snapshot rather than moving files</h2>
 * The Fabric build moves the player's data files aside at a point where it can prove the server
 * has finished writing them. A plugin has no such point -- Bukkit saves player data around quit
 * on its own schedule, and racing it would risk a half-written file or a stale one overwriting
 * a good one. So the belongings are read out of the live player, written here, and cleared from
 * the player before they leave. Whatever the server saves afterwards is the emptied state, which
 * is what we want anyway.
 *
 * <p>Restoring is the same in reverse, and it works on an offline player only for the pieces
 * Bukkit exposes offline -- which is why a resurrection waits for them to reconnect.
 */
public final class Graveyard {

    private Graveyard() {}

    private static File dir;

    static void init(LethePaper plugin) {
        dir = new File(plugin.getDataFolder(), "graveyard");
        if (!dir.exists() && !dir.mkdirs()) {
            plugin.getLogger().warning("Could not create the graveyard directory");
        }
    }

    private static File plot(UUID uuid) {
        return new File(dir, uuid + ".yml");
    }

    public static boolean exists(UUID uuid) {
        return plot(uuid).exists();
    }

    /**
     * Takes everything and files it away.
     *
     * @return true if a plot was written and the player was emptied
     */
    public static boolean entomb(Player player) {
        PluginConfig cfg = PluginConfig.get();
        YamlConfiguration y = new YamlConfiguration();

        if (cfg.wipePlayerData) {
            y.set("inventory", player.getInventory().getContents());
            y.set("armour", player.getInventory().getArmorContents());
            y.set("offhand", player.getInventory().getItemInOffHand());
            y.set("enderchest", player.getEnderChest().getContents());
            y.set("xp.level", player.getLevel());
            y.set("xp.exp", player.getExp());
            y.set("xp.total", player.getTotalExperience());
        }

        if (cfg.wipeAdvancements) {
            List<String> done = new ArrayList<>();
            for (Iterator<Advancement> it = Bukkit.advancementIterator(); it.hasNext(); ) {
                Advancement a = it.next();
                AdvancementProgress p = player.getAdvancementProgress(a);
                for (String c : p.getAwardedCriteria()) {
                    done.add(a.getKey() + "|" + c);
                }
            }
            y.set("advancements", done);
        }

        if (cfg.wipeStats) {
            y.set("stats", snapshotStats(player));
        }

        try {
            y.save(plot(player.getUniqueId()));
        } catch (IOException e) {
            LethePaper.get().getLogger().severe(
                    "Could not entomb " + player.getName() + ": " + e.getMessage()
                            + " -- leaving their belongings alone rather than destroying them.");
            return false;
        }

        clearLive(player);
        return true;
    }

    /** Empties the player themselves, after the snapshot is safely on disk. */
    private static void clearLive(Player player) {
        PluginConfig cfg = PluginConfig.get();

        if (cfg.wipePlayerData) {
            player.getInventory().clear();
            player.getInventory().setArmorContents(null);
            player.getInventory().setItemInOffHand(null);
            player.getEnderChest().clear();
            player.setLevel(0);
            player.setExp(0f);
            player.setTotalExperience(0);
        }

        if (cfg.wipeAdvancements) {
            for (Iterator<Advancement> it = Bukkit.advancementIterator(); it.hasNext(); ) {
                Advancement a = it.next();
                AdvancementProgress p = player.getAdvancementProgress(a);
                for (String c : new ArrayList<>(p.getAwardedCriteria())) {
                    p.revokeCriteria(c);
                }
            }
        }

        if (cfg.wipeStats) {
            wipeStats(player);
        }
    }

    /** Gives it all back. The player must be online. */
    public static boolean restore(Player player) {
        File f = plot(player.getUniqueId());
        if (!f.exists()) return false;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);

        if (y.contains("inventory")) {
            player.getInventory().setContents(toStacks(y.getList("inventory")));
            player.getInventory().setArmorContents(toStacks(y.getList("armour")));
            player.getInventory().setItemInOffHand(y.getItemStack("offhand"));
            player.getEnderChest().setContents(toStacks(y.getList("enderchest")));
            player.setLevel(y.getInt("xp.level"));
            player.setExp((float) y.getDouble("xp.exp"));
            player.setTotalExperience(y.getInt("xp.total"));
        }

        for (String entry : y.getStringList("advancements")) {
            int bar = entry.lastIndexOf('|');
            if (bar <= 0) continue;
            String key = entry.substring(0, bar), crit = entry.substring(bar + 1);
            for (Iterator<Advancement> it = Bukkit.advancementIterator(); it.hasNext(); ) {
                Advancement a = it.next();
                if (a.getKey().toString().equals(key)) {
                    player.getAdvancementProgress(a).awardCriteria(crit);
                    break;
                }
            }
        }

        for (String entry : y.getStringList("stats")) {
            applyStat(player, entry);
        }

        destroy(player.getUniqueId());
        return true;
    }

    private static ItemStack[] toStacks(List<?> list) {
        if (list == null) return new ItemStack[0];
        ItemStack[] out = new ItemStack[list.size()];
        for (int i = 0; i < list.size(); i++) {
            out[i] = list.get(i) instanceof ItemStack s ? s : null;
        }
        return out;
    }

    /** Erases the plot for good. */
    public static void destroy(UUID uuid) {
        File f = plot(uuid);
        if (f.exists() && !f.delete()) {
            LethePaper.get().getLogger().warning("Could not delete graveyard plot for " + uuid);
        }
    }

    // ------------------------------------------------------------------
    // statistics
    //
    // Bukkit splits these three ways -- untyped, per-Material and per-EntityType -- and asking
    // for the wrong shape throws. Only non-zero values are recorded, because the alternative is
    // tens of thousands of zeroes per player.
    // ------------------------------------------------------------------

    private static List<String> snapshotStats(Player p) {
        List<String> out = new ArrayList<>();
        for (Statistic s : Statistic.values()) {
            try {
                switch (s.getType()) {
                    case UNTYPED -> {
                        int v = p.getStatistic(s);
                        if (v != 0) out.add(s.name() + "||" + v);
                    }
                    case BLOCK, ITEM -> {
                        for (Material m : Material.values()) {
                            if (m.isLegacy()) continue;
                            try {
                                int v = p.getStatistic(s, m);
                                if (v != 0) out.add(s.name() + "|" + m.name() + "|" + v);
                            } catch (IllegalArgumentException ignored) { }
                        }
                    }
                    case ENTITY -> {
                        for (EntityType t : EntityType.values()) {
                            try {
                                int v = p.getStatistic(s, t);
                                if (v != 0) out.add(s.name() + "|" + t.name() + "|" + v);
                            } catch (IllegalArgumentException ignored) { }
                        }
                    }
                }
            } catch (Exception ignored) {
                // A statistic this server build does not accept. Skipping it loses one number,
                // which beats aborting the entombment.
            }
        }
        return out;
    }

    private static void wipeStats(Player p) {
        for (String entry : snapshotStats(p)) {
            String[] parts = entry.split("\\|", 3);
            setStat(p, parts[0], parts[1], 0);
        }
    }

    private static void applyStat(Player p, String entry) {
        String[] parts = entry.split("\\|", 3);
        if (parts.length != 3) return;
        try {
            setStat(p, parts[0], parts[1], Integer.parseInt(parts[2]));
        } catch (NumberFormatException ignored) { }
    }

    private static void setStat(Player p, String stat, String qualifier, int value) {
        try {
            Statistic s = Statistic.valueOf(stat);
            switch (s.getType()) {
                case UNTYPED -> p.setStatistic(s, value);
                case BLOCK, ITEM -> p.setStatistic(s, Material.valueOf(qualifier), value);
                case ENTITY -> p.setStatistic(s, EntityType.valueOf(qualifier), value);
            }
        } catch (Exception ignored) {
            // Same reasoning as the snapshot: one lost number is not worth failing over.
        }
    }
}
