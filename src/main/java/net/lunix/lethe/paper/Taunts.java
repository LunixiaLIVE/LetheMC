package net.lunix.lethe.paper;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The line that follows the reincarnation greeting.
 *
 * <p>Plain text, one phrase per line, and the file <em>is</em> the switch -- empty it and the
 * greeting appears on its own. No extra toggle to keep in step with it.
 */
public final class Taunts {

    private Taunts() {}

    private static final List<String> PHRASES = new ArrayList<>();

    private static final String[] DEFAULTS = {
        "Maybe this time you won't be so careless.",
        "The river takes everyone eventually.",
        "You had a whole life. What did you do with it?",
        "Try to make this one last.",
        "Nobody remembers the old you. Not even you.",
        "Starting again is a kind of mercy.",
        "The world moved on without you.",
        "Everything you owned belongs to the ground now.",
        "A clean slate, whether you wanted one or not.",
        "Do try to be more careful.",
    };

    static void load(LethePaper plugin) {
        PHRASES.clear();
        File f = new File(plugin.getDataFolder(), "reincarnation.txt");
        if (!f.exists()) {
            List<String> out = new ArrayList<>();
            out.add("# One phrase per line, picked at random after the reincarnation greeting.");
            out.add("# Blank lines and lines starting with # are ignored. Empty the file to show");
            out.add("# the greeting on its own. Reloaded by /lethe admin reload -- no restart needed.");
            for (String d : DEFAULTS) out.add(d);
            try {
                Files.write(f.toPath(), out, StandardCharsets.UTF_8);
            } catch (IOException e) {
                plugin.getLogger().warning("Could not write reincarnation.txt: " + e.getMessage());
            }
        }
        try {
            for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                String s = line.trim();
                if (!s.isEmpty() && !s.startsWith("#")) PHRASES.add(s);
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Could not read reincarnation.txt: " + e.getMessage());
        }
        plugin.getLogger().info("Loaded " + PHRASES.size() + " reincarnation phrase(s)");
    }

    /** @return a phrase, or null when the file is empty. */
    public static String pick() {
        if (PHRASES.isEmpty()) return null;
        return PHRASES.get(ThreadLocalRandom.current().nextInt(PHRASES.size()));
    }
}
