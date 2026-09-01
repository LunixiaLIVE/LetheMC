package net.lunix.lethemc.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reaches the list of chunks the server currently has loaded.
 *
 * <p>The gear sweep needs to walk containers, and a container is a block entity -- so it needs
 * to enumerate chunks. Vanilla exposes a count and a lookup by position but no iteration, and
 * the only public way to reach a chunk is {@code getChunk}, which <b>generates or loads one if
 * it is missing</b>. Driving a sweep off that would have the mod paging in the world around it.
 *
 * <p>{@code visibleChunkMap} is the already-loaded set, so reading it can never cause a load.
 * The sweep still skips any holder whose chunk is not ticking yet.
 */
@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {

    @Accessor("visibleChunkMap")
    Long2ObjectLinkedOpenHashMap<ChunkHolder> lethemc$visibleChunks();
}
