package io.github.ariuan.connectorPlugin.common;

import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Platform-agnostic interface that abstracts all server operations needed by
 * the shared {@link HttpServer}.  Each platform (Paper, NeoForge, Fabric) provides
 * its own implementation.
 */
public interface IPlatformAdapter {

    Logger getLogger();

    /** All currently online players. */
    List<IPlayerInfo> getOnlinePlayers();

    /** Find player by exact name; returns {@code null} if not found. */
    IPlayerInfo getPlayerByName(String name);

    /** Find player by UUID; returns {@code null} if not found. */
    IPlayerInfo getPlayerByUUID(UUID uuid);

    /** Send a plain-text message to a specific player. */
    void sendMessageToPlayer(UUID playerUUID, String message);

    /** Mark a player as verified (called after OTP confirmation). */
    void markPlayerVerified(UUID playerUUID);

    /** Execute a server command on the main thread and capture its output. */
    CommandResult runCommandAndCapture(String command);

    /** Schedule a server shutdown.  Returns false if one is already scheduled. */
    boolean scheduleShutdown(long tickDelay, boolean isGracePeriod);

    /** Cancel a scheduled shutdown.  Returns false if none was scheduled. */
    boolean cancelShutdown();

    /** Returns true if a shutdown is currently scheduled. */
    boolean hasScheduledShutdown();

    /** Names of all loaded plugins / mods. */
    List<String> getLoadedComponents();

    /** Submit a task to run on the main server thread. */
    void runOnMainThread(Runnable task);

    /** Submit a task to run asynchronously. */
    void runAsync(Runnable task);

    /** The TCP port the Minecraft server is listening on. */
    int getServerPort();

    /** The shared log-capture handler (used by the /logs endpoint). */
    LogCaptureHandler getLogCaptureHandler();
}
