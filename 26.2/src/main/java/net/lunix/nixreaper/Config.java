package net.lunix.nixreaper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * nixReaper configuration.
 *
 * <p>Enforces the design invariant from DESIGN section 4.4:
 * {@code lockout.minutes > wipe.graceMinutes}. Both are in minutes, so the default 5 minute
 * grace yields a 6 minute minimum lockout. This guarantees a hard delete can never still be
 * pending when the lockout expires.
 */
public final class Config {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir()
            .resolve("nixreaper").resolve("config.json");

    // --- wipe ---
    public boolean wipePlayerData = true;
    public boolean wipeAdvancements = true;
    public boolean wipeStats = true;

    /**
     * How long, in minutes, an entombed player stays restorable before the hard delete.
     *
     * <p>Minutes to match {@code lockout.minutes}, so the two values that must be compared
     * (§4.4) are in the same unit and an admin can check the rule by eye.
     *
     * <p><b>No alias for the older {@code wipeGraceSeconds}/{@code wipeDelaySeconds} keys.</b>
     * This is a unit change, not a rename: silently reading an existing {@code 300} would turn
     * a five minute grace into a five hour one. An old config loses this value and takes the
     * default instead, which is the safe direction to be wrong in.
     */
    public int wipeGraceMinutes = 5;

    /**
     * Hold an exclusive OS lock on graveyard files for the whole grace period, so nothing
     * else can open them and block the hard delete. Mandatory on Windows, advisory on Linux
     * -- see {@link Graveyard} for why that difference does not undermine the design.
     */
    public boolean wipeLockFiles = true;

    // --- what a reincarnation frees ---
    // Grouped by how Minecraft models ownership, because that is what actually differs:
    // TamableAnimal (sit/tame flags), AbstractHorse (its own tame + inventory), and Fox
    // (two independent trust slots). One toggle each, since a server may well want loyal
    // horses but forgetful wolves.

    /** Wolves, cats, parrots. */
    public boolean wipePets = true;

    /** Horses, donkeys, mules, llamas, camels -- including whatever is in their chest. */
    public boolean wipeLivestock = true;

    /** Foxes, which trust rather than obey. */
    public boolean wipeFoxes = true;

    /**
     * How often to look for animals belonging to an ended life, in ticks.
     *
     * <p>Deliberately frequent. The exploit is short-range: park a loaded donkey at spawn,
     * die, and collect it on the walk back. A slow sweep leaves a window where that works.
     */
    public int petsCheckIntervalTicks = 20;

    // --- lockout ---
    public int lockoutMinutes = 360;
    public int lockoutDeathScreenSeconds = 15;

    // --- messages ---
    // Does NOT claim the data is gone. At the moment this is shown the player has not even
    // been entombed yet, and stays fully restorable for the whole grace period -- telling
    // them otherwise is the same lie the rejoin message used to tell (DESIGN 10, bug 3).
    // %grace_line% reports whichever is actually true.
    public String messageDeath =
            "§c☠ You have died.\n§7%death_reason%\n\n"
            + "§fYou are in §5Purgatory§f for §e%time_remaining%§f.\n\n"
            + "%grace_line%";
    // A duration, never %unlock_time%: an absolute timestamp is rendered in the server's
    // timezone, which is not the player's, and nothing on the screen says so.
    public String messageRejoin =
            "§5☠ You are in Purgatory\n\n"
            + "§fYou may return in §e%time_remaining%\n\n"
            + "%grace_line%";

    /**
     * Shown once, in chat, to a player returning from Purgatory with nothing.
     * A random line from config/nixreaper/reincarnation.txt is appended.
     */
    public String messageReincarnation =
            "§5You have been reincarnated. §7Nothing of your old life remains.";

    /**
     * The single line of the death screen a server can control (see ServerPlayerMixin).
     * Must stay ONE line -- the score sits 15px below it and there is no wrapping.
     * Set empty to leave vanilla's death message alone.
     */
    // "was sent" rather than "sent": vanilla death messages are all past tense and vary a
    // lot ("drowned", "was slain by", "fell from a high place"), and the full verb is the
    // only phrasing that reads correctly after every one of them.
    public String messageDeathScreen = "%death_reason% §7& was sent to §5Purgatory";

