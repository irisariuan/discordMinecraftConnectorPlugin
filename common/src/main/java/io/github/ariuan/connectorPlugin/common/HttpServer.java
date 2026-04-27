package io.github.ariuan.connectorPlugin.common;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fi.iki.elonen.NanoHTTPD;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Platform-agnostic NanoHTTPD-based HTTP server.
 * All game interactions are delegated to the injected {@link IPlatformAdapter}.
 */
public class HttpServer extends NanoHTTPD {

    private final IPlatformAdapter adapter;

    public HttpServer(int port, IPlatformAdapter adapter) throws IOException {
        super(port);
        this.adapter = adapter;
        start(SOCKET_READ_TIMEOUT, false);
        adapter.getLogger().info("HTTP Server started on port " + port);
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
                    case "/runCommand" -> {
                        String command = json.get("command").getAsString();
                        if (command == null) {
                            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT,
                                    "Bad Request, Missing command");
                        }
                        CommandResult result = adapter.runCommandAndCapture(command);
                        JsonObject response = new JsonObject();
                        response.addProperty("success", result.success);
                        response.addProperty("output", result.output);
                        response.addProperty("logger", result.loggerOutput);
                        return newFixedLengthResponse(Response.Status.OK, "application/json",
                                new Gson().toJson(response));
                    }
                    case "/shutdown" -> {
                        long tickDelay = json.get("tick").getAsLong();
                        boolean successful = adapter.scheduleShutdown(tickDelay, false);
                        JsonObject response = new JsonObject();
                        response.addProperty("success", successful);
                        return newFixedLengthResponse(Response.Status.OK, "application/json", response.toString());
                    }
                    case "/register" -> {
                        String playerName = json.has("playerName") && !json.get("playerName").isJsonNull()
                                ? json.get("playerName").getAsString() : null;
                        String uuid = json.has("uuid") && !json.get("uuid").isJsonNull()
                                ? json.get("uuid").getAsString() : null;
                        String otp = json.get("otp").getAsString();

                        IPlayerInfo player = playerName != null
                                ? adapter.getPlayerByName(playerName)
                                : (uuid != null ? adapter.getPlayerByUUID(UUID.fromString(uuid)) : null);
                        if (player == null) {
                            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT,
                                    "Player not found");
                        }
                        adapter.sendMessageToPlayer(player.getUUID(), "Here's your OTP: " + otp);
                        JsonObject response = new JsonObject();
                        response.addProperty("success", true);
                        response.addProperty("uuid", player.getUUID().toString());
                        return newFixedLengthResponse(Response.Status.OK, "application/json", response.toString());
                    }
                    case "/registered" -> {
                        String uuid = json.get("uuid").getAsString();
                        IPlayerInfo player = adapter.getPlayerByUUID(UUID.fromString(uuid));
                        if (player == null) {
                            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT,
                                    "Player not found");
                        }
                        adapter.markPlayerVerified(player.getUUID());
                        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "Ok");
                    }
                }

            } else if (Method.GET.equals(session.getMethod())) {
                String uri = session.getUri();
                switch (uri) {
                    case "/logs": {
                        JsonArray arr = buildLogsArray();
                        adapter.getLogger().info("Called logs");
                        return newFixedLengthResponse(Response.Status.OK, "application/json", arr.toString());
                    }
                    case "/players": {
                        JsonArray arr = new JsonArray();
                        adapter.getOnlinePlayers().forEach(player -> {
                            JsonObject obj = new JsonObject();
                            obj.addProperty("uuid", player.getUUID().toString());
                            obj.addProperty("name", player.getName());
                            arr.add(obj);
                        });
                        return newFixedLengthResponse(Response.Status.OK, "application/json", arr.toString());
                    }
                    case "/cancelShutdown": {
                        boolean successful = adapter.cancelShutdown();
                        JsonObject response = new JsonObject();
                        response.addProperty("success", successful);
                        return newFixedLengthResponse(Response.Status.OK, "application/json", response.toString());
                    }
                    case "/shutdown": {
                        boolean successful = adapter.scheduleShutdown(20 * 60, false);
                        JsonObject response = new JsonObject();
                        response.addProperty("success", successful);
                        return newFixedLengthResponse(Response.Status.OK, "application/json", response.toString());
                    }
                    case "/shuttingDown": {
                        boolean shuttingDown = adapter.hasScheduledShutdown();
                        JsonObject response = new JsonObject();
                        response.addProperty("result", shuttingDown);
                        return newFixedLengthResponse(Response.Status.OK, "application/json", response.toString());
                    }
                    case "/ping": {
                        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "Pong!");
                    }
                    case "/plugins": {
                        JsonArray nameList = new JsonArray();
                        adapter.getLoadedComponents().forEach(nameList::add);
                        JsonObject response = new JsonObject();
                        response.add("plugins", nameList);
                        return newFixedLengthResponse(Response.Status.OK, "application/json", response.toString());
                    }
                }
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found");
            }
        } catch (Exception e) {
            adapter.getLogger().warning("Error handling HTTP request: " + e.getMessage());
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Internal error");
        }
        return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found");
    }

    private @NotNull JsonArray buildLogsArray() {
        LogCaptureHandler.LogEntry[] entries = adapter.getLogCaptureHandler().getRecentLogs();
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
