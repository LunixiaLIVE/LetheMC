package net.lunix.lethe.paper;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every knob, read once and held in memory.
 *
 * <p>Key names match the Fabric build's dotted commands exactly, so an admin who has run one
 * does not have to relearn the other.
 */
public final class PluginConfig {

    private static PluginConfig current = new PluginConfig();

    public boolean wipePlayerData = true;
    public boolean wipeAdvancements = true;
    public boolean wipeStats = true;
    public int wipeGraceMinutes = 5;
    public boolean wipePets = true;
    public boolean wipeLivestock = true;
    public boolean wipeFoxes = true;
    public boolean wipeVaultRewards = true;
    public boolean wipeVillagerReputation = true;
    public boolean wipeVillagers = true;
    public int wipeSweepIntervalTicks = 20;

    public int purgatoryMinutes = 360;

    public String messageDeath =
            "&c☠ You have died.\n&7%death_reason%\n\n&fYou are in &5Purgatory&f for &e%time_remaining%&f.\n\n%grace_line%";
    public String messageRejoin =
            "&5☠ You are in Purgatory\n\n&fYou may return in &e%time_remaining%\n\n%grace_line%";
    public String messageReincarnation =
            "&5You have been reincarnated. &7Nothing of your old life remains.";

    public int bypassPermissionLevel = -1;

    public static PluginConfig get() { return current; }

    static void load(LethePaper plugin) {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration c = plugin.getConfig();
        PluginConfig p = new PluginConfig();

        p.wipePlayerData = c.getBoolean("wipe.playerData", p.wipePlayerData);
        p.wipeAdvancements = c.getBoolean("wipe.advancements", p.wipeAdvancements);
        p.wipeStats = c.getBoolean("wipe.stats", p.wipeStats);
        p.wipeGraceMinutes = c.getInt("wipe.graceMinutes", p.wipeGraceMinutes);
        p.wipePets = c.getBoolean("wipe.pets", p.wipePets);
        p.wipeLivestock = c.getBoolean("wipe.livestock", p.wipeLivestock);
        p.wipeFoxes = c.getBoolean("wipe.foxes", p.wipeFoxes);
        p.wipeVaultRewards = c.getBoolean("wipe.vaultRewards", p.wipeVaultRewards);
        p.wipeVillagerReputation = c.getBoolean("wipe.villagerReputation", p.wipeVillagerReputation);
        p.wipeVillagers = c.getBoolean("wipe.villagers", p.wipeVillagers);
        p.wipeSweepIntervalTicks = Math.max(1, c.getInt("wipe.sweepIntervalTicks", p.wipeSweepIntervalTicks));
        p.purgatoryMinutes = c.getInt("purgatory.minutes", p.purgatoryMinutes);
        p.messageDeath = c.getString("message.death", p.messageDeath);
        p.messageRejoin = c.getString("message.rejoin", p.messageRejoin);
        p.messageReincarnation = c.getString("message.reincarnation", p.messageReincarnation);
        p.bypassPermissionLevel = c.getInt("bypass.permissionLevel", p.bypassPermissionLevel);

        // Purgatory must outlast the grace period, or the belongings would still be restorable
        // at the moment the player is let back in -- and the wipe would then land on a live
        // session. Corrected rather than refused: a bad number should not stop the server.
        if (p.purgatoryMinutes <= p.wipeGraceMinutes) {
            int corrected = p.wipeGraceMinutes + 1;
            plugin.getLogger().warning("purgatory.minutes (" + p.purgatoryMinutes
                    + ") must exceed wipe.graceMinutes (" + p.wipeGraceMinutes + "); clamping to " + corrected);
            p.purgatoryMinutes = corrected;
        }
        current = p;
    }

    /** Key-based access, so one command can read and write everything. */
    public Map<String, String> asMap() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("wipe.playerData", String.valueOf(wipePlayerData));
        m.put("wipe.advancements", String.valueOf(wipeAdvancements));
        m.put("wipe.stats", String.valueOf(wipeStats));
        m.put("wipe.graceMinutes", String.valueOf(wipeGraceMinutes));
        m.put("wipe.pets", String.valueOf(wipePets));
        m.put("wipe.livestock", String.valueOf(wipeLivestock));
        m.put("wipe.foxes", String.valueOf(wipeFoxes));
        m.put("wipe.vaultRewards", String.valueOf(wipeVaultRewards));
        m.put("wipe.villagerReputation", String.valueOf(wipeVillagerReputation));
        m.put("wipe.villagers", String.valueOf(wipeVillagers));
        m.put("wipe.sweepIntervalTicks", String.valueOf(wipeSweepIntervalTicks));
        m.put("purgatory.minutes", String.valueOf(purgatoryMinutes));
        m.put("bypass.permissionLevel", String.valueOf(bypassPermissionLevel));
        return m;
    }

    /** @return an error to show the admin, or null on success. */
    public static String set(LethePaper plugin, String key, String value) {
        if (!current.asMap().containsKey(key)) return "Unknown key: " + key;
        FileConfiguration c = plugin.getConfig();
        try {
            if (key.equals("purgatory.minutes") || key.equals("wipe.graceMinutes")
                    || key.equals("wipe.sweepIntervalTicks") || key.equals("bypass.permissionLevel")) {
                c.set(key, Integer.parseInt(value.trim()));
            } else {
                c.set(key, Boolean.parseBoolean(value.trim()));
            }
        } catch (NumberFormatException e) {
            return value + " is not a number.";
        }
        plugin.saveConfig();
        load(plugin);
        return null;
    }
}
