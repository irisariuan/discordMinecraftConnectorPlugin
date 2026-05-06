package io.github.ariuan.connectorPlugin.forge;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.permission.PermissionAPI;

/**
 * Registers the {@code /cancelstop} command via Brigadier (NeoForge).
 * Requires the sender to be a player with at least OP level 2.
 */
public class ForgeCancelStopCommand {

	public static void register(
		CommandDispatcher<CommandSourceStack> dispatcher,
		ConnectorMod mod
	) {
		dispatcher.register(
			Commands.literal("cancelstop")
				.requires(source -> {
					// Use NeoForge PermissionAPI — falls back to canUseGameMasterBlocks() by default
					if (source.getEntity() instanceof ServerPlayer player) {
						return PermissionAPI.getPermission(
							player,
							ConnectorMod.PERM_CANCEL_STOP
						);
					}
					return true; // console / non-player sources always have access
				})
				.executes(ctx -> {
					CommandSourceStack source = ctx.getSource();
					ForgeShutdownManager shutdownManager =
						mod.getShutdownManager();

					if (!shutdownManager.hasScheduledShutdown()) {
						source.sendFailure(
							Component.literal(
								"No shutdown is currently scheduled"
							).withStyle(ChatFormatting.YELLOW)
						);
						return 0;
					}

					ServerPlayer player;
					try {
						player = source.getPlayerOrException();
					} catch (Exception e) {
						source.sendFailure(
							Component.literal(
								"This command must be run by a player."
							)
						);
						return 0;
					}

					// Run the API call asynchronously to avoid blocking the main thread
					mod.runAsync(() -> {
						boolean success = shutdownManager.cancelShutdownViaApi(
							player
						);
						mod.runOnMainThread(() -> {
							if (success) {
								source.sendSuccess(
									() ->
										Component.literal(
											"Shutdown cancelled successfully"
										).withStyle(ChatFormatting.GREEN),
									true
								);
							} else {
								source.sendFailure(
									Component.literal(
										"Failed to cancel shutdown - check server logs for details"
									).withStyle(ChatFormatting.RED)
								);
							}
						});
					});
					return 1;
				})
		);
	}
}
