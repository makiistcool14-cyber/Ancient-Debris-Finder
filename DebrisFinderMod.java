package dev.debrisfinder;

import com.mojang.brigadier.Command;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.network.chat.Component;

public class DebrisFinderMod implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(DebrisFinder::tick);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommandManager.literal("debris")
                .then(ClientCommandManager.literal("gui").executes(ctx -> {
                    DebrisFinder.openGuiRequested = true; // opened on the next tick (chat screen is still closing)
                    return Command.SINGLE_SUCCESS;
                }))
                .then(ClientCommandManager.literal("start").executes(ctx -> {
                    DebrisFinder.start();
                    ctx.getSource().sendFeedback(Component.literal("[Debris] " + DebrisFinder.getStatus()));
                    return Command.SINGLE_SUCCESS;
                }))
                .then(ClientCommandManager.literal("stop").executes(ctx -> {
                    DebrisFinder.stop("Stopped by user");
                    ctx.getSource().sendFeedback(Component.literal("[Debris] " + DebrisFinder.getStatus()));
                    return Command.SINGLE_SUCCESS;
                }))
                .then(ClientCommandManager.literal("list").executes(ctx -> {
                    ctx.getSource().sendFeedback(Component.literal("[Debris] Seed: " + DebrisFinder.getSeedText()));
                    var list = DebrisFinder.getDisplay();
                    if (list.isEmpty()) {
                        ctx.getSource().sendFeedback(Component.literal("[Debris] No targets known yet."));
                    }
                    for (DebrisFinder.Target t : list) {
                        ctx.getSource().sendFeedback(Component.literal(
                            (t.confirmed() ? "[CONFIRMED] " : "[predicted] ") + DebrisFinder.fmt(t.pos())
                                + " " + t.note()));
                    }
                    return Command.SINGLE_SUCCESS;
                }))
            ));
    }
}
