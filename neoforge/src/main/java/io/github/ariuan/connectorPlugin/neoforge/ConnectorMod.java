package io.github.ariuan.connectorPlugin.neoforge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.ariuan.connectorPlugin.common.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;

@Mod(ConnectorMod.MOD_ID)
public class ConnectorMod implements IPlatformAdapter {

	public static final String MOD_ID = "discordconnector";
	private static ConnectorMod INSTANCE;

	/** Permission node for the /cancelstop command (default: operators only). */
	public static final PermissionNode<Boolean> PERM_CANCEL_STOP =
		new PermissionNode<>(
			MOD_ID,
			"command.cancelstop",
			PermissionTypes.BOOLEAN,
			(player, uuid, ctx) ->
				player != null && player.canUseGameMasterBlocks()
		);

	private final Logger logger = Logger.getLogger(MOD_ID);
	private final ScheduledExecutorService scheduler =
		Executors.newScheduledThreadPool(4);

	private MinecraftServer server;
	private HttpServer httpServer;
	private LogCaptureHandler logCaptureHandler;
	private NeoForgePlayerVerificationManager verificationManager;
	private NeoForgeShutdownManager shutdownManager;
	private NeoForgePlayerRestrictionHandler restrictionHandler;

	public ConnectorMod(IEventBus modEventBus) {
		INSTANCE = this;
		NeoForge.EVENT_BUS.register(this);
	}

	public static ConnectorMod getInstance() {
		return INSTANCE;
	}

	@SubscribeEvent
	public void onServerStarted(ServerStartedEvent event) {
		this.server = event.getServer();

		String apiUrl = readConfig("api-url");
		if (apiUrl == null || apiUrl.isEmpty()) {
			logger.severe(
				"Please set api-url in config/discordconnector/config.json"
			);
			return;
		}
		long periodPerRequest = 36000L;
		String periodStr = readConfig("period-per-request");
		if (periodStr != null && !periodStr.isEmpty()) {
			try {
				periodPerRequest = Long.parseLong(periodStr);
			} catch (NumberFormatException ignored) {}
		}

		verificationManager = new NeoForgePlayerVerificationManager(
			server,
			apiUrl,
			periodPerRequest,
			logger,
			scheduler
		);
		restrictionHandler = new NeoForgePlayerRestrictionHandler(
			verificationManager
		);
		shutdownManager = new NeoForgeShutdownManager(
			server,
			apiUrl,
			logger,
			scheduler
		);

		NeoForge.EVENT_BUS.register(restrictionHandler);

		File logFile = new File(
			server.getServerDirectory().toFile(),
			"discordconnector/log.txt"
		);
		logFile.getParentFile().mkdirs();
		try {
			if (!logFile.exists()) logFile.createNewFile();
		} catch (IOException e) {
			logger.warning("Error creating log file: " + e.getMessage());
		}

		logCaptureHandler = new LogCaptureHandler(logFile, logger);
		logCaptureHandler.setFormatter(new SimpleFormatter());
		logger.addHandler(logCaptureHandler);

		try {
			httpServer = new HttpServer(6001, this);
		} catch (IOException e) {
			logger.warning("Error creating HTTP server: " + e.getMessage());
		}

		logger.info(
			"Player verification system enabled with API URL: " + apiUrl
		);
	}

	@SubscribeEvent
	public void onServerStopping(ServerStoppingEvent event) {
		if (verificationManager != null) verificationManager.cleanup();
		if (httpServer != null) httpServer.stop();
		if (logCaptureHandler != null) logger.removeHandler(logCaptureHandler);
		scheduler.shutdownNow();
	}

	@SubscribeEvent
	public void onRegisterCommands(RegisterCommandsEvent event) {
		NeoForgeCancelStopCommand.register(event.getDispatcher(), this);
	}

