package net.lunix.lethemc.mixin;

import net.lunix.lethemc.Incarnated;
import net.lunix.lethemc.Pets;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gives wolves, cats and parrots a memory of <em>which life</em> tamed them.
 *
 * <p>Stored on the animal rather than in a registry the mod keeps, because the animal is the
 * only thing that reliably knows. It persists in chunk data, so it survives restarts, survives
 * the mod being absent for a while, and is still correct for a wolf that has been sitting in an
 * unloaded chunk for a month. A separate registry would have to track every tame, notice every
 * death, and cope with going stale; the animal carrying its own provenance has none of those
 * failure modes.
 *
 * <p>An animal with no stamp is one tamed before this feature existed, and is left alone.
 */
@Mixin(TamableAnimal.class)
public abstract class TamableAnimalMixin implements Incarnated {

    @Unique
    private String lethemc$incarnation;

    @Override
    public String lethemc$getIncarnation() {
        return lethemc$incarnation;
    }

    @Override
    public void lethemc$setIncarnation(String id) {
        this.lethemc$incarnation = id;
    }

    @Inject(method = "tame", at = @At("TAIL"))
    private void lethemc$stampOnTame(Player player, CallbackInfo ci) {
        Pets.stamp((TamableAnimal) (Object) this, player);
    }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void lethemc$save(ValueOutput out, CallbackInfo ci) {
        if (lethemc$incarnation != null) {
            out.putString(Incarnated.KEY, lethemc$incarnation);
        }
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void lethemc$load(ValueInput in, CallbackInfo ci) {
        this.lethemc$incarnation = in.getString(Incarnated.KEY).orElse(null);
    }
}
