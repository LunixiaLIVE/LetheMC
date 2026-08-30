package net.lunix.lethemc.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.fox.Fox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Optional;

/**
 * Exposes the two private trust slots on {@link Fox}.
 *
 * <p>Vanilla offers no way to revoke one player's trust: {@code clearTrusted} is private and
 * clears both slots at once, which would mean one player's death costing a second player their
 * fox as well. Reaching the accessors directly is what makes per-player revocation possible.
 */
@Mixin(Fox.class)
public interface FoxAccessor {

    @Accessor("DATA_TRUSTED_ID_0")
    static EntityDataAccessor<Optional<EntityReference<LivingEntity>>> lethemc$trusted0() {
        throw new AssertionError("mixin accessor not applied");
    }

    @Accessor("DATA_TRUSTED_ID_1")
    static EntityDataAccessor<Optional<EntityReference<LivingEntity>>> lethemc$trusted1() {
        throw new AssertionError("mixin accessor not applied");
    }
}
