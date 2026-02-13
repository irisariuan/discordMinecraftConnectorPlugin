# Discord Minecraft Connector Plugin - Player Verification System

## Overview

This plugin implements a player verification and monitoring system that restricts player actions until they are verified via an external API. The system integrates with an external service to control player access and monitor their session time.

**Architecture**: The plugin runs as a Minecraft server plugin and provides an **IPC (Inter-Process Communication)** server using Unix Domain Sockets for local communication. This allows external processes running on the same machine to interact with the Minecraft server through a REST-like API.

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

## IPC Server

The plugin starts an IPC server using Unix Domain Sockets. The socket file is created at:
```
plugins/DiscordConnectorPlugin/minecraft-ipc.sock
```

### Communication Protocol

The IPC server uses a REST-like protocol:
- **Request format**: `METHOD /path\nJSON_BODY`
- **Response format**: `STATUS_CODE\nRESPONSE_BODY`

Example request:
```
POST /runCommand
{"command": "list"}
```

Example response:
```
200
{"success": true, "output": "There are 3 players online", "logger": ""}
```

## API Endpoints

The IPC server provides the following REST-like endpoints:

### POST /verify

**Request:**
```json
{
  "uuid": "player-uuid-here"
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
  "onlineTime": 1234
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
6. The IPC socket will be created at `plugins/DiscordConnectorPlugin/minecraft-ipc.sock`

## IPC Client Usage

To communicate with the plugin from another process, you can use Unix Domain Sockets. Here's a Java example:

```java
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;

Path socketPath = Paths.get("plugins/DiscordConnectorPlugin/minecraft-ipc.sock");
UnixDomainSocketAddress address = UnixDomainSocketAddress.of(socketPath);

try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
    channel.connect(address);
    
    // Send request
    String request = "GET /ping";
    ByteBuffer buffer = ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8));
    channel.write(buffer);
    
    // Read response
    buffer = ByteBuffer.allocate(8192);
    channel.read(buffer);
    buffer.flip();
    String response = StandardCharsets.UTF_8.decode(buffer).toString();
    System.out.println(response);
}
```

For a complete working example, see `src/main/java/io/github/ariuan/connectorPlugin/example/IPCClientExample.java`.

**Available IPC Endpoints:**
- `GET /ping` - Returns "Pong!" to test connectivity
- `GET /players` - Returns list of online players
- `GET /logs` - Returns recent server logs
- `GET /plugins` - Returns list of installed plugins
- `GET /shutdown` - Schedules server shutdown (60 seconds)
- `GET /cancelShutdown` - Cancels scheduled shutdown
- `GET /shuttingDown` - Checks if shutdown is scheduled
- `POST /runCommand` - Executes a Minecraft command
- `POST /shutdown` - Schedules server shutdown with custom delay

## Technical Details

### Classes

- **PlayerVerificationManager**: Handles API communication and player session tracking
- **PlayerRestrictionListener**: Listens to player events and enforces restrictions
- **ConnectorPlugin**: Main plugin class that coordinates everything
- **IPCServer**: IPC server using Unix Domain Sockets for inter-process communication

### Event Handling

The plugin uses Bukkit's event system with HIGHEST priority to ensure restrictions are enforced before other plugins can process events.

### Threading

- Verification and monitoring API calls are made asynchronously to avoid blocking the main server thread
- Player kicks and state changes are executed on the main thread for thread safety
- IPC server runs on a separate thread pool to handle concurrent connections

### IPC vs HTTP

This plugin uses IPC (Unix Domain Sockets) instead of HTTP for local communication because:
- **Security**: Socket files can have file system permissions, limiting access to authorized processes
- **Performance**: Unix Domain Sockets are faster than TCP/HTTP for local communication
- **No Port Conflicts**: No need to manage port numbers
- **Process Isolation**: Better suited for communication between processes on the same machine

## Dependencies

- PaperMC API 1.21.4
- GSON 2.12.1 (for JSON serialization)
- Java 21+ (LTS version with Unix Domain Socket support introduced in Java 16)

## Building

```bash
./gradlew clean build
```

The compiled JAR will be in `build/libs/`.

**Note**: While Unix Domain Sockets were introduced in Java 16, we use Java 21 as it's a Long Term Support (LTS) version with better stability and maintenance. The code is compatible with Java 23 and higher.
