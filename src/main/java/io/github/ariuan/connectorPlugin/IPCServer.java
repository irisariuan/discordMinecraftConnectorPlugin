package io.github.ariuan.connectorPlugin;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

public class IPCServer {
    private final LogCaptureHandler logCaptureHandler;
    private final Path socketPath;
    private ServerSocketChannel serverChannel;
    private final ExecutorService executorService;
    private volatile boolean running;

    public IPCServer(Path socketPath, LogCaptureHandler logCaptureHandler) throws IOException {
        this.socketPath = socketPath;
        this.logCaptureHandler = logCaptureHandler;
        this.executorService = Executors.newCachedThreadPool();
        this.running = true;
        
        // Delete existing socket file if it exists
        Files.deleteIfExists(socketPath);
        
        // Create Unix Domain Socket server
        UnixDomainSocketAddress address = UnixDomainSocketAddress.of(socketPath);
        serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        serverChannel.bind(address);
        
        System.out.println("IPC Server started on socket: " + socketPath);
        
        // Start accepting connections
        executorService.submit(this::acceptConnections);
    }

    private void acceptConnections() {
        while (running) {
            try {
                SocketChannel clientChannel = serverChannel.accept();
                executorService.submit(() -> handleClient(clientChannel));
            } catch (IOException e) {
                if (running) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void handleClient(SocketChannel clientChannel) {
        try {
            // Read the request
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            int bytesRead = clientChannel.read(buffer);
            
            if (bytesRead == -1) {
                clientChannel.close();
                return;
            }
            
            buffer.flip();
            String request = StandardCharsets.UTF_8.decode(buffer).toString();
            
            // Parse the request (REST-like format: METHOD /path JSON_BODY)
            String[] parts = request.split("\n", 2);
            if (parts.length < 1) {
                sendResponse(clientChannel, 400, "Bad Request");
                return;
            }
            
            String[] requestLine = parts[0].trim().split(" ", 2);
            if (requestLine.length < 2) {
                sendResponse(clientChannel, 400, "Bad Request");
                return;
            }
            
            String method = requestLine[0];
            String path = requestLine[1];
            String body = parts.length > 1 ? parts[1].trim() : "";
            
            // Process the request and send response
            String response = processRequest(method, path, body);
            sendResponse(clientChannel, 200, response);
            
        } catch (Exception e) {
            try {
                sendResponse(clientChannel, 500, "{\"error\": \"Internal server error\"}");
            } catch (IOException ex) {
                ex.printStackTrace();
            }
            e.printStackTrace();
        } finally {
            try {
                clientChannel.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void sendResponse(SocketChannel channel, int statusCode, String body) throws IOException {
        String response = statusCode + "\n" + body;
        ByteBuffer buffer = ByteBuffer.wrap(response.getBytes(StandardCharsets.UTF_8));
        channel.write(buffer);
    }

    private String processRequest(String method, String path, String body) {
        try {
            if ("POST".equals(method)) {
                JsonObject json = body.isEmpty() ? new JsonObject() : JsonParser.parseString(body).getAsJsonObject();
                switch (path) {
                    case "/runCommand": {
                        String command = json.get("command").getAsString();
                        Bukkit.getLogger().info("Called command: " + command);
                        if (command == null) {
                            return "{\"error\": \"Missing command\"}";
                        }
                        CompletableFuture<String> future = new CompletableFuture<>();

                        Bukkit.getScheduler().runTask(ConnectorPlugin.getInstance(), () -> {
                            CapturingConsoleSender sender = new CapturingConsoleSender();
                            LogCapture logCapture = new LogCapture();
                            Logger logger = Bukkit.getLogger();
                            logger.addHandler(logCapture);
                            boolean success = Bukkit.dispatchCommand(sender, command);

                            // Some commands log later, so wait 2 ticks
                            Bukkit.getScheduler().runTaskLater(ConnectorPlugin.getInstance(), () -> {
                                String output = sender.getOutput();
                                String loggerOutput = logCapture.getCapturedOutput();
                                logger.removeHandler(logCapture);
                                JsonObject response = new JsonObject();
                                response.addProperty("success", success);
                                response.addProperty("output", output.trim());
                                response.addProperty("logger", loggerOutput.trim());
                                future.complete(new Gson().toJson(response));
                            }, 2L);
                        });
                        
                        try {
                            return future.get(); // Safe because it's not the main thread
                        } catch (Exception e) {
                            e.printStackTrace();
                            return "{\"error\": \"Command execution failed\"}";
                        }
                    }
                    case "/shutdown": {
                        long tickDelay = json.get("tick").getAsLong();
                        boolean successful = ConnectorPlugin.getInstance().shutdown(tickDelay);
                        JsonObject response = new JsonObject();
                        response.addProperty("success", successful);
                        return response.toString();
                    }
                }
            } else if ("GET".equals(method)) {
                switch (path) {
                    case "/logs": {
                        JsonArray arr = getJsonArray();
                        Bukkit.getLogger().info("Called logs");
                        return arr.toString();
                    }
                    case "/players": {
                        JsonArray arr = new JsonArray();
                        Bukkit.getOnlinePlayers().forEach(player -> {
                            JsonObject obj = new JsonObject();
                            obj.addProperty("uuid", player.getUniqueId().toString());
                            obj.addProperty("name", player.getName());
                            arr.add(obj);
                        });
                        return arr.toString();
                    }
                    case "/cancelShutdown": {
                        boolean successful = ConnectorPlugin.getInstance().cancelShutdown();
                        JsonObject response = new JsonObject();
                        response.addProperty("success", successful);
                        return response.toString();
                    }
                    case "/shutdown": {
                        boolean successful = ConnectorPlugin.getInstance().shutdown(20 * 60);
                        JsonObject response = new JsonObject();
                        response.addProperty("success", successful);
                        return response.toString();
                    }
                    case "/shuttingDown": {
                        boolean shuttingDown = ConnectorPlugin.getInstance().haveScheduledShutdown();
                        JsonObject response = new JsonObject();
                        response.addProperty("result", shuttingDown);
                        return response.toString();
                    }
                    case "/ping": {
                        return "Pong!";
                    }
                    case "/plugins": {
                        var nameList = new JsonArray();
                        for (Plugin plugin: Bukkit.getPluginManager().getPlugins()) {
                            nameList.add(plugin.getName());
                        }
                        JsonObject response = new JsonObject();
                        response.add("plugins", nameList);
                        return response.toString();
                    }
                }
                return "{\"error\": \"Not found\"}";
            }
        } catch (Exception e) {
            e.printStackTrace();
            return "{\"error\": \"Internal error\"}";
        }
        return "{\"error\": \"Not found\"}";
    }

    private @NotNull JsonArray getJsonArray() {
        LogCaptureHandler.LogEntry[] entries = logCaptureHandler.getRecentLogs();
        JsonArray arr = new JsonArray();
        for (LogCaptureHandler.LogEntry entry : entries) {
            JsonObject json = new JsonObject();
            json.addProperty("timestamp", entry.timestamp);
            json.addProperty("message", entry.message);
            json.addProperty("type", "server");
            arr.add(json);
        }
        return arr;
    }

    public void stop() {
        running = false;
        try {
            if (serverChannel != null && serverChannel.isOpen()) {
                serverChannel.close();
            }
            executorService.shutdown();
            Files.deleteIfExists(socketPath);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
