package net.lunix.lethe.paper;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Lethe, as a Paper plugin.
 *
 * <p>The Fabric build does this with eighteen mixins. Almost none are needed here: Bukkit
 * exposes the fox's two trust slots, the vault's rewarded-player list and the villager's
 * reputation map as ordinary API, and {@code PersistentDataContainer} stands in for the NBT the
 * mixins added. What is left is events.
 *
 * <p><b>The one thing that does not survive the port</b> is holding the death screen. The mod
 * swallows the respawn packet so a player can read what killed them before being disconnected;
 * a plugin cannot, so the cause of death goes into the kick message instead and the player is
 * removed immediately.
 */
public final class LethePaper extends JavaPlugin {

    private static LethePaper instance;

    /** Players who died this tick, so nothing drops while the death event is being handled. */
    static final Set<UUID> DYING = new HashSet<>();
    public static LethePaper get() { return instance; }

    @Override
    public void onEnable() {
        instance = this;
        Keys.init(this);
        PluginConfig.load(this);
        Incarnations.load(this);
        Ledger.load(this);
        Pending.load(this);
        Graveyard.init(this);
        Taunts.load(this);

        getServer().getPluginManager().registerEvents(new DeathListener(), this);
        getServer().getPluginManager().registerEvents(new ConnectionListener(), this);
        getServer().getPluginManager().registerEvents(new WardListener(), this);
        getServer().getPluginManager().registerEvents(new StampListener(), this);

        LetheCommand cmd = new LetheCommand();
        var command = getCommand("lethe");
        if (command != null) {
            command.setExecutor(cmd);
            command.setTabCompleter(cmd);
        }

        int interval = PluginConfig.get().wipeSweepIntervalTicks;
        getServer().getScheduler().runTaskTimer(this, this::tick, interval, interval);

        recover();
        getLogger().info("Lethe ready -- Purgatory " + PluginConfig.get().purgatoryMinutes
                + " min, grace " + PluginConfig.get().wipeGraceMinutes + " min");
    }

    @Override
    public void onDisable() {
        Ledger.save();
        Incarnations.save();
        Pending.save();
    }

    /**
     * Catches up on anything that fell due while the server was down.
     *
     * <p>Purgatory is measured in real time on purpose, so a six-hour lockout begun at ten
     * o'clock has expired by four whether or not the server ran overnight.
     */
    private void recover() {
        long now = System.currentTimeMillis();
        for (UUID uuid : Ledger.uuids()) {
            Ledger.Entry e = Ledger.get(uuid);
            if (e == null) continue;
            // A crash during the death screen leaves the clock unstarted; start it from the
            // death itself, which errs in the player's favour.
            if (e.purgatoryStartsAt <= 0) {
                e.purgatoryStartsAt = e.deathAt;
            }
            if (e.restorable && now >= e.deathAt + e.graceMillis) {
                Graveyard.destroy(uuid);
                e.restorable = false;
                Incarnations.rotate(uuid);
                getLogger().info("Purged graveyard plot for " + e.name + " (" + uuid + ")");
            }
        }
        Ledger.save();
    }

    private void tick() {
        long now = System.currentTimeMillis();
        boolean dirty = false;

        for (UUID uuid : Ledger.uuids()) {
            Ledger.Entry e = Ledger.get(uuid);
            if (e == null) continue;

            // Grace over: the belongings go, and the life they belonged to ends with them.
            if (e.restorable && now >= e.deathAt + e.graceMillis) {
                Graveyard.destroy(uuid);
                e.restorable = false;
                Incarnations.rotate(uuid);
                getLogger().info("Purged graveyard plot for " + e.name + " (" + uuid + ")");
                dirty = true;
            }

            // Purgatory over: they are reincarnated and owed a greeting.
            if (e.expired(now) && !Ledger.STATE_PARDONED.equals(e.state)) {
                reincarnate(uuid, e);
                dirty = false; // remove() already saved
            }
        }
        if (dirty) Ledger.save();

        Reclaimer.sweep();
    }

    // ------------------------------------------------------------------

    /**
     * Retires a served sentence.
     *
     * <p>Called from the sweep and again from the login handler, because a player can arrive in
     * the window between their Purgatory expiring and the next tick noticing. Doing it at the
     * door as well means the debt is always recorded <em>before</em> the join fires -- otherwise
     * they would join owed nothing, and on a hardcore world stay a spectator until they relogged.
     */
    static void reincarnate(UUID uuid, Ledger.Entry e) {
        get().getLogger().info(e.name + " left Purgatory -- reincarnated");
        Pending.oweGreeting(uuid);
        Ledger.remove(uuid);
    }

    /** Whether this player is above the reckoning. */
    public static boolean isExempt(Player player) {
        int level = PluginConfig.get().bypassPermissionLevel;
        if (level < 0) return false;   // -1: nobody is exempt
        if (level == 0) return true;   // 0: everybody is
        return player.isOp();
    }

    /** Whether this player may run the admin commands. */
    public static boolean canAdmin(org.bukkit.command.CommandSender sender) {
        return !(sender instanceof Player p) || p.isOp();
    }

    public static String colour(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    public static Player online(UUID uuid) {
        return Bukkit.getPlayer(uuid);
    }
}
