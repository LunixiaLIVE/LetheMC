package net.lunix.lethemc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Which life each player is currently living.
 *
 * <p>A player's UUID does not change when they are reincarnated, so it cannot answer "is this
 * pet still yours?" -- a wolf tamed before you died and one tamed ten minutes ago look
 * identical by owner UUID. Each life therefore gets its own ID, animals are stamped with the
 * ID of the life that claimed them, and a mismatch means the animal belongs to someone who no
 * longer exists.
 *
 * <h2>Why this is a file and not the player's NBT</h2>
 * The sweep runs against loaded animals whose owner may be offline, in another dimension, or --
 * immediately after a reincarnation -- may have no playerdata at all, because the purge deleted
 * it. A lookup that depends on the player being present cannot answer the question at the exact
 * moment it matters most. This file always can.
 *
 * <p>It also gives resurrection the right behaviour for free: the ID is rotated <em>only</em> on
 * reincarnation, so a resurrected player keeps the same life and their animals never notice.
 */
public final class Incarnations {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir()
            .resolve("lethemc").resolve("incarnations.json");
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();

    /** player UUID -> current incarnation ID. */
    private static Map<String, String> current = new HashMap<>();

    private Incarnations() {}

    /**
     * The player's current incarnation, minting one if they have never had it.
     *
     * <p>Minting on demand means an existing world's players quietly acquire an ID on their
     * next login rather than needing a migration.
     */
    public static String of(UUID player) {
        String id = current.get(player.toString());
        if (id == null) {
            id = UUID.randomUUID().toString();
            current.put(player.toString(), id);
            save();
        }
        return id;
    }

    /**
     * The player's current incarnation, or null if we have never seen them.
     *
     * <p>Used by the sweep, which must NOT mint. A null answer means "unknown", and an animal
     * is never released on an unknown -- otherwise every pet belonging to someone who has not
     * logged in since the feature landed would be freed the moment its chunk loaded.
     */
    public static String peek(UUID player) {
        return current.get(player.toString());
    }

    /** A new life. Everything stamped with the old ID stops being theirs. */
    public static String rotate(UUID player) {
        String id = UUID.randomUUID().toString();
        current.put(player.toString(), id);
        save();
        LetheMC.LOGGER.info("Rotated incarnation for {} -- anything tamed in the old life is now loose", player);
        return id;
    }

    // ------------------------------------------------------------------

    public static void load() {
        if (!Files.exists(PATH)) return;
        try (Reader r = Files.newBufferedReader(PATH)) {
            Map<String, String> loaded = GSON.fromJson(r, MAP_TYPE);
            if (loaded != null) current = loaded;
            LetheMC.LOGGER.info("Loaded {} incarnation record(s)", current.size());
        } catch (Exception e) {
            LetheMC.LOGGER.error("Failed to load incarnations; pets will not be released this session", e);
        }
    }

    public static void save() {
        try {
            Files.createDirectories(PATH.getParent());
            try (Writer w = Files.newBufferedWriter(PATH)) {
                GSON.toJson(current, MAP_TYPE, w);
            }
        } catch (IOException e) {
            LetheMC.LOGGER.error("Failed to save incarnations", e);
        }
    }

    public static int count() {
        return current.size();
    }
}
