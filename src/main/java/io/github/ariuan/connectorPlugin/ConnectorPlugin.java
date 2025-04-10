package io.github.ariuan.connectorPlugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.SimpleFormatter;

public class ConnectorPlugin extends JavaPlugin implements Listener {
    private HttpServer httpServer;
    private static ConnectorPlugin instance;
    private LogCaptureHandler logCaptureHandler;
    private final List<BukkitTask> shutdownTask = new ArrayList<>();

    @Override
    public void onEnable() {
        instance = this;
        File logFile = new File(getDataFolder(), "log.txt");
        try {
            if (!logFile.exists() && logFile.createNewFile()) {
                getLogger().info("Created new log file");
            }
        } catch (IOException e) {
            e.printStackTrace();
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
            e.printStackTrace();
        }
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    public static ConnectorPlugin getInstance() {
        return instance;
    }

    public boolean cancelShutdown() {
        if (shutdownTask.isEmpty()) return false;
        getLogger().info("Canceling shutdown");
        Bukkit.broadcast(Component.text("Canceling shutdown", NamedTextColor.GREEN));
        for (BukkitTask task : shutdownTask) {
            if (task == null) continue;
            if (task.isCancelled()) continue;
            task.cancel();
        }
        shutdownTask.clear();
        return true;
    }

    public boolean shutdown(long tickDelay) {
        getLogger().info("Shutting down in " + tickDelay + " tick");
        if (tickDelay <= 0) {
            Bukkit.broadcast(Component.text("Shutting down server!", NamedTextColor.DARK_RED));
            Bukkit.getServer().shutdown();
            return true;
        }
        if (!shutdownTask.isEmpty()) return false;
        if (tickDelay > 20 * 15) {
            long seconds = Math.floorDiv(tickDelay, 20);
            Bukkit.broadcast(Component.text("Shutting down server in " + seconds + " seconds", NamedTextColor.DARK_RED));
        }
        if (tickDelay > 20 * 10) {
            shutdownTask.add(Bukkit.getScheduler().runTaskLater(getInstance(), () -> {
                Countdown countdown = new Countdown();
                countdown.start(10);
            }, tickDelay - 20 * 10));
        }
        shutdownTask.add(Bukkit.getScheduler().runTaskLater(getInstance(), () -> {
            Bukkit.broadcast(Component.text("Shutting down server!", NamedTextColor.DARK_RED));
            getLogger().info("Scheduled shutting down server");
            Bukkit.getServer().shutdown();
            shutdownTask.clear();
        }, tickDelay));
        return true;
    }

    public boolean haveScheduledShutdown() {
        return !shutdownTask.isEmpty();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        event.getPlayer().sendMessage(Component.text("Hello, " + event.getPlayer().getName() + "!"));
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        event.getPlayer().sendMessage(Component.text("Goodbye, " + event.getPlayer().getName() + "!"));
        Bukkit.getScheduler().runTask(getInstance(), () -> {
            int onlinePlayersCount = Bukkit.getOnlinePlayers().size();
            if (onlinePlayersCount == 0) {
                shutdown(20 * 60);
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
        if (httpServer != null){
            httpServer.stop();
        }
        if (logCaptureHandler != null) {
            getLogger().removeHandler(logCaptureHandler);
        }
    }
}
