package io.github.ariuan.connectorPlugin.paper;

import io.github.ariuan.connectorPlugin.common.ConnectorApi;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ShutdownManager {
    private final ConnectorPlugin plugin;
    private final ConnectorApi api;
    private final List<BukkitTask> shutdownTasks = new ArrayList<>();
    private volatile Countdown activeCountdown;
    private boolean isGracePeriodShutdown = false;
    public static final long GRACE_PERIOD_TICKS = 20 * 60;

    public ShutdownManager(ConnectorPlugin plugin, ConnectorApi api) {
        this.plugin = plugin;
        this.api = api;
    }

    public boolean cancelShutdown() {
        if (shutdownTasks.isEmpty()) return false;
        plugin.getLogger().info("Cancelling shutdown");
        Bukkit.broadcast(Component.text("Cancelled shutdown", NamedTextColor.GREEN));
        for (BukkitTask task : shutdownTasks) {
            if (task == null || task.isCancelled()) continue;
            task.cancel();
        }
        shutdownTasks.clear();
        stopCountdown();
        isGracePeriodShutdown = false;
        return true;
    }

    /**
     * Stops the countdown broadcast. Cancelling the scheduled tasks above is not
     * enough once the countdown has started: it drives its own repeating task.
     */
    private void stopCountdown() {
        Countdown countdown = activeCountdown;
        if (countdown != null) {
            countdown.cancel();
            activeCountdown = null;
        }
    }

    public boolean shutdown(long tickDelay, boolean allowGracePeriod) {
        plugin.getLogger().info("Shutting down in " + tickDelay + " ticks (grace period: " + allowGracePeriod + ")");

        if (tickDelay <= 0) {
            Bukkit.broadcast(Component.text("Shutting down server!", NamedTextColor.DARK_RED));
            Bukkit.getServer().shutdown();
            return true;
        }

        if (!shutdownTasks.isEmpty()) return false;

        isGracePeriodShutdown = allowGracePeriod;

        if (allowGracePeriod) {
            Bukkit.broadcast(Component.text(
                    "All players left. Server will shutdown in " + (tickDelay / 20) + " seconds if no one rejoins.",
                    NamedTextColor.YELLOW));
        } else if (tickDelay > 20 * 15) {
            long seconds = Math.floorDiv(tickDelay, 20);
            Bukkit.broadcast(Component.text("Shutting down server in " + seconds + " seconds", NamedTextColor.DARK_RED));
        }

        if (tickDelay > 20 * 10) {
            shutdownTasks.add(Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Countdown countdown = new Countdown();
                activeCountdown = countdown;
                countdown.start(10);
            }, tickDelay - 20 * 10));
        }

        shutdownTasks.add(Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Bukkit.broadcast(Component.text("Shutting down server!", NamedTextColor.DARK_RED));
            plugin.getLogger().info("Scheduled shutting down server");
            Bukkit.getServer().shutdown();
            shutdownTasks.clear();
            stopCountdown();
            isGracePeriodShutdown = false;
        }, tickDelay));

        return true;
    }

    public void handlePlayerRejoin() {
        if (isGracePeriodShutdown && !shutdownTasks.isEmpty()) {
            plugin.getLogger().info("Player rejoined during grace period, cancelling shutdown");
            cancelShutdown();
        }
    }

    public boolean hasScheduledShutdown() {
        return !shutdownTasks.isEmpty();
    }

    public boolean cancelShutdownViaApi(Player player) {
        if (shutdownTasks.isEmpty()) {
            plugin.getLogger().info("No shutdown scheduled to cancel");
            return false;
        }
        try {
            ConnectorApi.CancelDecision decision =
                    api.requestCancelShutdown(player.getUniqueId(), player.getName());
            if (decision.allowed()) {
                return cancelShutdown();
            }
            String reason = decision.reason() == null ? "denied by the bot" : decision.reason();
            plugin.getLogger().info("Bot denied shutdown cancellation: " + reason);
            Bukkit.broadcast(Component.text("Shutdown cancellation denied: " + reason, NamedTextColor.RED));
            return false;
        } catch (IOException e) {
            plugin.getLogger().warning("Error requesting shutdown cancellation: " + e.getMessage());
            Bukkit.broadcast(Component.text("Error contacting the bot for shutdown cancellation", NamedTextColor.RED));
            return false;
        }
    }
}
