package net.lunix.lethemc.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.SharedSuggestionProvider;
import net.lunix.lethemc.Config;
import net.lunix.lethemc.Graveyard;
import net.lunix.lethemc.Ledger;
import net.lunix.lethemc.Messages;
import net.lunix.lethemc.LetheMC;
import net.lunix.lethemc.PurgeService;
import net.lunix.lethemc.Taunts;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class LetheCommand {

    private LetheCommand() {}

    /**
     * Config keys, straight from the config itself.
     *
     * <p>Built from {@code asMap()} rather than a hand-written list, so a key added to the
     * config can never go missing here -- two lists would drift the first time anyone forgot.
     */
    private static final SuggestionProvider<CommandSourceStack> CONFIG_KEYS =
            (ctx, builder) -> SharedSuggestionProvider.suggest(Config.get().asMap().keySet(), builder);

    /**
     * Players currently in Purgatory.
     *
     * <p>The only sensible targets for status, resurrect and purge -- and vanilla's player
     * argument cannot offer them, because a player in Purgatory is by definition offline.
     * Without this an admin has to recall the exact spelling of a name they cannot see.
     */
    private static final SuggestionProvider<CommandSourceStack> IN_PURGATORY =
            (ctx, builder) -> {
                List<String> names = new ArrayList<>();
                for (UUID uuid : Ledger.uuids()) {
                    Ledger.Entry e = Ledger.get(uuid);
                    if (e != null && !e.name.isEmpty()) names.add(e.name);
                }
                return SharedSuggestionProvider.suggest(names, builder);
            };

    /** Anyone who can be sent to Purgatory by hand: online players, plus those already in it. */
    private static final SuggestionProvider<CommandSourceStack> LOCKABLE =
            (ctx, builder) -> {
                List<String> names = new ArrayList<>(List.of(ctx.getSource().getServer().getPlayerNames()));
                for (UUID uuid : Ledger.uuids()) {
                    Ledger.Entry e = Ledger.get(uuid);
                    if (e != null && !e.name.isEmpty() && !names.contains(e.name)) names.add(e.name);
                }
                return SharedSuggestionProvider.suggest(names, builder);
            };

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(build("lethemc"));
        dispatcher.register(build("lmc"));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> build(String root) {
        return Commands.literal(root)
                .executes(ctx -> info(ctx.getSource()))
                .then(Commands.literal("info").executes(ctx -> info(ctx.getSource())))
                .then(Commands.literal("status").executes(ctx -> selfStatus(ctx.getSource())))
                .then(Commands.literal("admin")
                        .requires(src -> LetheMC.canAdmin(src.permissions()))
                        .then(Commands.literal("config")
                                .then(Commands.literal("list").executes(ctx -> configList(ctx.getSource())))
                                .then(Commands.literal("get")
                                        .then(Commands.argument("key", StringArgumentType.string())
                                                .suggests(CONFIG_KEYS)
                                                .executes(ctx -> configGet(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "key")))))
                                .then(Commands.literal("set")
                                        .then(Commands.argument("key", StringArgumentType.string())
                                                .suggests(CONFIG_KEYS)
                                                .then(Commands.argument("value", StringArgumentType.greedyString())
                                                        .executes(ctx -> configSet(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "key"),
                                                                StringArgumentType.getString(ctx, "value")))))))
                        .then(Commands.literal("reload").executes(ctx -> reload(ctx.getSource())))
                        .then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))
                        .then(Commands.literal("status")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .suggests(IN_PURGATORY)
                                        .executes(ctx -> status(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "player")))))
                        // One name for one behaviour. "pardon" was the older spelling and read
                        // like a ban being lifted; the vocabulary here is resurrection.
                        .then(Commands.literal("resurrect")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .suggests(IN_PURGATORY)
                                        .executes(ctx -> resurrect(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "player")))))
                        .then(Commands.literal("purge")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .suggests(IN_PURGATORY)
                                        .executes(ctx -> forcePurge(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "player")))))
                        .then(Commands.literal("lock")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .suggests(LOCKABLE)
                                        .executes(ctx -> lock(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "player"), -1))
                                        .then(Commands.argument("minutes", StringArgumentType.word())
                                                .executes(ctx -> lock(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "player"),
                                                        parseInt(StringArgumentType.getString(ctx, "minutes"))))))));
    }

    // ------------------------------------------------------------------
    // Player commands
    // ------------------------------------------------------------------

    private static int info(CommandSourceStack src) {
        Config c = Config.get();
        StringBuilder sb = new StringBuilder();
        sb.append("§6=== LetheMC ===\n");

        // Say this first and plainly. A player told they will lose everything, who then
        // does not, has been misled by the mod itself.
        if (LetheMC.isStandingDown()) {
            sb.append("§c§lDISABLED §r§c-- deaths are currently vanilla.\n");
            sb.append("§7Reason: ").append(LetheMC.standDownReason()).append("\n");
            sb.append("§7Nothing is taken and nobody is locked out until this is fixed.");
            src.sendSuccess(() -> Component.literal(sb.toString()), false);
            return 1;
        }

        String stakes = Messages.stakes();
        sb.append("§fWhen you die you are sent to §5Purgatory§f for §e")
          .append(Messages.humanize(c.purgatoryMinutes * 60_000L)).append("§f.\n");
        sb.append("§7You cannot rejoin until it ends. The clock is real time --\n");
        sb.append("§7it keeps running while the server is offline.\n");

        if (stakes != null) {
            sb.append("§f\nTaken from you: §c").append(stakes).append("\n");
            sb.append("§7Nothing drops on the ground. There is no body to loot.\n");
        } else {
            sb.append("§f\nNothing of yours is taken -- only your time.\n");
        }

        sb.append("§f\nYou leave Purgatory one of two ways:\n");
        if (stakes != null) {
            sb.append("§7 - §aResurrected§7 by an admin, within §e")
              .append(Messages.humanize(c.wipeGraceMinutes * 60_000L))
              .append("§7 -- you return exactly as you were\n");
            sb.append("§7 - §5Reincarnated§7 when the time runs out -- you return with nothing\n");
        } else {
            sb.append("§7 - released early by an admin\n");
            sb.append("§7 - or when the time runs out\n");
        }
        sb.append("§7The death screen stays up for ").append(c.purgatoryDeathScreenSeconds)
          .append("s so you can see what killed you.");
        // Worth stating out loud on a server that has chosen it: the stakes are the same for
        // everyone, which is exactly the thing players assume is untrue of the person running it.
        if (c.bypassPermissionLevel < 0) {
            sb.append("\n§7Nobody is exempt -- not even the server owner.");
        }
        src.sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    private static int selfStatus(CommandSourceStack src) {
        ServerPlayer p = src.getPlayer();
        if (p == null) {
            src.sendFailure(Component.literal("Only a player can use this. Try /lethemc admin status <player>."));
            return 0;
        }
        Ledger.Entry e = Ledger.get(p.getUUID());
        if (e == null) {
            src.sendSuccess(() -> Component.literal("§aYou are alive and clear. Nothing pending."), false);
        } else {
            long now = System.currentTimeMillis();
            src.sendSuccess(() -> Component.literal("§5Purgatory: §e" + Messages.humanize(e.remainingMillis(now))
                    + "§f remaining."), false);
        }
        return 1;
    }

    // ------------------------------------------------------------------
    // Admin: config
    // ------------------------------------------------------------------

    private static int configList(CommandSourceStack src) {
        StringBuilder sb = new StringBuilder("§6LetheMC config:");
        for (Map.Entry<String, String> e : Config.get().asMap().entrySet()) {
            String v = e.getValue();
            if (v.length() > 40) v = v.substring(0, 37).replace("\n", "\\n") + "...";
            sb.append("\n§7").append(e.getKey()).append(" §8= §f").append(v);
        }
        src.sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    private static int configGet(CommandSourceStack src, String key) {
        String v = Config.get().getKey(key);
        if (v == null) {
            src.sendFailure(Component.literal("Unknown key: " + key));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("§7" + key + " §8= §f" + v), false);
        return 1;
    }

    private static int configSet(CommandSourceStack src, String key, String value) {
        String problem = Config.get().setKey(key, value);
        if (problem != null) {
            // This is where the DESIGN 4.4 constraint bites, in both directions.
            src.sendFailure(Component.literal("§c" + problem));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("§a" + key + " set to " + value), true);
        return 1;
    }

    private static int reload(CommandSourceStack src) {
        Config.load();
        Ledger.load();
        Taunts.load();
        // Re-run the startup checks so an admin who has just fixed server.properties gets
        // the mod back without a second restart -- and so one that has just been broken is
        // caught here rather than at the next death.
        if (LetheMC.server() != null) {
            LetheMC.recheckPreconditions(LetheMC.server());
        }
        if (LetheMC.isStandingDown()) {
            src.sendFailure(Component.literal("§cReloaded, but LetheMC is DISABLED -- "
                    + LetheMC.standDownReason()));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("§aLetheMC config and ledger reloaded."), true);
        return 1;
    }

    // ------------------------------------------------------------------
    // Admin: Purgatory
    // ------------------------------------------------------------------

    private static int list(CommandSourceStack src) {
        long now = System.currentTimeMillis();
        if (LetheMC.isStandingDown()) {
            src.sendFailure(Component.literal("§cLetheMC is DISABLED -- " + LetheMC.standDownReason()
                    + "\n§7Nobody is being held in Purgatory."));
            return 0;
        }
        if (Ledger.all().isEmpty()) {
            src.sendSuccess(() -> Component.literal("§aNobody is in Purgatory."), false);
            return 1;
        }
        StringBuilder sb = new StringBuilder("§6In Purgatory:");
        for (UUID uuid : Ledger.uuids()) {
            Ledger.Entry e = Ledger.get(uuid);
            if (e == null) continue;
            sb.append("\n§f").append(e.name)
              .append(" §7- ").append(Messages.humanize(e.remainingMillis(now))).append(" left")
              .append(e.restorable()
                      ? " §e(resurrectable for " + Messages.humanize(e.graceRemainingMillis(now)) + ")"
                      : " §8(purged)");
        }
        src.sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    private static int status(CommandSourceStack src, String name) {
        UUID uuid = Ledger.findByName(name);
        if (uuid == null) {
            src.sendSuccess(() -> Component.literal("§a" + name + " is not in Purgatory."), false);
            return 1;
        }
        Ledger.Entry e = Ledger.get(uuid);
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder("§6" + e.name + "§7:");
        sb.append("\n§7 state: §f").append(e.state);
        sb.append("\n§7 remaining: §f").append(Messages.humanize(e.remainingMillis(now)));
        sb.append("\n§7 died to: §f").append(e.deathReason);
        sb.append("\n§7 data: §f").append(switch (e.dataState()) {
            case ENTOMBED -> "in graveyard" + (Graveyard.isLocked(uuid) ? " §8(locked)" : " §8(unlocked)");
            case LIVE -> e.wipePending ? "§cstill live -- entombment pending" : "live";
            case ERASED -> "§8erased";
        });
        if (e.restorable()) {
            // Surfaced so an admin can see whether resurrection would still restore them.
            sb.append("\n§e grace: ").append(Messages.humanize(e.graceRemainingMillis(now)))
              .append(" left -- resurrection restores everything");
        } else {
            sb.append("\n§8 grace expired -- resurrection only lifts Purgatory");
        }
        src.sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    /**
     * Pardon lifts Purgatory AND cancels the pending hard delete.
     *
     * <p>Inside the grace period that means full restoration: the files are lifted back out
     * of the graveyard and put where vanilla expects them. After it, they are gone and
     * nothing can bring them back -- so the feedback has to say which of the two happened,
     * or the admin has no way to tell.
     *
     * <p>Safe to do here because a pardoned player is necessarily offline: Purgatory was
     * still running, so nothing has written them a fresh profile to be clobbered.
     */
    private static int resurrect(CommandSourceStack src, String name) {
        UUID uuid = Ledger.findByName(name);
        if (uuid == null) {
            src.sendFailure(Component.literal(name + " is not in Purgatory."));
            return 0;
        }
        // No resurrecting yourself. Otherwise the penalty is optional for anyone holding the
        // permission: die, pardon, keep everything, repeat. An admin can still be pardoned --
        // by another admin, or from the console -- so a genuinely bogus death is still
        // recoverable; it just stops being a decision they make alone about themselves.
        ServerPlayer caller = src.getPlayer();
        if (caller != null && caller.getUUID().equals(uuid)) {
            src.sendFailure(Component.literal(
                    "§cYou cannot resurrect yourself. Ask another admin, or use the server console."));
            return 0;
        }

        Ledger.Entry e = Ledger.get(uuid);

        if (!e.restorable()) {
            // Released early rather than resurrected: they still come back with nothing, so
            // they are owed the reincarnation greeting the same as anyone who served the term.
            Ledger.remove(uuid);
            LetheMC.markReincarnated(uuid);
            src.sendSuccess(() -> Component.literal(
                    "§e" + e.name + " released from Purgatory -- too late to resurrect, "
                    + "their Inventory, Ender Chest & XP are gone."), true);
            return 1;
        }

        // Not entombed yet means a failed or not-yet-run move, so the files never left the
        // live folder. Cancelling is the whole restoration in that case.
        if (e.entombed() && !Graveyard.restore(LetheMC.server(), uuid, e.name)) {
            // Keep the entry: the plot is intact and a retry can still save them. Dropping it
            // would strand the files with nothing left pointing at them.
            src.sendFailure(Component.literal(
                    "§cCould not restore " + e.name + " -- files left in the graveyard, see log. "
                    + "Their Purgatory is unchanged; try again."));
            return 0;
        }

        // Keep the entry alive in PARDONED state rather than deleting it. The restored data
        // still has them dead, so they will rejoin to a death screen -- and vanilla's respawn
        // discards inventory and XP unless the mod forces keepInventory for that one respawn.
        // The entry is what carries that instruction; it retires itself immediately after.
        e.state = Ledger.STATE_PARDONED;
        e.wipePending = false;
        e.purgeAt = 0L;
        e.entombRetryAt = 0L;
        e.graveyardAt = 0L;
        Ledger.put(uuid, e);

        src.sendSuccess(() -> Component.literal(
                "§a" + e.name + " resurrected -- Inventory, Ender Chest & XP restored."), true);
        return 1;
    }

    private static int forcePurge(CommandSourceStack src, String name) {
        UUID uuid = Ledger.findByName(name);
        if (uuid == null) {
            src.sendFailure(Component.literal(name + " has no pending purge."));
            return 0;
        }
        Ledger.Entry e = Ledger.get(uuid);
        if (LetheMC.server() != null && LetheMC.server().getPlayerList().getPlayer(uuid) != null) {
            src.sendFailure(Component.literal("§c" + e.name + " is online. Refusing to purge a live player."));
            return 0;
        }
        // Skip the grace period on purpose -- that is what "force" means here. Both sides get
        // cleared, because the files could be in either depending on how entombment went.
        boolean ok = Graveyard.destroy(LetheMC.server(), uuid, e.name)
                & PurgeService.purge(LetheMC.server(), uuid, e.name);
        if (ok) {
            e.wipePending = false;
            Ledger.put(uuid, e);
            LetheMC.onRemainsDestroyed(uuid);
            src.sendSuccess(() -> Component.literal(
                    "§aPurged " + e.name + " -- grace period skipped, nothing left to restore."), true);
            return 1;
        }
        src.sendFailure(Component.literal("§cPurge failed for " + e.name + " -- see log."));
        return 0;
    }

    private static int lock(CommandSourceStack src, String name, int minutes) {
        Config cfg = Config.get();
        if (minutes < 0) minutes = cfg.purgatoryMinutes;

        // Manual locks take the same floor as the config: Purgatory must always outlast
        // the grace period, or the hard delete would still be pending when the ban lifts.
        if (minutes <= cfg.wipeGraceMinutes) {
            final int min = cfg.minimumPurgatoryMinutes();
            src.sendFailure(Component.literal("§cDuration must be greater than wipe.graceMinutes ("
                    + cfg.wipeGraceMinutes + "). Try " + min + " or higher."));
            return 0;
        }

        ServerPlayer target = LetheMC.server() == null ? null
                : LetheMC.server().getPlayerList().getPlayerByName(name);
        if (target == null) {
            src.sendFailure(Component.literal(name + " is not online."));
            return 0;
        }

        long now = System.currentTimeMillis();
        Ledger.Entry e = new Ledger.Entry();
        e.name = target.getName().getString();
        e.state = Ledger.STATE_LOCKED;
        e.deathAt = now;
        e.purgatoryStartsAt = now;
        e.durationMillis = minutes * 60_000L;
        e.graceMillis = cfg.wipeGraceMinutes * 60_000L;
        e.wipePending = true;
        // purgeAt is stamped at entombment, which fires from the tail of PlayerList.remove
        // as soon as the kick below lands. The grace clock starts from the files moving,
        // not from the command, so a slow disconnect cannot eat into it.
        e.entombRetryAt = now;
        e.purgeAt = 0L;
        e.deathReason = "locked by " + src.getTextName();
        Ledger.put(target.getUUID(), e);

        target.connection.disconnect(Messages.format(cfg.messageDeath, e.name, e, now));
        final int m = minutes;
        src.sendSuccess(() -> Component.literal("§aLocked " + e.name + " out for " + m + " minutes."), true);
        return 1;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
