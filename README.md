# Discord Minecraft Connector Plugin - Player Verification System

## Overview

This plugin implements a player verification and monitoring system that restricts player actions until they are verified via an external API. The system integrates with an external service to control player access and monitor their session time.

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
1. The plugin calls the `/verify` endpoint (POST) with the player's UUID
2. The API responds with a verification status
3. If verified, all restrictions are lifted
4. If not verified, the player is kicked from the server

### Session Monitoring

Once verified, the plugin monitors each player:
1. Every 30 seconds, calls the `/play` endpoint (POST) with:
   - Player UUID
   - Current session online time (in seconds)
2. The API can respond with a kick instruction
3. If instructed, the player is kicked with an appropriate message

## Configuration

Edit `config.yml` in the plugin data folder:

```yaml
# API URL for player verification and monitoring
api-url: "http://localhost:8080"
```

## API Endpoints

### POST /verify

**Request:**
```json
{
  "uuid": "player-uuid-here",
  "serverPort": 25565
}
```

**Response:**
```json
{
  "verified": true
}
```

### POST /play

**Request:**
```json
{
  "uuid": "player-uuid-here",
  "onlineTime": 1234,
  "serverPort": 25565
}
```

**Response:**
```json
{
  "kick": false
}
```

## Installation

1. Download the plugin JAR file
2. Place it in your server's `plugins` folder
3. Start/restart your server
4. Edit `plugins/DiscordConnectorPlugin/config.yml` with your API URL
5. Reload/restart the server

## Technical Details

### Classes

- **PlayerVerificationManager**: Handles API communication and player session tracking
- **PlayerRestrictionListener**: Listens to player events and enforces restrictions
- **ConnectorPlugin**: Main plugin class that coordinates everything

### Event Handling

The plugin uses Bukkit's event system with HIGHEST priority to ensure restrictions are enforced before other plugins can process events.

### Threading

- Verification and monitoring API calls are made asynchronously to avoid blocking the main server thread
- Player kicks and state changes are executed on the main thread for thread safety

## Dependencies

- PaperMC API 1.21.11
- NanoHTTPD 2.2.0
- GSON 2.12.1

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
