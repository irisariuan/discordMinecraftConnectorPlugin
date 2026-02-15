package io.github.ariuan.connectorPlugin;

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
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.logging.Handler;
import java.util.logging.SimpleFormatter;

public class ConnectorPlugin extends JavaPlugin implements Listener {
    private HttpServer httpServer;
    private static ConnectorPlugin instance;
    private LogCaptureHandler logCaptureHandler;
    private PlayerVerificationManager verificationManager;
    private PlayerRestrictionListener restrictionListener;
    private ShutdownManager shutdownManager;

    @Override
    public void onEnable() {
        instance = this;

        // Save default config
        saveDefaultConfig();

        // Initialize verification manager
        File customConfigFile = new File(getDataFolder(), "config.yml");
        FileConfiguration customConfig = YamlConfiguration.loadConfiguration(customConfigFile);
        String apiUrl = customConfig.getString("api-url");
        long periodPerRequest = customConfig.getLong("period-per-request", 36000L);
        if (apiUrl == null) {
            throw new IllegalStateException("Please set api-url");
        }
        verificationManager = new PlayerVerificationManager(this, apiUrl, periodPerRequest);
        // Initialize restriction listener
        restrictionListener = new PlayerRestrictionListener(verificationManager);
        // Initialize shutdown manager
        shutdownManager = new ShutdownManager(this, apiUrl);

        File logFile = new File(getDataFolder(), "log.txt");
        try {
            if (!logFile.exists() && logFile.createNewFile()) {
                getLogger().info("Created new log file");
            }
        } catch (IOException e) {
            getLogger().warning("Error creating log file: " + e.getMessage());
        }

        logCaptureHandler = new LogCaptureHandler(logFile);
        Handler[] handlers = getLogger().getHandlers();
        if (handlers.length > 0 && handlers[0].getFormatter() != null) {
            logCaptureHandler.setFormatter(handlers[0].getFormatter());
        } else {
            logCaptureHandler.setFormatter(new SimpleFormatter());
        }
        getLogger().addHandler(logCaptureHandler);

        try {
            httpServer = new HttpServer(6001, logCaptureHandler);
            getLogger().info("HTTP server started on port: " + 6001);
        } catch (IOException e) {
            getLogger().warning("Error creating HTTP server: " + e.getMessage());
        }
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(restrictionListener, this);

        // Register commands
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
        return shutdownManager.haveScheduledShutdown();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        event.getPlayer().sendMessage(Component.text("Hello, " + event.getPlayer().getName() + "!"));

        // Cancel grace period shutdown if a player rejoins
        shutdownManager.handlePlayerRejoin();

        // Start player verification
        verificationManager.verifyPlayer(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        event.getPlayer().sendMessage(Component.text("Goodbye, " + event.getPlayer().getName() + "!"));

        // Stop monitoring when player quits
        verificationManager.stopMonitoring(event.getPlayer());

        Bukkit.getScheduler().runTask(getInstance(), () -> {
            int onlinePlayersCount = Bukkit.getOnlinePlayers().size();
            if (onlinePlayersCount == 0) {
                // Trigger grace period shutdown when all players leave
                shutdownManager.shutdown(20 * 60, true);
            }
        });
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        var position = player.getLocation();
        Bukkit.broadcast(Component.text("Grab " + player.getName() + " items at " + position.getBlockX() + ", " + position.getBlockY() + ", " + position.getBlockZ() + "!"));
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
}
