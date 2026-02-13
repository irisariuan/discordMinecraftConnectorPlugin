package io.github.ariuan.connectorPlugin.example;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Example IPC client for communicating with the Minecraft plugin's IPC server.
 * This demonstrates how to send requests to the plugin using Unix Domain Sockets.
 */
public class IPCClientExample {

    public static void main(String[] args) {
        // Example: Connect to the IPC server and send a ping request
        Path socketPath = Paths.get("/path/to/plugins/DiscordConnectorPlugin/minecraft-ipc.sock");
        
        try {
            // Example 1: Ping the server
            String response = sendRequest(socketPath, "GET", "/ping", "");
            System.out.println("Ping response: " + response);
            
            // Example 2: Get online players
            String playersResponse = sendRequest(socketPath, "GET", "/players", "");
            System.out.println("Players response: " + playersResponse);
            
            // Example 3: Run a command
            JsonObject commandRequest = new JsonObject();
            commandRequest.addProperty("command", "list");
            String commandResponse = sendRequest(socketPath, "POST", "/runCommand", commandRequest.toString());
            System.out.println("Command response: " + commandResponse);
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Sends a request to the IPC server and returns the response.
     * 
     * @param socketPath Path to the Unix Domain Socket
     * @param method HTTP-like method (GET, POST)
     * @param path Endpoint path (e.g., "/ping", "/players")
     * @param body Request body (JSON for POST requests)
     * @return Response from the server
     * @throws IOException if communication fails
     */
    public static String sendRequest(Path socketPath, String method, String path, String body) throws IOException {
        UnixDomainSocketAddress address = UnixDomainSocketAddress.of(socketPath);
        
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(address);
            
            // Build request in REST-like format: METHOD /path\nBODY
            String request = method + " " + path;
            if (!body.isEmpty()) {
                request += "\n" + body;
            }
            
            // Send request
            ByteBuffer buffer = ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8));
            channel.write(buffer);
            
            // Read response
            buffer = ByteBuffer.allocate(8192);
            int bytesRead = channel.read(buffer);
            
            if (bytesRead == -1) {
                throw new IOException("Connection closed by server");
            }
            
            buffer.flip();
            String response = StandardCharsets.UTF_8.decode(buffer).toString();
            
            // Parse response format: STATUS_CODE\nBODY
            String[] parts = response.split("\n", 2);
            int statusCode = Integer.parseInt(parts[0]);
            String responseBody = parts.length > 1 ? parts[1] : "";
            
            if (statusCode != 200) {
                throw new IOException("Server returned error: " + statusCode + " - " + responseBody);
            }
            
            return responseBody;
        }
    }

    /**
     * Example method to run a Minecraft command via IPC.
     */
    public static String runCommand(Path socketPath, String command) throws IOException {
        JsonObject request = new JsonObject();
        request.addProperty("command", command);
        
        String response = sendRequest(socketPath, "POST", "/runCommand", request.toString());
        JsonObject responseJson = JsonParser.parseString(response).getAsJsonObject();
        
        boolean success = responseJson.get("success").getAsBoolean();
        String output = responseJson.get("output").getAsString();
        
        return "Command " + (success ? "succeeded" : "failed") + ": " + output;
    }

    /**
     * Example method to get online players.
     */
    public static String getOnlinePlayers(Path socketPath) throws IOException {
        String response = sendRequest(socketPath, "GET", "/players", "");
        return response;
    }

    /**
     * Example method to shutdown the server.
     */
    public static boolean shutdownServer(Path socketPath, long delayTicks) throws IOException {
        JsonObject request = new JsonObject();
        request.addProperty("tick", delayTicks);
        
        String response = sendRequest(socketPath, "POST", "/shutdown", request.toString());
        JsonObject responseJson = JsonParser.parseString(response).getAsJsonObject();
        
        return responseJson.get("success").getAsBoolean();
    }
}
