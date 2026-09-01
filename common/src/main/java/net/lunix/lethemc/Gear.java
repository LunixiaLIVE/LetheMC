package net.lunix.lethemc;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.lunix.lethemc.mixin.ChunkMapAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Destroys stashed gear belonging to a life its holder no longer lives.
 *
 * <p>This closes the largest remaining hole in the mod. Death takes what you are carrying, but
 * until now it left the chest room untouched -- so a well-supplied player lost a set of armour
 * and walked back to a wall of spares. The stash was the whole reason death was survivable.
 *
 * <h2>Only what does not stack</h2>
 * Tools, weapons, armour, elytra, enchanted books, totems, shulker boxes. That restriction is
 * not squeamishness, it is what makes the feature possible at all: the stamp lives in
 * {@code custom_data}, and two stacks whose components differ will not merge. Tagging cobble
 * would leave chests full of piles that refuse to combine and hopper sorters that quietly stop
 * working. Everything unstackable already has a stack size of one, so the tag costs nothing.
 *
 * <p>It is also where the value is. Raw materials can be re-mined; a mending netherite set is
 * the thing that makes a death cheap.
 *
 * <h2>One rule, applied wherever an item is found</h2>
 * <ol>
 *   <li><b>Warded</b> -- its owner is in Purgatory with their remains intact. Left completely
 *       alone: not destroyed, and above all not re-stamped.</li>
 *   <li><b>Stale</b> -- the life that last held it has ended. Destroyed.</li>
 *   <li><b>Otherwise</b>, if a player is holding it, stamped with the life they are living now.</li>
 * </ol>
 *
 * <p>Because the rule is the same everywhere, <em>when</em> a container gets scanned stops
 * mattering. A player who opens a chest a second before the sweep reaches it and grabs their
 * dead life's sword is holding a stale item, and the inventory pass destroys it in their hands.
 * That is why there is no hook on opening a container: it would only buy a second, and the
 * second is already covered.
 *
 * <h2>Last handled by</h2>
 * Ownership follows whoever last had the item in their inventory, rather than sticking to
 * whoever first made it. That is what lets gifts and shared chests work: hand someone a sword
 * and it becomes theirs, and a chest two players use loses only the items belonging to the one
 * who died.
 *
 * <p>The cost is that a player who sees death coming can hand their gear to somebody living and
 * get it back afterwards. The ward closes that from the instant of death onward; before it,
 * nothing can, and no scheme that lets ownership transfer at all could. It is the same trade the
 * villager rule makes when it lets a single living customer spare a hall.
 *
 * <p>Note what is <em>not</em> possible: stashing. A chest is precisely where the sweep looks,
 * so gear cannot be protected, only entrusted -- to someone who then has every reason to keep it.
 */
public final class Gear {

    private Gear() {}

    /** Our compound inside the item's {@code custom_data}. */
    private static final String TAG = "lethemc";
    private static final String OWNER = "owner";
    private static final String LIFE = "life";

    // ------------------------------------------------------------------
    // The stamp
    // ------------------------------------------------------------------

    /** Who last held an item, and the life they were living at the time. */
    public record Mark(UUID owner, String life) {}

    /**
     * Whether this is the kind of item the feature touches at all.
     *
     * <p>{@code isStackable} is false exactly for the things worth protecting -- gear, tools,
     * and anything damaged -- and true for the commodities that must keep merging.
     */
    public static boolean eligible(ItemStack stack) {
        return !stack.isEmpty() && !stack.isStackable();
    }

    /** The stamp on an item, or null if it has never been stashed by anyone. */
    public static Mark markOf(ItemStack stack) {
        return markOf(stack.get(DataComponents.CUSTOM_DATA));
    }

    /**
     * The stamp on a placed block, or null.
     *
     * <p>A shulker box keeps its components when it is placed and hands them back when it is
     * broken, so the same mark that follows the item follows the block. That is what lets the
     * two forms be treated as one thing rather than as a container that happens to be portable.
     */
    public static Mark markOf(BlockEntity be) {
        return markOf(be.components().get(DataComponents.CUSTOM_DATA));
    }

    private static Mark markOf(CustomData data) {
        if (data == null) return null;
        CompoundTag mine = data.copyTag().getCompoundOrEmpty(TAG);
        if (mine.isEmpty()) return null;

        String owner = mine.getString(OWNER).orElse(null);
        String life = mine.getString(LIFE).orElse(null);
        if (owner == null || life == null) return null;
        try {
            return new Mark(UUID.fromString(owner), life);
        } catch (IllegalArgumentException e) {
            // Hand-edited NBT. Treating it as unstamped means we never act on it, which is the
            // harmless direction to fail in for something that deletes player property.
            return null;
        }
    }

