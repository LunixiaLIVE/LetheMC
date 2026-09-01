package net.lunix.lethemc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LetheMC configuration.
 *
 * <p>Enforces the design invariant from DESIGN section 4.4:
 * {@code purgatory.minutes > wipe.graceMinutes}. Both are in minutes, so the default 5 minute
 * grace yields a minimum Purgatory of 6 minutes. This guarantees a hard delete can never still be
 * pending when Purgatory expires.
 */
public final class Config {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** Resolved on demand: the config directory is injected at startup, not at class load. */
    private static Path path() {
        return LetheMC.configDir().resolve("lethemc").resolve("config.json");
    }

    // --- wipe ---
    public boolean wipePlayerData = true;
    public boolean wipeAdvancements = true;
    public boolean wipeStats = true;

    /**
     * How long, in minutes, an entombed player stays restorable before the hard delete.
     *
     * <p>Minutes to match {@code purgatory.minutes}, so the two values that must be compared
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
     * Trial vaults forget that a past life already looted them.
     *
     * <p>The only entry on the list of things that outlive a purge which <em>costs</em> the
     * player rather than granting them something: without this a reincarnated player is
     * silently refused by a vault they have never opened in this life.
     */
    public boolean wipeVaultRewards = true;

    /**
     * Villagers forget what they thought of a past life.
     *
     * <p>Reputation is stored against your UUID on each villager, so trade discounts earned
     * before a death would otherwise follow you into a life that did nothing to earn them.
     */
    public boolean wipeVillagerReputation = true;

    /**
     * Destroy villagers whose only customers belong to lives that have ended.
     *
     * <p>Aimed at the private trading hall: villagers one player cured, bred and levelled, which
     * would otherwise stand fully stocked waiting for their next life. Only villagers actually
     * dealt with count -- gossip spreads by itself, and destroying on hearsay would empty a
     * village outward from the people who died in it.
     *
     * <p><b>The most destructive setting in this file.</b> It removes world content rather than
     * a player's belongings, and on a shared server a hall used by one player who dies is gone
     * for good. Any living customer spares a villager.
     */
    public boolean wipeVillagers = true;

    /**
     * Destroy stashed gear -- tools, weapons, armour, anything that does not stack -- once the
     * life that last held it has ended.
     *
     * <p>The largest hole this mod had. Death took what you carried and left the chest room
     * alone, so a supplied player lost one set of armour and walked back to a wall of spares.
     *
     * <p>Restricted to unstackable items because the stamp lives in the item's components, and
     * two stacks whose components differ will not merge. Tagging cobble would leave chests full
     * of piles that refuse to combine. It is also where the value is -- ore can be re-mined, a
     * mending netherite set cannot be re-earned in an afternoon.
     */
    public boolean wipeGear = true;

    /**
     * Report what the gear sweep would destroy without destroying anything.
     *
     * <p><b>On by default, and deliberately so.</b> Every other setting here takes a pet, a
     * villager, or a reward flag; this one deletes items out of chests, and a mistake cannot be
     * undone. An admin should watch a day of log lines on their own world before arming it.
     */
    public boolean wipeGearLogOnly = true;

    /**
     * How often to walk loaded containers, in ticks.
     *
     * <p>Slower than the animal sweep because it is a bigger walk and does not need to be fast.
     * A player who grabs their dead life's sword before the container is scanned is holding a
     * stale item, and the inventory pass -- which runs every tick the animals do -- destroys it
     * in their hands.
     */
    public int gearCheckIntervalTicks = 100;

    /**
     * How often to look for animals belonging to an ended life, in ticks.
     *
     * <p>Deliberately frequent. The exploit is short-range: park a loaded donkey at spawn,
     * die, and collect it on the walk back. A slow sweep leaves a window where that works.
     */
    public int petsCheckIntervalTicks = 20;

    // --- Purgatory ---
    public int purgatoryMinutes = 360;
    public int purgatoryDeathScreenSeconds = 15;

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
     * A random line from config/lethemc/reincarnation.txt is appended.
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
    // false choice: an admin could either run /lethemc admin OR be able to die, never both -- so on
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
     * Who can run {@code /lethemc admin ...}. {@code 0-4}, default 4.
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

    /** Minimum legal value for purgatoryMinutes given the current grace period. */
    public int minimumPurgatoryMinutes() {
        return wipeGraceMinutes + 1;
    }

    /** Maximum legal value for wipeGraceMinutes given the current Purgatory. */
    public int maximumWipeGraceMinutes() {
        return purgatoryMinutes - 1;
    }

