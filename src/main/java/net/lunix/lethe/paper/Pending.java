package net.lunix.lethe.paper;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Players owed something the next time they connect.
 *
 * <p>Held on disk rather than in memory, because the gap between owing and paying is unbounded:
 * a player reincarnates the moment their Purgatory expires, which may be hours before they next
 * log in, and the server can restart many times in between. An in-memory set loses the debt on
 * the first restart -- and the debt here is not cosmetic. A reincarnated player on a hardcore
 * world arrives as a <em>spectator</em>, and if nothing puts them back into survival they cannot
 * play at all.
 */
public final class Pending {

    private Pending() {}

    /** Reincarnated: owed a greeting, and a way out of spectator. */
    private static final Set<UUID> GREETING = new LinkedHashSet<>();
    /** Resurrected: owed their belongings back, and the same way out of spectator. */
    private static final Set<UUID> RESTORE = new LinkedHashSet<>();

    private static File file;

    static void load(LethePaper plugin) {
        file = new File(plugin.getDataFolder(), "pending.yml");
        GREETING.clear();
        RESTORE.clear();
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        read(y.getStringList("greeting"), GREETING);
        read(y.getStringList("restore"), RESTORE);
    }

    private static void read(List<String> from, Set<UUID> into) {
        for (String s : from) {
            try {
                into.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {
                // A hand-edited entry. Losing one is better than refusing to start.
            }
        }
    }

    static void save() {
        if (file == null) return;
        YamlConfiguration y = new YamlConfiguration();
        y.set("greeting", GREETING.stream().map(UUID::toString).toList());
        y.set("restore", RESTORE.stream().map(UUID::toString).toList());
        try {
            y.save(file);
        } catch (IOException e) {
            LethePaper.get().getLogger().warning("Could not save pending list: " + e.getMessage());
        }
    }

    public static void oweGreeting(UUID uuid) { GREETING.add(uuid); save(); }
    public static void oweRestore(UUID uuid) { RESTORE.add(uuid); save(); }

    public static boolean takeGreeting(UUID uuid) {
        boolean had = GREETING.remove(uuid);
        if (had) save();
        return had;
    }

    public static boolean takeRestore(UUID uuid) {
        boolean had = RESTORE.remove(uuid);
        if (had) save();
        return had;
    }
}
