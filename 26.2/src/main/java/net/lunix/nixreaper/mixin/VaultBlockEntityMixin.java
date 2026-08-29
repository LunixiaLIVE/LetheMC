package net.lunix.nixreaper.mixin;

import net.lunix.nixreaper.LifeStamped;
import net.lunix.nixreaper.PendingStamps;
import net.lunix.nixreaper.Stamps;
import net.minecraft.world.level.block.entity.vault.VaultBlockEntity;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.UUID;

/**
 * Persists the vault's life stamps.
 *
 * <p>The stamps live on {@link net.minecraft.world.level.block.entity.vault.VaultServerData},
 * which is written through a fixed {@code Codec} a mixin cannot extend, so they ride along in
 * the block entity's own save data instead.
 *
 * <h2>Why loading is a two-step</h2>
 * {@code serverData} is <b>null while {@code loadAdditional} runs</b> -- measured, not assumed:
 * the field is assigned in the constructor after {@code super()}, and the load happens before
 * that assignment is visible. Decoding straight into it silently dropped every stamp, and the
 * failure was invisible in testing because the very next save then wrote the now-empty map back,
 * erasing the stamps from disk as well.
 *
 * <p>That failure was worse than losing the feature. With no stamp, nothing is ever judged
 * stale, so a vault that survived one restart would bar its looters <em>permanently</em> -- the
 * exact penalty this was written to lift, made unliftable.
 *
 * <p>So load only captures the raw string, and the decode happens at {@code setLevel}, which
 * runs when the block entity is attached to the world and is therefore fully constructed.
 */
@Mixin(VaultBlockEntity.class)
public abstract class VaultBlockEntityMixin implements PendingStamps {

    @Unique
    private String nixreaper$pending;

    @Inject(method = "loadAdditional", at = @At("TAIL"))
    private void nixreaper$load(ValueInput in, CallbackInfo ci) {
        nixreaper$pending = in.getString(LifeStamped.KEY).orElse(null);
        nixreaper$flush(); // harmless if serverData is not ready yet; setLevel will catch it
    }

    @Inject(method = "saveAdditional", at = @At("TAIL"))
    private void nixreaper$save(ValueOutput out, CallbackInfo ci) {
        nixreaper$flush(); // never write an empty map over stamps that simply had not landed yet
        Map<UUID, String> stamps = nixreaper$stamps();
        if (stamps != null && !stamps.isEmpty()) {
            out.putString(LifeStamped.KEY, Stamps.encode(stamps));
        }
    }

    /** Moves the raw string into the server data once that object actually exists. */
    @Override
    public void nixreaper$flushStamps() {
        nixreaper$flush();
    }

    @Unique
    private void nixreaper$flush() {
        if (nixreaper$pending == null) return;
        Map<UUID, String> stamps = nixreaper$stamps();
        if (stamps == null) return; // too early -- a later call will do it
        Stamps.decode(nixreaper$pending, stamps);
        nixreaper$pending = null;
    }

    @Unique
    private Map<UUID, String> nixreaper$stamps() {
        Object data = ((VaultBlockEntity) (Object) this).getServerData();
        return data instanceof LifeStamped stamped ? stamped.nixreaper$stamps() : null;
    }
}
