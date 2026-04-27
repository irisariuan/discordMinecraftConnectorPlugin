package io.github.ariuan.connectorPlugin.common;

import java.util.UUID;

/** Minimal view of an online player, usable without platform-specific imports. */
public interface IPlayerInfo {
    UUID getUUID();
    String getName();
}
