package net.lunix.nixreaper;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Holding pen for a dead player's files between entombment and the hard delete.
 *
 * <p>The player's data leaves the live world directories the instant vanilla has finished
 * flushing it (DESIGN 4.4). It is not deleted then -- it is <em>moved</em> here, and the
 * grace period (spec H) runs against this copy instead. That splits the two jobs the old
 * 5 minute delay was doing badly at once:
 *
 * <ul>
 *   <li>The server is clean immediately. Nothing can read, back up, restore or resurrect a
 *       dead player's inventory out of {@code playerdata/}, because it is not there.</li>
 *   <li>{@code /nr admin pardon} still restores everything for the full grace period, which
 *       is the whole point of having one -- deaths caused by lag or a bad chunk load.</li>
 * </ul>
 *
 * <h2>Why a move rather than a copy-then-delete</h2>
 * A move within one filesystem is a rename: atomic, instant, and it cannot leave a second
 * copy behind if the server dies halfway. The graveyard therefore lives <em>inside the world
 * folder</em> rather than under {@code config/}, so it is guaranteed to share a filesystem
 * with {@code playerdata/} no matter how the host has mounted things. It also means a world
 * backup captures the graveyard consistently with the world it belongs to.
 *
 * <h2>File locks</h2>
 * After the move each file is held open under an exclusive {@link FileLock} for the whole
 * grace period, so the hard delete at the end cannot be blocked by something else having
 * opened the file in the meantime.
 *
 * <p><b>This is genuinely mandatory on Windows and merely advisory on Linux.</b> Windows
 * enforces the lock in the kernel: another process trying to open the file gets a sharing
 * violation, which is exactly the guarantee we want. POSIX locks only bind processes that
 * ask for locks themselves, so a stray rsync or backup job on a Linux host will read
 * straight through one. The real protection on Linux is the move: the file is no longer at
 * any path another process would think to look at. The lock is defence in depth, not the
 * defence -- so a lock that cannot be taken is logged and shrugged off, never fatal.
 */
public final class Graveyard {

    /** Open channel + lock we are holding for one entombed file. */
    private record Held(FileChannel channel, FileLock lock) {}

    /** Locks are process state, not disk state -- rebuilt from the ledger on boot. */
    private static final Map<UUID, List<Held>> HELD = new HashMap<>();

    private Graveyard() {}

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    public static Path root(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("nixreaper").resolve("graveyard");
    }

    public static Path plot(MinecraftServer server, UUID uuid) {
        return root(server).resolve(uuid.toString());
    }

