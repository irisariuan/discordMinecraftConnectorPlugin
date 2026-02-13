#!/usr/bin/env python3
"""
Example IPC client for communicating with the Minecraft plugin's IPC server.
This demonstrates how to send requests to the plugin using Unix Domain Sockets from Python.

Requirements: Python 3.x (Unix Domain Sockets supported in Python 3.3+)
"""

import socket
import json
import os
from pathlib import Path


class MinecraftIPCClient:
    """Client for communicating with Minecraft plugin via IPC."""
    
    def __init__(self, socket_path):
        """
        Initialize the IPC client.
        
        Args:
            socket_path: Path to the Unix Domain Socket file
        """
        self.socket_path = socket_path
    
    def send_request(self, method, path, body=None):
        """
        Send a request to the IPC server.
        
        Args:
            method: HTTP-like method (GET, POST)
            path: Endpoint path (e.g., "/ping", "/players")
            body: Optional request body (dict for POST requests)
            
        Returns:
            Response from the server (str or dict)
        """
        # Create a Unix Domain Socket
        sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        
        try:
            # Connect to the server
            sock.connect(str(self.socket_path))
            
            # Build request
            request = f"{method} {path}"
            if body:
                request += "\n" + json.dumps(body)
            
            # Send request
            sock.sendall(request.encode('utf-8'))
            
            # Receive response
            response = sock.recv(8192).decode('utf-8')
            
            # Parse response (STATUS_CODE\nBODY)
            parts = response.split('\n', 1)
            status_code = int(parts[0])
            response_body = parts[1] if len(parts) > 1 else ""
            
            if status_code != 200:
                raise Exception(f"Server returned error: {status_code} - {response_body}")
            
            # Try to parse JSON response
            try:
                return json.loads(response_body)
            except json.JSONDecodeError:
                return response_body
                
        finally:
            sock.close()
    
    def ping(self):
        """Ping the server to test connectivity."""
        return self.send_request("GET", "/ping")
    
    def get_players(self):
        """Get list of online players."""
        return self.send_request("GET", "/players")
    
    def get_logs(self):
        """Get recent server logs."""
        return self.send_request("GET", "/logs")
    
    def get_plugins(self):
        """Get list of installed plugins."""
        return self.send_request("GET", "/plugins")
    
    def run_command(self, command):
        """
        Execute a Minecraft command.
        
        Args:
            command: Minecraft command to execute (without leading /)
            
        Returns:
            dict with 'success', 'output', and 'logger' fields
        """
        return self.send_request("POST", "/runCommand", {"command": command})
    
    def shutdown(self, delay_ticks=1200):
        """
        Schedule server shutdown.
        
        Args:
            delay_ticks: Delay in ticks (20 ticks = 1 second)
            
        Returns:
            dict with 'success' field
        """
        return self.send_request("POST", "/shutdown", {"tick": delay_ticks})
    
    def cancel_shutdown(self):
        """Cancel scheduled shutdown."""
        return self.send_request("GET", "/cancelShutdown")
    
    def is_shutting_down(self):
        """Check if server shutdown is scheduled."""
        result = self.send_request("GET", "/shuttingDown")
        return result.get("result", False)


def main():
    """Example usage of the IPC client."""
    # Update this path to match your server's plugin directory
    socket_path = Path("plugins/DiscordConnectorPlugin/minecraft-ipc.sock")
    
    if not socket_path.exists():
        print(f"Error: Socket file not found at {socket_path}")
        print("Make sure the Minecraft server is running and the plugin is loaded.")
        return
    
    client = MinecraftIPCClient(socket_path)
    
    try:
        # Example 1: Ping the server
        print("=== Ping Test ===")
        response = client.ping()
        print(f"Ping response: {response}")
        print()
        
        # Example 2: Get online players
        print("=== Online Players ===")
        players = client.get_players()
        print(f"Players: {json.dumps(players, indent=2)}")
        print()
        
        # Example 3: Run a command
        print("=== Run Command ===")
        result = client.run_command("list")
        print(f"Command success: {result['success']}")
        print(f"Output: {result['output']}")
        print()
        
        # Example 4: Get plugins
        print("=== Installed Plugins ===")
        plugins = client.get_plugins()
        print(f"Plugins: {json.dumps(plugins, indent=2)}")
        print()
        
    except Exception as e:
        print(f"Error: {e}")


if __name__ == "__main__":
    main()
