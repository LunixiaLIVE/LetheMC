package net.lunix.lethemc.mixin;

import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.storage.PrimaryLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read and replace a world's {@link LevelSettings}.
 *
 * <p>Hardcore is not a field of its own: {@code PrimaryLevelData.isHardcore()} resolves to
 * {@code settings.difficultySettings().hardcore()}, and both of those are records. So the only
 * way to change it is to rebuild the pair and put the new {@code LevelSettings} back — which the
 * field allows, being the one piece of that chain that is not final.
 *
 * <p>Used by {@code /lethemc admin hardcore}, and by nothing else. Vanilla deliberately refuses
 * to convert an existing world from {@code server.properties}, and that refusal is worth keeping:
 * the conversion happens only when an admin asks for it, on the world they are standing in.
 */
@Mixin(PrimaryLevelData.class)
public interface PrimaryLevelDataAccessor {

    @Accessor("settings")
    LevelSettings lethemc$settings();

    @Accessor("settings")
    void lethemc$setSettings(LevelSettings settings);
}