    // --- permissions ---
    // Two separate questions that used to share one answer. Welding them together forced a
    // false choice: an admin could either run /nr admin OR be able to die, never both -- so on
    // a server built around death having stakes, the person running it was silently exempt.
    //
    // Also note bypass and admin have different legal ranges. "Nobody is exempt" is a sensible
    // and desirable setting; "nobody can administer" is not, so -1 is only offered for bypass.

    /**
     * Who is exempt from dying.
     *
     * <p>{@code -1} nobody (the default -- everyone faces Purgatory, including the owner),
     * {@code 0} everyone (equivalent to switching the mod off), {@code 1-4} that op permission
     * and above.
     *
     * <p>Defaults to -1 deliberately. An existing config already has this key written out and
     * keeps whatever it says, so upgrading servers are unaffected; only fresh installs get the
     * stricter default.
     */
    public int bypassPermissionLevel = -1;

    /**
     * Who can run {@code /nr admin ...}. {@code 0-4}, default 4.
     *
     * <p>With the defaults you can resurrect other people and still die yourself, which is what
     * makes the self-pardon guard meaningful -- previously anyone who could pardon was immune
     * to ever needing it.
     */
    public int adminPermissionLevel = 4;

    private static Config INSTANCE = new Config();

    public static Config get() {
        return INSTANCE;
    }

    /** Minimum legal value for lockoutMinutes given the current grace period. */
    public int minimumLockoutMinutes() {
        return wipeGraceMinutes + 1;
    }

    /** Maximum legal value for wipeGraceMinutes given the current lockout. */
    public int maximumWipeGraceMinutes() {
        return lockoutMinutes - 1;
    }

