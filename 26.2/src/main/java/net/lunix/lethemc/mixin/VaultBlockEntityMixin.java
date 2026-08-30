package net.lunix.lethemc.mixin;

import net.lunix.lethemc.LifeStamped;
import net.lunix.lethemc.PendingStamps;
import net.lunix.lethemc.Stamps;
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
    private String lethemc$pending;

    @Inject(method = "loadAdditional", at = @At("TAIL"))
    private void lethemc$load(ValueInput in, CallbackInfo ci) {
        lethemc$pending = in.getString(LifeStamped.KEY).orElse(null);
        lethemc$flush(); // harmless if serverData is not ready yet; setLevel will catch it
    }

    @Inject(method = "saveAdditional", at = @At("TAIL"))
    private void lethemc$save(ValueOutput out, CallbackInfo ci) {
        lethemc$flush(); // never write an empty map over stamps that simply had not landed yet
        Map<UUID, String> stamps = lethemc$stamps();
        if (stamps != null && !stamps.isEmpty()) {
            out.putString(LifeStamped.KEY, Stamps.encode(stamps));
        }
    }

    /** Moves the raw string into the server data once that object actually exists. */
    @Override
    public void lethemc$flushStamps() {
        lethemc$flush();
    }

    @Unique
    private void lethemc$flush() {
        if (lethemc$pending == null) return;
        Map<UUID, String> stamps = lethemc$stamps();
        if (stamps == null) return; // too early -- a later call will do it
        Stamps.decode(lethemc$pending, stamps);
        lethemc$pending = null;
    }

    @Unique
    private Map<UUID, String> lethemc$stamps() {
        Object data = ((VaultBlockEntity) (Object) this).getServerData();
        return data instanceof LifeStamped stamped ? stamped.lethemc$stamps() : null;
    }
}
