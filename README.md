# Discord Minecraft Connector Plugin - Player Verification System

## Overview

This plugin implements a player verification and monitoring system that restricts player actions until they are verified by the Discord bot. The bot controls player access and monitors session time over a local IPC channel.

## Features

### Player Restrictions (Pre-Verification)

When a player joins the server, they are immediately restricted from performing the following actions until verified:

- **Picking up items** - Players cannot pick up any items from the ground
- **Dropping items** - Players cannot drop items from their inventory
- **Block interactions** - Players cannot interact with blocks (placing, breaking, using)
- **Chat messages** - Players cannot send chat messages
- **Combat** - Players cannot deal or receive damage

### Verification System

When a player joins:
1. The plugin calls `player.verify` over the IPC channel with the player's UUID
   and name
2. The bot responds with a verification status
3. If verified, all restrictions are lifted
4. If not verified, the player is told to link their account in Discord

### Session Monitoring

Once verified, the plugin monitors each player:
1. Every `period-per-request` ticks, calls `player.play` with:
   - Player UUID and name
   - Current session online time (in milliseconds)
2. The bot can respond with a kick instruction
3. If instructed, the player is kicked with an appropriate message

## Transport

The plugin and the bot talk over a **Unix domain socket** — no HTTP, and no
listening port on either side. The bot creates the socket when it launches the
server and passes the path in the `CONNECTOR_IPC_SOCKET` environment variable;
the plugin dials it and reconnects with backoff, so a bot restart only detaches
the server until the next attempt succeeds. Frames are newline-delimited JSON
and both sides may issue requests over the same connection.

The full wire format and method list live in `docs/IPC.md` in the
[bot repository](https://github.com/irisariuan/minecraftDiscordConnector).

Transport code is platform-agnostic and lives in the `common` module:

- **IpcClient**: socket session, framing, request/response correlation, reconnect
- **ConnectorApi**: the methods the bot may call (delegated to the platform
  adapter) plus the typed calls the plugin makes back into the bot

## Configuration

Edit the config file in the plugin/mod data folder (`config.yml` for Paper,
`config/discordconnector/config.json` for Fabric and NeoForge):

```yaml
# Leave empty to use CONNECTOR_IPC_SOCKET, falling back to connector.sock in
# the server directory. Set it only if the socket lives somewhere else.
socket-path: ""
# How often to re-check player credits, in ticks (default 30 minutes)
period-per-request: 36000
```

## Installation

1. Download the plugin JAR file
2. Place it in your server's `plugins` folder
3. Start/restart your server
4. Only if the socket lives outside the server directory and the bot does not
   launch the server: set `socket-path` in
   `plugins/DiscordConnectorPlugin/config.yml`
5. Reload/restart the server

## Technical Details

### Classes

- **PlayerVerificationManager**: Handles bot communication and player session tracking
- **PlayerRestrictionListener**: Listens to player events and enforces restrictions
- **ConnectorPlugin**: Main plugin class that coordinates everything

### Event Handling

The plugin uses Bukkit's event system with HIGHEST priority to ensure restrictions are enforced before other plugins can process events.

### Threading

- Verification and monitoring calls are made asynchronously to avoid blocking the main server thread
- Player kicks and state changes are executed on the main thread for thread safety

## Dependencies

- PaperMC API 1.21.11
- GSON 2.12.1
- Java 21 (Unix domain sockets need Java 16+)

## Multi-Platform Support

This plugin is available for three Minecraft server platforms:
- **Paper/Bukkit** (Minecraft 1.21.11)
- **NeoForge** (Minecraft 1.21.11)
- **Fabric** (Minecraft 1.21.11)

## Building

```bash
./gradlew build
```

If you need a clean build, run `clean` as a **separate** invocation:

```bash
./gradlew clean
./gradlew build
```

Do not run `./gradlew clean build` in one command. NeoGradle expands its NeoForm patch bundle into `neoforge/build/tmp` while configuring the project, and a same-invocation `clean` deletes it, so `:neoforge:neoFormSetup` fails with `patches.lzma (No such file or directory)`.

A Java 21 toolchain is required. If it is not installed, Gradle downloads one automatically via the foojay toolchain resolver.

The compiled JARs will be in:
- Paper: `paper/build/libs/minecraftDiscordConnector-paper-<version>.jar`
- NeoForge: `neoforge/build/libs/minecraftDiscordConnector-neoforge-<version>.jar`
- Fabric: `fabric/build/libs/minecraftDiscordConnector-fabric-<version>.jar`

Jars with a `-thin` classifier do not bundle the `common` module and are not meant to be installed.

The version of all three jars comes from `modVersion` in the root `gradle.properties`.

## CI/CD

GitHub Actions builds all three platform versions on every push and pull request. Artifacts are available in the Actions tab.

### Publishing a release

Push a tag starting with `v` to publish a GitHub Release with all three jars attached:

```bash
git tag v1.0.0
git push origin v1.0.0
```

The workflow builds with `-PmodVersion=1.0.0` (the tag without the leading `v`), so the jar file names and the mod metadata carry the tag version.
