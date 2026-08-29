package net.lunix.nixreaper;

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
        if (!restorable) {
            return "§8Your items have been permanently erased.";
        }
        if (grace <= 0) {
            // Still on disk but past the deadline -- a delete that has not landed yet
            // (retrying after an IO error). Promising a countdown here would be a lie in the
            // other direction, so say only what is certainly true.
            return "§6Your items are still recoverable, but only for moments.\n"
                    + "§7Ask an admin to pardon you §lnow§r§7.";
        }
        return "§6Your items are held for another §e" + humanize(grace)
                + "§6.\n§7Ask an admin to pardon you before then and you get everything back.";
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
