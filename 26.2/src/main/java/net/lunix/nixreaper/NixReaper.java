package net.lunix.nixreaper;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.lunix.nixreaper.command.NixReaperCommand;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.permissions.Permissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class NixReaper implements ModInitializer {

    public static final String MOD_ID = "nixreaper";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /**
     * Players currently inside vanilla's death processing. While a UUID is in here the
     * keepInventory gamerule reads as true (see GameRulesMixin) so nothing drops on the
     * ground -- the items are destroyed later by the purge instead. The real gamerule is
     * never modified; this override lives purely in memory.
     */
    private static final Set<UUID> DYING_NOW = new HashSet<>();

    /**
     * Players inside the single respawn that follows a pardon.
     *
     * <p>A pardon restores the files, but the player is still dead in that restored data, so
     * they come back to a death screen. Vanilla's respawn then throws the inventory and XP
     * away unless keepInventory reads true at that instant -- which is how the first round of
     * in-game testing ended with a "fully restored" player owning nothing. The window is
     * bracketed tightly around the PlayerList.respawn call itself.
     */
    private static final Set<UUID> PARDONED_RESPAWN = new HashSet<>();

    /**
     * Pardoned players who logged in still dead, mapped to when the server should respawn
     * them for it.
     *
     * <p>A pardon restores the player exactly as they were, which includes being dead -- so
     * they arrive at a death screen and have to click through it. On a hardcore server that
     * button reads "Spectate world", because the client renders it from the single
     * {@code isHardcore} flag in the login packet. A server-side mod cannot relabel it, and
     * the only way to change the word would be to lie about hardcore at login, which would
     * also strip the hardcore hearts.
     *
     * <p>So the death screen is removed instead of relabelled: the server presses the button
     * for them and they simply appear in the world. Counted in ticks rather than performed
     * inline in the join event, because the respawn has to happen on the server thread at a
     * point where the player is fully placed.
     */
    private static final Map<UUID, Integer> AUTO_RESPAWN_IN_TICKS = new HashMap<>();

    /**
     * Ticks to wait after login before respawning a pardoned player.
     *
     * <p>Deliberately as small as possible: the sooner it fires, the less chance the player
     * sees a death screen at all. It was originally a wall-clock second, chosen when the
     * client desync was misdiagnosed as a timing problem -- the actual cause was the packet
     * listener still pointing at the pre-respawn player, and delay had nothing to do with it.
     *
     * <p>One tick means the respawn happens at the end of the tick the player joined on. If a
     * client ever turns out to need longer to finish its login sequence, this is the single
     * number to raise.
     */
    private static final int AUTO_RESPAWN_DELAY_TICKS = 1;

    /**
     * Players admitted back with nothing, owed the reincarnation greeting on arrival.
     *
     * <p>Populated at the join gate (which runs before a player object exists, so the message
     * cannot be sent there) and consumed by the JOIN event a moment later. Deliberately NOT
     * persisted: if the server restarts between those two points the player simply misses a
     * flavour line, which is not worth a ledger entry outliving its purpose.
     */
    private static final Set<UUID> AWAITING_REINCARNATION = new HashSet<>();

    private static MinecraftServer server;

    /**
     * Set when a startup precondition fails. The mod then does NOTHING -- deaths are vanilla,
     * joins are unrestricted, no files move. Half-working is the one outcome worse than off:
     * taking a player's inventory and then failing to manage the lockout is strictly worse
     * than never having touched them.
     */
    private static boolean standingDown = false;
    private static String standDownReason = "";

    @Override
    public void onInitialize() {
        Config.load();

        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            Ledger.load();

            String problem = checkPreconditions(s);
            if (problem != null) {
                standDown(s, problem);
                return;
            }

            Taunts.load();
            Incarnations.load();
            recover(s);
            LOGGER.info("nixReaper ready -- lockout {} min, grace {} min",
                    Config.get().lockoutMinutes, Config.get().wipeGraceMinutes);
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            Ledger.save();
            // Hand the OS its file locks back explicitly. Process exit would do it anyway,
            // but not if the JVM is still winding down while a backup job starts.
            Graveyard.releaseAll();
        });

        // Fires at the head of death processing.
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (standingDown) return true;
            if (entity instanceof ServerPlayer player && !isExempt(player)) {
                DYING_NOW.add(player.getUUID());
            }
            return true;
        });

        // Fires at the tail, after drops would have happened.
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (standingDown) return;
            if (!(entity instanceof ServerPlayer player)) return;
            DYING_NOW.remove(player.getUUID());
            if (isExempt(player)) return;
            onPlayerDeath(player);
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> {
            if (standingDown) return;
            greetReincarnated(handler.getPlayer());
            retirePardonIfAlive(handler.getPlayer());
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> {
            if (standingDown) return;
            // Bound the pardoned-respawn flag: it is normally cleared at the tail of the
            // respawn packet handler, but a player dropping mid-respawn would strand it, and
            // it forces keepInventory globally while set.
            ServerPlayer p = handler.getPlayer();
            if (p != null) {
                PARDONED_RESPAWN.remove(p.getUUID());
                AUTO_RESPAWN_IN_TICKS.remove(p.getUUID());
            }
            onDisconnect(p);
        });

        ServerTickEvents.END_SERVER_TICK.register(s -> {
            if (standingDown) return;
            tick(s);
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) ->
                NixReaperCommand.register(dispatcher));
    }

    // ------------------------------------------------------------------
    // Preconditions
    // ------------------------------------------------------------------

    /**
     * The empty-pause check.
     *
     * <p>{@code pause-when-empty-seconds} stops the server ticking once the last player
     * leaves. The hard delete runs on the tick loop -- and the moment it is due is precisely
     * the moment a dying player has just left, very often as the last player online. So on a
     * pausing server the purge silently never runs: the player's files sit in the graveyard,
     * the lockout expires with the wipe still pending, and the death penalty quietly does not
     * happen. Verified in testing on 2026-08-28.
     *
     * <p>nixReaper deliberately does NOT override the setting. It is a real setting an admin
     * chose, usually to save CPU on a host running several servers, and a mod silently
     * reversing it is a nasty surprise. Refusing to run instead makes the conflict visible
     * and leaves the choice where it belongs.
     *
     * @return null if everything is in order, else a human-readable reason to stand down.
     */
    private static String checkPreconditions(MinecraftServer s) {
        int pause = emptyPauseSeconds(s);
        if (pause > 0) {
            return "server.properties has pause-when-empty-seconds=" + pause
                    + " (must be 0)";
        }
        return null;
    }

    /** The configured empty-pause, or 0 when it cannot apply (integrated server). */
    public static int emptyPauseSeconds(MinecraftServer s) {
        // DedicatedServer widens this to public; the MinecraftServer base declares it
        // protected and always answers 0, so the cast is what actually reads the property.
        return s instanceof DedicatedServer ds ? ds.pauseWhenEmptySeconds() : 0;
    }

    /**
     * Refuse to operate, loudly, and hand back anything already taken.
     *
     * <p>Undoing pending state matters: if the admin enables the pause while players are
     * mid-lockout, going inert without restoring would strand their files in the graveyard
     * with nothing left to ever purge or return them. Backing out leaves the world in a
     * consistent vanilla state.
     */
    private static void standDown(MinecraftServer s, String reason) {
        standingDown = true;
        standDownReason = reason;

        LOGGER.error("+----------------------------------------------------------------+");
        LOGGER.error("|  nixReaper is DISABLED and will not take anyone's items.       |");
        LOGGER.error("+----------------------------------------------------------------+");
        LOGGER.error("  Reason: {}", reason);
        LOGGER.error("");
        LOGGER.error("  The purge runs on the server tick loop. A server that pauses when");
        LOGGER.error("  empty stops ticking exactly when a dying player has just left, so");
        LOGGER.error("  the wipe would silently never happen and the penalty would not");
        LOGGER.error("  apply. Rather than half-work, nixReaper stands down entirely.");
        LOGGER.error("");
        LOGGER.error("  To fix: set pause-when-empty-seconds=0 in server.properties and");
        LOGGER.error("  restart. Deaths are vanilla until then.");
        LOGGER.error("+----------------------------------------------------------------+");

        int undone = 0;
        for (UUID uuid : Ledger.uuids()) {
            Ledger.Entry e = Ledger.get(uuid);
            if (e == null) continue;
            if (e.entombed() && e.wipePending && Graveyard.restore(s, uuid, e.name)) {
                undone++;
            }
            Ledger.remove(uuid);
        }
        if (undone > 0) {
            LOGGER.error("  Restored {} player(s) out of the graveyard and cleared the ledger.", undone);
        }
    }

    /** True when a precondition failed and the mod is inert. */
    public static boolean isStandingDown() {
        return standingDown;
    }

    public static String standDownReason() {
        return standDownReason;
    }

    /** Re-run the precondition check, so /nr admin reload can pick up a fix. */
    public static void recheckPreconditions(MinecraftServer s) {
        String problem = checkPreconditions(s);
        if (problem == null && standingDown) {
            standingDown = false;
            standDownReason = "";
            LOGGER.info("nixReaper re-enabled -- preconditions now satisfied");
        } else if (problem != null && !standingDown) {
            standDown(s, problem);
        }
    }

    // ------------------------------------------------------------------
    // Death -> lockout
    // ------------------------------------------------------------------

    private static void onPlayerDeath(ServerPlayer player) {
        Config cfg = Config.get();
        long now = System.currentTimeMillis();

        Ledger.Entry e = new Ledger.Entry();
        e.name = player.getName().getString();
        e.state = Ledger.STATE_DYING;
        e.deathAt = now;
        e.lockoutStartsAt = 0L; // stamped when the death screen ends or they disconnect
        // Snapshot the duration so a later `config set lockout.minutes` doesn't
        // retroactively re-time anyone already locked out.
        e.durationMillis = cfg.lockoutMinutes * 60_000L;
        e.graceMillis = cfg.wipeGraceMinutes * 60_000L; // snapshotted for the same reason
        e.wipePending = false;
        e.graveyardAt = 0L;
        e.entombRetryAt = 0L;
        e.purgeAt = 0L;
        try {
            e.deathReason = player.getCombatTracker().getDeathMessage().getString();
        } catch (Exception ignored) {
            e.deathReason = "unknown";
        }

        Ledger.put(player.getUUID(), e);
        LOGGER.info("{} died -- lockout {} min pending", e.name, cfg.lockoutMinutes);
    }

    /**
     * Stamps the lockout start. Called for any disconnect; only acts if the player was
     * still in the DYING state, i.e. the death screen was up when they left.
     *
     * <p>The clock starts at whichever came first, the death screen timing out or the
     * player disconnecting by hand -- so sitting through the screen is neither a penalty
     * nor an advantage.
     */
    private static void onDisconnect(ServerPlayer player) {
        if (player == null) return;
        startLockout(player.getUUID());
    }

    /**
     * DYING -> LOCKED. Idempotent, because two different hooks race to call it and either
     * order is fine: this event, and the tail of PlayerList.remove.
     */
    private static void startLockout(UUID uuid) {
        Ledger.Entry e = Ledger.get(uuid);
        if (e == null || !Ledger.STATE_DYING.equals(e.state)) return;

        long now = System.currentTimeMillis();
        e.state = Ledger.STATE_LOCKED;
        e.lockoutStartsAt = now;
        e.wipePending = true;
        e.entombRetryAt = now; // eligible immediately; the remove hook normally beats the tick
        Ledger.put(uuid, e);

        LOGGER.info("{} locked out until {}", e.name, Messages.clock(e.remainingMillis(now)));
    }

    /**
     * Called from the tail of {@code PlayerList.remove}, which is the earliest moment the
     * files can safely be taken.
     *
     * <p>Vanilla's {@code PlayerList.save} writes playerdata, stats and advancements, and all
     * three are fully synchronous -- no IO pool, no future. So by the time remove() returns,
     * every file exists in its final state and nothing will touch it again while the player
     * is offline. Entombing here replaces the old five-minute wait with an ordering fact.
     */
    public static void onPlayerRemoved(ServerPlayer player) {
        if (standingDown || player == null || server == null) return;
        UUID uuid = player.getUUID();

        // Belt and braces: if the Fabric disconnect event has not fired yet, do the state
        // transition here so entombment is never deferred a tick on hook ordering alone.
        startLockout(uuid);

        Ledger.Entry e = Ledger.get(uuid);
        if (e == null || !e.wipePending || e.entombed()) return;
        tryEntomb(server, uuid, e);
    }

    /** Moves a player into the graveyard and starts the grace clock. */
    private static void tryEntomb(MinecraftServer s, UUID uuid, Ledger.Entry e) {
        long now = System.currentTimeMillis();
        if (Graveyard.entomb(s, uuid, e.name)) {
            e.graveyardAt = now;
            e.entombRetryAt = 0L;
            e.purgeAt = now + e.graceMillis;
            Ledger.put(uuid, e);
            LOGGER.info("{} entombed -- live data clear, pardon restores for another {}",
                    e.name, Messages.humanize(e.graceMillis));
        } else {
            // Something holds a file open. Keep the obligation and come back to it; the
            // player is locked out either way, so a slow entombment is not a hole.
            e.entombRetryAt = now + 5_000L;
            Ledger.put(uuid, e);
        }
    }

    // ------------------------------------------------------------------
    // Tick
    // ------------------------------------------------------------------

    private static void tick(MinecraftServer s) {
        long now = System.currentTimeMillis();
        Config cfg = Config.get();

        // 0. Respawn pardoned players who logged back in dead.
        runAutoRespawns(s);

        // 0b. Free animals whose owner is living a different life now.
        if (s.getTickCount() % Math.max(1, cfg.petsCheckIntervalTicks) == 0) {
            Pets.sweep(s);
        }

        // 1. Kick anyone whose death screen has run its course.
        long holdMillis = cfg.lockoutDeathScreenSeconds * 1000L;
        for (ServerPlayer player : s.getPlayerList().getPlayers().toArray(new ServerPlayer[0])) {
            Ledger.Entry e = Ledger.get(player.getUUID());
            if (e == null || !Ledger.STATE_DYING.equals(e.state)) continue;
            if (now - e.deathAt < holdMillis) continue;
            player.connection.disconnect(Messages.format(cfg.messageDeath, e.name, e, now));
        }

        // 2. Retry any entombment that lost a fight with an open file handle.
        for (UUID uuid : Ledger.uuids()) {
            Ledger.Entry e = Ledger.get(uuid);
            if (e == null || !e.wipePending || e.entombed()) continue;
            if (e.entombRetryAt <= 0 || now < e.entombRetryAt) continue;
            if (s.getPlayerList().getPlayer(uuid) != null) continue; // vanilla would rewrite it
            tryEntomb(s, uuid, e);
        }

        // 3. Hard-delete anyone whose grace period has run out.
        for (UUID uuid : Ledger.uuids()) {
            Ledger.Entry e = Ledger.get(uuid);
            if (e == null || !e.wipePending || !e.entombed()) continue;
            if (e.purgeAt <= 0 || now < e.purgeAt) continue;
            if (s.getPlayerList().getPlayer(uuid) != null) continue; // never purge someone online
            if (Graveyard.destroy(s, uuid, e.name)) {
                e.wipePending = false;
                Ledger.put(uuid, e);
                onRemainsDestroyed(uuid);
            } else {
                // Retry rather than dropping the obligation. The locks we hold mean this
                // should be unreachable on Windows, which is where it used to happen.
                e.purgeAt = now + 30_000L;
                Ledger.put(uuid, e);
            }
        }

        // 4. Drop entries whose lockout has expired and whose purge is done.
        for (UUID uuid : Ledger.uuids()) {
            Ledger.Entry e = Ledger.get(uuid);
            if (e == null || e.wipePending) continue;
            if (Ledger.STATE_LOCKED.equals(e.state) && e.expired(now)) {
                // Mark the reincarnation HERE, not at the join gate. This is the moment it
                // happens, and it is also the moment the evidence is destroyed: the entry is
                // gone within a tick of expiry, so a player logging in even seconds later
                // would reach checkLogin with nothing to read and never be greeted.
                if (e.dataState() == Ledger.DataState.ERASED || !e.restorable()) {
                    AWAITING_REINCARNATION.add(uuid);
                    // Normally the incarnation was already rotated when the remains were
                    // destroyed, which is the earlier and more important moment -- it is what
                    // stops a friend emptying someone's chest donkey while they are locked
                    // out. Only rotate here if that never happened: entombment failed, or the
                    // wipe was disabled, so this is the first point the old life truly ends.
                    if (!e.entombed()) {
                        Incarnations.rotate(uuid);
                    }
                }
                Ledger.remove(uuid);
                LOGGER.info("{} left Purgatory -- reincarnated", e.name);
            }
        }
    }

    /**
     * The remains are gone for good. Ends the old life at that same instant.
     *
     * <p>Rotating here rather than only when Purgatory expires closes a window. Between the
     * hard delete and the lockout ending, a player is offline for potentially hours while
     * their tamed animals are still theirs -- long enough for a friend to empty their chest
     * donkey and hand it all back afterwards, which is precisely the loophole that destroying
     * the animals exists to shut. Once the belongings are destroyed, everything of that life
     * goes together.
     *
     * <p>Deliberately NOT called on resurrection: that cancels the destruction, so the life
     * continues and the animals stay theirs.
     */
    public static void onRemainsDestroyed(UUID uuid) {
        Incarnations.rotate(uuid);
    }

    /**
     * Rebuilds process state from the ledger and settles anything that fell due while the
     * server was down.
     *
     * <p>Three jobs: re-take the file locks (they died with the last JVM), entomb anyone the
     * shutdown caught mid-flight, and run any hard delete whose grace period elapsed while
     * the server was off. Real-time lockouts keep running across a restart, so grace periods
     * have to as well or a restart would quietly extend everyone's mercy window.
     */
    private static void recover(MinecraftServer s) {
        long now = System.currentTimeMillis();
        for (UUID uuid : Ledger.uuids()) {
            Ledger.Entry e = Ledger.get(uuid);
            if (e == null) continue;

            // Ledgers written before the graveyard existed have no grace snapshot.
            if (e.graceMillis <= 0) e.graceMillis = Config.get().wipeGraceMinutes * 60_000L;

            // A crash during the death screen leaves a DYING entry with no start stamp.
            // Fall back to the moment of death -- errs in the player's favour by at most
            // deathScreenSeconds.
            if (Ledger.STATE_DYING.equals(e.state)) {
                e.state = Ledger.STATE_LOCKED;
                e.lockoutStartsAt = e.deathAt;
                e.wipePending = true;
                e.entombRetryAt = now;
                Ledger.put(uuid, e);
                LOGGER.warn("Recovered interrupted death for {}", e.name);
            }

            if (!e.wipePending) continue;

            if (!e.entombed()) {
                // Shutdown flushed everything on its way out, so the files are settled and
                // this is safe to run immediately.
                tryEntomb(s, uuid, e);
                continue;
            }

            // Locks do not survive the JVM. Re-take them before anything else can.
            Graveyard.lockPlot(s, uuid, e.name);

            if (e.purgeAt > 0 && now >= e.purgeAt) {
                LOGGER.info("Grace for {} expired while the server was down", e.name);
                if (Graveyard.destroy(s, uuid, e.name)) {
                    e.wipePending = false;
                    Ledger.put(uuid, e);
                    onRemainsDestroyed(uuid);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Hooks used by mixins
    // ------------------------------------------------------------------

    /**
     * The death-screen line, built from {@code message.deathScreen}.
     *
     * <p>Called while the player is still inside {@code die()}, so there is no ledger entry
     * yet -- {@code %time_remaining%} therefore reflects the configured lockout rather than a
     * live countdown. That is the right value anyway: the clock has not started.
     *
     * <p>Falls through to the vanilla message untouched when the mod is inert or the player is
     * exempt, so a server that is standing down never claims to have done something.
     */
    public static Component deathScreenMessage(ServerPlayer player, Component vanillaMessage) {
        if (standingDown || player == null || isExempt(player)) return vanillaMessage;

        String template = Config.get().messageDeathScreen;
        if (template == null || template.isBlank()) return vanillaMessage;

        String reason = vanillaMessage == null ? "" : vanillaMessage.getString();
        String out = template
                .replace("%death_reason%", reason)
                .replace("%player%", player.getName().getString())
                .replace("%time_remaining%", Messages.humanize(Config.get().lockoutMinutes * 60_000L))
                .replace("%grace_remaining%", Messages.humanize(Config.get().wipeGraceMinutes * 60_000L));
        return Component.literal(out);
    }

    /** True while this player is inside death processing -- forces keepInventory on. */
    public static boolean isDying(UUID uuid) {
        return DYING_NOW.contains(uuid);
    }

    /**
     * True while ANY player is inside death processing.
     *
     * <p>GameRules.getBoolean has no player context, so the override is keyed on "is a
     * death being processed right now" rather than on a specific UUID. The window is a
     * fraction of a single tick inside die(), and if two players die in the same tick both
     * want the same answer anyway, so the coarser check is safe.
     */
    public static boolean isAnyoneDying() {
        return !standingDown && (!DYING_NOW.isEmpty() || !PARDONED_RESPAWN.isEmpty());
    }

    /**
     * Brackets the respawn of a pardoned player so vanilla keeps their inventory and XP.
     *
     * <p>Called from a redirect around {@code PlayerList.respawn} rather than from the head
     * of the packet handler: the handler can run twice (once on the netty thread, which
     * aborts via {@code ensureRunningOnSameThread}, then again on the server thread), and a
     * flag set on the aborted pass would never be cleared. Wrapping the call itself means the
     * flag cannot outlive the respawn it exists for.
     *
     * @return true if this respawn is a pardoned one, i.e. the caller must clear it after.
     */
    public static boolean beginPardonedRespawn(ServerPlayer player) {
        if (standingDown || player == null) return false;
        Ledger.Entry e = Ledger.get(player.getUUID());
        if (e == null || !Ledger.STATE_PARDONED.equals(e.state)) return false;
        PARDONED_RESPAWN.add(player.getUUID());
        return true;
    }

    /**
     * Retires a PARDONED entry for a player who came back alive.
     *
     * <p>Normally the entry is spent by the respawn that follows a pardon. But a player
     * pardoned after {@code /nr admin lock} was never dead, so no respawn is coming and the
     * entry would sit in the ledger forever waiting for one.
     */
    private static void retirePardonIfAlive(ServerPlayer player) {
        if (player == null) return;
        Ledger.Entry e = Ledger.get(player.getUUID());
        if (e == null || !Ledger.STATE_PARDONED.equals(e.state)) return;
        if (!player.isDeadOrDying()) {
            Ledger.remove(player.getUUID());
            LOGGER.info("{} rejoined alive after pardon -- nothing to restore", e.name);
            return;
        }
        // Dead and pardoned: respawn them ourselves on the next tick, rather than leaving them
        // staring at a death screen whose button is mislabelled on hardcore servers.
        AUTO_RESPAWN_IN_TICKS.put(player.getUUID(), AUTO_RESPAWN_DELAY_TICKS);
    }

    /**
     * Respawns pardoned players who came back dead, so they never see the death screen.
     *
     * <p>Calls {@code PlayerList.respawn} directly rather than going through the packet
     * handler, which means hardcore's {@code setGameMode(SPECTATOR)} is simply never reached --
     * that call lives in {@code handleClientCommand}, not in {@code respawn} itself. The
     * keepInventory bracket is applied here by hand for the same reason.
     *
     * <p>If the player clicks through the screen before this fires, the packet handler's
     * redirects do the same job and retire the entry; this then finds nothing to do.
     */
    private static void runAutoRespawns(MinecraftServer s) {
        if (AUTO_RESPAWN_IN_TICKS.isEmpty()) return;

        for (UUID uuid : new ArrayList<>(AUTO_RESPAWN_IN_TICKS.keySet())) {
            int ticksLeft = AUTO_RESPAWN_IN_TICKS.get(uuid) - 1;
            if (ticksLeft > 0) {
                AUTO_RESPAWN_IN_TICKS.put(uuid, ticksLeft);
                continue;
            }
            AUTO_RESPAWN_IN_TICKS.remove(uuid);

            ServerPlayer player = s.getPlayerList().getPlayer(uuid);
            if (player == null) continue; // left again before we got to them

            Ledger.Entry e = Ledger.get(uuid);
            if (e == null || !Ledger.STATE_PARDONED.equals(e.state)) continue; // already handled
            if (!player.isDeadOrDying()) {
                Ledger.remove(uuid);
                continue;
            }

            autoRespawning = true;
            try {
                // Press the button on their behalf rather than calling PlayerList.respawn
                // directly. Vanilla does three more things straight after that call --
                // reassigns the connection's player field to the NEW entity, resetPosition(),
                // restartClientLoadTimerAfterRespawn() -- and skipping them leaves the packet
                // listener pointing at the old, removed player. The client then desyncs:
                // inventory stops responding and interactions are validated against a corpse.
                // Replaying the packet runs the whole sequence, and the keepInventory and
                // no-spectator redirects hang off this method anyway, so they apply for free.
                player.connection.handleClientCommand(
                        new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
            } catch (Exception ex) {
                // Leave the entry in place so the player can still click through manually.
                LOGGER.error("Auto-respawn failed for {}; they can respawn manually", e.name, ex);
                continue;
            } finally {
                autoRespawning = false;
            }

            // The packet handler's TAIL retires the entry and logs. If it somehow did not,
            // clear it here so a resurrection cannot be spent twice.
            if (Ledger.get(uuid) != null) Ledger.remove(uuid);
        }
    }

    /**
     * True while this player is inside the respawn that follows their pardon.
     *
     * <p>Read by the mixin that suppresses hardcore's spectator conversion: on a hardcore
     * server the respawn is immediately followed by an unconditional
     * {@code setGameMode(SPECTATOR)}, which would hand a pardoned player all their belongings
     * and then lock them out of using any of them. There is no "has died" flag in vanilla to
     * clear -- the spectator state is re-derived from {@code isHardcore()} at every respawn,
     * so the only place to intervene is that call.
     */
    public static boolean isPardonedRespawn(ServerPlayer player) {
        return player != null && PARDONED_RESPAWN.contains(player.getUUID());
    }

    /**
     * True while the server is driving the respawn itself rather than the player clicking.
     *
     * <p>Only affects wording. Auto-respawn works by replaying the respawn packet, so the
     * packet handler's TAIL runs for both paths and logged its own line on top of the
     * auto-respawn's -- one respawn, two log entries, and the pair could not be told apart.
     */
    private static boolean autoRespawning = false;

    /** Ends the bracket and retires the ledger entry -- the pardon is now fully spent. */
    public static void endPardonedRespawn(ServerPlayer player) {
        if (player == null) return;
        UUID uuid = player.getUUID();
        PARDONED_RESPAWN.remove(uuid);
        Ledger.Entry e = Ledger.get(uuid);
        if (e != null && Ledger.STATE_PARDONED.equals(e.state)) {
            Ledger.remove(uuid);
            LOGGER.info("{} {} after resurrection -- Inventory, Ender Chest & XP intact",
                    e.name, autoRespawning ? "auto-respawned" : "respawned");
        }
    }

    /** True if the death screen should stay up, i.e. swallow the respawn request. */
    public static boolean shouldBlockRespawn(ServerPlayer player) {
        if (standingDown) return false;
        Ledger.Entry e = Ledger.get(player.getUUID());
        return e != null && Ledger.STATE_DYING.equals(e.state);
    }

    /**
     * Join gate. Returns a disconnect reason, or null to admit.
     *
     * <p>The countdown is computed here rather than stored, so it is accurate on every
     * attempt. This is also the backstop that stops anyone being admitted mid-purge --
     * unreachable in normal operation now that the config constraint guarantees a lockout
     * always outlasts the purge delay, but kept for restart recovery and failed deletes.
     */
    public static Component checkLogin(UUID uuid, String name) {
        if (standingDown) return null; // admit everyone; the mod is inert
        Ledger.Entry e = Ledger.get(uuid);
        if (e == null) return null;

        // Pardoned: admit them and leave the entry alone. It is still needed to force
        // keepInventory through the respawn they are about to do, and must NOT trigger the
        // wipePending sweep below -- that would delete the very files the pardon restored.
        if (Ledger.STATE_PARDONED.equals(e.state)) return null;

        long now = System.currentTimeMillis();

        if (Ledger.STATE_LOCKED.equals(e.state) && !e.expired(now)) {
            return Messages.format(Config.get().messageRejoin, name, e, now);
        }

        if (e.wipePending && server != null) {
            // Admitting them ends the grace period by definition -- vanilla is about to write
            // them a fresh profile, so a graveyard copy could never be restored over it
            // anyway. Clear both sides: the plot, and any live file a failed entombment left.
            boolean cleared = Graveyard.destroy(server, uuid, e.name)
                    & PurgeService.purge(server, uuid, e.name);
            if (!cleared) {
                return Component.literal("§cnixReaper could not finish clearing your data. Try again shortly.");
            }
            e.wipePending = false;
            Ledger.put(uuid, e);
            onRemainsDestroyed(uuid);
        }

        // Coming back with nothing -- that is a reincarnation, and it should be said out
        // loud rather than leaving them to work out why their inventory is empty.
        if (e.dataState() == Ledger.DataState.ERASED || !e.restorable()) {
            AWAITING_REINCARNATION.add(uuid);
        }

        Ledger.remove(uuid);
        return null;
    }

    /** Marks a player as owed the greeting -- used by a pardon that came too late. */
    public static void markReincarnated(UUID uuid) {
        AWAITING_REINCARNATION.add(uuid);
    }

    /**
     * Tells a returning player what happened to them, once.
     *
     * <p>Without this, Purgatory simply ends and they appear holding nothing, with no
     * explanation anywhere on screen -- which reads like a bug rather than the mechanic.
     */
    private static void greetReincarnated(ServerPlayer player) {
        if (player == null) return;
        if (!AWAITING_REINCARNATION.remove(player.getUUID())) return;

        String text = Config.get().messageReincarnation
                .replace("%player%", player.getName().getString());
        String taunt = Taunts.pick();
        if (taunt != null && !taunt.isBlank()) {
            text = text + "\n§7" + taunt;
        }
        player.sendSystemMessage(Component.literal(text));
        LOGGER.info("{} reincarnated", player.getName().getString());
    }

    /** Exempt from dying entirely -- nixReaper ignores this player's deaths. */
    public static boolean isExempt(ServerPlayer player) {
        return meetsLevel(player.permissions(), Config.get().bypassPermissionLevel);
    }

    /**
     * Allowed to run {@code /nr admin ...}.
     *
     * <p>Deliberately a separate question from {@link #isExempt}. While the two shared one
     * setting an admin could either administer or be mortal, never both -- so on a server whose
     * whole premise is that death costs something, the person running it was quietly exempt.
     */
    public static boolean canAdmin(PermissionSet set) {
        return meetsLevel(set, Config.get().adminPermissionLevel);
    }

    /**
     * 26.x replaced integer permission levels with named Permission objects. The config still
     * speaks in 0-4 because that is what server admins know, so map it here rather than
     * leaking the new vocabulary into the config file.
     *
     * @param level -1 nobody, 0 everyone, 1-4 that permission and above.
     */
    private static boolean meetsLevel(PermissionSet set, int level) {
        if (level < 0) return false;  // nobody -- the point of the default
        if (level == 0) return true;  // everyone
        Permission required = switch (level) {
            case 1 -> Permissions.COMMANDS_MODERATOR;
            case 2 -> Permissions.COMMANDS_GAMEMASTER;
            case 3 -> Permissions.COMMANDS_ADMIN;
            default -> Permissions.COMMANDS_OWNER;
        };
        return set.hasPermission(required);
    }

    public static MinecraftServer server() {
        return server;
    }
}
