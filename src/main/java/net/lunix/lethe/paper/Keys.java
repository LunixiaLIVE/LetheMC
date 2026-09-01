package net.lunix.lethe.paper;

import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataHolder;
import org.bukkit.persistence.PersistentDataType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The marks Lethe leaves on things out in the world.
 *
 * <p>The Fabric build adds fields to entities and block entities by mixin. A plugin cannot do
 * that, but it does not need to: Bukkit's {@link PersistentDataContainer} is exactly the same
 * idea with a supported API, and it is stored in the same NBT the mixin fields lived in.
 *
 * <p>The stored values match the mod's format on purpose -- {@code uuid=incarnation} pairs
 * separated by semicolons -- so the two implementations describe the world the same way and can
 * be compared against each other while testing.
 */
public final class Keys {

    private Keys() {}

    /** Which life tamed this animal. */
    public static NamespacedKey incarnation;
    /** uuid=incarnation pairs: which life earned a fox's trust, a vault's reward, a villager's opinion. */
    public static NamespacedKey stamps;
    /** UUIDs a villager has actually dealt with, as opposed to merely heard about. */
    public static NamespacedKey direct;

    static void init(LethePaper plugin) {
        incarnation = new NamespacedKey(plugin, "incarnation");
        stamps      = new NamespacedKey(plugin, "stamps");
        direct      = new NamespacedKey(plugin, "direct");
    }

    // ------------------------------------------------------------------
    // single value
    // ------------------------------------------------------------------

    public static String get(PersistentDataHolder holder, NamespacedKey key) {
        return holder.getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    public static void set(PersistentDataHolder holder, NamespacedKey key, String value) {
        holder.getPersistentDataContainer().set(key, PersistentDataType.STRING, value);
    }

    // ------------------------------------------------------------------
    // uuid -> incarnation maps
    // ------------------------------------------------------------------

    /** Reads the stamp map. Returns a mutable map; empty when absent. */
    public static Map<UUID, String> stamps(PersistentDataHolder holder) {
        return decodeMap(get(holder, stamps));
    }

    public static void stamps(PersistentDataHolder holder, Map<UUID, String> map) {
        if (map.isEmpty()) {
            holder.getPersistentDataContainer().remove(stamps);
        } else {
            set(holder, stamps, encodeMap(map));
        }
    }

    public static Map<UUID, String> decodeMap(String raw) {
        Map<UUID, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) return out;
        for (String pair : raw.split(";")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            try {
                out.put(UUID.fromString(pair.substring(0, eq)), pair.substring(eq + 1));
            } catch (IllegalArgumentException ignored) {
                // Hand-edited or corrupted. Dropping the entry leaves the record intact, which
                // is the harmless direction to fail in for every caller.
            }
        }
        return out;
    }

    public static String encodeMap(Map<UUID, String> map) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<UUID, String> e : map.entrySet()) {
            if (sb.length() > 0) sb.append(';');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // plain uuid sets
    // ------------------------------------------------------------------

    public static Set<UUID> uuids(PersistentDataHolder holder, NamespacedKey key) {
        Set<UUID> out = new java.util.LinkedHashSet<>();
        String raw = get(holder, key);
        if (raw == null || raw.isBlank()) return out;
        for (String part : raw.split(";")) {
            try {
                out.add(UUID.fromString(part));
            } catch (IllegalArgumentException ignored) {
                // See above: sparing the record is the safe direction.
            }
        }
        return out;
    }

    public static void uuids(PersistentDataHolder holder, NamespacedKey key, Set<UUID> set) {
        if (set.isEmpty()) {
            holder.getPersistentDataContainer().remove(key);
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (UUID id : set) {
            if (sb.length() > 0) sb.append(';');
            sb.append(id);
        }
        set(holder, key, sb.toString());
    }

    // ------------------------------------------------------------------

    /**
     * Whether this player's entry belongs to a life that has ended.
     *
     * <p>Two guards, both deliberately answering "no": an entry with <b>no stamp</b> was made
     * before Lethe was installed, and acting on it would wipe every animal and vault on the
     * server the first time the sweep ran; an <b>unknown player</b> has nothing to compare
     * against.
     */
    public static boolean isStale(UUID player, Map<UUID, String> stampMap) {
        String stamp = stampMap.get(player);
        if (stamp == null) return false;
        String living = Incarnations.peek(player);
        if (living == null) return false;
        return !living.equals(stamp);
    }
}
