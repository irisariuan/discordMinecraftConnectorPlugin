package io.github.ariuan.connectorPlugin.forge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class ForgePlayerVerificationManager {
    private final MinecraftServer server;
    private final String apiUrl;
    private final long periodMs;
    private final Logger logger;
    private final ScheduledExecutorService scheduler;
    private final Map<UUID, PlayerSession> playerSessions = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledFuture<?>> monitoringTasks = new ConcurrentHashMap<>();

    public ForgePlayerVerificationManager(MinecraftServer server, String apiUrl, long periodTick,
                                          Logger logger, ScheduledExecutorService scheduler) {
        this.server = server;
        this.apiUrl = apiUrl;
        this.periodMs = periodTick * 50L;
        this.logger = logger;
        this.scheduler = scheduler;
    }

    public void verifyPlayer(ServerPlayer player) {
        UUID uuid = player.getUUID();
        logger.info("Verifying player: " + player.getName().getString() + " (" + uuid + ")");

        PlayerSession session = new PlayerSession();
        playerSessions.put(uuid, session);

        scheduler.submit(() -> {
            try {
                boolean verified = callVerifyEndpoint(player);
                server.submit(() -> {
                    if (verified) {
                        session.setVerified(true);
                        player.sendSystemMessage(Component.literal("Welcome back to the server!"));
                        logger.info("Player " + player.getName().getString() + " verified successfully");
                        startMonitoring(player);
                    } else {
                        player.sendSystemMessage(Component.literal(
                                "You have not linked your account to Discord yet! Please use /link in the Discord!")
                                .withStyle(ChatFormatting.DARK_RED));
                    }
                });
            } catch (Exception e) {
                logger.severe("Error verifying player " + player.getName().getString() + ": " + e.getMessage());
                server.submit(() ->
                    player.connection.disconnect(
                        Component.literal("Verification error. Please try again later or contact the administrator.")));
            }
        });
    }

    private boolean callVerifyEndpoint(ServerPlayer player) throws IOException {
        URL url = URI.create(apiUrl + "/verify").toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            JsonObject json = new JsonObject();
            json.addProperty("uuid", player.getUUID().toString());
            json.addProperty("playerName", player.getName().getString());
            json.addProperty("serverPort", server.getPort());

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

    private void startMonitoring(ServerPlayer player) {
        UUID uuid = player.getUUID();
        PlayerSession session = playerSessions.get(uuid);
        if (session == null) return;

        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(() -> {
            if (!server.getPlayerList().getPlayers().contains(player)) {
                stopMonitoring(player);
                return;
            }
            try {
                boolean shouldKick = callPlayEndpoint(player, session.getOnlineTime(), false);
                if (shouldKick) {
                    server.submit(() -> {
                        if (server.getPlayerList().getPlayers().contains(player)) {
                            player.connection.disconnect(
                                Component.literal("You do not have enough credits to play on the server!"));
                            logger.info("Kicked player " + player.getName().getString() + " due to play endpoint response");
                        }
                    });
                }
            } catch (Exception e) {
                logger.warning("Error calling play endpoint for " + player.getName().getString() + ": " + e.getMessage());
            }
        }, 0, periodMs, TimeUnit.MILLISECONDS);

        monitoringTasks.put(uuid, task);
    }

    private void sendFinalOnlineTime(ServerPlayer player) {
        UUID uuid = player.getUUID();
        PlayerSession session = playerSessions.get(uuid);
        if (session == null) return;
        long onlineTime = session.getOnlineTime();
        scheduler.submit(() -> {
            try {
                callPlayEndpoint(player, onlineTime, true);
            } catch (IOException e) {
                logger.warning("Error calling play endpoint (disconnecting) for "
                        + player.getName().getString() + ": " + e.getMessage());
            }
        });
    }

    private boolean callPlayEndpoint(ServerPlayer player, long onlineTime, boolean disconnect) throws IOException {
        URL url = URI.create(apiUrl + "/play").toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            JsonObject json = new JsonObject();
            json.addProperty("uuid", player.getUUID().toString());
            json.addProperty("playerName", player.getName().getString());
            json.addProperty("serverPort", server.getPort());
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

    public void stopMonitoring(ServerPlayer player) {
        UUID uuid = player.getUUID();
        sendFinalOnlineTime(player);
        ScheduledFuture<?> task = monitoringTasks.remove(uuid);
        if (task != null && !task.isCancelled()) {
            task.cancel(false);
        }
        playerSessions.remove(uuid);
    }

    public boolean isVerified(UUID uuid) {
        PlayerSession session = playerSessions.get(uuid);
        return session != null && session.isVerified();
    }

    public void cleanup() {
        for (ScheduledFuture<?> task : monitoringTasks.values()) {
            if (task != null && !task.isCancelled()) task.cancel(false);
        }
        monitoringTasks.clear();
        playerSessions.clear();
    }

    private static class PlayerSession {
        private final long joinTime = System.currentTimeMillis();
        private volatile boolean verified = false;

        public long getOnlineTime() { return System.currentTimeMillis() - joinTime; }
        public boolean isVerified() { return verified; }
        public void setVerified(boolean v) { this.verified = v; }
    }
}
