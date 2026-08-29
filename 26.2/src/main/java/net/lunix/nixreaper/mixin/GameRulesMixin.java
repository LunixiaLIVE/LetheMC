package net.lunix.nixreaper.mixin;

import net.lunix.nixreaper.NixReaper;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Forces keepInventory to read as true while a player is inside death processing.
 *
 * <p>This is the "keep inventory in memory" override: the actual gamerule is never
 * written, so server.properties and level.dat are untouched and admins see no surprise
 * state change. Its only job is to stop vanilla dropping anything on the ground -- the
 * items are destroyed later by the purge.
 *
 * <p>Keeping the data alive through death is also what makes the pardon mercy window
 * possible: an admin pardoning inside the purge delay restores a player completely,
 * because nothing has actually been deleted yet.
 *
 * <p>The window is narrow by construction -- the flag is set at the head of death
 * processing and cleared at the tail, which is exactly the span containing the
 * inventory-drop and experience checks. GameRules#get is a hot path, so the cheap
 * "is anyone dying at all" test is deliberately checked first.
 */
@Mixin(GameRules.class)
public class GameRulesMixin {

    @Inject(method = "get", at = @At("HEAD"), cancellable = true)
    private void nixreaper$forceKeepInventory(GameRule<?> rule, CallbackInfoReturnable<Object> cir) {
        if (!NixReaper.isAnyoneDying()) return;
        if (rule == GameRules.KEEP_INVENTORY) {
            cir.setReturnValue(Boolean.TRUE);
        }
    }
}