    /** Records the life this holder is living now, replacing whatever was there. */
    private static void stamp(ItemStack stack, UUID holder) {
        String life = Incarnations.of(holder);
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            CompoundTag mine = new CompoundTag();
            mine.putString(OWNER, holder.toString());
            mine.putString(LIFE, life);
            tag.put(TAG, mine);
        });
    }

    // ------------------------------------------------------------------
    // Judgement
    // ------------------------------------------------------------------

    /**
     * Whether this item belonged to a life that has ended.
     *
     * <p>Unstamped means acquired before this feature existed, and an unknown owner means a
     * player we have never seen. Both answer no, for the same reason every other stamp check in
     * this mod does: acting on what cannot be dated would empty every chest on the server the
     * first time the sweep ran.
     */
    public static boolean isStale(ItemStack stack) {
        if (!eligible(stack)) return false;
        Mark mark = markOf(stack);
        if (mark == null) return false;
        String living = Incarnations.peek(mark.owner());
        if (living == null) return false;
        return !living.equals(mark.life());
    }

    /**
     * True while this item's owner is in Purgatory with their remains still intact.
     *
     * <p>The window the ward covers is exactly the one in which the item looks perfectly
     * ordinary. During the grace period the owner's incarnation has not rotated yet -- that
     * happens only when the remains are destroyed -- so nothing of theirs is stale, and a
     * staleness test here would produce a ward that never fired. <b>It asks the ledger</b>, like
     * the fox and villager wards do, and for the same reason.
     *
     * <p>Without it the whole feature is defeated in one move: a friend empties the dead
     * player's chest during the grace period, the items are re-stamped to a life that is still
     * running, and they hand them back afterwards.
     */
    public static boolean isWarded(ItemStack stack) {
        if (!Config.get().wipeGear) return false;
        if (!eligible(stack)) return false;
        Mark mark = markOf(stack);
        if (mark == null) return false;

        return wards(mark);
    }

    /**
     * The ward test itself, on an already-parsed mark.
     *
     * <p><b>{@code restorable()} is the half that matters.</b> Purgatory outlives the grace
     * period -- four minutes against two, by default -- so a ledger entry on its own says only
     * "locked out", not "still has something to protect". Warding on the entry alone kept every
     * stashed item safe for the whole lockout, long after the remains it was guarding had been
     * destroyed, and the sweep found nothing to do until the player was already back.
     */
    private static boolean wards(Mark mark) {
        Ledger.Entry e = Ledger.get(mark.owner());
        // No entry means alive and well; PARDONED means resurrected and on the way back;
        // not restorable means the remains are already gone and there is nothing left to guard.
        return e != null && !Ledger.STATE_PARDONED.equals(e.state) && e.restorable();
    }

    /**
     * Whether the ward should actually be enforced against players right now.
     *
     * <p>Separate from {@link #isWarded} because a dry run has to be invisible. In log-only mode
     * the sweep still reasons about wards -- otherwise its report would be wrong -- but an admin
     * evaluating the feature must not have their players quietly unable to pick things up.
     */
    public static boolean isWardEnforced(ItemStack stack) {
        return !Config.get().wipeGearLogOnly && isWarded(stack);
    }

    // ------------------------------------------------------------------

    /** Distinct findings already reported this run, so a dry run does not flood the log. */
    private static final java.util.Set<String> REPORTED = new java.util.HashSet<>();
    private static final int REPORT_CAP = 2000;
    private static int wouldDestroy;

    /**
     * Reports a finding once.
     *
     * <p>Without this a dry run is unreadable. Nothing is destroyed in log-only mode, so the
     * same stale sword in the same chest is found again on every pass -- fifty items in a
     * storage room becomes six hundred identical lines a minute, and the admin the feature was
     * written for learns nothing from it.
     */
    private static void report(ItemStack stack, String where, Mark mark) {
        wouldDestroy++;
        String key = where + '|' + stack.getItem() + '|' + mark.owner() + '|' + mark.life();
        if (!REPORTED.add(key)) return;
        if (REPORTED.size() > REPORT_CAP) {
            if (REPORTED.size() == REPORT_CAP + 1) {
                LetheMC.LOGGER.info("[gear, log only] {} distinct findings reported; "
                        + "further detail suppressed. Totals still follow each sweep.", REPORT_CAP);
            }
            return;
        }
        LetheMC.LOGGER.info("[gear, log only] WOULD destroy {} {} -- last held by a life that has ended",
                stack.getHoverName().getString(), where);
    }

    /**
     * Applies the rule to one item.
     *
     * @param holder the player whose hands it is in, or null for a container or a loose item
     * @return true if the caller should now empty the slot
     */
    private static boolean judge(ItemStack stack, UUID holder, String where) {
        if (!eligible(stack)) return false;

        Mark mark = markOf(stack);

        if (mark != null) {
            // Staleness is asked FIRST. A ward guards remains that can still be handed back, so
            // once they are gone it has nothing left to say -- and letting it answer anyway
            // meant nothing was ever destroyed while the owner sat out the rest of Purgatory.
            String living = Incarnations.peek(mark.owner());
            if (living != null && !living.equals(mark.life())) {
                if (Config.get().wipeGearLogOnly) {
                    report(stack, where, mark);
                    return false;
                }
                LetheMC.LOGGER.info("Destroying {} {} -- last held by a life that has ended",
                        stack.getHoverName().getString(), where);
                return true;
            }

            // Warded: left exactly as it is. Not re-stamped above all -- that is what stops
            // someone picking it up during the grace period and laundering it into their life.
            if (wards(mark)) return false;
        }

        // Not stale and in somebody's hands: this is the life that holds it now.
        if (holder != null && (mark == null || !mark.owner().equals(holder)
                || !mark.life().equals(Incarnations.peek(holder)))) {
            stamp(stack, holder);
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Sweeps
    // ------------------------------------------------------------------

    /** Walks a container, stamping or destroying as the rule says. Returns true if it changed. */
    private static boolean scan(Container container, UUID holder, String where) {
        // An unopened loot chest has not generated its contents yet, and asking for a slot is
        // what makes it roll. Scanning one would unpack every naturally generated chest in
        // every loaded chunk -- so leave them alone. Nothing a player stashed is in there.
        if (container instanceof RandomizableContainer r && r.getLootTable() != null) return false;

        boolean changed = false;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (judge(stack, holder, where)) {
                container.setItem(i, ItemStack.EMPTY);
                changed = true;
            }
        }
        return changed;
    }

    /**
     * Every online player's inventory.
     *
     * <p>This is how "last handled by" is implemented, and doing it as a scan rather than by
     * intercepting each way an item can arrive is deliberate. Pickup, click, shift-click, drag,
     * hotbar swap, crafting output, equipping, a dispenser firing armour onto you -- miss any
     * one of them and it becomes a way to carry gear through a death. A scan cannot miss a path
     * it does not know about.
     */
    private static void sweepPlayers(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID uuid = player.getUUID();
            scan(player.getInventory(), uuid, "in " + player.getGameProfile().name() + "'s inventory");

            // The stack held on the cursor lives in the open menu, not the inventory.
            if (player.containerMenu != null) {
                ItemStack carried = player.containerMenu.getCarried();
                if (judge(carried, uuid, "on " + player.getGameProfile().name() + "'s cursor")) {
                    player.containerMenu.setCarried(ItemStack.EMPTY);
                }
            }
        }
    }

    /**
     * Every container in every loaded chunk, plus loose items and worn equipment.
     *
     * <p>Deliberately not restricted to the places gear is supposed to live. Armour can be put
     * in a furnace without being smelted, a hopper will hold a sword indefinitely, and a
     * decorated pot takes anything -- so the test is whether a thing <em>can</em> hold an item,
     * not whether it ought to. {@code Container} is that test, and it covers chests, barrels,
     * the furnace family, hoppers, droppers, dispensers, brewing stands, shulker boxes,
     * crafters, chiseled bookshelves, decorated pots, jukeboxes, chest boats and chest carts
     * without naming any of them.
     */
    private static void sweepWorld(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            sweepBlockEntities(level);
            sweepEntities(level);
        }
    }

    private static void sweepBlockEntities(ServerLevel level) {
        Long2ObjectMap<ChunkHolder> visible =
                ((ChunkMapAccessor) level.getChunkSource().chunkMap).lethemc$visibleChunks();

        // Blocks to take out once the walk is done. Removing one edits the chunk's block entity
        // map, and doing that mid-iteration would throw -- so the positions are collected first
        // and acted on after. Left null until something actually needs removing, because the
        // overwhelmingly common sweep finds nothing.
        List<BlockPos> doomed = null;

        for (ChunkHolder holder : visible.values()) {
            LevelChunk chunk = holder.getTickingChunk();
            if (chunk == null) continue;   // still loading; it will come round again

            for (BlockEntity be : chunk.getBlockEntities().values()) {
                if (!(be instanceof Container container)) continue;

                switch (judgeBlock(be)) {
                    case WARDED -> { }   // its owner may still be coming back for it
                    case DOOMED -> {
                        if (doomed == null) doomed = new ArrayList<>();
                        doomed.add(be.getBlockPos().immutable());
                    }
                    case ORDINARY -> {
                        if (scan(container, null, "at " + be.getBlockPos().toShortString())) {
                            be.setChanged();
                        }
                    }
                }
            }
        }

        if (doomed == null) return;
        for (BlockPos pos : doomed) {
            // Emptied first so vanilla has nothing left to scatter on the way out. A shulker
            // box normally spills into the item it drops, and the whole point is that neither
            // the box nor what was in it survives.
            if (level.getBlockEntity(pos) instanceof Container c) c.clearContent();
            level.removeBlock(pos, false);
        }
    }

    /** What the sweep should do with a placed block that carries a stamp. */
    private enum Verdict { ORDINARY, WARDED, DOOMED }

    /**
     * Judges a placed block as a whole, rather than only its contents.
     *
     * <p>A shulker box is the one container that <em>is</em> an item -- it keeps what it holds
     * when broken, so a box left standing is a stash exactly like a box sitting in a chest, and
     * treating the two differently would mean the same object survived or died depending on
     * which way up it was. Emptying it and leaving the box would also be a strange half-measure:
     * everything the death was meant to take is gone, and what is left is the packaging.
     *
     * <p>Nothing else can reach here. A stamp is only ever put on an item that does not stack,
     * and the shulker box is the only such block in the game -- chests, barrels and the rest
     * stack, so they are never stamped and always fall through to the ordinary content scan.
     */
    private static Verdict judgeBlock(BlockEntity be) {
        Mark mark = markOf(be);
        if (mark == null) return Verdict.ORDINARY;

        String living = Incarnations.peek(mark.owner());
        if (living != null && !living.equals(mark.life())) {
            String where = "at " + be.getBlockPos().toShortString();
            if (Config.get().wipeGearLogOnly) {
                reportBlock(be, where, mark);
                return Verdict.WARDED;   // report only: change nothing, contents included
            }
            LetheMC.LOGGER.info("Destroying placed {} {} -- last held by a life that has ended",
                    be.getBlockState().getBlock().getName().getString(), where);
            return Verdict.DOOMED;
        }
        return wards(mark) ? Verdict.WARDED : Verdict.ORDINARY;
    }

    private static void reportBlock(BlockEntity be, String where, Mark mark) {
        wouldDestroy++;
        String key = where + "|block|" + mark.owner() + '|' + mark.life();
        if (!REPORTED.add(key) || REPORTED.size() > REPORT_CAP) return;
        LetheMC.LOGGER.info("[gear, log only] WOULD destroy placed {} {} -- last held by a life that has ended",
                be.getBlockState().getBlock().getName().getString(), where);
    }

    private static void sweepEntities(ServerLevel level) {
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof ServerPlayer) continue;   // handled by the player pass

            if (entity instanceof ItemEntity item) {
                checkLooseItem(item);
                continue;
            }
            if (entity instanceof ItemFrame frame) {
                if (judge(frame.getItem(), null, "at " + frame.blockPosition().toShortString())) {
                    frame.setItem(ItemStack.EMPTY);
                }
                continue;
            }
            // Chest boats and chest/hopper minecarts arrive here.
            if (entity instanceof Container container) {
                scan(container, null, "at " + entity.blockPosition().toShortString());
                continue;
            }
            // Armour stands are storage with a nicer shape, and a mob can be wearing gear a
            // player gave it.
            if (entity instanceof LivingEntity living) {
                for (EquipmentSlot slot : EquipmentSlot.values()) {
                    ItemStack worn = living.getItemBySlot(slot);
                    if (judge(worn, null, "at " + entity.blockPosition().toShortString())) {
                        living.setItemSlot(slot, ItemStack.EMPTY);
                    }
                }
            }
        }
    }

    /**
     * A loose item on the ground.
     *
     * <p>Warded ones are kept from despawning. Otherwise breaking a dead player's chest during
     * the grace period would scatter their gear where nobody may pick it up, and five minutes
     * later it would be gone -- destroying the very things a resurrection is meant to hand back.
     */
    private static void checkLooseItem(ItemEntity item) {
        ItemStack stack = item.getItem();
        if (isWardEnforced(stack)) {
            item.setUnlimitedLifetime();
            return;
        }
        if (judge(stack, null, "at " + item.blockPosition().toShortString())) {
            item.discard();
        }
    }

    /** The periodic pass. Players every time; the world on its own slower interval. */
    public static void sweep(MinecraftServer server, boolean includeWorld) {
        if (!Config.get().wipeGear) return;
        wouldDestroy = 0;
        sweepPlayers(server);
        if (includeWorld) sweepWorld(server);
        if (includeWorld && Config.get().wipeGearLogOnly && wouldDestroy > 0) {
            LetheMC.LOGGER.info("[gear, log only] {} stashed item(s) in loaded chunks belong to a life that has ended",
                    wouldDestroy);
        }
    }
}
