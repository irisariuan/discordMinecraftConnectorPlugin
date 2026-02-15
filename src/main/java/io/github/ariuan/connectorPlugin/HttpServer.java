package io.github.ariuan.connectorPlugin;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fi.iki.elonen.NanoHTTPD;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public class HttpServer extends NanoHTTPD {
    private final LogCaptureHandler logCaptureHandler;
    private final Logger logger;

    public HttpServer(int port, LogCaptureHandler logCaptureHandler) throws IOException {
        super(port);
        this.logCaptureHandler = logCaptureHandler;
        this.logger = ConnectorPlugin.getInstance().getLogger();
        start(SOCKET_READ_TIMEOUT, false);
        System.out.println("HTTP Server started on port " + port);
    }

    @Override
    public Response serve(IHTTPSession session) {
        try {
            if (Method.POST.equals(session.getMethod())) {
                String uri = session.getUri();
                Map<String, String> body = new HashMap<>();
                session.parseBody(body);
                String rawBody = body.get("postData");
                JsonObject json = JsonParser.parseString(rawBody).getAsJsonObject();
                switch (uri) {
                    case "/runCommand": {
                        String command = json.get("command").getAsString();
                        if (command == null) {
                            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Bad Request, Missing command");
                        }
                        CompletableFuture<String> future = new CompletableFuture<>();

                        Bukkit.getScheduler().runTask(ConnectorPlugin.getInstance(), () -> {
                            CapturingConsoleSender sender = new CapturingConsoleSender();
                            LogCapture logCapture = new LogCapture();
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
                        String result = future.get(); // Safe because it's not the main thread
                        return newFixedLengthResponse(Response.Status.OK, "application/json", result);
                    }
                    case "/shutdown": {
                        long tickDelay = json.get("tick").getAsLong();
                        boolean successful = ConnectorPlugin.getInstance().getShutdownManager().shutdown(tickDelay, false);
                        JsonObject response = new JsonObject();
                        response.addProperty("success", successful);
                        return newFixedLengthResponse(Response.Status.OK, "application/json", response.toString());
                    }
                    case "/register": {
                        String playerName = json.get("playerName").getAsString();
                        String otp = json.get("otp").getAsString();
                        var player = ConnectorPlugin.getInstance().getServer().getPlayerExact(playerName);
                        if (player == null) {
                            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Player not found");
                        }
                        player.sendMessage(Component.text("Here's your OTP: " + otp));
                        JsonObject response = new JsonObject();
                        response.addProperty("success", true);
                        response.addProperty("uuid", player.getUniqueId().toString());
                        return newFixedLengthResponse(Response.Status.OK, "application/json", response.toString());
                    }
                    case "/registered": {
                        String uuid  = json.get("uuid").getAsString();
                        var player = ConnectorPlugin.getInstance().getServer().getPlayer(UUID.fromString(uuid));
                        if (player == null) {
                            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Player not found");
                        }
                        ConnectorPlugin.getInstance().verifyPlayer(player);
                        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "Ok");
                    }
                }
            } else if (Method.GET.equals(session.getMethod())) {
                String uri = session.getUri();
                switch (uri) {
                    case "/logs": {
                        JsonArray arr = getJsonArray();
                        logger.info("Called logs");
                        return newFixedLengthResponse(Response.Status.OK, "application/json", arr.toString());
                    }
                    case "/players": {
                        JsonArray arr = new JsonArray();
                        Bukkit.getOnlinePlayers().forEach(player -> {
                            JsonObject obj = new JsonObject();
                            obj.addProperty("uuid", player.getUniqueId().toString());
                            obj.addProperty("name", player.getName());
                            arr.add(obj);
                        });
                        return newFixedLengthResponse(Response.Status.OK, "application/json", arr.toString());
                    }
                    case "/cancelShutdown": {
                        boolean successful = ConnectorPlugin.getInstance().getShutdownManager().cancelShutdown();
                        JsonObject response = new JsonObject();
                        response.addProperty("success", successful);
                        return newFixedLengthResponse(Response.Status.OK, "application/json", response.toString());
                    }
                    case "/shutdown": {
                        boolean successful = ConnectorPlugin.getInstance().getShutdownManager().shutdown(20 * 60, false);
                        JsonObject response = new JsonObject();
                        response.addProperty("success", successful);
                        return newFixedLengthResponse(Response.Status.OK, "application/json", response.toString());
                    }
                    case "/shuttingDown": {
                        boolean shuttingDown = ConnectorPlugin.getInstance().getShutdownManager().hasScheduledShutdown();
                        JsonObject response = new JsonObject();
                        response.addProperty("result", shuttingDown);
                        return newFixedLengthResponse(Response.Status.OK, "application/json", response.toString());
                    }
                    case "/ping": {
                        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "Pong!");
                    }
                    case "/plugins": {
                        var nameList = new JsonArray();
                        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
                            nameList.add(plugin.getName());
                        }
                        JsonObject response = new JsonObject();
                        response.add("plugins", nameList);
                        return newFixedLengthResponse(Response.Status.OK, "application/json", response.toString());
                    }
                }
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found");
            }
        } catch (Exception e) {
            ConnectorPlugin.getInstance().getLogger().warning("Error writing log: " + e.getMessage());
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Internal error");
        }
        return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found");
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
}
