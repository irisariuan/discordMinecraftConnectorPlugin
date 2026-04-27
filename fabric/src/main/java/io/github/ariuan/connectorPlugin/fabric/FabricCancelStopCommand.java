package io.github.ariuan.connectorPlugin.fabric;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Registers the {@code /cancelstop} command via Brigadier (Fabric).
 * Requires the sender to be a player with at least OP level 2.
 */
public class FabricCancelStopCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, ConnectorMod mod) {
        dispatcher.register(
            Commands.literal("cancelstop")
                .requires(source -> source.hasPermission(2))
                .executes(ctx -> {
                    CommandSourceStack source = ctx.getSource();
                    FabricShutdownManager shutdownManager = mod.getShutdownManager();

                    if (!shutdownManager.hasScheduledShutdown()) {
                        source.sendFailure(Component.literal("No shutdown is currently scheduled")
                                .withStyle(ChatFormatting.YELLOW));
                        return 0;
                    }

                    ServerPlayer player;
                    try {
                        player = source.getPlayerOrException();
                    } catch (Exception e) {
                        source.sendFailure(Component.literal("This command must be run by a player."));
                        return 0;
                    }

                    mod.runAsync(() -> {
                        boolean success = shutdownManager.cancelShutdownViaApi(player);
                        mod.runOnMainThread(() -> {
                            if (success) {
                                source.sendSuccess(
                                    () -> Component.literal("Shutdown cancelled successfully")
                                                   .withStyle(ChatFormatting.GREEN), true);
                            } else {
                                source.sendFailure(Component.literal(
                                    "Failed to cancel shutdown - check server logs for details")
                                    .withStyle(ChatFormatting.RED));
                            }
                        });
                    });
                    return 1;
                })
        );
    }
}
