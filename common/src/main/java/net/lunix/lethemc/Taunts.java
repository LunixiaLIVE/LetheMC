package net.lunix.lethemc;


import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The line appended after the reincarnation greeting.
 *
 * <p>Reincarnation is the same event every time, and a message that never varies stops being
 * read after the second death. A rotating jab keeps it alive and gives the server a voice.
 *
 * <p>Plain text, one phrase per line, so a server owner can edit it without touching JSON and
 * without a restart -- {@code /lethemc admin reload} re-reads it. The file IS the switch: empty it
 * (or delete every line) and the greeting is shown on its own. That is deliberately the only
 * control; a separate enable flag would be one more thing to explain and to get out of sync.
 */
public final class Taunts {

    /** Resolved on demand: the config directory is injected at startup, not at class load. */
    private static Path path() {
        return LetheMC.configDir().resolve("lethemc").resolve("reincarnation.txt");
    }

    private static final Random RANDOM = new Random();
    private static List<String> phrases = new ArrayList<>();

    /** Written on first run. Snide rather than cruel -- the player has just lost everything. */
    private static final List<String> DEFAULTS = List.of(
            "You gonna be more careful this time?",
            "Maybe this time you won't be so careless.",
            "Try to make this one last.",
            "A fresh start. Do try not to waste it.",
            "Let's see how long this one survives.",
            "The world remembers nothing of you. Do better.",
            "Everyone deserves a second chance. Some need several.",
            "Perhaps this time, with feeling.",
            "Statistically speaking, you will be back.",
            "Hold onto this one a little tighter."
    );

    private Taunts() {}

    public static void load() {
        if (!Files.exists(path())) {
            writeDefaults();
        }
        List<String> loaded = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(path(), StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                // '#' starts a comment so the header survives an edit, and so an owner can
                // park a phrase without deleting it.
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                loaded.add(trimmed);
            }
        } catch (IOException e) {
            LetheMC.LOGGER.error("Could not read {}; reincarnation greetings will be plain", path(), e);
        }
        phrases = loaded;
        LetheMC.LOGGER.info("Loaded {} reincarnation phrase(s)", phrases.size());
    }

    private static void writeDefaults() {
        List<String> out = new ArrayList<>();
        out.add("# LetheMC -- reincarnation phrases.");
        out.add("#");
        out.add("# One per line. Appended after the reincarnation greeting when a player");
        out.add("# returns from Purgatory with nothing. Picked at random.");
        out.add("#");
        out.add("# Lines starting with # are ignored. Remove every phrase to show the");
        out.add("# greeting on its own. Reloaded by /lethemc admin reload -- no restart needed.");
        out.add("");
        out.addAll(DEFAULTS);
        try {
            Files.createDirectories(path().getParent());
            Files.write(path(), out, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LetheMC.LOGGER.error("Could not write {}", path(), e);
        }
    }

    /** @return a random phrase, or null when the file has none. */
    public static String pick() {
        if (phrases.isEmpty()) return null;
        return phrases.get(RANDOM.nextInt(phrases.size()));
    }

    public static int count() {
        return phrases.size();
    }
}
