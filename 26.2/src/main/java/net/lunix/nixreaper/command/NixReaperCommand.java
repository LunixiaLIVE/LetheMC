package net.lunix.nixreaper.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.lunix.nixreaper.Config;
import net.lunix.nixreaper.Graveyard;
import net.lunix.nixreaper.Ledger;
import net.lunix.nixreaper.Messages;
import net.lunix.nixreaper.NixReaper;
import net.lunix.nixreaper.PurgeService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;

public final class NixReaperCommand {

    private NixReaperCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(build("nixreaper"));
        dispatcher.register(build("nr"));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> build(String root) {
        return Commands.literal(root)
                .executes(ctx -> info(ctx.getSource()))
                .then(Commands.literal("info").executes(ctx -> info(ctx.getSource())))
                .then(Commands.literal("status").executes(ctx -> selfStatus(ctx.getSource())))
                .then(Commands.literal("admin")
                        .requires(src -> NixReaper.hasBypass(src.permissions()))
                        .then(Commands.literal("config")
                                .then(Commands.literal("list").executes(ctx -> configList(ctx.getSource())))
                                .then(Commands.literal("get")
                                        .then(Commands.argument("key", StringArgumentType.string())
                                                .executes(ctx -> configGet(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "key")))))
                                .then(Commands.literal("set")
                                        .then(Commands.argument("key", StringArgumentType.string())
                                                .then(Commands.argument("value", StringArgumentType.greedyString())
                                                        .executes(ctx -> configSet(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "key"),
                                                                StringArgumentType.getString(ctx, "value")))))))
                        .then(Commands.literal("reload").executes(ctx -> reload(ctx.getSource())))
                        .then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))
                        .then(Commands.literal("status")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .executes(ctx -> status(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "player")))))
                        .then(Commands.literal("pardon")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .executes(ctx -> pardon(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "player")))))
                        .then(Commands.literal("purge")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .executes(ctx -> forcePurge(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "player")))))
                        .then(Commands.literal("lock")
                                .then(Commands.argument("player", StringArgumentType.word())
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
        sb.append("§6=== nixReaper ===\n");

        // Say this first and plainly. A player told they will lose everything, who then
        // does not, has been misled by the mod itself.
        if (NixReaper.isStandingDown()) {
            sb.append("§c§lDISABLED §r§c-- deaths are currently vanilla.\n");
            sb.append("§7Reason: ").append(NixReaper.standDownReason()).append("\n");
            sb.append("§7Nothing is taken and nobody is locked out until this is fixed.");
            src.sendSuccess(() -> Component.literal(sb.toString()), false);
            return 1;
        }

        sb.append("§fWhen you die:\n");
        if (c.wipePlayerData) sb.append("§7 - §cYour inventory, ender chest and XP are erased\n");
        if (c.wipeAdvancements) sb.append("§7 - §cYour advancements and recipe book are erased\n");
        if (c.wipeStats) sb.append("§7 - §cYour statistics are erased\n");
        sb.append("§7 - §cNothing drops on the ground. There is no body to loot.\n");
        sb.append("§f\nYou are then locked out for §e")
          .append(Messages.humanize(c.lockoutMinutes * 60_000L)).append("§f.\n");
        sb.append("§7The death screen stays up for ").append(c.lockoutDeathScreenSeconds)
          .append("s so you can see what killed you.\n");
        sb.append("§7The countdown is real time -- it keeps running while the server is offline.\n");
        sb.append("§f\nIf the death was not your fault, an admin has §e")
          .append(Messages.humanize(c.wipeGraceMinutes * 60_000L))
          .append("§f to pardon you and give it all back.");
        src.sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    private static int selfStatus(CommandSourceStack src) {
        ServerPlayer p = src.getPlayer();
        if (p == null) {
            src.sendFailure(Component.literal("Only a player can use this. Try /nr admin status <player>."));
            return 0;
        }
        Ledger.Entry e = Ledger.get(p.getUUID());
        if (e == null) {
            src.sendSuccess(() -> Component.literal("§aYou are alive and clear. Nothing pending."), false);
        } else {
            long now = System.currentTimeMillis();
            src.sendSuccess(() -> Component.literal("§eLockout: " + Messages.humanize(e.remainingMillis(now))
                    + " remaining."), false);
        }
        return 1;
    }

    // ------------------------------------------------------------------
    // Admin: config
    // ------------------------------------------------------------------

    private static int configList(CommandSourceStack src) {
        StringBuilder sb = new StringBuilder("§6nixReaper config:");
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
        // Re-run the startup checks so an admin who has just fixed server.properties gets
        // the mod back without a second restart -- and so one that has just been broken is
        // caught here rather than at the next death.
        if (NixReaper.server() != null) {
            NixReaper.recheckPreconditions(NixReaper.server());
        }
        if (NixReaper.isStandingDown()) {
            src.sendFailure(Component.literal("§cReloaded, but nixReaper is DISABLED -- "
                    + NixReaper.standDownReason()));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("§anixReaper config and ledger reloaded."), true);
        return 1;
    }

    // ------------------------------------------------------------------
    // Admin: lockouts
    // ------------------------------------------------------------------

    private static int list(CommandSourceStack src) {
        long now = System.currentTimeMillis();
        if (NixReaper.isStandingDown()) {
            src.sendFailure(Component.literal("§cnixReaper is DISABLED -- " + NixReaper.standDownReason()
                    + "\n§7No lockouts are being enforced."));
            return 0;
        }
        if (Ledger.all().isEmpty()) {
            src.sendSuccess(() -> Component.literal("§aNobody is locked out."), false);
            return 1;
        }
        StringBuilder sb = new StringBuilder("§6Locked out:");
        for (UUID uuid : Ledger.uuids()) {
            Ledger.Entry e = Ledger.get(uuid);
            if (e == null) continue;
            sb.append("\n§f").append(e.name)
              .append(" §7- ").append(Messages.humanize(e.remainingMillis(now))).append(" left")
              .append(e.restorable()
                      ? " §e(restorable for " + Messages.humanize(e.graceRemainingMillis(now)) + ")"
                      : " §8(purged)");
        }
        src.sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    private static int status(CommandSourceStack src, String name) {
        UUID uuid = Ledger.findByName(name);
        if (uuid == null) {
            src.sendSuccess(() -> Component.literal("§a" + name + " has no lockout."), false);
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
            // Surfaced so an admin can see whether a pardon would still restore them.
            sb.append("\n§e grace: ").append(Messages.humanize(e.graceRemainingMillis(now)))
              .append(" left -- pardon now restores everything");
        } else {
            sb.append("\n§8 grace expired -- pardon only lifts the lockout");
        }
        src.sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    /**
     * Pardon lifts the lockout AND cancels the pending hard delete.
     *
     * <p>Inside the grace period that means full restoration: the files are lifted back out
     * of the graveyard and put where vanilla expects them. After it, they are gone and
     * nothing can bring them back -- so the feedback has to say which of the two happened,
     * or the admin has no way to tell.
     *
     * <p>Safe to do here because a pardoned player is necessarily offline: the lockout was
     * still running, so nothing has written them a fresh profile to be clobbered.
     */
    private static int pardon(CommandSourceStack src, String name) {
        UUID uuid = Ledger.findByName(name);
        if (uuid == null) {
            src.sendFailure(Component.literal(name + " is not locked out."));
            return 0;
        }
        // No pardoning yourself. Otherwise the penalty is optional for anyone holding the
        // permission: die, pardon, keep everything, repeat. An admin can still be pardoned --
        // by another admin, or from the console -- so a genuinely bogus death is still
        // recoverable; it just stops being a decision they make alone about themselves.
        ServerPlayer caller = src.getPlayer();
        if (caller != null && caller.getUUID().equals(uuid)) {
            src.sendFailure(Component.literal(
                    "§cYou cannot pardon yourself. Ask another admin, or use the server console."));
            return 0;
        }

        Ledger.Entry e = Ledger.get(uuid);

        if (!e.restorable()) {
            Ledger.remove(uuid);
            src.sendSuccess(() -> Component.literal(
                    "§ePardoned " + e.name + " -- lockout lifted, but data was already purged."), true);
            return 1;
        }

        // Not entombed yet means a failed or not-yet-run move, so the files never left the
        // live folder. Cancelling is the whole restoration in that case.
        if (e.entombed() && !Graveyard.restore(NixReaper.server(), uuid, e.name)) {
            // Keep the entry: the plot is intact and a retry can still save them. Dropping it
            // would strand the files with nothing left pointing at them.
            src.sendFailure(Component.literal(
                    "§cCould not restore " + e.name + " -- files left in the graveyard, see log. "
                    + "Their lockout is unchanged; try again."));
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
                "§aPardoned " + e.name + " -- purge cancelled, data restored."), true);
        return 1;
    }

    private static int forcePurge(CommandSourceStack src, String name) {
        UUID uuid = Ledger.findByName(name);
        if (uuid == null) {
            src.sendFailure(Component.literal(name + " has no pending purge."));
            return 0;
        }
        Ledger.Entry e = Ledger.get(uuid);
        if (NixReaper.server() != null && NixReaper.server().getPlayerList().getPlayer(uuid) != null) {
            src.sendFailure(Component.literal("§c" + e.name + " is online. Refusing to purge a live player."));
            return 0;
        }
        // Skip the grace period on purpose -- that is what "force" means here. Both sides get
        // cleared, because the files could be in either depending on how entombment went.
        boolean ok = Graveyard.destroy(NixReaper.server(), uuid, e.name)
                & PurgeService.purge(NixReaper.server(), uuid, e.name);
        if (ok) {
            e.wipePending = false;
            Ledger.put(uuid, e);
            src.sendSuccess(() -> Component.literal(
                    "§aPurged " + e.name + " -- grace period skipped, nothing left to restore."), true);
            return 1;
        }
        src.sendFailure(Component.literal("§cPurge failed for " + e.name + " -- see log."));
        return 0;
    }

    private static int lock(CommandSourceStack src, String name, int minutes) {
        Config cfg = Config.get();
        if (minutes < 0) minutes = cfg.lockoutMinutes;

        // Manual locks take the same floor as the config: a lockout must always outlast
        // the grace period, or the hard delete would still be pending when the ban lifts.
        if (minutes <= cfg.wipeGraceMinutes) {
            final int min = cfg.minimumLockoutMinutes();
            src.sendFailure(Component.literal("§cDuration must be greater than wipe.graceMinutes ("
                    + cfg.wipeGraceMinutes + "). Try " + min + " or higher."));
            return 0;
        }

        ServerPlayer target = NixReaper.server() == null ? null
                : NixReaper.server().getPlayerList().getPlayerByName(name);
        if (target == null) {
            src.sendFailure(Component.literal(name + " is not online."));
            return 0;
        }

        long now = System.currentTimeMillis();
        Ledger.Entry e = new Ledger.Entry();
        e.name = target.getName().getString();
        e.state = Ledger.STATE_LOCKED;
        e.deathAt = now;
        e.lockoutStartsAt = now;
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
