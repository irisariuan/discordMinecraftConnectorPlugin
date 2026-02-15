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
import java.util.ArrayList;
import java.util.List;

public class ShutdownManager {
    private final ConnectorPlugin plugin;
    private final String apiUrl;
    private final List<BukkitTask> shutdownTasks = new ArrayList<>();
    private boolean isGracePeriodShutdown = false;
    public static final long GRACE_PERIOD_TICKS = 20 * 60; // 60 seconds grace period

    public ShutdownManager(ConnectorPlugin plugin, String apiUrl) {
        this.plugin = plugin;
        this.apiUrl = apiUrl;
    }

    /**
     * Cancel the scheduled shutdown
     *
     * @return true if shutdown was cancelled, false if no shutdown was scheduled
     */
    public boolean cancelShutdown() {
        if (shutdownTasks.isEmpty()) return false;
        plugin.getLogger().info("Cancelling shutdown");
        Bukkit.broadcast(Component.text("Cancelled shutdown", NamedTextColor.GREEN));
        for (BukkitTask task : shutdownTasks) {
            if (task == null) continue;
            if (task.isCancelled()) continue;
            task.cancel();
        }
        shutdownTasks.clear();
        isGracePeriodShutdown = false;
        return true;
    }

    /**
     * Schedule a shutdown with optional grace period
     *
     * @param tickDelay        Delay in ticks before shutdown
     * @param allowGracePeriod Whether to allow grace period (for all-players-left shutdown)
     * @return true if shutdown was scheduled, false if already scheduled
     */
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
            Bukkit.broadcast(Component.text("All players left. Server will shutdown in " + (tickDelay / 20) + " seconds if no one rejoins.", NamedTextColor.YELLOW));
        } else if (tickDelay > 20 * 15) {
            long seconds = Math.floorDiv(tickDelay, 20);
            Bukkit.broadcast(Component.text("Shutting down server in " + seconds + " seconds", NamedTextColor.DARK_RED));
        }

        if (tickDelay > 20 * 10) {
            shutdownTasks.add(Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Countdown countdown = new Countdown();
                countdown.start(10);
            }, tickDelay - 20 * 10));
        }

        shutdownTasks.add(Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Bukkit.broadcast(Component.text("Shutting down server!", NamedTextColor.DARK_RED));
            plugin.getLogger().info("Scheduled shutting down server");
            Bukkit.getServer().shutdown();
            shutdownTasks.clear();
            isGracePeriodShutdown = false;
        }, tickDelay));

        return true;
    }

    /**
     * Handle player rejoin during grace period
     * If shutdown is in grace period, cancel it
     */
    public void handlePlayerRejoin() {
        if (isGracePeriodShutdown && !shutdownTasks.isEmpty()) {
            plugin.getLogger().info("Player rejoined during grace period, cancelling shutdown");
            cancelShutdown();
        }
    }

    /**
     * Check if a shutdown is currently scheduled
     *
     * @return true if shutdown is scheduled
     */
    public boolean hasScheduledShutdown() {
        return !shutdownTasks.isEmpty();
    }

    /**
     * Cancel shutdown via API request
     * Sends a request to the API to check if cancellation is allowed
     *
     * @return true if cancellation was successful, false otherwise
     */
    public boolean cancelShutdownViaApi(Player player) {
        if (shutdownTasks.isEmpty()) {
            plugin.getLogger().info("No shutdown scheduled to cancel");
            return false;
        }

        try {
            boolean allowed = callCancelStopEndpoint(player);
            if (allowed) {
                return cancelShutdown();
            } else {
                plugin.getLogger().info("API denied shutdown cancellation");
                Bukkit.broadcast(Component.text("Shutdown cancellation denied by API", NamedTextColor.RED));
                return false;
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Error calling cancel-stop endpoint: " + e.getMessage());
            Bukkit.broadcast(Component.text("Error contacting API for shutdown cancellation", NamedTextColor.RED));
            return false;
        }
    }

    /**
     * Call the API cancel-stop endpoint
     *
     * @return true if cancellation is allowed
     * @throws IOException if there's a network error
     */
    private boolean callCancelStopEndpoint(Player player) throws IOException {
        String endpoint = apiUrl + "/cancelShutdown";
        URL url = URI.create(endpoint).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            JsonObject json = new JsonObject();
            json.addProperty("serverPort", Bukkit.getServer().getPort());
            json.addProperty("uuid", player.getUniqueId().toString());
            json.addProperty("playerName", player.getName());

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = json.toString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                String response = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                JsonObject responseJson = JsonParser.parseString(response).getAsJsonObject();
                return responseJson.has("allowed") && responseJson.get("allowed").getAsBoolean();
            }
            return false;
        } finally {
            conn.disconnect();
        }
    }
}
