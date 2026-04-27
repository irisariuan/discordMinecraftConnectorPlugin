package io.github.ariuan.connectorPlugin.paper;

import io.github.ariuan.connectorPlugin.common.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Handler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public class ConnectorPlugin extends JavaPlugin implements Listener, IPlatformAdapter {

    private HttpServer httpServer;
    private static ConnectorPlugin instance;
    private LogCaptureHandler logCaptureHandler;
    private PlayerVerificationManager verificationManager;
    private PlayerRestrictionListener restrictionListener;
    private ShutdownManager shutdownManager;

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();

        File customConfigFile = new File(getDataFolder(), "config.yml");
        FileConfiguration customConfig = YamlConfiguration.loadConfiguration(customConfigFile);
        String apiUrl = customConfig.getString("api-url");
        long periodPerRequest = customConfig.getLong("period-per-request", 36000L);
        if (apiUrl == null) {
            throw new IllegalStateException("Please set api-url in config.yml");
        }

        verificationManager = new PlayerVerificationManager(this, apiUrl, periodPerRequest);
        restrictionListener = new PlayerRestrictionListener(verificationManager);
        shutdownManager = new ShutdownManager(this, apiUrl);

        File logFile = new File(getDataFolder(), "log.txt");
        try {
            if (!logFile.exists() && logFile.createNewFile()) {
                getLogger().info("Created new log file");
            }
        } catch (IOException e) {
            getLogger().warning("Error creating log file: " + e.getMessage());
        }

        logCaptureHandler = new LogCaptureHandler(logFile, getLogger());
        Handler[] handlers = getLogger().getHandlers();
        if (handlers.length > 0 && handlers[0].getFormatter() != null) {
            logCaptureHandler.setFormatter(handlers[0].getFormatter());
        } else {
            logCaptureHandler.setFormatter(new SimpleFormatter());
        }
        getLogger().addHandler(logCaptureHandler);

        try {
            httpServer = new HttpServer(6001, this);
            getLogger().info("HTTP server started on port: 6001");
        } catch (IOException e) {
            getLogger().warning("Error creating HTTP server: " + e.getMessage());
        }

        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(restrictionListener, this);
        this.getCommand("cancelstop").setExecutor(new CancelStopCommand(this));

        getLogger().info("Player verification system enabled with API URL: " + apiUrl);
    }

    public static ConnectorPlugin getInstance() {
        return instance;
    }

    public ShutdownManager getShutdownManager() {
        return shutdownManager;
    }

    public boolean cancelShutdown() {
        return shutdownManager.cancelShutdown();
    }

    public void verifyPlayer(Player player) {
        verificationManager.verifyPlayer(player);
    }

    public boolean shutdown(long tickDelay) {
        return shutdownManager.shutdown(tickDelay, false);
    }

    public boolean haveScheduledShutdown() {
        return shutdownManager.hasScheduledShutdown();
    }

    // -------------------------------------------------------------------------
    // IPlatformAdapter implementation
    // -------------------------------------------------------------------------

    @Override
    public Logger getLogger() {
        return super.getLogger();
    }

    @Override
    public List<IPlayerInfo> getOnlinePlayers() {
        List<IPlayerInfo> result = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            result.add(new PaperPlayerInfo(p));
        }
        return result;
    }

    @Override
    public IPlayerInfo getPlayerByName(String name) {
        Player p = Bukkit.getServer().getPlayerExact(name);
        return p != null ? new PaperPlayerInfo(p) : null;
    }

    @Override
    public IPlayerInfo getPlayerByUUID(UUID uuid) {
        Player p = Bukkit.getServer().getPlayer(uuid);
        return p != null ? new PaperPlayerInfo(p) : null;
    }

    @Override
    public void sendMessageToPlayer(UUID playerUUID, String message) {
        Player p = Bukkit.getServer().getPlayer(playerUUID);
        if (p != null) {
            p.sendMessage(Component.text(message));
        }
    }

    @Override
    public void markPlayerVerified(UUID playerUUID) {
        Player p = Bukkit.getServer().getPlayer(playerUUID);
        if (p != null) {
            verificationManager.verifyPlayer(p);
        }
    }

    @Override
    public CommandResult runCommandAndCapture(String command) {
        CompletableFuture<CommandResult> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(this, () -> {
            CapturingConsoleSender sender = new CapturingConsoleSender();
            LogCapture logCapture = new LogCapture();
            getLogger().addHandler(logCapture);
            boolean success = Bukkit.dispatchCommand(sender, command);

            // Some commands log their output slightly after the call, so wait 2 ticks.
            Bukkit.getScheduler().runTaskLater(this, () -> {
                String output = sender.getOutput();
                String loggerOutput = logCapture.getCapturedOutput();
                getLogger().removeHandler(logCapture);
                future.complete(new CommandResult(success, output.trim(), loggerOutput.trim()));
            }, 2L);
        });
        try {
            return future.get();
        } catch (Exception e) {
            getLogger().warning("Error capturing command output: " + e.getMessage());
            return new CommandResult(false, "", "");
        }
    }

    @Override
    public boolean scheduleShutdown(long tickDelay, boolean isGracePeriod) {
        return shutdownManager.shutdown(tickDelay, isGracePeriod);
    }

    @Override
    public boolean hasScheduledShutdown() {
        return shutdownManager.hasScheduledShutdown();
    }

    @Override
    public List<String> getLoadedComponents() {
        List<String> names = new ArrayList<>();
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            names.add(plugin.getName());
        }
        return names;
    }

    @Override
    public void runOnMainThread(Runnable task) {
        Bukkit.getScheduler().runTask(this, task);
    }

    @Override
    public void runAsync(Runnable task) {
        Bukkit.getScheduler().runTaskAsynchronously(this, task);
    }

    @Override
    public int getServerPort() {
        return Bukkit.getServer().getPort();
    }

    @Override
    public LogCaptureHandler getLogCaptureHandler() {
        return logCaptureHandler;
    }

    // -------------------------------------------------------------------------
    // Events
    // -------------------------------------------------------------------------

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        event.getPlayer().sendMessage(Component.text("Hello, " + event.getPlayer().getName() + "!"));
        shutdownManager.handlePlayerRejoin();
        verificationManager.verifyPlayer(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        event.getPlayer().sendMessage(Component.text("Goodbye, " + event.getPlayer().getName() + "!"));
        verificationManager.stopMonitoring(event.getPlayer());

        Bukkit.getScheduler().runTask(getInstance(), () -> {
            if (Bukkit.getOnlinePlayers().isEmpty()) {
                shutdownManager.shutdown(ShutdownManager.GRACE_PERIOD_TICKS, true);
            }
        });
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        var position = player.getLocation();
        Bukkit.broadcast(Component.text("Grab " + player.getName() + " items at "
                + position.getBlockX() + ", " + position.getBlockY() + ", " + position.getBlockZ() + "!"));
    }

    @Override
    public void onDisable() {
        if (verificationManager != null) {
            verificationManager.cleanup();
        }
        if (httpServer != null) {
            httpServer.stop();
        }
        if (logCaptureHandler != null) {
            getLogger().removeHandler(logCaptureHandler);
        }
    }

    // -------------------------------------------------------------------------
    // Private helper
    // -------------------------------------------------------------------------

    private static class PaperPlayerInfo implements IPlayerInfo {
        private final Player player;
        PaperPlayerInfo(Player player) { this.player = player; }
        @Override public UUID getUUID() { return player.getUniqueId(); }
        @Override public String getName() { return player.getName(); }
    }
}
