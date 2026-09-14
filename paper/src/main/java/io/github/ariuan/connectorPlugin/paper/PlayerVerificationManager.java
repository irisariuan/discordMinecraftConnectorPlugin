package io.github.ariuan.connectorPlugin.paper;

import io.github.ariuan.connectorPlugin.common.ConnectorApi;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerVerificationManager {
    private final ConnectorPlugin plugin;
    private final ConnectorApi api;
    private final long periodTick;
    private final Map<UUID, PlayerSession> playerSessions = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> monitoringTasks = new ConcurrentHashMap<>();

    public PlayerVerificationManager(ConnectorPlugin plugin, ConnectorApi api, long periodTick) {
        this.plugin = plugin;
        this.api = api;
        this.periodTick = periodTick;
    }

    private void hidePlayer(Player player) {
        for (Player other : Bukkit.getOnlinePlayers()) {
            other.hidePlayer(plugin, player);
            player.hidePlayer(plugin, other);
        }
    }

    private void showPlayer(Player player) {
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.getUniqueId().equals(player.getUniqueId())) continue;
            if (playerSessions.containsKey(other.getUniqueId()) && playerSessions.get(other.getUniqueId()).isVerified()) {
                other.showPlayer(plugin, player);
                player.showPlayer(plugin, other);
            }
        }
    }

    public void verifyPlayer(Player player) {
        UUID uuid = player.getUniqueId();
        plugin.getLogger().info("Verifying player: " + player.getName() + " (" + uuid + ")");

        hidePlayer(player);

        PlayerSession session = new PlayerSession();
        playerSessions.put(uuid, session);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                boolean verified = api.verify(player.getUniqueId(), player.getName());

                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (verified) {
                        session.setVerified(true);
                        player.sendMessage("Welcome back to the server!");
                        plugin.getLogger().info("Player " + player.getName() + " verified successfully");
                        showPlayer(player);
                        startMonitoring(player);
                    } else {
                        player.sendMessage(Component.text(
                                "You have not linked your account to Discord yet! Please use /link in the Discord!")
                                .color(NamedTextColor.DARK_RED));
                    }
                });
            } catch (Exception e) {
                plugin.getLogger().severe("Error verifying player " + player.getName() + ": " + e.getMessage());
                Bukkit.getScheduler().runTask(plugin, () ->
                        player.kick(Component.text("Verification error. Please try again later or contact the administrator.")));
            }
        });
    }

    private void startMonitoring(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerSession session = playerSessions.get(uuid);
        if (session == null) return;

        BukkitTask task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (!player.isOnline()) {
                stopMonitoring(player);
                return;
            }
            try {
                boolean shouldKick = api.play(
                        player.getUniqueId(), player.getName(), session.getOnlineTime(), false);
                if (shouldKick) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (player.isOnline()) {
                            player.kick(Component.text("You do not have enough credits to play on the server!"));
                            plugin.getLogger().info("Kicked player " + player.getName() + " due to play endpoint response");
                        }
                    });
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Error calling play endpoint for " + player.getName() + ": " + e.getMessage());
            }
        }, 0, periodTick);

        monitoringTasks.put(uuid, task);
    }

    private void sendFinalOnlineTime(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerSession session = playerSessions.get(uuid);
        if (session == null) return;
        long onlineTime = session.getOnlineTime();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                api.play(uuid, player.getName(), onlineTime, true);
            } catch (IOException e) {
                plugin.getLogger().warning("Error reporting the final online time for " + player.getName() + ": " + e.getMessage());
            }
        });
    }

    public void stopMonitoring(Player player) {
        UUID uuid = player.getUniqueId();
        sendFinalOnlineTime(player);
        BukkitTask task = monitoringTasks.remove(uuid);
        if (task != null && !task.isCancelled()) {
            task.cancel();
        }
        playerSessions.remove(uuid);
    }

    public boolean isVerified(UUID uuid) {
        PlayerSession session = playerSessions.get(uuid);
        return session != null && session.isVerified();
    }

    public void cleanup() {
        for (BukkitTask task : monitoringTasks.values()) {
            if (task != null && !task.isCancelled()) {
                task.cancel();
            }
        }
        monitoringTasks.clear();
        playerSessions.clear();
    }

    private static class PlayerSession {
        private final long joinTime = System.currentTimeMillis();
        private volatile boolean verified = false;

        public long getOnlineTime() {
            return System.currentTimeMillis() - joinTime;
        }

        public boolean isVerified() { return verified; }
        public void setVerified(boolean verified) { this.verified = verified; }
    }
}
