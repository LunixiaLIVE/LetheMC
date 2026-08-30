package net.lunix.lethemc.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.lunix.lethemc.LetheMC;

/**
 * Fabric wiring. Every event here does nothing but call the matching handler in {@code common}.
 *
 * <p>Death is absent on purpose: it is a mixin on {@code ServerPlayer.die} rather than an event,
 * so both loaders reach it the same way and neither needs to wire it.
 */
public class LetheMCFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        LetheMC.setup(FabricLoader.getInstance().getConfigDir());

        ServerLifecycleEvents.SERVER_STARTED.register(LetheMC::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> LetheMC.onServerStopping());
        ServerTickEvents.END_SERVER_TICK.register(LetheMC::onServerTick);
        ServerPlayConnectionEvents.JOIN.register(
                (handler, sender, server) -> LetheMC.onPlayerJoin(handler.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT.register(
                (handler, server) -> LetheMC.onPlayerDisconnect(handler.getPlayer()));
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registry, environment) -> LetheMC.registerCommands(dispatcher));
    }
}
