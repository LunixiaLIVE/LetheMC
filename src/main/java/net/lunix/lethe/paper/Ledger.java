package net.lunix.lethe.paper;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Who is in Purgatory, and until when.
 *
 * <p>Deliberately not the vanilla ban list. A ban is an administrative act with its own reasons
 * and its own audit trail, and borrowing it would mean Lethe's timers fighting an admin's real
 * bans over the same records.
 */
public final class Ledger {

    private Ledger() {}

    /** Death screen is up; the Purgatory clock has not started yet. */
    public static final String STATE_DYING = "DYING";
    /** Clock running, belongings entombed or already gone. */
    public static final String STATE_LOCKED = "LOCKED";
    /** Resurrected and on the way back; the ward lifts immediately. */
    public static final String STATE_PARDONED = "PARDONED";

    public static final class Entry {
        public String name = "";
        public String state = STATE_DYING;
        public long deathAt;
        /** Epoch millis the Purgatory clock started; 0 while DYING. */
        public long purgatoryStartsAt;
        /** Snapshotted at death so later config edits never re-time someone already in it. */
        public long durationMillis;
        public long graceMillis;
        /** True while the belongings still exist and a resurrection would restore them. */
        public boolean restorable;

        public long unlockAt() {
            return purgatoryStartsAt <= 0 ? Long.MAX_VALUE : purgatoryStartsAt + durationMillis;
        }

        public long remainingMillis(long now) {
            if (purgatoryStartsAt <= 0) return durationMillis;
            return Math.max(0, unlockAt() - now);
        }

        public long graceRemainingMillis(long now) {
            return Math.max(0, (deathAt + graceMillis) - now);
        }

        public boolean expired(long now) {
            return purgatoryStartsAt > 0 && now >= unlockAt();
        }
    }

    private static final Map<UUID, Entry> ENTRIES = new LinkedHashMap<>();
    private static File file;

    static void load(LethePaper plugin) {
        file = new File(plugin.getDataFolder(), "ledger.yml");
        ENTRIES.clear();
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String key : y.getKeys(false)) {
            ConfigurationSection sec = y.getConfigurationSection(key);
            if (sec == null) continue;
            try {
                Entry e = new Entry();
                e.name = sec.getString("name", "");
                e.state = sec.getString("state", STATE_LOCKED);
                e.deathAt = sec.getLong("deathAt");
                e.purgatoryStartsAt = sec.getLong("purgatoryStartsAt");
                e.durationMillis = sec.getLong("durationMillis");
                e.graceMillis = sec.getLong("graceMillis");
                e.restorable = sec.getBoolean("restorable");
                ENTRIES.put(UUID.fromString(key), e);
            } catch (IllegalArgumentException ignored) {
                // Unreadable key: better to forget one record than to refuse to start.
            }
        }
    }

    static void save() {
        if (file == null) return;
        YamlConfiguration y = new YamlConfiguration();
        ENTRIES.forEach((uuid, e) -> {
            String k = uuid.toString();
            y.set(k + ".name", e.name);
            y.set(k + ".state", e.state);
            y.set(k + ".deathAt", e.deathAt);
            y.set(k + ".purgatoryStartsAt", e.purgatoryStartsAt);
            y.set(k + ".durationMillis", e.durationMillis);
            y.set(k + ".graceMillis", e.graceMillis);
            y.set(k + ".restorable", e.restorable);
        });
        try {
            y.save(file);
        } catch (IOException ex) {
            LethePaper.get().getLogger().warning("Could not save ledger: " + ex.getMessage());
        }
    }

    public static Entry get(UUID uuid) { return ENTRIES.get(uuid); }
    public static boolean has(UUID uuid) { return ENTRIES.containsKey(uuid); }
    public static void put(UUID uuid, Entry e) { ENTRIES.put(uuid, e); save(); }
    public static void remove(UUID uuid) { ENTRIES.remove(uuid); save(); }
    public static boolean isEmpty() { return ENTRIES.isEmpty(); }
    public static List<UUID> uuids() { return new ArrayList<>(ENTRIES.keySet()); }

    public static UUID findByName(String name) {
        for (Map.Entry<UUID, Entry> e : ENTRIES.entrySet()) {
            if (e.getValue().name.equalsIgnoreCase(name)) return e.getKey();
        }
        return null;
    }
}
