package io.github.ariuan.connectorPlugin.fabric;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.ariuan.connectorPlugin.common.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

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

public class ConnectorMod implements ModInitializer, IPlatformAdapter {

    public static final String MOD_ID = "discordconnector";
    private static ConnectorMod INSTANCE;

    private final Logger logger = Logger.getLogger(MOD_ID);
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

    private MinecraftServer server;
    private HttpServer httpServer;
    private LogCaptureHandler logCaptureHandler;
    private FabricPlayerVerificationManager verificationManager;
    private FabricShutdownManager shutdownManager;
    private FabricPlayerRestrictionHandler restrictionHandler;

    public static ConnectorMod getInstance() { return INSTANCE; }

    @Override
    public void onInitialize() {
        INSTANCE = this;

        ServerLifecycleEvents.SERVER_STARTED.register(srv -> {
            this.server = srv;
            startPlugin();
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(srv -> {
            if (verificationManager != null) verificationManager.cleanup();
            if (httpServer != null) httpServer.stop();
            if (logCaptureHandler != null) logger.removeHandler(logCaptureHandler);
            scheduler.shutdownNow();
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, srv) -> {
            ServerPlayer player = handler.getPlayer();
            player.sendSystemMessage(Component.literal("Hello, " + player.getName().getString() + "!"));
            if (shutdownManager != null) shutdownManager.handlePlayerRejoin();
            if (restrictionHandler != null) restrictionHandler.recordJoinPosition(player);
            if (verificationManager != null) verificationManager.verifyPlayer(player);
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, srv) -> {
            ServerPlayer player = handler.getPlayer();
            player.sendSystemMessage(Component.literal("Goodbye, " + player.getName().getString() + "!"));
            if (restrictionHandler != null) restrictionHandler.clearFreezeData(player.getUUID());
            if (verificationManager != null) verificationManager.stopMonitoring(player);

            srv.submit(() -> {
                if (srv.getPlayerList().getPlayers().isEmpty() && shutdownManager != null) {
                    shutdownManager.shutdown(FabricShutdownManager.GRACE_PERIOD_TICKS, true);
                }
            });
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            FabricCancelStopCommand.register(dispatcher, this));
    }

    private void startPlugin() {
        String apiUrl = readConfig("api-url");
        if (apiUrl == null || apiUrl.isEmpty()) {
            logger.severe("Please set api-url in config/discordconnector/config.json");
            return;
        }
        long periodPerRequest = 36000L;
        String periodStr = readConfig("period-per-request");
        if (periodStr != null && !periodStr.isEmpty()) {
            try { periodPerRequest = Long.parseLong(periodStr); } catch (NumberFormatException ignored) {}
        }

        verificationManager = new FabricPlayerVerificationManager(server, apiUrl, periodPerRequest, logger, scheduler);
        shutdownManager = new FabricShutdownManager(server, apiUrl, logger, scheduler);
        restrictionHandler = new FabricPlayerRestrictionHandler(verificationManager);
        restrictionHandler.register(server);

        File logFile = FabricLoader.getInstance().getGameDir().resolve("discordconnector/log.txt").toFile();
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

        logger.info("Player verification system enabled with API URL: " + apiUrl);
    }

    // -------------------------------------------------------------------------
    // IPlatformAdapter
    // -------------------------------------------------------------------------

    @Override
    public Logger getLogger() { return logger; }

    @Override
    public List<IPlayerInfo> getOnlinePlayers() {
        List<IPlayerInfo> result = new ArrayList<>();
        if (server != null) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                result.add(new FabricPlayerInfo(p));
            }
        }
        return result;
    }

    @Override
    public IPlayerInfo getPlayerByName(String name) {
        if (server == null) return null;
        ServerPlayer p = server.getPlayerList().getPlayerByName(name);
        return p != null ? new FabricPlayerInfo(p) : null;
    }

    @Override
    public IPlayerInfo getPlayerByUUID(UUID uuid) {
        if (server == null) return null;
        ServerPlayer p = server.getPlayerList().getPlayer(uuid);
        return p != null ? new FabricPlayerInfo(p) : null;
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
        if (p != null) {
            restrictionHandler.clearFreezeData(playerUUID);
            verificationManager.verifyPlayer(p);
        }
    }

    @Override
    public CommandResult runCommandAndCapture(String command) {
        if (server == null) return new CommandResult(false, "", "");
        CompletableFuture<CommandResult> future = new CompletableFuture<>();
        server.submit(() -> {
            CapturingCommandOutput capturing = new CapturingCommandOutput();
            LogCapture logCapture = new LogCapture();
            logger.addHandler(logCapture);

            CommandSourceStack source = server.createCommandSourceStack()
                    .withSource(capturing)
                    .withPermission(4);
            server.getCommands().performPrefixedCommand(source, command);

            try { Thread.sleep(100); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }

            String output = capturing.getOutput();
            String loggerOutput = logCapture.getCapturedOutput();
            logger.removeHandler(logCapture);
            future.complete(new CommandResult(!output.isEmpty() || !loggerOutput.isEmpty(), output.trim(), loggerOutput.trim()));
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
        return shutdownManager != null && shutdownManager.shutdown(tickDelay, isGracePeriod);
    }

    @Override
    public boolean cancelShutdown() {
        return shutdownManager != null && shutdownManager.cancelShutdown();
    }

    @Override
    public boolean hasScheduledShutdown() {
        return shutdownManager != null && shutdownManager.hasScheduledShutdown();
    }

    @Override
    public List<String> getLoadedComponents() {
        List<String> names = new ArrayList<>();
        FabricLoader.getInstance().getAllMods().forEach(m -> names.add(m.getMetadata().getId()));
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
    public LogCaptureHandler getLogCaptureHandler() { return logCaptureHandler; }

    public FabricShutdownManager getShutdownManager() { return shutdownManager; }

    // -------------------------------------------------------------------------
    // Config helper
    // -------------------------------------------------------------------------

    private String configCache = null;

    private String readConfig(String key) {
        if (configCache == null) {
            File configFile = FabricLoader.getInstance().getGameDir()
                    .resolve("config/discordconnector/config.json").toFile();
            if (!configFile.exists()) {
                configFile.getParentFile().mkdirs();
                try (OutputStream os = new FileOutputStream(configFile)) {
                    String defaults = "{\n  \"api-url\": \"\",\n  \"period-per-request\": 36000\n}\n";
                    os.write(defaults.getBytes(StandardCharsets.UTF_8));
                } catch (IOException e) {
                    logger.warning("Could not write default config: " + e.getMessage());
                }
                return null;
            }
            try {
                configCache = new String(Files.readAllBytes(configFile.toPath()), StandardCharsets.UTF_8);
            } catch (IOException e) {
                logger.warning("Could not read config: " + e.getMessage());
                return null;
            }
        }
        try {
            JsonObject json = JsonParser.parseString(configCache).getAsJsonObject();
            if (!json.has(key) || json.get(key).isJsonNull()) return null;
            String value = json.get(key).getAsString();
            return value.isEmpty() ? null : value;
        } catch (Exception e) {
            logger.warning("Could not parse config key '" + key + "': " + e.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Private helper
    // -------------------------------------------------------------------------

    private static class FabricPlayerInfo implements IPlayerInfo {
        private final ServerPlayer player;
        FabricPlayerInfo(ServerPlayer player) { this.player = player; }
        @Override public UUID getUUID() { return player.getUUID(); }
        @Override public String getName() { return player.getName().getString(); }
    }
}
