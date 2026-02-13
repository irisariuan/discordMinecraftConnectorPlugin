package io.github.ariuan.connectorPlugin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerVerificationManager {
    private final ConnectorPlugin plugin;
    private final String apiUrl;
    private final Map<UUID, PlayerSession> playerSessions = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> monitoringTasks = new ConcurrentHashMap<>();

    public PlayerVerificationManager(ConnectorPlugin plugin, String apiUrl) {
        this.plugin = plugin;
        this.apiUrl = apiUrl;
    }

    public void verifyPlayer(Player player) {
        UUID uuid = player.getUniqueId();
        plugin.getLogger().info("Verifying player: " + player.getName() + " (" + uuid + ")");
        
        // Create a new session for the player
        PlayerSession session = new PlayerSession();
        playerSessions.put(uuid, session);

        // Call /verify endpoint asynchronously
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                boolean verified = callVerifyEndpoint(uuid);
                
                // Update session on main thread
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (verified) {
                        session.setVerified(true);
                        player.sendMessage("§aYou have been verified! Welcome to the server.");
                        plugin.getLogger().info("Player " + player.getName() + " verified successfully");
                        
                        // Start monitoring task
                        startMonitoring(player);
                    } else {
                        player.kickPlayer("§cVerification failed. Please try again later.");
                        plugin.getLogger().warning("Player " + player.getName() + " verification failed");
                    }
                });
            } catch (Exception e) {
                plugin.getLogger().severe("Error verifying player " + player.getName() + ": " + e.getMessage());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    player.kickPlayer("§cVerification error. Please try again later.");
                });
            }
        });
    }

    private boolean callVerifyEndpoint(UUID uuid) throws IOException {
        String endpoint = apiUrl + "/verify";
        URL url = new URL(endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            JsonObject json = new JsonObject();
            json.addProperty("uuid", uuid.toString());
            json.addProperty("serverPort", Bukkit.getServer().getPort());

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = json.toString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                // Read response
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

        // Create a repeating task that runs every 30 seconds (600 ticks)
        BukkitTask task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (!player.isOnline()) {
                stopMonitoring(uuid);
                return;
            }

            long onlineTime = session.getOnlineTime();
            
            try {
                boolean shouldKick = callPlayEndpoint(uuid, onlineTime);
                
                if (shouldKick) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (player.isOnline()) {
                            player.kickPlayer("§cYour session has expired. Please reconnect.");
                            plugin.getLogger().info("Kicked player " + player.getName() + " due to play endpoint response");
                        }
                    });
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Error calling play endpoint for " + player.getName() + ": " + e.getMessage());
            }
        }, 600L, 600L); // Start after 30 seconds, repeat every 30 seconds

        monitoringTasks.put(uuid, task);
    }

    private boolean callPlayEndpoint(UUID uuid, long onlineTime) throws IOException {
        String endpoint = apiUrl + "/play";
        URL url = new URL(endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            JsonObject json = new JsonObject();
            json.addProperty("uuid", uuid.toString());
            json.addProperty("onlineTime", onlineTime);
            json.addProperty("serverPort", Bukkit.getServer().getPort());

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = json.toString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                String response = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                JsonObject responseJson = JsonParser.parseString(response).getAsJsonObject();
                return responseJson.has("kick") && responseJson.get("kick").getAsBoolean();
            }
            return false;
        } finally {
            conn.disconnect();
        }
    }

    public void stopMonitoring(UUID uuid) {
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
        // Cancel all monitoring tasks
        for (BukkitTask task : monitoringTasks.values()) {
            if (task != null && !task.isCancelled()) {
                task.cancel();
            }
        }
        monitoringTasks.clear();
        playerSessions.clear();
    }

    private static class PlayerSession {
        private final long joinTime;
        private volatile boolean verified;

        public PlayerSession() {
            this.joinTime = System.currentTimeMillis();
            this.verified = false;
        }

        public long getOnlineTime() {
            return (System.currentTimeMillis() - joinTime) / 1000; // Return in seconds
        }

        public boolean isVerified() {
            return verified;
        }

        public void setVerified(boolean verified) {
            this.verified = verified;
        }
    }
}
