package net.lunix.lethemc.neoforge;

import net.lunix.lethemc.LetheMC;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * NeoForge wiring, the mirror of {@code LetheMCFabric}: every handler forwards straight into
 * {@code common} and holds no logic of its own.
 *
 * <p>Death is handled by a mixin on {@code ServerPlayer.die} rather than an event, which is why
 * there is no death subscription here. NeoForge's {@code LivingDeathEvent} fires before the death
 * is processed and has no counterpart for the tail, so the mixin covers both ends and behaves the
 * same on either loader.
 */
@Mod(LetheMC.MOD_ID)
public class LetheMCNeoForge {

    public LetheMCNeoForge(IEventBus modBus) {
        LetheMC.setup(FMLPaths.CONFIGDIR.get());
        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        LetheMC.onServerStarted(event.getServer());
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        LetheMC.onServerStopping();
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        LetheMC.onServerTick(event.getServer());
    }

    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            LetheMC.onPlayerJoin(player);
        }
    }

    @SubscribeEvent
    public void onPlayerDisconnect(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            LetheMC.onPlayerDisconnect(player);
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        LetheMC.registerCommands(event.getDispatcher());
    }
}