    /**
     * Where a graveyard file came from, and therefore where a pardon puts it back.
     *
     * <p>Deliberately NOT config-driven. Entombment consults {@code wipe.*} to decide what to
     * take, but restore has to put back whatever is actually sitting in the plot -- otherwise
     * flipping {@code wipe.stats} to false between a death and a pardon would strand that
     * player's stats in the graveyard forever.
     *
     * @return null for an unrecognised filename, which is then left alone.
     */
    private static Path liveDestination(MinecraftServer server, UUID uuid, String graveName) {
        return switch (graveName) {
            case "playerdata.dat" ->
                    server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(uuid + ".dat");
            case "playerdata.dat_old" ->
                    server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(uuid + ".dat_old");
            case "advancements.json" ->
                    server.getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR).resolve(uuid + ".json");
            case "stats.json" ->
                    server.getWorldPath(LevelResource.PLAYER_STATS_DIR).resolve(uuid + ".json");
            default -> null;
        };
    }

    /** The live files entombment should take, per current config. */
    private static Map<String, Path> sources(MinecraftServer server, UUID uuid) {
        Config cfg = Config.get();
        Map<String, Path> out = new HashMap<>();
        if (cfg.wipePlayerData) {
            Path dir = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
            out.put("playerdata.dat", dir.resolve(uuid + ".dat"));
            // Vanilla's Util.safeReplaceFile rotates .dat -> .dat_old on every save, so the
            // backup holds the state from one save ago. Leaving it behind would leave a
            // near-complete copy of the inventory sitting in the world folder.
            out.put("playerdata.dat_old", dir.resolve(uuid + ".dat_old"));
        }
        if (cfg.wipeAdvancements) {
            out.put("advancements.json",
                    server.getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR).resolve(uuid + ".json"));
        }
        if (cfg.wipeStats) {
            out.put("stats.json",
                    server.getWorldPath(LevelResource.PLAYER_STATS_DIR).resolve(uuid + ".json"));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Entomb
    // ------------------------------------------------------------------

    /**
     * Moves every enabled live file into this player's plot and locks what landed.
     *
     * <p>Must only be called once vanilla has finished writing, i.e. from the tail of
     * {@code PlayerList.remove}. Called earlier it would move a file vanilla is about to
     * rewrite, and the rewritten copy would survive in the live folder.
     *
     * @return true if every enabled file is now out of the live directories. False means the
     *         caller must keep the obligation open and try again -- a partial entombment is
     *         the dangerous outcome, because the files that did move look like success.
     */
    public static boolean entomb(MinecraftServer server, UUID uuid, String name) {
        Path plot = plot(server, uuid);
        try {
            Files.createDirectories(plot);
        } catch (IOException e) {
            NixReaper.LOGGER.error("Could not create graveyard plot for {} at {}", name, plot, e);
            return false;
        }

        boolean ok = true;
        int moved = 0;
        for (Map.Entry<String, Path> src : sources(server, uuid).entrySet()) {
            Path from = src.getValue();
            if (!Files.exists(from)) continue; // never existed, or a previous attempt took it
            if (move(from, plot.resolve(src.getKey()))) {
                moved++;
            } else {
                ok = false;
            }
        }

        if (ok) {
            lockPlot(server, uuid, name);
            NixReaper.LOGGER.info("Entombed {} file(s) for {} ({}) -- live data is clear", moved, name, uuid);
        } else {
            NixReaper.LOGGER.warn("Entombment for {} ({}) incomplete; will retry", name, uuid);
        }
        return ok;
    }

    /** Rename if the filesystem allows it, otherwise settle for a plain move. */
    private static boolean move(Path from, Path to) {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (AtomicMoveNotSupportedException e) {
            try {
                Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
                return true;
            } catch (IOException e2) {
                NixReaper.LOGGER.error("Could not move {} -> {}", from, to, e2);
                return false;
            }
        } catch (IOException e) {
            // Windows: something else holds the file open. Retrying is the right move --
            // whatever it is (antivirus, a backup pass) is almost always transient.
            NixReaper.LOGGER.error("Could not move {} -> {}", from, to, e);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Locking
    // ------------------------------------------------------------------

    /** Takes an exclusive lock on every file in the plot. Best effort by design. */
    public static void lockPlot(MinecraftServer server, UUID uuid, String name) {
        if (!Config.get().wipeLockFiles) return;
        if (HELD.containsKey(uuid)) return; // already holding

        List<Held> held = new ArrayList<>();
        for (Path file : list(server, uuid)) {
            try {
                FileChannel ch = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE);
                FileLock lock = ch.tryLock();
                if (lock == null) {
                    ch.close();
                    NixReaper.LOGGER.warn("Could not lock {} for {} -- another process holds it",
                            file.getFileName(), name);
                    continue;
                }
                held.add(new Held(ch, lock));
            } catch (OverlappingFileLockException e) {
                NixReaper.LOGGER.warn("Already locked within this JVM: {}", file.getFileName());
            } catch (IOException e) {
                NixReaper.LOGGER.warn("Could not lock {} for {}: {}", file.getFileName(), name, e.toString());
            }
        }
        if (!held.isEmpty()) HELD.put(uuid, held);
    }

    /**
     * Drops our locks so the files can be moved or deleted.
     *
     * <p>Not optional on Windows: a file this process holds open cannot be deleted, so
     * skipping this turns every hard delete into an AccessDeniedException.
     */
    public static void unlockPlot(UUID uuid) {
        List<Held> held = HELD.remove(uuid);
        if (held == null) return;
        for (Held h : held) {
            try {
                if (h.lock().isValid()) h.lock().release();
            } catch (IOException ignored) {
                // Releasing a lock on a channel we are about to close is not worth reporting.
            }
            try {
                h.channel().close();
            } catch (IOException ignored) {
                // Same.
            }
        }
    }

    /** Server shutdown -- hand every lock back to the OS rather than relying on process exit. */
    public static void releaseAll() {
        for (UUID uuid : new ArrayList<>(HELD.keySet())) {
            unlockPlot(uuid);
        }
    }

    public static boolean isLocked(UUID uuid) {
        return HELD.containsKey(uuid);
    }

    // ------------------------------------------------------------------
    // Restore / destroy
    // ------------------------------------------------------------------

    /**
     * Puts a pardoned player's files back where vanilla expects them (spec J).
     *
     * <p>Safe because a pardon can only reach an offline player: the lockout is still running,
     * so nothing has recreated the live files underneath us.
     */
    public static boolean restore(MinecraftServer server, UUID uuid, String name) {
        unlockPlot(uuid);

        boolean ok = true;
        int restored = 0;
        for (Path file : list(server, uuid)) {
            Path dest = liveDestination(server, uuid, file.getFileName().toString());
            if (dest == null) continue;
            try {
                Files.createDirectories(dest.getParent());
            } catch (IOException e) {
                NixReaper.LOGGER.error("Could not recreate {}", dest.getParent(), e);
                ok = false;
                continue;
            }
            if (move(file, dest)) {
                restored++;
            } else {
                ok = false;
            }
        }

        if (ok) {
            deletePlotDir(server, uuid);
            NixReaper.LOGGER.info("Restored {} file(s) for {} ({})", restored, name, uuid);
        } else {
            // Leave the plot intact so a second pardon can retry rather than losing the lot.
            NixReaper.LOGGER.error("Restore for {} ({}) incomplete -- plot left in place", name, uuid);
            lockPlot(server, uuid, name);
        }
        return ok;
    }

    /** The hard delete at the end of the grace period. Unrecoverable, as intended. */
    public static boolean destroy(MinecraftServer server, UUID uuid, String name) {
        unlockPlot(uuid);

        boolean ok = true;
        for (Path file : list(server, uuid)) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                NixReaper.LOGGER.error("Could not delete {}", file, e);
                ok = false;
            }
        }

        if (ok) {
            deletePlotDir(server, uuid);
            NixReaper.LOGGER.info("Purged graveyard plot for {} ({})", name, uuid);
        } else {
            NixReaper.LOGGER.warn("Purge for {} ({}) incomplete; will retry", name, uuid);
            lockPlot(server, uuid, name);
        }
        return ok;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Whatever is actually in the plot right now. Empty if there is no plot. */
    public static List<Path> list(MinecraftServer server, UUID uuid) {
        Path plot = plot(server, uuid);
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(plot)) return out;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(plot)) {
            for (Path p : stream) {
                if (Files.isRegularFile(p)) out.add(p);
            }
        } catch (IOException e) {
            NixReaper.LOGGER.error("Could not read graveyard plot {}", plot, e);
        }
        return out;
    }

    private static void deletePlotDir(MinecraftServer server, UUID uuid) {
        try {
            Files.deleteIfExists(plot(server, uuid));
        } catch (IOException e) {
            // An empty directory left behind is cosmetic; do not fail the operation over it.
            NixReaper.LOGGER.debug("Left empty plot directory for {}", uuid);
        }
    }
}
