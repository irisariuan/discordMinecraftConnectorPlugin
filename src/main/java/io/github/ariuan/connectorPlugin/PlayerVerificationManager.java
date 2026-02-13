package io.github.ariuan.connectorPlugin;

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
        for (Player otherPlayer : Bukkit.getOnlinePlayers()) {
            otherPlayer.hidePlayer(plugin, player);
            player.hidePlayer(plugin, otherPlayer);
        }
    }

    private void showPlayer(Player player) {
        for (Player otherPlayer : Bukkit.getOnlinePlayers()) {
            otherPlayer.showPlayer(plugin, player);
            player.showPlayer(plugin, otherPlayer);
        }

    }

    public void verifyPlayer(Player player) {
        UUID uuid = player.getUniqueId();
        plugin.getLogger().info("Verifying player: " + player.getName() + " (" + uuid + ")");

        hidePlayer(player);

        // Create a new session for the player
        PlayerSession session = new PlayerSession();
        playerSessions.put(uuid, session);

        // Call /verify endpoint asynchronously
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                boolean verified = callVerifyEndpoint(player);

                // Update session on main thread
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (verified) {
                        session.setVerified(true);
                        player.sendMessage("Welcome back to the server!");
                        plugin.getLogger().info("Player " + player.getName() + " verified successfully");
                        showPlayer(player);
                        // Start monitoring task
                        startMonitoring(player);
                    } else {
                        player.sendMessage(Component.text("You have not linked your account to Discord yet! Please use /link in the server!").color(NamedTextColor.DARK_RED));
                    }
                });
            } catch (Exception e) {
                plugin.getLogger().severe("Error verifying player " + player.getName() + ": " + e.getMessage());
                Bukkit.getScheduler().runTask(plugin, () -> player.kick(Component.text("Verification error. Please try again later or contact the administrator.")));
            }
        });
    }

    private boolean callVerifyEndpoint(Player player) throws IOException {
        String endpoint = apiUrl + "/verify";
        URL url = URI.create(endpoint).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            JsonObject json = new JsonObject();
            UUID uuid = player.getUniqueId();
            json.addProperty("uuid", uuid.toString());
            json.addProperty("playerName", player.getName());
            json.addProperty("serverPort", serverPort);

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

        // Create a repeating task that runs every 30 minutes (36000 ticks)
        BukkitTask task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (!player.isOnline()) {
                stopMonitoring(player);
                return;
            }

            long onlineTime = session.getOnlineTime();

            try {
                boolean shouldKick = callPlayEndpoint(player, onlineTime, false);

                if (shouldKick) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (player.isOnline()) {
                            player.kick(Component.text("You have not enough credits to play on the server!"));
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
        var onlineTime = session.getOnlineTime();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                callPlayEndpoint(player, onlineTime, true);
            } catch (IOException e) {
                plugin.getLogger().warning("Error calling play endpoint (disconnecting) for " + player.getName() + ": " + e.getMessage());
            }
        });
    }


    private boolean callPlayEndpoint(Player player, long onlineTime, boolean disconnect) throws IOException {
        String endpoint = apiUrl + "/play";
        URL url = URI.create(endpoint).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            JsonObject json = new JsonObject();
            UUID uuid = player.getUniqueId();
            json.addProperty("uuid", uuid.toString());
            json.addProperty("playerName", player.getName());
            json.addProperty("serverPort", serverPort);
            json.addProperty("onlineTime", onlineTime);
            json.addProperty("disconnect", disconnect);

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = json.toString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
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
        private long lastJoinTime;

        public PlayerSession() {
            this.joinTime = System.currentTimeMillis();
            this.lastJoinTime = joinTime;
            this.verified = false;
        }

        public long getOnlineTime() {
            return System.currentTimeMillis() - joinTime; // Return in milliseconds
        }

        public boolean isVerified() {
            return verified;
        }

        public void setVerified(boolean verified) {
            this.verified = verified;
        }
    }
}
