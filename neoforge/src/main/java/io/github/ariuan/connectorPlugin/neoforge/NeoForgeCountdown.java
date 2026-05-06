package io.github.ariuan.connectorPlugin.neoforge;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.ChatFormatting;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Broadcasts a second-by-second countdown to all players and then fires a callback. */
public class NeoForgeCountdown {
    private final MinecraftServer server;
    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> future;

    public NeoForgeCountdown(MinecraftServer server, ScheduledExecutorService scheduler) {
        this.server = server;
        this.scheduler = scheduler;
    }

    public void start(int seconds) {
        AtomicInteger remaining = new AtomicInteger(seconds);
        future = scheduler.scheduleAtFixedRate(() -> {
            int current = remaining.getAndDecrement();
            if (current > 0) {
                server.submit(() ->
                    server.getPlayerList().broadcastSystemMessage(
                        Component.literal("Shutting down in " + current + " seconds!")
                                 .withStyle(ChatFormatting.DARK_RED), false));
            } else {
                cancel();
            }
        }, 0, 1, TimeUnit.SECONDS);
    }

    public void cancel() {
        if (future != null && !future.isCancelled()) {
            future.cancel(false);
        }
    }
}