	@SubscribeEvent
	public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
		if (!(event.getEntity() instanceof ServerPlayer player)) return;
		player.sendSystemMessage(
			Component.literal("Hello, " + player.getName().getString() + "!")
		);
		if (shutdownManager != null) shutdownManager.handlePlayerRejoin();
		if (verificationManager != null) verificationManager.verifyPlayer(
			player
		);
	}

	@SubscribeEvent
	public void onPlayerQuit(PlayerEvent.PlayerLoggedOutEvent event) {
		if (!(event.getEntity() instanceof ServerPlayer player)) return;
		player.sendSystemMessage(
			Component.literal("Goodbye, " + player.getName().getString() + "!")
		);
		if (verificationManager != null) verificationManager.stopMonitoring(
			player
		);

		server.submit(() -> {
			if (
				server.getPlayerList().getPlayers().isEmpty() &&
				shutdownManager != null
			) {
				shutdownManager.shutdown(
					NeoForgeShutdownManager.GRACE_PERIOD_TICKS,
					true
				);
			}
		});
	}

	@SubscribeEvent
	public void onPlayerDeath(LivingDeathEvent event) {
		if (!(event.getEntity() instanceof ServerPlayer player)) return;
		var pos = player.blockPosition();
		server
			.getPlayerList()
			.broadcastSystemMessage(
				Component.literal(
					"Grab " +
						player.getName().getString() +
						" items at " +
						pos.getX() +
						", " +
						pos.getY() +
						", " +
						pos.getZ() +
						"!"
				),
				false
			);
	}

	// -------------------------------------------------------------------------
	// IPlatformAdapter
	// -------------------------------------------------------------------------

	@Override
	public Logger getLogger() {
		return logger;
	}

	@Override
	public List<IPlayerInfo> getOnlinePlayers() {
		List<IPlayerInfo> result = new ArrayList<>();
		if (server != null) {
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				result.add(new NeoForgePlayerInfo(p));
			}
		}
		return result;
	}

	@Override
	public IPlayerInfo getPlayerByName(String name) {
		if (server == null) return null;
		ServerPlayer p = server.getPlayerList().getPlayerByName(name);
		return p != null ? new NeoForgePlayerInfo(p) : null;
	}

	@Override
	public IPlayerInfo getPlayerByUUID(UUID uuid) {
		if (server == null) return null;
		ServerPlayer p = server.getPlayerList().getPlayer(uuid);
		return p != null ? new NeoForgePlayerInfo(p) : null;
	}

	@Override
	public void sendMessageToPlayer(UUID playerUUID, String message) {
		if (server == null) return;
		ServerPlayer p = server.getPlayerList().getPlayer(playerUUID);
		if (p != null) p.sendSystemMessage(Component.literal(message));
	}

	@Override
	public void markPlayerVerified(UUID playerUUID) {
		if (server == null || verificationManager == null) return;
		ServerPlayer p = server.getPlayerList().getPlayer(playerUUID);
		if (p != null) verificationManager.verifyPlayer(p);
	}

	@Override
	public CommandResult runCommandAndCapture(String command) {
		if (server == null) return new CommandResult(false, "", "");
		CompletableFuture<CommandResult> future = new CompletableFuture<>();
		server.submit(() -> {
			CapturingCommandOutput capturing = new CapturingCommandOutput();
			LogCapture logCapture = new LogCapture();
			logger.addHandler(logCapture);

			CommandSourceStack source = server
				.createCommandSourceStack()
				.withSource(capturing);
			int result;
			try {
				String cmd = command.startsWith("/")
					? command.substring(1)
					: command;
				result = server
					.getCommands()
					.getDispatcher()
					.execute(cmd, source);
			} catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
				result = 0;
			}

			// Brief pause to allow any async log output to flush
			try {
				Thread.sleep(100);
			} catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			}

			String output = capturing.getOutput();
			String loggerOutput = logCapture.getCapturedOutput();
			logger.removeHandler(logCapture);
			future.complete(
				new CommandResult(
					result > 0,
					output.trim(),
					loggerOutput.trim()
				)
			);
		});
		try {
			return future.get();
		} catch (Exception e) {
			logger.warning("Error capturing command output: " + e.getMessage());
			return new CommandResult(false, "", "");
		}
	}

	@Override
	public boolean scheduleShutdown(long tickDelay, boolean isGracePeriod) {
		return (
			shutdownManager != null &&
			shutdownManager.shutdown(tickDelay, isGracePeriod)
		);
	}

	@Override
	public boolean cancelShutdown() {
		return shutdownManager != null && shutdownManager.cancelShutdown();
	}

	@Override
	public boolean hasScheduledShutdown() {
		return (
			shutdownManager != null && shutdownManager.hasScheduledShutdown()
		);
	}

	@Override
	public List<String> getLoadedComponents() {
		List<String> names = new ArrayList<>();
		net.neoforged.fml.ModList.get()
			.getMods()
			.forEach(m -> names.add(m.getModId()));
		return names;
	}

	@Override
	public void runOnMainThread(Runnable task) {
		if (server != null) server.submit(task);
	}

	@Override
	public void runAsync(Runnable task) {
		scheduler.submit(task);
	}

	@Override
	public int getServerPort() {
		return server != null ? server.getPort() : 0;
	}

	@Override
	public LogCaptureHandler getLogCaptureHandler() {
		return logCaptureHandler;
	}

	public NeoForgeShutdownManager getShutdownManager() {
		return shutdownManager;
	}

	// -------------------------------------------------------------------------
	// Config helper
	// -------------------------------------------------------------------------

	private String configCache = null;

	private String readConfig(String key) {
		if (configCache == null) {
			File configFile = new File(
				server.getServerDirectory().toFile(),
				"config/discordconnector/config.json"
			);
			if (!configFile.exists()) {
				configFile.getParentFile().mkdirs();
				try (OutputStream os = new FileOutputStream(configFile)) {
					String defaults =
						"{\n  \"api-url\": \"\",\n  \"period-per-request\": 36000\n}\n";
					os.write(defaults.getBytes(StandardCharsets.UTF_8));
				} catch (IOException e) {
					logger.warning(
						"Could not write default config: " + e.getMessage()
					);
				}
				return null;
			}
			try {
				configCache = new String(
					Files.readAllBytes(configFile.toPath()),
					StandardCharsets.UTF_8
				);
			} catch (IOException e) {
				logger.warning("Could not read config: " + e.getMessage());
				return null;
			}
		}
		try {
			JsonObject json = JsonParser.parseString(
				configCache
			).getAsJsonObject();
			if (!json.has(key) || json.get(key).isJsonNull()) return null;
			String value = json.get(key).getAsString();
			return value.isEmpty() ? null : value;
		} catch (Exception e) {
			logger.warning(
				"Could not parse config key '" + key + "': " + e.getMessage()
			);
			return null;
		}
	}

	// -------------------------------------------------------------------------
	// Private helper
	// -------------------------------------------------------------------------

	private static class NeoForgePlayerInfo implements IPlayerInfo {

		private final ServerPlayer player;

		NeoForgePlayerInfo(ServerPlayer player) {
			this.player = player;
		}

		@Override
		public UUID getUUID() {
			return player.getUUID();
		}

		@Override
		public String getName() {
			return player.getName().getString();
		}
	}
}
