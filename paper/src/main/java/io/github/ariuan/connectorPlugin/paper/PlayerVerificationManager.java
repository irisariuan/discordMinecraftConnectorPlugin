package io.github.ariuan.connectorPlugin.paper;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerVerificationManager {
    private final ConnectorPlugin plugin;
    private final String apiUrl;
    private final int serverPort;
    private final long periodTick;
    private final Map<UUID, PlayerSession> playerSessions = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> monitoringTasks = new ConcurrentHashMap<>();

    public PlayerVerificationManager(ConnectorPlugin plugin, String apiUrl, long periodTick) {
        this.plugin = plugin;
        this.apiUrl = apiUrl;
        this.serverPort = Bukkit.getServer().getPort();
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
                boolean verified = callVerifyEndpoint(player);

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

    private boolean callVerifyEndpoint(Player player) throws IOException {
        URL url = URI.create(apiUrl + "/verify").toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            JsonObject json = new JsonObject();
            json.addProperty("uuid", player.getUniqueId().toString());
            json.addProperty("playerName", player.getName());
            json.addProperty("serverPort", serverPort);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.toString().getBytes(StandardCharsets.UTF_8));
            }

            if (conn.getResponseCode() == 200) {
                String response = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                JsonObject responseJson = JsonParser.parseString(response).getAsJsonObject();
                return responseJson.has("verified") && responseJson.get("verified").getAsBoolean();
            }
            return false;
        } finally {
            conn.disconnect();
        }
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
                boolean shouldKick = callPlayEndpoint(player, session.getOnlineTime(), false);
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
                callPlayEndpoint(player, onlineTime, true);
            } catch (IOException e) {
                plugin.getLogger().warning("Error calling play endpoint (disconnecting) for " + player.getName() + ": " + e.getMessage());
            }
        });
    }

    private boolean callPlayEndpoint(Player player, long onlineTime, boolean disconnect) throws IOException {
        URL url = URI.create(apiUrl + "/play").toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            JsonObject json = new JsonObject();
            json.addProperty("uuid", player.getUniqueId().toString());
            json.addProperty("playerName", player.getName());
            json.addProperty("serverPort", serverPort);
            json.addProperty("onlineTime", onlineTime);
            json.addProperty("disconnect", disconnect);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.toString().getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if (code == 200) {
                if (disconnect) return true;
                String response = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                JsonObject responseJson = JsonParser.parseString(response).getAsJsonObject();
                return responseJson.has("kick") && responseJson.get("kick").getAsBoolean();
            }
            return false;
        } finally {
            conn.disconnect();
        }
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
