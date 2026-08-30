package net.lunix.lethemc;

import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Placeholder expansion for the death and rejoin messages. */
public final class Messages {

    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private Messages() {}

    public static Component format(String template, String playerName, Ledger.Entry entry, long now) {
        long remaining = entry == null ? 0L : entry.remainingMillis(now);
        long unlockAt = entry == null ? now : entry.unlockAt();
        long grace = entry == null ? 0L : entry.graceRemainingMillis(now);
        boolean restorable = entry != null && entry.restorable();

        String out = template
                .replace("%player%", playerName)
                .replace("%time_remaining%", humanize(remaining))
                .replace("%time_remaining_short%", clock(remaining))
                .replace("%unlock_time%", unlockAt == Long.MAX_VALUE ? "unknown" : CLOCK.format(Instant.ofEpochMilli(unlockAt)))
                .replace("%grace_remaining%", restorable ? humanize(grace) : "expired")
                .replace("%grace_remaining_short%", restorable ? clock(grace) : "00:00:00")
                .replace("%grace_line%", graceLine(restorable, grace))
                .replace("%death_reason%", entry == null || entry.deathReason.isEmpty() ? "unknown" : entry.deathReason);

        return Component.literal(out);
    }

    /**
     * The whole recoverability sentence, ready-formatted.
     *
     * <p>Exists as its own placeholder because the two branches want different wording AND
     * different colours, and a server owner editing {@code message.rejoin} should not have to
     * hand-write both. {@code %grace_remaining%} is still there for anyone who does.
     *
     * <p>Worth telling the player rather than only the admin: a grace period nobody knows
     * about is a grace period nobody asks for, and the whole reason it exists is the death
     * that was not the player's fault.
     */
    private static String graceLine(boolean restorable, long grace) {
        // Names the stakes plainly rather than poetically. "Purgatory" is good flavour for the
        // state a player is IN, but what they stand to lose has to be unambiguous -- a player
        // reading "your remains" should not have to work out that it means their gear.
        String stakes = stakes();
        if (stakes == null) {
            // Every wipe toggle is off: this server only holds people, it takes nothing.
            // Saying anything about belongings here would be false.
            return restorable
                    ? "§7Nothing of yours is taken. An admin can release you early."
                    : "§7Nothing of yours was taken.";
        }

        // The spawn point lives in playerdata, so wiping it takes that too: the bed block
        // survives in the world but the association does not, and the player wakes at world
        // spawn with a walk home ahead of them. Not obvious from "your items are gone" -- but
        // only true when wipe.playerData is actually on.
        boolean losesSpawn = Config.get().wipePlayerData;

        if (!restorable) {
            return "§8Your " + stakes + " are gone.\n"
                    + (losesSpawn
                        ? "§8You will wake at world spawn with nothing,\n§8and no bed to return to."
                        : "§8You will be reincarnated with nothing.");
        }
        if (grace <= 0) {
            // Still on disk but past the deadline -- a delete that has not landed yet
            // (retrying after an IO error). Promising a countdown here would be a lie in the
            // other direction, so say only what is certainly true.
            return "§6Your " + stakes + " can still be saved,\n"
                    + "§6but only for moments. §7Ask an admin to resurrect you §lnow§r§7.";
        }
        return "§6Your " + stakes + " are held for §e" + humanize(grace) + "§6.\n"
                + "§7An admin can resurrect you until then."
                + (losesSpawn ? "\n§8Otherwise you wake at world spawn with nothing." : "");
    }

    /**
     * What this server actually takes, phrased as an English list.
     *
     * <p>Built from the live {@code wipe.*} toggles rather than hardcoded, so the message can
     * never promise to take something the config has switched off. An admin who disables
     * {@code wipe.stats} should not have players told their statistics are at risk.
     *
     * <p>Read at call time, so {@code /lethe admin config set} and {@code reload} are reflected
     * immediately without touching the templates.
     *
     * @return e.g. "Inventory, Ender Chest, XP, Advancements & Statistics", or null if the
     *         server has been configured to take nothing at all.
     */
    public static String stakes() {
        Config c = Config.get();
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (c.wipePlayerData) {
            // One file, but three things a player thinks of separately.
            parts.add("Inventory");
            parts.add("Ender Chest");
            parts.add("XP");
        }
        if (c.wipeAdvancements) parts.add("Advancements");
        if (c.wipeStats) parts.add("Statistics");

        if (parts.isEmpty()) return null;
        if (parts.size() == 1) return parts.get(0);
        return String.join(", ", parts.subList(0, parts.size() - 1))
                + " & " + parts.get(parts.size() - 1);
    }

    /** "2h 14m", "45m", "30s" */
    public static String humanize(long millis) {
        long totalSeconds = Math.max(0L, millis / 1000L);
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        if (hours > 0) {
            return minutes > 0 ? hours + "h " + minutes + "m" : hours + "h";
        }
        if (minutes > 0) {
            return seconds > 0 ? minutes + "m " + seconds + "s" : minutes + "m";
        }
        return seconds + "s";
    }

    /**
     * "00:02:14:33" — DD:HH:MM:SS.
     *
     * <p>A countdown, deliberately not a wall-clock timestamp. An absolute unlock time has to
     * be rendered in *some* timezone, and the server's is not the player's -- so a player in
     * another region reads a number that is simply wrong for them, with nothing on screen
     * saying so. A duration means the same thing everywhere.
     *
     * <p>The day field is always present rather than appearing only when non-zero, so the
     * field positions never move and the value stays readable at a glance.
     */
    public static String clock(long millis) {
        long totalSeconds = Math.max(0L, millis / 1000L);
        return String.format("%02d:%02d:%02d:%02d",
                totalSeconds / 86400,
                (totalSeconds % 86400) / 3600,
                (totalSeconds % 3600) / 60,
                totalSeconds % 60);
    }
}
