package io.github.ariuan.connectorPlugin.fabric;

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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class FabricShutdownManager {
    private final MinecraftServer server;
    private final String apiUrl;
    private final Logger logger;
    private final ScheduledExecutorService scheduler;
    private final List<ScheduledFuture<?>> shutdownFutures = new ArrayList<>();
    private volatile FabricCountdown activeCountdown;
    private boolean isGracePeriodShutdown = false;
    public static final long GRACE_PERIOD_TICKS = 20 * 60;

    public FabricShutdownManager(MinecraftServer server, String apiUrl, Logger logger,
                                  ScheduledExecutorService scheduler) {
        this.server = server;
        this.apiUrl = apiUrl;
        this.logger = logger;
        this.scheduler = scheduler;
    }

    public boolean cancelShutdown() {
        if (shutdownFutures.isEmpty()) return false;
        logger.info("Cancelling shutdown");
        server.submit(() ->
            server.getPlayerList().broadcastSystemMessage(
                Component.literal("Cancelled shutdown").withStyle(ChatFormatting.GREEN), false));
        for (ScheduledFuture<?> f : shutdownFutures) {
            if (f != null && !f.isCancelled()) f.cancel(false);
        }
        shutdownFutures.clear();
        stopCountdown();
        isGracePeriodShutdown = false;
        return true;
    }

    /**
     * Stops the countdown broadcast. Cancelling the futures above is not enough
     * once the countdown has started: it drives its own repeating future.
     */
    private void stopCountdown() {
        FabricCountdown countdown = activeCountdown;
        if (countdown != null) {
            countdown.cancel();
            activeCountdown = null;
        }
    }

    public boolean shutdown(long tickDelay, boolean allowGracePeriod) {
        logger.info("Shutting down in " + tickDelay + " ticks (grace period: " + allowGracePeriod + ")");

        if (tickDelay <= 0) {
            server.submit(() -> {
                server.getPlayerList().broadcastSystemMessage(
                    Component.literal("Shutting down server!").withStyle(ChatFormatting.DARK_RED), false);
                server.halt(false);
            });
            return true;
        }

        if (!shutdownFutures.isEmpty()) return false;

        isGracePeriodShutdown = allowGracePeriod;
        long msDelay = tickDelay * 50L;

        if (allowGracePeriod) {
            server.submit(() ->
                server.getPlayerList().broadcastSystemMessage(
                    Component.literal("All players left. Server will shutdown in " + (tickDelay / 20)
                            + " seconds if no one rejoins.").withStyle(ChatFormatting.YELLOW), false));
        } else if (tickDelay > 20 * 15) {
            long seconds = tickDelay / 20;
            server.submit(() ->
                server.getPlayerList().broadcastSystemMessage(
                    Component.literal("Shutting down server in " + seconds + " seconds")
                             .withStyle(ChatFormatting.DARK_RED), false));
        }

        if (tickDelay > 20 * 10) {
            ScheduledFuture<?> countdownFuture = scheduler.schedule(() ->
                server.submit(() -> {
                    FabricCountdown countdown = new FabricCountdown(server, scheduler);
                    activeCountdown = countdown;
                    countdown.start(10);
                }),
                msDelay - (20 * 10 * 50L), TimeUnit.MILLISECONDS);
            shutdownFutures.add(countdownFuture);
        }

        ScheduledFuture<?> shutdownFuture = scheduler.schedule(() ->
            server.submit(() -> {
                server.getPlayerList().broadcastSystemMessage(
                    Component.literal("Shutting down server!").withStyle(ChatFormatting.DARK_RED), false);
                logger.info("Scheduled shutting down server");
                server.halt(false);
                shutdownFutures.clear();
                stopCountdown();
                isGracePeriodShutdown = false;
            }),
            msDelay, TimeUnit.MILLISECONDS);
        shutdownFutures.add(shutdownFuture);

        return true;
    }

    public void handlePlayerRejoin() {
        if (isGracePeriodShutdown && !shutdownFutures.isEmpty()) {
            logger.info("Player rejoined during grace period, cancelling shutdown");
            cancelShutdown();
        }
    }

    public boolean hasScheduledShutdown() {
        return !shutdownFutures.isEmpty();
    }

    public boolean cancelShutdownViaApi(ServerPlayer player) {
        if (shutdownFutures.isEmpty()) {
            logger.info("No shutdown scheduled to cancel");
            return false;
        }
        try {
            boolean allowed = callCancelStopEndpoint(player);
            if (allowed) {
                return cancelShutdown();
            } else {
                logger.info("API denied shutdown cancellation");
                server.submit(() ->
                    server.getPlayerList().broadcastSystemMessage(
                        Component.literal("Shutdown cancellation denied by API").withStyle(ChatFormatting.RED), false));
                return false;
            }
        } catch (IOException e) {
            logger.warning("Error calling cancel-stop endpoint: " + e.getMessage());
            server.submit(() ->
                server.getPlayerList().broadcastSystemMessage(
                    Component.literal("Error contacting API for shutdown cancellation").withStyle(ChatFormatting.RED), false));
            return false;
        }
    }

    private boolean callCancelStopEndpoint(ServerPlayer player) throws IOException {
        URL url = URI.create(apiUrl + "/cancelShutdown").toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            JsonObject json = new JsonObject();
            json.addProperty("serverPort", server.getPort());
            json.addProperty("uuid", player.getUUID().toString());
            json.addProperty("playerName", player.getName().getString());

            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.toString().getBytes(StandardCharsets.UTF_8));
            }

            if (conn.getResponseCode() == 200) {
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
