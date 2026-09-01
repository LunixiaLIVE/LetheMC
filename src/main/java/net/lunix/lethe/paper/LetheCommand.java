package net.lunix.lethe.paper;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** {@code /lethe} and its admin subcommands. */
public final class LetheCommand implements CommandExecutor, TabCompleter {

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("info")) return info(s);
        if (args[0].equalsIgnoreCase("status")) return selfStatus(s);
        if (!args[0].equalsIgnoreCase("admin")) return info(s);

        if (!LethePaper.canAdmin(s)) {
            s.sendMessage(LethePaper.colour("&cYou do not have permission."));
            return true;
        }
        if (args.length < 2) {
            s.sendMessage(LethePaper.colour(
                    "&7/lethe admin &flist | status | resurrect | purge | lock | config | reload | probe"));
            return true;
        }

        return switch (args[1].toLowerCase()) {
            case "list"      -> list(s);
            case "reload"    -> reload(s);
            case "status"    -> adminStatus(s, args);
            case "resurrect" -> resurrect(s, args);
            case "purge"     -> purge(s, args);
            case "lock"      -> lock(s, args);
            case "config"    -> config(s, args);
            case "hardcore"  -> hardcore(s, args);
            case "probe"     -> probe(s, args);
            default -> { s.sendMessage(LethePaper.colour("&cUnknown subcommand.")); yield true; }
        };
    }

    // ------------------------------------------------------------------

    private boolean info(CommandSender s) {
        PluginConfig c = PluginConfig.get();
        StringBuilder sb = new StringBuilder("&6=== Lethe ===\n");
        sb.append("&fWhen you die you are sent to &5Purgatory&f for &e")
          .append(Messages.humanize(c.purgatoryMinutes * 60_000L)).append("&f.\n");
        sb.append("&7You lose your &fInventory, Ender Chest & XP");
        if (c.wipeAdvancements) sb.append("&7, &fAdvancements");
        if (c.wipeStats) sb.append("&7, &fStatistics");
        sb.append("&7.\n");
        if (c.wipePets || c.wipeLivestock) sb.append("&7Animals you tamed are destroyed.\n");
        if (c.wipeVillagers) sb.append("&7Villagers only you traded with are destroyed.\n");
        sb.append("&7An admin can resurrect you for &e")
          .append(Messages.humanize(c.wipeGraceMinutes * 60_000L)).append("&7 after death.\n");
        sb.append("&7Reincarnation returns you to world spawn with nothing.");
        s.sendMessage(LethePaper.colour(sb.toString()));
        return true;
    }

    private boolean selfStatus(CommandSender s) {
        if (!(s instanceof Player p)) {
            s.sendMessage(LethePaper.colour("&7Only a player can use this. Try /lethe admin status <player>."));
            return true;
        }
        Ledger.Entry e = Ledger.get(p.getUniqueId());
        if (e == null) {
            s.sendMessage(LethePaper.colour("&aYou are alive and clear. Nothing pending."));
            return true;
        }
        long now = System.currentTimeMillis();
        s.sendMessage(LethePaper.colour("&5Purgatory: &e" + Messages.humanize(e.remainingMillis(now))
                + "&7 left\n" + Messages.graceLine(e, now)));
        return true;
    }

    private boolean list(CommandSender s) {
        if (Ledger.isEmpty()) {
            s.sendMessage(LethePaper.colour("&aNobody is in Purgatory."));
            return true;
        }
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder("&6In Purgatory:");
        for (UUID uuid : Ledger.uuids()) {
            Ledger.Entry e = Ledger.get(uuid);
            if (e == null) continue;
            sb.append("\n&f").append(e.name).append(" &7- ")
              .append(Messages.humanize(e.remainingMillis(now))).append(" left")
              .append(e.restorable
                      ? " &e(resurrectable for " + Messages.humanize(e.graceRemainingMillis(now)) + ")"
                : " &8(purged)");
        }
        s.sendMessage(LethePaper.colour(sb.toString()));
        return true;
    }

    private boolean reload(CommandSender s) {
        PluginConfig.load(LethePaper.get());
        Taunts.load(LethePaper.get());
        Ledger.load(LethePaper.get());
        Incarnations.load(LethePaper.get());
        s.sendMessage(LethePaper.colour("&aLethe config and ledger reloaded."));
        return true;
    }

    private boolean adminStatus(CommandSender s, String[] args) {
        if (args.length < 3) { s.sendMessage(LethePaper.colour("&c/lethe admin status <player>")); return true; }
        UUID uuid = Ledger.findByName(args[2]);
        if (uuid == null) { s.sendMessage(LethePaper.colour("&a" + args[2] + " is not in Purgatory.")); return true; }
        Ledger.Entry e = Ledger.get(uuid);
        long now = System.currentTimeMillis();
        s.sendMessage(LethePaper.colour("&f" + e.name + "\n&7Purgatory: &e"
                + Messages.humanize(e.remainingMillis(now)) + "&7 left\n"
                + (e.restorable
                    ? "&7Belongings: &aentombed&7 -- resurrection restores everything"
                : "&7Belongings: &8gone&7 -- resurrection only lifts Purgatory")));
        return true;
    }

    private boolean resurrect(CommandSender s, String[] args) {
        if (args.length < 3) { s.sendMessage(LethePaper.colour("&c/lethe admin resurrect <player>")); return true; }
        UUID uuid = Ledger.findByName(args[2]);
        if (uuid == null) { s.sendMessage(LethePaper.colour("&a" + args[2] + " is not in Purgatory.")); return true; }

        // No resurrecting yourself. Otherwise the penalty is optional for anyone holding the
        // permission: die, resurrect, keep everything, repeat.
        if (s instanceof Player p && p.getUniqueId().equals(uuid)) {
            s.sendMessage(LethePaper.colour(
                    "&cYou cannot resurrect yourself. Ask another admin, or use the server console."));
            return true;
        }

        Ledger.Entry e = Ledger.get(uuid);
        e.state = Ledger.STATE_PARDONED;
        Ledger.put(uuid, e);

        Player online = LethePaper.online(uuid);
        if (online != null && Graveyard.restore(online)) {
            online.sendMessage(LethePaper.colour("&aYou have been resurrected. &7Everything is as you left it."));
            Ledger.remove(uuid);
            s.sendMessage(LethePaper.colour("&a" + e.name + " resurrected, belongings restored."));
        } else {
            Pending.oweRestore(uuid);
            s.sendMessage(LethePaper.colour("&a" + e.name + " may return."
                + (e.restorable ? " &7Their belongings are restored the moment they reconnect." : "")));
        }
        return true;
    }

    private boolean purge(CommandSender s, String[] args) {
        if (args.length < 3) { s.sendMessage(LethePaper.colour("&c/lethe admin purge <player>")); return true; }
        UUID uuid = Ledger.findByName(args[2]);
        if (uuid == null) { s.sendMessage(LethePaper.colour("&c" + args[2] + " is not in Purgatory.")); return true; }
        Ledger.Entry e = Ledger.get(uuid);
        Graveyard.destroy(uuid);
        e.restorable = false;
        Ledger.put(uuid, e);
        Incarnations.rotate(uuid);
        s.sendMessage(LethePaper.colour("&a" + e.name + "'s belongings erased. Purgatory still runs."));
        return true;
    }

    private boolean lock(CommandSender s, String[] args) {
        if (args.length < 3) { s.sendMessage(LethePaper.colour("&c/lethe admin lock <player> [minutes]")); return true; }
        Player target = Bukkit.getPlayerExact(args[2]);
        UUID uuid = target != null ? target.getUniqueId() : Bukkit.getOfflinePlayer(args[2]).getUniqueId();
        PluginConfig c = PluginConfig.get();
        int minutes = c.purgatoryMinutes;
        if (args.length >= 4) {
            try { minutes = Integer.parseInt(args[3]); }
            catch (NumberFormatException ex) { s.sendMessage(LethePaper.colour("&cMinutes must be a number.")); return true; }
        }
        if (minutes <= c.wipeGraceMinutes) {
            s.sendMessage(LethePaper.colour("&cMust exceed wipe.graceMinutes (" + c.wipeGraceMinutes + ")."));
            return true;
        }
        long now = System.currentTimeMillis();
        Ledger.Entry e = new Ledger.Entry();
        e.name = args[2];
        e.state = Ledger.STATE_LOCKED;
        e.deathAt = now;
        e.purgatoryStartsAt = now;
        e.durationMillis = minutes * 60_000L;
        e.graceMillis = c.wipeGraceMinutes * 60_000L;
        e.restorable = false;   // a manual lock takes nothing, so there is nothing to give back
        Ledger.put(uuid, e);
        if (target != null) target.kickPlayer(LethePaper.colour(
                Messages.render(c.messageRejoin, e, null, now)));
        s.sendMessage(LethePaper.colour("&aLocked " + args[2] + " out for " + minutes + " minutes."));
        return true;
    }

    private boolean config(CommandSender s, String[] args) {
        Map<String, String> map = PluginConfig.get().asMap();
        if (args.length < 3 || args[2].equalsIgnoreCase("list")) {
            StringBuilder sb = new StringBuilder("&6Lethe config:");
            map.forEach((k, v) -> sb.append("\n&7").append(k).append(" &8= &f").append(v));
            s.sendMessage(LethePaper.colour(sb.toString()));
            return true;
        }
        if (args[2].equalsIgnoreCase("get") && args.length >= 4) {
            String v = map.get(args[3]);
            s.sendMessage(LethePaper.colour(v == null
                    ? "&cUnknown key: " + args[3]
                    : "&7" + args[3] + " &8= &f" + v));
            return true;
        }
        if (args[2].equalsIgnoreCase("set") && args.length >= 5) {
            String problem = PluginConfig.set(LethePaper.get(), args[3], args[4]);
            s.sendMessage(LethePaper.colour(problem == null
                    ? "&a" + args[3] + " set to " + args[4]
                    : "&c" + problem));
            return true;
        }
        s.sendMessage(LethePaper.colour("&7/lethe admin config list | get <key> | set <key> <value>"));
        return true;
    }

    /**
     * Converts the world to or from hardcore.
     *
     * <p>Vanilla will not do this: the {@code hardcore} line in server.properties is read only
     * when a world is created and silently ignored afterwards. Bukkit exposes a setter, so the
     * plugin can offer what the server itself cannot.
     */
    private boolean hardcore(CommandSender s, String[] args) {
        if (args.length < 3) { s.sendMessage(LethePaper.colour("&c/lethe admin hardcore <on|off>")); return true; }
        boolean on = args[2].equalsIgnoreCase("on") || args[2].equalsIgnoreCase("true");
        var world = s instanceof Player p ? p.getWorld() : Bukkit.getWorlds().get(0);
        if (world.isHardcore() == on) {
            s.sendMessage(LethePaper.colour("&7" + world.getName() + " is already "
                + (on ? "hardcore" : "not hardcore") + ". Nothing changed."));
            return true;
        }
        world.setHardcore(on);
        LethePaper.get().getLogger().warning(s.getName() + " switched " + world.getName()
                + " to hardcore=" + on + " via /lethe admin hardcore.");
        s.sendMessage(LethePaper.colour("&6" + world.getName() + ": &fhardcore = " + on
                + "\n&e⚠ Restart the server for this to take effect.&7 Clients read hardcore from\n"
                + "&7the login packet, so hearts will not change until players reconnect."));
        return true;
    }

    /**
     * Reports what Lethe knows about everything nearby.
     *
     * <p>Reads the same Bukkit API the reclaimer does rather than raw NBT, so what it prints is
     * by definition what the plugin will act on. Worth having beyond testing: when an admin asks
     * why a villager was spared or a fox destroyed, this is the answer, and the alternative --
     * reading entity NBT by hand -- needs the field names to be guessed correctly and is
     * truncated by the server anyway.
     */
    private boolean probe(CommandSender s, String[] args) {
        if (!(s instanceof Player p)) {
            s.sendMessage(LethePaper.colour("&cA player has to run this -- it reports on what is around them."));
            return true;
        }
        int radius = 12;
        if (args.length >= 3) {
            try { radius = Math.max(1, Math.min(64, Integer.parseInt(args[2]))); }
            catch (NumberFormatException ignored) { }
        }

        StringBuilder sb = new StringBuilder("&6=== Lethe probe, r=" + radius + " ===");

        org.bukkit.block.Block looking = p.getTargetBlockExact(6);
        if (looking != null && looking.getState() instanceof org.bukkit.block.Vault vault) {
            sb.append("\n&eVault &7").append(brief(vault.getLocation()));
            sb.append("\n  &7looted by: &f").append(names(vault.getRewardedPlayers()));
            sb.append("\n  &7stamps: &f").append(stampLine(Keys.stamps(vault)));
        }

        int shown = 0;
        for (org.bukkit.entity.Entity e : p.getNearbyEntities(radius, radius, radius)) {
            String line = describe(e);
            if (line == null) continue;
            sb.append("\n").append(line);
            if (++shown >= 25) { sb.append("\n&8...more not shown"); break; }
        }
        if (shown == 0) sb.append("\n&7Nothing here that Lethe tracks.");
        s.sendMessage(LethePaper.colour(sb.toString()));
        return true;
    }

    /** One line per tracked entity, or null for anything Lethe never looks at. */
    private String describe(org.bukkit.entity.Entity e) {
        String head = "&e" + e.getType() + (e.getCustomName() == null ? "" : " &f\"" + e.getCustomName() + "\"")
                + " &7" + brief(e.getLocation());

        if (e instanceof org.bukkit.entity.Fox fox) {
            var a = fox.getFirstTrustedPlayer();
            var b = fox.getSecondTrustedPlayer();
            return head
                    + "\n  &7trusts: &f" + (a == null ? "-" : name(a.getUniqueId()))
                    + " &8| &f" + (b == null ? "-" : name(b.getUniqueId()))
                    + "\n  &7stamps: &f" + stampLine(Keys.stamps(fox));
        }
        if (e instanceof org.bukkit.entity.Villager v) {
            StringBuilder rep = new StringBuilder();
            v.getReputations().forEach((id, r) -> rep.append(name(id)).append("=").append(r).append(" "));
            return head
                    + "\n  &7traded with: &f" + names(Keys.uuids(v, Keys.direct))
                    + "\n  &7reputation: &f" + (rep.length() == 0 ? "none" : rep.toString().trim())
                    + "\n  &7stamps: &f" + stampLine(Keys.stamps(v));
        }
        if (e instanceof org.bukkit.entity.Tameable || e instanceof org.bukkit.entity.AbstractHorse) {
            UUID owner = e instanceof org.bukkit.entity.Tameable t
                    ? t.getOwnerUniqueId()
                    : ((org.bukkit.entity.AbstractHorse) e).getOwnerUniqueId();
            String stamp = Keys.get(e, Keys.incarnation);
            return head
                    + "\n  &7owner: &f" + (owner == null ? "untamed" : name(owner))
                    + "\n  &7stamp: &f" + shortId(stamp) + staleMark(owner, stamp);
        }
        return null;
    }

    // ------------------------------------------------------------------

    private String stampLine(Map<UUID, String> stamps) {
        if (stamps.isEmpty()) return "none";
        StringBuilder sb = new StringBuilder();
        stamps.forEach((id, stamp) -> sb.append(name(id)).append("=").append(shortId(stamp))
                .append(staleMark(id, stamp)).append(" "));
        return sb.toString().trim();
    }

    /** Marks a stamp that no longer matches the life its player is living now. */
    private String staleMark(UUID player, String stamp) {
        if (player == null || stamp == null) return "";
        String living = Incarnations.peek(player);
        if (living == null) return " &8(no life on record)";
        return living.equals(stamp) ? " &a(current)" : " &c(STALE)";
    }

    private String names(java.util.Collection<UUID> ids) {
        if (ids.isEmpty()) return "nobody";
        StringBuilder sb = new StringBuilder();
        for (UUID id : ids) sb.append(name(id)).append(" ");
        return sb.toString().trim();
    }

    private String name(UUID id) {
        String n = Bukkit.getOfflinePlayer(id).getName();
        return n == null ? shortId(id.toString()) : n;
    }

    private String shortId(String id) {
        if (id == null) return "none";
        return id.length() > 8 ? id.substring(0, 8) : id;
    }

    private String brief(org.bukkit.Location l) {
        return l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ();
    }

    // ------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.add("info"); out.add("status");
            if (LethePaper.canAdmin(s)) out.add("admin");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("admin") && LethePaper.canAdmin(s)) {
            for (String x : new String[]{"list", "status", "resurrect", "purge", "lock",
                    "config", "reload", "hardcore", "probe"}) out.add(x);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("admin")) {
            switch (args[1].toLowerCase()) {
                // Anyone in Purgatory is offline by definition, so Bukkit's own player
                // completion cannot offer them -- these names come from the ledger instead.
                case "status", "resurrect", "purge" -> {
                    for (UUID u : Ledger.uuids()) {
                        Ledger.Entry e = Ledger.get(u);
                        if (e != null && !e.name.isEmpty()) out.add(e.name);
                    }
                }
                case "lock" -> Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
                case "config" -> { out.add("list"); out.add("get"); out.add("set"); }
                case "hardcore" -> { out.add("on"); out.add("off"); }
            }
        } else if (args.length == 4 && args[0].equalsIgnoreCase("admin")
                && args[1].equalsIgnoreCase("config")
                && (args[2].equalsIgnoreCase("get") || args[2].equalsIgnoreCase("set"))) {
            out.addAll(PluginConfig.get().asMap().keySet());
        }
        String prefix = args[args.length - 1].toLowerCase();
        out.removeIf(x -> !x.toLowerCase().startsWith(prefix));
        return out;
    }
}