    /** @return null if valid, else a human-readable reason. */
    public String validate() {
        if (lockoutMinutes <= wipeGraceMinutes) {
            return "lockout.minutes must be greater than wipe.graceMinutes ("
                    + wipeGraceMinutes + "). Try " + minimumLockoutMinutes() + " or higher.";
        }
        if (lockoutDeathScreenSeconds < 0) return "lockout.deathScreenSeconds cannot be negative.";
        if (wipeGraceMinutes < 1) return "wipe.graceMinutes must be at least 1.";
        if (petsCheckIntervalTicks < 1) return "wipe.petsCheckIntervalTicks must be at least 1.";
        if (bypassPermissionLevel < -1 || bypassPermissionLevel > 4) {
            return "bypass.permissionLevel must be -1 (nobody exempt), 0 (everyone) or 1-4.";
        }
        if (adminPermissionLevel < 0 || adminPermissionLevel > 4) {
            return "admin.permissionLevel must be 0-4.";
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Key-based access, used by /nr admin config get|set|list
    // ------------------------------------------------------------------

    public Map<String, String> asMap() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("wipe.playerData", String.valueOf(wipePlayerData));
        m.put("wipe.advancements", String.valueOf(wipeAdvancements));
        m.put("wipe.stats", String.valueOf(wipeStats));
        m.put("wipe.graceMinutes", String.valueOf(wipeGraceMinutes));
        m.put("wipe.lockFiles", String.valueOf(wipeLockFiles));
        m.put("wipe.pets", String.valueOf(wipePets));
        m.put("wipe.livestock", String.valueOf(wipeLivestock));
        m.put("wipe.foxes", String.valueOf(wipeFoxes));
        m.put("wipe.petsCheckIntervalTicks", String.valueOf(petsCheckIntervalTicks));
        m.put("lockout.minutes", String.valueOf(lockoutMinutes));
        m.put("lockout.deathScreenSeconds", String.valueOf(lockoutDeathScreenSeconds));
        m.put("message.death", messageDeath);
        m.put("message.rejoin", messageRejoin);
        m.put("message.deathScreen", messageDeathScreen);
        m.put("message.reincarnation", messageReincarnation);
        m.put("bypass.permissionLevel", String.valueOf(bypassPermissionLevel));
        m.put("admin.permissionLevel", String.valueOf(adminPermissionLevel));
        return m;
    }

    public String getKey(String key) {
        return asMap().get(key);
    }

    /**
     * Applies a key change, validating the result before committing.
     * Validation runs on a copy so a rejected change leaves config untouched --
     * this is what enforces the DESIGN 4.4 constraint in BOTH directions
     * (lowering lockout.minutes and raising wipe.delaySeconds each break it).
     *
     * @return null on success, else the rejection reason.
     */
    public String setKey(String key, String value) {
        Config c = copy();
        try {
            switch (key) {
                case "wipe.playerData" -> c.wipePlayerData = parseBool(value);
                case "wipe.advancements" -> c.wipeAdvancements = parseBool(value);
                case "wipe.stats" -> c.wipeStats = parseBool(value);
                case "wipe.graceMinutes" -> c.wipeGraceMinutes = Integer.parseInt(value);
                case "wipe.lockFiles" -> c.wipeLockFiles = parseBool(value);
                case "wipe.pets" -> c.wipePets = parseBool(value);
                case "wipe.livestock" -> c.wipeLivestock = parseBool(value);
                case "wipe.foxes" -> c.wipeFoxes = parseBool(value);
                case "wipe.petsCheckIntervalTicks" -> c.petsCheckIntervalTicks = Integer.parseInt(value);
                case "lockout.minutes" -> c.lockoutMinutes = Integer.parseInt(value);
                case "lockout.deathScreenSeconds" -> c.lockoutDeathScreenSeconds = Integer.parseInt(value);
                case "message.death" -> c.messageDeath = value;
                case "message.rejoin" -> c.messageRejoin = value;
                case "message.deathScreen" -> c.messageDeathScreen = value;
                case "message.reincarnation" -> c.messageReincarnation = value;
                case "bypass.permissionLevel" -> c.bypassPermissionLevel = Integer.parseInt(value);
                case "admin.permissionLevel" -> c.adminPermissionLevel = Integer.parseInt(value);
                default -> {
                    return "Unknown key: " + key;
                }
            }
        } catch (NumberFormatException e) {
            return "'" + value + "' is not a valid number for " + key + ".";
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }

        String problem = c.validate();
        if (problem != null) return problem;

        c.copyInto(this);
        save();
        return null;
    }

    private static boolean parseBool(String v) {
        if (v.equalsIgnoreCase("true")) return true;
        if (v.equalsIgnoreCase("false")) return false;
        throw new IllegalArgumentException("Expected true or false, got '" + v + "'.");
    }

    private Config copy() {
        Config c = new Config();
        copyInto(c);
        return c;
    }

    private void copyInto(Config c) {
        c.wipePlayerData = wipePlayerData;
        c.wipeAdvancements = wipeAdvancements;
        c.wipeStats = wipeStats;
        c.wipeGraceMinutes = wipeGraceMinutes;
        c.wipeLockFiles = wipeLockFiles;
        c.wipePets = wipePets;
        c.wipeLivestock = wipeLivestock;
        c.wipeFoxes = wipeFoxes;
        c.petsCheckIntervalTicks = petsCheckIntervalTicks;
        c.lockoutMinutes = lockoutMinutes;
        c.lockoutDeathScreenSeconds = lockoutDeathScreenSeconds;
        c.messageDeath = messageDeath;
        c.messageRejoin = messageRejoin;
        c.messageDeathScreen = messageDeathScreen;
        c.messageReincarnation = messageReincarnation;
        c.bypassPermissionLevel = bypassPermissionLevel;
        c.adminPermissionLevel = adminPermissionLevel;
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    public static void load() {
        Config loaded = new Config();
        if (Files.exists(PATH)) {
            try (Reader r = Files.newBufferedReader(PATH)) {
                Config fromDisk = GSON.fromJson(r, Config.class);
                if (fromDisk != null) loaded = fromDisk;
            } catch (Exception e) {
                NixReaper.LOGGER.error("Failed to read config, using defaults", e);
                loaded = new Config();
            }
        }

        String problem = loaded.validate();
        if (problem != null) {
            // Clamp rather than refuse to boot -- a server that won't start is worse
            // than one running a corrected value, but say so loudly.
            NixReaper.LOGGER.error("INVALID CONFIG: {}", problem);
            int corrected = loaded.minimumLockoutMinutes();
            NixReaper.LOGGER.error("Clamping lockout.minutes {} -> {}", loaded.lockoutMinutes, corrected);
            loaded.lockoutMinutes = corrected;
        }

        INSTANCE = loaded;
        save();
    }

    public static void save() {
        try {
            Files.createDirectories(PATH.getParent());
            try (Writer w = Files.newBufferedWriter(PATH)) {
                GSON.toJson(INSTANCE, w);
            }
        } catch (IOException e) {
            NixReaper.LOGGER.error("Failed to save config", e);
        }
    }
}
