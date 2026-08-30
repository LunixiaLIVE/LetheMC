package net.lunix.lethemc;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Deletes every per-UUID file the server keeps for a player, in place.
 *
 * <p>No longer the main path. The normal route is {@link Graveyard}: files are moved out of
 * the live directories at the tail of {@code PlayerList.remove} and hard-deleted from the
 * plot when the grace period ends. This class is the sweeper for the cases where a live file
 * is still sitting there and has to go regardless of the grace period -- an entombment that
 * kept failing, or {@code /lethe admin purge} forcing the issue.
 *
 * <p>Callers must not run it while the player is online: vanilla writes all three files on
 * removal, so a deletion before that point is simply undone.
 *
 * <p>Deliberately never touches whitelist.json or ops.json -- see {@link #NEVER_TOUCH}.
 */
public final class PurgeService {

    /**
     * Files that must survive a purge. Documented here because getting this wrong is
     * catastrophic rather than merely buggy:
     *
     * <ul>
     *   <li>whitelist.json -- on a whitelisted server, removing the entry means the player
     *       can never rejoin. A 6 hour lockout silently becomes permanent, and it presents
     *       as a countdown bug rather than as what it is.</li>
     *   <li>ops.json -- would silently deop any staff member who dies.</li>
     *   <li>banned-players.json -- vanilla bans are unrelated to this mod.</li>
     *   <li>usercache.json -- name/UUID resolution; removing it breaks admin commands.</li>
     * </ul>
     */
    private static final String NEVER_TOUCH = "whitelist.json, ops.json, banned-players.json, usercache.json";

    private PurgeService() {}

    /**
     * @return true if every enabled deletion succeeded. False means the caller should keep
     *         wipePending set and retry later.
     */
    public static boolean purge(MinecraftServer server, UUID uuid, String name) {
        Config cfg = Config.get();
        boolean ok = true;

        if (cfg.wipePlayerData) {
            Path dir = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
            // Delete the backup too. Whether 26.x actually falls back to .dat_old when .dat
            // is missing is unverified -- deleting both is harmless either way and removes
            // the possibility of a silent no-op.
            ok &= delete(dir.resolve(uuid + ".dat"), "playerdata");
            ok &= delete(dir.resolve(uuid + ".dat_old"), "playerdata backup");
        }
        if (cfg.wipeAdvancements) {
            Path dir = server.getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR);
            ok &= delete(dir.resolve(uuid + ".json"), "advancements");
        }
        if (cfg.wipeStats) {
            Path dir = server.getWorldPath(LevelResource.PLAYER_STATS_DIR);
            ok &= delete(dir.resolve(uuid + ".json"), "stats");
        }

        if (ok) {
            LetheMC.LOGGER.info("Purged all data for {} ({})", name, uuid);
        } else {
            LetheMC.LOGGER.warn("Purge for {} ({}) incomplete; will retry", name, uuid);
        }
        return ok;
    }

    /** @return true if the file is gone afterwards (including if it never existed). */
    private static boolean delete(Path path, String label) {
        try {
            if (Files.deleteIfExists(path)) {
                LetheMC.LOGGER.debug("Deleted {} -> {}", label, path.getFileName());
            }
            return true;
        } catch (IOException e) {
            // Plausible on Windows hosts where a handle may still be open.
            LetheMC.LOGGER.error("Could not delete {} at {}", label, path, e);
            return false;
        }
    }
}
