package net.lunix.lethe.paper;

/**
 * Player-facing text.
 *
 * <p>Every line is <em>derived</em> from the ledger rather than asserted. Telling someone their
 * belongings are gone while they are still sitting in the graveyard is the mistake this exists
 * to prevent -- the entry knows whether they are restorable, so the sentence asks it.
 */
public final class Messages {

    private Messages() {}

    /** Durations, never clock times: an absolute time is in the server's timezone, not theirs. */
    public static String humanize(long millis) {
        if (millis <= 0) return "0s";
        long s = millis / 1000, d = s / 86400, h = (s % 86400) / 3600, m = (s % 3600) / 60, sec = s % 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("d ");
        if (h > 0) sb.append(h).append("h ");
        if (m > 0) sb.append(m).append("m ");
        if (sb.length() == 0) sb.append(sec).append("s");
        return sb.toString().trim();
    }

    /** {@code DD:HH:MM:SS}, day field always present so columns do not shift. */
    public static String clock(long millis) {
        long s = Math.max(0, millis) / 1000;
        return String.format("%02d:%02d:%02d:%02d", s / 86400, (s % 86400) / 3600, (s % 3600) / 60, s % 60);
    }

    /** One sentence covering both cases, so no caller has to work out which applies. */
    public static String graceLine(Ledger.Entry e, long now) {
        if (e.restorable) {
            long left = e.graceRemainingMillis(now);
            return "&7An admin can still restore everything, for &e" + humanize(left) + "&7.";
        }
        return "&8Your belongings are gone for good. Only the wait remains.";
    }

    public static String render(String template, Ledger.Entry e, String deathReason, long now) {
        return template
                .replace("%player%", e.name)
                .replace("%death_reason%", deathReason == null ? "" : deathReason)
                .replace("%time_remaining%", humanize(e.remainingMillis(now)))
                .replace("%time_remaining_short%", clock(e.remainingMillis(now)))
                .replace("%grace_remaining%", e.restorable ? humanize(e.graceRemainingMillis(now)) : "expired")
                .replace("%grace_remaining_short%", clock(e.restorable ? e.graceRemainingMillis(now) : 0))
                .replace("%grace_line%", graceLine(e, now));
    }
}
