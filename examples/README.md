# IPC Client Examples

This directory contains example clients for communicating with the Minecraft plugin's IPC server using Unix Domain Sockets.

## Available Examples

### Python Client (`ipc_client.py`)

A Python 3.x client demonstrating how to interact with the plugin.

**Usage:**
```bash
python3 ipc_client.py
```

**Requirements:**
- Python 3.3 or higher (for Unix Domain Socket support)

**Features:**
- Simple API wrapper around Unix Domain Sockets
- Automatic JSON encoding/decoding
- Helper methods for common operations
- Error handling

**Example:**
```python
from pathlib import Path
from ipc_client import MinecraftIPCClient

socket_path = Path("plugins/DiscordConnectorPlugin/minecraft-ipc.sock")
client = MinecraftIPCClient(socket_path)

# Ping the server
print(client.ping())

# Get online players
players = client.get_players()
print(players)

# Run a command
result = client.run_command("list")
print(result['output'])
```

### Java Client

See `src/main/java/io/github/ariuan/connectorPlugin/example/IPCClientExample.java` for a comprehensive Java client implementation.

## Socket Location

The IPC socket is created at:
```
plugins/DiscordConnectorPlugin/minecraft-ipc.sock
```

This path is relative to your Minecraft server's root directory.

## Protocol

The IPC protocol is simple and REST-like:

**Request Format:**
```
METHOD /path
JSON_BODY (optional, for POST requests)
```

**Response Format:**
```
STATUS_CODE
RESPONSE_BODY
```

**Example:**
```
Request:  POST /runCommand\n{"command": "list"}
Response: 200\n{"success": true, "output": "There are 3 players online", "logger": ""}
```

## Security

Unix Domain Sockets are secured through file system permissions. Only processes with access to the socket file can communicate with the plugin. Ensure the socket file has appropriate permissions for your use case.

## Troubleshooting

**Socket file not found:**
- Ensure the Minecraft server is running
- Verify the plugin is loaded (`/plugins` command)
- Check the plugin's data folder for the socket file

**Permission denied:**
- Ensure your process has read/write permissions on the socket file
- Run with appropriate user privileges

**Connection refused:**
- The IPC server may not be running
- Check the plugin logs for errors
