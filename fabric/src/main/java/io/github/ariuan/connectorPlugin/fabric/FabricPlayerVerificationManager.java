package io.github.ariuan.connectorPlugin.fabric;

import io.github.ariuan.connectorPlugin.common.ConnectorApi;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class FabricPlayerVerificationManager {
    private final MinecraftServer server;
    private final ConnectorApi api;
    private final long periodMs;
    private final Logger logger;
    private final ScheduledExecutorService scheduler;
    private final Map<UUID, PlayerSession> playerSessions = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledFuture<?>> monitoringTasks = new ConcurrentHashMap<>();

    public FabricPlayerVerificationManager(MinecraftServer server, ConnectorApi api, long periodTick,
                                            Logger logger, ScheduledExecutorService scheduler) {
        this.server = server;
        this.api = api;
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
                boolean verified = api.verify(player.getUUID(), player.getName().getString());
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
                boolean shouldKick = api.play(
                        player.getUUID(), player.getName().getString(), session.getOnlineTime(), false);
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
                api.play(uuid, player.getName().getString(), onlineTime, true);
            } catch (IOException e) {
                logger.warning("Error reporting the final online time for "
                        + player.getName().getString() + ": " + e.getMessage());
            }
        });
    }

    public void stopMonitoring(ServerPlayer player) {
        UUID uuid = player.getUUID();
        sendFinalOnlineTime(player);
        ScheduledFuture<?> task = monitoringTasks.remove(uuid);
        if (task != null && !task.isCancelled()) task.cancel(false);
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