    /** @return null if valid, else a human-readable reason. */
    public String validate() {
        if (purgatoryMinutes <= wipeGraceMinutes) {
            return "purgatory.minutes must be greater than wipe.graceMinutes ("
                    + wipeGraceMinutes + "). Try " + minimumPurgatoryMinutes() + " or higher.";
        }
        if (purgatoryDeathScreenSeconds < 0) return "purgatory.deathScreenSeconds cannot be negative.";
        if (wipeGraceMinutes < 1) return "wipe.graceMinutes must be at least 1.";
        if (petsCheckIntervalTicks < 1) return "wipe.petsCheckIntervalTicks must be at least 1.";
        if (gearCheckIntervalTicks < 1) return "wipe.gearCheckIntervalTicks must be at least 1.";
        if (bypassPermissionLevel < -1 || bypassPermissionLevel > 4) {
            return "bypass.permissionLevel must be -1 (nobody exempt), 0 (everyone) or 1-4.";
        }
        if (adminPermissionLevel < 0 || adminPermissionLevel > 4) {
            return "admin.permissionLevel must be 0-4.";
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Key-based access, used by /lethemc admin config get|set|list
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
        m.put("wipe.vaultRewards", String.valueOf(wipeVaultRewards));
        m.put("wipe.villagerReputation", String.valueOf(wipeVillagerReputation));
        m.put("wipe.villagers", String.valueOf(wipeVillagers));
        m.put("wipe.gear", String.valueOf(wipeGear));
        m.put("wipe.gearLogOnly", String.valueOf(wipeGearLogOnly));
        m.put("wipe.petsCheckIntervalTicks", String.valueOf(petsCheckIntervalTicks));
        m.put("wipe.gearCheckIntervalTicks", String.valueOf(gearCheckIntervalTicks));
        m.put("purgatory.minutes", String.valueOf(purgatoryMinutes));
        m.put("purgatory.deathScreenSeconds", String.valueOf(purgatoryDeathScreenSeconds));
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
     * (lowering purgatory.minutes and raising wipe.delaySeconds each break it).
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
                case "wipe.vaultRewards" -> c.wipeVaultRewards = parseBool(value);
                case "wipe.villagerReputation" -> c.wipeVillagerReputation = parseBool(value);
                case "wipe.villagers" -> c.wipeVillagers = parseBool(value);
                case "wipe.gear" -> c.wipeGear = parseBool(value);
                case "wipe.gearLogOnly" -> c.wipeGearLogOnly = parseBool(value);
                case "wipe.petsCheckIntervalTicks" -> c.petsCheckIntervalTicks = Integer.parseInt(value);
                case "wipe.gearCheckIntervalTicks" -> c.gearCheckIntervalTicks = Integer.parseInt(value);
                case "purgatory.minutes" -> c.purgatoryMinutes = Integer.parseInt(value);
                case "purgatory.deathScreenSeconds" -> c.purgatoryDeathScreenSeconds = Integer.parseInt(value);
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

    /**
     * Copies every setting across, by reflection rather than by hand.
     *
     * <p>It used to be a written-out list of assignments, and adding a field without adding a
     * line to it broke {@code /lethemc admin config set} for that field <b>silently</b>: the
     * command reported success, validation passed, and the value was dropped on the way back
     * out of the copy. Three new keys shipped that way and the only symptom was a setting that
     * would not change. A loop cannot forget a field.
     *
     * <p>Skips statics (the Gson instance) and finals; every setting here is a plain public
     * field, so nothing needs to be made accessible.
     */
    private void copyInto(Config c) {
        for (java.lang.reflect.Field f : Config.class.getDeclaredFields()) {
            int mods = f.getModifiers();
            if (java.lang.reflect.Modifier.isStatic(mods) || java.lang.reflect.Modifier.isFinal(mods)) continue;
            try {
                f.set(c, f.get(this));
            } catch (IllegalAccessException e) {
                LetheMC.LOGGER.error("Could not copy config field {}", f.getName(), e);
            }
        }
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    public static void load() {
        Config loaded = new Config();
        if (Files.exists(path())) {
            try (Reader r = Files.newBufferedReader(path())) {
                Config fromDisk = GSON.fromJson(r, Config.class);
                if (fromDisk != null) loaded = fromDisk;
            } catch (Exception e) {
                LetheMC.LOGGER.error("Failed to read config, using defaults", e);
                loaded = new Config();
            }
        }

        String problem = loaded.validate();
        if (problem != null) {
            // Clamp rather than refuse to boot -- a server that won't start is worse
            // than one running a corrected value, but say so loudly.
            LetheMC.LOGGER.error("INVALID CONFIG: {}", problem);
            int corrected = loaded.minimumPurgatoryMinutes();
            LetheMC.LOGGER.error("Clamping purgatory.minutes {} -> {}", loaded.purgatoryMinutes, corrected);
            loaded.purgatoryMinutes = corrected;
        }

        INSTANCE = loaded;
        save();
    }

    public static void save() {
        try {
            Files.createDirectories(path().getParent());
            try (Writer w = Files.newBufferedWriter(path())) {
                GSON.toJson(INSTANCE, w);
            }
        } catch (IOException e) {
            LetheMC.LOGGER.error("Failed to save config", e);
        }
    }
}
