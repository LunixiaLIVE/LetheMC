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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-UUID lockout records. This is the mod's own ban list -- deliberately NOT the vanilla
 * one (see DESIGN 3.4): the mod needs somewhere to keep wipePending / purgeAt / state
 * anyway, pardon has to be able to cancel a scheduled purge, and the rejoin message wants a
 * live countdown rather than vanilla's fixed expiry timestamp.
 *
 * <p>Persisted to disk on every mutation. Memory-only would make a restart a free pardon.
 */
public final class Ledger {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir()
            .resolve("lethemc").resolve("ledger.json");
    private static final Type MAP_TYPE = new TypeToken<Map<String, Entry>>() {}.getType();

    /** Death screen is up; the lockout clock has NOT started yet. */
    public static final String STATE_DYING = "DYING";
    /** Clock running. */
    public static final String STATE_LOCKED = "LOCKED";
    /**
     * Pardoned, files handed back, but the player has not respawned yet.
     *
     * <p>The entry has to outlive the pardon. The player's restored data still has them
     * dead, so they rejoin to a death screen -- and vanilla's respawn discards the inventory
     * and XP unless keepInventory is on at that exact moment. This state is what tells the
     * mod to force it for that one respawn; the entry is dropped immediately afterwards.
     */
    public static final String STATE_PARDONED = "PARDONED";

    /** Where a player's files physically are. Drives every user-facing message. */
    public enum DataState {
        /** Still in the live world directories -- nothing has been taken. */
        LIVE,
        /** Moved to the graveyard; a pardon puts them back. */
        ENTOMBED,
        /** Hard-deleted. Gone for good. */
        ERASED
    }

    private static Map<String, Entry> entries = new HashMap<>();

    public static final class Entry {
        public String name = "";
        public String state = STATE_DYING;
        public long deathAt;
        /** Epoch millis the lockout clock started; 0 while DYING. */
        public long lockoutStartsAt;
        /** Duration snapshotted at death so later config edits don't re-time existing lockouts. */
        public long durationMillis;
        /** True from disconnect until the hard delete succeeds. The obligation, not the timer. */
        public boolean wipePending;
        /** Epoch millis the files landed in the graveyard; 0 while they are still live. */
        public long graveyardAt;
        /** Epoch millis to retry a failed entombment; 0 if none is owed. */
        public long entombRetryAt;
        /** Epoch millis the hard delete should run; 0 until entombed. */
        public long purgeAt;
        /** Grace snapshotted at death, for the same reason as durationMillis. */
        public long graceMillis;
        public String deathReason = "";

        public long unlockAt() {
            return lockoutStartsAt <= 0 ? Long.MAX_VALUE : lockoutStartsAt + durationMillis;
        }

        public long remainingMillis(long now) {
            if (lockoutStartsAt <= 0) return durationMillis;
            return Math.max(0L, unlockAt() - now);
        }

        public boolean expired(long now) {
            return lockoutStartsAt > 0 && now >= unlockAt();
        }

        /** Files are out of the live world directories and sitting in the graveyard. */
        public boolean entombed() {
            return graveyardAt > 0;
        }

        /**
         * Where this player's data actually is right now.
         *
         * <p>The single source of truth for anything user-facing. Every display bug found in
         * testing came from asking a timestamp instead: {@code graveyardAt > 0} is never
         * cleared by the purge, so status kept reporting "in graveyard" for files that had
         * been deleted, and the rejoin message flipped to "permanently erased" the moment
         * {@code purgeAt} passed rather than when the delete actually succeeded -- which on a
         * paused server was never.
         */
        public DataState dataState() {
            if (STATE_DYING.equals(state)) return DataState.LIVE; // nothing taken yet
            if (wipePending) return entombed() ? DataState.ENTOMBED : DataState.LIVE;
            return graveyardAt > 0 ? DataState.ERASED : DataState.LIVE;
        }

        /**
         * True while a pardon would still hand everything back.
         *
         * <p>Includes the {@link DataState#LIVE} case: if entombment has not run yet the
         * files are still exactly where vanilla left them, so a pardon restores them by doing
         * nothing at all. Same outcome from the player's point of view, so it counts.
         */
        public boolean restorable() {
            // Anything not yet hard-deleted can be handed back -- including files that never
            // left the live folder, where cancelling IS the restoration.
            return dataState() != DataState.ERASED;
        }

        /** Millis until the hard delete. 0 once it has happened or is overdue. */
        public long graceRemainingMillis(long now) {
            if (STATE_DYING.equals(state)) return graceMillis; // clock has not started
            if (!wipePending) return 0L;
            if (purgeAt <= 0) return graceMillis; // entombment still pending; full grace ahead
            return Math.max(0L, purgeAt - now);
        }
    }

    // ------------------------------------------------------------------

    public static Entry get(UUID uuid) {
        return entries.get(uuid.toString());
    }

    public static boolean has(UUID uuid) {
        return entries.containsKey(uuid.toString());
    }

    public static void put(UUID uuid, Entry e) {
        entries.put(uuid.toString(), e);
        save();
    }

    public static void remove(UUID uuid) {
        entries.remove(uuid.toString());
        save();
    }

    public static Map<String, Entry> all() {
        return entries;
    }

    /** Snapshot of the keys, safe to iterate while mutating. */
    public static List<UUID> uuids() {
        List<UUID> out = new ArrayList<>();
        for (String k : new ArrayList<>(entries.keySet())) {
            try {
                out.add(UUID.fromString(k));
            } catch (IllegalArgumentException ignored) {
                // Malformed key from a hand-edited file; skip rather than crash.
            }
        }
        return out;
    }

    public static UUID findByName(String name) {
        for (Map.Entry<String, Entry> e : entries.entrySet()) {
            if (e.getValue().name.equalsIgnoreCase(name)) {
                try {
                    return UUID.fromString(e.getKey());
                } catch (IllegalArgumentException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------

    public static void load() {
        if (!Files.exists(PATH)) return;
        try (Reader r = Files.newBufferedReader(PATH)) {
            Map<String, Entry> loaded = GSON.fromJson(r, MAP_TYPE);
            if (loaded != null) entries = loaded;
        } catch (Exception e) {
            LetheMC.LOGGER.error("Failed to load ledger", e);
        }
    }

    public static void save() {
        try {
            Files.createDirectories(PATH.getParent());
            try (Writer w = Files.newBufferedWriter(PATH)) {
                GSON.toJson(entries, MAP_TYPE, w);
            }
        } catch (IOException e) {
            LetheMC.LOGGER.error("Failed to save ledger", e);
        }
    }
}
