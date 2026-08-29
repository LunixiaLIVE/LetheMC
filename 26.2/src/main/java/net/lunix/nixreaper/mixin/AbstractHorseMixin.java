package net.lunix.nixreaper.mixin;

import net.lunix.nixreaper.Incarnated;
import net.lunix.nixreaper.Pets;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The same life-stamp as {@link TamableAnimalMixin}, for horses, donkeys, mules, llamas and
 * camels.
 *
 * <p>They need their own mixin because they do not extend {@code TamableAnimal} -- ownership on
 * {@code AbstractHorse} is a separate implementation with its own tame flag and owner
 * reference. Missing this would leave the single largest loophole in the feature: a chest
 * animal is a stash, and a donkey parked at spawn would survive a death that took everything
 * else.
 *
 * <p>{@code tameWithName} is the taming action and is not called when a horse loads from disk
 * -- {@code readAdditionalSaveData} assigns the owner field directly. That matters: hooking a
 * method that ran on load would re-stamp the animal with the current life every time its chunk
 * loaded, and it would never be reclaimed.
 */
@Mixin(AbstractHorse.class)
public abstract class AbstractHorseMixin implements Incarnated {

    @Unique
    private String nixreaper$incarnation;

    @Override
    public String nixreaper$getIncarnation() {
        return nixreaper$incarnation;
    }

    @Override
    public void nixreaper$setIncarnation(String id) {
        this.nixreaper$incarnation = id;
    }

    @Inject(method = "tameWithName", at = @At("TAIL"))
    private void nixreaper$stampOnTame(Player player, CallbackInfoReturnable<Boolean> cir) {
        Pets.stamp((AbstractHorse) (Object) this, player);
    }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void nixreaper$save(ValueOutput out, CallbackInfo ci) {
        if (nixreaper$incarnation != null) {
            out.putString(Incarnated.KEY, nixreaper$incarnation);
        }
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void nixreaper$load(ValueInput in, CallbackInfo ci) {
        this.nixreaper$incarnation = in.getString(Incarnated.KEY).orElse(null);
    }
}
