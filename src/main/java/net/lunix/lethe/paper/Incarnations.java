package net.lunix.lethe.paper;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Which life each player is currently living.
 *
 * <p>A player's UUID survives death, so it cannot answer "is this still yours?". Each *life*
 * gets its own id instead, and anything a player claims is stamped with the life that claimed
 * it. When the life ends the id rotates, and every stamp made under it is now stale.
 *
 * <p>Kept outside the data a purge deletes, deliberately -- it has to outlive the wipe in order
 * to make the wipe meaningful.
 */
public final class Incarnations {

    private Incarnations() {}

    private static final Map<UUID, String> LIVES = new HashMap<>();
    private static File file;

    static void load(LethePaper plugin) {
        file = new File(plugin.getDataFolder(), "incarnations.yml");
        LIVES.clear();
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String key : y.getKeys(false)) {
            try {
                LIVES.put(UUID.fromString(key), y.getString(key));
            } catch (IllegalArgumentException ignored) {
                // A hand-edited key. Skipping it means that player reads as unknown, and an
                // unknown player is never acted on.
            }
        }
    }

    static void save() {
        if (file == null) return;
        YamlConfiguration y = new YamlConfiguration();
        LIVES.forEach((k, v) -> y.set(k.toString(), v));
        try {
            y.save(file);
        } catch (IOException e) {
            LethePaper.get().getLogger().warning("Could not save incarnations: " + e.getMessage());
        }
    }

    /** The life this player is living, minting one if they have never been seen. */
    public static String of(UUID player) {
        String life = LIVES.get(player);
        if (life == null) {
            life = UUID.randomUUID().toString();
            LIVES.put(player, life);
            save();
        }
        return life;
    }

    /**
     * The life this player is living, or {@code null} if they have never been seen.
     *
     * <p>Never mints. The sweep uses this: minting inside a sweep would give a brand-new id to
     * every player who happened to be referenced by something in the world, and then every one
     * of those records would read as stale.
     */
    public static String peek(UUID player) {
        return LIVES.get(player);
    }

    /** Ends the current life. Everything stamped with it is now stale. */
    public static void rotate(UUID player) {
        LIVES.put(player, UUID.randomUUID().toString());
        save();
        LethePaper.get().getLogger().info(
                "Rotated incarnation for " + player + " -- anything tamed in the old life is now loose");
    }
}
